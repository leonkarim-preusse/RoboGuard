package com.example.robocontrol.voiceprobe

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
 * Shared log for all voice probes.
 *
 * Every line goes to two places:
 *  1. [lines], which [VoiceProbeActivity] shows on screen while the test is running
 *  2. a file under `filesDir/voiceprobe/`, one file per app start, so a test session can be
 *     read later with `adb exec-out run-as com.example.roboguard cat files/voiceprobe/<file>`
 *
 * Why the two outputs differ for speech payloads:
 * the speech service can hand the app transcripts of what people in the room said. To find
 * out what format a callback uses, a short preview has to be visible. Writing that preview to
 * disk would silently collect conversation content, which is the exact behaviour the thesis
 * argues against. So [payload] shows a preview on screen only, and the file receives just the
 * shape (length and JSON keys).
 *
 * Timestamps are seconds since this log was created, so that events from different
 * callbacks can be lined up against each other.
 *
 * Thread-safe: probes call it from binder threads, the audio thread and the main thread.
 */
class ProbeLog(context: Context) {

    /** Reference point for the relative timestamps on every line. */
    private val start = SystemClock.elapsedRealtime()

    /** One file per session, e.g. `files/voiceprobe/probe-20260915-143000.log`. */
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
     * Logs a message identically to screen and file.
     * Use only for text that cannot contain speech content (states, codes, numbers, angles).
     *
     * @param tag short probe name, e.g. "mic", "speech", "direction"
     */
    fun i(tag: String, message: String) = write("[$tag] $message", "[$tag] $message")

    /**
     * Logs a value whose format is unknown and that may contain speech content.
     * The screen gets the shape plus a preview of up to [PREVIEW_CHARS] characters;
     * the file gets the shape only.
     *
     * @param name what the value is, e.g. the callback name
     * @param value the raw payload as received from the SDK, may be null
     */
    fun payload(tag: String, name: String, value: String?) {
        val shape = describe(value)
        val preview = value?.take(PREVIEW_CHARS)?.replace('\n', ' ')
        write("[$tag] $name: $shape | \"$preview\"", "[$tag] $name: $shape")
    }

    /** Clears the on-screen lines. The file is kept, so no results are lost. */
    fun clearScreen() { _lines.value = emptyList() }

    /**
     * Appends one line to both outputs.
     * Synchronized so that lines from concurrent callbacks are neither lost nor interleaved.
     * File errors are ignored on purpose: a full disk must not crash a hardware test.
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
         * Describes a payload without keeping its content, e.g.
         * `len=42, json-object keys=[angle, text]` or `len=12`.
         * Anything that is not valid JSON is reported by length only.
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
