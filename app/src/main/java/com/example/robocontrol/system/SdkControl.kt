package com.example.robocontrol.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ainirobot.coreservice.client.RobotApi
import kotlinx.coroutines.delay

/**
 * Waiting for RobotOS to give RoboGuard SDK control (speech, driving) after bringing a screen to the front.
 *
 * Found on the robot (see CLAUDE.md): `startActivity` only queues the start, control arrives ~0.3–0.5 s later, and RobotOS
 * calls stopTTS right after the app change. Speaking earlier is refused ("skillType is SUSPEND").
 */
object SdkControl {

    private const val TAG = "SdkControl"

    /**
     * Polls `RobotApi.isActive()` every 100 ms for at most [timeoutMs]; if control had to be regained, waits [settleMs] more.
     * @return true if RoboGuard is in control
     */
    suspend fun awaitControl(context: Context, timeoutMs: Long = 3_000, settleMs: Long = 1_000): Boolean {
        RobotApiConnection.connect(context)
        val api = RobotApi.getInstance()
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
