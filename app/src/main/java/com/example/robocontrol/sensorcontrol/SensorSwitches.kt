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
        CONFIRMED,

        SENT,

        FAILED,

        PENDING,

        UNSUPPORTED;

        val isFinal: Boolean get() = this == CONFIRMED || this == FAILED || this == UNSUPPORTED
    }
}

/**
 * Result of applying one save to the hardware, over all its switches (SDK and Android).
 * @property outcome all, some or none of the switches confirmed
 * @property notConfirmed one line per switch that was not confirmed
 * @property ignored switches that were not confirmed but do not count for [outcome] (re-enabling speech recognition)
 */
data class ApplyResult(val outcome: Outcome, val notConfirmed: List<String>, val ignored: List<String> = emptyList()) {
    /** Outcome of one switch. */
    enum class Outcome {
        ALL_CONFIRMED,

        PARTIAL,

        NONE
    }
}

/**
 * Switches the sensors on or off through the OrionStar SDK and Android, and records every attempt in [reports].
 * @param context used for the system services and the SDK connections
 */
internal class SensorSwitches(private val context: Context) {
    private val _reports = MutableStateFlow<Map<String, SwitchReport>>(emptyMap())

    val reports: StateFlow<Map<String, SwitchReport>> = _reports.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devicePolicy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, RoboGuardDeviceAdmin::class.java)

    private val skillApi = SkillApi()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var skillConnected = false
    @Volatile private var skillConnectRequested = false
    @Volatile private var robotConnectRequested = false

    @Volatile private var desired: Map<String, Boolean> = emptyMap()

    private val reqIds = AtomicInteger(REQ_ID_START)

    /**
     * Switches every sensor in [sensors]; false = off, true = on.
     * @param sensors requested states
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

    /**
     * Switches the LIDAR through the SDK (stops a drive first when switching off).
     * @param name sensor name
     * @param enabled new state
     */
    private fun switchLidarSdk(name: String, enabled: Boolean) {
        val method = "SDK RobotApi.updateRadarStatus"
        if (!robotApiReady()) {
            return report(name, method, enabled, SwitchReport.Outcome.PENDING, "RobotApi not connected yet")
        }
        val api = RobotApi.getInstance()
        if (!enabled) runCatching { api.stopNavigation(reqIds.incrementAndGet()) }

        val code = runCatching {
            api.updateRadarStatus(reqIds.incrementAndGet(), enabled, resultListener(name, method, enabled))
        }.getOrElse {
            return report(name, method, enabled, SwitchReport.Outcome.FAILED, it.toString())
        }
        if (code < 0) report(name, method, enabled, SwitchReport.Outcome.FAILED, "request rejected, code $code")
        else report(name, method, enabled, SwitchReport.Outcome.SENT, "waiting for result")
    }

    /**
     * Switches RobotOS speech recognition through the SDK and reads `isRecognizable` back after 2 s.
     * @param name sensor name
     * @param enabled new state
     */
    private fun switchMicrophoneSdk(name: String, enabled: Boolean) {
        val method = "SDK SkillApi.setASREnabled + setRecognizable"
        if (!skillApiReady()) {
            return report(name, method, enabled, SwitchReport.Outcome.PENDING, "SkillApi not connected yet")
        }
        runCatching {
            skillApi.setASREnabled(enabled)
            skillApi.setRecognizable(enabled)
        }.onSuccess {
            val recognizable = runCatching { skillApi.isRecognizable() }.getOrNull()
            val outcome = if (recognizable == enabled) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.SENT
            report(name, method, enabled, outcome, "isRecognizable=$recognizable")

            // isRecognizable may still read false right after switching on; check again later.
            mainHandler.postDelayed({
                if (desired[name] != enabled) return@postDelayed
                val later = runCatching { skillApi.isRecognizable() }.getOrNull()
                val laterOutcome = if (later == enabled) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.FAILED
                report(name, method, enabled, laterOutcome, "isRecognizable=$later after ${READBACK_DELAY_MS} ms")
            }, READBACK_DELAY_MS)
        }.onFailure {
            report(name, method, enabled, SwitchReport.Outcome.FAILED, it.toString())
        }
    }

    /**
     * Mutes or unmutes the microphone for Android apps and reads the state back.
     * @param name sensor name
     * @param enabled new state
     */
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

    /**
     * Starts or stops RobotOS's vision through the SDK.
     * @param name sensor name
     * @param enabled new state
     */
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

    /**
     * Disables or enables the camera through the device policy (needs the device admin) and reads it back.
     * @param name sensor name
     * @param enabled new state
     */
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

    /**
     * True if RobotApi is connected. Otherwise starts connecting once (unless something else in the
     * app already did) and returns false; [desired] is re-applied when the connection comes up.
     * @return true if connected
     */
    private fun robotApiReady(): Boolean {
        if (RobotApi.getInstance().isApiConnectedService()) return true
        if (!robotConnectRequested) {
            robotConnectRequested = true
            // Through the app-wide connection; a second connectServer breaks other SDK calls.
            RobotApiConnection.connect(context, object : ApiListener {
                /** Applies the requested states once connected. */
                override fun handleApiConnected() {
                    Log.i(TAG, "RobotApi connected, applying sensor settings")
                    RobotApiConnection.removeListener(this)
                    robotConnectRequested = false
                    apply(desired)
                }

                /** Logs the disconnect. */
                override fun handleApiDisconnected() {
                    robotConnectRequested = false
                }

                /** Logs that the SDK is unavailable. */
                override fun handleApiDisabled() {
                    robotConnectRequested = false
                    Log.w(TAG, "RobotApi disabled: app not in foreground or not authorized")
                }
            })
        }
        return false
    }

    /**
     * Same as [robotApiReady], for the speech service.
     * @return true if connected
     */
    private fun skillApiReady(): Boolean {
        if (skillConnected) return true
        if (!skillConnectRequested) {
            skillConnectRequested = true
            skillApi.connectApi(context, object : ApiListener {
                /** Applies the requested states once connected. */
                override fun handleApiConnected() {
                    skillConnected = true
                    Log.i(TAG, "SkillApi connected, applying sensor settings")
                    apply(desired)
                }

                /** Logs the disconnect. */
                override fun handleApiDisconnected() {
                    skillConnected = false
                    skillConnectRequested = false
                }

                /** Logs that the speech service is unavailable. */
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
     * Creates a listener that turns the SDK's answer into a report.
     * @param name sensor name
     * @param method mechanism used
     * @param enabled requested state
     * @return the listener
     */
    private fun resultListener(name: String, method: String, enabled: Boolean) = object : CommandListener() {
        /**
         * Reports CONFIRMED or FAILED.
         * @param result result code
         * @param message answer
         * @param extraData extra data
         */
        override fun onResult(result: Int, message: String?, extraData: String?) {
            val outcome = if (result == Definition.RESULT_OK) SwitchReport.Outcome.CONFIRMED else SwitchReport.Outcome.FAILED
            report(name, method, enabled, outcome, "result=$result message=$message")
        }

        /**
         * Reports FAILED.
         * @param errorCode error code
         * @param errorString error text
         * @param extraData extra data
         */
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
        val expected = sensors.flatMap { (name, enabled) -> kindsFor(name).map { kind -> Triple(name, enabled, kind) } }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val latest = expected.map { (name, enabled, kind) ->
                _reports.value.values
                    .filter { it.sensor == name && it.enabled == enabled && it.method.startsWith(kind) && it.atMs >= sinceMs }
                    .maxByOrNull { it.atMs }
            }
            if (latest.all { it != null && it.outcome.isFinal } || SystemClock.elapsedRealtime() >= deadline) {
                /**
                 * Describes one expected switch for the log.
                 * @param e sensor, state and kind
                 * @param r its report, or null
                 * @return the text
                 */
                fun describe(e: Triple<String, Boolean, String>, r: SwitchReport?) =
                    "${e.first} ${if (e.second) "ON" else "OFF"} via ${r?.method ?: e.third}: " +
                        (r?.let { "${it.outcome}${it.detail?.let { d -> " ($d)" } ?: ""}" } ?: "no answer")
                val (ignoredPairs, countedPairs) = expected.zip(latest).partition { (e, _) -> isSpeechReEnable(e) }
                val notConfirmed = countedPairs
                    .filter { (_, r) -> r?.outcome != SwitchReport.Outcome.CONFIRMED }
                    .map { (e, r) -> describe(e, r) }
                val ignored = ignoredPairs
                    .filter { (_, r) -> r?.outcome != SwitchReport.Outcome.CONFIRMED }
                    .map { (e, r) -> describe(e, r) }
                ignored.forEach { Log.i(TAG, "not counted for the save result: $it") }
                val outcome = when {
                    notConfirmed.isEmpty() -> ApplyResult.Outcome.ALL_CONFIRMED
                    notConfirmed.size == countedPairs.size -> ApplyResult.Outcome.NONE
                    else -> ApplyResult.Outcome.PARTIAL
                }
                return ApplyResult(outcome, notConfirmed, ignored)
            }
            delay(AWAIT_POLL_MS)
        }
    }

    /**
     * Whether a switch is the SDK part of switching the microphone on, which does not count for the result.
     * @param switch sensor, state and kind
     * @return true for that switch
     */
    private fun isSpeechReEnable(switch: Triple<String, Boolean, String>): Boolean =
        switch.first.lowercase() == MICROPHONE && switch.second && switch.third == SDK

    /**
     * Which kinds of switch [apply] makes for a sensor: LIDAR only the SDK, microphone and camera both.
     * @param name sensor name
     * @return the kinds
     */
    private fun kindsFor(name: String): List<String> = when (name.lowercase()) {
        LIDAR -> listOf(SDK)
        MICROPHONE, CAMERA -> listOf(SDK, ANDROID)
        else -> emptyList()
    }

    /**
     * Stores the outcome of one switch in [reports] and logs it.
     * @param sensor sensor name
     * @param method kind of switch
     * @param enabled requested state
     * @param outcome outcome
     * @param detail detail text, or null
     */
    private fun report(sensor: String, method: String, enabled: Boolean, outcome: SwitchReport.Outcome, detail: String?) {
        val entry = SwitchReport(sensor, method, enabled, outcome, detail)
        _reports.update { it + ("$sensor | $method" to entry) }
        val line = "$sensor ${if (enabled) "ON" else "OFF"} via $method: $outcome${detail?.let { " ($it)" } ?: ""}"
        if (outcome == SwitchReport.Outcome.FAILED) Log.w(TAG, line) else Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "SensorSwitches"

        const val LIDAR = "lidar"
        const val MICROPHONE = "microphone"
        const val CAMERA = "camera"

        const val REQ_ID_START = 20_000

        const val READBACK_DELAY_MS = 2_000L

        const val AWAIT_POLL_MS = 100L

        const val SDK = "SDK"
        const val ANDROID = "Android"
    }
}
