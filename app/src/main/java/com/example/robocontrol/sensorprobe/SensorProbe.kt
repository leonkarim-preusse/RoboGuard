package com.example.robocontrol.sensorprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin
import com.example.robocontrol.sensorcontrol.SensorChangeListener
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.robocontrol.vision.CameraSnapshot
import com.example.robocontrol.vision.SnapshotFailure
import com.example.robocontrol.vision.SnapshotResult
import com.example.robocontrol.voiceprobe.ProbeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Hardware test for sensor switching: does `Sensors.update()` really switch LIDAR, microphone and camera
 * on the robot?
 *
 * Every switch test goes through the real path (`Sensors.update` → `SensorSwitches`) and then checks
 * the result on three levels:
 *  1. the switch reports: did each SDK/Android call succeed (CONFIRMED / SENT / FAILED / PENDING)?
 *  2. read-backs: what the SDK and Android report now (radar status, isMicrophoneMute, getCameraDisabled, ...)
 *  3. the effect: is a test recording silent, does a camera snapshot fail or come back black? For LIDAR
 *     the effect can only be checked by looking at the robot.
 *
 * Automatic effect checks log PASS or FAIL. Everything that needs eyes or ears ends with "Press ✔ or ✘";
 * [verdict] writes the tester's answer into the log file.
 *
 * The test changes only the in-memory state of [Sensors], never `privacy_settings.json`. [restoreSaved]
 * (also called when the screen closes) switches everything back to the saved settings.
 *
 * All suspend functions block for several seconds and must not run on the main thread.
 */
class SensorProbe(private val context: Context, private val log: ProbeLog) {

    private val sensors = Sensors.get(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devicePolicy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, RoboGuardDeviceAdmin::class.java)
    private val camera = CameraSnapshot()
    private val reqIds = AtomicInteger(REQ_ID_START)

    /** Name of the most recent test, used to label the ✔/✘ verdict. */
    @Volatile
    private var lastTest: String? = null

    /** Logs every change `Sensors` applies, including ones coming from the phone app via RobotServerService. */
    private val changeLogger = SensorChangeListener { name, enabled ->
        log.i(TAG, "settings change applied: $name=${onOff(enabled)}")
    }

    /** Logs `status_radar` updates, to see whether the LIDAR reports its own state change. */
    private val radarStatus = object : StatusListener() {
        override fun onStatusUpdate(type: String?, value: String?) = log.i(TAG, "status $type: $value")
    }

    @Volatile
    private var radarListenerRegistered = false

    /**
     * Logs the starting state and begins watching for settings changes. Creating [Sensors] (above) also
     * applies the saved settings once and connects the SDK if needed.
     */
    fun start() {
        log.section("Sensor switching")
        log.i(TAG, "saved settings: ${sensors.getSensors()}")
        sensors.addListener(changeLogger)
        log.i(TAG, "watching for settings changes, e.g. saved from the phone app")
    }

    /** Stops watching. Does not restore settings; see [restoreSaved]. */
    fun stop() {
        sensors.removeListener(changeLogger)
        if (radarListenerRegistered) {
            runCatching { RobotApi.getInstance().unregisterStatusListener(radarStatus) }
            radarListenerRegistered = false
        }
    }

    /** Records the tester's judgement of the most recent test in the log. */
    fun verdict(asExpected: Boolean) {
        val test = lastTest ?: return log.i(TAG, "no test has run yet")
        log.i(TAG, "VERDICT $test: ${if (asExpected) "as expected" else "WRONG"}")
    }

    /**
     * Shows everything that can be read without switching anything: settings, SDK connection, device admin,
     * Android read-backs, SDK status queries and the latest switch reports. Run it first, and after each test
     * if something looks wrong.
     */
    suspend fun status() {
        begin("status", "Current state. Nothing is switched.")
        log.i(TAG, "settings in Sensors: ${sensors.getSensors()}")
        log.i(TAG, "RobotApi connected: ${robotConnected()}")
        log.i(TAG, "device admin active (needed for the Android camera switch): ${devicePolicy.isAdminActive(admin)}")
        log.i(TAG, "Android: isMicrophoneMute=${audioManager.isMicrophoneMute}, getCameraDisabled=${devicePolicy.getCameraDisabled(null)}")
        if (robotConnected()) {
            ensureRadarListener()
            sdkQuery("queryRadarStatus") { id, l -> RobotApi.getInstance().queryRadarStatus(id, l) }
            sdkQuery("getSensorStatus") { id, l -> RobotApi.getInstance().getSensorStatus(id, l) }
            sdkQuery("getHeadCameraStatus") { id, l -> RobotApi.getInstance().getHeadCameraStatus(id, l) }
        } else {
            log.i(TAG, "SDK queries skipped: RobotApi not connected yet (Sensors connects on first use; try again in a few seconds)")
        }
        logReports(sensor = null)
    }

    /**
     * Switches one sensor through `Sensors.update`, waits for the SDK's answers, then checks read-backs
     * and effects for that sensor.
     *
     * @param sensor "LIDAR", "Microphone" or "Camera" (case-insensitive; the settings' own spelling is kept)
     */
    suspend fun switchSensor(sensor: String, enabled: Boolean) {
        begin("$sensor ${onOff(enabled)}", expectation(sensor, enabled))

        val current = sensors.getSensors()
        val key = current.keys.firstOrNull { it.equals(sensor, ignoreCase = true) } ?: sensor
        sensors.update(current + (key to enabled))
        log.i(TAG, "Sensors.update($key=${onOff(enabled)}) called, waiting ${REPORT_WAIT_MS / 1000} s for SDK answers")
        delay(REPORT_WAIT_MS)
        logReports(key)

        when (sensor.lowercase()) {
            "lidar" -> checkLidar()
            "microphone" -> checkMicrophone(enabled)
            "camera" -> checkCamera(enabled)
        }
        askForVerdict()
    }

    /** Switches all sensors back to the saved settings (`Sensors.reload`) and shows the reports. */
    suspend fun restoreSaved() {
        begin("restore saved settings", "Every sensor returns to the state saved by the phone app.")
        sensors.reload()
        log.i(TAG, "Sensors.reload() called: ${sensors.getSensors()}, waiting ${REPORT_WAIT_MS / 1000} s")
        delay(REPORT_WAIT_MS)
        logReports(sensor = null)
    }

    // ---- effect checks ------------------------------------------------------------

    private suspend fun checkLidar() {
        if (!robotConnected()) return log.i(TAG, "radar status skipped: RobotApi not connected")
        ensureRadarListener()
        sdkQuery("queryRadarStatus") { id, l -> RobotApi.getInstance().queryRadarStatus(id, l) }
        log.i(TAG, "Look at the LIDAR on the robot's base: is it running or stopped? The robot must not move while it is off.")
    }

    /**
     * Records one second and checks it against the requested state: off = silent, on = signal.
     * Talk or clap during the recording. Silence while "on" can also mean the speech service holds the mic
     * (see the voice probes), so run "Microphone on" first as a baseline.
     */
    private fun checkMicrophone(enabled: Boolean) {
        log.i(TAG, "Android read-back: isMicrophoneMute=${audioManager.isMicrophoneMute}")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return log.i(TAG, "test recording skipped: RECORD_AUDIO not granted")
        }
        log.i(TAG, "test recording for 1 s now: talk or clap")
        val level = measureMicLevelDbfs()
            ?: return log.i(TAG, "test recording failed: could not open or read the microphone")
        val silent = level < SILENCE_DBFS
        val pass = silent == !enabled
        log.i(
            TAG,
            "${if (pass) "PASS" else "FAIL"}: recording level ${"%.1f".format(level)} dBFS = ${if (silent) "silent" else "signal"}, " +
                "expected ${if (enabled) "signal" else "silent"}"
        )
        log.i(TAG, "Also say the wake word: the robot should ${if (enabled) "" else "NOT "}react.")
    }

    /**
     * Takes one camera snapshot: off = the capture fails or the frame is black, on = a normal frame.
     * A failure because the SDK is not connected proves nothing and is reported as inconclusive.
     */
    private suspend fun checkCamera(enabled: Boolean) {
        log.i(TAG, "Android read-back: getCameraDisabled=${devicePolicy.getCameraDisabled(null)} (device admin active: ${devicePolicy.isAdminActive(admin)})")
        val blocked: Boolean = when (val shot = camera.capture()) {
            is SnapshotResult.Success -> {
                val pixels = shot.image.pixels
                val mean = pixels.sumOf { (it.toInt() and 0xFF).toLong() }.toDouble() / pixels.size
                log.i(TAG, "snapshot succeeded after ${shot.elapsedMs} ms, mean brightness ${"%.0f".format(mean)}/255")
                mean < DARK_FRAME_MEAN
            }
            is SnapshotResult.Failure -> {
                log.i(TAG, "snapshot failed after ${shot.elapsedMs} ms: ${shot.reason}")
                if (shot.reason == SnapshotFailure.NotConnected) {
                    log.i(TAG, "INCONCLUSIVE: RobotApi not connected, so the failure says nothing about the camera")
                    return
                }
                true
            }
        }
        val pass = blocked == !enabled
        log.i(TAG, "${if (pass) "PASS" else "FAIL"}: camera ${if (blocked) "blocked" else "delivers images"}, expected ${if (enabled) "images" else "blocked"}")
        log.i(TAG, "Also step in front of the robot: face detection should ${if (enabled) "" else "NOT "}react.")
    }

    // ---- helpers ------------------------------------------------------------------

    /**
     * Records one second, mono 16 kHz, and returns its RMS level in dBFS, or null if recording failed.
     * Only the level is computed; no audio is kept.
     */
    @SuppressLint("MissingPermission") // checked by the caller
    private fun measureMicLevelDbfs(): Double? {
        val rate = 16_000
        val samples = ShortArray(rate)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, samples.size * 2)
        }.getOrNull() ?: return null
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return null
            recorder.startRecording()
            var read = 0
            while (read < samples.size) {
                val n = recorder.read(samples, read, samples.size - read)
                if (n <= 0) break
                read += n
            }
            if (read == 0) return null
            var sum = 0.0
            for (i in 0 until read) sum += samples[i].toDouble() * samples[i]
            val rms = sqrt(sum / read)
            return if (rms == 0.0) Double.NEGATIVE_INFINITY else 20 * log10(rms / Short.MAX_VALUE)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Sends one SDK query and logs its answer, or a timeout. The answer formats are undocumented, which is
     * exactly what this logs. They contain device state, no personal data.
     */
    private suspend fun sdkQuery(name: String, call: (Int, CommandListener) -> Int) {
        val answer = CompletableDeferred<String>()
        val listener = object : CommandListener() {
            // 3-argument callbacks only: the SDK calls both variants (CLAUDE.md).
            override fun onResult(result: Int, message: String?, extraData: String?) {
                answer.complete("result=$result message=$message")
            }

            override fun onError(errorCode: Int, errorString: String?, extraData: String?) {
                answer.complete("error=$errorCode $errorString")
            }
        }
        val code = runCatching { call(reqIds.incrementAndGet(), listener) }.getOrElse {
            return log.i(TAG, "$name threw $it")
        }
        if (code < 0) return log.i(TAG, "$name rejected, code $code")
        val text = withTimeoutOrNull(SDK_TIMEOUT_MS) { answer.await() } ?: "no answer within ${SDK_TIMEOUT_MS / 1000} s"
        log.i(TAG, "$name: $text")
    }

    private fun ensureRadarListener() {
        if (radarListenerRegistered) return
        runCatching { RobotApi.getInstance().registerStatusListener(Definition.STATUS_RADAR, radarStatus) }
            .onSuccess { radarListenerRegistered = true }
    }

    /** Logs the latest switch report per mechanism, for one sensor or all. */
    private fun logReports(sensor: String?) {
        val reports = sensors.switchReports.value.values
            .filter { sensor == null || it.sensor.equals(sensor, ignoreCase = true) }
        if (reports.isEmpty()) return log.i(TAG, "no switch reports${sensor?.let { " for $it" } ?: ""} yet")
        for (r in reports) {
            log.i(TAG, "report: ${r.sensor} ${onOff(r.enabled)} via ${r.method}: ${r.outcome}${r.detail?.let { " ($it)" } ?: ""}")
        }
    }

    private fun robotConnected(): Boolean = runCatching { RobotApi.getInstance().isApiConnectedService() }.getOrDefault(false)

    private fun expectation(sensor: String, enabled: Boolean): String = when (sensor.lowercase() to enabled) {
        "lidar" to false -> "Expect: SDK report CONFIRMED, radar status closed, LIDAR visibly stopped. The robot must not move."
        "lidar" to true -> "Expect: SDK report CONFIRMED, radar status open, LIDAR running again. The robot may need to relocalize."
        "microphone" to false -> "Expect: Android CONFIRMED (isMicrophoneMute=true), SDK CONFIRMED or SENT, test recording silent, no reaction to the wake word."
        "microphone" to true -> "Expect: Android CONFIRMED (isMicrophoneMute=false), test recording has signal, wake word works."
        "camera" to false -> "Expect: SDK stopVision CONFIRMED, Android CONFIRMED if the device admin is active, snapshot blocked or black, no reaction to faces."
        "camera" to true -> "Expect: SDK startVision CONFIRMED, snapshot with normal brightness, face reactions return."
        else -> "No expectation defined for $sensor."
    }

    private fun begin(name: String, expectation: String) {
        lastTest = name
        log.section("Sensors: $name")
        log.i(TAG, expectation)
    }

    private fun askForVerdict() = log.i(TAG, "Did it behave as expected? Press ✔ or ✘.")

    private fun onOff(enabled: Boolean) = if (enabled) "ON" else "OFF"

    private companion object {
        const val TAG = "sensors"

        /** Request ids for probe queries, apart from SensorSwitches (20 000+) and other components. */
        const val REQ_ID_START = 30_000

        /** Time for the SDK's asynchronous answers to arrive before the reports are read. */
        const val REPORT_WAIT_MS = 3_000L
        const val SDK_TIMEOUT_MS = 5_000L

        /** A muted microphone delivers zeros or digital silence; real room noise is far above this. */
        const val SILENCE_DBFS = -80.0

        /** Mean Y value below which a frame counts as black (lens covered or camera blocked). */
        const val DARK_FRAME_MEAN = 8.0
    }
}
