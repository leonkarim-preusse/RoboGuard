package com.example.robocontrol.conversation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * [ConversationDetector] that only counts how much of the last seconds carried speech at all.
 *
 * The idea behind it (owner, 2026-09-28): one person talking leaves gaps — to breathe, to think, to read what is on the
 * screen. A conversation fills those gaps, because somebody answers into them. So a window in which almost everything is
 * speech is evidence of more than one speaker, without ever asking WHO is speaking.
 *
 * It is by far the cheapest of the four methods: no cepstra, no Gaussians, no embeddings, no template. The only state is
 * a ring of booleans over [ConversationSettings.activityWindowMs] and the count of the ones that are true. Nothing that
 * describes a voice is computed, so there is nothing to store, to leak or to delete — the strongest privacy position of
 * the four, and worth stating as such.
 *
 * Its weakness is the mirror image: a television, a radio or one person reading aloud fills the window just as well, and
 * the detector cannot tell them from a conversation. That is a false alarm the robot answers by ASKING, which is exactly
 * what the rest of the design does with every other uncertain signal.
 *
 * While the robot speaks itself the window does not advance at all (see [RobotSpeaking]): its own voice neither counts
 * as speech nor dilutes the share.
 */
class SpeechActivityDetector(
    private val context: Context,
    private val source: PcmSource = AndroidMicSource(),
    private val settings: ConversationSettings = ConversationSettings()
) : ConversationDetector {

    private val _snapshot = MutableStateFlow(ConversationSnapshot(method = DetectionMethod.ACTIVITY))
    override val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var thread: Thread? = null

    @Synchronized
    override fun start() {
        if (thread != null) return
        thread = Thread(::run, "SpeechActivityDetector").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun stop() {
        val t = thread
        thread = null
        t?.interrupt()
        // Wait for the thread to close the microphone. The next detector opens it immediately afterwards, and two
        // AudioRecords on the same device give silence on Android 9 rather than an error.
        runCatching { t?.join(STOP_TIMEOUT_MS) }
    }

    private fun run() {
        var vad: SileroVad? = null
        val read = ShortArray(HOP)
        val hop = ShortArray(HOP)
        val chunk = ShortArray(512)
        var chunkFill = 0
        var probability = 0.0
        val speaking = RobotSpeaking(context)
        var wasRobotTalking = false
        var sileroWasSpeech = false
        val load = AudioLoad(TAG, "speech activity, %.0f %% of the last %.0f s, gaps up to %d ms count, ".format(
            settings.activityShare * 100, settings.activityWindowMs / 1000.0, settings.activityMinSilenceMs) +
            "confirm twice %b, gate %s, silero %.2f (hysteresis %.2f), floor %.0f dBFS".format(
                settings.confirmTwice, settings.gateMode, settings.speechThreshold, settings.speechHysteresis,
                settings.minLevelDb))

        // The window itself: one flag per 10 ms hop, plus the running count of the flags that are true.
        val window = BooleanArray((settings.activityWindowMs / (HOP * 1000L / 16_000)).toInt().coerceAtLeast(1))
        var writeAt = 0
        var filled = 0
        var speechInWindow = 0
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L
        var wasOverThreshold = false
        // Hangover: until this moment a frame still counts as speech although the gate says no (see activityMinSilenceMs).
        var speechUntilMs = Long.MIN_VALUE / 2
        // Outlier filter (settings.confirmTwice): since when the share has been above the threshold without a break.
        var aboveSince = Long.MIN_VALUE / 2
        // True while the window holds nothing that was collected since the last reset. Without it the reset fires again on
        // the very next frame — the window refills to one frame, `filled > 0` is true again, and the silence clock has not
        // moved — which spins the loop and floods the log (seen on the robot 2026-09-28: 12 158 lines in half a minute,
        // which starved the audio thread badly enough to lose microphone data).
        var collected = false

        try {
            if (settings.gateMode != SpeechGateMode.LEVEL_ONLY) vad = SileroVad(context)
            val openError = source.open()
            if (openError != null) {
                _snapshot.value = _snapshot.value.copy(running = false, error = openError)
                return
            }
            // Audio that is not read in time is gone for good, unlike a camera frame: this loop outranks the detection
            // workers (which run at THREAD_PRIORITY_URGENT_DISPLAY, -8).
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            // One line the graphs can read the threshold lines from (performance/graphs.py).
            Log.i(TAG, "gate: %s, silero %.2f (hysteresis %.2f), level floor %.0f dBFS".format(
                settings.gateMode, settings.speechThreshold, settings.speechHysteresis, settings.minLevelDb))
            Log.i(TAG, "counting speech activity (%.0f %% of the last %.0f s, gaps up to %d ms still count, gate %s)".format(
                settings.activityShare * 100, settings.activityWindowMs / 1000.0, settings.activityMinSilenceMs,
                settings.gateMode))

            while (!Thread.currentThread().isInterrupted) {
                var got = 0
                while (got < HOP) {
                    val n = source.read(read, HOP - got)
                    if (n < 0) return
                    read.copyInto(hop, got, 0, n)
                    got += n
                }
                samples += HOP
                val timeMs = samples * 1000 / source.sampleRate

                var i = 0
                while (i < HOP) {
                    val take = minOf(HOP - i, chunk.size - chunkFill)
                    hop.copyInto(chunk, chunkFill, i, i + take)
                    chunkFill += take
                    i += take
                    if (chunkFill == chunk.size) {
                        vad?.let { v ->
                            val vadStart = System.nanoTime()
                            probability = v.probability(chunk).toDouble()
                            load.vad(System.nanoTime() - vadStart)
                        }
                        chunkFill = 0
                    }
                }
                var sum = 0.0
                for (k in 0 until HOP) {
                    val v = hop[k] / 32768f
                    sum += v * v
                }
                val levelDb = 20 * log10(max(sqrt(sum / HOP), 1e-9))

                // Hysteresis: while speech is running the bar is lower, so a probability sitting on the threshold does
                // not flip the gate every chunk.
                val sileroLimit = if (sileroWasSpeech) settings.speechThreshold - settings.speechHysteresis
                else settings.speechThreshold
                val sileroSaysSpeech = probability >= sileroLimit
                sileroWasSpeech = sileroSaysSpeech
                val loudEnough = levelDb > settings.minLevelDb
                val gate = when (settings.gateMode) {
                    SpeechGateMode.SILERO_AND_LEVEL -> sileroSaysSpeech && loudEnough
                    SpeechGateMode.SILERO_ONLY -> sileroSaysSpeech
                    SpeechGateMode.LEVEL_ONLY -> loudEnough
                }
                val robotTalking = speaking.active()
                if (robotTalking != wasRobotTalking) {
                    Log.i(TAG, if (robotTalking) "robot is speaking: ignoring the microphone until it stops"
                    else "robot stopped speaking: listening again")
                    wasRobotTalking = robotTalking
                }
                load.frame(!robotTalking && gate, sileroSaysSpeech, loudEnough, levelDb, probability)

                // A gap shorter than the hangover is part of the same utterance: without this the share would measure
                // the pauses between WORDS, which one speaker has just as much as two.
                if (gate) speechUntilMs = timeMs + settings.activityMinSilenceMs
                val counts = gate || timeMs <= speechUntilMs

                if (!robotTalking) {
                    // Advance the ring by one frame: forget the oldest, remember this one.
                    if (filled == window.size && window[writeAt]) speechInWindow--
                    window[writeAt] = counts
                    if (counts) speechInWindow++
                    writeAt = (writeAt + 1) % window.size
                    if (filled < window.size) filled++
                    if (counts) {
                        lastSpeechAtMs = timeMs
                        speechSamples += HOP
                        collected = true
                    }
                }

                // A long silence ends the conversation: the window starts empty again, like the other methods' reset.
                if (collected && timeMs - lastSpeechAtMs >= settings.resetAfterSilenceMs) {
                    window.fill(false)
                    writeAt = 0; filled = 0; speechInWindow = 0; speechSamples = 0
                    wasOverThreshold = false
                    collected = false
                    speechUntilMs = Long.MIN_VALUE / 2
                    aboveSince = Long.MIN_VALUE / 2
                    vad?.reset()
                    Log.i(TAG, "reset after ${settings.resetAfterSilenceMs} ms of silence")
                }

                val share = if (filled > 0) speechInWindow.toDouble() / filled else 0.0
                val full = filled == window.size
                val above = full && share >= settings.activityShare
                // Outlier filter: the crossing has to still hold one second later. The share is continuous here, so
                // "said twice" means "seen on two checks a second apart" rather than "two pieces".
                if (!above) aboveSince = Long.MIN_VALUE / 2
                else if (aboveSince < 0) aboveSince = timeMs
                val over = above && (!settings.confirmTwice || timeMs - aboveSince >= CONFIRM_MS)
                if (over != wasOverThreshold) {
                    Log.i(TAG, "speech share %.0f %% of the last %.0f s -> %s".format(
                        share * 100, settings.activityWindowMs / 1000.0,
                        if (over) "more than one speaker" else "one speaker again"))
                    wasOverThreshold = over
                }
                load.tick(timeMs)

                if (timeMs - lastPublish >= PUBLISH_MS) {
                    lastPublish = timeMs
                    val speechRecently = timeMs - lastSpeechAtMs <= settings.speechHoldMs
                    _snapshot.value = ConversationSnapshot(
                        state = when {
                            !speechRecently -> ConversationState.NO_SPEECH
                            over -> ConversationState.MULTIPLE_SPEAKERS
                            !full -> ConversationState.LISTENING
                            else -> ConversationState.ONE_SPEAKER
                        },
                        running = true,
                        levelDb = levelDb,
                        speechNow = !robotTalking && counts,
                        speechProbability = if (vad != null) probability else null,
                        speechGate = if (vad != null) SpeechGate.SILERO else SpeechGate.LOUDNESS,
                        speechSeconds = speechSamples / 16_000.0,
                        method = DetectionMethod.ACTIVITY,
                        speechShare = share,
                        speechShareNeeded = settings.activityShare.toDouble(),
                        windowFilled = filled.toDouble() / window.size,
                        timeMs = timeMs
                    )
                }
            }
        } catch (_: InterruptedException) {
            // stop() while waiting for audio: a normal end
        } catch (e: Exception) {
            Log.e(TAG, "detector stopped", e)
            _snapshot.value = _snapshot.value.copy(error = e.message ?: e.toString())
        } finally {
            read.fill(0)
            hop.fill(0)
            chunk.fill(0)
            window.fill(false)
            runCatching { vad?.close() }
            runCatching { source.close() }
            _snapshot.value = _snapshot.value.copy(running = false)
            thread = null
        }
    }

    private companion object {
        const val TAG = "SpeechActivity"
        const val HOP = 160
        const val PUBLISH_MS = 100L

        /** How long stop() waits for the thread to let go of the microphone. */
        const val STOP_TIMEOUT_MS = 1_000L

        /** How long the share has to stay above the threshold while the outlier filter is on. */
        const val CONFIRM_MS = 1_000L
    }
}
