package com.example.robocontrol.conversation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Decision of a conversation detector. */
enum class ConversationState {
    NO_SPEECH,
    LISTENING,
    ONE_SPEAKER,
    MULTIPLE_SPEAKERS
}
/**
 * One 10 ms frame of the debug timeline.
 * @property timeMs time since the detector started
 * @property levelDb level in dBFS
 * @property probability Silero speech probability; null without Silero
 * @property speech whether the gate counted the frame as speech
 * @property silero whether Silero's half of the gate is on
 * @property loud whether the level is above the floor
 * @property robot whether the robot itself was speaking
 */
data class AudioFrame(
    val timeMs: Long,
    val levelDb: Double,
    val probability: Double?,
    val speech: Boolean,
    val silero: Boolean = false,
    val loud: Boolean = false,
    val robot: Boolean = false
)

/** Last 15 s of frames and events for the debug screen; collected only while it is enabled. */
internal class DebugTimeline {
    private val frames = ArrayDeque<AudioFrame>()
    private val events = ArrayDeque<ConversationEvent>()

    /**
     * Adds one 10 ms frame while [enabled]; drops everything as soon as nobody watches.
     * @param enabled whether the timeline is collected
     * @param f frame to add
     */
    fun frame(enabled: Boolean, f: AudioFrame) {
        if (!enabled) {
            if (frames.isNotEmpty() || events.isNotEmpty()) { frames.clear(); events.clear() }
            return
        }
        frames.addLast(f)
        while (frames.size > FRAMES) frames.removeFirst()
        while (events.isNotEmpty() && f.timeMs - events.first().timeMs > FRAMES * 10L) events.removeFirst()
    }

    /**
     * Adds an event mark while [enabled].
     * @param enabled whether the timeline is collected
     * @param e event to add
     */
    fun event(enabled: Boolean, e: ConversationEvent) {
        if (enabled) events.addLast(e)
    }

    /**
     * Copy of the frames for the snapshot; empty when not enabled.
     * @param enabled whether the timeline is collected
     * @return copy of the frames
     */
    fun frames(enabled: Boolean): List<AudioFrame> = if (enabled) frames.toList() else emptyList()
    /**
     * Copy of the events for the snapshot; empty when not enabled.
     * @param enabled whether the timeline is collected
     * @return copy of the events
     */
    fun events(enabled: Boolean): List<ConversationEvent> = if (enabled) events.toList() else emptyList()

    private companion object {
        const val FRAMES = 1500
    }
}

/** Kinds of event marked on the debug timeline. */
enum class ConversationEventKind { CHANGE, CANDIDATE_REJECTED, RESET, MULTIPLE }

/**
 * Event marked on the debug timeline.
 * @property timeMs audio time of the event
 * @property kind kind of event
 * @property ratio BIC gain / penalty of a candidate (0 for resets and state flips)
 */
data class ConversationEvent(val timeMs: Long, val kind: ConversationEventKind, val ratio: Double = 0.0)

/**
 * State published by a detector every 100 ms.
 * @property state current decision
 * @property running whether the detector runs
 * @property levelDb current level in dBFS
 * @property noiseFloorDb current noise floor in dBFS
 * @property speechNow whether someone is talking now
 * @property speechProbability latest Silero probability
 * @property speechGate speech gate in use
 * @property speechSeconds speech collected in this conversation, in seconds
 * @property lastKl2 latest KL2 value
 * @property kl2Mean running KL2 mean
 * @property changesInWindow changes in the decision window
 * @property evidence current evidence
 * @property multipleByCount decision of the count rule
 * @property multipleByEvidence decision of the evidence rule
 * @property decisionRule rule in use
 * @property totalChanges changes since start
 * @property timeline debug frames, empty unless a debug screen is open
 * @property events debug events
 * @property sileroThreshold Silero start threshold
 * @property sileroRelease Silero end threshold
 * @property levelFloorDb level floor of the gate
 * @property kl2History recent KL2 values
 * @property candidates recent change candidates
 * @property timeMs audio time of the snapshot
 * @property method detection method
 * @property ownerSimilarity similarity of the last piece to the stored voice
 * @property ownerThreshold threshold of the stored voice
 * @property ownerMargin unclear band below the threshold
 * @property ownerReadings recent similarity readings
 * @property ownerPieces owner pieces in the window
 * @property otherPieces other pieces in the window
 * @property speechShare share of speech in the activity window
 * @property speechShareNeeded share needed for a conversation
 * @property windowFilled share of the activity window filled
 * @property pairwiseMin result of the last pairwise comparison
 * @property pairwiseReadings recent pairwise readings
 * @property piecesHeld pieces held for comparison
 * @property warning warning text, or null
 * @property error error text, or null
 */
data class ConversationSnapshot(
    val state: ConversationState = ConversationState.NO_SPEECH,
    val running: Boolean = false,
    val levelDb: Double = Double.NEGATIVE_INFINITY,
    val noiseFloorDb: Double = Double.NEGATIVE_INFINITY,
    val speechNow: Boolean = false,
    val speechProbability: Double? = null,
    val speechGate: SpeechGate = SpeechGate.LOUDNESS,
    val speechSeconds: Double = 0.0,
    val lastKl2: Double = 0.0,
    val kl2Mean: Double = 0.0,
    val changesInWindow: Int = 0,
    val evidence: Double = 0.0,
    val multipleByCount: Boolean = false,
    val multipleByEvidence: Boolean = false,
    val decisionRule: DecisionRule = DecisionRule.EVIDENCE,
    val totalChanges: Int = 0,
    val timeline: List<AudioFrame> = emptyList(),
    val events: List<ConversationEvent> = emptyList(),
    val sileroThreshold: Double? = null,
    val sileroRelease: Double? = null,
    val levelFloorDb: Double? = null,
    val kl2History: List<Double> = emptyList(),
    val candidates: List<ChangeCandidate> = emptyList(),
    val timeMs: Long = 0,
    val method: DetectionMethod = DetectionMethod.CHANGE,
    val ownerSimilarity: Float? = null,
    val ownerThreshold: Float = 0f,
    val ownerMargin: Float = 0f,
    val ownerReadings: List<Float> = emptyList(),
    val ownerPieces: Int = 0,
    val otherPieces: Int = 0,
    val speechShare: Double = 0.0,
    val speechShareNeeded: Double = 0.0,
    val windowFilled: Double = 0.0,
    val pairwiseMin: Float? = null,
    val pairwiseReadings: List<Float> = emptyList(),
    val piecesHeld: Int = 0,
    val warning: String? = null,
    val error: String? = null
)

/** How the detector decides between one and several speakers. */
enum class DetectionMethod {
    CHANGE,

    OWNER,

    PAIRWISE,

    ACTIVITY
}

/** Detects whether more than one person is talking. Implementations must not store or send audio. */
interface ConversationDetector {
    val snapshot: StateFlow<ConversationSnapshot>

    var debugTimeline: Boolean

    /** Starts the detector's thread; it opens the microphone itself. */
    fun start()

    /**
     * Stops the detector and waits briefly for its thread.
     * @return true if the thread ended and the microphone is free
     */
    fun stop(): Boolean
}

/**
 * [ConversationDetector] based on speaker change detection ([ChangeDetector]) on 16 kHz mono audio.
 * Runs on its own thread; audio buffers are overwritten every 10 ms and zeroed on stop.
 * @param source audio input
 * @param config detection settings
 * @property sileroFactory creates the Silero model; null for the loudness gate
 * @param robotSpeaking returns true while the robot itself is speaking; those frames are treated as silence
 * @property onCandidate called for every change candidate
 */
class SpeakerChangeDetector(
    private val source: PcmSource,
    private val config: ChangeDetectorConfig = ChangeDetectorConfig(),
    private val sileroFactory: (() -> SileroVad)? = null,
    private val robotSpeaking: ((audioTimeMs: Long) -> Boolean)? = null,
    private val onCandidate: (ChangeCandidate) -> Unit = {}
) : ConversationDetector {
    @Volatile
    override var debugTimeline: Boolean = false

    private var lastState = ConversationState.NO_SPEECH

    private val timeline = ArrayDeque<AudioFrame>()
    private val events = ArrayDeque<ConversationEvent>()

    private val _snapshot = MutableStateFlow(ConversationSnapshot())
    override val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var thread: Thread? = null

    /** Starts the detection thread. */
    override fun start() {
        if (thread != null) return
        _snapshot.value = ConversationSnapshot(running = true)
        thread = Thread(::run, "SpeakerChangeDetector").also { it.start() }
    }

    /**
     * Stops the detection thread.
     * @return true if the thread ended
     */
    override fun stop(): Boolean {
        val t = thread ?: return true
        thread = null
        t.interrupt()
        runCatching { t.join(1_000) }
        return !t.isAlive
    }

    /** Runs until the source ends or [stop] is called. */
    fun runBlocking() {
        _snapshot.value = ConversationSnapshot(running = true)
        run()
    }

    /** The detector thread: reads 10 ms hops, runs the speech gate and the change detection and publishes snapshots. */
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
        var evidence = 0.0
        var evidenceAtMs = 0L
        var evidenceTriggeredAtMs = Long.MIN_VALUE / 2
        /**
         * Evidence decayed to time [t].
         * @param t audio time in milliseconds
         * @return the evidence at [t]
         */
        fun evidenceAt(t: Long) = evidence * Math.pow(0.5, (t - evidenceAtMs).toDouble() / config.evidenceHalfLifeMs)
        var totalChanges = 0
        var samples = 0L
        var lastPublishMs = -1_000L
        var lastKl2Seen = Double.NaN
        var resetsSeen = 0

        val openError = source.open()
        if (openError != null) {
            _snapshot.value = ConversationSnapshot(running = false, error = openError)
            thread = null
            return
        }
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
        var wasRobotTalking = false
        val vadChunk = ShortArray(vad?.chunkSize ?: 0)
        var vadFill = 0
        val load = AudioLoad(TAG, "change detection, window %.1f s, lambda %.1f, %s, gate %s, silero %.2f (hysteresis %.2f)".format(
            config.windowFrames / 100.0, config.bicLambda, config.decisionRule, config.speechGate,
            config.sileroThreshold, config.sileroHysteresis))
        var speechProbability: Double? = if (vad != null) 0.0 else null
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            while (!Thread.currentThread().isInterrupted) {
                var filled = 0
                var emptyReads = 0
                while (filled < hop) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val n = source.read(readBuffer, hop - filled)
                    if (n < 0 || (n == 0 && ++emptyReads > 200)) {
                        if (source is AndroidMicSource) _snapshot.value = _snapshot.value.copy(running = false, error = "microphone stopped delivering audio")
                        return
                    }
                    if (n == 0) { Thread.sleep(5); continue }
                    emptyReads = 0
                    readBuffer.copyInto(hopBuffer, filled, 0, n)
                    filled += n
                }
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
                            val vadStart = System.nanoTime()
                            speechProbability = v.probability(vadChunk).toDouble()
                            load.vad(System.nanoTime() - vadStart)
                            vadFill = 0
                        }
                    }
                }

                val analysisStart = System.nanoTime()
                val levelDb = mfcc.compute(frame, features)
                val robotTalking = robotSpeaking?.invoke(timeMs) == true
                if (robotTalking != wasRobotTalking) {
                    Log.i(TAG, if (robotTalking) "robot is speaking: ignoring the microphone until it stops"
                    else "robot stopped speaking: listening again")
                    wasRobotTalking = robotTalking
                }
                val candidate = if (robotTalking) {
                    detector.onFrame(timeMs, Double.NEGATIVE_INFINITY, features, speechProbability?.let { 0.0 })
                } else {
                    detector.onFrame(timeMs, levelDb, features, speechProbability)
                }
                load.analysis(System.nanoTime() - analysisStart)
                load.frame(detector.lastFrameWasSpeech)
                load.tick(timeMs)
                if (debugTimeline) {
                    timeline.addLast(AudioFrame(timeMs, levelDb, speechProbability, detector.lastFrameWasSpeech,
                        detector.lastSilero, detector.lastLoud, robotTalking))
                    while (timeline.size > TIMELINE_FRAMES) timeline.removeFirst()
                    while (events.isNotEmpty() && timeMs - events.first().timeMs > TIMELINE_FRAMES * 10L) events.removeFirst()
                } else if (timeline.isNotEmpty()) {
                    timeline.clear(); events.clear()
                }
                if (detector.conversationResets != resetsSeen) {
                    resetsSeen = detector.conversationResets
                    if (debugTimeline) events.addLast(ConversationEvent(timeMs, ConversationEventKind.RESET))
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
                    if (candidate.countsAsChange) {
                        acceptedTimes.addLast(candidate.timeMs)
                        totalChanges++
                    }
                    if (debugTimeline) {
                        events.addLast(ConversationEvent(candidate.timeMs,
                            if (candidate.countsAsChange) ConversationEventKind.CHANGE else ConversationEventKind.CANDIDATE_REJECTED, candidate.ratio))
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
                    val multipleByCount = acceptedTimes.size >= config.minChangesForMultiple
                    val multipleByEvidence = timeMs - evidenceTriggeredAtMs <= config.decisionWindowMs
                    val multiple = if (config.decisionRule == DecisionRule.COUNT) multipleByCount else multipleByEvidence
                    val state = when {
                        !speechRecently -> ConversationState.NO_SPEECH
                        multiple -> ConversationState.MULTIPLE_SPEAKERS
                        detector.speechFrames < 2L * config.windowFrames -> ConversationState.LISTENING
                        else -> ConversationState.ONE_SPEAKER
                    }
                    if (debugTimeline && state == ConversationState.MULTIPLE_SPEAKERS && lastState != ConversationState.MULTIPLE_SPEAKERS) {
                        events.addLast(ConversationEvent(timeMs, ConversationEventKind.MULTIPLE))
                    }
                    lastState = state
                    _snapshot.value = ConversationSnapshot(
                        state = state,
                        running = true,
                        levelDb = detector.lastLevelDb,
                        noiseFloorDb = detector.noiseFloorDb,
                        speechNow = detector.lastFrameWasSpeech,
                        speechProbability = speechProbability,
                        speechGate = if (vad != null) SpeechGate.SILERO else SpeechGate.LOUDNESS,
                        warning = gateError,
                        speechSeconds = detector.speechFrames / 100.0,
                        lastKl2 = detector.lastKl2,
                        kl2Mean = detector.runningKl2Mean,
                        changesInWindow = acceptedTimes.size,
                        evidence = evidenceAt(timeMs),
                        multipleByCount = multipleByCount,
                        multipleByEvidence = multipleByEvidence,
                        decisionRule = config.decisionRule,
                        timeline = if (debugTimeline) timeline.toList() else emptyList(),
                        events = if (debugTimeline) events.toList() else emptyList(),
                        sileroThreshold = if (vad != null) config.sileroThreshold else null,
                        sileroRelease = if (vad != null) config.sileroThreshold - config.sileroHysteresis else null,
                        levelFloorDb = detector.lastLevelFloorDb.takeIf { it.isFinite() },
                        totalChanges = totalChanges,
                        kl2History = kl2History.toList(),
                        candidates = candidates.toList(),
                        timeMs = timeMs
                    )
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "detector stopped: $e", e)
            _snapshot.value = _snapshot.value.copy(error = e.toString())
        } finally {
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

        const val TIMELINE_FRAMES = 1500
    }
}
