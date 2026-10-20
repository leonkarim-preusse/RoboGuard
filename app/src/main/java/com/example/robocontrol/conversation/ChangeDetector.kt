package com.example.robocontrol.conversation

import kotlin.math.max

/** How "more than one speaker" is decided from the change candidates. Both are always computed; this one is shown. */
enum class DecisionRule {
    /** At least [ChangeDetectorConfig.minChangesForMultiple] accepted changes (ΔBIC > 0) within the decision window. */
    COUNT,
    /**
     * Evidence rule: each candidate adds max(0, r − r₀), with r = BIC gain / penalty; the evidence halves every
     * [ChangeDetectorConfig.evidenceHalfLifeMs], and reaching [ChangeDetectorConfig.evidenceThreshold] means more than one speaker.
     */
    EVIDENCE
}

/** How a frame is judged to be speech before it may enter the comparison windows. */
enum class SpeechGate {
    /** Loudness above the noise floor only; loud noise (claps, doors, music) counts as speech too. */
    LOUDNESS,
    /** Silero VAD neural network ([SileroVad]); falls back to LOUDNESS if the model cannot be loaded. */
    SILERO
}

/** Settings of [ChangeDetector]. A frame is one MFCC vector per 10 ms; only speech frames count towards the windows. */
data class ChangeDetectorConfig(
    /** Which speech gate to use. */
    val speechGate: SpeechGate = SpeechGate.SILERO,
    /** Silero: speech starts at this probability and ends below it minus [sileroHysteresis]. */
    val sileroThreshold: Double = 0.5,
    val sileroHysteresis: Double = 0.15,
    /** Speech frames per comparison window (150 = 1.5 s of speech). Two adjacent windows are compared. */
    val windowFrames: Int = 150,
    /** Compare every this many new speech frames (10 = every 0.1 s of speech). */
    val stepFrames: Int = 10,
    /** Penalty weight λ of ΔBIC; higher values accept fewer changes. */
    val bicLambda: Double = 2.0,
    /** A KL2 peak is a candidate only if it is at least this many times the running mean of KL2. */
    val kl2Relative: Double = 1.3,
    /** Candidates within this many speech frames of a group's first boundary belong to the same group. */
    val groupFrames: Int = 50,
    /** No second change within this many speech frames of the previous one. */
    val minFramesBetweenChanges: Int = 100,
    /** A frame is speech if it is this many dB above the noise floor … */
    val speechMarginDb: Double = 15.0,
    /** … and above this absolute level (dBFS) … */
    val speechMinDb: Double = -55.0,
    /** … and at least this many of the last [speechRunFrames] frames were loud too (drops clicks and short noise bursts). */
    val speechRunMin: Int = 6,
    val speechRunFrames: Int = 20,
    /** Noise floor = this percentile of the frame levels over the last [noiseWindowFrames] frames (5 s). */
    val noisePercentile: Double = 0.10,
    val noiseWindowFrames: Int = 500,
    /** Changes are counted over this much recent time for the decision. */
    val decisionWindowMs: Long = 20_000,
    /** At least this many changes in [decisionWindowMs] → more than one speaker (2 = a back-and-forth). */
    val minChangesForMultiple: Int = 2,
    /** Which rule decides the shown state. */
    val decisionRule: DecisionRule = DecisionRule.EVIDENCE,
    /** Evidence rule: ratio r₀ that a candidate must exceed to add evidence. */
    val evidenceBaseRatio: Double = 1.8,
    /** Evidence rule: evidence halves every this many ms. */
    val evidenceHalfLifeMs: Long = 5_000,
    /** Evidence rule: this much evidence → more than one speaker. */
    val evidenceThreshold: Double = 0.8,
    /** Speech within this time counts as "someone is talking now". */
    val speechHoldMs: Long = 1_500,
    /** After this much silence the conversation is over: buffered speech and counted changes are dropped. */
    val resetAfterSilenceMs: Long = 30_000,
    /** "Silence" may still contain this share of speech frames (see [SilenceWindow]). */
    val resetMaxSpeechShare: Double = 0.10
)

/**
 * Tracks whether the last [windowMs] were quiet, i.e. at most [maxSpeechShare] of the frames contained speech.
 * A full window is needed after every [clear].
 * @param windowMs window length in milliseconds
 * @param maxSpeechShare highest share of speech frames that still counts as quiet
 * @param frameMs length of one frame in milliseconds
 */
class SilenceWindow(windowMs: Long, private val maxSpeechShare: Double, frameMs: Long = 10) {
    private val frames = BooleanArray((windowMs / frameMs).toInt().coerceAtLeast(1))
    private var at = 0
    private var filled = 0
    private var speech = 0

    /** Adds one frame; true if the window is full and quiet. */
    fun add(isSpeech: Boolean): Boolean {
        if (filled == frames.size && frames[at]) speech--
        frames[at] = isSpeech
        if (isSpeech) speech++
        at = (at + 1) % frames.size
        if (filled < frames.size) filled++
        return filled == frames.size && speech <= maxSpeechShare * frames.size
    }

    /** Forgets the window. */
    fun clear() {
        frames.fill(false)
        at = 0; filled = 0; speech = 0
    }
}

/** One candidate boundary: accepted as a change if [deltaBic] > 0. Numbers only, nothing that describes a voice. */
data class ChangeCandidate(
    val timeMs: Long,
    val kl2: Double,
    val deltaBic: Double,
    val accepted: Boolean,
    /** ΔBIC without the penalty, and the penalty for λ = 1: accepted iff gain > λ · penalty. For tuning λ. */
    val bicGain: Double = 0.0,
    val bicPenalty: Double = 0.0,
    /** Highest gain/penalty ratio of earlier candidates in the same group (0 = first of its group). */
    val groupBestRatioBefore: Double = 0.0,
    /** An earlier candidate of the same group was already accepted as a change. */
    val groupAlreadyAccepted: Boolean = false
) {
    /** BIC gain / penalty (λ-independent); accepted iff ratio > λ. */
    val ratio: Double get() = if (bicPenalty > 0) bicGain / bicPenalty else 0.0

    /** Evidence this candidate adds for base ratio [r0], counting its group once. */
    fun evidenceAdded(r0: Double): Double = maxOf(0.0, ratio - r0) - maxOf(0.0, groupBestRatioBefore - r0).coerceAtMost(maxOf(0.0, ratio - r0))

    /** True if this is the first accepted change of its group (what the count rule counts). */
    val countsAsChange: Boolean get() = accepted && !groupAlreadyAccepted
}

/**
 * Speaker change detection on a stream of MFCC frames (DISTBIC): every [ChangeDetectorConfig.stepFrames] speech frames
 * the two latest adjacent windows are modelled as Gaussians and compared with the symmetric KL distance; a clear KL2
 * peak is a candidate, and it is accepted as a change if ΔBIC > 0. Frames are kept only in a fixed ring buffer that
 * [clear] zeroes. Not thread-safe.
 * @param dim number of MFCC coefficients per frame
 * @param config detection settings
 */
class ChangeDetector(val dim: Int, private val config: ChangeDetectorConfig = ChangeDetectorConfig()) {

    private val capacity = 2 * config.windowFrames + 2 * config.stepFrames
    private val ring = DoubleArray(capacity * dim)
    private val ringTime = LongArray(capacity)

    /** Speech frames seen so far (absolute index of the next frame). */
    var speechFrames = 0L
        private set

    /** Levels of the last noiseWindowFrames frames (dB only, no audio), for the noise floor percentile. */
    private val levelRing = DoubleArray(config.noiseWindowFrames) { Double.NaN }
    private var levelIndex = 0
    private val loudRing = BooleanArray(config.speechRunFrames)
    private var loudCount = 0
    private var frameCount = 0L

    /** Current noise floor estimate (dBFS). */
    var noiseFloorDb = -70.0
        private set
    private var lastChangeFrame = Long.MIN_VALUE / 2
    private val silence = SilenceWindow(config.resetAfterSilenceMs, config.resetMaxSpeechShare)

    // The last three KL2 evaluations (value + boundary frame), to find local maxima.
    private val evalKl2 = DoubleArray(3)
    private val evalBoundary = LongArray(3)
    private var evalCount = 0
    private var kl2Mean = 0.0

    var lastKl2 = 0.0
        private set
    val runningKl2Mean: Double get() = kl2Mean
    var lastSpeechAtMs = Long.MIN_VALUE / 2
        private set
    var lastLevelDb = Double.NEGATIVE_INFINITY
        private set
    var lastFrameWasSpeech = false
        private set

    /** Results of the last frame's gate halves (Silero, level) and the level floor, for the debug timeline. */
    var lastSilero = false
        private set
    var lastLoud = false
        private set
    var lastLevelFloorDb = Double.NEGATIVE_INFINITY
        private set

    /**
     * Feeds one frame. [mfcc] is copied into the ring buffer (if speech), not kept.
     * @return a candidate if one was evaluated at this frame (accepted or not), else null
     */
    fun onFrame(timeMs: Long, levelDb: Double, mfcc: DoubleArray, speechProbability: Double? = null): ChangeCandidate? {
        lastLevelDb = levelDb
        // Noise floor = low percentile of recent levels: robust against both steady room noise and long speech.
        val level = if (levelDb.isFinite()) levelDb else -120.0
        levelRing[levelIndex] = level
        levelIndex = (levelIndex + 1) % levelRing.size
        frameCount++
        if (frameCount % FLOOR_UPDATE_FRAMES == 0L || frameCount == 50L) updateNoiseFloor()

        val loud = level > max(noiseFloorDb + config.speechMarginDb, config.speechMinDb)
        val runSlot = (frameCount % loudRing.size).toInt()
        if (loudRing[runSlot]) loudCount--
        loudRing[runSlot] = loud
        if (loud) loudCount++
        val speech = if (speechProbability != null) {
            // Silero decides; hysteresis keeps a word from flickering between speech and silence. The absolute minimum
            // level still applies, so near-silent frames never count.
            val threshold = if (lastFrameWasSpeech) config.sileroThreshold - config.sileroHysteresis else config.sileroThreshold
            lastSilero = speechProbability >= threshold
            lastLoud = level > config.speechMinDb - 10
            lastLevelFloorDb = config.speechMinDb - 10
            lastSilero && lastLoud
        } else {
            // Speech = loud AND part of a loud stretch, so isolated bangs and clicks do not enter the windows.
            lastSilero = false
            lastLoud = loud && loudCount >= config.speechRunMin
            lastLevelFloorDb = max(noiseFloorDb + config.speechMarginDb, config.speechMinDb)
            lastLoud
        }
        lastFrameWasSpeech = speech
        // Long (mostly) silence = new conversation: forget the old speech so it is not compared with whoever talks next.
        if (silence.add(speech) && speechFrames > 0) {
            resetSpeech()
            conversationResets++
            silence.clear()
        }
        if (!speech) return null
        lastSpeechAtMs = timeMs

        val slot = (speechFrames % capacity).toInt()
        mfcc.copyInto(ring, slot * dim, 0, dim)
        ringTime[slot] = timeMs
        speechFrames++

        return groupCandidates(evaluate())
    }

    /** A candidate before grouping, with its boundary (absolute speech-frame index). */
    private class Raw(val boundary: Long, val candidate: ChangeCandidate)

    private var groupStart = Long.MIN_VALUE / 2
    private var groupBestRatio = 0.0
    private var groupAccepted = false

    /**
     * Assigns a candidate to a group of candidates around the same boundary, so that each group counts only once.
     * @param raw the new candidate, or null
     * @return the candidate with its group information, or null
     */
    private fun groupCandidates(raw: Raw?): ChangeCandidate? {
        raw ?: return null
        val c = raw.candidate
        if (raw.boundary >= groupStart + config.groupFrames) {
            groupStart = raw.boundary
            groupBestRatio = 0.0
            groupAccepted = false
        }
        val reported = c.copy(groupBestRatioBefore = groupBestRatio, groupAlreadyAccepted = groupAccepted)
        groupBestRatio = maxOf(groupBestRatio, c.ratio)
        if (c.accepted) {
            if (!groupAccepted) lastChangeFrame = raw.boundary
            groupAccepted = true
        }
        return reported
    }

    /** Compares the two latest windows. @return a candidate at a qualifying KL2 peak, otherwise null */
    private fun evaluate(): Raw? {
        val w = config.windowFrames
        if (speechFrames < 2 * w || speechFrames % config.stepFrames != 0L) return null

        val boundary = speechFrames - w
        val left = fitRange(boundary - w, w) ?: return null
        val right = fitRange(boundary, w) ?: return null
        val kl2 = Gaussian.kl2(left, right)
        lastKl2 = kl2
        // Plain average for the first evaluations (a single early peak must not dominate), then a slow moving average.
        kl2Mean = if (evalCount < KL2_WARMUP) (kl2Mean * evalCount + kl2) / (evalCount + 1) else 0.98 * kl2Mean + 0.02 * kl2

        // Shift the 3-slot history and test whether the middle one is a peak.
        evalKl2[0] = evalKl2[1]; evalKl2[1] = evalKl2[2]; evalKl2[2] = kl2
        evalBoundary[0] = evalBoundary[1]; evalBoundary[1] = evalBoundary[2]; evalBoundary[2] = boundary
        evalCount++
        if (evalCount < 3) return null
        val peak = evalKl2[1]
        val peakBoundary = evalBoundary[1]
        if (!(peak > evalKl2[0] && peak >= evalKl2[2])) return null
        if (peak < config.kl2Relative * kl2Mean) return null
        if (peakBoundary - lastChangeFrame < config.minFramesBetweenChanges) return null

        val first = fitRange(peakBoundary - w, w) ?: return null
        val second = fitRange(peakBoundary, w) ?: return null
        val whole = fitRange(peakBoundary - w, 2 * w) ?: return null
        val gain = Gaussian.bicGain(whole, first, second)
        val penalty = Gaussian.bicPenalty(dim, whole.count)
        val deltaBic = gain - config.bicLambda * penalty
        val accepted = deltaBic > 0
        val time = ringTime[(peakBoundary % capacity).toInt()]
        return Raw(peakBoundary, ChangeCandidate(time, peak, deltaBic, accepted, gain, penalty))
    }

    /** Noise floor = 10th percentile of the levels of the last 5 s. */
    private fun updateNoiseFloor() {
        val known = levelRing.filter { !it.isNaN() }
        if (known.size < 50) return
        val sorted = known.sorted()
        noiseFloorDb = sorted[(config.noisePercentile * (sorted.size - 1)).toInt()]
    }

    /** Gaussian over [count] speech frames starting at absolute speech-frame index [start] (must still be in the ring). */
    private fun fitRange(start: Long, count: Int): Gaussian? {
        if (start < speechFrames - capacity || start + count > speechFrames) return null
        return Gaussian.fit(ring, dim, (start % capacity).toInt(), count)
    }

    /** Increments on every reset after long silence; [SpeakerChangeDetector] drops its counted changes then. */
    var conversationResets = 0
        private set

    /** Zeroes the buffered speech features and the comparison state (noise floor is kept). */
    private fun resetSpeech() {
        ring.fill(0.0)
        ringTime.fill(0L)
        speechFrames = 0
        evalCount = 0
        kl2Mean = 0.0
        lastKl2 = 0.0
        lastChangeFrame = Long.MIN_VALUE / 2
        groupStart = Long.MIN_VALUE / 2
        groupBestRatio = 0.0
        groupAccepted = false
    }

    /** Zeroes all buffered features and resets the state. */
    fun clear() {
        resetSpeech()
        silence.clear()
        noiseFloorDb = -70.0
        levelRing.fill(Double.NaN)
        loudRing.fill(false)
        loudCount = 0
        frameCount = 0
    }

    private companion object {
        const val FLOOR_UPDATE_FRAMES = 25L
        const val KL2_WARMUP = 50
    }
}
