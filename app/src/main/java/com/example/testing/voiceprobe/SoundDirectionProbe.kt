package com.example.testing.voiceprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.example.robocontrol.system.RobotApiConnection
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.module.ModuleCallbackApi

/**
 * Test that checks whether RobotOS reports the direction of a voice. Listens to forwarded requests
 * ([ModuleCallbackApi.onSendRequest]), the wake-up and sleep broadcasts and the `status_speaker` status, and logs what
 * arrives.
 * @param context used to connect and register the receivers
 * @param log log of the test screen
 */
class SoundDirectionProbe(private val context: Context, private val log: ProbeLog) {

    private var started = false

    /** Receives requests and reports forwarded by RobotOS. */
    private val moduleCallback = object : ModuleCallbackApi() {

        /**
         * Logs a request forwarded by RobotOS.
         * @return false, so that RobotOS keeps its default handling
         */
        override fun onSendRequest(reqId: Int, reqType: String?, reqText: String?, reqParam: String?): Boolean {
            log.i(TAG, "onSendRequest id=$reqId type=$reqType")
            log.payload(TAG, "  reqText", reqText)
            log.payload(TAG, "  reqParam", reqParam)
            return false
        }

        /** Hardware events (sensors, chassis, possibly mic array). Contains no speech content. */
        override fun onHWReport(function: Int, type: String?, message: String?) =
            log.i(TAG, "onHWReport function=$function type=$type message=$message")

        /** This app lost SDK control, e.g. another app came to the foreground. */
        override fun onSuspend() = log.i(TAG, "onSuspend (app lost SDK control)")

        /** SDK control is back. */
        override fun onRecovery() = log.i(TAG, "onRecovery")
    }

    /** Logs every `status_speaker` update. */
    private val speakerStatus = object : StatusListener() {
        override fun onStatusUpdate(type: String?, value: String?) = log.payload(TAG, "status $type", value)
    }

    /** Logs the wake-up and sleep broadcasts with all their extras. */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras
            @Suppress("DEPRECATION")
            val described = extras?.keySet()?.joinToString { "$it=${extras.get(it)}" } ?: "no extras"
            log.i(TAG, "broadcast ${intent.action}: $described")
        }
    }

    /** Starts all listeners; does nothing if already started. */
    fun start() {
        if (started) return
        started = true
        log.section("Sound direction")

        // Exported, because RobotOS's speech service is a different app sending the broadcast.
        ContextCompat.registerReceiver(
            context,
            wakeReceiver,
            IntentFilter().apply {
                addAction(Definition.INTENT_SPEECH_WAKEUP)
                addAction(Definition.INTENT_SPEECH_SLEEP)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        RobotApiConnection.connect(context, object : ApiListener {
            override fun handleApiConnected() {
                log.i(TAG, "RobotApi connected, setting module callback and status listener")
                RobotApi.getInstance().setCallback(moduleCallback)
                RobotApi.getInstance().registerStatusListener(Definition.STATUS_SPEAKER, speakerStatus)
            }

            override fun handleApiDisconnected() = log.i(TAG, "RobotApi disconnected")
            override fun handleApiDisabled() = log.i(TAG, "RobotApi disabled")
        })
    }

    /**
     * Removes all listeners and disconnects from RobotApi. Safe to call when not started.
     * Each step is guarded separately, so one failure does not leave the others registered.
     */
    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(wakeReceiver) }
        runCatching { RobotApi.getInstance().unregisterStatusListener(speakerStatus) }
        // No disconnectApi(): the RobotApi connection is shared by the whole app (RobotApiConnection).
        log.i(TAG, "stopped")
    }

    private companion object {
        const val TAG = "direction"
    }
}
