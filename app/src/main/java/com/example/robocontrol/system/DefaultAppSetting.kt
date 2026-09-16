package com.example.robocontrol.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.robotsetting.RobotSettingApi

/**
 * Makes RoboGuard the robot's default (boot) app via the RobotOS setting `boot_app_package_name`.
 *
 * Why: RobotOS only gives an app SDK control (driving, speech, sensors) when it is launched from the home launcher,
 * and takes it away when the app leaves the foreground. CoreService's `PermissionManager` (read from CoreService.apk,
 * see CLAUDE.md) keeps a white list of apps that get control whenever they come to the foreground, however they got
 * there (recent apps, a popup started by RobotServerService, ...). That list contains the package named in
 * `boot_app_package_name`, and CoreService re-reads the setting as soon as it changes.
 *
 * Side effect: the robot also starts RoboGuard after booting. The value found before the first change is kept in
 * SharedPreferences, so [restorePrevious] can undo it.
 *
 * Every call only writes when the setting differs; everything is logged under [TAG].
 */
object DefaultAppSetting {

    const val TAG = "DefaultApp"
    private const val PREFS = "robocontrol_default_app"
    private const val KEY_PREVIOUS = "previous_boot_app"
    private const val KEY_HAS_PREVIOUS = "has_previous_boot_app"
    private const val CONNECT_TIMEOUT_MS = 5_000L

    /** Outcome of [ensureRoboGuardIsDefault] / [restorePrevious]. */
    sealed interface Result {
        data class AlreadySet(val value: String) : Result
        data class Changed(val from: String, val to: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** Sets `boot_app_package_name` to this app's package if it is not already. [onDone] runs on the main thread. */
    fun ensureRoboGuardIsDefault(context: Context, onDone: (Result) -> Unit = {}) {
        val app = context.applicationContext
        val target = app.packageName
        withRobotApi(app, onDone) {
            val current = RobotSettingApi.getInstance().getRobotString(Definition.BOOT_APP_PACKAGE_NAME).orEmpty()
            Log.i(TAG, "boot_app_package_name is \"$current\"")
            if (current == target) return@withRobotApi Result.AlreadySet(current)

            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_PREVIOUS, false)) {
                // Keep only the value from before RoboGuard's first change, so a restore goes back to the original.
                prefs.edit().putString(KEY_PREVIOUS, current).putBoolean(KEY_HAS_PREVIOUS, true).commit()
            }
            write(current, target)
        }
    }

    /** Puts back the default app that was set before RoboGuard's first change (possibly none). */
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

    /** Writes [to] and reads it back: the SDK call returns nothing and swallows RemoteExceptions. */
    private fun write(from: String, to: String): Result {
        RobotSettingApi.getInstance().setRobotString(Definition.BOOT_APP_PACKAGE_NAME, to)
        val readBack = RobotSettingApi.getInstance().getRobotString(Definition.BOOT_APP_PACKAGE_NAME).orEmpty()
        return if (readBack == to) Result.Changed(from, to)
        else Result.Failed("wrote \"$to\" but read back \"$readBack\" (write refused?)")
    }

    /**
     * Runs [block] once RobotApi is connected (RobotSettingApi is served through RobotApi's connection), connecting
     * first if nothing in the app has yet. Exceptions and a connection that does not come up end in [Result.Failed].
     */
    private fun withRobotApi(context: Context, onDone: (Result) -> Unit, block: () -> Result) {
        val main = Handler(Looper.getMainLooper())
        var finished = false
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
        fun run() = finish(runCatching(block).getOrElse { Result.Failed("error: $it") })

        val api = RobotApi.getInstance()
        if (api.isApiConnectedService()) {
            Thread(::run, "DefaultAppSetting").start()
            return
        }
        api.connectServer(context, object : ApiListener {
            override fun handleApiConnected() { Thread(::run, "DefaultAppSetting").start() }
            override fun handleApiDisconnected() {}
            override fun handleApiDisabled() = finish(Result.Failed("RobotApi disabled (RoboGuard not in control?)"))
        })
        main.postDelayed({ finish(Result.Failed("RobotApi not connected after $CONNECT_TIMEOUT_MS ms")) }, CONNECT_TIMEOUT_MS)
    }
}
