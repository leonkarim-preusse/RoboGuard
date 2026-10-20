package com.example.robocontrol.audio

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Which voice ("speaker role") the robot's speech service offers for a language.
 *
 * The service speaks through a cloud TTS with ONE robot-wide speaker (here the German "de-de-wavenet-c"); telling it that a
 * sentence is English does not change the voice, and a guessed name ("en-us-wavenet-c") was refused by the cloud with error
 * 400 and nothing was spoken (robot log 2026-10-19). The service keeps the list it got from the cloud in its own provider
 * (`content://com.ainirobot.speechservice.SpokesmanProvider/spokesman_list`, columns langCode, nickname, type, is_default),
 * which an app may read with a normal permission. `type` is the name the cloud accepts as SpeakerRole.
 */
object TtsVoices {
    private const val TAG = "TtsVoices"
    private val LIST: Uri = Uri.parse("content://com.ainirobot.speechservice.SpokesmanProvider/spokesman_list")

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    /** The speaker for [language] ("en_US"): the list's default for that language, else its first; null if there is none. */
    fun voiceFor(context: Context, language: String): String? {
        val key = normalise(language)
        cache[key]?.let { return it }
        val found = runCatching {
            context.contentResolver.query(LIST, null, null, null, null)?.use { c ->
                val lang = c.getColumnIndex("langCode")
                val type = c.getColumnIndex("type")
                val default = c.getColumnIndex("is_default")
                var first: String? = null
                var preferred: String? = null
                val all = ArrayList<String>()
                while (c.moveToNext()) {
                    val l = if (lang >= 0) c.getString(lang) else null
                    val t = if (type >= 0) c.getString(type) else null
                    if (l != null && t != null) all += "$l=$t"
                    if (l == null || t.isNullOrBlank() || normalise(l) != key) continue
                    if (first == null) first = t
                    if (default >= 0 && c.getString(default).let { it == "1" || it.equals("true", true) }) preferred = t
                }
                Log.i(TAG, "voices offered: ${all.joinToString()}")
                preferred ?: first
            }
        }.onFailure { Log.w(TAG, "could not read the voice list: $it") }.getOrNull()
        if (found != null) {
            cache = cache + (key to found)
            Log.i(TAG, "voice for $language: $found")
        } else {
            Log.w(TAG, "no voice for $language in the speech service's list; the robot's own voice is used")
        }
        return found
    }

    /** "en-US" and "en_us" both become "en_us", so language codes can be compared. */
    private fun normalise(code: String) = code.replace('-', '_').lowercase()
}
