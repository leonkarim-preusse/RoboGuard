package com.example.testing.voiceprobe

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log of the test screens. Every line is shown on screen ([lines]) and written to a file in `files/voiceprobe/`, one file
 * per session, with seconds since the log was created. Speech payloads appear on screen as a short preview, but only
 * their shape (length, JSON keys) is written to the file. Thread-safe.
 * @param context used to locate the log folder
 */
class ProbeLog(context: Context) {

    /** Reference point for the relative timestamps on every line. */
    private val start = SystemClock.elapsedRealtime()

    /** Log file of this session. */
    private val file: File = File(context.filesDir, "voiceprobe")
        .apply { mkdirs() }
        .resolve("probe-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log")

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Lines for the screen, newest last, capped at [MAX_SCREEN_LINES]. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** Absolute path of this session's log file, shown at the top of the probe screen. */
    val filePath: String get() = file.absolutePath

    /** Writes a visible separator, used when a probe starts. */
    fun section(title: String) = write("==== $title ====", "==== $title ====")

    /**
     * Logs a message to the screen and to the file.
     * @param tag short name of the test
     * @param message text without speech content
     */
    fun i(tag: String, message: String) = write("[$tag] $message", "[$tag] $message")

    /**
     * Logs a value that may contain speech content: the screen gets its shape and a short preview, the file only the shape.
     * @param tag short name of the test
     * @param name what the value is, e.g. the callback name
     * @param value raw payload; may be null
     */
    fun payload(tag: String, name: String, value: String?) {
        val shape = describe(value)
        val preview = value?.take(PREVIEW_CHARS)?.replace('\n', ' ')
        write("[$tag] $name: $shape | \"$preview\"", "[$tag] $name: $shape")
    }

    /** Clears the on-screen lines. The file is kept, so no results are lost. */
    fun clearScreen() { _lines.value = emptyList() }

    /**
     * Appends one line to the screen list and to the file; file errors are ignored.
     * @param screen line for the screen
     * @param persisted line for the file
     */
    @Synchronized
    private fun write(screen: String, persisted: String) {
        val t = "%7.2f".format(Locale.US, (SystemClock.elapsedRealtime() - start) / 1000.0)
        _lines.value = (_lines.value + "$t $screen").takeLast(MAX_SCREEN_LINES)
        runCatching { file.appendText("$t $persisted\n") }
    }

    companion object {
        /** Long enough to recognise a format, short enough not to show whole sentences. */
        private const val PREVIEW_CHARS = 160

        /** Keeps the Compose list responsive during long sessions. */
        private const val MAX_SCREEN_LINES = 500

        /**
         * Describes a payload by its length and, for JSON, its keys.
         * @param value the payload
         */
        fun describe(value: String?): String {
            if (value == null) return "null"
            val trimmed = value.trimStart()
            val json = when {
                trimmed.startsWith("{") -> runCatching {
                    "json-object keys=" + JSONObject(trimmed).keys().asSequence().toList()
                }.getOrNull()
                trimmed.startsWith("[") -> runCatching {
                    "json-array len=" + JSONArray(trimmed).length()
                }.getOrNull()
                else -> null
            }
            return "len=${value.length}" + (json?.let { ", $it" } ?: "")
        }
    }
}
