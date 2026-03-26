package com.example.roboguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Listens for system boot events to ensure the RoboGuard server starts automatically.
 * This ensures that the robot's privacy protection is active as soon as the device is powered on.
 */
class BootReceiver : BroadcastReceiver() {
    /**
     * Called when the BroadcastReceiver is receiving an Intent broadcast.
     * Specifically looks for [Intent.ACTION_BOOT_COMPLETED].
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val serviceIntent = Intent(context, RobotServerService::class.java)

            // Android 8.0 (API 26) and above requires startForegroundService for background services
            // that want to promote themselves to the foreground.
            context.startForegroundService(serviceIntent)
        }
    }
}
