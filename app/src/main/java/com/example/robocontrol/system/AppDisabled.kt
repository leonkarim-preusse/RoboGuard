package com.example.robocontrol.system

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.robocontrol.conversation.ConversationMonitor
import com.example.robocontrol.movement.NavigationHub
import com.example.robocontrol.vision.CalendarMonitor

/**
 * Debug switch "Disable RoboGuard". While disabled, RoboGuard is not the default app, does not start at boot and its
 * service is not running. Saved in SharedPreferences.
 */
object AppDisabled {
    private const val TAG = "AppDisabled"
    private const val PREFS = "robocontrol_app_disabled"
    private const val KEY = "disabled"
    private const val SERVICE = "com.example.roboguard.RobotServerService"

    /**
     * Whether RoboGuard was switched off with the debug switch.
     * @param context Android context
     * @return true if disabled
     */
    fun isDisabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /**
     * Switches RoboGuard off or back on.
     * @param context Android context
     * @param disabled true to switch it off
     */
    fun setDisabled(context: Context, disabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, disabled).commit()
        val service = Intent().setClassName(app, SERVICE)
        if (disabled) {
            Log.i(TAG, "RoboGuard disabled: no autostart, no service, camera and microphone released")
            DefaultAppSetting.restorePrevious(app) { Log.i(TAG, "default app: $it") }
            ConversationMonitor.stop()
            CalendarMonitor.stop()
            NavigationHub.stop()
            app.stopService(service)
        } else {
            Log.i(TAG, "RoboGuard enabled again")
            DefaultAppSetting.ensureRoboGuardIsDefault(app) { Log.i(TAG, "default app: $it") }
            app.startForegroundService(service)
        }
    }
}
