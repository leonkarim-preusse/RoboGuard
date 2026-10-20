package com.example.robocontrol.text

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * All texts shown on the robot's screen or spoken, loaded from `assets/texts/texts.json`. Each entry has a `text` and a
 * `note`; `meta.speechLanguage` selects the speech language. Placeholders are written as `{name}` and passed as pairs
 * to [get]. A missing key returns the key itself. Thread-safe.
 */
object UiText {

    private const val TAG = "UiText"
    private const val ASSET_FILE = "texts/texts.json"

    @Volatile
    private var texts: Map<String, String> = emptyMap()

    @Volatile
    private var meta: Map<String, String> = emptyMap()

    @Volatile
    private var appContext: Context? = null

    /**
     * Loads the texts (idempotent). Call once at start-up, e.g. in the service's onCreate; screens and speech call [get]
     * afterwards. Cheap enough for the main thread (one small JSON file).
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        reload()
    }

    /** Voice for the spoken sentences, from `meta.speechLanguage`; German if the file does not say. */
    val speechLanguage: String get() = meta["speechLanguage"] ?: "de_DE"

    /** Speaker of the spoken sentences (`meta.speechVoice`, e.g. "en-us-wavenet-c"); null = the robot's own setting. */
    val speechVoice: String? get() = meta["speechVoice"]?.takeIf { it.isNotBlank() }

    /** Re-reads the file (after an app update or for tests). */
    @Synchronized
    fun reload() {
        val context = appContext ?: return
        val json = runCatching { JSONObject(context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }) }
            .onFailure { Log.e(TAG, "could not read $ASSET_FILE: $it") }
            .getOrNull() ?: return
        val entries = json.optJSONObject("texts")
        val merged = LinkedHashMap<String, String>()
        if (entries != null) {
            for (key in entries.keys()) {
                val entry = entries.opt(key)
                val text = if (entry is JSONObject) entry.optString("text") else entry?.toString()
                if (!text.isNullOrEmpty()) merged[key] = text
            }
        }
        texts = merged
        meta = json.optJSONObject("meta")?.let { m -> buildMap { for (k in m.keys()) put(k, m.optString(k)) } } ?: emptyMap()
        Log.i(TAG, "loaded ${merged.size} texts, speech language $speechLanguage")
    }

    /**
     * Returns the text for [key] with the placeholders filled in.
     * @param key text key
     * @param placeholders name → value pairs, e.g. "location" to "Kitchen" for `{location}`
     */
    fun get(key: String, vararg placeholders: Pair<String, Any?>): String {
        val template = texts[key] ?: run {
            Log.w(TAG, "missing text '$key' in assets/$ASSET_FILE")
            return key
        }
        if (placeholders.isEmpty()) return template
        var result = template
        for ((name, value) in placeholders) result = result.replace("{$name}", value?.toString() ?: "")
        return result
    }

    /** Like [get], but returns null for a missing key (for optional texts). */
    fun getOrNull(key: String): String? = texts[key]

    /** All keys currently loaded, for tooling and tests. */
    val keys: Set<String> get() = texts.keys
}
