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
 * [ConversationDetector] that compares each piece of speech with the stored owner voice. Only similarity values are
 * kept.
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

    /** Starts the detection thread. */
    @Synchronized
    override fun start() {
        if (thread != null) return
        thread = Thread(::run, "OwnerVoiceDetector").apply {
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
        val margin = template.info.margin
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
        val load = AudioLoad(TAG, "owner comparison, threshold %.2f, margin %.2f, %d pieces for somebody else, ".format(
            threshold, margin, settings.piecesForOther) +
            "window %d s, %s, confirm twice %b, pieces %s, gate %s, silero %.2f (release %.2f), floor %.0f dBFS".format(
                settings.windowMs / 1000, if (cohort != null) "cohort centred" else "no cohort",
                settings.confirmTwice, settings.assemblyText(),
                settings.gateMode, settings.speechThreshold, settings.sileroRelease, settings.minLevelDb))

        val ownerTimes = ArrayDeque<Long>()
        val otherTimes = ArrayDeque<Long>()
        var readings = emptyList<Float>()
        var confirmedVerdict = UNCLEAR
        var pendingVerdict = UNCLEAR
        var pendingTimeMs = 0L
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastResultAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L
        var worker: EmbeddingWorker<Float>? = null
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
                    if (!worker.offer(piece)) Log.w(TAG, "embedding still busy: piece dropped")
                }

                if (silence.add(speech) && (speechSamples > 0 || ownerTimes.isNotEmpty() || otherTimes.isNotEmpty())) {
                    silence.clear()
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
                    when {
                        !settings.confirmTwice -> {
                            if (verdict == OWNER) ownerTimes.addLast(done.piece.timeMs)
                            else if (verdict == OTHER) otherTimes.addLast(done.piece.timeMs)
                            if (verdict != UNCLEAR) confirmedVerdict = verdict
                        }
                        verdict == UNCLEAR -> {
                            if (pendingVerdict != UNCLEAR) note = " (dropped, the next piece was unclear)"
                            pendingVerdict = UNCLEAR
                        }
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
        } catch (e: Exception) {
            Log.e(TAG, "detector stopped", e)
            _snapshot.value = _snapshot.value.copy(error = e.message ?: e.toString())
        } finally {
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

        const val STOP_TIMEOUT_MS = 1_000L
        const val READINGS_KEPT = 8

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
 * Settings of the voice detectors and of the piece assembly.
 * @property pieceSeconds speech per piece in seconds
 * @property speechThreshold Silero probability at which speech starts
 * @property speechHysteresis drop below the threshold at which speech ends
 * @property minLevelDb level floor in dBFS
 * @property gateMode speech gate mode
 * @property margin owner method: width of the unclear band below the threshold
 * @property piecesForOther owner method: "other" pieces needed in the window
 * @property anyOtherIsConversation owner method: true = any other voice counts as a conversation, false = the owner
 *   must also have been heard
 * @property windowMs decision window in milliseconds
 * @property speechHoldMs time after speech that still counts as talking
 * @property resetAfterSilenceMs silence after which the conversation is reset
 * @property resetMaxSpeechShare share of speech that still counts as silence
 * @property differentVoiceBelow pairwise threshold for different voices
 * @property piecesKept pieces held for pairwise comparison
 * @property activityWindowMs window of the activity method
 * @property activityShare share of speech for a conversation
 * @property activityMinSilenceMs gaps shorter than this count as speech
 * @property confirmTwice whether a changed verdict needs confirmation
 * @property continuousPieces whether pieces are cut as a continuous stretch
 * @property speechPadMs audio kept before the first speech frame
 * @property bridgeGapMs pauses up to this length stay in the piece
 * @property maxPieceGapMs a longer pause ends the piece
 * @property minPieceSeconds shortest piece that is embedded
 * @property splitAtChanges whether pieces are split at voice changes
 */
data class ConversationSettings(
    val pieceSeconds: Double = 3.0,
    val speechThreshold: Double = 0.5,
    val speechHysteresis: Double = 0.15,
    val minLevelDb: Double = -65.0,
    val gateMode: SpeechGateMode = SpeechGateMode.SILERO_AND_LEVEL,
    val margin: Float = VoiceprintInfo.DEFAULT_MARGIN,
    val piecesForOther: Int = 2,
    val anyOtherIsConversation: Boolean = false,
    val windowMs: Long = 20_000,
    val speechHoldMs: Long = 1_500,
    val resetAfterSilenceMs: Long = 30_000,
    val resetMaxSpeechShare: Double = 0.10,
    val differentVoiceBelow: Float = 0.5f,
    val piecesKept: Int = 6,
    val activityWindowMs: Long = 8_000,
    val activityShare: Float = 0.5f,
    val activityMinSilenceMs: Long = 250,
    val confirmTwice: Boolean = false,
    val continuousPieces: Boolean = false,
    val speechPadMs: Long = 30,
    val bridgeGapMs: Long = 200,
    val maxPieceGapMs: Long = 2_000,
    val minPieceSeconds: Double = 1.5,
    val splitAtChanges: Boolean = true
) {
    val sileroRelease: Double get() = maxOf(speechThreshold - speechHysteresis, speechThreshold / 2)

    /**
     * How pieces are cut, for log lines.
     * @return description of the assembly
     */
    fun assemblyText(): String =
        (if (continuousPieces) "continuous (+%d ms pad, %d ms gaps kept)".format(speechPadMs, bridgeGapMs) else "gated frames only") +
            ", pause > %d ms ends a piece".format(maxPieceGapMs) + if (splitAtChanges) ", split at voice changes" else ""
}
