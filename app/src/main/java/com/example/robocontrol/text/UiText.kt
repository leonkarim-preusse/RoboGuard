package com.example.robocontrol.text

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * All texts the robot shows on its screen or says out loud. They live in one file, `robocontrol/assets/texts/texts.json`
 * (ships in the app as `assets/texts/texts.json`): one entry per text, so wording — or the whole language — can be changed
 * there without touching the code.
 *
 * File format:
 * ```json
 * "conversation_popup.title": { "text": "Conversation detected", "note": "Pop-up when more than one person talks: headline" }
 * ```
 * `note` only says where the text appears and is never shown. `meta.speechLanguage` ("de_DE" / "en_US") picks the voice for
 * the spoken sentences (`speech.*` keys).
 *
 * Placeholders are named and written in curly braces, e.g. `"Drive to {location}"`; pass them as pairs:
 * `UiText.get("nav.button.drive_to", "location" to name)`. Unknown placeholders stay as they are, so nothing disappears
 * silently, and a missing key returns the key name (visible on screen instead of an empty label).
 *
 * Thread-safe: the file is read once into an immutable map and only re-read by [reload].
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
     * The text for [key], with [placeholders] filled in ("location" to "Kitchen" replaces `{location}`).
     * Unknown keys return the key itself and are logged, so a missing entry shows up instead of an empty screen.
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
