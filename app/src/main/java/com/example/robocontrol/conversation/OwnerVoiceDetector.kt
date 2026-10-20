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
 * [ConversationDetector] that compares each piece of speech with the stored owner voice ([OwnerVoiceprint]).
 * Each piece of [ConversationSettings.pieceSeconds] of speech is turned into an embedding and compared with the
 * template: at or above the threshold it counts as the owner, below the threshold minus the margin as another person.
 * More than one speaker is reported when the owner and another person were both heard within
 * [ConversationSettings.windowMs], or, with [ConversationSettings.anyOtherIsConversation], as soon as another person
 * is heard. Only similarity values are kept; embeddings and audio buffers are zeroed after use.
 * @param context used to load the stored voice and the models
 * @param source audio input
 * @param settings detection settings
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

    @Volatile
    override var debugTimeline: Boolean = false
    private val debug = DebugTimeline()
    private var lastStateForDebug = ConversationState.NO_SPEECH

    @Synchronized
    override fun start() {
        if (thread != null) return
        thread = Thread(::run, "OwnerVoiceDetector").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun stop(): Boolean {
        val t = thread
        thread = null
        t?.interrupt()
        // Wait for the thread to close the microphone. The next detector opens it immediately afterwards, and two
        // AudioRecords on the same device give silence on Android 9 rather than an error.
        runCatching { t?.join(STOP_TIMEOUT_MS) }
        return t == null || !t.isAlive
    }

    /**
     * The detector thread: reads the microphone, gates speech, cuts pieces, compares each embedding with the stored voice
     * and publishes snapshots. Erases every buffer before it ends.
     */
    private fun run() {
        val store = OwnerVoiceprintStore.get(context)
        val template = store.load()
        if (template == null) {
            _snapshot.value = ConversationSnapshot(
                method = DetectionMethod.OWNER,
                running = false,
                error = store.problem() ?: "no voice stored: teach the robot the owner's voice first"
            )
            thread = null
            return
        }
        val threshold = template.info.threshold
        // The unclear band belongs to the voice; the setting is only the fallback for very old files.
        val margin = template.info.margin
        // Average of other voices through this microphone; subtracted from both sides when comparing (see Voiceprint).
        val cohort = store.loadCohort()
        var vad: SileroVad? = null
        val read = ShortArray(SPEECH_HOP)
        val hop = ShortArray(SPEECH_HOP)
        val chunk = ShortArray(512)
        var chunkFill = 0
        var probability = 0.0
        val speaking = RobotSpeaking(context)
        var wasRobotTalking = false
        val gate = SpeechGateState(settings)
        val pieces = PieceAssembler(settings, source.sampleRate)
        // Load counters for the performance recording (numbers only, see AudioLoad).
        val load = AudioLoad(TAG, "owner comparison, threshold %.2f, margin %.2f, %d pieces for somebody else, ".format(
            threshold, margin, settings.piecesForOther) +
            "window %d s, %s, confirm twice %b, pieces %s, gate %s, silero %.2f (release %.2f), floor %.0f dBFS".format(
                settings.windowMs / 1000, if (cohort != null) "cohort centred" else "no cohort",
                settings.confirmTwice, settings.assemblyText(),
                settings.gateMode, settings.speechThreshold, settings.sileroRelease, settings.minLevelDb))

        // Decision window: times only, no vectors.
        val ownerTimes = ArrayDeque<Long>()
        val otherTimes = ArrayDeque<Long>()
        var readings = emptyList<Float>()
        // Outlier filter (settings.confirmTwice): the verdict that is currently accepted, and the one waiting to be said
        // a second time.
        var confirmedVerdict = UNCLEAR
        var pendingVerdict = UNCLEAR
        var pendingTimeMs = 0L
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        // When the last verdict came back: it arrives up to a few seconds after its speech, and must not be hidden by
        // "nobody is talking" just because the turn ended meanwhile.
        var lastResultAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L
        var worker: EmbeddingWorker<Float>? = null
        val silence = SilenceWindow(settings.resetAfterSilenceMs, settings.resetMaxSpeechShare)

        try {
            // The microphone first: if it cannot be opened, no model has been loaded for nothing.
            val openError = source.open()
            if (openError != null) {
                _snapshot.value = _snapshot.value.copy(running = false, error = openError)
                return
            }
            if (settings.gateMode != SpeechGateMode.LEVEL_ONLY) vad = SileroVad(context)
            // Audio that is not read in time is gone for good, unlike a camera frame: this loop outranks the detection
            // workers (which run at THREAD_PRIORITY_URGENT_DISPLAY, -8).
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            // One line the graphs can read the threshold lines from (performance/graphs.py).
            Log.i(TAG, "gate: %s, silero %.2f (hysteresis %.2f), level floor %.0f dBFS".format(
                settings.gateMode, settings.speechThreshold, settings.speechHysteresis, settings.minLevelDb))
            // The embedding thread owns the model and erases the template when it ends (see EmbeddingWorker).
            worker = EmbeddingWorker(context, "OwnerVoiceEmbed", capacity = 1,
                compute = { embedding, _ -> template.similarityTo(embedding, cohort) },
                onEnd = { template.zero(); cohort?.fill(0f) })
            Log.i(TAG, "listening for the owner's voice (threshold %.2f, margin %.2f, %s)".format(
                threshold, margin,
                if (cohort != null) "centred on ${cohort.size} values of other voices" else "no cohort recorded"))

            while (!Thread.currentThread().isInterrupted) {
                if (!readHop(source, read, hop)) {
                    _snapshot.value = _snapshot.value.copy(running = false, error = "microphone stopped delivering audio")
                    return
                }
                samples += SPEECH_HOP
                val timeMs = samples * 1000 / source.sampleRate

                var i = 0
                while (i < SPEECH_HOP) {
                    val take = minOf(SPEECH_HOP - i, chunk.size - chunkFill)
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
                val levelDb = hopLevelDb(hop)
                // The robot's own voice comes back through its microphone and is not the owner's, so it would read as
                // another speaker: while it plays anything, nothing counts as speech (see PieceAssembler).
                val robotTalking = speaking.active(timeMs)
                if (robotTalking != wasRobotTalking) {
                    Log.i(TAG, if (robotTalking) "robot is speaking: ignoring the microphone until it stops"
                    else "robot stopped speaking: listening again")
                    wasRobotTalking = robotTalking
                }
                val gated = gate.decide(probability, levelDb)
                val speech = !robotTalking && gated
                load.frame(speech, gate.silero, gate.loud, levelDb, probability)
                debug.frame(debugTimeline, AudioFrame(timeMs, levelDb, if (vad != null) probability else null, speech,
                    gate.silero, gate.loud, robotTalking))

                if (speech) {
                    lastSpeechAtMs = timeMs
                    speechSamples += SPEECH_HOP
                }
                for (piece in pieces.onHop(hop, timeMs, levelDb, gated, robotTalking)) {
                    // One at a time: a piece judged seconds late says nothing about the room now.
                    if (!worker.offer(piece)) Log.w(TAG, "embedding still busy: piece dropped")
                }

                // speechSamples too: after the robot spoke the half piece is already gone and the window entries expire
                // after 20 s, so without it the reset never ran and the next conversation got no prompt. It
                // cannot repeat: speechSamples only grows on speech, which also moves lastSpeechAtMs.
                // Silence = at most 10 % speech over the window (owner, 2026-10-19), not a single frame.
                if (silence.add(speech) && (speechSamples > 0 || ownerTimes.isNotEmpty() || otherTimes.isNotEmpty())) {
                    silence.clear()
                    // Long silence = new conversation.
                    ownerTimes.clear()
                    otherTimes.clear()
                    readings = emptyList()
                    confirmedVerdict = UNCLEAR
                    pendingVerdict = UNCLEAR
                    speechSamples = 0
                    pieces.clear()
                    gate.reset()
                    vad?.reset()
                    Log.i(TAG, "reset after ${settings.resetAfterSilenceMs} ms of silence")
                    debug.event(debugTimeline, ConversationEvent(timeMs, ConversationEventKind.RESET))
                }

                // Results come back on the next turn through the loop, so all the decision state stays on this thread.
                worker.error?.let { throw IllegalStateException(it) }
                while (true) {
                    val done = worker.poll() ?: break
                    load.piece(done.embedNs)
                    lastResultAtMs = timeMs
                    val similarity = done.value
                    val isOwner = similarity >= threshold
                    val isOther = similarity < threshold - margin
                    readings = (listOf(similarity) + readings).take(READINGS_KEPT)
                    val verdict = if (isOwner) OWNER else if (isOther) OTHER else UNCLEAR
                    var note = ""
                    // Without the filter every clear piece counts at once. With it, only a verdict that CHANGES has to be
                    // said twice; when it is confirmed, the held piece is entered with its own time, so nothing is lost,
                    // it is only delayed.
                    when {
                        !settings.confirmTwice -> {
                            if (verdict == OWNER) ownerTimes.addLast(done.piece.timeMs)
                            else if (verdict == OTHER) otherTimes.addLast(done.piece.timeMs)
                            if (verdict != UNCLEAR) confirmedVerdict = verdict
                        }
                        // The two pieces that confirm each other have to be the two pieces that FOLLOWED each other. An
                        // unclear piece in between is not a confirmation, so it breaks the chain (owner, 2026-09-28).
                        verdict == UNCLEAR -> {
                            if (pendingVerdict != UNCLEAR) note = " (dropped, the next piece was unclear)"
                            pendingVerdict = UNCLEAR
                        }
                        // A verdict that simply continues needs no second piece — only a CHANGE does.
                        verdict == confirmedVerdict -> {
                            if (verdict == OWNER) ownerTimes.addLast(done.piece.timeMs) else otherTimes.addLast(done.piece.timeMs)
                            pendingVerdict = UNCLEAR
                        }
                        verdict == pendingVerdict -> {
                            if (verdict == OWNER) { ownerTimes.addLast(pendingTimeMs); ownerTimes.addLast(done.piece.timeMs) }
                            else { otherTimes.addLast(pendingTimeMs); otherTimes.addLast(done.piece.timeMs) }
                            confirmedVerdict = verdict
                            pendingVerdict = UNCLEAR
                            note = " (confirmed by the piece right before it)"
                        }
                        else -> {
                            if (pendingVerdict != UNCLEAR) note = " (dropped, not repeated)"
                            pendingVerdict = verdict
                            pendingTimeMs = done.piece.timeMs
                            if (note.isEmpty()) note = " (waiting for the very next piece)"
                        }
                    }
                    Log.i(TAG, "piece: similarity %.3f -> %s%s · speech level %.0f dB over %.1f s (%s)".format(
                        similarity, if (isOwner) "owner" else if (isOther) "somebody else" else "unclear", note,
                        done.piece.levelDb, done.piece.spanMs / 1000.0, done.piece.reason))
                }
                while (ownerTimes.isNotEmpty() && timeMs - ownerTimes.first() > settings.windowMs) ownerTimes.removeFirst()
                while (otherTimes.isNotEmpty() && timeMs - otherTimes.first() > settings.windowMs) otherTimes.removeFirst()

                load.tick(timeMs)

                if (timeMs - lastPublish >= PUBLISH_MS) {
                    lastPublish = timeMs
                    val speechRecently = timeMs - maxOf(lastSpeechAtMs, lastResultAtMs) <= settings.speechHoldMs
                    val others = otherTimes.size >= settings.piecesForOther
                    val multiple = others && (settings.anyOtherIsConversation || ownerTimes.isNotEmpty())
                    val state = when {
                        !speechRecently -> ConversationState.NO_SPEECH
                        multiple -> ConversationState.MULTIPLE_SPEAKERS
                        readings.isEmpty() -> ConversationState.LISTENING
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
                        speechNow = speech,
                        speechProbability = probability,
                        speechGate = SpeechGate.SILERO,
                        speechSeconds = speechSamples / 16_000.0,
                        method = DetectionMethod.OWNER,
                        ownerSimilarity = readings.firstOrNull(),
                        ownerThreshold = threshold,
                        ownerMargin = margin,
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
            // The microphone first, so the next detector can open it at once. The embedding thread is only told to stop:
            // it finishes the run it is in, then closes the model and erases the voiceprint itself.
            runCatching { source.close() }
            if (worker != null) worker.stop() else { template.zero(); cohort?.fill(0f) }
            pieces.clear()
            read.fill(0)
            hop.fill(0)
            chunk.fill(0)
            runCatching { vad?.close() }
            _snapshot.value = _snapshot.value.copy(running = false)
            thread = null
        }
    }

    private companion object {
        const val TAG = "OwnerVoice"
        const val HOP = 160
        const val PUBLISH_MS = 100L

        /** How long stop() waits for the thread to let go of the microphone. */
        const val STOP_TIMEOUT_MS = 1_000L
        const val READINGS_KEPT = 8

        /** Verdict of one piece, for the outlier filter. */
        const val UNCLEAR = 0
        const val OWNER = 1
        const val OTHER = -1
    }
}

/**
 * Gate that decides whether a 10 ms frame contains speech: Silero and the level floor, Silero only, or the level floor only.
 */
enum class SpeechGateMode { SILERO_AND_LEVEL, SILERO_ONLY, LEVEL_ONLY }

/**
 * Settings of the voice detectors (owner, pairwise, activity) and of the piece assembly used by the voice enrolment.
 * @property margin owner method: width of the unclear band below the threshold
 * @property piecesForOther owner method: "other" pieces needed in the window
 * @property anyOtherIsConversation owner method: true = any other voice counts as a conversation, false = the owner
 * must also have been heard
 */
data class ConversationSettings(
    val pieceSeconds: Double = 3.0,
    val speechThreshold: Double = 0.5,
    /** Silero hysteresis: after speech has started it ends only below `speechThreshold - speechHysteresis`. */
    val speechHysteresis: Double = 0.15,
    val minLevelDb: Double = -65.0,
    val gateMode: SpeechGateMode = SpeechGateMode.SILERO_AND_LEVEL,
    /** Only for voices stored before the band was kept per voice ([VoiceprintInfo.margin] decides now). */
    val margin: Float = VoiceprintInfo.DEFAULT_MARGIN,
    val piecesForOther: Int = 2,
    val anyOtherIsConversation: Boolean = false,
    val windowMs: Long = 20_000,
    val speechHoldMs: Long = 1_500,
    val resetAfterSilenceMs: Long = 30_000,
    /** "Silence" for that reset may still contain this share of speech (see [SilenceWindow]). */
    val resetMaxSpeechShare: Double = 0.10,
    /**
     * Pairwise method: two pieces whose similarity is below this are treated as two different voices. Needs measuring on
     * the robot like every other number here — the screen shows the live comparisons and lets it be adjusted.
     */
    val differentVoiceBelow: Float = 0.5f,
    /** Pairwise method: how many pieces are held for comparison (in memory only). */
    val piecesKept: Int = 6,
    /** Activity method: length of the window over which the share of speech is measured. */
    val activityWindowMs: Long = 8_000,
    val activityShare: Float = 0.5f,
    /** Activity method: gaps shorter than this still count as speech. */
    val activityMinSilenceMs: Long = 250,
    /** If true, a changed verdict counts only when the next piece confirms it. */
    val confirmTwice: Boolean = false,
    /**
     * If true, pieces are cut as one continuous stretch: [speechPadMs] of audio before the first speech frame is included
     * and pauses shorter than [bridgeGapMs] are kept. If false, a piece consists of the speech frames only.
     */
    val continuousPieces: Boolean = false,
    val speechPadMs: Long = 30,
    val bridgeGapMs: Long = 200,
    /** A pause longer than this ends the current piece. */
    val maxPieceGapMs: Long = 2_000,
    val minPieceSeconds: Double = 1.5,
    /** If true, pieces are split at voice changes found by a change detector on the same audio. */
    val splitAtChanges: Boolean = true
) {
    /** Probability below which Silero speech ends: the threshold minus the hysteresis, but at least half the threshold. */
    val sileroRelease: Double get() = maxOf(speechThreshold - speechHysteresis, speechThreshold / 2)

    /** How pieces are cut, for log lines. */
    fun assemblyText(): String =
        (if (continuousPieces) "continuous (+%d ms pad, %d ms gaps kept)".format(speechPadMs, bridgeGapMs) else "gated frames only") +
            ", pause > %d ms ends a piece".format(maxPieceGapMs) + if (splitAtChanges) ", split at voice changes" else ""
}
