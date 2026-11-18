package com.example.robocontrol.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.robotsetting.RobotSettingApi

/** Makes RoboGuard the robot's default app, which gives it SDK control in the foreground, and can undo it. */
object DefaultAppSetting {
    const val TAG = "DefaultApp"
    private const val PREFS = "robocontrol_default_app"
    private const val KEY_PREVIOUS = "previous_boot_app"
    private const val KEY_HAS_PREVIOUS = "has_previous_boot_app"
    private const val CONNECT_TIMEOUT_MS = 5_000L

    /** Outcome of [ensureRoboGuardIsDefault] / [restorePrevious]. */
    sealed interface Result {
        /**
         * The setting already named [value] (this app); nothing was written.
         * @property value current value
         */
        data class AlreadySet(val value: String) : Result
        /**
         * The setting was changed [from] the previous app [to] this one.
         * @property from previous value
         * @property to new value
         */
        data class Changed(val from: String, val to: String) : Result
        /**
         * Reading or writing the setting failed; [reason] says why.
         * @property reason error text
         */
        data class Failed(val reason: String) : Result
    }

    /**
     * Sets `boot_app_package_name` to this app's package if it is not already. [onDone] runs on the main thread.
     * @param context Android context
     * @param onDone receives the result on the main thread
     */
    fun ensureRoboGuardIsDefault(context: Context, onDone: (Result) -> Unit = {}) {
        val app = context.applicationContext
        val target = app.packageName
        withRobotApi(app, onDone) {
            val current = RobotSettingApi.getInstance().getRobotString(Definition.BOOT_APP_PACKAGE_NAME).orEmpty()
            Log.i(TAG, "boot_app_package_name is \"$current\"")
            if (current == target) return@withRobotApi Result.AlreadySet(current)

            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_PREVIOUS, false)) {
                prefs.edit().putString(KEY_PREVIOUS, current).putBoolean(KEY_HAS_PREVIOUS, true).commit()
            }
            write(current, target)
        }
    }

    /**
     * Puts back the default app that was set before RoboGuard's first change (possibly none).
     * @param context Android context
     * @param onDone receives the result on the main thread
     */
    fun restorePrevious(context: Context, onDone: (Result) -> Unit = {}) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_PREVIOUS, false)) {
            onDone(Result.Failed("no previous value stored; RoboGuard never changed the default app"))
            return
        }
        val previous = prefs.getString(KEY_PREVIOUS, "").orEmpty()
        withRobotApi(app, onDone) {
            val current = RobotSettingApi.getInstance().getRobotString(Definition.BOOT_APP_PACKAGE_NAME).orEmpty()
            if (current == previous) return@withRobotApi Result.AlreadySet(current)
            write(current, previous).also { if (it is Result.Changed) prefs.edit().clear().commit() }
        }
    }

    /**
     * Writes [to] and reads it back.
     * @param from previous value
     * @param to new value
     * @return the result
     */
    private fun write(from: String, to: String): Result {
        RobotSettingApi.getInstance().setRobotString(Definition.BOOT_APP_PACKAGE_NAME, to)
        val readBack = RobotSettingApi.getInstance().getRobotString(Definition.BOOT_APP_PACKAGE_NAME).orEmpty()
        return if (readBack == to) Result.Changed(from, to)
        else Result.Failed("wrote \"$to\" but read back \"$readBack\" (write refused?)")
    }

    /**
     * Runs [block] once RobotApi is connected.
     * @param context Android context
     * @param onDone receives the result on the main thread
     * @param block work that needs the connection
     */
    private fun withRobotApi(context: Context, onDone: (Result) -> Unit, block: () -> Result) {
        val main = Handler(Looper.getMainLooper())
        var finished = false
        /**
         * Delivers the result on the main thread.
         * @param result the result
         */
        fun finish(result: Result) {
            main.post {
                if (finished) return@post
                finished = true
                when (result) {
                    is Result.Failed -> Log.w(TAG, "default app: ${result.reason}")
                    else -> Log.i(TAG, "default app: $result")
                }
                onDone(result)
            }
        }
        /** Runs the block and delivers its result. */
        fun run() = finish(runCatching(block).getOrElse { Result.Failed("error: $it") })

        RobotApiConnection.connect(context, object : ApiListener {
            /** Runs the block once connected. */
            override fun handleApiConnected() {
                RobotApiConnection.removeListener(this)
                Thread(::run, "DefaultAppSetting").start()
            }
            /** Reports a failure. */
            override fun handleApiDisconnected() {}
            /** Reports a failure. */
            override fun handleApiDisabled() {
                RobotApiConnection.removeListener(this)
                finish(Result.Failed("RobotApi disabled (RoboGuard not in control?)"))
            }
        })
        main.postDelayed({ finish(Result.Failed("RobotApi not connected after $CONNECT_TIMEOUT_MS ms")) }, CONNECT_TIMEOUT_MS)
    }
}
