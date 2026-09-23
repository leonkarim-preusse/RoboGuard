package com.example.robocontrol.conversation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val running: Boolean get() = thread != null

    /** What a run is for. */
    enum class Mode { ENROL, TEST, COHORT }

    /** Records until [TARGET_SECONDS] of speech are collected, then stores the template. */
    fun startEnrolment() = start(Mode.ENROL)

    /** Listens and reports how close each piece of speech is to the stored template. */
    fun startTest() = start(Mode.TEST)

    /**
     * Records OTHER voices — played from a phone, a video, whoever is in the room — and stores only their average
     * ([OwnerVoiceprintStore.saveCohort]). That average carries what this microphone and this room add to every voice,
     * and subtracting it is what makes the owner comparison readable. Individual voices are never stored.
     */
    fun startCohort() = start(Mode.COHORT)

    @Synchronized
    private fun start(mode: Mode) {
        if (thread != null) return
        val stored = if (mode == Mode.TEST) OwnerVoiceprintStore.get(context).load() else null
        if (mode == Mode.TEST && stored == null) {
            _state.value = EnrolmentState(phase = EnrolmentState.Phase.ERROR, error = "no voice stored yet")
            return
        }
        _state.value = EnrolmentState(
            phase = when (mode) {
                Mode.TEST -> EnrolmentState.Phase.TESTING
                else -> EnrolmentState.Phase.RECORDING
            },
            mode = mode
        )
        ConversationMonitor.setProbeUsingMicrophone(true)
        thread = Thread({ run(mode, stored) }, "VoiceEnrolment").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun run(mode: Mode, stored: Voiceprint?) {
        val testing = mode == Mode.TEST
        val source = AndroidMicSource()
        var vad: SileroVad? = null
        var embedder: SpeakerEmbedder? = null
        // One buffer for the speech of the current piece; reused, and zeroed when the run ends.
        val piece = FloatArray(PIECE_SAMPLES)
        var fill = 0
        val embeddings = ArrayList<FloatArray>()
        val read = ShortArray(HOP)
        val hop = ShortArray(HOP)
        val chunk = ShortArray(512)
        var chunkFill = 0
        var probability = 0.0
        var speechSamples = 0L
        // The cohort mean of other voices, if one was recorded: subtracted from both sides when comparing.
        val cohort = if (mode == Mode.TEST) OwnerVoiceprintStore.get(context).loadCohort() else null
        try {
            vad = SileroVad(context)
            embedder = SpeakerEmbedder(context)
            val openError = source.open()
            if (openError != null) {
                _state.value = _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = openError)
                return
            }
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Log.i(TAG, if (testing) "trying the stored voice (threshold %.2f)".format(stored!!.info.threshold) else "recording the owner's voice")
            var lastReport = 0L
            while (!Thread.currentThread().isInterrupted) {
                var filled = 0
                while (filled < HOP) {
                    val n = source.read(read, HOP - filled)
                    if (n < 0) return
                    read.copyInto(hop, filled, 0, n)
                    filled += n
                }
                // Silero works on 512-sample chunks; the newest probability applies to the samples that follow.
                var i = 0
                while (i < HOP) {
                    val take = minOf(HOP - i, chunk.size - chunkFill)
                    hop.copyInto(chunk, chunkFill, i, i + take)
                    chunkFill += take
                    i += take
                    if (chunkFill == chunk.size) {
                        probability = vad.probability(chunk).toDouble()
                        chunkFill = 0
                    }
                }
                var sum = 0.0
                for (k in 0 until HOP) {
                    val v = hop[k] / 32768f
                    sum += v * v
                }
                val levelDb = 20 * kotlin.math.log10(max(sqrt(sum / HOP), 1e-9))

                if (probability >= SPEECH_THRESHOLD) {
                    for (k in 0 until HOP) {
                        if (fill < piece.size) piece[fill++] = hop[k] / 32768f
                    }
                    speechSamples += HOP
                }
                _state.value = _state.value.copy(
                    speechSeconds = speechSamples / 16_000.0,
                    speechProbability = probability,
                    levelDb = levelDb
                )
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReport >= 2_000) {
                    lastReport = now
                    Log.i(TAG, "mic: speech p %.2f, level %.0f dB, speech %.1f s, piece %d%% full"
                        .format(probability, levelDb, speechSamples / 16_000.0, 100 * fill / piece.size))
                }

                if (fill >= piece.size) {
                    val embedding = embedder.embed(piece, fill)
                    fill = 0
                    piece.fill(0f)
                    if (embedding != null) {
                        if (testing) {
                            val raw = stored!!.similarityTo(embedding)
                            val similarity = stored.similarityTo(embedding, cohort)
                            Log.i(TAG, "piece: similarity %.3f (raw %.3f), threshold %.2f -> %s".format(
                                similarity, raw, stored.info.threshold,
                                if (similarity >= stored.info.threshold) "owner" else "somebody else"))
                            _state.value = _state.value.copy(
                                similarity = similarity,
                                isOwner = similarity >= stored.info.threshold,
                                readings = (listOf(similarity) + _state.value.readings).take(READINGS_KEPT),
                                rawSimilarity = raw,
                                pieces = _state.value.pieces + 1
                            )
                            // The guest's vector is not kept: overwrite it right away.
                            embedding.fill(0f)
                        } else {
                            embeddings += embedding
                            Log.i(TAG, "piece ${embeddings.size} recorded")
                            _state.value = _state.value.copy(pieces = embeddings.size)
                        }
                    }
                }

                val target = if (mode == Mode.COHORT) COHORT_TARGET_SECONDS else TARGET_SECONDS
                if (!testing && speechSamples >= target * 16_000) {
                    finish(mode, embeddings, speechSamples / 16_000.0)
                    return
                }
            }
        } catch (_: InterruptedException) {
            // stop() during a read: a normal end
        } catch (e: Exception) {
            Log.e(TAG, "enrolment stopped", e)
            _state.value = _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = e.message ?: e.toString())
        } finally {
            piece.fill(0f)
            read.fill(0)
            hop.fill(0)
            chunk.fill(0)
            embeddings.forEach { it.fill(0f) }
            stored?.zero()
            cohort?.fill(0f)
            runCatching { vad?.close() }
            runCatching { embedder?.close() }
            runCatching { source.close() }
            ConversationMonitor.setProbeUsingMicrophone(false)
            // A cohort run is normally ended by hand when the recording has played: keep what was collected.
            if (mode == Mode.COHORT && _state.value.phase == EnrolmentState.Phase.RECORDING && embeddings.size >= MIN_PIECES) {
                finish(mode, embeddings, speechSamples / 16_000.0)
            }
            thread = null
            Log.i(TAG, "run ended (${_state.value.phase})")
            if (_state.value.phase == EnrolmentState.Phase.RECORDING || _state.value.phase == EnrolmentState.Phase.TESTING) {
                _state.value = _state.value.copy(phase = EnrolmentState.Phase.IDLE)
            }
        }
    }

    /** Averages the pieces and stores them — as the owner's template, or as the cohort mean. */
    private fun finish(mode: Mode, embeddings: List<FloatArray>, speechSeconds: Double) {
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
        var template = centroid(embeddings)
        // Pieces far from the middle are not the owner talking normally (a door, another voice, a cough): drop them
        // and average again, so one bad moment does not move the template.
        val kept = embeddings.filter { SpeakerEmbedder.similarity(template, it) >= OUTLIER_SIMILARITY }
        if (kept.size >= MIN_PIECES) template = centroid(kept)
        val used = if (kept.size >= MIN_PIECES) kept else embeddings

        val similarities = used.map { SpeakerEmbedder.similarity(template, it) }
        val mean = similarities.average().toFloat()
        val sd = sqrt(similarities.sumOf { (it - mean).toDouble() * (it - mean) } / similarities.size).toFloat()
        val worst = similarities.min()
        // The threshold sits two standard deviations below the owner's own spread, never under MIN_THRESHOLD: with
        // this model different speakers measured 0.09–0.24, the same speaker 0.55–0.68 (PC, 2026-09-21).
        val threshold = max(MIN_THRESHOLD, mean - 2 * sd)

        val info = VoiceprintInfo(
            model = SpeakerEmbedder.MODEL_ASSET,
            dimensions = template.size,
            createdAtMs = System.currentTimeMillis(),
            speechSeconds = speechSeconds,
            pieces = used.size,
            selfSimilarityMean = mean,
            selfSimilarityMin = worst,
            threshold = threshold
        )
        val error = OwnerVoiceprintStore.get(context).save(template, info)
        template.fill(0f)
        _state.value = if (error == null) {
            _state.value.copy(
                phase = EnrolmentState.Phase.SAVED,
                pieces = used.size,
                message = "stored: ${used.size} pieces, %.0f s of speech, threshold %.2f".format(speechSeconds, threshold)
            )
        } else {
            _state.value.copy(phase = EnrolmentState.Phase.ERROR, error = error)
        }
    }

    private fun centroid(embeddings: List<FloatArray>): FloatArray {
        val sum = FloatArray(embeddings.first().size)
        for (e in embeddings) for (i in sum.indices) sum[i] += e[i]
        for (i in sum.indices) sum[i] /= embeddings.size
        return SpeakerEmbedder.normalise(sum)
    }

    companion object {
        private const val TAG = "VoiceEnrolment"

        /** Samples read at a time (10 ms), the same hop the conversation detector uses. */
        private const val HOP = 160

        /** Speech per embedded piece. Shorter pieces give unstable vectors. */
        const val PIECE_SECONDS = 3.0
        private const val PIECE_SAMPLES = (PIECE_SECONDS * 16_000).toInt()

        /** Speech to collect before the template is built. */
        const val TARGET_SECONDS = 30.0

        /** Speech to collect for the cohort of other voices; a run can also be ended by hand earlier. */
        const val COHORT_TARGET_SECONDS = 180.0

        /** Pieces needed for a usable template. */
        private const val MIN_PIECES = 4

        /** Silero probability from which a chunk counts as speech during enrolment. */
        private const val SPEECH_THRESHOLD = 0.5

        /** A piece further from the middle than this is left out of the template. */
        private const val OUTLIER_SIMILARITY = 0.45f

        /** The threshold never goes below this, however consistent the enrolment was. */
        private const val MIN_THRESHOLD = 0.45f

        /** Readings kept for the screen while trying the voice. */
        private const val READINGS_KEPT = 8
    }
}
