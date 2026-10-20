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
 * [ConversationDetector] that compares pieces of speech with each other: each new embedding is compared with the
 * pieces held in the window, and a median similarity below [ConversationSettings.differentVoiceBelow] means two
 * voices. Embeddings are kept in memory only for the window and zeroed on reset and stop. A stored cohort mean is used
 * to centre the comparison.
 * @param context used to load the models and the cohort
 * @param source audio input
 * @param settings detection settings
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

    @Volatile
    override var debugTimeline: Boolean = false
    private val debug = DebugTimeline()
    private var lastStateForDebug = ConversationState.NO_SPEECH

    @Synchronized
    override fun start() {
        if (thread != null) return
        thread = Thread(::run, "PairwiseVoiceDetector").apply {
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

    /** One piece of speech: its centred embedding and when it was heard. */
    private class Piece(val embedding: FloatArray, val timeMs: Long)

    /**
     * The detector thread: reads the microphone, gates speech, cuts pieces and compares each new embedding with the held pieces.
     */
    private fun run() {
        val cohort = OwnerVoiceprintStore.get(context).loadCohort()
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
        val held = ArrayDeque<Piece>()
        // Outlier filter (settings.confirmTwice): a piece that read "another voice" waits HERE, outside `held`, until the
        // next piece says the same. Before, it went into `held` at once, so the next normal piece was compared with the
        // outlier itself and "confirmed" it.
        var pending: Piece? = null
        var readings = emptyList<Float>()
        var wasDifferent = false
        var differentUntil = Long.MIN_VALUE / 2
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastResultAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L
        var worker: EmbeddingWorker<FloatArray>? = null
        val silence = SilenceWindow(settings.resetAfterSilenceMs, settings.resetMaxSpeechShare)
        // Load counters for the performance recording (numbers only, see AudioLoad).
        val load = AudioLoad(TAG, "compare pieces, another voice when the median is below %.2f, %d pieces held, window %d s, ".format(
            settings.differentVoiceBelow, settings.piecesKept, settings.windowMs / 1000) +
            "%s, confirm twice %b, pieces %s, gate %s, silero %.2f (release %.2f), floor %.0f dBFS".format(
                if (cohort != null) "cohort centred" else "no cohort", settings.confirmTwice, settings.assemblyText(),
                settings.gateMode, settings.speechThreshold, settings.sileroRelease, settings.minLevelDb))

        /** Forgets every held voice. */
        fun forget() {
            held.forEach { it.embedding.fill(0f) }
            held.clear()
            pending?.embedding?.fill(0f)
            pending = null
        }

        fun hold(piece: Piece) {
            held.addLast(piece)
            while (held.size > settings.piecesKept) held.removeFirst().embedding.fill(0f)
        }

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
            // The embedding runs on its own thread, so the microphone is read all the time. It hands back the
            // CENTRED vector, which this thread keeps in `held` and zeroes when it is dropped.
            worker = EmbeddingWorker(context, "PairwiseVoiceEmbed", capacity = 1,
                compute = { embedding, _ -> centre(embedding, cohort) },
                discard = { it.fill(0f) },
                onEnd = { cohort?.fill(0f) })
            Log.i(TAG, "comparing voices with each other (different below %.2f, %d pieces held, %s)".format(
                settings.differentVoiceBelow, settings.piecesKept,
                if (cohort != null) "centred" else "not centred — record other voices for a better comparison"))

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
                // The robot's own voice would read as another voice (see RobotSpeaking and PieceAssembler).
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
                // speechSamples too, see OwnerVoiceDetector.
                // Silence = at most 10 % speech over the window (owner, 2026-10-19), not a single frame.
                if (silence.add(speech) && (speechSamples > 0 || held.isNotEmpty() || pending != null)) {
                    silence.clear()
                    forget()
                    readings = emptyList()
                    wasDifferent = false
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
                    val piece = Piece(done.value, done.piece.timeMs)
                    if (held.isEmpty()) {
                        hold(piece)
                        continue
                    }
                    // The MEDIAN of the comparisons decides, not the lowest: the minimum of up to six noisy pairs sank the
                    // more pieces were held, and one outlier in the window pulled every later minimum down.
                    val similarities = held.map { SpeakerEmbedder.similarity(piece.embedding, it.embedding) }.sorted()
                    val median = if (similarities.size % 2 == 1) similarities[similarities.size / 2]
                    else (similarities[similarities.size / 2 - 1] + similarities[similarities.size / 2]) / 2
                    readings = (listOf(median) + readings).take(READINGS_KEPT)
                    val different = median < settings.differentVoiceBelow
                    var note = ""
                    val waiting = pending
                    when {
                        !settings.confirmTwice -> {
                            if (different) differentUntil = timeMs + settings.windowMs
                            hold(piece)
                        }
                        waiting != null && different -> {
                            // The piece after the outlier is also far from the voices held before it: two voices.
                            differentUntil = timeMs + settings.windowMs
                            hold(waiting)
                            hold(piece)
                            pending = null
                            note = " (confirmed)"
                        }
                        waiting != null -> {
                            waiting.embedding.fill(0f)
                            pending = null
                            hold(piece)
                            note = " (the piece before it was dropped, not repeated)"
                        }
                        different && wasDifferent -> {
                            // A run of "another voice" keeps counting at once; only the first one has to be repeated.
                            differentUntil = timeMs + settings.windowMs
                            hold(piece)
                        }
                        different -> {
                            pending = piece
                            note = " (waiting for a second piece)"
                        }
                        else -> hold(piece)
                    }
                    wasDifferent = different && note != " (waiting for a second piece)"
                    Log.i(TAG, "piece: median similarity to the %d held pieces %.3f (lowest %.3f) -> %s%s".format(
                        similarities.size, median, similarities.first(), if (different) "another voice" else "same voice", note))
                }
                // Pieces older than the window say nothing about who is in the room now.
                while (held.isNotEmpty() && timeMs - held.first().timeMs > settings.windowMs) {
                    held.removeFirst().embedding.fill(0f)
                }
                pending?.let { if (timeMs - it.timeMs > settings.windowMs) { it.embedding.fill(0f); pending = null } }

                load.tick(timeMs)

                if (timeMs - lastPublish >= PUBLISH_MS) {
                    lastPublish = timeMs
                    val speechRecently = timeMs - maxOf(lastSpeechAtMs, lastResultAtMs) <= settings.speechHoldMs
                    val multiple = timeMs <= differentUntil
                    val state = when {
                        !speechRecently -> ConversationState.NO_SPEECH
                        multiple -> ConversationState.MULTIPLE_SPEAKERS
                        held.size < 2 -> ConversationState.LISTENING
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
                        method = DetectionMethod.PAIRWISE,
                        ownerThreshold = settings.differentVoiceBelow,
                        pairwiseMin = readings.firstOrNull(),
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
            runCatching { source.close() }
            if (worker != null) worker.stop() else cohort?.fill(0f)
            // Results the worker still hands back are erased by its own discard.
            while (true) (worker?.poll() ?: break).value.fill(0f)
            forget()
            pieces.clear()
            read.fill(0)
            hop.fill(0)
            chunk.fill(0)
            runCatching { vad?.close() }
            _snapshot.value = _snapshot.value.copy(running = false)
            thread = null
        }
    }

    /** Subtracts the cohort mean and renormalises, so the room and the microphone cancel out of the comparison. */
    private fun centre(embedding: FloatArray, cohort: FloatArray?): FloatArray {
        // Runs on the embedding thread; the cohort is only read there and erased by the worker when it ends.
        if (cohort == null || cohort.size != embedding.size) return embedding.copyOf()
        val out = FloatArray(embedding.size) { embedding[it] - cohort[it] }
        return SpeakerEmbedder.normalise(out)
    }

    private companion object {
        const val TAG = "PairwiseVoice"
        const val PUBLISH_MS = 100L

        /** How long stop() waits for the thread to let go of the microphone. */
        const val STOP_TIMEOUT_MS = 1_000L
        const val READINGS_KEPT = 8
    }
}
