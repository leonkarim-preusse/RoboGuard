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

    /** How close [embedding] (L2-normalised) is to this voice: 1 = identical, 0 = unrelated. */
    fun similarityTo(embedding: FloatArray): Float = SpeakerEmbedder.similarity(values, embedding)

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
        private const val KEY_ALIAS = "robocontrol_owner_voiceprint"

        @Volatile
        private var instance: OwnerVoiceprintStore? = null

        fun get(context: Context): OwnerVoiceprintStore =
            instance ?: synchronized(this) {
                instance ?: OwnerVoiceprintStore(context.applicationContext).also { instance = it }
            }
    }
}
