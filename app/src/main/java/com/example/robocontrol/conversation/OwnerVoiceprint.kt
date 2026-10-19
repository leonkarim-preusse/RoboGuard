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
 * What the robot knows about its owner's voice, and everything it is allowed to do with it.
 *
 * [Voiceprint] deliberately offers no way to read the vector out: it can be compared and it can be erased, nothing
 * else. The values are a biometric template, so the type makes the safe use the only use — no getter, no copy, no
 * serialisation except the one encrypted file below.
 */
class Voiceprint internal constructor(private val values: FloatArray, val info: VoiceprintInfo) {

    /**
     * How close [embedding] (L2-normalised) is to this voice: 1 = identical, 0 = unrelated.
     *
     * With a [cohort] mean the comparison is CENTRED: the average of many other voices heard through the same
     * microphone is subtracted from both sides first. Every recording made in one room through one microphone shares a
     * large part that says nothing about the person — on this robot it pushed the owner to ~0.90 and a stranger to
     * ~0.80 (2026-09-23). Removing it is what makes the remaining difference readable; it is the standard trick from
     * speaker verification (centering / score normalisation).
     */
    fun similarityTo(embedding: FloatArray, cohort: FloatArray? = null): Float =
        centredSimilarity(values, embedding, cohort)

    /** Overwrites the values in memory. Call when the template is no longer needed. */
    fun zero() = values.fill(0f)

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
 * Everything about the template that may be shown on screen: how it was made and how well it separates. The numbers
 * are statistics, not voice data.
 *
 * @property selfSimilarityMean how close the enrolment pieces were to the finished template on average
 * @property selfSimilarityMin the worst of those pieces — the basis for [threshold]
 * @property threshold similarity from which a voice counts as the owner
 * @property margin how far below [threshold] a piece must be to count as somebody else (the unclear band). Per voice,
 *           because it should be about half the owner's own spread; 0.02 for everybody was far too narrow next to a
 *           measured spread of 0.14–0.20 (review 2026-10-02, item 10).
 * @property cohortId which cohort the threshold was set against: its creation time, [NO_COHORT] for the raw scale, null
 *           for voices stored before this was recorded. Removing or re-recording the cohort changes the scale of every
 *           similarity, so a threshold only means something together with its cohort (item 11).
 * @property suggestedThreshold what the last enrolment computed, kept next to a hand-set [threshold] instead of
 *           overwriting it (item 12)
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
        /** About half the owner's measured spread (0.14–0.20); the replay of three owner runs gave no false episode with it. */
        const val DEFAULT_MARGIN = 0.07f

        /** [cohortId] of a threshold set on the raw scale, without a cohort. */
        const val NO_COHORT = 0L
    }
}

/**
 * Stores the owner's template, encrypted with its own Android Keystore key (AES-256-GCM, the same arrangement as the
 * private areas and the saved places, but a separate key: one key per purpose). File:
 * `files/robocontrol/voice/owner.print`.
 *
 * Only this one voice is ever written to disk. Voices the robot hears while it is running are compared and forgotten.
 */
class OwnerVoiceprintStore private constructor(private val context: Context) {

    private val cipher = KeystoreZoneCipher(KEY_ALIAS)
    private val folder: File get() = File(context.filesDir, "robocontrol/voice")

    /**
     * Several voices can be stored side by side, under names, and one of them is the ACTIVE one that the detectors compare
     * against. That is for testing: record two or three people, switch between them, and read off how close each one comes
     * to the others. Only the active voice is ever used for a decision.
     */
    private val prefs get() = context.getSharedPreferences("robocontrol_voice", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(safeName(prefs.getString(KEY_ACTIVE, null) ?: DEFAULT_PROFILE))

    private val _generation = MutableStateFlow(0L)

    /**
     * Counts every change of what the detectors compare against: a voice stored, deleted or switched, its threshold
     * changed, the cohort replaced or deleted. ConversationMonitor restarts a running OWNER/PAIRWISE detector on it, which
     * otherwise kept deciding with what it loaded at its start (review 2026-10-02, item 6).
     */
    val generation: StateFlow<Long> = _generation.asStateFlow()

    private fun changed() { _generation.value = _generation.value + 1 }

    /** Name of the voice the detectors use. */
    val activeProfile: StateFlow<String> = _active.asStateFlow()

    private val _profiles = MutableStateFlow(listProfiles())

    /** All stored voices, by name. */
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    private val file: File get() = fileFor(_active.value)

    private fun fileFor(profile: String) = File(folder, safeName(profile) + ".print")

    /**
     * File names are user input: keep them to letters, digits and a few separators. Every name is normalised ONCE here,
     * so the active name, the listed names and the file always agree ("Jürgen" is "J_rgen" everywhere, item 56).
     */
    fun safeName(profile: String) = profile.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").take(40).ifBlank { DEFAULT_PROFILE }

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
     * Changes only the decision threshold of the stored voice; the template itself stays as it was.
     *
     * The threshold the enrolment computes comes from how consistent the pieces of ONE recording were, which is far too
     * strict: measured on the robot 2026-09-23, the owner's own voice scored 0.89–0.91 against a computed threshold of
     * 0.94. Being able to set it by hand from a measured value is the honest way round that.
     *
     * @return null on success, else a message for the screen
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

    @Synchronized
    fun saveCohort(values: FloatArray, pieces: Int): String? = runCatching {
        // An all-zero (or nearly zero) mean would switch the centring off without anybody noticing (review 2026-10-02, item 5).
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

    private fun norm(values: FloatArray): Double = kotlin.math.sqrt(values.sumOf { it.toDouble() * it })

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

        /** Range the threshold can be set to by hand. Below 0.2 almost any voice passes, above 0.97 not even the owner. */
        const val MIN_THRESHOLD = 0.2f
        const val MAX_THRESHOLD = 0.97f

        /** Widest unclear band that can be set. */
        const val MAX_MARGIN = 0.4f

        /** A cohort mean shorter than this is empty (zeros), not an average of voices. */
        private const val MIN_COHORT_NORM = 1e-3

        @Volatile
        private var instance: OwnerVoiceprintStore? = null

        fun get(context: Context): OwnerVoiceprintStore =
            instance ?: synchronized(this) {
                instance ?: OwnerVoiceprintStore(context.applicationContext).also { instance = it }
            }
    }
}
