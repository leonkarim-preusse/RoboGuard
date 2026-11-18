package com.example.robocontrol.conversation

import kotlin.math.max

/** How "more than one speaker" is decided from the change candidates. Both are always computed; this one is shown. */
enum class DecisionRule {
    COUNT,
    EVIDENCE
}

/** How a frame is judged to be speech before it may enter the comparison windows. */
enum class SpeechGate {
    LOUDNESS,
    SILERO
}

/**
 * Settings of [ChangeDetector]. A frame is one MFCC vector per 10 ms.
 * @property speechGate speech gate to use
 * @property sileroThreshold Silero probability at which speech starts
 * @property sileroHysteresis drop below the threshold at which speech ends
 * @property windowFrames speech frames per comparison window
 * @property stepFrames speech frames between two comparisons
 * @property bicLambda penalty weight of ΔBIC
 * @property kl2Relative factor over the running KL2 mean a candidate needs
 * @property groupFrames speech frames within which candidates form one group
 * @property minFramesBetweenChanges minimum speech frames between two changes
 * @property speechMarginDb margin above the noise floor for speech, in dB
 * @property speechMinDb absolute minimum level for speech, in dBFS
 * @property speechRunMin loud frames needed among the last [speechRunFrames]
 * @property speechRunFrames frames checked for [speechRunMin]
 * @property noisePercentile percentile of frame levels used as noise floor
 * @property noiseWindowFrames frames the noise floor is computed over
 * @property decisionWindowMs time over which changes are counted
 * @property minChangesForMultiple changes needed for more than one speaker
 * @property decisionRule rule that decides the shown state
 * @property evidenceBaseRatio ratio a candidate must exceed to add evidence
 * @property evidenceHalfLifeMs half-life of the evidence
 * @property evidenceThreshold evidence needed for more than one speaker
 * @property speechHoldMs time after speech that still counts as talking
 * @property resetAfterSilenceMs silence after which the conversation is reset
 * @property resetMaxSpeechShare share of speech that still counts as silence
 */
data class ChangeDetectorConfig(
    val speechGate: SpeechGate = SpeechGate.SILERO,
    val sileroThreshold: Double = 0.5,
    val sileroHysteresis: Double = 0.15,
    val windowFrames: Int = 150,
    val stepFrames: Int = 10,
    val bicLambda: Double = 2.0,
    val kl2Relative: Double = 1.3,
    val groupFrames: Int = 50,
    val minFramesBetweenChanges: Int = 100,
    val speechMarginDb: Double = 15.0,
    val speechMinDb: Double = -55.0,
    val speechRunMin: Int = 6,
    val speechRunFrames: Int = 20,
    val noisePercentile: Double = 0.10,
    val noiseWindowFrames: Int = 500,
    val decisionWindowMs: Long = 20_000,
    val minChangesForMultiple: Int = 2,
    val decisionRule: DecisionRule = DecisionRule.EVIDENCE,
    val evidenceBaseRatio: Double = 1.8,
    val evidenceHalfLifeMs: Long = 5_000,
    val evidenceThreshold: Double = 0.8,
    val speechHoldMs: Long = 1_500,
    val resetAfterSilenceMs: Long = 30_000,
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

    /**
     * Adds one frame; true if the window is full and quiet.
     * @param isSpeech whether the frame contains speech
     * @return true if the window is full and quiet
     */
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

/**
 * One candidate boundary: accepted as a change if [deltaBic] > 0.
 * @property timeMs audio time of the boundary
 * @property kl2 symmetric KL distance at the boundary
 * @property deltaBic ΔBIC of the boundary
 * @property accepted whether it was accepted as a change
 * @property bicGain ΔBIC without the penalty
 * @property bicPenalty ΔBIC penalty for λ = 1
 * @property groupBestRatioBefore best gain/penalty ratio of earlier candidates in the group
 * @property groupAlreadyAccepted whether an earlier candidate of the group was accepted
 */
data class ChangeCandidate(
    val timeMs: Long,
    val kl2: Double,
    val deltaBic: Double,
    val accepted: Boolean,
    val bicGain: Double = 0.0,
    val bicPenalty: Double = 0.0,
    val groupBestRatioBefore: Double = 0.0,
    val groupAlreadyAccepted: Boolean = false
) {
    val ratio: Double get() = if (bicPenalty > 0) bicGain / bicPenalty else 0.0

    /**
     * Evidence this candidate adds for base ratio [r0], counting its group once.
     * @param r0 base ratio of the evidence rule
     * @return added evidence
     */
    fun evidenceAdded(r0: Double): Double = maxOf(0.0, ratio - r0) - maxOf(0.0, groupBestRatioBefore - r0).coerceAtMost(maxOf(0.0, ratio - r0))

    val countsAsChange: Boolean get() = accepted && !groupAlreadyAccepted
}

/**
 * Speaker change detection on MFCC frames (DISTBIC): adjacent windows are compared with KL2 and confirmed with ΔBIC.
 * Not thread-safe.
 * @param dim number of MFCC coefficients per frame
 * @param config detection settings
 */
class ChangeDetector(val dim: Int, private val config: ChangeDetectorConfig = ChangeDetectorConfig()) {
    private val capacity = 2 * config.windowFrames + 2 * config.stepFrames
    private val ring = DoubleArray(capacity * dim)
    private val ringTime = LongArray(capacity)

    var speechFrames = 0L
        private set

    private val levelRing = DoubleArray(config.noiseWindowFrames) { Double.NaN }
    private var levelIndex = 0
    private val loudRing = BooleanArray(config.speechRunFrames)
    private var loudCount = 0
    private var frameCount = 0L

    var noiseFloorDb = -70.0
        private set
    private var lastChangeFrame = Long.MIN_VALUE / 2
    private val silence = SilenceWindow(config.resetAfterSilenceMs, config.resetMaxSpeechShare)

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

    var lastSilero = false
        private set
    var lastLoud = false
        private set
    var lastLevelFloorDb = Double.NEGATIVE_INFINITY
        private set

    /**
     * Feeds one frame. [mfcc] is copied into the ring buffer (if speech), not kept.
     * @param timeMs audio time of the frame
     * @param levelDb frame level in dBFS
     * @param mfcc MFCC vector of the frame
     * @param speechProbability Silero probability, or null for the loudness gate
     * @return a candidate if one was evaluated at this frame (accepted or not), else null
     */
    fun onFrame(timeMs: Long, levelDb: Double, mfcc: DoubleArray, speechProbability: Double? = null): ChangeCandidate? {
        lastLevelDb = levelDb
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
            val threshold = if (lastFrameWasSpeech) config.sileroThreshold - config.sileroHysteresis else config.sileroThreshold
            lastSilero = speechProbability >= threshold
            lastLoud = level > config.speechMinDb - 10
            lastLevelFloorDb = config.speechMinDb - 10
            lastSilero && lastLoud
        } else {
            lastSilero = false
            lastLoud = loud && loudCount >= config.speechRunMin
            lastLevelFloorDb = max(noiseFloorDb + config.speechMarginDb, config.speechMinDb)
            lastLoud
        }
        lastFrameWasSpeech = speech
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

    /**
     * A candidate before grouping, with its boundary (absolute speech-frame index).
     * @property boundary absolute speech-frame index of the boundary
     * @property candidate the candidate
     */
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
        kl2Mean = if (evalCount < KL2_WARMUP) (kl2Mean * evalCount + kl2) / (evalCount + 1) else 0.98 * kl2Mean + 0.02 * kl2

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

    /**
     * Fits a Gaussian over [count] speech frames from index [start].
     * @param start absolute speech-frame index
     * @param count number of frames
     * @return the Gaussian, or null if the frames are no longer in the buffer
     */
    private fun fitRange(start: Long, count: Int): Gaussian? {
        if (start < speechFrames - capacity || start + count > speechFrames) return null
        return Gaussian.fit(ring, dim, (start % capacity).toInt(), count)
    }

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
