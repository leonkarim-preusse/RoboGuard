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
/** One finished piece on its way to the embedding thread. The samples are zeroed as soon as they have been used. */
private class PieceToEmbed(val samples: FloatArray, val timeMs: Long, val levelDb: Double, val spanMs: Long)

/** What comes back: numbers only, never a vector. */
private class PieceResult(
    val similarity: Float,
    val timeMs: Long,
    val levelDb: Double,
    val spanMs: Long,
    val embedNs: Long
)

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
        val t = thread
        thread = null
        t?.interrupt()
        // Wait for the thread to close the microphone. The next detector opens it immediately afterwards, and two
        // AudioRecords on the same device give silence on Android 9 rather than an error.
        runCatching { t?.join(STOP_TIMEOUT_MS) }
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
        // Continuous pieces: a ring of the newest hops, so the start of an utterance can be taken with its first
        // milliseconds instead of after them, plus the moment of the last gated frame for bridging short pauses.
        val preRollHops = ((settings.speechPadMs / 10).toInt()).coerceAtLeast(1)
        val preRoll = ShortArray(preRollHops * HOP)
        var preRollAt = 0
        var preRollFilled = 0
        var wasAppending = false
        var lastGateMs = Long.MIN_VALUE / 2
        // Load counters for the performance recording (numbers only, see AudioLoad).
        val load = AudioLoad(TAG, "owner comparison, threshold %.2f, margin %.2f, %d pieces for somebody else, ".format(
            template.info.threshold, settings.margin, settings.piecesForOther) +
            "window %d s, %s, confirm twice %b, pieces %s, gate %s, silero %.2f (hysteresis %.2f), floor %.0f dBFS".format(
                settings.windowMs / 1000, if (cohort != null) "cohort centred" else "no cohort",
                settings.confirmTwice,
                if (settings.continuousPieces) "continuous (+%d ms pad, %d ms gaps kept)".format(
                    settings.speechPadMs, settings.bridgeGapMs) else "gated frames only",
                settings.gateMode, settings.speechThreshold, settings.speechHysteresis, settings.minLevelDb))

        // Decision window: times only, no vectors.
        val ownerTimes = ArrayDeque<Long>()
        val otherTimes = ArrayDeque<Long>()
        var readings = emptyList<Float>()
        // Outlier filter (settings.confirmTwice): the verdict that is currently accepted, and the one waiting to be said
        // a second time.
        var confirmedVerdict = UNCLEAR
        var pendingVerdict = UNCLEAR
        var pendingTimeMs = 0L
        // Per piece: level of its speech frames, and when it started, for the log line.
        var pieceLevelSum = 0.0
        var pieceLevelFrames = 0
        var pieceStartedMs = -1L
        var speechSamples = 0L
        var samples = 0L
        var lastSpeechAtMs = Long.MIN_VALUE / 2
        var lastPublish = -1_000L

        // Handover to the embedding thread: one piece at a time (a piece judged seconds late is worthless) and the
        // results come back as plain numbers.
        val toEmbed = java.util.concurrent.ArrayBlockingQueue<PieceToEmbed>(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<PieceResult>()
        var embedThread: Thread? = null
        // Set by the embedding thread when it cannot go on (model did not load, unexpected error); the loop below ends then.
        val embedError = java.util.concurrent.atomic.AtomicReference<String?>(null)

        try {
            vad = SileroVad(context)
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
            // The embedding thread OWNS the speaker model: it loads it, and it alone closes it, after its last run has
            // returned. Closing the session from this thread while a run is still inside native code frees the model
            // under it and takes the whole process down (a run takes 0.6-3.4 s, far longer than any wait here).
            embedThread = Thread({
                var worker: SpeakerEmbedder? = null
                try {
                    worker = SpeakerEmbedder(context)
                    while (!Thread.currentThread().isInterrupted) {
                        val next = toEmbed.take()
                        val started = System.nanoTime()
                        val embedding = runCatching { worker.embed(next.samples, next.samples.size) }.getOrNull()
                        next.samples.fill(0f)
                        if (embedding != null) {
                            val similarity = template.similarityTo(embedding, cohort)
                            embedding.fill(0f)
                            results.add(PieceResult(similarity, next.timeMs, next.levelDb, next.spanMs,
                                System.nanoTime() - started))
                        }
                    }
                } catch (_: InterruptedException) {
                    // stop() while waiting for a piece: a normal end
                } catch (e: Exception) {
                    Log.e(TAG, "embedding stopped", e)
                    embedError.set("speaker model: " + (e.message ?: e.toString()))
                } finally {
                    runCatching { worker?.close() }
                    while (true) (toEmbed.poll() ?: break).samples.fill(0f)
                    template.zero()
                    cohort?.fill(0f)
                }
            }, "OwnerVoiceEmbed").apply { isDaemon = true; start() }
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
                    // Only the frames that carry speech, so the number says how loud the PERSON was, not how quiet the
                    // pauses were: the closest thing to a distance for each single reading.
                    pieceLevelSum += levelDb
                    pieceLevelFrames++
                    if (pieceStartedMs < 0) pieceStartedMs = timeMs
                }
                // Which frames end up IN the piece (see ConversationSettings.continuousPieces).
                val appending = if (settings.continuousPieces) {
                    !robotTalking && (speech || timeMs - lastGateMs <= settings.bridgeGapMs)
                } else speech
                if (appending) {
                    if (!wasAppending) {
                        // Start of an utterance: take the padding that is already in the ring, oldest first.
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
                // The half-filled piece counts too, not only the decision window: the window empties itself after
                // windowMs, so with the old condition the reset could never fire afterwards, and a piece that was half
                // full when somebody stopped talking waited for MINUTES and was then completed with audio from a
                // different situation. Measured on the robot 2026-09-28: a piece spanning 322 s read 0.394 and the one
                // after it 0.214, both from the owner alone.
                if (!speech && timeMs - lastSpeechAtMs >= settings.resetAfterSilenceMs &&
                    (fill > 0 || ownerTimes.isNotEmpty() || otherTimes.isNotEmpty())) {
                    // Long silence = new conversation.
                    ownerTimes.clear()
                    otherTimes.clear()
                    readings = emptyList()
                    confirmedVerdict = UNCLEAR
                    pendingVerdict = UNCLEAR
                    speechSamples = 0
                    fill = 0
                    piece.fill(0f)
                    pieceLevelSum = 0.0
                    pieceLevelFrames = 0
                    pieceStartedMs = -1L
                    vad.reset()
                    Log.i(TAG, "reset after ${settings.resetAfterSilenceMs} ms of silence")
                }

                // A full piece is handed to the embedding thread and the loop goes straight back to the microphone.
                // The embedding needs 0.7 s on an idle robot and up to 3.4 s under load; doing it here meant the
                // microphone was not read for that long, and the audio beyond the buffer was lost — right in the middle
                // of the next words (measured 2026-09-28: audio/clock 0.76-0.87 while the owner was speaking normally).
                if (fill >= piece.size) {
                    val ready = PieceToEmbed(
                        samples = piece.copyOf(),
                        timeMs = timeMs,
                        levelDb = if (pieceLevelFrames > 0) pieceLevelSum / pieceLevelFrames else Double.NEGATIVE_INFINITY,
                        spanMs = if (pieceStartedMs >= 0) timeMs - pieceStartedMs else 0L
                    )
                    fill = 0
                    piece.fill(0f)
                    pieceLevelSum = 0.0
                    pieceLevelFrames = 0
                    pieceStartedMs = -1L
                    if (!toEmbed.offer(ready)) {
                        // The thread is still busy with the piece before this one: dropping it is better than queueing,
                        // because a piece judged seconds late says nothing about the room now.
                        ready.samples.fill(0f)
                        Log.w(TAG, "embedding still busy: piece dropped")
                    }
                }

                // Results come back on the next turn through the loop, so all the decision state stays on this thread.
                embedError.get()?.let { throw IllegalStateException(it) }
                while (true) {
                    val done = results.poll() ?: break
                    load.piece(done.embedNs)
                    val similarity = done.similarity
                    val pieceLevel = done.levelDb
                    val pieceSpanMs = done.spanMs
                    run {
                        val isOwner = similarity >= template.info.threshold
                        val isOther = similarity < template.info.threshold - settings.margin
                        readings = (listOf(similarity) + readings).take(READINGS_KEPT)
                        val verdict = if (isOwner) OWNER else if (isOther) OTHER else UNCLEAR
                        var note = ""
                        // Without the filter every clear piece counts at once. With it, only a verdict that CHANGES has
                        // to be said twice; when it is confirmed, the held piece is entered with its own time, so nothing
                        // is lost, it is only delayed.
                        when {
                            !settings.confirmTwice -> {
                                if (verdict == OWNER) ownerTimes.addLast(done.timeMs)
                                else if (verdict == OTHER) otherTimes.addLast(done.timeMs)
                                if (verdict != UNCLEAR) confirmedVerdict = verdict
                            }
                            // The two pieces that confirm each other have to be the two pieces that FOLLOWED each other.
                            // An unclear piece in between is not a confirmation, so it breaks the chain (owner, 2026-09-28).
                            verdict == UNCLEAR -> {
                                if (pendingVerdict != UNCLEAR) note = " (dropped, the next piece was unclear)"
                                pendingVerdict = UNCLEAR
                            }
                            // A verdict that simply continues needs no second piece — only a CHANGE does.
                            verdict == confirmedVerdict -> {
                                if (verdict == OWNER) ownerTimes.addLast(done.timeMs) else otherTimes.addLast(done.timeMs)
                                pendingVerdict = UNCLEAR
                            }
                            verdict == pendingVerdict -> {
                                if (verdict == OWNER) { ownerTimes.addLast(pendingTimeMs); ownerTimes.addLast(done.timeMs) }
                                else { otherTimes.addLast(pendingTimeMs); otherTimes.addLast(done.timeMs) }
                                confirmedVerdict = verdict
                                pendingVerdict = UNCLEAR
                                note = " (confirmed by the piece right before it)"
                            }
                            else -> {
                                if (pendingVerdict != UNCLEAR) note = " (dropped, not repeated)"
                                pendingVerdict = verdict
                                pendingTimeMs = done.timeMs
                                if (note.isEmpty()) note = " (waiting for the very next piece)"
                            }
                        }
                        Log.i(TAG, "piece: similarity %.3f -> %s%s · speech level %.0f dB over %.1f s".format(
                            similarity, if (isOwner) "owner" else if (isOther) "somebody else" else "unclear", note,
                            pieceLevel, pieceSpanMs / 1000.0))
                    }
                }
                while (ownerTimes.isNotEmpty() && timeMs - ownerTimes.first() > settings.windowMs) ownerTimes.removeFirst()
                while (otherTimes.isNotEmpty() && timeMs - otherTimes.first() > settings.windowMs) otherTimes.removeFirst()

                load.tick(timeMs)

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
            // The microphone first, so the next detector can open it at once. The embedding thread is only told to stop:
            // it finishes the run it is in, then closes the model and erases the voiceprint itself (see above).
            runCatching { source.close() }
            val embedding = embedThread
            if (embedding != null) embedding.interrupt() else { template.zero(); cohort?.fill(0f) }
            while (true) (toEmbed.poll() ?: break).samples.fill(0f)
            results.clear()
            preRoll.fill(0)
            piece.fill(0f)
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
 * Settings of the owner comparison. Defaults come from what was measured on the robot (owner ≈ 0.90, another person
 * ≈ 0.80); the threshold itself lives with the stored voice and is set on the voice screen.
 *
 * @property margin how far below the threshold a piece must be before it counts as somebody else
 * @property piecesForOther pieces that must say "somebody else" before the robot believes it
 * @property anyOtherIsConversation true = any voice that is not the owner counts as a conversation; false = the owner
 *           must have been heard in the same window (a stranger talking alone is then not a conversation)
 */
/**
 * Which gate decides that a 10 ms frame carries speech.
 *
 * [SILERO_AND_LEVEL] is the original rule: the neural voice-activity model must agree AND the frame must be louder than
 * [ConversationSettings.minLevelDb]. [SILERO_ONLY] drops the loudness floor, [LEVEL_ONLY] drops Silero — and then the
 * model is not run at all, which also gives its CPU time back (it costs 3–19 ms per 32 ms chunk on this robot).
 */
enum class SpeechGateMode { SILERO_AND_LEVEL, SILERO_ONLY, LEVEL_ONLY }

data class ConversationSettings(
    val pieceSeconds: Double = 3.0,
    val speechThreshold: Double = 0.5,
    /**
     * Silero hysteresis: once speech has started it only ends below `speechThreshold - speechHysteresis`. Without it a
     * probability sitting on the threshold flips the gate every 32 ms chunk. The change detector always had this; the
     * other methods did not, which is why they flickered at the boundary.
     */
    val speechHysteresis: Double = 0.15,
    val minLevelDb: Double = -65.0,
    val gateMode: SpeechGateMode = SpeechGateMode.SILERO_AND_LEVEL,
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
    val piecesKept: Int = 6,
    /**
     * Activity method: how long the window is that the share of speech is measured over, and how much of it has to carry
     * speech before the robot calls it a conversation. 10 s and 0.8 are the owner's starting values; both are meant to be
     * measured on the robot, because how much a single speaker pauses depends on the person and on the room.
     */
    val activityWindowMs: Long = 10_000,
    val activityShare: Float = 0.8f,
    /**
     * Activity method: how long a gap still counts as speech (the "hangover" of a voice-activity detector; Silero's own
     * utilities call it `min_silence_duration_ms`). Without it the share measures the gaps BETWEEN WORDS, which every
     * single speaker has; with it, the share measures the gaps between TURNS, which is what tells one speaker from two.
     */
    val activityMinSilenceMs: Long = 250,
    /**
     * Outlier filter: a verdict that is NEW — the first "somebody else" after the owner, the first piece below the
     * pairwise threshold, the moment the speech share crosses — only counts when the next piece says the same. A verdict
     * that simply continues counts at once, so this costs time only at the transitions, which is exactly where the
     * mistakes were measured (the piece that straddled "video off / owner walks up" read 0.402 and was called the owner,
     * 2026-09-23).
     *
     * The price is one extra piece of delay, about three seconds, at every change — and a real change that is followed by
     * silence is dropped instead of reported. Off by default; the change detector is not affected, it has its own
     * confirmation (ΔBIC, plus a rule that needs either two changes or accumulated evidence).
     */
    val confirmTwice: Boolean = false,
    /**
     * How a piece of speech is assembled. False (the old behaviour) puts ONLY the frames that passed the gate into the
     * piece, so three seconds are glued together from many fragments with every pause cut out. Each cut is a jump in the
     * signal, i.e. a broadband click in the spectrum, and how often it is cut depends on how far away the speaker is —
     * which is exactly the axis the comparison is supposed to measure. It also drops the first tens of milliseconds of
     * every utterance, where the plosives live, and it changes the statistics the speaker network pools over.
     *
     * True keeps the audio continuous: [speechPadMs] before the first gated frame are taken from a short ring buffer,
     * and pauses shorter than [bridgeGapMs] are not cut at all. A piece then contains a little silence, which is what the
     * model was trained on.
     */
    val continuousPieces: Boolean = false,
    val speechPadMs: Long = 30,
    val bridgeGapMs: Long = 200
)
