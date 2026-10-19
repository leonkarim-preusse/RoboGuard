package com.example.robocontrol.system

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.example.robocontrol.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hidden debug mode, unlocked like Android's developer options: tap a screen's header [TAPS_NEEDED] times in a row.
 * From the third tap a toast counts down; the last one switches debug mode on. It is switched off again with the
 * "Show debug" switch, which is only visible while debug mode is on.
 *
 * Kept in SharedPreferences, so it is the same on every screen and survives a restart.
 */
object DebugMode {

    private const val PREFS = "robocontrol_debug_mode"
    private const val KEY = "enabled"

    /** Taps in a row that unlock debug mode (Android's build number also needs 7). */
    const val TAPS_NEEDED = 7

    /** A pause longer than this starts the count again. */
    private const val TAP_GAP_MS = 1_500L

    /** The countdown toast appears from this tap on, so a single accidental tap shows nothing. */
    private const val COUNTDOWN_FROM = 3

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false
    private var taps = 0
    private var lastTapAt = 0L
    private var toast: Toast? = null

    /** Reads the stored state once (idempotent). Call before showing a screen that depends on it. */
    fun init(context: Context) {
        if (loaded) return
        _enabled.value = prefs(context).getBoolean(KEY, false)
        loaded = true
    }

    /** One tap on a header. Call on the main thread. */
    fun tapHeader(context: Context) {
        init(context)
        if (_enabled.value) {
            show(context, UiText.get("debug.toast.already_on"))
            return
        }
        val now = SystemClock.elapsedRealtime()
        taps = if (now - lastTapAt > TAP_GAP_MS) 1 else taps + 1
        lastTapAt = now
        val left = TAPS_NEEDED - taps
        when {
            left <= 0 -> {
                taps = 0
                set(context, true)
                show(context, UiText.get("debug.toast.on"))
            }
            taps >= COUNTDOWN_FROM -> show(context, UiText.get("debug.toast.steps", "taps" to left))
        }
    }

    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
        _enabled.value = on
        loaded = true
    }

    private fun show(context: Context, text: String) {
        // Replace the previous toast so fast taps do not queue up a row of them.
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
