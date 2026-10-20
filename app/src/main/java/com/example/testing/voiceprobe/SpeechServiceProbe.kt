package com.example.testing.voiceprobe

import android.content.Context
import android.os.SystemClock
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.speech.SkillApi
import com.ainirobot.coreservice.client.speech.SkillCallback

/**
 * Test that logs every callback of RobotOS's speech service ([SkillCallback]) with a timestamp, and can switch multiple
 * mode and read the TTS play status. Speech payloads go through [ProbeLog.payload].
 * @param context used to connect to the speech service
 * @param log log of the test screen
 */
class SpeechServiceProbe(private val context: Context, private val log: ProbeLog) {

    /** Non-null while connected (or connecting). Also serves as the "is started" flag. */
    private var skillApi: SkillApi? = null

    /** Last value sent with setMultipleModeEnable. Assumes the service starts with it off. */
    private var multipleMode = false

    /** For throttling [SkillCallback.onVolumeChange]. */
    private var lastVolumeLogAt = 0L

    /** Logs every callback of the speech service. */
    private val callback = object : SkillCallback() {

        /** Partial ASR result while someone is still speaking. May contain speech content. */
        override fun onSpeechParResult(result: String?) = log.payload(TAG, "onSpeechParResult", result)

        /** Final ASR result of one utterance. May contain speech content. */
        override fun onQueryAsrResult(result: String?) = log.payload(TAG, "onQueryAsrResult", result)

        /** Logs the shape of a speech stream payload. */
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
         * Logs the request and returns null.
         * @param index index passed by the speech service
         */
        override fun onGetMultipleModeInfos(index: Int): String? {
            log.i(TAG, "onGetMultipleModeInfos($index) requested, answering null")
            return null
        }
    }

    /** Connects to the speech service and registers the callback; does nothing if already started. */
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
