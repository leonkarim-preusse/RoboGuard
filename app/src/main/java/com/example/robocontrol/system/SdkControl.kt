package com.example.robocontrol.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ainirobot.coreservice.client.RobotApi
import kotlinx.coroutines.delay

/** Waits until RobotOS has given RoboGuard SDK control after a screen was brought to the front. */
object SdkControl {
    private const val TAG = "SdkControl"

    /**
     * Waits until RobotOS grants SDK control, then [settleMs] more if control had to be regained.
     * @param context Android context
     * @param timeoutMs maximum wait
     * @param settleMs extra wait after regaining control
     * @return true if RoboGuard is in control
     */
    suspend fun awaitControl(context: Context, timeoutMs: Long = 3_000, settleMs: Long = 1_000): Boolean {
        RobotApiConnection.connect(context)
        val api = RobotApi.getInstance()
        /**
         * Checks whether RoboGuard has SDK control.
         * @return true if it has control
         */
        fun active() = runCatching { api.isApiConnectedService() && api.isActive() }.getOrDefault(false)
        val wasActive = active()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!active()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "no SDK control after $timeoutMs ms")
                return false
            }
            delay(100)
        }
        if (!wasActive) delay(settleMs)
        return true
    }
}
