package com.example.robocontrol.voiceprobe

import android.content.Context
import android.os.SystemClock
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.speech.SkillApi
import com.ainirobot.coreservice.client.speech.SkillCallback

/**
 * Probe 2: what does RobotOS's speech service tell an app, without the app touching the mic?
 *
 * Background: if [MicAccessProbe] shows the microphone is held by RobotOS, these callbacks
 * are the only audio-related signals left. The jar shows their signatures but not their
 * behaviour, so this probe connects to [SkillApi], registers a [SkillCallback] and logs
 * every callback with a timestamp.
 *
 * Questions it answers (see CLAUDE.md, "Voice / people APIs in the jar"):
 *  - Does `onSpeechStreamData` carry audio (long, base64-like strings) or text?
 *  - What does `onVadMuteTime` report, and is it a usable "someone is speaking" signal?
 *  - Does `onVolumeChange` follow the loudness in the room, or only while ASR is active?
 *  - What changes when multiple mode is switched on, and when does the service call
 *    `onGetMultipleModeInfos`?
 *  - Does `getTtsPlayStatus` change while the robot talks? A detector needs that to ignore
 *    the robot's own voice.
 *
 * Suggested session, each step with multiple mode off and then on:
 *  1. one person talks to the robot
 *  2. two people take turns
 *  3. two people talk over each other
 * Then compare which callbacks and payload shapes differ between the steps.
 *
 * Speech payloads can contain transcripts, so they go through [ProbeLog.payload], which
 * keeps content out of the log file.
 */
class SpeechServiceProbe(private val context: Context, private val log: ProbeLog) {

    /** Non-null while connected (or connecting). Also serves as the "is started" flag. */
    private var skillApi: SkillApi? = null

    /** Last value sent with setMultipleModeEnable. Assumes the service starts with it off. */
    private var multipleMode = false

    /** For throttling [SkillCallback.onVolumeChange]. */
    private var lastVolumeLogAt = 0L

    /**
     * Logs every callback the speech service delivers. Binder calls arrive on background
     * threads; [ProbeLog] is thread-safe, so no hand-off is needed.
     *
     * Parameter names are guesses: the jar keeps types but not names for these methods.
     */
    private val callback = object : SkillCallback() {

        /** Partial ASR result while someone is still speaking. May contain speech content. */
        override fun onSpeechParResult(result: String?) = log.payload(TAG, "onSpeechParResult", result)

        /** Final ASR result of one utterance. May contain speech content. */
        override fun onQueryAsrResult(result: String?) = log.payload(TAG, "onQueryAsrResult", result)

        /** Unknown: audio or text stream. The logged length and shape tell which. */
        override fun onSpeechStreamData(data: String?) = log.payload(TAG, "onSpeechStreamData", data)

        /** Recognition session started (presumably after the wake word). */
        override fun onStart() = log.i(TAG, "onStart")

        /** Recognition session ended. */
        override fun onStop() = log.i(TAG, "onStop")

        /** A query finished; the meaning of [status] is undocumented. */
        override fun onQueryEnded(status: Int) = log.i(TAG, "onQueryEnded status=$status")

        /**
         * Voice activity detection timing. Probably how long it has been silent (ms);
         * the log should show whether it resets when a person starts speaking.
         */
        override fun onVadMuteTime(time: Int) = log.i(TAG, "onVadMuteTime $time")

        /**
         * Input loudness. It can fire many times per second, so only one value every
         * [VOLUME_LOG_INTERVAL_MS] is logged. That is enough to see whether it follows speech.
         */
        override fun onVolumeChange(volume: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVolumeLogAt < VOLUME_LOG_INTERVAL_MS) return
            lastVolumeLogAt = now
            log.i(TAG, "onVolumeChange $volume")
        }

        /** Errors from the speech service, e.g. network ASR failures. */
        override fun onError(sid: String?, code: Int, message: String?) =
            log.i(TAG, "onError sid=$sid code=$code message=$message")

        /**
         * Called by the service in multiple mode, apparently to ask the app for information.
         * The expected answer format is unknown. Returning null is the least invasive choice;
         * the log shows whether and when it is asked, which is what this probe needs.
         */
        override fun onGetMultipleModeInfos(index: Int): String? {
            log.i(TAG, "onGetMultipleModeInfos($index) requested, answering null")
            return null
        }
    }

    /**
     * Connects to the speech service and registers the callback once the connection is up.
     * Calling it again while started does nothing.
     *
     * If "disabled" is logged instead of "connected", the app is not allowed to use the SDK.
     * Usually that means the probe screen is not in the foreground, or the OrionStar
     * permission is missing from the manifest.
     */
    fun start() {
        if (skillApi != null) return
        log.section("Speech service")
        val api = SkillApi()
        skillApi = api
        api.connectApi(context, object : ApiListener {
            override fun handleApiConnected() {
                log.i(TAG, "connected, registering callback")
                api.registerCallBack(callback)
                logTtsStatus()
            }

            override fun handleApiDisconnected() = log.i(TAG, "disconnected")
            override fun handleApiDisabled() = log.i(TAG, "disabled (app not authorized or not in foreground?)")
        })
    }

    /**
     * Switches the undocumented multiple mode on or off and logs the new state.
     * Watch the following callbacks for differences after each switch.
     */
    fun toggleMultipleMode() {
        val api = skillApi ?: return log.i(TAG, "start the speech probe first")
        multipleMode = !multipleMode
        runCatching { api.setMultipleModeEnable(multipleMode) }
            .onSuccess { log.i(TAG, "setMultipleModeEnable($multipleMode) sent") }
            .onFailure { log.i(TAG, "setMultipleModeEnable($multipleMode) failed: $it") }
    }

    /**
     * Logs the current TTS play status. Press it once while the robot is silent and once
     * while it is speaking, to learn which values mean "robot is talking".
     */
    fun logTtsStatus() {
        val api = skillApi ?: return log.i(TAG, "start the speech probe first")
        log.i(TAG, "getTtsPlayStatus=" + runCatching { api.getTtsPlayStatus().toString() }.getOrElse { "error $it" })
    }

    /** Unregisters the callback and disconnects. Safe to call when not started. */
    fun stop() {
        val api = skillApi ?: return
        runCatching { api.unregisterCallBack(callback) }
        runCatching { api.disconnectApi() }
        skillApi = null
        log.i(TAG, "stopped")
    }

    private companion object {
        const val TAG = "speech"
        const val VOLUME_LOG_INTERVAL_MS = 250L
    }
}
