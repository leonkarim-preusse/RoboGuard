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
        thread?.interrupt()
        thread = null
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
        val held = ArrayDeque<Piece>()
        var readings = emptyList<Float>()
        var differentUntil = Long.MIN_VALUE / 2
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L

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
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
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
                        probability = vad.probability(chunk).toDouble()
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
                }
                wasRobotTalking = robotTalking
                val speech = !robotTalking && probability >= settings.speechThreshold && levelDb > settings.minLevelDb

                if (speech) {
                    lastSpeechAtMs = timeMs
                    for (k in 0 until HOP) if (fill < piece.size) piece[fill++] = hop[k] / 32768f
                    speechSamples += HOP
                } else if (timeMs - lastSpeechAtMs >= settings.resetAfterSilenceMs && held.isNotEmpty()) {
                    forget()
                    readings = emptyList()
                    speechSamples = 0
                    fill = 0
                    piece.fill(0f)
                    vad.reset()
                    Log.i(TAG, "reset after ${settings.resetAfterSilenceMs} ms of silence")
                }

                if (fill >= piece.size) {
                    val embedding = embedder.embed(piece, fill)
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
                            Log.i(TAG, "piece: lowest similarity to the %d held pieces %.3f -> %s".format(
                                held.size, lowest,
                                if (lowest < settings.differentVoiceBelow) "another voice" else "same voice"))
                            if (lowest < settings.differentVoiceBelow) differentUntil = timeMs + settings.windowMs
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
        const val READINGS_KEPT = 8
    }
}
