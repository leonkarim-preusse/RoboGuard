package com.example.robocontrol.conversation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Answer to "is one person talking, or more than one?". Not who, and not how many in total. */
enum class ConversationState {
    /** Nobody has spoken recently. */
    NO_SPEECH,
    /** Someone is talking, but not yet enough speech (2 windows) to judge. */
    LISTENING,
    /** Speech without enough voice changes. */
    ONE_SPEAKER,
    /** Enough voice changes recently: more than one person. */
    MULTIPLE_SPEAKERS
}

/** What a detector currently reports. Only levels, distances, counts and times; nothing that characterises a voice. */
data class ConversationSnapshot(
    val state: ConversationState = ConversationState.NO_SPEECH,
    val running: Boolean = false,
    val levelDb: Double = Double.NEGATIVE_INFINITY,
    val noiseFloorDb: Double = Double.NEGATIVE_INFINITY,
    val speechNow: Boolean = false,
    /** Latest Silero speech probability (0…1), or null when the loudness gate is used. */
    val speechProbability: Double? = null,
    /** The speech gate actually in use (SILERO may have fallen back to LOUDNESS). */
    val speechGate: SpeechGate = SpeechGate.LOUDNESS,
    val speechSeconds: Double = 0.0,
    val lastKl2: Double = 0.0,
    val kl2Mean: Double = 0.0,
    val changesInWindow: Int = 0,
    /** Current accumulated evidence (evidence rule), already decayed to [timeMs]. */
    val evidence: Double = 0.0,
    /** What each rule says right now (only meaningful while someone talks). */
    val multipleByCount: Boolean = false,
    val multipleByEvidence: Boolean = false,
    val decisionRule: DecisionRule = DecisionRule.EVIDENCE,
    val totalChanges: Int = 0,
    /** Recent KL2 values, oldest first, for a live graph. */
    val kl2History: List<Double> = emptyList(),
    /** Recent candidates, newest last (accepted = counted as a change). */
    val candidates: List<ChangeCandidate> = emptyList(),
    /** Stream time (ms since start) of this snapshot. */
    val timeMs: Long = 0,
    val error: String? = null
)

/** Detects whether more than one person is talking. Implementations must not store or send audio. */
interface ConversationDetector {
    val snapshot: StateFlow<ConversationSnapshot>
    fun start()
    fun stop()
}

/**
 * [ConversationDetector] by speaker change detection (see [ChangeDetector]) on 16 kHz mono audio from a [PcmSource].
 *
 * Runs on its own thread. Audio is handled in two fixed buffers (160 new samples, one 400-sample frame) that are
 * overwritten every 10 ms and zeroed on stop; features live only in the ChangeDetector's ring buffer. Nothing is written
 * to disk or logged except numbers.
 *
 * Designed so clustering ("which segments are the same voice") can be added later on top of the accepted changes.
 */
class SpeakerChangeDetector(
    private val source: PcmSource,
    private val config: ChangeDetectorConfig = ChangeDetectorConfig(),
    /** Creates the Silero VAD when [ChangeDetectorConfig.speechGate] is SILERO; null = always the loudness gate. */
    private val sileroFactory: (() -> SileroVad)? = null,
    /** Called on the audio thread for every evaluated candidate (for logging / tests). */
    private val onCandidate: (ChangeCandidate) -> Unit = {}
) : ConversationDetector {

    private val _snapshot = MutableStateFlow(ConversationSnapshot())
    override val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var thread: Thread? = null

    override fun start() {
        if (thread != null) return
        _snapshot.value = ConversationSnapshot(running = true)
        thread = Thread(::run, "SpeakerChangeDetector").also { it.start() }
    }

    override fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
    }

    /** Runs until the source ends or [stop] is called. Blocking; for offline tests call directly instead of [start]. */
    fun runBlocking() {
        _snapshot.value = ConversationSnapshot(running = true)
        run()
    }

    private fun run() {
        val mfcc = Mfcc(sampleRate = source.sampleRate)
        val detector = ChangeDetector(mfcc.coefficients, config)
        val hop = source.sampleRate / 100 // 10 ms
        val hopBuffer = ShortArray(hop)
        val readBuffer = ShortArray(hop)
        val frame = DoubleArray(mfcc.frameLength)
        val features = DoubleArray(mfcc.coefficients)
        val kl2History = ArrayDeque<Double>()
        val candidates = ArrayDeque<ChangeCandidate>()
        val acceptedTimes = ArrayDeque<Long>()
        // Evidence rule state: value at evidenceAtMs (decays from there), and when it last crossed the threshold.
        var evidence = 0.0
        var evidenceAtMs = 0L
        var evidenceTriggeredAtMs = Long.MIN_VALUE / 2
        fun evidenceAt(t: Long) = evidence * Math.pow(0.5, (t - evidenceAtMs).toDouble() / config.evidenceHalfLifeMs)
        var totalChanges = 0
        var samples = 0L
        var lastPublishMs = -1_000L
        var lastKl2Seen = Double.NaN
        var resetsSeen = 0

        // Silero VAD: 512-sample chunks (32 ms), filled from the 10 ms hops; the latest probability applies to the next frames.
        var vad: SileroVad? = null
        var gateError: String? = null
        if (config.speechGate == SpeechGate.SILERO) {
            vad = sileroFactory?.let { factory ->
                runCatching { factory() }.onFailure {
                    gateError = "Silero VAD not available, using loudness: $it"
                    Log.e(TAG, "Silero VAD failed to load", it)
                }.getOrNull()
            }
            if (vad == null && gateError == null) gateError = "Silero VAD not configured, using loudness"
        }
        val vadChunk = ShortArray(vad?.chunkSize ?: 0)
        var vadFill = 0
        var speechProbability: Double? = if (vad != null) 0.0 else null

        val openError = source.open()
        if (openError != null) {
            _snapshot.value = ConversationSnapshot(running = false, error = openError)
            thread = null
            return
        }
        try {
            while (!Thread.currentThread().isInterrupted) {
                var filled = 0
                while (filled < hop) {
                    val n = source.read(readBuffer, hop - filled)
                    if (n < 0) return
                    readBuffer.copyInto(hopBuffer, filled, 0, n)
                    filled += n
                }
                // Slide the 25 ms frame by 10 ms and append the new samples.
                frame.copyInto(frame, 0, hop, frame.size)
                for (i in 0 until hop) frame[frame.size - hop + i] = hopBuffer[i] / 32768.0
                samples += hop
                val timeMs = samples * 1000 / source.sampleRate

                vad?.let { v ->
                    var i = 0
                    while (i < hop) {
                        val take = minOf(hop - i, vadChunk.size - vadFill)
                        hopBuffer.copyInto(vadChunk, vadFill, i, i + take)
                        vadFill += take
                        i += take
                        if (vadFill == vadChunk.size) {
                            speechProbability = v.probability(vadChunk).toDouble()
                            vadFill = 0
                        }
                    }
                }

                val levelDb = mfcc.compute(frame, features)
                val candidate = detector.onFrame(timeMs, levelDb, features, speechProbability)
                if (detector.conversationResets != resetsSeen) {
                    resetsSeen = detector.conversationResets
                    acceptedTimes.clear()
                    kl2History.clear()
                    evidence = 0.0
                    evidenceAtMs = timeMs
                    evidenceTriggeredAtMs = Long.MIN_VALUE / 2
                    vad?.reset()
                    Log.i(TAG, "reset after ${config.resetAfterSilenceMs} ms of silence")
                }
                if (detector.lastKl2 != lastKl2Seen) {
                    lastKl2Seen = detector.lastKl2
                    kl2History.addLast(detector.lastKl2)
                    if (kl2History.size > HISTORY) kl2History.removeFirst()
                }
                if (candidate != null) {
                    candidates.addLast(candidate)
                    if (candidates.size > CANDIDATES) candidates.removeFirst()
                    // Each group of overlapping candidates counts once (see ChangeDetector.groupCandidates).
                    if (candidate.countsAsChange) {
                        acceptedTimes.addLast(candidate.timeMs)
                        totalChanges++
                    }
                    if (candidate.bicPenalty > 0) {
                        evidence = evidenceAt(timeMs) + candidate.evidenceAdded(config.evidenceBaseRatio)
                        evidenceAtMs = timeMs
                        if (evidence >= config.evidenceThreshold) evidenceTriggeredAtMs = timeMs
                    }
                    onCandidate(candidate)
                }
                while (acceptedTimes.isNotEmpty() && timeMs - acceptedTimes.first() > config.decisionWindowMs) acceptedTimes.removeFirst()

                if (timeMs - lastPublishMs >= PUBLISH_MS) {
                    lastPublishMs = timeMs
                    val speechRecently = timeMs - detector.lastSpeechAtMs <= config.speechHoldMs
                    // Silence wins: past changes keep counting for the decision, but nobody talking now is shown at once.
                    val multipleByCount = acceptedTimes.size >= config.minChangesForMultiple
                    // Held for the decision window after the last crossing, like counted changes are.
                    val multipleByEvidence = timeMs - evidenceTriggeredAtMs <= config.decisionWindowMs
                    val multiple = if (config.decisionRule == DecisionRule.COUNT) multipleByCount else multipleByEvidence
                    val state = when {
                        !speechRecently -> ConversationState.NO_SPEECH
                        multiple -> ConversationState.MULTIPLE_SPEAKERS
                        detector.speechFrames < 2L * config.windowFrames -> ConversationState.LISTENING
                        else -> ConversationState.ONE_SPEAKER
                    }
                    _snapshot.value = ConversationSnapshot(
                        state = state,
                        running = true,
                        levelDb = detector.lastLevelDb,
                        noiseFloorDb = detector.noiseFloorDb,
                        speechNow = detector.lastFrameWasSpeech,
                        speechProbability = speechProbability,
                        speechGate = if (vad != null) SpeechGate.SILERO else SpeechGate.LOUDNESS,
                        error = gateError,
                        speechSeconds = detector.speechFrames / 100.0,
                        lastKl2 = detector.lastKl2,
                        kl2Mean = detector.runningKl2Mean,
                        changesInWindow = acceptedTimes.size,
                        evidence = evidenceAt(timeMs),
                        multipleByCount = multipleByCount,
                        multipleByEvidence = multipleByEvidence,
                        decisionRule = config.decisionRule,
                        totalChanges = totalChanges,
                        kl2History = kl2History.toList(),
                        candidates = candidates.toList(),
                        timeMs = timeMs
                    )
                }
            }
        } catch (_: InterruptedException) {
            // stop() while a source was waiting: normal end
        } catch (e: Exception) {
            Log.e(TAG, "detector stopped: $e", e)
            _snapshot.value = _snapshot.value.copy(error = e.toString())
        } finally {
            // Privacy: nothing of the audio or features survives the run.
            hopBuffer.fill(0)
            readBuffer.fill(0)
            vadChunk.fill(0)
            runCatching { vad?.close() }
            frame.fill(0.0)
            features.fill(0.0)
            detector.clear()
            runCatching { source.close() }
            _snapshot.value = _snapshot.value.copy(running = false)
            thread = null
        }
    }

    private companion object {
        const val TAG = "SpeakerChange"
        const val PUBLISH_MS = 100L
        const val HISTORY = 300
        const val CANDIDATES = 30
    }
}
