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
 * [ConversationDetector] by comparison with the owner's stored voice ([OwnerVoiceprint]).
 *
 * Every [CONFIG.pieceSeconds] of speech — silence does not count — becomes one embedding, which is compared with the
 * template. Above the threshold the piece is the owner, below it somebody else. More than one person is reported when
 * both have been heard inside [ConversationSettings.windowMs], or, in [ConversationSettings.anyOtherIsConversation]
 * mode, as soon as a voice that is not the owner is heard at all.
 *
 * Why it is built on a MEASURED threshold rather than a computed one: on this robot the microphone and the room put a
 * large common part into every embedding, so the owner reads ~0.90 and another person ~0.80 (2026-09-23) instead of the
 * 0.55–0.68 vs 0.09–0.24 measured on close-talk recordings. The gap is real but narrow, so the threshold is set by hand
 * on the voice screen from what was actually measured, and two rules keep a single borderline piece from flipping the
 * state: a piece must be below the threshold by [CONFIG.margin] to count as somebody else, and
 * [ConversationSettings.piecesForOther] such pieces are needed.
 *
 * Privacy: the template is the only voice kept on disk. A guest's embedding exists while it is compared, and the
 * decision window keeps nothing but two counters and a handful of similarity NUMBERS — no vectors. Everything is zeroed
 * when the detector stops or after [ConversationSettings.resetAfterSilenceMs] of silence.
 */
class OwnerVoiceDetector(
    private val context: Context,
    private val source: PcmSource = AndroidMicSource(),
    private val settings: ConversationSettings = ConversationSettings()
) : ConversationDetector {

    private val _snapshot = MutableStateFlow(ConversationSnapshot(method = DetectionMethod.OWNER))
    override val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var thread: Thread? = null

    @Synchronized
    override fun start() {
        if (thread != null) return
        thread = Thread(::run, "OwnerVoiceDetector").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val store = OwnerVoiceprintStore.get(context)
        val template = store.load()
        if (template == null) {
            _snapshot.value = ConversationSnapshot(
                method = DetectionMethod.OWNER,
                running = false,
                error = "no voice stored: teach the robot the owner's voice first"
            )
            thread = null
            return
        }
        // Average of other voices through this microphone; subtracted from both sides when comparing (see Voiceprint).
        val cohort = store.loadCohort()
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

        // Decision window: times only, no vectors.
        val ownerTimes = ArrayDeque<Long>()
        val otherTimes = ArrayDeque<Long>()
        var readings = emptyList<Float>()
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L

        try {
            vad = SileroVad(context)
            embedder = SpeakerEmbedder(context)
            val openError = source.open()
            if (openError != null) {
                _snapshot.value = _snapshot.value.copy(running = false, error = openError)
                return
            }
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            Log.i(TAG, "listening for the owner's voice (threshold %.2f, margin %.2f, %s)".format(
                template.info.threshold, settings.margin,
                if (cohort != null) "centred on ${cohort.size} values of other voices" else "no cohort recorded"))

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
                } else if (timeMs - lastSpeechAtMs >= settings.resetAfterSilenceMs && (ownerTimes.isNotEmpty() || otherTimes.isNotEmpty())) {
                    // Long silence = new conversation.
                    ownerTimes.clear()
                    otherTimes.clear()
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
                        val similarity = template.similarityTo(embedding, cohort)
                        embedding.fill(0f)
                        val isOwner = similarity >= template.info.threshold
                        val isOther = similarity < template.info.threshold - settings.margin
                        if (isOwner) ownerTimes.addLast(timeMs)
                        if (isOther) otherTimes.addLast(timeMs)
                        readings = (listOf(similarity) + readings).take(READINGS_KEPT)
                        Log.i(TAG, "piece: similarity %.3f -> %s".format(
                            similarity, if (isOwner) "owner" else if (isOther) "somebody else" else "unclear"))
                    }
                }
                while (ownerTimes.isNotEmpty() && timeMs - ownerTimes.first() > settings.windowMs) ownerTimes.removeFirst()
                while (otherTimes.isNotEmpty() && timeMs - otherTimes.first() > settings.windowMs) otherTimes.removeFirst()

                if (timeMs - lastPublish >= PUBLISH_MS) {
                    lastPublish = timeMs
                    val speechRecently = timeMs - lastSpeechAtMs <= settings.speechHoldMs
                    val others = otherTimes.size >= settings.piecesForOther
                    val multiple = others && (settings.anyOtherIsConversation || ownerTimes.isNotEmpty())
                    _snapshot.value = ConversationSnapshot(
                        state = when {
                            !speechRecently -> ConversationState.NO_SPEECH
                            multiple -> ConversationState.MULTIPLE_SPEAKERS
                            speechSamples < piece.size.toLong() -> ConversationState.LISTENING
                            else -> ConversationState.ONE_SPEAKER
                        },
                        running = true,
                        levelDb = levelDb,
                        speechNow = speech,
                        speechProbability = probability,
                        speechGate = SpeechGate.SILERO,
                        speechSeconds = speechSamples / 16_000.0,
                        method = DetectionMethod.OWNER,
                        ownerSimilarity = readings.firstOrNull(),
                        ownerThreshold = template.info.threshold,
                        ownerReadings = readings,
                        ownerPieces = ownerTimes.size,
                        otherPieces = otherTimes.size,
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
            piece.fill(0f)
            read.fill(0)
            hop.fill(0)
            chunk.fill(0)
            template.zero()
            cohort?.fill(0f)
            runCatching { vad?.close() }
            runCatching { embedder?.close() }
            runCatching { source.close() }
            _snapshot.value = _snapshot.value.copy(running = false)
            thread = null
        }
    }

    private companion object {
        const val TAG = "OwnerVoice"
        const val HOP = 160
        const val PUBLISH_MS = 100L
        const val READINGS_KEPT = 8
    }
}

/**
 * Settings of the owner comparison. Defaults come from what was measured on the robot (owner ≈ 0.90, another person
 * ≈ 0.80); the threshold itself lives with the stored voice and is set on the voice screen.
 *
 * @property margin how far below the threshold a piece must be before it counts as somebody else
 * @property piecesForOther pieces that must say "somebody else" before the robot believes it
 * @property anyOtherIsConversation true = any voice that is not the owner counts as a conversation; false = the owner
 *           must have been heard in the same window (a stranger talking alone is then not a conversation)
 */
data class ConversationSettings(
    val pieceSeconds: Double = 3.0,
    val speechThreshold: Double = 0.5,
    val minLevelDb: Double = -65.0,
    val margin: Float = 0.02f,
    val piecesForOther: Int = 2,
    val anyOtherIsConversation: Boolean = false,
    val windowMs: Long = 20_000,
    val speechHoldMs: Long = 1_500,
    val resetAfterSilenceMs: Long = 30_000,
    /**
     * Pairwise method: two pieces whose similarity is below this are treated as two different voices. Needs measuring on
     * the robot like every other number here — the screen shows the live comparisons and lets it be adjusted.
     */
    val differentVoiceBelow: Float = 0.5f,
    /** Pairwise method: how many pieces are held for comparison (in memory only). */
    val piecesKept: Int = 6
)
