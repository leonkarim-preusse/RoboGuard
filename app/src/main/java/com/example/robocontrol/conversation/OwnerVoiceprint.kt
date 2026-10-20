package com.example.robocontrol.conversation

import android.content.Context
import android.util.Log
import com.example.robocontrol.movement.KeystoreZoneCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A stored voice template. It can only be compared with embeddings and erased; the values cannot be read out.
 * @property info statistics about the template
 */
class Voiceprint internal constructor(private val values: FloatArray, val info: VoiceprintInfo) {

    /**
     * Cosine similarity of [embedding] to this voice.
     * @param embedding L2-normalised embedding
     * @param cohort mean embedding of other voices; if given, it is subtracted from both sides before comparing
     * @return similarity, 1 = identical
     */
    fun similarityTo(embedding: FloatArray, cohort: FloatArray? = null): Float =
        centredSimilarity(values, embedding, cohort)

    /** Overwrites the values in memory. Call when the template is no longer needed. */
    fun zero() = values.fill(0f)

    /** A copy of the values for the enrolment's own comparison; the caller must zero it after use. */
    internal fun copyValues(): FloatArray = values.copyOf()

    /** Never prints the values. */
    override fun toString(): String = "Voiceprint(${info.dimensions} values, taught ${info.createdAtMs})"

    companion object {
        /**
         * The comparison of [similarityTo] for two plain vectors, for the enrolment, which has to judge its own pieces
         * before a [Voiceprint] exists — and has to judge them on the scale the detector will use later.
         */
        internal fun centredSimilarity(one: FloatArray, other: FloatArray, cohort: FloatArray?): Float {
            if (cohort == null || cohort.size != one.size) return SpeakerEmbedder.similarity(one, other)
            val a = FloatArray(one.size) { one[it] - cohort[it] }
            val b = FloatArray(other.size) { other[it] - cohort[it] }
            SpeakerEmbedder.normalise(a)
            SpeakerEmbedder.normalise(b)
            val similarity = SpeakerEmbedder.similarity(a, b)
            a.fill(0f)
            b.fill(0f)
            return similarity
        }
    }
}

/**
 * Statistics about a stored voice that may be shown on screen.
 * @property selfSimilarityMean mean similarity of the enrolment pieces to the template
 * @property selfSimilarityMin lowest similarity of an enrolment piece to the template
 * @property threshold similarity at or above which a voice counts as the owner
 * @property margin width of the unclear band below [threshold]
 * @property cohortId creation time of the cohort the threshold was set with; [NO_COHORT] = none, null = not recorded
 * @property suggestedThreshold threshold computed by the last enrolment
 */
data class VoiceprintInfo(
    val model: String,
    val dimensions: Int,
    val createdAtMs: Long,
    val speechSeconds: Double,
    val pieces: Int,
    val selfSimilarityMean: Float,
    val selfSimilarityMin: Float,
    val threshold: Float,
    val margin: Float = DEFAULT_MARGIN,
    val cohortId: Long? = null,
    val suggestedThreshold: Float? = null
) {
    companion object {
        /** Default width of the unclear band below the threshold. */
        const val DEFAULT_MARGIN = 0.07f

        /** [cohortId] of a threshold set on the raw scale, without a cohort. */
        const val NO_COHORT = 0L
    }
}

/**
 * Stores voice templates and the cohort mean, encrypted with an own Android Keystore key (AES-256-GCM), under
 * `files/robocontrol/voice/`. Several named voices can be stored; the detectors use the active one.
 */
class OwnerVoiceprintStore private constructor(private val context: Context) {

    private val cipher = KeystoreZoneCipher(KEY_ALIAS)
    private val folder: File get() = File(context.filesDir, "robocontrol/voice")

    /** Preferences that hold the name of the active voice. */
    private val prefs get() = context.getSharedPreferences("robocontrol_voice", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(safeName(prefs.getString(KEY_ACTIVE, null) ?: DEFAULT_PROFILE))

    private val _generation = MutableStateFlow(0L)

    /** Incremented on every change of a stored voice, its threshold, the active voice or the cohort. */
    val generation: StateFlow<Long> = _generation.asStateFlow()

    /** Counts one change of the stored voices, threshold or cohort, so a running detector restarts with the new state. */
    private fun changed() { _generation.value = _generation.value + 1 }

    /** Name of the voice the detectors use. */
    val activeProfile: StateFlow<String> = _active.asStateFlow()

    private val _profiles = MutableStateFlow(listProfiles())

    /** All stored voices, by name. */
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    private val file: File get() = fileFor(_active.value)

    /** The encrypted file of one stored voice. */
    private fun fileFor(profile: String) = File(folder, safeName(profile) + ".print")

    /**
     * Normalises a voice name for use as a file name: letters, digits, space, "_" and "-", at most 40 characters.
     * @param profile name entered by the user
     */
    fun safeName(profile: String) = profile.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").take(40).ifBlank { DEFAULT_PROFILE }

    /** Names of all stored voices, sorted. */
    private fun listProfiles(): List<String> =
        folder.listFiles()?.filter { it.name.endsWith(".print") }?.map { it.name.removeSuffix(".print") }?.sorted().orEmpty()

    /** Switches the voice the detectors compare against. */
    @Synchronized
    fun setActiveProfile(profile: String) {
        val name = safeName(profile)
        if (_active.value == name) {
            refreshInfo()
            return
        }
        _active.value = name
        prefs.edit().putString(KEY_ACTIVE, name).apply()
        refreshInfo()
        Log.i(TAG, "active voice: $name")
        changed()
    }

    /** Re-reads the statistics of the active voice for [info]; the values themselves are zeroed at once. */
    private fun refreshInfo() {
        _info.value = runCatching { load()?.also { it.zero() }?.info }.getOrNull()
    }

    /** Null if the active voice can be used; otherwise why not ("no voice stored", or it cannot be read). */
    @Synchronized
    fun problem(): String? {
        if (!file.exists()) return "no owner voice stored"
        val print = load() ?: return "the stored voice cannot be read (record it again)"
        val info = print.info
        print.zero()
        return if (cohortMismatch(info)) "the owner threshold was set for a different cohort: set it again" else null
    }

    /**
     * True if [info]'s threshold was set against another cohort than the one stored now. Voices from before this was
     * recorded ([VoiceprintInfo.cohortId] null) are accepted as they are.
     */
    fun cohortMismatch(info: VoiceprintInfo): Boolean = info.cohortId != null && info.cohortId != currentCohortId()

    /** Creation time of the stored cohort, or [VoiceprintInfo.NO_COHORT]. */
    @Synchronized
    fun currentCohortId(): Long = runCatching { readCohort()?.third }.getOrNull() ?: VoiceprintInfo.NO_COHORT

    /** Deletes one stored voice; the active one falls back to whatever is left. */
    @Synchronized
    fun deleteProfile(profile: String): Boolean {
        @Suppress("NAME_SHADOWING") val profile = safeName(profile)
        val deleted = fileFor(profile).let { !it.exists() || it.delete() }
        if (deleted) {
            _profiles.value = listProfiles()
            if (_active.value == profile) {
                setActiveProfile(_profiles.value.firstOrNull() ?: DEFAULT_PROFILE)
                // Same name again (the last voice was the default one): setActiveProfile changed nothing, so say it here.
                _info.value = runCatching { load()?.also { it.zero() }?.info }.getOrNull()
                changed()
            }
            Log.i(TAG, "voice \"$profile\" deleted")
        }
        return deleted
    }

    private val _info = MutableStateFlow(runCatching { load()?.also { it.zero() }?.info }.getOrNull())

    /** What is stored, without the vector — for the screen. Null = the robot does not know a voice. */
    val info: StateFlow<VoiceprintInfo?> = _info.asStateFlow()

    /** Whether a voice is stored under the active name. */
    fun exists(): Boolean = file.exists()

    /** Reads the template. Returns null if none is stored; throws nothing — an unreadable file is reported as null. */
    @Synchronized
    fun load(): Voiceprint? {
        val f = file
        if (!f.exists()) return null
        return runCatching {
            val json = JSONObject(String(cipher.decrypt(f.readBytes(), aad()), Charsets.UTF_8))
            val array = json.getJSONArray("template")
            val values = FloatArray(array.length()) { array.getDouble(it).toFloat() }
            Voiceprint(values, info(json))
        }.onFailure { Log.e(TAG, "could not read the stored voice: $it") }.getOrNull()
    }

    /** Writes the template (temp file + rename). Returns null on success, else a message for the screen. */
    @Synchronized
    fun save(values: FloatArray, info: VoiceprintInfo): String? {
        return runCatching {
            val json = JSONObject()
                .put("model", info.model)
                .put("dimensions", info.dimensions)
                .put("createdAtMs", info.createdAtMs)
                .put("speechSeconds", info.speechSeconds)
                .put("pieces", info.pieces)
                .put("selfSimilarityMean", info.selfSimilarityMean.toDouble())
                .put("selfSimilarityMin", info.selfSimilarityMin.toDouble())
                .put("threshold", info.threshold.toDouble())
                .put("margin", info.margin.toDouble())
                .apply {
                    info.cohortId?.let { put("cohortId", it) }
                    info.suggestedThreshold?.let { put("suggestedThreshold", it.toDouble()) }
                }
                .put("template", JSONArray().apply { values.forEach { put(it.toDouble()) } })
            val bytes = cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8), aad())
            val target = file
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw java.io.IOException("could not replace ${target.name}")
            _info.value = info
            _profiles.value = listProfiles()
            changed()
            Log.i(TAG, "voice \"${_active.value}\" stored: ${info.pieces} pieces, %.1f s of speech, threshold %.2f"
                .format(info.speechSeconds, info.threshold))
            null
        }.getOrElse {
            Log.e(TAG, "could not store the voice", it)
            it.message ?: it.toString()
        }
    }

    /**
     * Sets the decision threshold of the active voice; the template is not changed.
     * @param threshold new threshold, clamped to [MIN_THRESHOLD]–[MAX_THRESHOLD]
     * @return null on success, otherwise the error message
     */
    @Synchronized
    fun setThreshold(threshold: Float): String? = update { it.copy(threshold = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)) }

    /** Changes only the unclear band of the stored voice (see [VoiceprintInfo.margin]). */
    @Synchronized
    fun setMargin(margin: Float): String? = update { it.copy(margin = margin.coerceIn(0f, MAX_MARGIN)) }

    /** Takes over the threshold the last enrolment suggested. */
    @Synchronized
    fun applySuggestedThreshold(): String? = update { info -> info.suggestedThreshold?.let { info.copy(threshold = it) } ?: info }

    /**
     * Rewrites the stored info with [change], binding the threshold to the cohort stored NOW (a threshold set by hand is
     * set on the scale the detector uses at this moment). Saves nothing if nothing changed.
     */
    private fun update(change: (VoiceprintInfo) -> VoiceprintInfo): String? {
        val print = load() ?: return "no voice stored"
        val values = print.copyValues()
        val info = change(print.info).copy(cohortId = currentCohortId())
        print.zero()
        if (info == print.info) {
            values.fill(0f)
            return null
        }
        val error = save(values, info)
        values.fill(0f)
        return error
    }

    // ---- cohort: the average of many OTHER voices, recorded through this microphone -------------------------

    private val _cohort = MutableStateFlow(runCatching { readCohort()?.second }.getOrNull())

    /** How many pieces of speech the stored cohort was averaged from, or null if none is stored. */
    val cohortPieces: StateFlow<Int?> = _cohort.asStateFlow()

    /** The cohort mean, or null. A plain average — it is nobody's voiceprint and matches no single person. */
    @Synchronized
    fun loadCohort(): FloatArray? = runCatching { readCohort()?.first }
        .onFailure { Log.e(TAG, "could not read the cohort: $it") }
        .getOrNull()

    /**
     * Stores the average of the recorded other voices (never single voices).
     * @return null on success, otherwise the reason (for example an empty average).
     */
    @Synchronized
    fun saveCohort(values: FloatArray, pieces: Int): String? = runCatching {
        // An all-zero (or nearly zero) mean would switch the centring off without anybody noticing.
        require(norm(values) >= MIN_COHORT_NORM) { "the recorded average is empty; record the other voices again" }
        val json = JSONObject()
            .put("pieces", pieces)
            .put("createdAtMs", System.currentTimeMillis())
            .put("mean", JSONArray().apply { values.forEach { put(it.toDouble()) } })
        val target = File(File(context.filesDir, "robocontrol/voice"), COHORT_FILE)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$COHORT_FILE.tmp")
        tmp.outputStream().use { out ->
            out.write(cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8), cohortAad()))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) throw java.io.IOException("could not replace ${target.name}")
        _cohort.value = pieces
        Log.i(TAG, "cohort stored: $pieces pieces")
        changed()
        null
    }.getOrElse {
        Log.e(TAG, "could not store the cohort", it)
        it.message ?: it.toString()
    }

    /** Deletes the stored cohort average. @return true if it is gone. */
    @Synchronized
    fun deleteCohort(): Boolean {
        val file = File(File(context.filesDir, "robocontrol/voice"), COHORT_FILE)
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            _cohort.value = null
            changed()
        }
        return deleted
    }

    /** Reads the cohort average with its piece count and time, or null if none is stored or it is empty. */
    private fun readCohort(): Triple<FloatArray, Int, Long>? {
        val file = File(File(context.filesDir, "robocontrol/voice"), COHORT_FILE)
        if (!file.exists()) return null
        val json = JSONObject(String(cipher.decrypt(file.readBytes(), cohortAad()), Charsets.UTF_8))
        val array = json.getJSONArray("mean")
        val mean = FloatArray(array.length()) { array.getDouble(it).toFloat() }
        // Written by the bug fixed 2026-10-19 (Stop during a cohort run stored zeros): treat it as no cohort.
        if (norm(mean) < MIN_COHORT_NORM) {
            Log.w(TAG, "stored cohort is empty (all zeros): ignored, record the other voices again")
            return null
        }
        return Triple(mean, json.optInt("pieces"), json.optLong("createdAtMs", 1L))
    }

    /** Euclidean length of [values]. */
    private fun norm(values: FloatArray): Double = kotlin.math.sqrt(values.sumOf { it.toDouble() * it })

    /** Associated data of the cohort file: binds it to its file name. */
    private fun cohortAad() = "roboguard-voiceprint:$COHORT_FILE".toByteArray(Charsets.UTF_8)

    /** Deletes the stored voice. */
    @Synchronized
    fun delete(): Boolean {
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            _info.value = null
            _profiles.value = listProfiles()
            Log.i(TAG, "stored voice \"${_active.value}\" deleted")
            changed()
        }
        return deleted
    }

    /** The statistics of a stored voice as written in its file. */
    private fun info(json: JSONObject) = VoiceprintInfo(
        model = json.optString("model"),
        dimensions = json.optInt("dimensions"),
        createdAtMs = json.optLong("createdAtMs"),
        speechSeconds = json.optDouble("speechSeconds", 0.0),
        pieces = json.optInt("pieces"),
        selfSimilarityMean = json.optDouble("selfSimilarityMean", 0.0).toFloat(),
        selfSimilarityMin = json.optDouble("selfSimilarityMin", 0.0).toFloat(),
        threshold = json.optDouble("threshold", 0.0).toFloat(),
        margin = json.optDouble("margin", VoiceprintInfo.DEFAULT_MARGIN.toDouble()).toFloat(),
        cohortId = if (json.has("cohortId")) json.optLong("cohortId") else null,
        suggestedThreshold = if (json.has("suggestedThreshold")) json.optDouble("suggestedThreshold").toFloat() else null
    )

    /** Binds the file to its purpose: a copy dropped into another app's folder cannot be decrypted. */
    private fun aad() = "roboguard-voiceprint:${file.name}".toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAG = "OwnerVoiceprint"
        private const val COHORT_FILE = "cohort.mean"

        /** Name of the voice used when nothing else is chosen; also the file the very first enrolment wrote. */
        const val DEFAULT_PROFILE = "owner"

        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_ALIAS = "robocontrol_owner_voiceprint"

        /** Lowest threshold that can be set. */
        const val MIN_THRESHOLD = 0.2f
        const val MAX_THRESHOLD = 0.97f

        /** Widest unclear band that can be set. */
        const val MAX_MARGIN = 0.4f

        /** A cohort mean shorter than this is empty (zeros), not an average of voices. */
        private const val MIN_COHORT_NORM = 1e-3

        @Volatile
        private var instance: OwnerVoiceprintStore? = null

        /** The one store of the process. */
        fun get(context: Context): OwnerVoiceprintStore =
            instance ?: synchronized(this) {
                instance ?: OwnerVoiceprintStore(context.applicationContext).also { instance = it }
            }
    }
}
