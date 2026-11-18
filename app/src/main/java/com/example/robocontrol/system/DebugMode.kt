package com.example.robocontrol.system

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.example.robocontrol.text.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hidden debug mode: tapping a screen header [TAPS_NEEDED] times in a row switches it on, the "Show debug" switch
 * switches it off. Saved in SharedPreferences.
 */
object DebugMode {
    private const val PREFS = "robocontrol_debug_mode"
    private const val KEY = "enabled"

    const val TAPS_NEEDED = 7

    private const val TAP_GAP_MS = 1_500L

    private const val COUNTDOWN_FROM = 3

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false
    private var taps = 0
    private var lastTapAt = 0L
    private var toast: Toast? = null

    /**
     * Reads the stored state once (idempotent). Call before showing a screen that depends on it.
     * @param context Android context
     */
    fun init(context: Context) {
        if (loaded) return
        _enabled.value = prefs(context).getBoolean(KEY, false)
        loaded = true
    }

    /**
     * One tap on a header. Call on the main thread.
     * @param context Android context
     */
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

    /**
     * Switches debug mode on or off and saves it.
     * @param context any context
     * @param on new state
     */
    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
        _enabled.value = on
        loaded = true
    }

    /**
     * Shows [text] as a toast, replacing the previous one.
     * @param context Android context
     * @param text toast text
     */
    private fun show(context: Context, text: String) {
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    /**
     * The preferences that hold the debug mode.
     * @param context any context
     * @return the preferences
     */
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
