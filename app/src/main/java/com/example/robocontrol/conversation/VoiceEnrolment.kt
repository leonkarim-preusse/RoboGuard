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

/**
 * What the enrolment screen shows.
 * @property phase current phase
 * @property speechSeconds speech collected, in seconds
 * @property pieces pieces embedded
 * @property speechProbability current Silero probability
 * @property levelDb current level in dBFS
 * @property similarity test similarity to the stored voice
 * @property isOwner whether the test reading was above the threshold
 * @property readings recent test readings
 * @property message status message
 * @property error error message
 * @property mode purpose of the run
 * @property rawSimilarity similarity before centring
 */
data class EnrolmentState(
    val phase: Phase = Phase.IDLE,
    val speechSeconds: Double = 0.0,
    val pieces: Int = 0,
    val speechProbability: Double = 0.0,
    val levelDb: Double = Double.NEGATIVE_INFINITY,
    val similarity: Float? = null,
    val isOwner: Boolean? = null,
    val readings: List<Float> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val mode: VoiceEnrolment.Mode = VoiceEnrolment.Mode.ENROL,
    val rawSimilarity: Float? = null
) {
    /** What the enrolment is doing right now. */
    enum class Phase { IDLE, RECORDING, SAVED, TESTING, ERROR }
}

/**
 * Records a voice template or the cohort mean, and tests a stored voice. Audio buffers are zeroed after use.
 * @param context used for the microphone, the models and the store
 */
class VoiceEnrolment(private val context: Context) {
    private val _state = MutableStateFlow(EnrolmentState())
    val state: StateFlow<EnrolmentState> = _state.asStateFlow()

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var cancelled = false

    val running: Boolean get() = thread != null

    /** What a run is for. */
    enum class Mode { ENROL, TEST, COHORT }

    /**
     * Records a new voice and stores it as the active voice.
     * @param profile name under which the voice is stored
     */
    fun startEnrolment(profile: String = OwnerVoiceprintStore.DEFAULT_PROFILE) {
        start(Mode.ENROL, OwnerVoiceprintStore.get(context).safeName(profile))
    }

    /** Starts a test of the stored voice. */
    fun startTest() = start(Mode.TEST, null)

    /** Starts a cohort recording. */
    fun startCohort() = start(Mode.COHORT, null)

    /**
     * Starts a run in its own thread unless one is running or the microphone is switched off in the settings.
     * @param mode purpose of the run
     * @param profile voice name for an enrolment, or null
     */
    @Synchronized
    private fun start(mode: Mode, profile: String?) {
        if (thread != null) return
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

    /** Ends the current run without saving anything. */
    @Synchronized
    fun cancel() {
        cancelled = true
        thread?.interrupt()
        thread = null
    }

    /**
     * Whether the privacy settings switch the microphone off.
     * @return true if the microphone is switched off
     */
    private fun microphoneSwitchedOff(): Boolean = Sensors.get(context).getSensors().entries
        .firstOrNull { it.key.equals("microphone", ignoreCase = true) }?.value == false

    /**
     * Pieces a worker handed back: an embedding (ENROL, COHORT) or the two similarities (TEST).
     * @property embedding embedding, or null in a test
     * @property similarity centred similarity
     * @property raw raw similarity
     */
    private class Reading(val embedding: FloatArray?, val similarity: Float, val raw: Float)

    /**
     * Recording thread: reads the microphone, embeds the pieces and stores or compares the result.
     * @param mode purpose of the run
     * @param stored stored voice for a test, or null
     * @param profile voice name, or null
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
        val audio = ConversationMonitor.audioSettings()
        val gate = SpeechGateState(audio)
        val pieces = PieceAssembler(audio)
        val speaking = RobotSpeaking(context)
        var speechSamples = 0L
        var samples = 0L
        val startedWall = android.os.SystemClock.elapsedRealtime()
        var worstAudioClock = 1.0
        val cohort = if (mode == Mode.TEST) OwnerVoiceprintStore.get(context).loadCohort() else null
        var worker: EmbeddingWorker<Reading>? = null
        var lease: ConversationMonitor.MicLease? = null
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
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            worker = EmbeddingWorker(context, "VoiceEnrolmentEmbed",
                capacity = if (testing) 1 else 8,
                compute = { embedding, _ ->
                    if (testing) Reading(null, stored!!.similarityTo(embedding, cohort), stored.similarityTo(embedding))
                    else Reading(embedding.copyOf(), 0f, 0f)
                },
                discard = { it.embedding?.fill(0f) },
                onEnd = { stored?.zero(); cohort?.fill(0f) })
            Log.i(TAG, if (testing) "trying the stored voice (threshold %.2f)".format(stored!!.info.threshold) else "recording ($mode)")
            Log.i(TAG, "gate: %s, silero %.2f (release %.2f), level floor %.0f dBFS, pieces %s".format(
                audio.gateMode, audio.speechThreshold, audio.sileroRelease, audio.minLevelDb, audio.assemblyText()))
            var lastReport = 0L
            var lastPublish = 0L
            var targetReached = false
            val startedMs = android.os.SystemClock.elapsedRealtime()
            while (!Thread.currentThread().isInterrupted) {
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
                        val audioClock = timeMs.toDouble() / maxOf(now - startedWall, 1L)
                        if (timeMs > 4_000) worstAudioClock = minOf(worstAudioClock, audioClock)
                        Log.i(TAG, "mic: speech p %.2f, level %.0f dB, speech %.1f s, collected %.1f s, audio/clock %.2f%s"
                            .format(probability, levelDb, speechSamples / 16_000.0, pieces.collectedSamples / 16_000.0,
                                audioClock, if (robotTalking) " (robot speaking)" else ""))
                    }
                    val target = if (mode == Mode.COHORT) COHORT_TARGET_SECONDS else TARGET_SECONDS
                    if (!testing && speechSamples >= target * 16_000) {
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
            worker?.stop()
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

    /**
     * Averages the collected embeddings and stores them as a voice template or as the cohort mean.
     * @param mode what the run was for
     * @param embeddings collected embeddings
     * @param speechSeconds collected speech in seconds
     * @param profile name of the voice; null for the cohort
     */
    private fun finish(mode: Mode, embeddings: List<FloatArray>, speechSeconds: Double, profile: String?) {
        if (mode == Mode.COHORT) {
            if (embeddings.size < MIN_PIECES) {
                _state.value = _state.value.copy(
                    phase = EnrolmentState.Phase.ERROR,
                    error = "only ${embeddings.size} usable pieces; play more speech"
                )
                return
            }
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
        /**
         * Scores each piece against the mean of the other pieces.
         * @param pieces embeddings
         * @return one score per piece
         */
        fun leaveOneOut(pieces: List<FloatArray>): List<Float> = pieces.indices.map { i ->
            val others = centroid(pieces.filterIndexed { j, _ -> j != i })
            Voiceprint.centredSimilarity(others, pieces[i], cohort).also { others.fill(0f) }
        }
        val scores = leaveOneOut(embeddings)
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

    /**
     * Element-wise mean of [embeddings] (not normalised).
     * @param embeddings vectors
     * @return the mean
     */
    private fun centroid(embeddings: List<FloatArray>): FloatArray {
        val sum = FloatArray(embeddings.first().size)
        for (e in embeddings) for (i in sum.indices) sum[i] += e[i]
        for (i in sum.indices) sum[i] /= embeddings.size
        return SpeakerEmbedder.normalise(sum)
    }

    companion object {
        private const val TAG = "VoiceEnrolment"

        const val PIECE_SECONDS = 3.0

        const val TARGET_SECONDS = 30.0

        const val COHORT_TARGET_SECONDS = 180.0

        private const val MIN_PIECES = 4

        private const val READINGS_KEPT = 8

        private const val MAX_TEST_MS = 10 * 60_000L

        private const val MIN_AUDIO_CLOCK = 0.98

        @Volatile
        private var lastThread: Thread? = null
    }
}
