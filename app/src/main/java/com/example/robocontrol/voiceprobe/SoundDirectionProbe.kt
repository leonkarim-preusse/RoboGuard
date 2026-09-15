package com.example.robocontrol.voiceprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.module.ModuleCallbackApi

/**
 * Probe 3: does RobotOS report the direction a voice came from, and for which speech?
 *
 * Background: the planned conversation detector fuses two weak signals, speaker change in
 * the audio and the bearing of the sound source (CLAUDE.md, "Conversation detection").
 * The robot turns toward whoever says the wake word, so its mic array computes a bearing.
 * The question is whether an app can read that bearing.
 *
 * The jar only shows where an angle *might* arrive, so this probe listens on all three:
 *  - [ModuleCallbackApi.onSendRequest]: RobotOS forwards requests such as
 *    `req_speech_wakeup` to the app; the angle may be in `reqParam`
 *  - the `com.ainirobot.intent.speech.WAKEUP` / `...SLEEP` broadcasts: [Definition] has
 *    an `EXTRA_UPLOAD_ANGLE = "angle"` constant that may belong to these
 *  - the `status_speaker` status ([Definition.STATUS_SPEAKER]), meaning unknown
 *
 * `RobotApi.wakeUp(reqId, angle)` is deliberately NOT called: it is a command (turn the
 * robot toward an angle), not a report.
 *
 * Suggested session:
 *  1. Say the wake word from the front, left, right and behind the robot. Check whether a
 *     matching angle is logged, and in which of the three places.
 *  2. Talk without the wake word from different sides. If nothing arrives, bearings exist only
 *     per wake-up, which is too rare to tell speakers apart in a running conversation.
 */
class SoundDirectionProbe(private val context: Context, private val log: ProbeLog) {

    private var started = false

    /**
     * Receives requests and hardware reports from RobotOS.
     * Registering it makes this app RobotOS's callback target while the probe screen is in the
     * foreground, so speech requests may reach the probe instead of the normal app behaviour.
     */
    private val moduleCallback = object : ModuleCallbackApi() {

        /**
         * A request forwarded by RobotOS, e.g. a wake-up or a recognised voice command.
         * [reqText] can be a transcript, so both strings go through [ProbeLog.payload].
         *
         * @return false = "not handled by this app". The intention is that RobotOS then keeps
         *         its default behaviour. Unverified: note in the log whether the robot still
         *         reacts normally while the probe runs.
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

    /** Logs every `status_speaker` update; the payload format is unknown. */
    private val speakerStatus = object : StatusListener() {
        override fun onStatusUpdate(type: String?, value: String?) = log.payload(TAG, "status $type", value)
    }

    /**
     * Logs the WAKEUP and SLEEP broadcasts with all their extras. The extra types are unknown,
     * which is why the untyped (deprecated) `Bundle.get` is used. Wake-up extras are expected
     * to hold an angle, not speech, so they are logged in full.
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras
            @Suppress("DEPRECATION")
            val described = extras?.keySet()?.joinToString { "$it=${extras.get(it)}" } ?: "no extras"
            log.i(TAG, "broadcast ${intent.action}: $described")
        }
    }

    /**
     * Starts all three listeners. Calling it again while started does nothing.
     *
     * Order matters for the SDK part: RobotApi only accepts setCallback and
     * registerStatusListener after `handleApiConnected` (see CLAUDE.md, init order).
     * The broadcast receiver needs no connection and is registered immediately.
     */
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

        RobotApi.getInstance().connectServer(context, object : ApiListener {
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
        runCatching { RobotApi.getInstance().disconnectApi() }
        log.i(TAG, "stopped")
    }

    private companion object {
        const val TAG = "direction"
    }
}
