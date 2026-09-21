package com.example.testing.objectprobe

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.robocontrol.system.RobotApiConnection
import com.example.robocontrol.vision.CameraFrame
import com.example.robocontrol.vision.CalendarDetectionSettings
import com.example.robocontrol.vision.CalendarMonitor
import com.example.robocontrol.vision.CameraStream
import com.example.robocontrol.vision.markerGatedPass
import com.example.robocontrol.vision.ColorMarker
import com.example.robocontrol.vision.ImagePoint
import com.example.robocontrol.vision.MarkerColor
import com.example.robocontrol.vision.MarkerRegion
import com.example.robocontrol.vision.ORB
import com.example.robocontrol.vision.ObjectMatch
import com.example.testing.voiceprobe.ProbeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * Test screen for ORB object detection ("RG Object Test" launcher icon). Not part of RoboGuard's UI.
 *
 * Shows the robot's camera live (SurfaceShare stream, so RobotOS vision keeps working) and runs [ORB] on the latest frame
 * a few times per second against every reference image in `robocontrol/assets`. A detected object gets its projected
 * outline (green) and an axis-aligned bounding box (yellow) with name and inlier count. Frames are only in memory; nothing
 * is saved. Procedure: README.md, "Testing object detection".
 */
class ObjectProbeActivity : ComponentActivity() {

    private lateinit var log: ProbeLog
    private val camera = CameraStream()
    private var orb: ORB? = null

    private val frame = MutableStateFlow<CameraFrame?>(null)
    private val matches = MutableStateFlow<List<ObjectMatch>>(emptyList())

    /**
     * Boxes on screen: the detections of the latest pass (full frame, merged with the latest tile pass). They stay drawn until
     * the next pass has been computed; a pass without detection removes them.
     */
    private val boxes = MutableStateFlow<List<ObjectMatch>>(emptyList())
    private val status = MutableStateFlow("loading references…")
    private val running = MutableStateFlow(false)
    private val rotation = MutableStateFlow(0)
    private val stats = MutableStateFlow("")
    private val brightness = MutableStateFlow("")
    private val contrastBoost = MutableStateFlow(false)
    /** Start values from the wall test (2026-09-17): more features, weaker corners allowed, keypoints spread over a grid. */
    private val orbSettings = MutableStateFlow(CalendarDetectionSettings.orb)

    /** B: additionally search enlarged, overlapping tiles about once per second (for small, distant objects). */
    private val tilesOn = MutableStateFlow(true)

    /** E: a box appears only if the object was detected in enough of the last passes (CalendarDetectionSettings, now 3 of 4). */
    private val consistencyOn = MutableStateFlow(true)

    /**
     * Pink marker: search features ONLY in areas close to pink pixels (the pink line drawn around the calendar). Replaces the
     * full-frame and tile passes while on. A match there counts with enough inliers even if its projected outline is folded
     * (lens distortion); the box is then the pink pixels' bounds.
     */
    private val markerOn = MutableStateFlow(true)
    private val markerMargin = MutableStateFlow(CalendarDetectionSettings.MARKER_MARGIN_PX)
    private val markerSaturation = MutableStateFlow(com.example.robocontrol.vision.DetectionSettings.pink.minSaturation)
    private val markerWhiteBalance = MutableStateFlow(com.example.robocontrol.vision.DetectionSettings.pink.whiteBalance)
    private val markerRequireFrame = MutableStateFlow(com.example.robocontrol.vision.DetectionSettings.pink.requireFrame)
    private val markerWhiteInside = MutableStateFlow(com.example.robocontrol.vision.DetectionSettings.pink.requireWhiteInside)
    private val markerRegions = MutableStateFlow<List<MarkerRegion>>(emptyList())

    /** Green mask of the pink pixels found in the latest pink pass (frame size), or null. */
    private val markerOverlay = MutableStateFlow<Bitmap?>(null)

    /** Keypoints of the latest detection pass (frame coordinates), drawn as small dots. */
    private val featurePoints = MutableStateFlow<List<ImagePoint>>(emptyList())
    /** Which drawing layers the preview shows; each has its own toggle. */
    private val layers = MutableStateFlow(Layers())
    private val marker = ColorMarker()

    /** Detection thresholds (good matches / inliers); relaxed values are meant to be combined with [consistencyOn]. */
    private val thresholds = MutableStateFlow(CalendarDetectionSettings.MIN_GOOD_MATCHES to CalendarDetectionSettings.MIN_INLIERS)

    /** Region selection on the preview, in frame pixels; null = not selecting. */
    private val selecting = MutableStateFlow(false)
    private val selection = MutableStateFlow<android.graphics.Rect?>(null)

    /** Where captured references are kept on the robot (app-private), so they survive restarts. */
    private val captureDir get() = java.io.File(filesDir, "robocontrol/orb_refs")
    private val references = MutableStateFlow<List<String>>(emptyList())

    /** Changes ORB detector settings (re-extracts the references off the main thread) and logs the new reference keypoints. */
    private fun changeOrb(label: String, change: (com.example.robocontrol.vision.OrbConfig) -> com.example.robocontrol.vision.OrbConfig) {
        val o = orb ?: return
        val next = change(orbSettings.value)
        orbSettings.value = next
        lifecycleScope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val counts = o.applySettings(next.copy(contrastBoost = contrastBoost.value))
            references.value = o.classNames.toList()
            log.i(TAG, "setting $label: features ${next.maxFeatures}, FAST ${next.fastThreshold}, grid ${if (next.gridDistribution) "on" else "off"} " +
                "→ references re-extracted in ${SystemClock.elapsedRealtime() - t0} ms: " + counts.entries.joinToString { "${it.key} ${it.value} keypoints" })
        }
    }

    /** Uses the newest camera frame as a reference, so reference and frames share lens and lighting. Kept in memory only. */
    private fun captureReference() {
        val o = orb ?: return
        val f = camera.latest.get() ?: return log.i(TAG, "no camera frame yet")
        val rect = selection.value
        lifecycleScope.launch(Dispatchers.Default) {
            val gray = if (rect == null) f.gray else cropGray(f.gray, rect)
            val ref = o.addReference(CAMERA_REFERENCE, gray)
            references.value = o.classNames.toList()
            if (ref == null) {
                log.i(TAG, "selected region (${gray.width}×${gray.height}) has too few features for a reference")
                return@launch
            }
            // Saved as a grayscale PNG in app-private storage (only the selected region, e.g. just the calendar).
            val saved = runCatching {
                captureDir.mkdirs()
                val file = java.io.File(captureDir, "$CAMERA_REFERENCE.png")
                grayToBitmap(gray).let { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                file
            }.getOrNull()
            log.i(TAG, "reference $CAMERA_REFERENCE from the camera: region ${gray.width}×${gray.height} px, ${ref.keypointCount} keypoints" +
                (saved?.let { " · saved to ${it.path}" } ?: " · NOT saved"))
            selecting.value = false
            selection.value = null
        }
    }

    private fun cropGray(g: com.example.robocontrol.vision.GrayImage, r: android.graphics.Rect): com.example.robocontrol.vision.GrayImage {
        val x0 = r.left.coerceIn(0, g.width - 1); val y0 = r.top.coerceIn(0, g.height - 1)
        val w = (r.right.coerceIn(x0 + 1, g.width) - x0); val h = (r.bottom.coerceIn(y0 + 1, g.height) - y0)
        val out = ByteArray(w * h)
        for (row in 0 until h) System.arraycopy(g.pixels, (y0 + row) * g.width + x0, out, row * w, w)
        return com.example.robocontrol.vision.GrayImage(w, h, out)
    }

    private fun grayToBitmap(g: com.example.robocontrol.vision.GrayImage): Bitmap {
        val px = IntArray(g.width * g.height) { val v = g.pixels[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, g.width, g.height, Bitmap.Config.ARGB_8888)
    }

    /** Adds references captured on earlier runs (files in [captureDir]). */
    private fun loadSavedCaptures(o: ORB) {
        captureDir.listFiles { f -> f.name.endsWith(".png") }?.forEach { file ->
            val bmp = android.graphics.BitmapFactory.decodeFile(file.path) ?: return@forEach
            val ref = o.addReference(file.nameWithoutExtension, bmp)
            log.i(TAG, "  saved capture ${file.nameWithoutExtension}: ${bmp.width}×${bmp.height}, ${ref?.keypointCount ?: 0} keypoints")
        }
    }

    private var cameraJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = ProbeLog(applicationContext)
        log.i("probe", "log file: ${log.filePath}")
        lifecycleScope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            runCatching { ORB(applicationContext, orbSettings.value) }
                .onSuccess { o ->
                    orb = o
                    val report = o.assetLoadReport
                    log.i(TAG, "ORB ready after ${SystemClock.elapsedRealtime() - t0} ms: references ${report.loaded}")
                    report.loaded.forEach { name ->
                        val ref = o[name]
                        log.i(TAG, "  $name: ${ref?.keypointCount} keypoints, reference ${ref?.image?.width}×${ref?.image?.height}")
                    }
                    report.rejected.forEach { (file, reason) -> log.i(TAG, "  rejected $file: $reason") }
                    log.i(TAG, "settings: features ${o.config.maxFeatures}, FAST ${o.config.fastThreshold}, grid ${if (o.config.gridDistribution) "on" else "off"}")
                    loadSavedCaptures(o)
                    references.value = o.classNames.toList()
                    status.value = if (report.loaded.isEmpty()) "no reference images found in assets" else "ready: ${report.loaded.joinToString()}"
                }
                .onFailure {
                    log.i(TAG, "ORB failed: $it")
                    status.value = "ORB failed: $it"
                }
        }
        setContent { MaterialTheme { Screen() } }
    }

    override fun onStop() {
        stopCamera("screen left")
        super.onStop()
    }

    override fun onDestroy() {
        orb?.release()
        super.onDestroy()
    }

    private fun startCamera() {
        if (cameraJob != null) return
        val cameraAllowed = Sensors.get(applicationContext).getSensors().entries.firstOrNull { it.key.equals("camera", ignoreCase = true) }?.value
        if (cameraAllowed == false) {
            log.i(TAG, "NOT STARTED: the camera is switched off in RoboGuard's privacy settings")
            status.value = "camera switched off in the privacy settings"
            return
        }
        running.value = true
        // The background calendar monitor streams the camera too; one SurfaceShare consumer at a time.
        CalendarMonitor.setProbeUsingCamera(true)
        cameraJob = lifecycleScope.launch(Dispatchers.Default) {
            RobotApiConnection.connect(applicationContext)
            if (withTimeoutOrNull(5_000) { RobotApiConnection.connected.first { it } } == null) {
                log.i(TAG, "RobotApi not connected (start this screen from the home launcher)")
                status.value = "RobotApi not connected"
                running.value = false
                cameraJob = null
                CalendarMonitor.setProbeUsingCamera(false)
                return@launch
            }
            val code = camera.start { error, message ->
                log.i(TAG, "camera stream error $error: $message" + if (error == -15) " (camera blocked by the privacy device policy?)" else "")
                status.value = "camera error $error"
            }
            log.i(TAG, "camera stream requested: code $code")
            if (code < 0) {
                status.value = "camera request failed: $code"
                running.value = false
                cameraJob = null
                CalendarMonitor.setProbeUsingCamera(false)
                return@launch
            }
            // Preview and detection run separately: the preview must not wait for ORB.
            launch { previewLoop() }
            detectLoop()
        }
    }

    private fun stopCamera(reason: String) {
        val job = cameraJob ?: return
        job.cancel()
        cameraJob = null
        camera.stop()
        running.value = false
        frame.value = null
        matches.value = emptyList()
        boxes.value = emptyList()
        markerRegions.value = emptyList()
        markerOverlay.value = null
        featurePoints.value = emptyList()
        CalendarMonitor.setProbeUsingCamera(false)
        log.i(TAG, "camera stopped ($reason)")
    }

    /** Pushes the newest camera frame to the screen ~30 times per second, independent of detection. */
    private suspend fun kotlinx.coroutines.CoroutineScope.previewLoop() {
        var shown = 0L
        var lastTs = 0L
        var windowStart = SystemClock.elapsedRealtime()
        var shownInWindow = 0
        while (isActive) {
            val f = camera.latest.get()
            if (f != null && f.timestampMs != lastTs) {
                lastTs = f.timestampMs
                frame.value = f
                shown++
                shownInWindow++
                brightness.value = "${f.gray.width}×${f.gray.height} · brightness %.0f/255 · contrast %.0f · overexposed %.0f %%".format(
                    f.meanBrightness, f.contrast, f.clippedShare * 100)
            }
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 2_000) {
                previewFps = shownInWindow * 1000.0 / (now - windowStart)
                shownInWindow = 0
                windowStart = now
            }
            delay(30)
        }
    }

    @Volatile
    private var previewFps = 0.0

    /** Runs ORB on the newest frame as often as it can keep up; logs a numbers-only summary every 2 s. */
    private suspend fun kotlinx.coroutines.CoroutineScope.detectLoop() {
        val detectedBefore = mutableSetOf<String>()
        var detections = 0
        var sumMs = 0L
        var lastSummary = SystemClock.elapsedRealtime()
        var camFramesAtSummary = camera.framesReceived
        var lastTs = 0L
        val history = HashMap<String, ArrayDeque<Boolean>>()         // class → detected in the last 3 passes
        var lastTilesAt = 0L
        var lastTileResult: List<ObjectMatch> = emptyList()
        var tilePasses = 0
        var tileMsSum = 0L
        while (isActive) {
            val f = camera.latest.get()
            val o = orb
            if (f == null || o == null || f.timestampMs == lastTs) {
                delay(20)
                continue
            }
            lastTs = f.timestampMs
            val (minGood, minInl) = thresholds.value
            o.config = o.config.copy(contrastBoost = contrastBoost.value, minGoodMatches = minGood, minInliers = minInl)
            val t0 = SystemClock.elapsedRealtime()
            val pinkMode = markerOn.value
            var result = if (pinkMode) {
                markerPass(o, f, minGood, minInl).also { markerStats = it.second }.first
            } else {
                markerRegions.value = emptyList()
                markerOverlay.value = null
                runCatching { o.evaluate(f.gray) }.getOrElse {
                    log.i(TAG, "evaluate failed: $it")
                    emptyList()
                }.also { featurePoints.value = o.lastKeypointPositions }
            }
            val ms = SystemClock.elapsedRealtime() - t0
            // B: tiles about once per second; per object the better of full frame and tiles counts.
            if (!pinkMode && tilesOn.value && t0 - lastTilesAt >= TILE_INTERVAL_MS) {
                lastTilesAt = t0
                val tStart = SystemClock.elapsedRealtime()
                lastTileResult = runCatching { o.evaluateTiles(f.gray) }.getOrElse { log.i(TAG, "tiles failed: $it"); emptyList() }
                tilePasses++
                tileMsSum += SystemClock.elapsedRealtime() - tStart
                val byName = lastTileResult.associateBy { it.className }
                for (m in result) byName[m.className]?.takeIf { it.detected && !m.detected }?.let {
                    log.i(TAG, "tiles found ${it.className}: good ${it.goodMatches}, inliers ${it.inliers} (full frame: good ${m.goodMatches}, inliers ${m.inliers})")
                }
            }
            if (pinkMode || !tilesOn.value) lastTileResult = emptyList()
            // The latest tile result counts until the next tile pass replaces it (tiles run only every TILE_INTERVAL_MS).
            if (lastTileResult.isNotEmpty()) {
                val byName = lastTileResult.associateBy { it.className }
                result = result.map { m -> byName[m.className]?.takeIf { better(it, m) } ?: m }
            }
            detections++
            sumMs += ms
            matches.value = result
            val nowMs = SystemClock.elapsedRealtime()
            // E: an object counts as present if detected in ≥ CONSISTENCY_NEEDED of the last CONSISTENCY_WINDOW passes (when enabled).
            val confirmed = result.associate { m ->
                val h = history.getOrPut(m.className) { ArrayDeque() }
                h.addLast(m.detected)
                while (h.size > CalendarDetectionSettings.CONSISTENCY_WINDOW) h.removeFirst()
                m.className to if (consistencyOn.value) h.count { it } >= CalendarDetectionSettings.CONSISTENCY_NEEDED else m.detected
            }
            // Boxes = exactly what this pass computed (owner): a box stays on screen until the next pass, which may remove it.
            boxes.value = result.filter { it.detected && confirmed[it.className] == true }
            val perRef = result.joinToString {
                "${it.className}: good ${it.goodMatches}, inliers ${it.inliers}" +
                    (if (it.detected) " DETECTED${if (it.outlineSource == "marker") " (pink box)" else ""}" else "") +
                    (it.outlineRejected?.let { r -> " [outline $r]" } ?: "")
            }
            val pinkText = if (pinkMode) "$markerStats · " else ""
            stats.value = "${pinkText}keypoints ${o.lastFrameKeypoints} · detection $ms ms · $perRef"
            for (m in result) {
                val present = confirmed[m.className] == true
                if (present && detectedBefore.add(m.className)) log.i(TAG, "DETECTED ${m.className}: good ${m.goodMatches}, inliers ${m.inliers}")
                if (!present && detectedBefore.remove(m.className)) log.i(TAG, "lost ${m.className} (good ${m.goodMatches}, inliers ${m.inliers})" +
                    if (pinkMode) " · $markerStats" else "")
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastSummary >= 2_000) {
                val camFps = (camera.framesReceived - camFramesAtSummary) * 1000.0 / (now - lastSummary)
                val cfg = o.config
                log.i(TAG, (if (pinkMode) "pink marker ON (margin ${markerMargin.value} px, min saturation ${markerSaturation.value}, white balance ${onOff(markerWhiteBalance.value)}, frame ${onOff(markerRequireFrame.value)}, white inside ${onOff(markerWhiteInside.value)}): $markerStats · " else "") +
                    "${f.gray.width}×${f.gray.height} camera %.1f fps, preview %.1f fps, detection %.1f/s (avg %d ms) · brightness %.0f, contrast %.0f, overexposed %.0f%% · boost %s, features %d, FAST %d, grid %s, thresholds %d/%d, consistency %s, tiles %s · keypoints %d · %s".format(
                    camFps, previewFps, detections * 1000.0 / (now - lastSummary), sumMs / detections.coerceAtLeast(1),
                    f.meanBrightness, f.contrast, f.clippedShare * 100, if (contrastBoost.value) "on" else "off", cfg.maxFeatures, cfg.fastThreshold,
                    if (cfg.gridDistribution) "on" else "off", cfg.minGoodMatches, cfg.minInliers, if (consistencyOn.value) "on" else "off",
                    if (tilesOn.value) "on (%d passes, avg %d ms)".format(tilePasses, tileMsSum / tilePasses.coerceAtLeast(1)) else "off",
                    o.lastFrameKeypoints, perRef))
                tilePasses = 0
                tileMsSum = 0
                lastSummary = now
                camFramesAtSummary = camera.framesReceived
                detections = 0
                sumMs = 0
            }
        }
    }

    @Volatile
    private var markerStats = ""

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /**
     * Pink-marker pass: find pink areas, run ORB only inside each (enlarged), keep the best result per reference.
     * Accepted: a plausible outline whose centre lies in the pink area, or (lens distortion folds outlines) enough good matches
     * and inliers inside the pink area; the box is then the pink pixels' bounds ("marker").
     * @return results per reference and a numbers-only description
     */
    private fun markerPass(o: ORB, f: CameraFrame, minGood: Int, minInl: Int): Pair<List<ObjectMatch>, String> {
        marker.marginPx = markerMargin.value
        marker.color = MarkerColor.PINK.copy(saturationMin = markerSaturation.value)
        marker.whiteBalance = markerWhiteBalance.value
        marker.requireFrame = markerRequireFrame.value
        marker.requireWhiteInside = markerWhiteInside.value
        val pass = markerGatedPass(o, marker, f, { minGood to minInl }, MAX_MARKER_REGIONS, withOverlay = layers.value.pinkMask)
        val found = pass.marker
        val regions = pass.regions
        markerRegions.value = regions
        markerOverlay.value = found?.overlay
        featurePoints.value = pass.keypointPositions
        val text = "pink ${"%.2f".format((found?.pinkShare ?: 0.0) * 100)} %, regions ${regions.size}" +
            regions.joinToString("", " [", "]") { " ${it.bounds.width}×${it.bounds.height}/${it.pinkPixels}px/${it.sides} sides${if (it.held) " held" else ""}/white ${(it.whiteShare * 100).toInt()}%" }.takeIf { regions.isNotEmpty() }.orEmpty() +
            found?.rejected.orEmpty().let { r -> if (r.isEmpty()) "" else ", rejected ${r.size} [" + r.take(4).joinToString { "${it.bounds.width}×${it.bounds.height}/${it.pinkPixels}px/" +
                if (it.rejectReason == "white") "white ${(it.whiteShare * 100).toInt()}%" else "sides " + it.sideCoverage.joinToString("/") { c -> "%.0f".format(c * 100) } + "%" } + "]" } +
            " · colour ${found?.millis ?: 0} ms · region keypoints ${pass.keypoints}"
        return pass.results to text
    }

    @Composable
    private fun Screen() {
        val lines by log.lines.collectAsState()
        val f by frame.collectAsState()
        val found by boxes.collectAsState()
        val statusText by status.collectAsState()
        val isRunning by running.collectAsState()
        val rot by rotation.collectAsState()
        val statsText by stats.collectAsState()
        val brightnessText by brightness.collectAsState()
        val boost by contrastBoost.collectAsState()
        val settings by orbSettings.collectAsState()
        val refs by references.collectAsState()
        val isSelecting by selecting.collectAsState()
        val thr by thresholds.collectAsState()
        val consistency by consistencyOn.collectAsState()
        val tiles by tilesOn.collectAsState()
        val sel by selection.collectAsState()
        val pinkOn by markerOn.collectAsState()
        val pinkMargin by markerMargin.collectAsState()
        val pinkSat by markerSaturation.collectAsState()
        val pinkWb by markerWhiteBalance.collectAsState()
        val pinkFrame by markerRequireFrame.collectAsState()
        val pinkWhite by markerWhiteInside.collectAsState()
        val pinkRegions by markerRegions.collectAsState()
        val pinkOverlay by markerOverlay.collectAsState()
        val points by featurePoints.collectAsState()
        val allMatches by matches.collectAsState()
        val layer by layers.collectAsState()
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

        Row(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { stopCamera("STOP pressed") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("STOP", fontSize = 18.sp) }
                Button(enabled = !isRunning, onClick = { startCamera() }, modifier = Modifier.fillMaxWidth()) { Text("Start camera + detection") }
                Text(statusText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Rotate preview", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0, 90, 180, 270).forEach { deg ->
                        val pad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        if (deg == rot) Button(onClick = { rotation.value = deg }, contentPadding = pad, modifier = Modifier.weight(1f)) { Text("$deg°", maxLines = 1) }
                        else OutlinedButton(onClick = { rotation.value = deg }, contentPadding = pad, modifier = Modifier.weight(1f)) { Text("$deg°", maxLines = 1) }
                    }
                }
                Text("Contrast boost (CLAHE) before detection", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(false, true).forEach { on ->
                        val label = if (on) "On" else "Off"
                        if (on == boost) Button(onClick = { contrastBoost.value = on; log.i(TAG, "contrast boost $label") }, modifier = Modifier.weight(1f)) { Text(label) }
                        else OutlinedButton(onClick = { contrastBoost.value = on; log.i(TAG, "contrast boost $label") }, modifier = Modifier.weight(1f)) { Text(label) }
                    }
                }
                Text("Features per image", fontSize = 13.sp)
                Choice(listOf(1000, 3000, 5000), settings.maxFeatures, { "$it" }) { v -> changeOrb("features") { it.copy(maxFeatures = v) } }
                Text("FAST threshold (lower = weaker corners count)", fontSize = 13.sp)
                Choice(listOf(20, 15, 10, 5), settings.fastThreshold, { "$it" }) { v -> changeOrb("FAST") { it.copy(fastThreshold = v) } }
                Text("Spread keypoints over a grid", fontSize = 13.sp)
                Choice(listOf(false, true), settings.gridDistribution, { if (it) "On" else "Off" }) { v -> changeOrb("grid") { it.copy(gridDistribution = v) } }
                Text("Thresholds (good matches / inliers)", fontSize = 13.sp)
                Choice(listOf(15 to 12, 10 to 8, 8 to 6), thr, { "${it.first}/${it.second}" }) { v -> thresholds.value = v; log.i(TAG, "thresholds ${v.first}/${v.second}") }
                // Custom inlier requirement (good matches stay as chosen above; inliers are always a subset of the good matches).
                Text("Inliers required: ${thr.second}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val pad = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                    listOf(-10, -1, 1, 10).forEach { step ->
                        OutlinedButton(onClick = {
                            val next = (thresholds.value.second + step).coerceIn(MIN_INLIERS, MAX_INLIERS)
                            thresholds.value = thresholds.value.first to next
                            log.i(TAG, "inliers required $next (good matches ${thresholds.value.first})")
                        }, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(if (step > 0) "+$step" else "$step", maxLines = 1) }
                    }
                }
                Text("Detected in ${CalendarDetectionSettings.CONSISTENCY_NEEDED} of the last ${CalendarDetectionSettings.CONSISTENCY_WINDOW} passes", fontSize = 13.sp)
                Choice(listOf(false, true), consistency, { if (it) "On" else "Off" }) { v -> consistencyOn.value = v; log.i(TAG, "consistency ${if (v) "on" else "off"}") }
                Text("Enlarged tiles 2×2, 2× zoom (every ${TILE_INTERVAL_MS / 1000} s)", fontSize = 13.sp)
                Choice(listOf(false, true), tiles, { if (it) "On" else "Off" }) { v -> tilesOn.value = v; log.i(TAG, "tiles ${if (v) "on" else "off"}") }
                Text("Only search near pink marker (replaces full frame + tiles)", fontSize = 13.sp)
                Choice(listOf(false, true), pinkOn, { if (it) "On" else "Off" }) { v -> markerOn.value = v; log.i(TAG, "pink marker ${if (v) "on" else "off"}") }
                Text("Pink: search margin around pink (px)", fontSize = 13.sp)
                Choice(listOf(20, 40, 80), pinkMargin, { "$it" }) { v -> markerMargin.value = v; log.i(TAG, "pink margin $v px") }
                Text("Pink: minimum saturation (lower = paler pink counts)", fontSize = 13.sp)
                Choice(listOf(35, 50, 80), pinkSat, { "$it" }) { v -> markerSaturation.value = v; log.i(TAG, "pink min saturation $v") }
                Text("Pink: white balance first (camera renders white bluish)", fontSize = 13.sp)
                Choice(listOf(false, true), pinkWb, { if (it) "On" else "Off" }) { v -> markerWhiteBalance.value = v; log.i(TAG, "pink white balance ${onOff(v)}") }
                Text("Pink: must form a frame (pink along ≥ 3 sides; yellow = rejected pink)", fontSize = 13.sp)
                Choice(listOf(false, true), pinkFrame, { if (it) "On" else "Off" }) { v -> markerRequireFrame.value = v; log.i(TAG, "pink frame required ${onOff(v)}") }
                Text("Pink: inside must be ≥ ${(CalendarDetectionSettings.MIN_WHITE_SHARE * 100).toInt()} % white", fontSize = 13.sp)
                Choice(listOf(false, true), pinkWhite, { if (it) "On" else "Off" }) { v -> markerWhiteInside.value = v; log.i(TAG, "pink white inside ${onOff(v)}") }
                Text("Show on camera image (tap to toggle)", fontSize = 13.sp)
                listOf(
                    listOf<Pair<String, (Layers) -> Pair<Boolean, Layers>>>(
                        "Keypoints" to { l -> l.keypoints to l.copy(keypoints = !l.keypoints) },
                        "Inliers" to { l -> l.inliers to l.copy(inliers = !l.inliers) }
                    ),
                    listOf(
                        "Boxes" to { l -> l.boxes to l.copy(boxes = !l.boxes) },
                        "Outlines" to { l -> l.outlines to l.copy(outlines = !l.outlines) }
                    ),
                    listOf(
                        "Pink (green)" to { l -> l.pinkMask to l.copy(pinkMask = !l.pinkMask) },
                        "Pink bounds" to { l -> l.pinkBounds to l.copy(pinkBounds = !l.pinkBounds) }
                    ),
                    listOf(
                        "Search area" to { l -> l.searchArea to l.copy(searchArea = !l.searchArea) },
                        "Darken outside" to { l -> l.darkenOutside to l.copy(darkenOutside = !l.darkenOutside) }
                    )
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (label, toggle) ->
                            val on = toggle(layer).first
                            val pad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            val click = { layers.value = toggle(layers.value).second }
                            if (on) Button(onClick = click, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1, fontSize = 13.sp) }
                            else OutlinedButton(onClick = click, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1, fontSize = 13.sp) }
                        }
                    }
                }
                Text("References (box colour):", fontSize = 13.sp)
                refs.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(18.dp).height(12.dp).background(colorFor(name)))
                        Spacer(Modifier.width(6.dp))
                        Text(name, fontSize = 13.sp)
                    }
                }
                if (!isSelecting) {
                    Button(enabled = isRunning, onClick = { rotation.value = 0; selection.value = null; selecting.value = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Select region as reference")
                    }
                } else {
                    Text("Drag a box around the object on the camera image", fontSize = 13.sp)
                    Button(enabled = sel != null, onClick = { captureReference() }, modifier = Modifier.fillMaxWidth()) {
                        Text(sel?.let { "Use region ${it.width()}×${it.height()} px" } ?: "Use region")
                    }
                    OutlinedButton(onClick = { selecting.value = false; selection.value = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                OutlinedButton(enabled = CAMERA_REFERENCE in refs, onClick = {
                    orb?.removeReference(CAMERA_REFERENCE); references.value = orb?.classNames?.toList().orEmpty()
                    java.io.File(captureDir, "$CAMERA_REFERENCE.png").delete()
                    log.i(TAG, "camera reference removed and deleted")
                }, modifier = Modifier.fillMaxWidth()) { Text("Delete camera reference") }
                Text(brightnessText, fontSize = 13.sp)
                Text(statsText, fontSize = 13.sp)
                OutlinedButton(onClick = { log.clearScreen() }, modifier = Modifier.fillMaxWidth()) { Text("Clear log") }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
                val current = f
                if (current == null) Text(if (isRunning) "waiting for camera…" else "camera off", color = Color.White)
                else Preview(current.preview, found, rot, if (isSelecting) sel else null, isSelecting, if (pinkOn) pinkRegions else emptyList(),
                    Overlays(points, allMatches, if (pinkOn) pinkOverlay else null), pinkOn, layer)
            }
        }
    }

    /** Stable colour per reference name. */
    private fun colorFor(className: String): Color = BOX_COLORS[Math.floorMod(className.hashCode(), BOX_COLORS.size)]

    private fun better(a: ObjectMatch, b: ObjectMatch): Boolean =
        if (a.detected != b.detected) a.detected else if (a.inliers != b.inliers) a.inliers > b.inliers else a.goodMatches > b.goodMatches

    @Composable
    private fun <T> Choice(options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val pad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            options.forEach { o ->
                if (o == current) Button(onClick = { onPick(o) }, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(label(o), maxLines = 1) }
                else OutlinedButton(onClick = { onPick(o) }, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(label(o), maxLines = 1) }
            }
        }
    }

    /** The frame scaled to fit, rotated by [rotationDeg], with outline (green) and bounding box (yellow) per detection. */
    @Composable
    private fun Preview(
        bitmap: Bitmap, detected: List<ObjectMatch>, rotationDeg: Int, selectionRect: android.graphics.Rect?, selectMode: Boolean,
        pinkRegions: List<MarkerRegion>, overlays: Overlays, pinkMode: Boolean, layer: Layers
    ) {
        val image = bitmap.asImageBitmap()
        val labelPaint = android.graphics.Paint().apply { textSize = 32f; isAntiAlias = true; color = android.graphics.Color.YELLOW }
        val imgW = image.width; val imgH = image.height
        // Drag to select a region (rotation is forced to 0° while selecting, so screen ↔ frame mapping is a plain scale + offset).
        val gestures = if (!selectMode) Modifier else Modifier.pointerInput(imgW, imgH) {
            fun toImage(o: Offset): Pair<Int, Int> {
                val scale = min(size.width / imgW.toFloat(), size.height / imgH.toFloat())
                val left = (size.width - imgW * scale) / 2; val top = (size.height - imgH * scale) / 2
                return ((o.x - left) / scale).toInt().coerceIn(0, imgW) to ((o.y - top) / scale).toInt().coerceIn(0, imgH)
            }
            var start = 0 to 0
            detectDragGestures(
                onDragStart = { o -> start = toImage(o) },
                onDrag = { change, _ ->
                    val end = toImage(change.position)
                    selection.value = android.graphics.Rect(minOf(start.first, end.first), minOf(start.second, end.second),
                        maxOf(start.first, end.first), maxOf(start.second, end.second))
                }
            )
        }
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val sideways = rotationDeg % 180 != 0
            val w = if (sideways) image.height.toFloat() else image.width.toFloat()
            val h = if (sideways) image.width.toFloat() else image.height.toFloat()
            val scale = min(size.width / w, size.height / h)
            val dw = image.width * scale
            val dh = image.height * scale
            val left = (size.width - dw) / 2
            val top = (size.height - dh) / 2
            rotate(rotationDeg.toFloat(), pivot = center) {
                drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
                fun p(x: Double, y: Double) = Offset(left + x.toFloat() * scale, top + y.toFloat() * scale)
                // Pink mode: darken everything OUTSIDE the search areas, so it is obvious where ORB looks (nowhere else).
                if (layer.darkenOutside && pinkMode) {
                    val shade = Path().apply {
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(left, top, left + dw, top + dh))
                        pinkRegions.forEach { r ->
                            val s = r.search
                            addRect(androidx.compose.ui.geometry.Rect(p(s.x.toDouble(), s.y.toDouble()), p((s.x + s.width).toDouble(), (s.y + s.height).toDouble())))
                        }
                    }
                    drawPath(shade, Color.Black.copy(alpha = 0.5f))
                }
                overlays.let { ov ->
                    if (layer.pinkMask) ov.pinkMask?.let { mask ->
                        drawImage(mask.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(mask.width, mask.height),
                            dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
                    }
                    // All keypoints: small light-blue dots. Inliers of each reference (detected or not): bigger dots in its box colour.
                    if (layer.keypoints) drawPoints(ov.keypoints.map { p(it.x, it.y) }, androidx.compose.ui.graphics.PointMode.Points, Color(0xAA80D8FF),
                        strokeWidth = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    if (layer.inliers) ov.matches.forEach { m ->
                        if (m.inlierPoints.isNotEmpty()) drawPoints(m.inlierPoints.map { p(it.x, it.y) }, androidx.compose.ui.graphics.PointMode.Points,
                            colorFor(m.className), strokeWidth = 9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                // Pink areas: thick orange = where features are searched, thin pink = the pink pixels' bounds.
                pinkRegions.forEachIndexed { i, r ->
                    val s = r.search; val b = r.bounds
                    val tl = p(s.x.toDouble(), s.y.toDouble())
                    val size = androidx.compose.ui.geometry.Size(s.width * scale, s.height * scale)
                    if (layer.searchArea) {
                        drawRect(Color(0xFFFF9100), topLeft = tl, size = size, style = Stroke(width = 6f))
                        labelPaint.color = 0xFFFF9100.toInt()
                        drawContext.canvas.nativeCanvas.drawText("search ${i + 1}", tl.x + 6f, tl.y + size.height + 30f, labelPaint)
                    }
                    if (layer.pinkBounds) drawRect(Color(0xFFFF80AB), topLeft = p(b.x.toDouble(), b.y.toDouble()),
                        size = androidx.compose.ui.geometry.Size(b.width * scale, b.height * scale), style = Stroke(width = 1.5f))
                }
                detected.forEachIndexed { index, m ->
                    if (m.corners.size < 4) return@forEachIndexed
                    // One colour per reference, so boxes of different references on the same object stay distinguishable.
                    val color = colorFor(m.className)
                    labelPaint.color = color.toArgb()
                    val labelOffset = index * 36f
                    val outline = Path().apply {
                        m.corners.forEachIndexed { i, c -> val o = p(c.x, c.y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                        close()
                    }
                    if (layer.outlines) drawPath(outline, color.copy(alpha = 0.6f), style = Stroke(width = 3f))
                    if (!layer.boxes) return@forEachIndexed
                    val minX = m.corners.minOf { it.x }; val maxX = m.corners.maxOf { it.x }
                    val minY = m.corners.minOf { it.y }; val maxY = m.corners.maxOf { it.y }
                    val tl = p(max(0.0, minX), max(0.0, minY))
                    val br = p(min(image.width.toDouble(), maxX), min(image.height.toDouble(), maxY))
                    drawRect(color, topLeft = tl, size = androidx.compose.ui.geometry.Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = 4f))
                    drawContext.canvas.nativeCanvas.drawText("${m.className} (${m.inliers})", tl.x + 6f, tl.y - 10f - labelOffset, labelPaint)
                }
                selectionRect?.let { r ->
                    val tl = p(r.left.toDouble(), r.top.toDouble())
                    drawRect(Color(0xFF40C4FF), topLeft = tl, size = androidx.compose.ui.geometry.Size(r.width() * scale, r.height() * scale), style = Stroke(width = 4f))
                }
            }
        }
    }

    /** Drawing layers of the preview, each switchable on the screen. */
    private data class Layers(
        val keypoints: Boolean = true,
        val inliers: Boolean = true,
        val boxes: Boolean = true,
        val outlines: Boolean = true,
        val pinkMask: Boolean = true,
        val pinkBounds: Boolean = true,
        val searchArea: Boolean = true,
        val darkenOutside: Boolean = true
    )

    /** Data the preview can draw on top of the image. */
    private class Overlays(val keypoints: List<ImagePoint>, val matches: List<ObjectMatch>, val pinkMask: Bitmap?)

    private companion object {
        const val TAG = "objects"

        /** Range of the custom inlier requirement (4 = the minimum a homography needs). */
        const val MIN_INLIERS = 4
        const val MAX_INLIERS = 200

        /** Class name of a reference captured from the camera on this screen. */
        const val CAMERA_REFERENCE = "Camera_capture"

        /** Box colours, one per reference (chosen by name). */
        val BOX_COLORS = listOf(Color(0xFFFFEB3B), Color(0xFF00E5FF), Color(0xFFFF4081), Color(0xFF76FF03), Color(0xFF448AFF), Color(0xFFE040FB))

        /** How often the tile search runs when enabled. */
        const val TILE_INTERVAL_MS = 1_000L

        /** Largest pink areas searched per pass (each costs one ORB pass). */
        const val MAX_MARKER_REGIONS = 3


    }
}
