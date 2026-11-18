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
 * [ConversationDetector] that reports a conversation when the share of speech in a sliding window reaches a threshold.
 * @param context used to load Silero
 * @param source audio input
 * @param settings detection settings
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

    @Volatile
    override var debugTimeline: Boolean = false
    private val debug = DebugTimeline()
    private var lastStateForDebug = ConversationState.NO_SPEECH

    /** Starts the detection thread. */
    @Synchronized
    override fun start() {
        if (thread != null) return
        thread = Thread(::run, "SpeechActivityDetector").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stops the detection thread and waits for it.
     * @return true if the thread ended
     */
    @Synchronized
    override fun stop(): Boolean {
        val t = thread
        thread = null
        t?.interrupt()
        // Two AudioRecords on Android 9 give silence, so wait until the microphone is released.
        runCatching { t?.join(STOP_TIMEOUT_MS) }
        return t == null || !t.isAlive
    }

    /** The detector thread: gates every 10 ms hop and keeps only the share of speech in the window. */
    private fun run() {
        var vad: SileroVad? = null
        val read = ShortArray(HOP)
        val hop = ShortArray(HOP)
        val chunk = ShortArray(512)
        var chunkFill = 0
        var probability = 0.0
        val speaking = RobotSpeaking(context)
        var wasRobotTalking = false
        val speechGate = SpeechGateState(settings)
        val load = AudioLoad(TAG, "speech activity, %.0f %% of the last %.0f s, gaps up to %d ms count, ".format(
            settings.activityShare * 100, settings.activityWindowMs / 1000.0, settings.activityMinSilenceMs) +
            "confirm twice %b, gate %s, silero %.2f (hysteresis %.2f), floor %.0f dBFS".format(
                settings.confirmTwice, settings.gateMode, settings.speechThreshold, settings.speechHysteresis,
                settings.minLevelDb))

        val window = BooleanArray((settings.activityWindowMs / (HOP * 1000L / 16_000)).toInt().coerceAtLeast(1))
        var writeAt = 0
        var filled = 0
        var speechInWindow = 0
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L
        var wasOverThreshold = false
        var speechUntilMs = Long.MIN_VALUE / 2
        var aboveSince = Long.MIN_VALUE / 2
        var collected = false
        val silence = SilenceWindow(settings.resetAfterSilenceMs, settings.resetMaxSpeechShare)

        try {
            val openError = source.open()
            if (openError != null) {
                _snapshot.value = _snapshot.value.copy(running = false, error = openError)
                return
            }
            if (settings.gateMode != SpeechGateMode.LEVEL_ONLY) vad = SileroVad(context)
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            Log.i(TAG, "gate: %s, silero %.2f (hysteresis %.2f), level floor %.0f dBFS".format(
                settings.gateMode, settings.speechThreshold, settings.speechHysteresis, settings.minLevelDb))
            Log.i(TAG, "counting speech activity (%.0f %% of the last %.0f s, gaps up to %d ms still count, gate %s)".format(
                settings.activityShare * 100, settings.activityWindowMs / 1000.0, settings.activityMinSilenceMs,
                settings.gateMode))

            while (!Thread.currentThread().isInterrupted) {
                if (!readHop(source, read, hop)) {
                    _snapshot.value = _snapshot.value.copy(running = false, error = "microphone stopped delivering audio")
                    return
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

                val gate = speechGate.decide(probability, levelDb)
                val sileroSaysSpeech = speechGate.silero
                val loudEnough = speechGate.loud
                val robotTalking = speaking.active(timeMs)
                if (robotTalking != wasRobotTalking) {
                    Log.i(TAG, if (robotTalking) "robot is speaking: ignoring the microphone until it stops"
                    else "robot stopped speaking: listening again")
                    wasRobotTalking = robotTalking
                }
                load.frame(!robotTalking && gate, sileroSaysSpeech, loudEnough, levelDb, probability)

                if (robotTalking) speechUntilMs = Long.MIN_VALUE / 2
                else if (gate) speechUntilMs = timeMs + settings.activityMinSilenceMs
                val counts = !robotTalking && (gate || timeMs <= speechUntilMs)
                debug.frame(debugTimeline, AudioFrame(timeMs, levelDb, if (vad != null) probability else null, counts,
                    sileroSaysSpeech, loudEnough, robotTalking))

                if (!robotTalking) {
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

                if (silence.add(counts) && collected) {
                    silence.clear()
                    window.fill(false)
                    writeAt = 0; filled = 0; speechInWindow = 0; speechSamples = 0
                    wasOverThreshold = false
                    collected = false
                    speechUntilMs = Long.MIN_VALUE / 2
                    aboveSince = Long.MIN_VALUE / 2
                    vad?.reset()
                    speechGate.reset()
                    Log.i(TAG, "reset after ${settings.resetAfterSilenceMs} ms of silence")
                    debug.event(debugTimeline, ConversationEvent(timeMs, ConversationEventKind.RESET))
                }

                val share = if (filled > 0) speechInWindow.toDouble() / filled else 0.0
                val full = filled == window.size
                val above = full && share >= settings.activityShare
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
                    val state = when {
                        !speechRecently -> ConversationState.NO_SPEECH
                        over -> ConversationState.MULTIPLE_SPEAKERS
                        !full -> ConversationState.LISTENING
                        else -> ConversationState.ONE_SPEAKER
                    }
                    if (state == ConversationState.MULTIPLE_SPEAKERS && lastStateForDebug != state) {
                        debug.event(debugTimeline, ConversationEvent(timeMs, ConversationEventKind.MULTIPLE))
                    }
                    lastStateForDebug = state
                    _snapshot.value = ConversationSnapshot(
                        state = state,
                        timeline = debug.frames(debugTimeline),
                        events = debug.events(debugTimeline),
                        sileroThreshold = if (vad != null) settings.speechThreshold else null,
                        sileroRelease = if (vad != null) settings.sileroRelease else null,
                        levelFloorDb = if (settings.gateMode != SpeechGateMode.SILERO_ONLY) settings.minLevelDb else null,
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

        const val STOP_TIMEOUT_MS = 1_000L

        const val CONFIRM_MS = 1_000L
    }
}
