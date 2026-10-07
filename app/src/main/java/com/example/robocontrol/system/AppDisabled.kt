package com.example.robocontrol.system

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.robocontrol.conversation.ConversationMonitor
import com.example.robocontrol.movement.NavigationHub
import com.example.robocontrol.vision.CalendarMonitor

/**
 * Debug switch "Disable RoboGuard" (Navigation and Map, Show debug).
 *
 * While disabled, RoboGuard does nothing by itself: it is not the robot's default app and does not start after booting,
 * its service (server, conversation detection, object detection, shared navigation) is not running, and so nothing of it
 * holds the camera stream or the microphone. The app can still be opened by hand to switch it back on; screens the person
 * opens themselves (the test screens, "Teach owner's voice") still work.
 *
 * The state is kept in SharedPreferences, so it survives a reboot.
 */
object AppDisabled {

    private const val TAG = "AppDisabled"
    private const val PREFS = "robocontrol_app_disabled"
    private const val KEY = "disabled"
    private const val SERVICE = "com.example.roboguard.RobotServerService"

    fun isDisabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /**
     * Switches RoboGuard off or back on. Off: gives the default-app setting back, stops the service and both monitors.
     * On: makes RoboGuard the default app again and starts the service. The caller closes or rebuilds its screen.
     */
    fun setDisabled(context: Context, disabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, disabled).commit()
        val service = Intent().setClassName(app, SERVICE)
        if (disabled) {
            Log.i(TAG, "RoboGuard disabled: no autostart, no service, camera and microphone released")
            DefaultAppSetting.restorePrevious(app) { Log.i(TAG, "default app: $it") }
            // Stopped here as well: the service only ends once nothing is bound to it any more.
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
