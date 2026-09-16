package com.example.robocontrol.movement

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Event log of the "Navigation and Map" screen: lines for the screen plus Logcat (tag [LOGCAT_TAG]).
 *
 * Unlike the test screens' ProbeLog, nothing is written to a file: in normal use, positions and drives are shown
 * only while the screen is open.
 *
 * Thread-safe: called from the poll coroutine, SDK callback threads and the main thread.
 */
class NavigationLog {

    private val start = SystemClock.elapsedRealtime()

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Lines for the screen, newest last, capped at [MAX_SCREEN_LINES]. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** Writes a visible separator. */
    fun section(title: String) = write("==== $title ====")

    fun i(tag: String, message: String) = write("[$tag] $message")

    fun clearScreen() { _lines.value = emptyList() }

    @Synchronized
    private fun write(line: String) {
        val t = "%7.2f".format(Locale.US, (SystemClock.elapsedRealtime() - start) / 1000.0)
        _lines.value = (_lines.value + "$t $line").takeLast(MAX_SCREEN_LINES)
        Log.i(LOGCAT_TAG, line)
    }

    companion object {
        const val LOGCAT_TAG = "RoboGuardNav"
        private const val MAX_SCREEN_LINES = 500
    }
}
