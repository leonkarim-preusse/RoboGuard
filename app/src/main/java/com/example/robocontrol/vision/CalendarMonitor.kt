package com.example.robocontrol.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.robocontrol.audio.OrionStarTts
import com.example.robocontrol.audio.TtsFailure
import com.example.robocontrol.audio.TtsListener
import com.example.robocontrol.sensorcontrol.SensorChangeListener
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.robocontrol.system.RobotApiConnection
import com.example.robocontrol.system.SdkControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Watches the camera whenever RoboGuard runs (started by RobotServerService) and says [SENTENCE] when a calendar is detected in
 * [CalendarDetectionSettings.CONSISTENCY_NEEDED] of the last [CalendarDetectionSettings.CONSISTENCY_WINDOW] passes, at most once per
 * [COOLDOWN_MS]. Every reference image in assets/ORB_img counts as "calendar". Detection = pink-marker-gated ORB with
 * [CalendarDetectionSettings] (the object test's chosen settings). Runs in the service, so it also works while driving.
 *
 * Watches only while RoboGuard's privacy settings allow the camera and no test screen uses the camera (SurfaceShare allows one
 * consumer per app). Frames are only in memory; logs contain numbers only (Logcat tag CalendarMonitor).
 * Speaking needs SDK control (RoboGuard in the foreground); otherwise the announcement is skipped and logged.
 */
object CalendarMonitor {

    private const val TAG = "CalendarMonitor"

    /** Owner asked for "Kalendar entdeckt"; spelled as the German word so the German voice pronounces it correctly. */
    const val SENTENCE = "Kalender entdeckt"

    /** No new announcement for this long after one (owner: 15 s, then 3 s). */
    const val COOLDOWN_MS = 3_000L

    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var probeUsingCamera = false
    private var tts: OrionStarTts? = null

    /** One detection pass for a debug view (only published while a view is open). */
    class DebugSnapshot(
        val pass: MarkerPass,
        val passMs: Long,
        /** Detected passes among the last [window]. */
        val recent: Int,
        val window: Int,
        val cooldownLeftMs: Long,
        val announcements: Int,
        val passesPerSecond: Double
    )

    private val _state = MutableStateFlow("not started")
    /** What the monitor is doing ("watching", or why not), for debug views. */
    val state: StateFlow<String> = _state.asStateFlow()

    private val _debug = MutableStateFlow<DebugSnapshot?>(null)
    val debug: StateFlow<DebugSnapshot?> = _debug.asStateFlow()

    private val debugViewers = AtomicInteger(0)

    @Volatile
    private var activeCamera: CameraStream? = null

    @Volatile
    private var announcements = 0

    /** Newest camera frame of the monitor's stream (null when not watching). For live debug previews; never stored. */
    fun latestFrame(): CameraFrame? = activeCamera?.latest?.get()

    /** A debug view is open: publish [debug] snapshots (with the pink overlay). Pair with [removeDebugViewer]. */
    fun addDebugViewer() { debugViewers.incrementAndGet() }

    fun removeDebugViewer() {
        if (debugViewers.decrementAndGet() <= 0) {
            debugViewers.set(0)
            _debug.value = null
        }
    }

    private const val PREFS = "robocontrol_calendar_monitor"
    /** Renamed whenever the default changes (v2: 30, v3: 20), so an older saved value does not stay. */
    private const val KEY_MIN_INLIERS = "min_inliers_v3"

    private const val KEY_EVERY_DETECTION = "announce_every_detection"

    private val _announceEveryDetection = MutableStateFlow(true)
    /**
     * Test mode (owner): speak on every detected pass (only while not already speaking), without "in a row" and cooldown.
     * Off = [CalendarDetectionSettings.CONSISTENCY_NEEDED] in a row, then [COOLDOWN_MS]. Saved.
     */
    val announceEveryDetection: StateFlow<Boolean> = _announceEveryDetection.asStateFlow()

    fun setAnnounceEveryDetection(on: Boolean) {
        _announceEveryDetection.value = on
        if (::appContext.isInitialized) appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EVERY_DETECTION, on).apply()
        Log.i(TAG, "announce every detection: $on")
    }

    private val _minInliers = MutableStateFlow(CalendarDetectionSettings.MIN_INLIERS)
    /** Inliers a detection needs; changed in the camera debug view, kept across restarts (SharedPreferences). */
    val minInliers: StateFlow<Int> = _minInliers.asStateFlow()

    fun setMinInliers(value: Int) {
        val v = value.coerceIn(CalendarDetectionSettings.MIN_INLIERS_LOWEST, CalendarDetectionSettings.MIN_INLIERS_HIGHEST)
        _minInliers.value = v
        if (::appContext.isInitialized) appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MIN_INLIERS, v).apply()
        Log.i(TAG, "inliers required $v")
    }

    /** elapsedRealtime until which no announcement is made; kept across stream restarts. */
    @Volatile
    private var cooldownUntil = 0L

    private val sensorListener = SensorChangeListener { name, _ ->
        if (name.equals("camera", ignoreCase = true)) reevaluate()
    }

    /** Starts watching (idempotent). Call from RobotServerService.onCreate. */
    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        appContext = context.applicationContext
        _minInliers.value = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MIN_INLIERS, CalendarDetectionSettings.MIN_INLIERS)
        _announceEveryDetection.value = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EVERY_DETECTION, true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Sensors.get(appContext).addListener(sensorListener)
        Log.i(TAG, "started")
        reevaluate()
    }

    /** Stops everything. Call from RobotServerService.onDestroy. */
    @Synchronized
    fun stop() {
        if (scope == null) return
        stopLoop("service stopped")
        runCatching { Sensors.get(appContext).removeListener(sensorListener) }
        runCatching { tts?.disconnect() }
        tts = null
        scope?.cancel()
        scope = null
    }

    /** The object test calls this while it streams the camera itself. */
    @Synchronized
    fun setProbeUsingCamera(active: Boolean) {
        probeUsingCamera = active
        if (scope != null) reevaluate()
    }

    @Synchronized
    private fun reevaluate() {
        if (scope == null) return
        val cameraAllowed = Sensors.get(appContext).getSensors().entries
            .firstOrNull { it.key.equals("camera", ignoreCase = true) }?.value != false
        val reason = when {
            !cameraAllowed -> "camera switched off in the privacy settings"
            probeUsingCamera -> "a test screen is using the camera"
            else -> null
        }
        if (reason == null) startLoop() else {
            stopLoop(reason)
            _state.value = "not watching: $reason"
        }
    }

    private fun startLoop() {
        if (loopJob != null) return
        val s = scope ?: return
        _state.value = "starting (waiting for the robot connection)"
        loopJob = s.launch { watch() }
    }

    private fun stopLoop(reason: String) {
        val job = loopJob ?: return
        loopJob = null
        job.cancel()
        Log.i(TAG, "not watching: $reason")
    }

    /** Camera + detection loop; restarts the stream after errors. Stream and ORBs are released when cancelled. */
    private suspend fun CoroutineScope.watch() {
        RobotApiConnection.connect(appContext)
        RobotApiConnection.connected.first { it }
        // One ORB + ColorMarker per worker: ORB is synchronized per instance, so parallel passes need their own.
        val workers = ArrayList<Pair<ORB, ColorMarker>>()
        try {
            repeat(CalendarDetectionSettings.WORKERS) {
                val orb = ORB(appContext, CalendarDetectionSettings.orb)
                workers += orb to ColorMarker().also { m -> CalendarDetectionSettings.applyTo(m) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ORB failed, not watching", e)
            workers.forEach { it.first.release() }
            return
        }
        val orb0 = workers.first().first
        Log.i(TAG, "references ${orb0.assetLoadReport.loaded}; features ${orb0.config.maxFeatures}, FAST ${orb0.config.fastThreshold}, " +
            "grid ${orb0.config.gridDistribution}, thresholds ${CalendarDetectionSettings.MIN_GOOD_MATCHES}/${_minInliers.value} (confirm ${confirmInliers()}), " +
            "pink margin ${CalendarDetectionSettings.MARKER_MARGIN_PX}, white inside ${CalendarDetectionSettings.REQUIRE_WHITE_INSIDE} " +
            "(≥ ${CalendarDetectionSettings.MIN_WHITE_SHARE}), workers ${workers.size}, " +
            "${CalendarDetectionSettings.CONSISTENCY_NEEDED} of ${CalendarDetectionSettings.CONSISTENCY_WINDOW}, cooldown ${COOLDOWN_MS / 1000} s, " +
            "announce every detection ${_announceEveryDetection.value}")
        val camera = CameraStream()
        activeCamera = camera
        var streamError = false
        try {
            while (isActive) {
                streamError = false
                val code = camera.start { error, message ->
                    Log.w(TAG, "camera stream error $error: $message")
                    streamError = true
                }
                if (code < 0) {
                    Log.w(TAG, "camera request failed: $code, retry in 5 s")
                    _state.value = "camera request failed ($code), retrying"
                    delay(5_000)
                    continue
                }
                Log.i(TAG, "watching")
                _state.value = "watching"
                detectUntilError(workers, camera) { streamError }
                camera.stop()
                if (isActive) delay(5_000)
            }
        } finally {
            activeCamera = null
            camera.stop()
            workers.forEach { it.first.release() }
            _debug.value = null
        }
    }

    /** Results of the workers, combined in frame order. Guarded by its own lock. */
    private class PassLog {
        /** Per pass in frame order: 0 = nothing, 1 = confirm-level detection (≥ X − 10 inliers), 2 = full detection (≥ X). */
        val history = ArrayDeque<Int>()
        var lastTs = 0L
        // For the 2 s summary.
        var passes = 0; var detected = 0; var msSum = 0L; var colourMsSum = 0L; var regions = 0
        var rejectedFrame = 0; var rejectedWhite = 0; var bestInliers = 0; var outOfOrder = 0
        var skipped = 0
        var previewMsSum = 0L; var regionPasses = 0; var regionMsSum = 0L; val orb = ORB.Timings()
        /** Per reference image, over passes with a pink area: inlier sum, best, passes where it was the strongest. */
        val refInliers = HashMap<String, IntArray>()
    }

    private suspend fun detectUntilError(workers: List<Pair<ORB, ColorMarker>>, camera: CameraStream, failed: () -> Boolean) = coroutineScope {
        val taken = AtomicLong(0)
        val orbGate = java.util.concurrent.Semaphore(1)
        val nextStart = AtomicLong(0)
        val startInterval = 1000L / CalendarDetectionSettings.MAX_PASSES_PER_SECOND
        val log = PassLog()
        val jobs = workers.map { (orb, marker) ->
            launch(Dispatchers.Default) {
                while (isActive) {
                    val f = camera.latest.get()
                    val prev = taken.get()
                    // Each frame goes to exactly one worker, always the newest one available.
                    // While another worker runs ORB, wait instead of doing a colour pass that would only be skipped (it cost
                    // 60–100 ms each, ~8 per second, competing with ORB for the CPU).
                    if (orbGate.availablePermits() == 0) {
                        delay(10)
                        continue
                    }
                    val slot = nextStart.get()
                    val nowStart = SystemClock.elapsedRealtime()
                    if (f == null || f.timestampMs <= prev || nowStart < slot) {
                        delay(10)
                        continue
                    }
                    // Claim the start slot first (rate limit), then the frame.
                    if (!nextStart.compareAndSet(slot, nowStart + startInterval)) continue
                    if (!taken.compareAndSet(prev, f.timestampMs)) continue
                    val debugging = debugViewers.get() > 0
                    val t0 = SystemClock.elapsedRealtime()
                    val pass = try {
                        // Run with the lower confirm threshold; onPass decides whether a result is a full or a confirm-level detection.
                        markerGatedPass(orb, marker, f, CalendarDetectionSettings.MIN_GOOD_MATCHES, confirmInliers(), withOverlay = debugging, orbGate = orbGate)
                    } catch (e: Exception) {
                        Log.w(TAG, "detection failed: $e")
                        continue
                    }
                    onPass(log, pass, f.timestampMs, SystemClock.elapsedRealtime() - t0, debugging)
                }
            }
        }
        var lastSummary = SystemClock.elapsedRealtime()
        var framesAtSummary = camera.framesReceived
        var lastFrameTs = 0L
        var lastFrameAt = SystemClock.elapsedRealtime()
        while (isActive && !failed()) {
            delay(200)
            val now = SystemClock.elapsedRealtime()
            val ts = camera.latest.get()?.timestampMs ?: 0L
            if (ts != lastFrameTs) { lastFrameTs = ts; lastFrameAt = now }
            if (now - lastFrameAt > 10_000) {
                Log.w(TAG, "no camera frame for 10 s, restarting the stream")
                break
            }
            if (now - lastSummary >= 2_000) {
                val seconds = (now - lastSummary) / 1000.0
                val frames = camera.framesReceived
                synchronized(log) {
                    val n = log.passes.coerceAtLeast(1)
                    passesPerSecond = log.passes / seconds
                    Log.i(TAG, "camera %.1f fps · passes %.1f/s (avg %d ms, colour %d ms) · detected %d/%d · regions %.1f · rejected frame %d, white %d · best inliers %d%s".format(
                        (frames - framesAtSummary) / seconds, passesPerSecond, log.msSum / n, log.colourMsSum / n, log.detected, log.passes,
                        log.regions.toDouble() / n, log.rejectedFrame, log.rejectedWhite, log.bestInliers,
                        (if (log.outOfOrder > 0) " · out of order ${log.outOfOrder}" else "") +
                        if (log.skipped > 0) " · skipped (ORB busy) ${log.skipped}" else ""))
                    log.skipped = 0
                    if (log.regionPasses > 0) {
                        val r = log.regionPasses
                        fun ms(ns: Long) = ns / 1_000_000.0 / r
                        Log.i(TAG, "timing per pass: yuv→rgb %.0f ms · colour %.0f ms (all passes) | passes with a pink area: %d, avg %d ms = resize %.0f + keypoints %.0f + knn match %.0f + ratio test %.0f + homography %.0f ms".format(
                            log.previewMsSum.toDouble() / n, log.colourMsSum.toDouble() / n, r, log.regionMsSum / r,
                            ms(log.orb.resizeNs), ms(log.orb.extractNs), ms(log.orb.knnNs), ms(log.orb.ratioNs), ms(log.orb.homographyNs)))
                    } else {
                        Log.i(TAG, "timing per pass: yuv→rgb %.0f ms · colour %.0f ms (no pass with a pink area)".format(
                            log.previewMsSum.toDouble() / n, log.colourMsSum.toDouble() / n))
                    }
                    if (log.regionPasses > 0 && log.refInliers.isNotEmpty()) {
                        Log.i(TAG, "inliers per reference (passes with a pink area: ${log.regionPasses}): " + log.refInliers.entries.sortedBy { it.key }.joinToString { (name, a) ->
                            "$name avg %.1f, best ${a[1]}, strongest in ${a[2]}".format(a[0].toDouble() / log.regionPasses) })
                    }
                    log.refInliers.clear()
                    log.previewMsSum = 0; log.regionPasses = 0; log.regionMsSum = 0
                    log.orb.resizeNs = 0; log.orb.extractNs = 0; log.orb.knnNs = 0; log.orb.ratioNs = 0; log.orb.homographyNs = 0
                    log.passes = 0; log.detected = 0; log.msSum = 0; log.colourMsSum = 0; log.regions = 0
                    log.rejectedFrame = 0; log.rejectedWhite = 0; log.bestInliers = 0; log.outOfOrder = 0
                }
                framesAtSummary = frames
                lastSummary = now
            }
        }
        jobs.forEach { it.cancel() }
    }

    @Volatile
    private var passesPerSecond = 0.0

    /** Inliers a pass needs to confirm a previous full detection: X − [CalendarDetectionSettings.CONFIRM_INLIER_DROP]. */
    fun confirmInliers(): Int = (_minInliers.value - CalendarDetectionSettings.CONFIRM_INLIER_DROP).coerceAtLeast(CalendarDetectionSettings.MIN_INLIERS_LOWEST)

    private fun onPass(log: PassLog, pass: MarkerPass, frameTs: Long, ms: Long, debugging: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val strongNeeded = _minInliers.value
        val best = pass.results.filter { it.detected }.maxByOrNull { it.inliers }
        // 2 = full detection (≥ X inliers), 1 = only enough to confirm a previous full detection (≥ X − 10), 0 = nothing.
        val level = when {
            best == null -> 0
            best.inliers >= strongNeeded -> 2
            else -> 1
        }
        var announce = false
        val recent: Int
        val window: Int
        if (pass.skipped) {
            // Another worker is running ORB on a newer or same-age frame: not a miss, so it must not enter the history.
            synchronized(log) { log.skipped++ }
            return
        }
        synchronized(log) {
            log.passes++; log.msSum += ms; log.colourMsSum += pass.marker?.millis ?: 0
            log.previewMsSum += pass.previewMs
            if (pass.regions.isNotEmpty()) {
                log.regionPasses++; log.regionMsSum += ms; log.orb.add(pass.orbTimings)
                val strongest = pass.results.maxByOrNull { it.inliers }
                for (r in pass.results) {
                    val a = log.refInliers.getOrPut(r.className) { IntArray(3) }
                    a[0] += r.inliers; a[1] = maxOf(a[1], r.inliers)
                    if (r === strongest && r.inliers > 0) a[2]++
                }
            }
            log.regions += pass.regions.size
            pass.marker?.rejected?.forEach { if (it.rejectReason == "white") log.rejectedWhite++ else log.rejectedFrame++ }
            log.bestInliers = maxOf(log.bestInliers, pass.results.maxOfOrNull { it.inliers } ?: 0)
            if (level == 2) log.detected++
            // Workers can finish out of order; an older frame does not count for "in a row".
            if (frameTs < log.lastTs) {
                log.outOfOrder++
            } else {
                log.lastTs = frameTs
                log.history.addLast(level)
                while (log.history.size > CalendarDetectionSettings.CONSISTENCY_WINDOW) log.history.removeFirst()
            }
            recent = log.history.count { it > 0 }
            window = log.history.size
            // Stepped confirmation (owner): the first pass of the window needs a full detection, the following ones only the
            // confirm level, all in a row.
            val confirmed = window >= CalendarDetectionSettings.CONSISTENCY_WINDOW && log.history.first() == 2 &&
                log.history.count { it > 0 } >= CalendarDetectionSettings.CONSISTENCY_NEEDED
            if (_announceEveryDetection.value) {
                if (level == 2 && !isSpeaking(now)) announce = true
            } else if (confirmed && now >= cooldownUntil) {
                announce = true
                cooldownUntil = now + COOLDOWN_MS
                log.history.clear()
            }
            if (announce) {
                announcements++
                speakingSince = now
            }
        }
        if (debugging) {
            _debug.value = DebugSnapshot(pass, ms, recent, window, (cooldownUntil - now).coerceAtLeast(0), announcements, passesPerSecond)
        }
        if (announce) {
            val strongest = pass.results.maxByOrNull { it.inliers }
            Log.i(TAG, "calendar detected (${if (_announceEveryDetection.value) "every detection" else "$recent/$window passes"}): " +
                "${strongest?.className} good ${strongest?.goodMatches}, inliers ${strongest?.inliers}, pink regions ${pass.regions.size}")
            scope?.launch { speak(SENTENCE) }
        }
    }

    /** elapsedRealtime when the last announcement started; 0 = not speaking. Cleared when speech ends or fails. */
    @Volatile
    private var speakingSince = 0L

    /** Speaking, with a safety timeout in case the speech service never reports the end. */
    private fun isSpeaking(now: Long) = speakingSince != 0L && now - speakingSince < 6_000

    /** Speaks if RoboGuard has SDK control; never brings a screen to the front and never throws. */
    private suspend fun speak(sentence: String) {
        try {
            if (!SdkControl.awaitControl(appContext, timeoutMs = 500)) {
                Log.w(TAG, "no SDK control (RoboGuard not in front), not spoken")
                speakingSince = 0L
                return
            }
            val speech = synchronized(this) { tts ?: OrionStarTts(appContext).also { tts = it; it.connect() } }
            if (withTimeoutOrNull(5_000) { speech.connected.first { it } } == null) {
                Log.w(TAG, "speech service not connected, not spoken")
                speakingSince = 0L
                return
            }
            val started = speech.speakGerman(sentence, object : TtsListener {
                override fun onFinished() { speakingSince = 0L }
                override fun onFailed(failure: TtsFailure) {
                    Log.w(TAG, "not spoken: $failure")
                    speakingSince = 0L
                }
            })
            if (!started) speakingSince = 0L
        } catch (e: Exception) {
            Log.e(TAG, "could not speak", e)
            speakingSince = 0L
        }
    }
}
