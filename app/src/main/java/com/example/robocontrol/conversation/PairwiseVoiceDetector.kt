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
 * Experimental [ConversationDetector]: compares the pieces of speech **with each other**.
 *
 * Every [ConversationSettings.pieceSeconds] of speech becomes one embedding, which is compared with the pieces already in
 * the window. Two pieces that are further apart than [ConversationSettings.differentVoiceBelow] come from two different
 * voices, and that is a conversation — whoever they are. No voice has to be taught, nobody is recognised, and the robot
 * cannot say WHO is talking, only that it is more than one person.
 *
 * Why this can work where an absolute threshold struggles: the microphone and the room add the same large component to
 * every embedding, which lifts all similarities together. A comparison between two pieces recorded seconds apart shares
 * that component on both sides, so it cancels — the difference that remains is the speaker.
 *
 * The honest cost, and it belongs in the thesis: a vector is computed for everybody who talks, and up to
 * [ConversationSettings.piecesKept] of them exist side by side for the length of the window. They live in memory only,
 * are never written, never logged (only the resulting distances are), and are zeroed when the window resets, the
 * conversation ends after [ConversationSettings.resetAfterSilenceMs] of silence, or the detector stops.
 *
 * A stored owner voice is used only if one exists, and only to CENTRE the comparison (its cohort mean); the decision
 * itself never involves the owner.
 */
class PairwiseVoiceDetector(
    private val context: Context,
    private val source: PcmSource = AndroidMicSource(),
    private val settings: ConversationSettings = ConversationSettings()
) : ConversationDetector {

    private val _snapshot = MutableStateFlow(ConversationSnapshot(method = DetectionMethod.PAIRWISE))
    override val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var thread: Thread? = null

    @Synchronized
    override fun start() {
        if (thread != null) return
        thread = Thread(::run, "PairwiseVoiceDetector").apply {
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

    /** One piece of speech: its embedding and when it was heard. */
    private class Piece(val embedding: FloatArray, val timeMs: Long)

    private fun run() {
        val cohort = OwnerVoiceprintStore.get(context).loadCohort()
        var vad: SileroVad? = null
        var embedder: SpeakerEmbedder? = null
        val piece = FloatArray((settings.pieceSeconds * 16_000).toInt())
        var fill = 0
        val read = ShortArray(HOP)
        val hop = ShortArray(HOP)
        val chunk = ShortArray(512)
        var chunkFill = 0
        var probability = 0.0
        val speaking = RobotSpeaking(context)
        var wasRobotTalking = false
        var sileroWasSpeech = false
        // Continuous pieces: see ConversationSettings.continuousPieces (same assembly as the owner detector).
        val preRollHops = ((settings.speechPadMs / 10).toInt()).coerceAtLeast(1)
        val preRoll = ShortArray(preRollHops * HOP)
        var preRollAt = 0
        var preRollFilled = 0
        var wasAppending = false
        var lastGateMs = Long.MIN_VALUE / 2
        val held = ArrayDeque<Piece>()
        var readings = emptyList<Float>()
        // Outlier filter (settings.confirmTwice): whether the last counted piece already said "another voice", and
        // whether one is waiting to be said a second time.
        var wasDifferent = false
        var pendingDifferent = false
        var differentUntil = Long.MIN_VALUE / 2
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L
        // Load counters for the performance recording (numbers only, see AudioLoad).
        val load = AudioLoad(TAG, "compare pieces, another voice below %.2f, %d pieces held, window %d s, ".format(
            settings.differentVoiceBelow, settings.piecesKept, settings.windowMs / 1000) +
            "%s, confirm twice %b, pieces %s, gate %s, silero %.2f (hysteresis %.2f), floor %.0f dBFS".format(
                if (cohort != null) "cohort centred" else "no cohort", settings.confirmTwice,
                if (settings.continuousPieces) "continuous (+%d ms pad, %d ms gaps kept)".format(
                    settings.speechPadMs, settings.bridgeGapMs) else "gated frames only",
                settings.gateMode, settings.speechThreshold, settings.speechHysteresis, settings.minLevelDb))

        /** Forgets every held voice. */
        fun forget() {
            held.forEach { it.embedding.fill(0f) }
            held.clear()
        }

        try {
            vad = SileroVad(context)
            embedder = SpeakerEmbedder(context)
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
            Log.i(TAG, "comparing voices with each other (different below %.2f, %d pieces held, %s)".format(
                settings.differentVoiceBelow, settings.piecesKept,
                if (cohort != null) "centred" else "not centred — record other voices for a better comparison"))

            while (!Thread.currentThread().isInterrupted) {
                var filled = 0
                while (filled < HOP) {
                    val n = source.read(read, HOP - filled)
                    if (n < 0) return
                    read.copyInto(hop, filled, 0, n)
                    filled += n
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
                        if (settings.gateMode != SpeechGateMode.LEVEL_ONLY) {
                            val vadStart = System.nanoTime()
                            probability = vad.probability(chunk).toDouble()
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
                // The robot's own voice comes back through its microphone and is not the owner's, so it would read as
                // another speaker: while it plays anything, nothing counts as speech and a half-collected piece is thrown
                // away rather than finished with the robot's voice in it.
                val robotTalking = speaking.active()
                if (robotTalking) {
                    if (!wasRobotTalking) Log.i(TAG, "robot is speaking: ignoring the microphone until it stops")
                    if (fill > 0) {
                        fill = 0
                        piece.fill(0f)
                    }
                } else if (wasRobotTalking) {
                    Log.i(TAG, "robot stopped speaking: listening again")
                }
                wasRobotTalking = robotTalking
                // The two halves of the gate are kept apart: which of them is doing the work can then be counted, and
                // either one can be switched off from the speaker screen.
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
                val speech = !robotTalking && gate
                load.frame(speech, sileroSaysSpeech, loudEnough, levelDb, probability)

                if (speech) {
                    lastSpeechAtMs = timeMs
                    speechSamples += HOP
                    lastGateMs = timeMs
                }
                val appending = if (settings.continuousPieces) {
                    !robotTalking && (speech || timeMs - lastGateMs <= settings.bridgeGapMs)
                } else speech
                if (appending) {
                    if (!wasAppending) {
                        for (n in 0 until preRollFilled) {
                            val start = ((preRollAt - preRollFilled + n + preRollHops) % preRollHops) * HOP
                            for (k in 0 until HOP) if (fill < piece.size) piece[fill++] = preRoll[start + k] / 32768f
                        }
                    }
                    for (k in 0 until HOP) if (fill < piece.size) piece[fill++] = hop[k] / 32768f
                }
                wasAppending = appending
                if (settings.continuousPieces) {
                    hop.copyInto(preRoll, preRollAt * HOP)
                    preRollAt = (preRollAt + 1) % preRollHops
                    if (preRollFilled < preRollHops) preRollFilled++
                }
                // A half-filled piece has to be thrown away too, not only the held ones — see OwnerVoiceDetector.
                if (!speech && timeMs - lastSpeechAtMs >= settings.resetAfterSilenceMs && (fill > 0 || held.isNotEmpty())) {
                    forget()
                    readings = emptyList()
                    wasDifferent = false
                    pendingDifferent = false
                    speechSamples = 0
                    fill = 0
                    piece.fill(0f)
                    vad.reset()
                    Log.i(TAG, "reset after ${settings.resetAfterSilenceMs} ms of silence")
                }

                if (fill >= piece.size) {
                    val embedStart = System.nanoTime()
                    val embedding = embedder.embed(piece, fill)
                    load.piece(System.nanoTime() - embedStart)
                    fill = 0
                    piece.fill(0f)
                    if (embedding != null) {
                        val centred = centre(embedding, cohort)
                        // Compare with every piece still in the window; the lowest similarity decides.
                        var lowest = Float.MAX_VALUE
                        for (other in held) {
                            val similarity = SpeakerEmbedder.similarity(centred, other.embedding)
                            readings = (listOf(similarity) + readings).take(READINGS_KEPT)
                            if (similarity < lowest) lowest = similarity
                        }
                        if (held.isNotEmpty()) {
                            val different = lowest < settings.differentVoiceBelow
                            var note = ""
                            // Outlier filter: the FIRST piece that says "another voice" only counts once the next one
                            // agrees. A run of them keeps counting at once, so only the transition costs a piece.
                            when {
                                !settings.confirmTwice || wasDifferent -> {
                                    if (different) differentUntil = timeMs + settings.windowMs
                                }
                                different && pendingDifferent -> {
                                    differentUntil = timeMs + settings.windowMs
                                    note = " (confirmed)"
                                }
                                different -> {
                                    pendingDifferent = true
                                    note = " (waiting for a second piece)"
                                }
                                pendingDifferent -> {
                                    pendingDifferent = false
                                    note = " (the piece before it was dropped, not repeated)"
                                }
                            }
                            wasDifferent = different && (!settings.confirmTwice || note != " (waiting for a second piece)")
                            Log.i(TAG, "piece: lowest similarity to the %d held pieces %.3f -> %s%s".format(
                                held.size, lowest, if (different) "another voice" else "same voice", note))
                        }
                        held.addLast(Piece(centred, timeMs))
                        while (held.size > settings.piecesKept) held.removeFirst().embedding.fill(0f)
                        embedding.fill(0f)
                    }
                }
                // Pieces older than the window say nothing about who is in the room now.
                while (held.isNotEmpty() && timeMs - held.first().timeMs > settings.windowMs) {
                    held.removeFirst().embedding.fill(0f)
                }

                load.tick(timeMs)

                if (timeMs - lastPublish >= PUBLISH_MS) {
                    lastPublish = timeMs
                    val speechRecently = timeMs - lastSpeechAtMs <= settings.speechHoldMs
                    val multiple = timeMs <= differentUntil
                    _snapshot.value = ConversationSnapshot(
                        state = when {
                            !speechRecently -> ConversationState.NO_SPEECH
                            multiple -> ConversationState.MULTIPLE_SPEAKERS
                            speechSamples < 2L * piece.size -> ConversationState.LISTENING
                            else -> ConversationState.ONE_SPEAKER
                        },
                        running = true,
                        levelDb = levelDb,
                        speechNow = speech,
                        speechProbability = probability,
                        speechGate = SpeechGate.SILERO,
                        speechSeconds = speechSamples / 16_000.0,
                        method = DetectionMethod.PAIRWISE,
                        ownerThreshold = settings.differentVoiceBelow,
                        pairwiseMin = readings.minOrNull(),
                        pairwiseReadings = readings,
                        piecesHeld = held.size,
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
            forget()
            preRoll.fill(0)
            piece.fill(0f)
            read.fill(0)
            hop.fill(0)
            chunk.fill(0)
            cohort?.fill(0f)
            runCatching { vad?.close() }
            runCatching { embedder?.close() }
            runCatching { source.close() }
            _snapshot.value = _snapshot.value.copy(running = false)
            thread = null
        }
    }

    /** Subtracts the cohort mean and renormalises, so the room and the microphone cancel out of the comparison. */
    private fun centre(embedding: FloatArray, cohort: FloatArray?): FloatArray {
        if (cohort == null || cohort.size != embedding.size) return embedding.copyOf()
        val out = FloatArray(embedding.size) { embedding[it] - cohort[it] }
        return SpeakerEmbedder.normalise(out)
    }

    private companion object {
        const val TAG = "PairwiseVoice"
        const val HOP = 160
        const val PUBLISH_MS = 100L

        /** How long stop() waits for the thread to let go of the microphone. */
        const val STOP_TIMEOUT_MS = 1_000L
        const val READINGS_KEPT = 8
    }
}
