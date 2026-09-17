package com.example.robocontrol.vision

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** Drawing layers of the camera debug view. Same meaning as in the object test. */
private data class CameraLayers(
    val keypoints: Boolean = true,
    val inliers: Boolean = true,
    val boxes: Boolean = true,
    val pinkMask: Boolean = true,
    val pinkBounds: Boolean = true,
    val searchArea: Boolean = true,
    val darkenOutside: Boolean = true
)

private val REFERENCE_COLORS = listOf(Color(0xFFFFEB3B), Color(0xFF00E5FF), Color(0xFFFF4081), Color(0xFF76FF03), Color(0xFF448AFF), Color(0xFFE040FB))
private fun colorFor(name: String) = REFERENCE_COLORS[Math.floorMod(name.hashCode(), REFERENCE_COLORS.size)]
private val SEARCH_ORANGE = Color(0xFFFF9100)

/**
 * Full-screen debug view of [CalendarMonitor]: its live camera stream with the detection drawn on top (keypoints, inliers, green/yellow
 * pink mask, orange search areas, boxes), the numbers of the latest pass and layer toggles. Uses the monitor's own stream, so it
 * shows exactly what the monitor sees and never competes for the camera. Nothing is stored.
 *
 * @param topControls shown above Back, e.g. a STOP button of the screen that embeds this view
 */
@Composable
fun CalendarCameraScreen(onBack: () -> Unit, topControls: @Composable () -> Unit = {}) {
    DisposableEffect(Unit) {
        CalendarMonitor.addDebugViewer()
        onDispose { CalendarMonitor.removeDebugViewer() }
    }
    val state by CalendarMonitor.state.collectAsState()
    val snapshot by CalendarMonitor.debug.collectAsState()
    val minInliers by CalendarMonitor.minInliers.collectAsState()
    val everyDetection by CalendarMonitor.announceEveryDetection.collectAsState()
    var layers by remember { mutableStateOf(CameraLayers()) }
    var showNumbers by rememberSaveable { mutableStateOf(true) }
    // Live preview ~20 fps from the monitor's latest frame; overlays come from the latest detection pass.
    var frame by remember { mutableStateOf<CameraFrame?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val f = CalendarMonitor.latestFrame()
            if (f !== frame) frame = f
            delay(50)
        }
    }

    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topControls()
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Back", fontSize = 16.sp) }
            Text("Calendar detection: $state", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Settings: features ${CalendarDetectionSettings.orb.maxFeatures}, FAST ${CalendarDetectionSettings.orb.fastThreshold}, " +
                "grid ${if (CalendarDetectionSettings.orb.gridDistribution) "on" else "off"}, thresholds " +
                "${CalendarDetectionSettings.MIN_GOOD_MATCHES}/$minInliers, pink margin " +
                "${CalendarDetectionSettings.MARKER_MARGIN_PX} px, white inside ≥ ${(CalendarDetectionSettings.MIN_WHITE_SHARE * 100).toInt()} %, " +
                "${CalendarDetectionSettings.WORKERS} workers", fontSize = 12.sp)
            Text("Speak \"Kalender entdeckt\"", fontSize = 13.sp)
            ToggleRow("Every detection" to everyDetection, "2 in a row + ${CalendarMonitor.COOLDOWN_MS / 1000} s" to !everyDetection) { i -> CalendarMonitor.setAnnounceEveryDetection(i == 0) }
            // Inlier requirement of the calendar monitor (saved; applies from the next pass).
            Text("Inliers required: $minInliers (confirming pass: ${(minInliers - CalendarDetectionSettings.CONFIRM_INLIER_DROP).coerceAtLeast(CalendarDetectionSettings.MIN_INLIERS_LOWEST)})",
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val pad = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
                listOf(-10, -1, 1, 10).forEach { step ->
                    OutlinedButton(onClick = { CalendarMonitor.setMinInliers(minInliers + step) }, contentPadding = pad, modifier = Modifier.weight(1f)) {
                        Text(if (step > 0) "+$step" else "$step", maxLines = 1)
                    }
                }
            }
            snapshot?.let { s ->
                val bestInliers = s.pass.results.filter { it.detected }.maxOfOrNull { it.inliers }
                val full = bestInliers != null && bestInliers >= minInliers
                Text(
                    when {
                        full -> "DETECTED in this pass"
                        bestInliers != null -> "confirm level only ($bestInliers inliers)"
                        else -> "not detected in this pass"
                    },
                    color = if (full) Color(0xFF2E7D32) else if (bestInliers != null) Color(0xFFEF6C00) else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Text("%.1f passes/s · last passes: ${s.recent}/${s.window} detected · announced ${s.announcements}×".format(s.passesPerSecond) +
                    if (!everyDetection && s.cooldownLeftMs > 0) " · cooldown ${(s.cooldownLeftMs + 999) / 1000} s" else "", fontSize = 13.sp)
                if (showNumbers) {
                    val m = s.pass.marker
                    Text("pass ${s.passMs} ms · colour ${m?.millis ?: 0} ms · pink %.2f %% · regions ${s.pass.regions.size}".format((m?.pinkShare ?: 0.0) * 100) +
                        s.pass.regions.joinToString("") { " [${it.bounds.width}×${it.bounds.height}, ${it.pinkPixels} px, ${it.sides} sides${if (it.held) ", held" else ""}, white ${(it.whiteShare * 100).toInt()} %]" } +
                        (m?.rejected.orEmpty().takeIf { it.isNotEmpty() }?.let { r -> " · rejected: " + r.take(3).joinToString { "${it.bounds.width}×${it.bounds.height} " +
                            (if (it.rejectReason == "white") "white ${(it.whiteShare * 100).toInt()} %" else "sides " + it.sideCoverage.joinToString("/") { c -> "%.0f".format(c * 100) } + "%") } } ?: "") +
                        " · keypoints ${s.pass.keypoints}", fontSize = 12.sp)
                    s.pass.results.forEach { r ->
                        Text("${r.className}: good ${r.goodMatches}, inliers ${r.inliers}" +
                            (if (r.detected) " DETECTED${if (r.outlineSource == "marker") " (pink box)" else ""}" else "") +
                            (r.outlineRejected?.let { " [outline $it]" } ?: ""), fontSize = 12.sp, color = colorFor(r.className).takeIf { r.detected } ?: Color.Unspecified)
                    }
                }
            } ?: Text(if (state == "watching") "waiting for the first detection pass…" else "no detection running", fontSize = 13.sp)
            Text("Show on camera image (tap to toggle)", fontSize = 13.sp)
            ToggleRow("Keypoints" to layers.keypoints, "Inliers" to layers.inliers) { i -> layers = if (i == 0) layers.copy(keypoints = !layers.keypoints) else layers.copy(inliers = !layers.inliers) }
            ToggleRow("Boxes" to layers.boxes, "Pink (green)" to layers.pinkMask) { i -> layers = if (i == 0) layers.copy(boxes = !layers.boxes) else layers.copy(pinkMask = !layers.pinkMask) }
            ToggleRow("Pink bounds" to layers.pinkBounds, "Search area" to layers.searchArea) { i -> layers = if (i == 0) layers.copy(pinkBounds = !layers.pinkBounds) else layers.copy(searchArea = !layers.searchArea) }
            ToggleRow("Darken outside" to layers.darkenOutside, "Numbers" to showNumbers) { i -> if (i == 0) layers = layers.copy(darkenOutside = !layers.darkenOutside) else showNumbers = !showNumbers }
            Text("Legend: blue dots = keypoints, big dots = inliers (reference colour), green = pink marker, yellow = rejected pink, " +
                "orange = search area, thin pink = pink line bounds", fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
            val f = frame
            if (f == null) Text(if (state == "watching") "waiting for camera…" else "no camera stream ($state)", color = Color.White)
            else CameraCanvas(f, snapshot, layers)
        }
    }
}

@Composable
private fun ToggleRow(a: Pair<String, Boolean>, b: Pair<String, Boolean>, onToggle: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val pad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        listOf(a, b).forEachIndexed { i, (label, on) ->
            if (on) Button(onClick = { onToggle(i) }, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1, fontSize = 13.sp) }
            else OutlinedButton(onClick = { onToggle(i) }, contentPadding = pad, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun CameraCanvas(frame: CameraFrame, snapshot: CalendarMonitor.DebugSnapshot?, layers: CameraLayers) {
    val image = frame.preview.asImageBitmap()
    val labelPaint = remember { android.graphics.Paint().apply { textSize = 30f; isAntiAlias = true } }
    Canvas(Modifier.fillMaxSize()) {
        val scale = min(size.width / image.width, size.height / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val left = (size.width - dw) / 2
        val top = (size.height - dh) / 2
        fun p(x: Double, y: Double) = Offset(left + x.toFloat() * scale, top + y.toFloat() * scale)
        drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
        val pass = snapshot?.pass ?: return@Canvas
        if (layers.darkenOutside) {
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(left, top, left + dw, top + dh))
                pass.regions.forEach { r -> addRect(Rect(p(r.search.x.toDouble(), r.search.y.toDouble()),
                    p((r.search.x + r.search.width).toDouble(), (r.search.y + r.search.height).toDouble()))) }
            }
            drawPath(shade, Color.Black.copy(alpha = 0.5f))
        }
        if (layers.pinkMask) pass.marker?.overlay?.let { mask ->
            drawImage(mask.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(mask.width, mask.height),
                dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
        }
        if (layers.keypoints) drawPoints(pass.keypointPositions.map { p(it.x, it.y) }, PointMode.Points, Color(0xAA80D8FF), strokeWidth = 3f, cap = StrokeCap.Round)
        if (layers.inliers) pass.results.forEach { m ->
            if (m.inlierPoints.isNotEmpty()) drawPoints(m.inlierPoints.map { p(it.x, it.y) }, PointMode.Points, colorFor(m.className), strokeWidth = 9f, cap = StrokeCap.Round)
        }
        pass.regions.forEachIndexed { i, r ->
            val s = r.search
            val tl = p(s.x.toDouble(), s.y.toDouble())
            val box = Size(s.width * scale, s.height * scale)
            if (layers.searchArea) {
                drawRect(SEARCH_ORANGE, topLeft = tl, size = box, style = Stroke(width = 6f))
                labelPaint.color = SEARCH_ORANGE.toArgb()
                drawContext.canvas.nativeCanvas.drawText("search ${i + 1}", tl.x + 6f, tl.y + box.height + 30f, labelPaint)
            }
            if (layers.pinkBounds) drawRect(Color(0xFFFF80AB), topLeft = p(r.bounds.x.toDouble(), r.bounds.y.toDouble()),
                size = Size(r.bounds.width * scale, r.bounds.height * scale), style = Stroke(width = 1.5f))
        }
        if (layers.boxes) pass.results.filter { it.detected && it.corners.size == 4 }.forEachIndexed { index, m ->
            val color = colorFor(m.className)
            val tl = p(max(0.0, m.corners.minOf { it.x }), max(0.0, m.corners.minOf { it.y }))
            val br = p(min(frame.gray.width.toDouble(), m.corners.maxOf { it.x }), min(frame.gray.height.toDouble(), m.corners.maxOf { it.y }))
            drawRect(color, topLeft = tl, size = Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = 4f))
            labelPaint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText("${m.className} (${m.inliers})", tl.x + 6f, tl.y - 10f - index * 34f, labelPaint)
        }
    }
}
