package com.example.robocontrol.vision

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.robocontrol.audio.OrionStarTts
import com.example.robocontrol.audio.TtsFailure
import com.example.robocontrol.audio.TtsListener
import com.example.robocontrol.sensorcontrol.SensorChangeListener
import com.example.robocontrol.sensorcontrol.SituationalChangeListener
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.robocontrol.system.RobotApiConnection
import com.example.robocontrol.system.SdkControl
import com.example.robocontrol.text.UiText
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
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Background object detection. Watches the camera while the camera and Pixelate Objects are enabled and no other
 * component uses the camera stream, and runs the pink-marker-gated ORB detection ([markerGatedPass]) with the values from
 * [CalendarDetectionSettings]. A confirmed calendar opens [CalendarPromptActivity]; other objects are announced by speech.
 * Logs contain numbers only (tag CalendarMonitor).
 */
object CalendarMonitor {

    private const val TAG = "CalendarMonitor"

    /**
     * How long a reference counts as "still in view" after its last full detection, for the "seen"/"gone" lines the
     * performance graphs draw as bars. Only a report: it changes no decision and no announcement.
     */
    private const val SEEN_HOLD_MS = 1_500L

    /** Name of the phone's situational switch that turns object detection on. */
    const val PIXELATE_OBJECTS = "Pixelate Objects"

    /** Spoken when a calendar was recognised; wording in assets/texts/texts.json ("speech.calendar_detected"). */
    val sentence: String get() = UiText.get("speech.calendar_detected")

    /**
     * The sentence spoken for a detected object, built from its reference name.
     * @param className name of the matched reference; null gives the default [sentence]
     */
    fun sentenceFor(className: String?): String {
        val name = className?.replace('_', ' ')?.replace('-', ' ')?.trim()
        return if (name.isNullOrEmpty()) sentence else UiText.get("speech.object_detected", "object" to name)
    }

    /** Minimum time between two announcements. */
    val COOLDOWN_MS: Long get() = CalendarDetectionSettings.COOLDOWN_MS

    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var probeUsingCamera = false
    private var tts: OrionStarTts? = null

    /**
     * Data of one detection pass for the camera debug view.
     * @property pass result of the pass
     * @property passMs duration of the pass
     * @property recent detected passes among the last [window]
     * @property window number of passes considered
     * @property cooldownLeftMs time until the next announcement is allowed
     * @property announcements announcements since start
     * @property passesPerSecond current pass rate
     */
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
    /** Current state of the monitor ("watching", or the reason it is not), for debug views. */
    val state: StateFlow<String> = _state.asStateFlow()

    private val _debug = MutableStateFlow<DebugSnapshot?>(null)
    val debug: StateFlow<DebugSnapshot?> = _debug.asStateFlow()

    private val debugViewers = AtomicInteger(0)

    /**
     * The camera view wants keypoints / pink mask (its layers). Off (default) = only boxes can be drawn, and the detection builds
     * no drawing data at all.
     */
    @Volatile
    var drawDetailsWanted = false

    @Volatile
    private var activeCamera: CameraStream? = null

    @Volatile
    private var announcements = 0

    /** Newest camera frame of the monitor's stream; null when not watching. */
    fun latestFrame(): CameraFrame? = activeCamera?.latest?.get()

    /**
     * Registers an open camera debug view; [debug] snapshots are published while one is open. Pair with [removeDebugViewer].
     */
    fun addDebugViewer() { debugViewers.incrementAndGet() }

    /** Unregisters a camera debug view; without viewers no debug data is published. */
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

    private val _announceEveryDetection = MutableStateFlow(false)
    /** Test mode for the camera debug view: announce every detected pass instead of once per object. */
    val announceEveryDetection: StateFlow<Boolean> = _announceEveryDetection.asStateFlow()

    /**
     * Test mode: speak on every confirmed detection instead of once per object. Only applies while the camera view is open. Saved.
     */
    fun setAnnounceEveryDetection(on: Boolean) {
        _announceEveryDetection.value = on
        if (::appContext.isInitialized) appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EVERY_DETECTION, on).apply()
        Log.i(TAG, "announce every detection: $on")
    }

    private val _minInliers = MutableStateFlow(CalendarDetectionSettings.MIN_INLIERS)
    /** Inliers a detection needs; set in the camera debug view and saved. */
    val minInliers: StateFlow<Int> = _minInliers.asStateFlow()

    /** Inliers a reference needs to count as found (clamped to the allowed range). Saved; applies from the next pass. */
    fun setMinInliers(value: Int) {
        val v = value.coerceIn(CalendarDetectionSettings.MIN_INLIERS_LOWEST, CalendarDetectionSettings.MIN_INLIERS_HIGHEST)
        _minInliers.value = v
        if (::appContext.isInitialized) appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MIN_INLIERS, v).apply()
        Log.i(TAG, "inliers required $v")
    }

    /** elapsedRealtime until which no announcement is made; kept across stream restarts. */
    @Volatile
    private var cooldownUntil = 0L

    /**
     * Per reference image: its own "in a row" history and whether it has already been announced. Kept at monitor level, so a
     * stream restart does not make an object that is still in view "new" again.
     */
    private class RefState {
        /** Per check of this reference, in frame order: 0 = nothing, 1 = confirm level, 2 = full detection. */
        val history = ArrayDeque<Int>()
        var lastTs = 0L
        /** elapsedRealtime of the last check that found it (confirm level or better). */
        var lastFoundAt = 0L
        /**
         * True once the object was announced; reset after it was out of view for [CalendarDetectionSettings.REARM_AFTER_GONE_MS].
         */
        var announced = false
    }

    private val refStates = HashMap<String, RefState>()

    /** The calendar popup is on screen: nothing is announced meanwhile. */
    @Volatile
    private var popupOpen = false

    /** elapsedRealtime until which nothing is announced after the popup was dismissed (not while the camera view is open). */
    @Volatile
    private var popupCooldownUntil = 0L

    /** Whole-picture results of the latest search, so the camera view can still draw them between searches. */
    @Volatile
    private var lastWhole: Pair<Long, List<ObjectMatch>> = 0L to emptyList()

    /** Choices of the calendar popup. */
    enum class PopupChoice { BLUR, SAVE_DATES, DISMISS }

    /** Called by [CalendarPromptActivity] once, when it closes. Closing without a choice counts as [PopupChoice.DISMISS]. */
    fun popupClosed(choice: PopupChoice) {
        if (!popupOpen) return
        popupOpen = false
        val debugging = debugViewers.get() > 0
        // Owner: Dismiss = no calendar detection for two minutes, but not while trying it out in the camera view.
        if (choice == PopupChoice.DISMISS && !debugging) {
            popupCooldownUntil = SystemClock.elapsedRealtime() + CalendarDetectionSettings.POPUP_COOLDOWN_MS
        }
        Log.i(TAG, "calendar popup: $choice" + when {
            choice != PopupChoice.DISMISS -> " (no action yet)"
            debugging -> " (camera view open: no cooldown)"
            else -> ", no announcement for ${CalendarDetectionSettings.POPUP_COOLDOWN_MS / 1000} s"
        })
    }

    /** Last reason logged by [reevaluate], so the same line is not repeated on every settings change. */
    private var lastReason: String? = "not started"

    private val sensorListener = SensorChangeListener { name, _ ->
        if (name.equals("camera", ignoreCase = true)) reevaluate()
    }

    /** The phone's "Pixelate Objects" switch decides whether objects are detected at all. */
    private val situationalListener = SituationalChangeListener { name, _ ->
        if (name.equals(PIXELATE_OBJECTS, ignoreCase = true)) reevaluate()
    }

    /** Starts watching (idempotent). Call from RobotServerService.onCreate. */
    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        appContext = context.applicationContext
        _minInliers.value = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MIN_INLIERS, CalendarDetectionSettings.MIN_INLIERS)
        _announceEveryDetection.value = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EVERY_DETECTION, false)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Sensors.get(appContext).addListener(sensorListener)
        Sensors.get(appContext).addSituationalListener(situationalListener)
        Log.i(TAG, "started")
        reevaluate()
    }

    /** Stops everything. Call from RobotServerService.onDestroy. */
    @Synchronized
    fun stop() {
        if (scope == null) return
        stopLoop("service stopped")
        runCatching { Sensors.get(appContext).removeListener(sensorListener) }
        runCatching { Sensors.get(appContext).removeSituationalListener(situationalListener) }
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

    /** Checks every condition (Pixelate Objects, camera setting, camera leases) and starts or stops the detection loop. */
    @Synchronized
    private fun reevaluate() {
        if (scope == null) return
        val cameraAllowed = Sensors.get(appContext).getSensors().entries
            .firstOrNull { it.key.equals("camera", ignoreCase = true) }?.value != false
        // Owner, 2026-09-21: object detection is a feature of Pixelate Objects. Off (or never sent by the phone) means
        // the robot does not look for objects — WITHOUT switching the camera itself off; that stays the sensor setting.
        // On unless the phone switched it off (owner 2026-10-19: features are on by default).
        val pixelate = Sensors.get(appContext).isSituationalEnabled(PIXELATE_OBJECTS) != false
        val reason = when {
            !pixelate -> "Pixelate Objects is off in the privacy settings"
            !cameraAllowed -> "camera switched off in the privacy settings"
            probeUsingCamera -> "a test screen is using the camera"
            else -> null
        }
        if (reason != lastReason) {
            lastReason = reason
            Log.i(TAG, reason?.let { "not watching: $it" } ?: "conditions met, watching")
        }
        if (reason == null) startLoop() else {
            stopLoop(reason)
            _state.value = "not watching: $reason"
        }
    }

    /** Starts the loop that opens the camera stream and runs the detection workers; restarts it after errors. */
    private fun startLoop() {
        if (loopJob != null) return
        val s = scope ?: return
        _state.value = "starting (waiting for the robot connection)"
        loopJob = s.launch { watch() }
    }

    /** Stops the detection loop and closes the camera stream. */
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
        DetectionSettings.init(appContext)
        // One ORB + ColorMarker per worker: ORB is synchronized per instance, so parallel passes need their own.
        val workers = ArrayList<Pair<ORB, ColorMarker>>()
        try {
            repeat(CalendarDetectionSettings.WORKERS) {
                val orb = newOrb()
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
            "pink ${if (CalendarDetectionSettings.USE_PINK_MARKER) "on" else "off"}, margin ${CalendarDetectionSettings.MARKER_MARGIN_PX}, " +
            "white inside ${DetectionSettings.pink.requireWhiteInside} " +
            "(≥ ${CalendarDetectionSettings.MIN_WHITE_SHARE}), workers ${workers.size}, " +
            "${CalendarDetectionSettings.CONSISTENCY_NEEDED} of ${CalendarDetectionSettings.CONSISTENCY_WINDOW}, cooldown ${COOLDOWN_MS / 1000} s, " +
            "announce every detection ${_announceEveryDetection.value}")
        val camera = CameraStream()
        activeCamera = camera
        // Dedicated detection threads with raised priority instead of the shared background pool. OpenCV's own helper
        // threads (if its build uses any) are not affected; their count is logged.
        val detectionThreads = java.util.concurrent.Executors.newFixedThreadPool(workers.size) { r ->
            Thread({
                runCatching { android.os.Process.setThreadPriority(CalendarDetectionSettings.WORKER_PRIORITY) }
                    .onFailure { Log.w(TAG, "could not raise the detection thread priority: $it") }
                Log.i(TAG, "detection thread priority ${android.os.Process.getThreadPriority(android.os.Process.myTid())}")
                r.run()
            }, "CalendarDetect")
        }
        val detectionDispatcher = detectionThreads.asCoroutineDispatcher()
        Log.i(TAG, "OpenCV threads: ${runCatching { org.opencv.core.Core.getNumThreads() }.getOrDefault(-1)}")
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
                detectUntilError(workers, camera, detectionDispatcher) { streamError }
                camera.stop()
                if (isActive) delay(5_000)
            }
        } finally {
            activeCamera = null
            detectionDispatcher.close()
            camera.stop()
            workers.forEach { it.first.release() }
            _debug.value = null
        }
    }

    /** Results of the workers, combined in frame order. Guarded by its own lock. */
    private class PassLog {
        // For the 2 s summary.
        var passes = 0; var detected = 0; var msSum = 0L; var colourMsSum = 0L; var regions = 0
        var rejectedFrame = 0; var rejectedWhite = 0; var bestInliers = 0; var outOfOrder = 0
        var skipped = 0
        /** Pink search step sums (ms) over all passes: colour image, colour ranges, cleaning + grouping, regions + shapes. */
        val pinkStages = LongArray(4)
        var previewMsSum = 0L; var regionPasses = 0; var regionMsSum = 0L; val orb = ORB.Timings()
        /** Per reference image, over passes with a pink area: inlier sum, best, passes where it was the strongest. */
        val refInliers = HashMap<String, IntArray>()
        /** Frame → decision delay over the window (ms): sum, worst, number of passes. */
        var delaySum = 0L; var delayMax = 0L; var delayPasses = 0
        /** Reference name → elapsedRealtime of its last full detection, for the "seen"/"gone" lines of the timeline. */
        val lastSeen = HashMap<String, Long>()
        /** Whole-picture searches in the window: count, time sum, ORB stage times. */
        var wholeSearches = 0; var wholeMsSum = 0L; val wholeOrb = ORB.Timings()
    }

    /** Runs the detection workers on the newest frames until the stream fails, and logs a summary every 2 s. */
    private suspend fun detectUntilError(
        workers: List<Pair<ORB, ColorMarker>>, camera: CameraStream, dispatcher: kotlinx.coroutines.CoroutineDispatcher, failed: () -> Boolean
    ) = coroutineScope {
        val taken = AtomicLong(0)
        val orbGate = java.util.concurrent.Semaphore(1)
        val nextStart = AtomicLong(0)
        val startInterval = 1000L / CalendarDetectionSettings.MAX_PASSES_PER_SECOND
        val nextWholeDue = AtomicLong(0)
        val log = PassLog()
        val jobs = workers.map { (orb, marker) ->
            launch(dispatcher) {
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
                    val drawDetails = debugging && drawDetailsWanted
                    orb.collectDrawData = drawDetails
                    val t0 = SystemClock.elapsedRealtime()
                    val pass = try {
                        // Run with the lower confirm threshold; onPass decides whether a result is a full or a confirm-level detection.
                        detectionPass(orb, marker, f, drawDetails, orbGate)
                    } catch (e: Exception) {
                        Log.w(TAG, "detection failed: $e")
                        continue
                    }
                    onPass(log, pass, f.timestampMs, SystemClock.elapsedRealtime() - t0, debugging)
                    // References without a pink frame are searched in the whole picture on their own, slower cadence and only
                    // after the calendar's decision is out. Before, this ran on every pass in both workers at once, outside the
                    // ORB gate, and every calendar decision waited for it.
                    val whole = wholePictureNames(orb)
                    val due = nextWholeDue.get()
                    val now = SystemClock.elapsedRealtime()
                    if (whole.isNotEmpty() && !pass.skipped && now >= due &&
                        nextWholeDue.compareAndSet(due, now + CalendarDetectionSettings.WHOLE_FRAME_INTERVAL_MS)) {
                        if (orbGate.tryAcquire()) {
                            try {
                                val w0 = SystemClock.elapsedRealtime()
                                orb.takeTimings()
                                val extra = runCatching { orb.evaluate(f.gray, whole) }.getOrElse { emptyList() }
                                onWholePass(log, extra, whole, f.timestampMs, SystemClock.elapsedRealtime() - w0, orb.takeTimings())
                            } finally {
                                orbGate.release()
                            }
                        } else {
                            nextWholeDue.set(0) // the other worker runs ORB: let the next free one do the search
                        }
                    }
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
            synchronized(log) {
                for (name in log.lastSeen.keys.toList()) {
                    if (now - (log.lastSeen[name] ?: 0L) > SEEN_HOLD_MS) {
                        log.lastSeen.remove(name)
                        Log.i(TAG, "gone: $name")
                    }
                }
            }
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
                    if (log.delayPasses > 0) {
                        Log.i(TAG, "delay frame→decision: avg ${log.delaySum / log.delayPasses} ms, worst ${log.delayMax} ms over ${log.delayPasses} passes")
                    }
                    if (log.wholeSearches > 0) {
                        val w = log.wholeSearches
                        Log.i(TAG, "whole picture: %d searches, avg %d ms = keypoints %.0f + knn match %.0f + homography %.0f ms".format(
                            w, log.wholeMsSum / w, log.wholeOrb.extractNs / 1e6 / w, log.wholeOrb.knnNs / 1e6 / w,
                            log.wholeOrb.homographyNs / 1e6 / w))
                    }
                    log.wholeSearches = 0; log.wholeMsSum = 0
                    log.wholeOrb.resizeNs = 0; log.wholeOrb.extractNs = 0; log.wholeOrb.knnNs = 0; log.wholeOrb.ratioNs = 0; log.wholeOrb.homographyNs = 0
                    log.delaySum = 0; log.delayMax = 0; log.delayPasses = 0
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
                    val ps = log.pinkStages
                    Log.i(TAG, "pink search per pass: colour image %.1f + colour ranges %.1f + cleaning/grouping %.1f + regions/shapes %.1f ms".format(
                        ps[0].toDouble() / n, ps[1].toDouble() / n, ps[2].toDouble() / n, ps[3].toDouble() / n))
                    ps.fill(0)
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

    private val orbFeatures get() = CalendarDetectionSettings.orb.maxFeatures

    /** Inliers a pass needs to confirm a previous full detection: X − [CalendarDetectionSettings.CONFIRM_INLIER_DROP]. */
    fun confirmInliers(): Int = (_minInliers.value - CalendarDetectionSettings.CONFIRM_INLIER_DROP).coerceAtLeast(CalendarDetectionSettings.MIN_INLIERS_LOWEST)

    /**
     * ORB with the settings from assets/settings/settings.json: general ones for every reference, the per-image section
     * where one exists, and images with `enabled: false` are not loaded at all.
     */
    private fun newOrb(): ORB = ORB(
        appContext,
        CalendarDetectionSettings.orb,
        configFor = { name -> DetectionSettings.forImage(name).toOrbConfig() },
        skipReference = { name -> !DetectionSettings.isEnabled(name) }
    )

    /**
     * Good matches and inliers required for one reference.
     * @param name name of the reference
     * @return pair of (good matches, inliers)
     */
    private fun thresholdsFor(name: String): Pair<Int, Int> {
        val settings = DetectionSettings.forImage(name)
        val full = if (settings.minInliers == DetectionSettings.general.minInliers) _minInliers.value else settings.minInliers
        return settings.minGoodMatches to (full - settings.confirmInlierDrop).coerceAtLeast(ObjectSettings.ORB_MIN_INLIERS)
    }

    /** References searched inside pink frames (the calendar). */
    private fun pinkGatedNames(orb: ORB): List<String> =
        if (!CalendarDetectionSettings.USE_PINK_MARKER) emptyList()
        else orb.classNames.filter { DetectionSettings.forImage(it).usePinkMarker }

    /** References whose settings say `usePinkMarker: false`: searched in the whole camera picture (slower, own cadence). */
    private fun wholePictureNames(orb: ORB): List<String> {
        val gated = pinkGatedNames(orb)
        return orb.classNames.filter { it !in gated }
    }

    /** One check of the pink-gated references. The whole-picture ones run separately, see the worker loop. */
    private fun detectionPass(orb: ORB, marker: ColorMarker, frame: CameraFrame, drawDetails: Boolean, orbGate: java.util.concurrent.Semaphore): MarkerPass {
        val gated = pinkGatedNames(orb)
        if (gated.isEmpty()) return MarkerPass(emptyList(), null, emptyList(), 0, emptyList())
        return markerGatedPass(orb, marker, frame, ::thresholdsFor, withOverlay = drawDetails, orbGate = orbGate, classNames = gated)
    }

    /** Inliers that count as a full detection for [name] (per image, else the value from the camera view). */
    private fun fullInliersFor(name: String): Int {
        val settings = DetectionSettings.forImage(name)
        return if (settings.minInliers == DetectionSettings.general.minInliers) _minInliers.value else settings.minInliers
    }

    /** 2 = full detection, 1 = only enough to confirm a previous full detection, 0 = nothing. */
    private fun levelOf(r: ObjectMatch?): Int = when {
        r == null || !r.detected -> 0
        r.inliers >= fullInliersFor(r.className) -> 2
        else -> 1
    }

    /**
     * Processes one finished pass: per-reference history, seen/gone transitions, announcements or the calendar popup,
     * debug data and statistics.
     * @param log statistics of the current 2 s window
     * @param pass result of the pass
     * @param frameTs capture time of the frame
     * @param ms duration of the pass
     * @param debugging true while the camera debug view is open
     */
    private fun onPass(log: PassLog, pass: MarkerPass, frameTs: Long, ms: Long, debugging: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (pass.skipped) {
            // Another worker is running ORB on a newer or same-age frame: not a miss, so it must not enter the history.
            synchronized(log) { log.skipped++ }
            return
        }
        // One line per ORB run on a pink area (numbers only), for inliers-vs-distance analysis (performance/plot.py).
        for (c in pass.checks) {
            Log.i(TAG, "check: frame ${c.frameSide} px, scale %.2f, keypoints ${c.keypoints}, good ${c.goodMatches}, inliers ${c.inliers}, features ${orbFeatures}".format(c.scale))
        }
        // How old the picture was when the decision was made: capture → result. This is the delay the owner sees, and it
        // is NOT the same as the time one pass takes, because a frame waits while the workers are busy.
        val delayMs = now - frameTs
        synchronized(log) {
            log.delaySum += delayMs; log.delayMax = maxOf(log.delayMax, delayMs); log.delayPasses++
            log.passes++; log.msSum += ms; log.colourMsSum += pass.marker?.millis ?: 0
            log.previewMsSum += pass.previewMs
            pass.marker?.stageMs?.forEachIndexed { i, v -> log.pinkStages[i] += v }
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
            if (pass.results.any { levelOf(it) == 2 }) log.detected++
        }
        decide(log, pass.results, pass.results.map { it.className }, frameTs, delayMs, pass.regions.size, debugging)
        if (debugging) {
            // Whole-picture results are drawn as long as they are fresh (they are searched only every few hundred ms).
            val (wholeAt, whole) = lastWhole
            val shown = if (whole.isNotEmpty() && now - wholeAt <= 2 * CalendarDetectionSettings.WHOLE_FRAME_INTERVAL_MS) {
                MarkerPass(pass.results + whole, pass.marker, pass.regions, pass.keypoints, pass.keypointPositions,
                    pass.previewMs, pass.orbTimings, pass.skipped, pass.checks)
            } else pass
            val (recent, window) = synchronized(refStates) {
                refStates.values.maxByOrNull { st -> st.history.count { it > 0 } }
                    ?.let { st -> st.history.count { it > 0 } to st.history.size } ?: (0 to 0)
            }
            _debug.value = DebugSnapshot(shown, ms, recent, window, (cooldownUntil - now).coerceAtLeast(0), announcements, passesPerSecond)
        }
    }

    /** A whole-picture search finished (references without a pink frame). */
    private fun onWholePass(log: PassLog, results: List<ObjectMatch>, searched: List<String>, frameTs: Long, ms: Long, timings: ORB.Timings) {
        val now = SystemClock.elapsedRealtime()
        lastWhole = now to results
        synchronized(log) { log.wholeSearches++; log.wholeMsSum += ms; log.wholeOrb.add(timings) }
        decide(log, results, searched, frameTs, now - frameTs, 0, debugGate = debugViewers.get() > 0)
    }

    /**
     * Updates the history of each searched reference and decides whether to announce. An object is announced once when it
     * comes into view and again after it was out of view for [CalendarDetectionSettings.REARM_AFTER_GONE_MS]; no announcement
     * while the calendar popup is open or during [CalendarDetectionSettings.POPUP_COOLDOWN_MS] after it was dismissed.
     * @param log statistics of the current 2 s window
     * @param results result per reference
     * @param searched references searched in this pass
     * @param frameTs capture time of the frame
     * @param delayMs time from capture to decision
     * @param regions number of pink areas found
     * @param debugGate true while the camera debug view is open; disables the once-per-object and popup cooldown rules
     */
    private fun decide(
        log: PassLog, results: List<ObjectMatch>, searched: List<String>, frameTs: Long, delayMs: Long, regions: Int,
        debugGate: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        val everyDetection = debugGate && _announceEveryDetection.value
        var chosen: ObjectMatch? = null
        synchronized(refStates) {
            for (name in searched) {
                val r = results.firstOrNull { it.className == name }
                val level = levelOf(r)
                val st = refStates.getOrPut(name) { RefState() }
                // Workers can finish out of order; an older frame does not count for "in a row".
                if (frameTs < st.lastTs) {
                    synchronized(log) { log.outOfOrder++ }
                    continue
                }
                st.lastTs = frameTs
                if (level > 0) st.lastFoundAt = now
                if (st.announced && now - st.lastFoundAt >= CalendarDetectionSettings.REARM_AFTER_GONE_MS) {
                    st.announced = false
                    Log.i(TAG, "$name out of view for ${CalendarDetectionSettings.REARM_AFTER_GONE_MS / 1000} s: may be announced again")
                }
                st.history.addLast(level)
                while (st.history.size > CalendarDetectionSettings.CONSISTENCY_WINDOW) st.history.removeFirst()
                if (level == 2) synchronized(log) {
                    // One line when an object starts being seen, one when it has not been seen for SEEN_HOLD_MS (in the
                    // 200 ms loop), so the graphs can draw a bar for "this object is in view".
                    if (log.lastSeen.put(name, now) == null) Log.i(TAG, "seen: $name (inliers ${r?.inliers}, delay $delayMs ms)")
                }
                // Stepped confirmation (owner): the first check of the window needs a full detection, the following ones
                // only the confirm level, all in a row.
                val confirmed = st.history.size >= CalendarDetectionSettings.CONSISTENCY_WINDOW && st.history.first() == 2 &&
                    st.history.count { it > 0 } >= CalendarDetectionSettings.CONSISTENCY_NEEDED
                val wanted = if (everyDetection) level == 2 && !isSpeaking(now)
                else confirmed && now >= cooldownUntil && (debugGate || (!st.announced && now >= popupCooldownUntil))
                if (wanted && !popupOpen && r != null && (chosen == null || r.inliers > chosen!!.inliers)) chosen = r
            }
            val pick = chosen ?: return
            val st = refStates.getValue(pick.className)
            st.announced = true
            st.history.clear()
            if (!everyDetection) cooldownUntil = now + COOLDOWN_MS
            announcements++
            speakingSince = now
        }
        val pick = chosen ?: return
        val popup = DetectionSettings.forImage(pick.className).calendarPopup
        Log.i(TAG, "object detected (${if (everyDetection) "every detection" else "in a row"}): " +
            "${pick.className} good ${pick.goodMatches}, inliers ${pick.inliers}, pink regions $regions, delay $delayMs ms" +
            if (popup) ", showing the calendar popup" else "")
        if (popup) showPopup() else scope?.launch { speak(sentenceFor(pick.className)) }
    }

    /** Opens the calendar popup and speaks the calendar question. */
    private fun showPopup() {
        popupOpen = true
        runCatching {
            appContext.startActivity(Intent(appContext, CalendarPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Log.e(TAG, "could not show the calendar popup", it)
            popupOpen = false
        }
        // The popup brings RoboGuard to the front, so speech waits for SDK control like the conversation prompt does.
        scope?.launch { speak(UiText.get("speech.calendar_question"), waitForControlMs = 3_000) }
    }

    /** elapsedRealtime when the last announcement started; 0 = not speaking. Cleared when speech ends or fails. */
    @Volatile
    private var speakingSince = 0L

    /** Speaking, with a safety timeout in case the speech service never reports the end. */
    private fun isSpeaking(now: Long) = speakingSince != 0L && now - speakingSince < 6_000

    /** Speaks if RoboGuard has SDK control; never brings a screen to the front and never throws. */
    private suspend fun speak(sentence: String, waitForControlMs: Long = 500) {
        try {
            if (!SdkControl.awaitControl(appContext, timeoutMs = waitForControlMs)) {
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
            val started = speech.speakConfigured(sentence, object : TtsListener {
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
