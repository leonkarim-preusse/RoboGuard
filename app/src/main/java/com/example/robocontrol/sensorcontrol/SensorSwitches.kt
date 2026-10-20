package com.example.robocontrol.sensorcontrol

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.example.robocontrol.system.RobotApiConnection
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.speech.SkillApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * The latest attempt to switch one sensor through one mechanism.
 * @property sensor sensor name, e.g. "Camera"
 * @property method mechanism used, e.g. "SDK RobotApi.updateRadarStatus"
 * @property enabled requested state
 * @property outcome how far the switch is known to have worked
 * @property detail return codes, read-back values or the reason for a failure
 * @property atMs time of the report (elapsedRealtime)
 */
data class SwitchReport(
    val sensor: String,
    val method: String,
    val enabled: Boolean,
    val outcome: Outcome,
    val detail: String? = null,
    val atMs: Long = SystemClock.elapsedRealtime()
) {
    /** How far a switch is known to have worked. */
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
        UNSUPPORTED;

        /** True once nothing more is coming for this switch (SENT and PENDING may still change). */
        val isFinal: Boolean get() = this == CONFIRMED || this == FAILED || this == UNSUPPORTED
    }
}

/**
 * Result of applying one save to the hardware, over all its switches (SDK and Android).
 * @property outcome all, some or none of the switches confirmed
 * @property notConfirmed one line per switch that was not confirmed
 */
data class ApplyResult(val outcome: Outcome, val notConfirmed: List<String>) {
    enum class Outcome {
        /** Every switch of the save was read back as requested. */
        ALL_CONFIRMED,

        /** Some switches confirmed, others did not (typically the SDK refused because RoboGuard had no control). */
        PARTIAL,

        /** Not a single switch confirmed. */
        NONE
    }
}

/**
 * Switches the sensors on or off through the OrionStar SDK and through Android:
 * LIDAR via `RobotApi.updateRadarStatus` (navigation is stopped first); microphone via
 * `SkillApi.setASREnabled` + `setRecognizable` and `AudioManager.setMicrophoneMute`; camera via
 * `RobotApi.stopVision` / `startVision` and `DevicePolicyManager.setCameraDisabled` (requires the device admin).
 * Every attempt is recorded in [reports].
 * @param context used for the system services and the SDK connections
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

    /** Switches the LIDAR through the SDK (stops a drive first when switching off). */
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

    /** Switches RobotOS speech recognition through the SDK and reads `isRecognizable` back after 2 s. */
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

    /** Mutes or unmutes the microphone for Android apps and reads the state back. */
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

    /** Starts or stops RobotOS's vision through the SDK. */
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

    /** Disables or enables the camera through the device policy (needs the device admin) and reads it back. */
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
        if (RobotApi.getInstance().isApiConnectedService()) return true
        if (!robotConnectRequested) {
            robotConnectRequested = true
            // Through the app-wide connection: a second connectServer broke pose/isActive calls elsewhere in the app.
            RobotApiConnection.connect(context, object : ApiListener {
                override fun handleApiConnected() {
                    Log.i(TAG, "RobotApi connected, applying sensor settings")
                    RobotApiConnection.removeListener(this)
                    robotConnectRequested = false
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
     * Turns the SDK's answer into a CONFIRMED or FAILED report.
     * @param name sensor name
     * @param method mechanism used
     * @param enabled requested state
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

    /**
     * Waits until every switch of [sensors] has a final report made after [sinceMs], at most [timeoutMs].
     * Switches without a final report at the deadline count as not confirmed.
     * @param sensors sensor name → requested state
     * @param sinceMs time taken just before [apply] (elapsedRealtime)
     * @param timeoutMs maximum waiting time
     * @return the combined result
     */
    suspend fun awaitResult(sensors: Map<String, Boolean>, sinceMs: Long, timeoutMs: Long): ApplyResult {
        // One expected switch per sensor and kind ("SDK" / "Android"), as apply() makes them.
        val expected = sensors.flatMap { (name, enabled) -> kindsFor(name).map { kind -> Triple(name, enabled, kind) } }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val latest = expected.map { (name, enabled, kind) ->
                _reports.value.values
                    .filter { it.sensor == name && it.enabled == enabled && it.method.startsWith(kind) && it.atMs >= sinceMs }
                    .maxByOrNull { it.atMs }
            }
            if (latest.all { it != null && it.outcome.isFinal } || SystemClock.elapsedRealtime() >= deadline) {
                val notConfirmed = expected.zip(latest)
                    .filter { (_, r) -> r?.outcome != SwitchReport.Outcome.CONFIRMED }
                    .map { (e, r) ->
                        "${e.first} ${if (e.second) "ON" else "OFF"} via ${r?.method ?: e.third}: " +
                            (r?.let { "${it.outcome}${it.detail?.let { d -> " ($d)" } ?: ""}" } ?: "no answer")
                    }
                val outcome = when {
                    notConfirmed.isEmpty() -> ApplyResult.Outcome.ALL_CONFIRMED
                    notConfirmed.size == expected.size -> ApplyResult.Outcome.NONE
                    else -> ApplyResult.Outcome.PARTIAL
                }
                return ApplyResult(outcome, notConfirmed)
            }
            delay(AWAIT_POLL_MS)
        }
    }

    /** Which kinds of switch [apply] makes for a sensor: LIDAR only the SDK, microphone and camera both. */
    private fun kindsFor(name: String): List<String> = when (name.lowercase()) {
        LIDAR -> listOf(SDK)
        MICROPHONE, CAMERA -> listOf(SDK, ANDROID)
        else -> emptyList()
    }

    /** Stores the outcome of one switch in [reports] and logs it. */
    private fun report(sensor: String, method: String, enabled: Boolean, outcome: SwitchReport.Outcome, detail: String?) {
        val entry = SwitchReport(sensor, method, enabled, outcome, detail)
        _reports.update { it + ("$sensor | $method" to entry) }
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

        /** How often [awaitResult] looks at the reports. */
        const val AWAIT_POLL_MS = 100L

        /** How the `method` names of the switch functions start, per kind of switch. */
        const val SDK = "SDK"
        const val ANDROID = "Android"
    }
}
