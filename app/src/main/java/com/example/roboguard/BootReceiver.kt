package com.example.roboguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val serviceIntent = Intent(context, RobotServerService::class.java)

            // Android 8+ benötigt startForegroundService
            context.startForegroundService(serviceIntent)
        }
    }
}