package com.example.robocontrol.sensorcontrol

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.speech.SkillApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * The latest attempt to switch one sensor through one mechanism.
 *
 * @property sensor settings name, e.g. "Camera"
 * @property method which mechanism was used, e.g. "SDK RobotApi.updateRadarStatus"
 * @property enabled the state that was requested
 * @property detail return codes, read-back values or the reason for a failure
 */
data class SwitchReport(
    val sensor: String,
    val method: String,
    val enabled: Boolean,
    val outcome: Outcome,
    val detail: String? = null
) {
    enum class Outcome {
        /** The state was read back and matches the request. */
        CONFIRMED,

        /** The call was accepted, but there is no way (or no answer yet) to read the state back. */
        SENT,

        /** The call was rejected, threw, or the read-back state does not match. */
        FAILED,

        /** The SDK is not connected yet; the switch is applied automatically once it is. */
        PENDING,

        /** No switch exists for this sensor name. */
        UNSUPPORTED
    }
}

/**
 * Switches the robot's sensors on or off, through the OrionStar SDK and through plain Android APIs.
 *
 * | Sensor     | SDK                                              | Android                                   |
 * |------------|--------------------------------------------------|-------------------------------------------|
 * | LIDAR      | `RobotApi.updateRadarStatus` (after stopping navigation) | none (robot hardware, not Android) |
 * | Microphone | `SkillApi.setASREnabled` + `setRecognizable`     | `AudioManager.setMicrophoneMute`          |
 * | Camera     | `RobotApi.stopVision` / `startVision`            | `DevicePolicyManager.setCameraDisabled`   |
 *
 * None of these switches have been run on the robot yet (see CLAUDE.md, "Sensor on/off switches").
 * Every attempt is written to [reports], so what actually worked can be checked on the robot and
 * shown to the user instead of assumed.
 *
 * Known limits:
 *  - LIDAR off: the robot can no longer localize, navigate or avoid obstacles. Navigation is stopped first.
 *  - Microphone via the SDK stops speech recognition; the mic may still capture audio. Muting through
 *    Android may not apply to RobotOS's speech service if it bypasses the Android audio framework.
 *  - Camera via the SDK stops the vision service's detection; it may not release the camera.
 *    Disabling through Android only works once the app is an active device admin:
 *    `adb shell dpm set-active-admin com.example.roboguard/com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin`
 *    (allowed for a plain device admin because targetSdk is 28), and may not stop RobotOS if it
 *    reads the camera without the Android camera service.
 *
 * Needs the app in the foreground for the SDK calls (RobotOS serves the SDK to the foreground app only).
 */
internal class SensorSwitches(private val context: Context) {

    private val _reports = MutableStateFlow<Map<String, SwitchReport>>(emptyMap())

    /** Latest report per "sensor | method", e.g. "Camera | Android DevicePolicyManager.setCameraDisabled". */
    val reports: StateFlow<Map<String, SwitchReport>> = _reports.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devicePolicy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, RoboGuardDeviceAdmin::class.java)

    private val skillApi = SkillApi()

    /** For delayed read-backs; the SDK calls themselves are made on the caller's thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var skillConnected = false
    @Volatile private var skillConnectRequested = false
    @Volatile private var robotConnectRequested = false

    /** The most recent settings, re-applied when an SDK connection comes up. */
    @Volatile private var desired: Map<String, Boolean> = emptyMap()

    private val reqIds = AtomicInteger(REQ_ID_START)

    /**
     * Switches every sensor in [sensors] to its value: false = off, true = on.
     * Idempotent, so it is safe to call with unchanged settings.
     */
    @Synchronized
    fun apply(sensors: Map<String, Boolean>) {
        desired = sensors.toMap()
        for ((name, enabled) in sensors) {
            when (name.lowercase()) {
                LIDAR -> switchLidarSdk(name, enabled)
                MICROPHONE -> {
                    switchMicrophoneSdk(name, enabled)
                    switchMicrophoneAndroid(name, enabled)
                }
                CAMERA -> {
                    switchCameraSdk(name, enabled)
                    switchCameraAndroid(name, enabled)
                }
                else -> report(name, "none", enabled, SwitchReport.Outcome.UNSUPPORTED, "no switch implemented for this sensor")
            }
        }
    }

    // ---- LIDAR --------------------------------------------------------------------

    private fun switchLidarSdk(name: String, enabled: Boolean) {
        val method = "SDK RobotApi.updateRadarStatus"
        if (!robotApiReady()) {
            return report(name, method, enabled, SwitchReport.Outcome.PENDING, "RobotApi not connected yet")
        }
        val api = RobotApi.getInstance()
        // The robot cannot drive safely without LIDAR, so stop any navigation before switching it off.
        if (!enabled) runCatching { api.stopNavigation(reqIds.incrementAndGet()) }

        val code = runCatching {
            api.updateRadarStatus(reqIds.incrementAndGet(), enabled, resultListener(name, method, enabled))
        }.getOrElse {
            return report(name, method, enabled, SwitchReport.Outcome.FAILED, it.toString())
        }
        if (code < 0) report(name, method, enabled, SwitchReport.Outcome.FAILED, "request rejected, code $code")
        else report(name, method, enabled, SwitchReport.Outcome.SENT, "waiting for result")
    }

    // ---- Microphone ---------------------------------------------------------------

    private fun switchMicrophoneSdk(name: String, enabled: Boolean) {
        val method = "SDK SkillApi.setASREnabled + setRecognizable"
        if (!skillApiReady()) {
            return report(name, method, enabled, SwitchReport.Outcome.PENDING, "SkillApi not connected yet")
        }
        runCatching {
            skillApi.setASREnabled(enabled)
            skillApi.setRecognizable(enabled)
        }.onSuccess {
            // isRecognizable is the only read-back the SDK offers; there is none for setASREnabled.
            val recognizable = runCatching { skillApi.isRecognizable() }.getOrNull()
            val outcome = if (recognizable == enabled) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.SENT
            report(name, method, enabled, outcome, "isRecognizable=$recognizable")

            // On the robot, isRecognizable still read false right after switching back ON. Read it again later so
            // the report says whether re-enabling really took effect or was ignored.
            mainHandler.postDelayed({
                if (desired[name] != enabled) return@postDelayed // superseded by a newer request
                val later = runCatching { skillApi.isRecognizable() }.getOrNull()
                val laterOutcome = if (later == enabled) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.FAILED
                report(name, method, enabled, laterOutcome, "isRecognizable=$later after ${READBACK_DELAY_MS} ms")
            }, READBACK_DELAY_MS)
        }.onFailure {
            report(name, method, enabled, SwitchReport.Outcome.FAILED, it.toString())
        }
    }

    private fun switchMicrophoneAndroid(name: String, enabled: Boolean) {
        val method = "Android AudioManager.setMicrophoneMute"
        runCatching { audioManager.isMicrophoneMute = !enabled }
            .onSuccess {
                val muted = audioManager.isMicrophoneMute
                val outcome = if (muted == !enabled) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.FAILED
                report(name, method, enabled, outcome, "isMicrophoneMute=$muted")
            }
            .onFailure { report(name, method, enabled, SwitchReport.Outcome.FAILED, it.toString()) }
    }

    // ---- Camera -------------------------------------------------------------------

    private fun switchCameraSdk(name: String, enabled: Boolean) {
        val method = if (enabled) "SDK RobotApi.startVision" else "SDK RobotApi.stopVision"
        if (!robotApiReady()) {
            return report(name, method, enabled, SwitchReport.Outcome.PENDING, "RobotApi not connected yet")
        }
        val api = RobotApi.getInstance()
        val listener = resultListener(name, method, enabled)
        val code = runCatching {
            if (enabled) api.startVision(reqIds.incrementAndGet(), listener)
            else api.stopVision(reqIds.incrementAndGet(), listener)
        }.getOrElse {
            return report(name, method, enabled, SwitchReport.Outcome.FAILED, it.toString())
        }
        if (code < 0) report(name, method, enabled, SwitchReport.Outcome.FAILED, "request rejected, code $code")
        else report(name, method, enabled, SwitchReport.Outcome.SENT, "waiting for result")
    }

    private fun switchCameraAndroid(name: String, enabled: Boolean) {
        val method = "Android DevicePolicyManager.setCameraDisabled"
        if (!devicePolicy.isAdminActive(adminComponent)) {
            return report(
                name, method, enabled, SwitchReport.Outcome.FAILED,
                "app is not an active device admin; run: adb shell dpm set-active-admin ${adminComponent.flattenToString()}"
            )
        }
        runCatching { devicePolicy.setCameraDisabled(adminComponent, !enabled) }
            .onSuccess {
                // null = the combined state across all admins, i.e. what the system actually enforces.
                val disabled = devicePolicy.getCameraDisabled(null)
                val outcome = if (disabled == !enabled) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.FAILED
                report(name, method, enabled, outcome, "getCameraDisabled=$disabled")
            }
            .onFailure { report(name, method, enabled, SwitchReport.Outcome.FAILED, it.toString()) }
    }

    // ---- SDK connections ----------------------------------------------------------

    /**
     * True if RobotApi is connected. Otherwise starts connecting once (unless something else in the
     * app already did) and returns false; [desired] is re-applied when the connection comes up.
     */
    private fun robotApiReady(): Boolean {
        val api = RobotApi.getInstance()
        if (api.isApiConnectedService()) return true
        if (!robotConnectRequested) {
            robotConnectRequested = true
            api.connectServer(context, object : ApiListener {
                override fun handleApiConnected() {
                    Log.i(TAG, "RobotApi connected, applying sensor settings")
                    apply(desired)
                }

                override fun handleApiDisconnected() {
                    robotConnectRequested = false
                }

                override fun handleApiDisabled() {
                    robotConnectRequested = false
                    Log.w(TAG, "RobotApi disabled: app not in foreground or not authorized")
                }
            })
        }
        return false
    }

    /** Same as [robotApiReady], for the speech service. */
    private fun skillApiReady(): Boolean {
        if (skillConnected) return true
        if (!skillConnectRequested) {
            skillConnectRequested = true
            skillApi.connectApi(context, object : ApiListener {
                override fun handleApiConnected() {
                    skillConnected = true
                    Log.i(TAG, "SkillApi connected, applying sensor settings")
                    apply(desired)
                }

                override fun handleApiDisconnected() {
                    skillConnected = false
                    skillConnectRequested = false
                }

                override fun handleApiDisabled() {
                    skillConnected = false
                    skillConnectRequested = false
                    Log.w(TAG, "SkillApi disabled: app not in foreground or not authorized")
                }
            })
        }
        return false
    }

    /**
     * Turns the SDK's asynchronous answer into a CONFIRMED or FAILED report.
     * Only the 3-argument callbacks are overridden: the SDK calls both variants, so overriding
     * both would report twice (verified in the jar, see CLAUDE.md).
     */
    private fun resultListener(name: String, method: String, enabled: Boolean) = object : CommandListener() {
        override fun onResult(result: Int, message: String?, extraData: String?) {
            val outcome = if (result == Definition.RESULT_OK) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.FAILED
            report(name, method, enabled, outcome, "result=$result message=$message")
        }

        override fun onError(errorCode: Int, errorString: String?, extraData: String?) {
            report(name, method, enabled, SwitchReport.Outcome.FAILED, "error=$errorCode $errorString")
        }
    }

    private fun report(sensor: String, method: String, enabled: Boolean, outcome: SwitchReport.Outcome, detail: String?) {
        val entry = SwitchReport(sensor, method, enabled, outcome, detail)
        _reports.value = _reports.value + ("$sensor | $method" to entry)
        val line = "$sensor ${if (enabled) "ON" else "OFF"} via $method: $outcome${detail?.let { " ($it)" } ?: ""}"
        if (outcome == SwitchReport.Outcome.FAILED) Log.w(TAG, line) else Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "SensorSwitches"

        // Settings names, compared case-insensitively.
        const val LIDAR = "lidar"
        const val MICROPHONE = "microphone"
        const val CAMERA = "camera"

        /** Request ids for these commands, kept apart from the ranges other components use. */
        const val REQ_ID_START = 20_000

        /** Delay before the second isRecognizable read-back (the probe waits 3 s before reading reports). */
        const val READBACK_DELAY_MS = 2_000L
    }
}
