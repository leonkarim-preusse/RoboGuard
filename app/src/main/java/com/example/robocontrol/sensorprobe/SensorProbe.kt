package com.example.robocontrol.sensorprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.speech.SkillApi
import com.ainirobot.coreservice.client.speech.SkillCallback
import com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin
import com.example.robocontrol.sensorcontrol.SensorChangeListener
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.robocontrol.vision.CameraSnapshot
import com.example.robocontrol.vision.SnapshotFailure
import com.example.robocontrol.vision.SnapshotResult
import com.example.robocontrol.voiceprobe.ProbeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/** The latest camera snapshot for the test screen: [bitmap] is null when no image could be taken. */
data class ShownImage(val bitmap: Bitmap?, val caption: String)

/** One test recording: 16 kHz mono PCM, held in memory only until it has been played back, never written. */
private class MicRecording(val samples: ShortArray, val count: Int, val levelDbfs: Double)

/**
 * Hardware test for sensor switching: does `Sensors.update()` really switch LIDAR, microphone and camera
 * on the robot?
 *
 * So the tester can judge with their own ears and eyes, every test recording is played back through the robot's
 * speaker right after it is measured, and every camera snapshot is shown on screen ([lastImage]).
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

    private val _lastImage = MutableStateFlow<ShownImage?>(null)

    /** The most recent camera snapshot (or the reason there is none), shown by the test screen. */
    val lastImage: StateFlow<ShownImage?> = _lastImage.asStateFlow()

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

    // ---- speech-service observer (for the RobotOS-level microphone check) ----

    private val skillApi = SkillApi()

    @Volatile
    private var skillConnected = false

    /** Recognition starts and partial/final results. Only counted; the recognised text is never read or logged. */
    private val speechEvents = AtomicInteger(0)

    /** Volume callbacks with a value above 0, i.e. the speech service measured sound. */
    private val loudVolumeEvents = AtomicInteger(0)

    private val skillCallback = object : SkillCallback() {
        override fun onSpeechParResult(result: String?) { speechEvents.incrementAndGet() }
        override fun onQueryAsrResult(result: String?) { speechEvents.incrementAndGet() }
        override fun onStart() { speechEvents.incrementAndGet() }
        override fun onStop() {}
        override fun onQueryEnded(status: Int) {}
        override fun onVolumeChange(volume: Int) { if (volume > 0) loudVolumeEvents.incrementAndGet() }
    }

    /**
     * Logs the starting state and begins watching for settings changes. Creating [Sensors] (above) also
     * applies the saved settings once and connects the SDK if needed.
     */
    fun start() {
        log.section("Sensor switching")
        log.i(TAG, "saved settings: ${sensors.getSensors()}")
        sensors.addListener(changeLogger)
        log.i(TAG, "watching for settings changes, e.g. saved from the phone app")

        // Observes RobotOS's speech service, to check whether the microphone switch reaches RobotOS itself,
        // not just ordinary apps.
        skillApi.connectApi(context, object : ApiListener {
            override fun handleApiConnected() {
                skillApi.registerCallBack(skillCallback)
                skillConnected = true
                log.i(TAG, "speech-service observer connected")
            }

            override fun handleApiDisconnected() {
                skillConnected = false
            }

            override fun handleApiDisabled() {
                skillConnected = false
                log.i(TAG, "speech-service observer disabled: start this screen from the robot's home launcher icon")
            }
        })
    }

    /** Stops watching. Does not restore settings; see [restoreSaved]. */
    fun stop() {
        sensors.removeListener(changeLogger)
        runCatching { skillApi.unregisterCallBack(skillCallback) }
        runCatching { skillApi.disconnectApi() }
        skillConnected = false
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
     * The microphone switch acts on two layers, so they are checked separately:
     *  1. Android layer (`setMicrophoneMute`): can an ordinary app still record? A 2 s test recording.
     *     A silent recording while ON is INCONCLUSIVE, not FAIL: it usually just means nobody talked.
     *  2. RobotOS speech layer (`setASREnabled` / `setRecognizable`): does RobotOS's speech service still hear
     *     anything? For [SPEECH_WINDOW_MS] the speech service's own callbacks are counted (recognition start,
     *     partial/final results, volume above 0) while the tester says the wake word and keeps talking.
     *     Only counts are logged, never recognised text.
     * A silent Android recording alone does NOT prove the microphone is off for RobotOS; layer 2 does.
     */
    private suspend fun checkMicrophone(enabled: Boolean) {
        log.i(TAG, "Android read-back: isMicrophoneMute=${audioManager.isMicrophoneMute}")

        // ---- 1. Android layer
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log.i(TAG, "[Android layer] skipped: RECORD_AUDIO not granted")
        } else {
            log.i(TAG, "[Android layer] test recording for ${MIC_RECORD_SECONDS} s NOW: talk or clap")
            val recording = recordMic(MIC_RECORD_SECONDS)
            if (recording == null) {
                log.i(TAG, "[Android layer] recording failed: could not open or read the microphone")
            } else {
                val level = recording.levelDbfs
                val silent = level < SILENCE_DBFS
                val levelText = "recording level ${"%.1f".format(level)} dBFS = ${if (silent) "silent" else "signal"}"
                val verdict = when {
                    !enabled && silent -> "PASS"
                    !enabled -> "FAIL"
                    !silent -> "PASS"
                    else -> "INCONCLUSIVE (did you talk? repeat the test; if it stays silent, another app may hold the mic)"
                }
                log.i(TAG, "[Android layer] $verdict: $levelText, expected ${if (enabled) "signal" else "silent"}")
                log.i(TAG, "[Android layer] playing the recording back now (${if (silent) "expect silence" else "you should hear yourself"})")
                playBack(recording)
            }
        }

        // ---- 2. RobotOS speech layer
        if (!skillConnected) {
            return log.i(TAG, "[Speech layer] skipped: speech-service observer not connected")
        }
        speechEvents.set(0)
        loudVolumeEvents.set(0)
        log.i(TAG, "[Speech layer] for ${SPEECH_WINDOW_MS / 1000} s NOW: say the wake word, then keep talking")
        delay(SPEECH_WINDOW_MS)
        val speech = speechEvents.get()
        val volume = loudVolumeEvents.get()
        val heard = speech > 0 || volume > 0
        log.i(TAG, "[Speech layer] speech-service callbacks: recognition/results=$speech, volume>0=$volume")
        val verdict = when {
            heard == enabled -> "PASS"
            enabled -> "FAIL? (nobody spoke, or RobotOS did not re-enable recognition; see isRecognizable in the report)"
            else -> "FAIL (RobotOS still hears the microphone)"
        }
        log.i(TAG, "[Speech layer] $verdict: speech service ${if (heard) "heard something" else "heard nothing"}, expected ${if (enabled) "to hear you" else "nothing"}")
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
                log.i(TAG, "snapshot succeeded after ${shot.elapsedMs} ms, mean brightness ${"%.0f".format(mean)}/255 (shown on screen)")
                _lastImage.value = ShownImage(
                    shot.image.toBitmap(),
                    "Camera ${onOff(enabled)}: ${shot.image.width}×${shot.image.height}, mean brightness ${"%.0f".format(mean)}/255"
                )
                mean < DARK_FRAME_MEAN
            }
            is SnapshotResult.Failure -> {
                log.i(TAG, "snapshot failed after ${shot.elapsedMs} ms: ${shot.reason}")
                _lastImage.value = ShownImage(null, "Camera ${onOff(enabled)}: no image (${shot.reason})")
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
     * Records [seconds] seconds, mono [MIC_RATE] Hz, and returns the samples with their RMS level in dBFS, or null if
     * recording failed. The samples stay in memory only, for [playBack], and are never written anywhere.
     */
    @SuppressLint("MissingPermission") // checked by the caller
    private fun recordMic(seconds: Int): MicRecording? {
        val rate = MIC_RATE
        val samples = ShortArray(rate * seconds)
        // CAMCORDER, not MIC: on the GreetBot Mini (robot ZTT18P1000A0) the voice probe's Mic access test measured
        // CAMCORDER -37 dBFS, MIC -77 dBFS and VOICE_RECOGNITION/VOICE_COMMUNICATION/UNPROCESSED about -93 dBFS with
        // the same sound; MIC also varied between -35 and -103 dBFS while com.ainirobot.remotecontrolservice keeps
        // recording from MIC permanently.
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, samples.size * 2)
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
            val level = if (rms == 0.0) Double.NEGATIVE_INFINITY else 20 * log10(rms / Short.MAX_VALUE)
            return MicRecording(samples, read, level)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Plays [recording] once through the robot's speaker and returns when it has finished, so the playback does not
     * overlap the following speech-layer check. The samples are zeroed afterwards.
     */
    private suspend fun playBack(recording: MicRecording) {
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MIC_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(recording.count * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrElse {
            recording.samples.fill(0)
            return log.i(TAG, "playback failed: $it")
        }
        try {
            track.write(recording.samples, 0, recording.count)
            track.play()
            delay(recording.count * 1000L / MIC_RATE + PLAYBACK_TAIL_MS)
        } catch (e: Exception) {
            log.i(TAG, "playback failed: $e")
        } finally {
            runCatching { track.stop() }
            track.release()
            recording.samples.fill(0)
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

        /** Length of the Android-layer test recording. */
        const val MIC_RECORD_SECONDS = 2

        /** Sample rate for the test recording and its playback. */
        const val MIC_RATE = 16_000

        /** Extra wait after playback so the end is not cut off. */
        const val PLAYBACK_TAIL_MS = 300L

        /** How long speech-service callbacks are counted for the RobotOS-layer microphone check. */
        const val SPEECH_WINDOW_MS = 6_000L

        /** Mean Y value below which a frame counts as black (lens covered or camera blocked). */
        const val DARK_FRAME_MEAN = 8.0
    }
}
