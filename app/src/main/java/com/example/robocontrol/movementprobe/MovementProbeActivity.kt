package com.example.robocontrol.movementprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.robocontrol.movement.PgmMap
import com.example.robocontrol.movement.Point2D
import com.example.robocontrol.movement.RobotPose
import com.example.robocontrol.movement.Zone
import com.example.robocontrol.voiceprobe.ProbeLog
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Test screen for [MovementProbe]: the map with the robot, saved places and tapped points, plus driving controls.
 * Not part of RoboGuard's UI.
 *
 * Start it from the robot's home launcher icon "RG Movement Test"; screens started with `adb shell am start` get
 * no SDK control. Procedure: README.md, "Testing movement".
 *
 * Layout: STOP, status and point list on the left (scrollable); map on the right with the log below it.
 * Tapping the map adds a point there. Leaving the screen stops any navigation.
 */
class MovementProbeActivity : ComponentActivity() {

    private lateinit var log: ProbeLog
    private lateinit var probe: MovementProbe
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = ProbeLog(applicationContext)
        probe = MovementProbe(applicationContext, log, lifecycleScope)
        log.i("probe", "log file: ${log.filePath}")

        // Plain MaterialTheme: robocontrol must not depend on roboguard's theme.
        setContent {
            MaterialTheme {
                ProbeScreen()
            }
        }
    }

    /** A robot must never keep driving while nobody sees the screen that controls it. */
    override fun onStop() {
        if (started) probe.stop("screen left")
        super.onStop()
    }

    override fun onDestroy() {
        probe.shutdown()
        super.onDestroy()
    }

    /** Starts the probe once, after the storage permission question has been answered either way. */
    private fun startOnce() {
        if (started) return
        started = true
        probe.start()
    }

    @Composable
    private fun ProbeScreen() {
        val lines by log.lines.collectAsState()
        val rendered by probe.rendered.collectAsState()
        val pose by probe.pose.collectAsState()
        val localized by probe.localized.collectAsState()
        val sdkActive by probe.sdkActive.collectAsState()
        val mapName by probe.mapName.collectAsState()
        val places by probe.places.collectAsState()
        val custom by probe.customPoints.collectAsState()
        val selected by probe.selected.collectAsState()
        val navState by probe.navState.collectAsState()
        val speed by probe.speed.collectAsState()
        val measuredSpeed by probe.measuredSpeed.collectAsState()
        var showSaveDialog by remember { mutableStateOf(false) }
        val pgm by probe.pgm.collectAsState()
        val lineA by probe.lineA.collectAsState()
        val lineB by probe.lineB.collectAsState()
        val lineCandidate by probe.lineCandidate.collectAsState()
        val hasMapBackup by probe.hasMapBackup.collectAsState()
        val privateZones by probe.privateZones.collectAsState()
        var confirmWrite by remember { mutableStateOf(false) }
        var confirmRestore by remember { mutableStateOf(false) }

        if (confirmWrite) {
            ConfirmDialog(
                title = "Write no-go line into the robot's map?",
                text = "This changes the map \"${mapName ?: "?"}\" for every app on this robot. RoboGuard keeps a copy of the " +
                    "original first, and \"Restore original map\" undoes it.",
                confirmLabel = "Write",
                onConfirm = { probe.writeNoGoLine() },
                onDismiss = { confirmWrite = false }
            )
        }
        if (confirmRestore) {
            ConfirmDialog(
                title = "Restore the original map?",
                text = "Puts the map image back as it was before RoboGuard's first change, removing every no-go line RoboGuard added.",
                confirmLabel = "Restore",
                onConfirm = { probe.restoreOriginalMap() },
                onDismiss = { confirmRestore = false }
            )
        }

        // Saving the current position is only possible while the robot knows where it is.
        val canSavePosition = localized == true && pose != null && mapName != null
        // Close the dialog if localization is lost while it is open.
        LaunchedEffect(canSavePosition) {
            if (!canSavePosition) showSaveDialog = false
        }
        if (showSaveDialog && canSavePosition) {
            SaveLocationDialog(onDismiss = { showSaveDialog = false })
        }

        // The map is read from /sdcard/robot/map (READ_EXTERNAL_STORAGE) and no-go lines are written there
        // (WRITE_EXTERNAL_STORAGE); both are runtime permissions.
        val storagePermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val requestStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            log.i("probe", "storage permissions: $granted")
            startOnce()
        }
        LaunchedEffect(Unit) {
            if (storagePermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                startOnce()
            } else {
                requestStorage.launch(storagePermissions)
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }

        val drawingVertices by probe.drawingVertices.collectAsState()
        var mapFullscreen by remember { mutableStateOf(false) }
        // Zoom/pan of the full-screen map; starts at the whole map again whenever a new map is loaded.
        val mapView = remember(rendered) { MapViewState() }

        if (mapFullscreen) {
            // Full screen: a control bar ABOVE the map, so nothing covers the map itself.
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                // STOP and Exit come first so they are always on screen; the rest scrolls sideways if it does not fit.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { probe.stop("STOP pressed") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                        modifier = Modifier.height(60.dp)
                    ) { Text("STOP", fontSize = 24.sp) }
                    OutlinedButton(
                        onClick = {
                            probe.cancelDrawingArea()
                            mapFullscreen = false
                        },
                        modifier = Modifier.height(60.dp)
                    ) { Text("Exit full screen") }
                    val corners = drawingVertices
                    if (corners == null) {
                        Button(enabled = selected != null, onClick = { probe.driveToSelected() }, modifier = Modifier.height(60.dp)) {
                            Text("Drive to ${selected?.name ?: "selected"}")
                        }
                        Button(
                            onClick = { probe.startDrawingArea() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                            modifier = Modifier.height(60.dp)
                        ) { Text("Draw private area") }
                    } else {
                        Button(
                            enabled = corners.size >= 3,
                            onClick = { probe.finishDrawingArea() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                            modifier = Modifier.height(60.dp)
                        ) { Text("Finish area (${corners.size} corners)") }
                        OutlinedButton(enabled = corners.isNotEmpty(), onClick = { probe.undoDrawingVertex() }, modifier = Modifier.height(60.dp)) {
                            Text("Undo corner")
                        }
                        OutlinedButton(onClick = { probe.cancelDrawingArea() }, modifier = Modifier.height(60.dp)) { Text("Cancel") }
                    }
                    OutlinedButton(enabled = mapView.zoom > 1f, onClick = { mapView.reset() }, modifier = Modifier.height(60.dp)) {
                        Text("Reset zoom")
                    }
                }
                Text(
                    (if (drawingVertices != null) "DRAWING: tap the map to add corners of the private area · " else "") +
                        "Navigation: $navState · Localized: ${when (localized) { true -> "yes"; false -> "NO"; null -> "?" }} · " +
                        "Private areas: ${privateZones.size} · Zoom ×%.1f · Double-tap or two fingers: zoom".format(mapView.zoom),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                MapCanvas(
                    rendered, pose, places, custom, selected, pgm, lineCandidate, privateZones, probe.privacyMargin, drawingVertices,
                    viewState = mapView,
                    onPreviewTap = null,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            return
        }

        Row(Modifier.fillMaxSize().padding(12.dp)) {
            // ---- left: controls, status, points
            Column(
                modifier = Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { probe.stop("STOP pressed") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) { Text("STOP", fontSize = 26.sp) }

                Text("Map: ${mapName ?: "?"}")
                Text("SDK control: ${if (sdkActive) "yes" else "NO (start from home launcher)"}")
                Text("Localized: ${when (localized) { true -> "yes"; false -> "NO"; null -> "?" }}")
                Text("Robot: " + (pose?.let { "x %.2f  y %.2f  %.0f°  %s".format(it.x, it.y, Math.toDegrees(it.theta), it.status) } ?: "?"))
                Text("Navigation: $navState")
                Text("Selected: " + (selected?.let { "${it.name} (%.2f, %.2f)".format(it.position.x, it.position.y) } ?: "none, tap the map"))

                Text("Speed (next drive): ${speed.label}" + (speed.linear?.let { " – $it m/s" } ?: ""))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpeedPreset.entries.forEach { preset ->
                        if (preset == speed) {
                            Button(onClick = { probe.setSpeed(preset) }, modifier = Modifier.weight(1f)) { Text(preset.label, fontSize = 13.sp) }
                        } else {
                            OutlinedButton(onClick = { probe.setSpeed(preset) }, modifier = Modifier.weight(1f)) { Text(preset.label, fontSize = 13.sp) }
                        }
                    }
                }
                Text("Measured speed: ${measuredSpeed ?: "?"}", fontSize = 13.sp)

                Button(enabled = selected != null, onClick = { probe.driveToSelected() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Drive to selected")
                }
                Button(enabled = canSavePosition, onClick = { showSaveDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (canSavePosition) "Save current position…" else "Save current position (robot not localized)")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { probe.reload() }, modifier = Modifier.weight(1f)) { Text("Reload map") }
                    OutlinedButton(onClick = { probe.clearCustomPoints() }, modifier = Modifier.weight(1f)) { Text("Clear tapped points") }
                }
                OutlinedButton(
                    enabled = selected?.persistent == true,
                    onClick = { probe.deleteSelectedLocation() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete selected location") }

                Text("Privacy areas", fontWeight = FontWeight.Bold)
                Text(
                    if (privateZones.isEmpty()) "none" else privateZones.joinToString { it.name },
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        enabled = selected != null,
                        onClick = { probe.makeSelectedAreaPrivate() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) { Text("Private area around selected", fontSize = 13.sp) }
                    OutlinedButton(
                        enabled = privateZones.isNotEmpty(),
                        onClick = { probe.clearPrivateAreas() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear private areas", fontSize = 13.sp) }
                }

                Text("Saved places (RobotOS)", fontWeight = FontWeight.Bold)
                places.forEach { PointButton(it, it == selected) }
                Text("My locations (saved in RoboGuard)", fontWeight = FontWeight.Bold)
                custom.filter { it.persistent }.forEach { PointButton(it, it == selected) }
                Text("Tapped points (temporary)", fontWeight = FontWeight.Bold)
                custom.filter { !it.persistent }.forEach { PointButton(it, it == selected) }

                Spacer(Modifier.height(8.dp))
                Text("No-go line test (writes the robot's map)", fontWeight = FontWeight.Bold)
                Text("A: ${lineA?.name ?: "-"}    B: ${lineB?.name ?: "-"}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = selected != null, onClick = { probe.setLineEnd(isA = true) }, modifier = Modifier.weight(1f)) { Text("A = selected") }
                    OutlinedButton(enabled = selected != null, onClick = { probe.setLineEnd(isA = false) }, modifier = Modifier.weight(1f)) { Text("B = selected") }
                }
                Button(
                    enabled = lineA != null && lineB != null && pgm != null,
                    onClick = { probe.previewNoGoLine() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Preview line across A–B") }
                Button(
                    enabled = lineCandidate != null && sdkActive,
                    onClick = { confirmWrite = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Write no-go line to robot map…") }
                OutlinedButton(
                    enabled = hasMapBackup && sdkActive,
                    onClick = { confirmRestore = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restore original map…") }

                OutlinedButton(onClick = { log.clearScreen() }, modifier = Modifier.fillMaxWidth()) { Text("Clear log") }
            }

            Spacer(Modifier.width(16.dp))

            // ---- right: map above log
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Preview of the whole map; a tap opens the full-screen map, where points can be set and zoomed.
                MapCanvas(
                    rendered, pose, places, custom, selected, pgm, lineCandidate, privateZones, probe.privacyMargin, null,
                    viewState = null,
                    onPreviewTap = { mapFullscreen = true },
                    modifier = Modifier.fillMaxWidth().weight(0.85f)
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(0.15f)) {
                    items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        }
    }

    /**
     * Asks for a name and saves the robot's current position under it. Stays open with a message if saving is
     * refused (empty or duplicate name, robot not localized at the moment of saving).
     */
    @Composable
    private fun SaveLocationDialog(onDismiss: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { if (!saving) onDismiss() },
            title = { Text("Save current position") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name for the robot's current position:")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { Text(it, color = Color(0xFFD32F2F)) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !saving && name.isNotBlank(),
                    onClick = {
                        saving = true
                        scope.launch {
                            val result = probe.saveCurrentPosition(name)
                            saving = false
                            if (result == null) onDismiss() else error = result
                        }
                    }
                ) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
        )
    }

    /**
     * Asks before an action that changes the robot, runs it on confirm and shows its result in place,
     * so the tester sees whether it worked before closing.
     */
    @Composable
    private fun ConfirmDialog(
        title: String,
        text: String,
        confirmLabel: String,
        onConfirm: suspend () -> String,
        onDismiss: () -> Unit
    ) {
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text)
                    result?.let { Text(it, fontWeight = FontWeight.Bold) }
                }
            },
            confirmButton = {
                if (result == null) {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            result = onConfirm()
                            busy = false
                        }
                    }) { Text(if (busy) "Working…" else confirmLabel) }
                } else {
                    Button(onClick = onDismiss) { Text("Close") }
                }
            },
            dismissButton = {
                if (result == null) TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        )
    }

    @Composable
    private fun PointButton(point: MapPoint, isSelected: Boolean) {
        val label = "${point.name}  (%.2f, %.2f)".format(point.position.x, point.position.y)
        if (isSelected) {
            Button(onClick = { probe.select(point) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } else {
            OutlinedButton(onClick = { probe.select(point) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }

    /**
     * Draws the map scaled to fit, with saved places (blue), tapped points (orange), the selection (red ring) and
     * the robot (green circle with a heading line).
     *
     * Two modes:
     *  - preview ([viewState] null): shows the whole map; a tap calls [onPreviewTap] (opens full screen)
     *  - interactive ([viewState] set): tap adds a point, double tap / pinch zooms, two-finger drag pans
     * Nothing is drawn over the map except the map content itself (plus a small hint in preview mode).
     */
    @Composable
    private fun MapCanvas(
        rendered: RenderedMap?,
        pose: RobotPose?,
        places: List<MapPoint>,
        custom: List<MapPoint>,
        selected: MapPoint?,
        pgm: PgmMap?,
        lineCandidate: Pair<Point2D, Point2D>?,
        privateZones: List<Zone>,
        privacyMargin: Double,
        drawingVertices: List<Point2D>?,
        viewState: MapViewState?,
        onPreviewTap: (() -> Unit)?,
        modifier: Modifier
    ) {
        if (rendered == null) {
            Box(modifier.background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) {
                Text("No map loaded yet (see log)")
            }
            return
        }
        val image = remember(rendered) { rendered.bitmap.asImageBitmap() }
        val noGoPixels = remember(pgm) { pgm?.noGoPixels().orEmpty() }
        val labelPaint = remember { android.graphics.Paint().apply { textSize = 26f; isAntiAlias = true } }

        fun transformFor(width: Float, height: Float): MapTransform {
            val base = fit(rendered, width, height)
            val zoom = viewState?.zoom ?: 1f
            val pan = viewState?.pan ?: Offset.Zero
            return MapTransform(rendered, base.scale * zoom, base.offsetX + pan.x, base.offsetY + pan.y)
        }

        /** Changes the zoom by [factor], keeping the map point under [focus] where it is on screen. */
        fun zoomAround(state: MapViewState, focus: Offset, factor: Float, width: Float, height: Float) {
            val base = fit(rendered, width, height)
            val newZoom = (state.zoom * factor).coerceIn(1f, MAX_ZOOM)
            val f = newZoom / state.zoom
            val offsetX = base.offsetX + state.pan.x
            val offsetY = base.offsetY + state.pan.y
            state.pan = if (newZoom <= 1f) Offset.Zero
            else Offset(focus.x - (focus.x - offsetX) * f - base.offsetX, focus.y - (focus.y - offsetY) * f - base.offsetY)
            state.zoom = newZoom
        }

        val gestures = if (viewState == null) {
            Modifier.pointerInput(rendered, onPreviewTap) {
                detectTapGestures(onTap = { onPreviewTap?.invoke() })
            }
        } else {
            Modifier
                .pointerInput(rendered, viewState) {
                    detectTapGestures(
                        // Single tap adds a point; double tap zooms in ×2 at that spot, and back to the whole map after MAX_ZOOM.
                        // MovementProbe decides: a corner while drawing a private area, otherwise a temporary point.
                        onTap = { tap ->
                            transformFor(size.width.toFloat(), size.height.toFloat()).toWorld(tap.x, tap.y)?.let { probe.handleMapTap(it) }
                        },
                        onDoubleTap = { tap ->
                            if (viewState.zoom >= MAX_ZOOM) viewState.reset()
                            else zoomAround(viewState, tap, 2f, size.width.toFloat(), size.height.toFloat())
                        }
                    )
                }
                .pointerInput(rendered, viewState) {
                    // Two fingers: pinch to zoom, drag to move the zoomed map.
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        if (zoomChange != 1f) zoomAround(viewState, centroid, zoomChange, size.width.toFloat(), size.height.toFloat())
                        if (viewState.zoom > 1f) viewState.pan += panChange
                    }
                }
        }

        Box(modifier.background(Color(0xFFEEEEEE)).clipToBounds()) {
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val t = transformFor(size.width, size.height)
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(t.offsetX.toInt(), t.offsetY.toInt()),
                dstSize = IntSize((image.width * t.scale).toInt(), (image.height * t.scale).toInt()),
                filterQuality = FilterQuality.None
            )

            fun marker(point: MapPoint, color: Color) {
                val center = t.toScreen(point.position)
                drawCircle(color, POINT_RADIUS_PX, center)
                if (point == selected) drawCircle(Color.Red, POINT_RADIUS_PX + 8f, center, style = Stroke(width = 4f))
                labelPaint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(point.name, center.x + POINT_RADIUS_PX + 6f, center.y - POINT_RADIUS_PX, labelPaint)
            }
            places.forEach { marker(it, Color(0xFF1565C0)) }
            custom.forEach { marker(it, if (it.persistent) Color(0xFF6A1B9A) else Color(0xFFEF6C00)) }

            // Private areas: filled polygon, plus an outer ring where PrivacyGuard's margin already stops the robot.
            privateZones.forEach { zone ->
                val path = Path().apply {
                    zone.polygon.forEachIndexed { i, p ->
                        val s = t.toScreen(p)
                        if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y)
                    }
                    close()
                }
                drawPath(path, Color(0x55D50000))
                drawPath(path, Color(0xFFD50000), style = Stroke(width = 3f))
                val centre = zone.centroid()
                val outerRadiusM = zone.polygon.maxOf { it.distanceTo(centre) } + privacyMargin
                drawCircle(
                    Color(0xFFD50000),
                    radius = (outerRadiusM / rendered.map.resolution * t.scale).toFloat(),
                    center = t.toScreen(centre),
                    style = Stroke(width = 2f)
                )
            }

            // The private area being drawn: filled once it has 3 corners, closing edge dashed, corners as dots.
            drawingVertices?.let { corners ->
                val screen = corners.map { t.toScreen(it) }
                if (screen.size >= 3) {
                    val fill = Path().apply {
                        screen.forEachIndexed { i, s -> if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y) }
                        close()
                    }
                    drawPath(fill, Color(0x40D50000))
                }
                screen.zipWithNext { a, b -> drawLine(Color(0xFFD50000), a, b, strokeWidth = 5f) }
                if (screen.size >= 3) {
                    drawLine(
                        Color(0xFFD50000), screen.last(), screen.first(), strokeWidth = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                    )
                }
                screen.forEach { drawCircle(Color(0xFFD50000), 10f, it) }
            }

            // No-go lines from the map tool's image (blue), placed via their world coordinates.
            pgm?.let { g ->
                val cell = (g.resolution / rendered.map.resolution * t.scale).toFloat().coerceAtLeast(2f)
                noGoPixels.forEach { (px, py) ->
                    val c = t.toScreen(g.toWorld(px + 0.5, py + 0.5))
                    drawRect(Color(0xFF1E88E5), topLeft = Offset(c.x - cell / 2, c.y - cell / 2), size = Size(cell, cell))
                }
            }
            // The previewed, not yet written line (red).
            lineCandidate?.let { (from, to) ->
                drawLine(Color(0xFFD50000), t.toScreen(from), t.toScreen(to), strokeWidth = 8f)
            }

            pose?.let { robot ->
                val center = t.toScreen(Point2D(robot.x, robot.y))
                val radius = (ROBOT_RADIUS_M / rendered.map.resolution * t.scale).toFloat().coerceAtLeast(10f)
                drawCircle(Color(0xFF2E7D32), radius, center)
                // World angles run counter-clockwise from +x; screen y points down, hence -sin.
                val tip = Offset(center.x + cos(robot.theta).toFloat() * radius * 1.8f, center.y - sin(robot.theta).toFloat() * radius * 1.8f)
                drawLine(Color(0xFF1B5E20), center, tip, strokeWidth = 6f)
            }
        }

            // Preview only: a small hint in the corner. The full-screen map has its controls in a bar above it.
            if (viewState == null) {
                Text(
                    "Tap to enlarge",
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0xCCFFFFFF)).padding(4.dp)
                )
            }
        }
    }

    /** Zoom factor (1 = whole map) and pan offset in screen pixels of the full-screen map. */
    private class MapViewState {
        var zoom by mutableStateOf(1f)
        var pan by mutableStateOf(Offset.Zero)

        fun reset() {
            zoom = 1f
            pan = Offset.Zero
        }
    }

    /** Converts between screen pixels and world metres for the map drawn centred and scaled to fit. */
    private class MapTransform(val rendered: RenderedMap, val scale: Float, val offsetX: Float, val offsetY: Float) {

        fun toScreen(p: Point2D): Offset {
            val (col, row) = rendered.map.toCell(p)
            val bx = col - rendered.bounds.minCol
            val by = rendered.bounds.maxRow + 1 - row // bitmap row 0 = top edge of the highest cell row
            return Offset((offsetX + bx * scale).toFloat(), (offsetY + by * scale).toFloat())
        }

        /** World position of a screen pixel, or null if it lies outside the drawn map. */
        fun toWorld(sx: Float, sy: Float): Point2D? {
            val bx = (sx - offsetX) / scale
            val by = (sy - offsetY) / scale
            if (bx < 0 || by < 0 || bx > rendered.bounds.width || by > rendered.bounds.height) return null
            return rendered.map.toWorld((rendered.bounds.minCol + bx).toDouble(), (rendered.bounds.maxRow + 1 - by).toDouble())
        }
    }

    private fun fit(rendered: RenderedMap, width: Float, height: Float): MapTransform {
        val bw = rendered.bitmap.width.toFloat()
        val bh = rendered.bitmap.height.toFloat()
        val scale = min(width / bw, height / bh)
        return MapTransform(rendered, scale, (width - bw * scale) / 2f, (height - bh * scale) / 2f)
    }

    private companion object {
        const val POINT_RADIUS_PX = 12f

        /** Largest map zoom factor; a double tap at this zoom returns to the whole map. */
        const val MAX_ZOOM = 8f

        /** GreetBot Mini footprint radius (0.41 m wide, see CLAUDE.md). */
        const val ROBOT_RADIUS_M = 0.205
    }
}
