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
    fun similarityTo(embedding: FloatArray, cohort: FloatArray? = null): Float {
        if (cohort == null || cohort.size != values.size) return SpeakerEmbedder.similarity(values, embedding)
        val a = FloatArray(values.size) { values[it] - cohort[it] }
        val b = FloatArray(embedding.size) { embedding[it] - cohort[it] }
        SpeakerEmbedder.normalise(a)
        SpeakerEmbedder.normalise(b)
        val similarity = SpeakerEmbedder.similarity(a, b)
        a.fill(0f)
        b.fill(0f)
        return similarity
    }

    /** Overwrites the values in memory. Call when the template is no longer needed. */
    fun zero() = values.fill(0f)

    internal fun copyValues(): FloatArray = values.copyOf()

    /** Never prints the values. */
    override fun toString(): String = "Voiceprint(${info.dimensions} values, taught ${info.createdAtMs})"
}

/**
 * Everything about the template that may be shown on screen: how it was made and how well it separates. The numbers
 * are statistics, not voice data.
 *
 * @property selfSimilarityMean how close the enrolment pieces were to the finished template on average
 * @property selfSimilarityMin the worst of those pieces — the basis for [threshold]
 * @property threshold similarity from which a voice counts as the owner
 */
data class VoiceprintInfo(
    val model: String,
    val dimensions: Int,
    val createdAtMs: Long,
    val speechSeconds: Double,
    val pieces: Int,
    val selfSimilarityMean: Float,
    val selfSimilarityMin: Float,
    val threshold: Float
)

/**
 * Stores the owner's template, encrypted with its own Android Keystore key (AES-256-GCM, the same arrangement as the
 * private areas and the saved places, but a separate key: one key per purpose). File:
 * `files/robocontrol/voice/owner.print`.
 *
 * Only this one voice is ever written to disk. Voices the robot hears while it is running are compared and forgotten.
 */
class OwnerVoiceprintStore private constructor(private val context: Context) {

    private val cipher = KeystoreZoneCipher(KEY_ALIAS)
    private val file: File get() = File(File(context.filesDir, "robocontrol/voice"), FILE_NAME)

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
                .put("template", JSONArray().apply { values.forEach { put(it.toDouble()) } })
            val bytes = cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8), aad())
            val target = file
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw java.io.IOException("could not replace ${target.name}")
            _info.value = info
            Log.i(TAG, "voice stored: ${info.pieces} pieces, %.1f s of speech, threshold %.2f"
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
    fun setThreshold(threshold: Float): String? {
        val print = load() ?: return "no voice stored"
        val values = print.copyValues()
        val info = print.info.copy(threshold = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD))
        print.zero()
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
        null
    }.getOrElse {
        Log.e(TAG, "could not store the cohort", it)
        it.message ?: it.toString()
    }

    @Synchronized
    fun deleteCohort(): Boolean {
        val file = File(File(context.filesDir, "robocontrol/voice"), COHORT_FILE)
        val deleted = !file.exists() || file.delete()
        if (deleted) _cohort.value = null
        return deleted
    }

    private fun readCohort(): Pair<FloatArray, Int>? {
        val file = File(File(context.filesDir, "robocontrol/voice"), COHORT_FILE)
        if (!file.exists()) return null
        val json = JSONObject(String(cipher.decrypt(file.readBytes(), cohortAad()), Charsets.UTF_8))
        val array = json.getJSONArray("mean")
        return FloatArray(array.length()) { array.getDouble(it).toFloat() } to json.optInt("pieces")
    }

    private fun cohortAad() = "roboguard-voiceprint:$COHORT_FILE".toByteArray(Charsets.UTF_8)

    /** Deletes the stored voice. */
    @Synchronized
    fun delete(): Boolean {
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            _info.value = null
            Log.i(TAG, "stored voice deleted")
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
        threshold = json.optDouble("threshold", 0.0).toFloat()
    )

    /** Binds the file to its purpose: a copy dropped into another app's folder cannot be decrypted. */
    private fun aad() = "roboguard-voiceprint:$FILE_NAME".toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAG = "OwnerVoiceprint"
        private const val FILE_NAME = "owner.print"
        private const val COHORT_FILE = "cohort.mean"
        private const val KEY_ALIAS = "robocontrol_owner_voiceprint"

        /** Range the threshold can be set to by hand. Below 0.2 almost any voice passes, above 0.97 not even the owner. */
        const val MIN_THRESHOLD = 0.2f
        const val MAX_THRESHOLD = 0.97f

        @Volatile
        private var instance: OwnerVoiceprintStore? = null

        fun get(context: Context): OwnerVoiceprintStore =
            instance ?: synchronized(this) {
                instance ?: OwnerVoiceprintStore(context.applicationContext).also { instance = it }
            }
    }
}
