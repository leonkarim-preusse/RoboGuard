package com.example.robocontrol.conversation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.robocontrol.sensorcontrol.SensorChangeListener
import com.example.robocontrol.sensorcontrol.Sensors
import kotlin.math.max
import kotlin.math.sqrt

/** What the enrolment screen shows. */
data class EnrolmentState(
    val phase: Phase = Phase.IDLE,
    /** Seconds of SPEECH collected so far (silence does not count). */
    val speechSeconds: Double = 0.0,
    /** Pieces of speech that produced an embedding. */
    val pieces: Int = 0,
    /** Silero's current speech probability, so the person sees that the robot hears them. */
    val speechProbability: Double = 0.0,
    val levelDb: Double = Double.NEGATIVE_INFINITY,
    /** In [Phase.TESTING]: how close the voice just heard is to the stored one. */
    val similarity: Float? = null,
    /** In [Phase.TESTING]: whether that was above the stored threshold. */
    val isOwner: Boolean? = null,
    /** In [Phase.TESTING]: the last readings, newest first, so a short test can be read as a whole. */
    val readings: List<Float> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    /** What the current run is for. */
    val mode: VoiceEnrolment.Mode = VoiceEnrolment.Mode.ENROL,
    /** In [Phase.TESTING] with a stored cohort: the raw similarity before centring, for comparison. */
    val rawSimilarity: Float? = null
) {
    /** What the enrolment is doing right now. */
    enum class Phase { IDLE, RECORDING, SAVED, TESTING, ERROR }
}

/**
 * Teaches the robot one voice — its owner's — and lets that voice be tried out afterwards.
 *
 * Recording: the microphone is read at 16 kHz, Silero decides which 32 ms chunks are speech, and only speech is kept.
 * Every [PIECE_SECONDS] of speech becomes one embedding. When [TARGET_SECONDS] of speech have been collected, the
 * embeddings are averaged into the template, pieces that do not fit the rest are dropped (a door, a second person),
 * and the result is stored encrypted ([OwnerVoiceprintStore]).
 *
 * The audio itself never leaves this class: it lives in one buffer that is overwritten piece by piece and zeroed at
 * the end. Nothing is written to disk except the finished template.
 *
 * Testing: the same pipeline, but instead of collecting, every piece is compared with the stored template and only the
 * similarity is published — the number the threshold is later set from.
 *
 * While this runs, `ConversationMonitor` releases the microphone (`setProbeUsingMicrophone`), the same as the test
 * screens do.
 */
class VoiceEnrolment(private val context: Context) {

    private val _state = MutableStateFlow(EnrolmentState())
    val state: StateFlow<EnrolmentState> = _state.asStateFlow()

    @Volatile
    private var thread: Thread? = null

    /** Set by [cancel]: the run ends WITHOUT saving anything (item 58). */
    @Volatile
    private var cancelled = false

    val running: Boolean get() = thread != null

    /** What a run is for. */
    enum class Mode { ENROL, TEST, COHORT }

    /**
     * Records until [TARGET_SECONDS] of speech are collected, then stores the template under [profile] and makes that the
     * voice the detectors use. Several voices can be kept side by side for testing (see [OwnerVoiceprintStore.profiles]).
     */
    fun startEnrolment(profile: String = OwnerVoiceprintStore.DEFAULT_PROFILE) {
        start(Mode.ENROL, OwnerVoiceprintStore.get(context).safeName(profile))
    }

    /** Listens and reports how close each piece of speech is to the stored template. */
    fun startTest() = start(Mode.TEST, null)

    /**
     * Records OTHER voices — played from a phone, a video, whoever is in the room — and stores only their average
     * ([OwnerVoiceprintStore.saveCohort]). That average carries what this microphone and this room add to every voice,
     * and subtracting it is what makes the owner comparison readable. Individual voices are never stored.
     */
    fun startCohort() = start(Mode.COHORT, null)

    /** Starts a run in its own thread unless one is running or the microphone is switched off in the settings. */
    @Synchronized
    private fun start(mode: Mode, profile: String?) {
        if (thread != null) return
        // The only part of RoboGuard that STORES something derived from a voice must respect "microphone off" (item 50).
        if (microphoneSwitchedOff()) {
            _state.value = EnrolmentState(phase = EnrolmentState.Phase.ERROR, error = "the microphone is switched off in the privacy settings")
            return
        }
        val stored = if (mode == Mode.TEST) OwnerVoiceprintStore.get(context).load() else null
        if (mode == Mode.TEST && stored == null) {
            _state.value = EnrolmentState(phase = EnrolmentState.Phase.ERROR, error = "no voice stored yet")
            return
        }
        cancelled = false
        _state.value = EnrolmentState(
            phase = when (mode) {
                Mode.TEST -> EnrolmentState.Phase.TESTING
                else -> EnrolmentState.Phase.RECORDING
            },
            mode = mode
        )
        val previous = lastThread
        thread = Thread({
            // A run of an earlier screen may still be inside its last embedding: let it finish and give the microphone
            // back first, so its release cannot hand the microphone to the monitor while this run records (item 31).
            runCatching { previous?.join(5_000) }
            run(mode, stored, profile)
        }, "VoiceEnrolment").apply {
            isDaemon = true
            start()
        }
        lastThread = thread
    }

    /** Ends the run. A cohort run keeps what it has (Stop is its normal end); enrolment and test keep nothing. */
    @Synchronized
    fun stop() {
        if (_state.value.mode != Mode.COHORT) cancelled = true
        thread?.interrupt()
        thread = null
    }

    /** Ends the run without saving anything, whatever the mode (the screen went away, item 25). */
    @Synchronized
    fun cancel() {
        cancelled = true
        thread?.interrupt()
        thread = null
    }

    /** Whether the privacy settings switch the microphone off. */
    private fun microphoneSwitchedOff(): Boolean = Sensors.get(context).getSensors().entries
        .firstOrNull { it.key.equals("microphone", ignoreCase = true) }?.value == false

    /** Pieces a worker handed back: an embedding (ENROL, COHORT) or the two similarities (TEST). */
    private class Reading(val embedding: FloatArray?, val similarity: Float, val raw: Float)

    /**
     * The recording thread: reads the microphone, cuts pieces like the detectors do, embeds them and, at the end,
     * stores the voice / the cohort or reports the test readings. Every buffer is zeroed before it returns.
     */
    private fun run(mode: Mode, stored: Voiceprint?, profile: String?) {
        val testing = mode == Mode.TEST
        val source = AndroidMicSource()
        var vad: SileroVad? = null
        val embeddings = ArrayList<FloatArray>()
        val read = ShortArray(SPEECH_HOP)
        val hop = ShortArray(SPEECH_HOP)
        val chunk = ShortArray(512)
        var chunkFill = 0
        var probability = 0.0
        // Gate and assembly exactly as the detector has them set right now, through the SAME code (SpeechPieces.kt).
        val audio = ConversationMonitor.audioSettings()
        val gate = SpeechGateState(audio)
        val pieces = PieceAssembler(audio)
        // The robot's own voice must not end up in a template or a cohort (item 12).
        val speaking = RobotSpeaking(context)
        var speechSamples = 0L
        var samples = 0L
        val startedWall = android.os.SystemClock.elapsedRealtime()
        var worstAudioClock = 1.0
        // The cohort mean of other voices, if one was recorded: subtracted from both sides when comparing.
        val cohort = if (mode == Mode.TEST) OwnerVoiceprintStore.get(context).loadCohort() else null
        var worker: EmbeddingWorker<Reading>? = null
        var lease: ConversationMonitor.MicLease? = null
        // "Microphone off" while recording ends the run and keeps nothing (item 50).
        val micListener = SensorChangeListener { name, enabled ->
            if (name.equals("microphone", ignoreCase = true) && !enabled) {
                Log.i(TAG, "microphone switched off in the privacy settings: run cancelled")
                _state.value = _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = "the microphone was switched off")
                cancel()
            }
        }
        var finished = false
        try {
            lease = ConversationMonitor.acquireMicrophone("voice enrolment")
            Sensors.get(context).addListener(micListener)
            val openError = source.open()
            if (openError != null) {
                _state.value = _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = openError)
                return
            }
            if (audio.gateMode != SpeechGateMode.LEVEL_ONLY) vad = SileroVad(context)
            // Like the detectors: audio not read in time is lost, and a template from a lossy recording is worse (item 37).
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            worker = EmbeddingWorker(context, "VoiceEnrolmentEmbed",
                // Enrolment and cohort must not lose pieces; a test drops them like the detector does.
                capacity = if (testing) 1 else 8,
                compute = { embedding, _ ->
                    if (testing) Reading(null, stored!!.similarityTo(embedding, cohort), stored.similarityTo(embedding))
                    else Reading(embedding.copyOf(), 0f, 0f)
                },
                discard = { it.embedding?.fill(0f) },
                onEnd = { stored?.zero(); cohort?.fill(0f) })
            Log.i(TAG, if (testing) "trying the stored voice (threshold %.2f)".format(stored!!.info.threshold) else "recording ($mode)")
            // Which gate and which assembly produced this template — the number it is compared against later only means
            // something for pieces cut the same way.
            Log.i(TAG, "gate: %s, silero %.2f (release %.2f), level floor %.0f dBFS, pieces %s".format(
                audio.gateMode, audio.speechThreshold, audio.sileroRelease, audio.minLevelDb, audio.assemblyText()))
            var lastReport = 0L
            var lastPublish = 0L
            var targetReached = false
            val startedMs = android.os.SystemClock.elapsedRealtime()
            while (!Thread.currentThread().isInterrupted) {
                // A test is a tool, not a recording that runs all day: it ends by itself after a while.
                if (testing && android.os.SystemClock.elapsedRealtime() - startedMs > MAX_TEST_MS) {
                    Log.i(TAG, "test ended after ${MAX_TEST_MS / 60_000} minutes")
                    break
                }
                if (!targetReached) {
                    if (!readHop(source, read, hop)) {
                        _state.value = _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = "the microphone stopped delivering audio")
                        return
                    }
                    samples += SPEECH_HOP
                    var i = 0
                    while (i < SPEECH_HOP) {
                        val take = minOf(SPEECH_HOP - i, chunk.size - chunkFill)
                        hop.copyInto(chunk, chunkFill, i, i + take)
                        chunkFill += take
                        i += take
                        if (chunkFill == chunk.size) {
                            vad?.let { probability = it.probability(chunk).toDouble() }
                            chunkFill = 0
                        }
                    }
                    val levelDb = hopLevelDb(hop)
                    val timeMs = samples * 1000 / 16_000
                    val robotTalking = speaking.active(timeMs)
                    val gated = gate.decide(probability, levelDb)
                    if (gated && !robotTalking) speechSamples += SPEECH_HOP
                    for (piece in pieces.onHop(hop, timeMs, levelDb, gated, robotTalking)) {
                        if (!worker.offer(piece)) Log.w(TAG, "embedding still busy: piece dropped")
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    // ~10 updates a second are enough for a progress bar (it used to publish every 10 ms).
                    if (now - lastPublish >= 100) {
                        lastPublish = now
                        _state.value = _state.value.copy(
                            speechSeconds = speechSamples / 16_000.0,
                            speechProbability = probability,
                            levelDb = levelDb
                        )
                    }
                    if (now - lastReport >= 2_000) {
                        lastReport = now
                        // Audio time per wall-clock time: below 1, the buffer ran over and speech was lost.
                        val audioClock = timeMs.toDouble() / maxOf(now - startedWall, 1L)
                        if (timeMs > 4_000) worstAudioClock = minOf(worstAudioClock, audioClock)
                        Log.i(TAG, "mic: speech p %.2f, level %.0f dB, speech %.1f s, collected %.1f s, audio/clock %.2f%s"
                            .format(probability, levelDb, speechSamples / 16_000.0, pieces.collectedSamples / 16_000.0,
                                audioClock, if (robotTalking) " (robot speaking)" else ""))
                    }
                    val target = if (mode == Mode.COHORT) COHORT_TARGET_SECONDS else TARGET_SECONDS
                    if (!testing && speechSamples >= target * 16_000) {
                        // The pieces still being collected count too; then wait for the worker to finish them.
                        for (piece in pieces.flush()) if (!worker.offer(piece)) Log.w(TAG, "piece dropped at the end")
                        targetReached = true
                    }
                } else {
                    Thread.sleep(20)
                }

                worker.error?.let { throw IllegalStateException(it) }
                while (true) {
                    val done = worker.poll() ?: break
                    val r = done.value
                    if (testing) {
                        Log.i(TAG, "piece: similarity %.3f (raw %.3f), threshold %.2f -> %s".format(
                            r.similarity, r.raw, stored!!.info.threshold,
                            if (r.similarity >= stored.info.threshold) "owner" else "somebody else"))
                        _state.value = _state.value.copy(
                            similarity = r.similarity,
                            isOwner = r.similarity >= stored.info.threshold,
                            readings = (listOf(r.similarity) + _state.value.readings).take(READINGS_KEPT),
                            rawSimilarity = r.raw,
                            pieces = _state.value.pieces + 1
                        )
                    } else {
                        embeddings += r.embedding!!
                        Log.i(TAG, "piece ${embeddings.size} recorded (${done.piece.reason}, %.1f s)".format(done.piece.samples.size / 16_000.0))
                        _state.value = _state.value.copy(pieces = embeddings.size)
                    }
                }
                if (targetReached && worker.idle) {
                    if (cancelled) return
                    if (worstAudioClock < MIN_AUDIO_CLOCK) {
                        Log.w(TAG, "audio was lost during the recording (audio/clock %.2f)".format(worstAudioClock))
                    }
                    finish(mode, embeddings, speechSamples / 16_000.0, profile)
                    finished = true
                    return
                }
            }
        } catch (_: InterruptedException) {
            // stop() or cancel(): a normal end
        } catch (e: Exception) {
            Log.e(TAG, "enrolment stopped", e)
            _state.value = _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = e.message ?: e.toString())
        } finally {
            runCatching { Sensors.get(context).removeListener(micListener) }
            read.fill(0)
            hop.fill(0)
            chunk.fill(0)
            pieces.clear()
            runCatching { vad?.close() }
            runCatching { source.close() }
            // The worker finishes its current run, closes the model and erases the stored voice and the cohort itself.
            worker?.stop()
            // A cohort run is normally ended by hand when the recording has played: keep what was collected — unless it
            // was cancelled (screen left, microphone switched off). This has to happen BEFORE the pieces are zeroed and
            // before the microphone is handed back, so the restarted detector loads the new cohort (item 5).
            if (!finished && !cancelled && mode == Mode.COHORT && _state.value.phase == EnrolmentState.Phase.RECORDING &&
                embeddings.size >= MIN_PIECES) {
                finish(mode, embeddings, speechSamples / 16_000.0, profile)
            }
            embeddings.forEach { it.fill(0f) }
            if (worker == null) { stored?.zero(); cohort?.fill(0f) }
            lease?.close()
            synchronized(this) { if (thread === Thread.currentThread()) thread = null }
            Log.i(TAG, "run ended (${_state.value.phase}${if (cancelled) ", cancelled: nothing saved" else ""})")
            if (_state.value.phase == EnrolmentState.Phase.RECORDING || _state.value.phase == EnrolmentState.Phase.TESTING) {
                _state.value = _state.value.copy(phase = EnrolmentState.Phase.IDLE)
            }
        }
    }

    /** Averages the pieces and stores them — as the owner's template, or as the cohort mean. */
    private fun finish(mode: Mode, embeddings: List<FloatArray>, speechSeconds: Double, profile: String?) {
        if (mode == Mode.COHORT) {
            if (embeddings.size < MIN_PIECES) {
                _state.value = _state.value.copy(
                    phase = EnrolmentState.Phase.ERROR,
                    error = "only ${embeddings.size} usable pieces; play more speech"
                )
                return
            }
            // No outlier filtering here: the point of the cohort is that it covers MANY different voices.
            val mean = centroid(embeddings)
            val error = OwnerVoiceprintStore.get(context).saveCohort(mean, embeddings.size)
            mean.fill(0f)
            _state.value = if (error == null) {
                _state.value.copy(
                    phase = EnrolmentState.Phase.SAVED,
                    pieces = embeddings.size,
                    message = "other voices stored: ${embeddings.size} pieces, %.0f s".format(speechSeconds)
                )
            } else {
                _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = error)
            }
            return
        }
        if (embeddings.size < MIN_PIECES) {
            _state.value = _state.value.copy(
                phase = EnrolmentState.Phase.ERROR,
                error = "only ${embeddings.size} usable pieces of speech; please try again and keep talking"
            )
            return
        }
        val store = OwnerVoiceprintStore.get(context)
        val cohort = store.loadCohort()
        // Leave-one-out: each piece is scored against the average of the OTHER pieces, on the scale the detector uses.
        // Scoring a piece against an average that contains it flattered every score by ~0.14, and the old raw 0.45
        // outlier filter never removed anything because any voice reads 0.67–0.93 raw on this robot (item 12).
        fun leaveOneOut(pieces: List<FloatArray>): List<Float> = pieces.indices.map { i ->
            val others = centroid(pieces.filterIndexed { j, _ -> j != i })
            Voiceprint.centredSimilarity(others, pieces[i], cohort).also { others.fill(0f) }
        }
        val scores = leaveOneOut(embeddings)
        // Relative outlier rule: further below the median than 3 median absolute deviations.
        val median = scores.sorted()[scores.size / 2]
        val mad = scores.map { kotlin.math.abs(it - median) }.sorted()[scores.size / 2]
        val keepIdx = scores.indices.filter { scores[it] >= median - 3 * mad }
        val used = if (keepIdx.size >= MIN_PIECES) keepIdx.map { embeddings[it] } else embeddings
        val dropped = embeddings.size - used.size
        if (dropped > 0) Log.i(TAG, "left out $dropped piece(s) far from the others: " +
            scores.indices.filter { it !in keepIdx }.joinToString { "%.2f".format(scores[it]) })
        val template = centroid(used)
        val similarities = if (used.size == embeddings.size) scores else leaveOneOut(used)
        cohort?.fill(0f)
        val mean = similarities.average().toFloat()
        val sd = sqrt(similarities.sumOf { (it - mean).toDouble() * (it - mean) } / similarities.size).toFloat()
        val worst = similarities.min()
        val suggested = max(OwnerVoiceprintStore.MIN_THRESHOLD, mean - 2 * sd)
        // An existing voice keeps its threshold (it was set by hand from measured readings); the computed one is only
        // offered next to it. A new voice starts with the computed one (item 12).
        store.setActiveProfile(profile ?: OwnerVoiceprintStore.DEFAULT_PROFILE)
        val existing = store.info.value
        val threshold = existing?.threshold ?: suggested
        val info = VoiceprintInfo(
            model = SpeakerEmbedder.MODEL_ASSET,
            dimensions = template.size,
            createdAtMs = System.currentTimeMillis(),
            speechSeconds = speechSeconds,
            pieces = used.size,
            selfSimilarityMean = mean,
            selfSimilarityMin = worst,
            threshold = threshold,
            margin = existing?.margin ?: VoiceprintInfo.DEFAULT_MARGIN,
            cohortId = if (existing != null && existing.cohortId != null) existing.cohortId else store.currentCohortId(),
            suggestedThreshold = suggested
        )
        val error = store.save(template, info)
        template.fill(0f)
        val name = store.activeProfile.value
        _state.value = if (error == null) {
            _state.value.copy(
                phase = EnrolmentState.Phase.SAVED,
                pieces = used.size,
                message = if (existing != null) "\"$name\" stored: ${used.size} pieces, %.0f s of speech; threshold kept at %.2f (suggested %.2f)"
                    .format(speechSeconds, threshold, suggested)
                else "\"$name\" stored: ${used.size} pieces, %.0f s of speech, threshold %.2f".format(speechSeconds, threshold)
            )
        } else {
            _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = error)
        }
    }

    /** Element-wise mean of [embeddings] (not normalised). */
    private fun centroid(embeddings: List<FloatArray>): FloatArray {
        val sum = FloatArray(embeddings.first().size)
        for (e in embeddings) for (i in sum.indices) sum[i] += e[i]
        for (i in sum.indices) sum[i] /= embeddings.size
        return SpeakerEmbedder.normalise(sum)
    }

    companion object {
        private const val TAG = "VoiceEnrolment"

        /** Speech per embedded piece. Shorter pieces give unstable vectors. */
        const val PIECE_SECONDS = 3.0

        /** Speech to collect before the template is built. */
        const val TARGET_SECONDS = 30.0

        /** Speech to collect for the cohort of other voices; a run can also be ended by hand earlier. */
        const val COHORT_TARGET_SECONDS = 180.0

        /** Pieces needed for a usable template. */
        private const val MIN_PIECES = 4

        /** Readings kept for the screen while trying the voice. */
        private const val READINGS_KEPT = 8

        /** "Try the voice" ends by itself after this long. */
        private const val MAX_TEST_MS = 10 * 60_000L

        /** Below this audio/clock ratio, audio was lost during a recording (logged). */
        private const val MIN_AUDIO_CLOCK = 0.98

        /** The run of the previous screen instance, which a new run waits for (see start). */
        @Volatile
        private var lastThread: Thread? = null
    }
}
