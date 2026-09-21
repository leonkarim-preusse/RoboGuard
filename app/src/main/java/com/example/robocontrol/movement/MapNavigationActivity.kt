package com.example.robocontrol.movement

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
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
import com.example.robocontrol.text.UiText
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * RoboGuard's "Navigation and Map" screen, opened from MainActivity: the map with the robot, saved places, named
 * locations, tapped points and private areas, plus driving controls. UI for [MapNavigation].
 *
 * Copied from the movement test screen (`movementprobe.MovementProbeActivity`, still available as "RG Movement Test"
 * for dedicated hardware tests), without the no-go line experiment and without a log file.
 *
 * Layout: STOP, status and point lists on the left (scrollable); map preview on the right with the event log below.
 * Tapping the preview opens the full-screen map (tap = point or area corner, double tap / pinch = zoom).
 * Leaving the screen stops any navigation.
 */
class MapNavigationActivity : ComponentActivity() {

    private lateinit var log: NavigationLog
    private lateinit var probe: MapNavigation
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen texts come from assets/texts/texts.json (no-op if the service already loaded them).
        UiText.init(applicationContext)
        // The navigation runs in the service (NavigationHub), so the phone app sees the same state and a drive does not
        // stop when this screen closes. Started here as well in case the service is not up yet.
        NavigationHub.start(applicationContext)
        log = NavigationHub.log
        probe = NavigationHub.current ?: MapNavigation(applicationContext, log, lifecycleScope).also { it.start() }

        // Plain MaterialTheme: robocontrol must not depend on roboguard's theme.
        setContent {
            MaterialTheme {
                NavigationScreen()
            }
        }
    }

    /**
     * Leaving the screen no longer stops the robot: the navigation belongs to the service and the phone app may be steering.
     * Stopping is the person's decision (STOP here or on the phone) or the privacy rules'.
     */
    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        // The navigation keeps running in the service; only an unshared fallback instance would need shutting down.
        if (NavigationHub.current == null) probe.shutdown()
        super.onDestroy()
    }

    /**
     * The navigation runs in the service and is already started; this only reloads the map once the storage permission
     * question has been answered (the map file needs that permission).
     */
    private fun startOnce() {
        if (started) return
        started = true
        probe.reload()
    }

    /** "yes" / "NO" / "?" for the localization state, from the text file. */
    private fun localizedText(localized: Boolean?) = UiText.get(
        when (localized) { true -> "nav.label.yes"; false -> "nav.label.no"; null -> "nav.label.unknown" }
    )

    @Composable
    private fun NavigationScreen() {
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
        val privateZones by probe.privateZones.collectAsState()
        val zoneStoreError by probe.zoneStoreError.collectAsState()
        val zoneSaveWarning by probe.zoneSaveWarning.collectAsState()
        val zonesLoaded by probe.zonesLoaded.collectAsState()
        val pointStoreError by probe.pointStoreError.collectAsState()
        val remoteControl by NavigationHub.remoteControl.collectAsState()
        val storageMessage = zoneStoreError ?: pointStoreError ?: zoneSaveWarning
        var confirmResetLocations by remember { mutableStateOf(false) }
        if (confirmResetLocations) {
            AlertDialog(
                onDismissRequest = { confirmResetLocations = false },
                title = { Text(UiText.get("nav.dialog.reset_locations.title")) },
                text = { Text(UiText.get("nav.dialog.reset_locations.text")) },
                confirmButton = {
                    Button(
                        onClick = { probe.resetSavedLocations(); confirmResetLocations = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)
                    ) { Text(UiText.get("nav.button.delete")) }
                },
                dismissButton = { TextButton(onClick = { confirmResetLocations = false }) { Text(UiText.get("nav.button.cancel")) } }
            )
        }
        val canDrive = selected != null && zonesLoaded && zoneStoreError == null
        var confirmClearAreas by remember { mutableStateOf(false) }
        if (confirmClearAreas) {
            AlertDialog(
                onDismissRequest = { confirmClearAreas = false },
                title = { Text(UiText.get(if (zoneStoreError != null) "nav.dialog.reset_areas.title" else "nav.dialog.clear_areas.title")) },
                text = {
                    Text(
                        if (zoneStoreError != null) UiText.get("nav.dialog.reset_areas.text")
                        else UiText.get("nav.dialog.clear_areas.text", "map" to (mapName ?: UiText.get("nav.label.unknown")))
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { probe.clearPrivateAreas(); confirmClearAreas = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)
                    ) { Text(UiText.get("nav.button.delete")) }
                },
                dismissButton = { TextButton(onClick = { confirmClearAreas = false }) { Text(UiText.get("nav.button.cancel")) } }
            )
        }

        // ---- private areas: name dialog, delete confirmation, permission popup
        val activeOverrides by probe.activeOverrides.collectAsState()
        var nameAreaFor by remember { mutableStateOf<AreaKind?>(null) }
        nameAreaFor?.let { kind ->
            AreaNameDialog(kind, onDismiss = { nameAreaFor = null })
        }
        var confirmDeleteArea by remember { mutableStateOf<String?>(null) }
        confirmDeleteArea?.let { name ->
            AlertDialog(
                onDismissRequest = { confirmDeleteArea = null },
                title = { Text(UiText.get("nav.dialog.delete_area.title", "area" to name)) },
                text = { Text(UiText.get("nav.dialog.delete_area.text")) },
                confirmButton = {
                    Button(
                        onClick = { probe.removePrivateArea(name); confirmDeleteArea = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)
                    ) { Text(UiText.get("nav.button.delete")) }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteArea = null }) { Text(UiText.get("nav.button.cancel")) } }
            )
        }
        // A drive stopped at a private area asks on the screen: open the popup once per question.
        LaunchedEffect(Unit) {
            var shownId = -1L
            PrivacyOverridePrompt.pending.collect { request ->
                if (request != null && request.id != shownId) {
                    shownId = request.id
                    startActivity(Intent(this@MapNavigationActivity, PrivacyOverrideActivity::class.java))
                }
            }
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

        // The map is read from /sdcard/robot/map: READ_EXTERNAL_STORAGE is a runtime permission.
        val storagePermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val requestStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            log.i("nav", "storage permission: $granted")
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
        // Off by default: the event log and technical status lines are for debugging only.
        var showDebug by rememberSaveable { mutableStateOf(false) }
        // Zoom/pan of the full-screen map; starts at the whole map again whenever a new map is loaded.
        val mapView = remember(rendered) { MapViewState() }

        // Debug: camera stream of the calendar detection, shown in place of this screen (same activity, so driving continues).
        var cameraView by rememberSaveable { mutableStateOf(false) }
        var speakerView by rememberSaveable { mutableStateOf(false) }
        var voiceView by rememberSaveable { mutableStateOf(false) }
        if (voiceView) {
            com.example.robocontrol.conversation.VoiceEnrolmentScreen(onBack = { voiceView = false }) {
                Button(
                    onClick = { probe.stop("STOP pressed") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text(UiText.get("nav.button.stop"), fontSize = 20.sp) }
            }
            return
        }
        if (speakerView) {
            com.example.robocontrol.conversation.SpeakerDebugScreen(onBack = { speakerView = false }) {
                Button(
                    onClick = { probe.stop("STOP pressed") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text(UiText.get("nav.button.stop"), fontSize = 20.sp) }
            }
            return
        }

        if (cameraView) {
            com.example.robocontrol.vision.CalendarCameraScreen(onBack = { cameraView = false }) {
                Button(
                    onClick = { probe.stop("STOP pressed") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text(UiText.get("nav.button.stop"), fontSize = 20.sp) }
            }
            return
        }

        if (mapFullscreen) {
            // Full screen: a control bar ABOVE the map, so nothing covers the map itself.
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                // STOP and Exit come first so they are always on screen; the rest scrolls sideways if it does not fit.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { probe.stop("STOP pressed") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                        modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING
                    ) { Text(UiText.get("nav.button.stop"), fontSize = 16.sp) }
                    OutlinedButton(
                        onClick = {
                            probe.cancelDrawingArea()
                            mapFullscreen = false
                        },
                        modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING
                    ) { Text(UiText.get("nav.button.exit_fullscreen"), fontSize = 13.sp) }
                    val corners = drawingVertices
                    if (corners == null) {
                        Button(enabled = canDrive, onClick = { probe.driveToSelected() }, modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING) {
                            Text(selected?.let { UiText.get("nav.button.drive_to", "location" to it.name) } ?: UiText.get("nav.button.drive_to_none"), fontSize = 13.sp)
                        }
                        Button(
                            enabled = zonesLoaded && zoneStoreError == null,
                            onClick = { probe.startDrawingArea() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                            modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING
                        ) { Text(UiText.get("nav.button.draw_area"), fontSize = 13.sp) }
                    } else {
                        Button(
                            enabled = corners.size >= 3,
                            onClick = { nameAreaFor = AreaKind.DRAWN },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                            modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING
                        ) { Text(UiText.get("nav.button.finish_area", "corners" to corners.size), fontSize = 13.sp) }
                        OutlinedButton(enabled = corners.isNotEmpty(), onClick = { probe.undoDrawingVertex() }, modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING) {
                            Text(UiText.get("nav.button.undo_corner"), fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { probe.cancelDrawingArea() }, modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING) { Text(UiText.get("nav.button.cancel_drawing"), fontSize = 13.sp) }
                    }
                    OutlinedButton(enabled = mapView.zoom > 1f, onClick = { mapView.reset() }, modifier = Modifier.height(FULLSCREEN_BUTTON_HEIGHT), contentPadding = FULLSCREEN_BUTTON_PADDING) {
                        Text(UiText.get("nav.button.reset_zoom"), fontSize = 13.sp)
                    }
                }
                storageMessage?.let { message ->
                    Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFFD32F2F)).padding(6.dp))
                }
                Text(
                    (if (drawingVertices != null) UiText.get("nav.label.drawing_hint") else "") +
                        (if (showDebug) UiText.get("nav.label.fullscreen_debug", "state" to navState, "localized" to localizedText(localized),
                            "areas" to privateZones.size, "zoom" to "%.1f".format(mapView.zoom)) else "") +
                        UiText.get("nav.label.fullscreen_hint"),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                MapCanvas(
                    rendered, pose, places, custom, selected, privateZones, probe.privacyMargin, drawingVertices,
                    viewState = mapView,
                    onPreviewTap = null,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            return
        }

        Row(Modifier.fillMaxSize().padding(12.dp)) {
            // ---- left: STOP and Drive pinned at the top, everything else scrolls below
            Column(modifier = Modifier.width(340.dp).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { probe.stop("STOP pressed") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.height(56.dp)
                ) { Text(UiText.get("nav.button.stop"), fontSize = 20.sp) }
                Button(
                    enabled = canDrive,
                    onClick = { probe.driveToSelected() },
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text(selected?.let { UiText.get("nav.button.drive_to", "location" to it.name) } ?: UiText.get("nav.button.drive_to_none"), maxLines = 2) }
            }
            // Always visible (not debug): someone is steering this robot from the phone app. The thesis asks for visible
            // state — a person standing next to the robot should see that the commands come from another room.
            remoteControl?.let { client ->
                Text(
                    UiText.get("nav.label.remote_control", "client" to client),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1A73E8)).padding(6.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
            // Always visible (not debug): problems with the saved private areas.
            storageMessage?.let { message ->
                Text(
                    message,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFD32F2F)).padding(6.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Status lines are debug information; only the speed choice is always shown.
                if (showDebug) {
                    Text(UiText.get("nav.label.map", "map" to (mapName ?: UiText.get("nav.label.unknown"))))
                    Text(UiText.get("nav.label.sdk_control", "state" to UiText.get(if (sdkActive) "nav.label.sdk_yes" else "nav.label.sdk_no")))
                    Text(UiText.get("nav.label.localized", "state" to localizedText(localized)))
                    Text(UiText.get("nav.label.robot", "pose" to (pose?.let { "x %.2f  y %.2f  %.0f°  %s".format(it.x, it.y, Math.toDegrees(it.theta), it.status) } ?: UiText.get("nav.label.unknown"))))
                    Text(UiText.get("nav.label.navigation", "state" to navState))
                    Text(UiText.get("nav.label.selected", "selection" to (selected?.let { "${it.name} (%.2f, %.2f)".format(it.position.x, it.position.y) } ?: UiText.get("nav.label.selected_none"))))
                }

                Text(UiText.get("nav.label.speed", "speed" to speed.label + (speed.linear?.let { " – $it m/s" } ?: "")))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpeedPreset.entries.forEach { preset ->
                        if (preset == speed) {
                            Button(onClick = { probe.setSpeed(preset) }, modifier = Modifier.weight(1f)) { Text(preset.label, fontSize = 13.sp) }
                        } else {
                            OutlinedButton(onClick = { probe.setSpeed(preset) }, modifier = Modifier.weight(1f)) { Text(preset.label, fontSize = 13.sp) }
                        }
                    }
                }
                if (showDebug) Text(UiText.get("nav.label.measured_speed", "speed" to (measuredSpeed ?: UiText.get("nav.label.unknown"))), fontSize = 13.sp)

                OutlinedButton(onClick = { probe.clearCustomPoints() }, modifier = Modifier.fillMaxWidth()) { Text(UiText.get("nav.button.clear_points")) }

                Text(UiText.get("nav.label.privacy_areas"), fontWeight = FontWeight.Bold)
                if (privateZones.isEmpty()) Text(UiText.get("nav.label.no_areas"), fontSize = 13.sp)
                privateZones.forEach { zone ->
                    val expiresAt = activeOverrides.entries.firstOrNull { it.key.equals(zone.name, ignoreCase = true) }?.value
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(zone.name, fontSize = 14.sp)
                            if (expiresAt != null) {
                                val left = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                                Text(UiText.get("nav.label.area_allowed", "time" to "%d:%02d".format(left / 60, left % 60)), fontSize = 12.sp, color = Color(0xFF2E7D32))
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // Delete: removes the area (after confirmation). Red text, plain button.
                            OutlinedButton(
                                enabled = zonesLoaded && zoneStoreError == null,
                                onClick = { confirmDeleteArea = zone.name },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) { Text(UiText.get("nav.button.delete"), fontSize = 13.sp, color = Color(0xFFD32F2F)) }
                            // ✕: only while crossing is temporarily allowed; ends that permission now.
                            if (expiresAt != null) {
                                OutlinedButton(
                                    onClick = { probe.revokeCrossingPermission(zone.name) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text(UiText.get("nav.button.revoke_permission"), fontSize = 14.sp) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        enabled = selected != null && zonesLoaded && zoneStoreError == null,
                        onClick = { nameAreaFor = AreaKind.CIRCLE },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) { Text(UiText.get("nav.button.set_private_area"), fontSize = 11.sp) }
                    OutlinedButton(
                        enabled = privateZones.isNotEmpty() || zoneStoreError != null,
                        onClick = { confirmClearAreas = true },
                        modifier = Modifier.weight(1f)
                    ) { Text(UiText.get(if (zoneStoreError != null) "nav.button.reset_areas" else "nav.button.clear_areas"), fontSize = 11.sp) }
                }

                Text(UiText.get("nav.label.places"), fontWeight = FontWeight.Bold)
                places.forEach { PointButton(it, it == selected) }
                Text(UiText.get("nav.label.my_locations"), fontWeight = FontWeight.Bold)
                custom.filter { it.persistent }.forEach { PointButton(it, it == selected) }
                val tapped = custom.filter { !it.persistent }
                if (tapped.isNotEmpty()) {
                    Text(UiText.get("nav.label.tapped_points"), fontWeight = FontWeight.Bold)
                    tapped.forEach { PointButton(it, it == selected) }
                }
                OutlinedButton(
                    enabled = selected?.persistent == true,
                    onClick = { probe.deleteSelectedLocation() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(UiText.get("nav.button.delete_location")) }
                if (pointStoreError != null) {
                    OutlinedButton(onClick = { confirmResetLocations = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(UiText.get("nav.button.reset_locations"))
                    }
                }

                // Always shown, directly above the debug switch.
                Button(enabled = canSavePosition && pointStoreError == null, onClick = { showSaveDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(UiText.get(if (canSavePosition) "nav.button.save_position" else "nav.button.save_position_blocked"))
                }
                OutlinedButton(onClick = { probe.reload() }, modifier = Modifier.fillMaxWidth()) { Text(UiText.get("nav.button.reload_map")) }

                // Debug: technical status lines and the event log are hidden unless switched on.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Switch(checked = showDebug, onCheckedChange = { showDebug = it })
                    Spacer(Modifier.width(8.dp))
                    Text(UiText.get("nav.label.debug"))
                }
                if (showDebug) {
                    Button(onClick = { cameraView = true }, modifier = Modifier.fillMaxWidth()) { Text(UiText.get("nav.button.show_camera")) }
                    Button(onClick = { speakerView = true }, modifier = Modifier.fillMaxWidth()) { Text(UiText.get("nav.button.show_speaker")) }
                    Button(onClick = { voiceView = true }, modifier = Modifier.fillMaxWidth()) { Text(UiText.get("nav.button.teach_voice")) }
                    OutlinedButton(onClick = { log.clearScreen() }, modifier = Modifier.fillMaxWidth()) { Text(UiText.get("nav.button.clear_log")) }
                }
            }
            }

            Spacer(Modifier.width(16.dp))

            // ---- right: map above log
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Preview of the whole map; a tap opens the full-screen map, where points can be set and zoomed.
                MapCanvas(
                    rendered, pose, places, custom, selected, privateZones, probe.privacyMargin, null,
                    viewState = null,
                    onPreviewTap = { mapFullscreen = true },
                    modifier = Modifier.fillMaxWidth().weight(0.85f)
                )
                if (showDebug) {
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(0.15f)) {
                        items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
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
            title = { Text(UiText.get("nav.dialog.save_position.title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(UiText.get("nav.dialog.save_position.prompt"))
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
                ) { Text(UiText.get(if (saving) "nav.button.saving" else "nav.button.save")) }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(UiText.get("nav.button.cancel")) } }
        )
    }

    /** Which kind of private area the name dialog creates. */
    private enum class AreaKind { CIRCLE, DRAWN }

    /** Asks for the name of a new private area (default "Bereich N"); stays open with a message if it is refused. */
    @Composable
    private fun AreaNameDialog(kind: AreaKind, onDismiss: () -> Unit) {
        var name by remember { mutableStateOf(probe.nextAreaName()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(UiText.get("nav.dialog.area_name.title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Button(enabled = name.isNotBlank(), onClick = {
                    val result = when (kind) {
                        AreaKind.CIRCLE -> probe.makeSelectedAreaPrivate(name)
                        AreaKind.DRAWN -> probe.finishDrawingArea(name)
                    }
                    if (result == null) onDismiss() else error = result
                }) { Text(UiText.get("nav.button.save")) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(UiText.get("nav.button.cancel")) } }
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
        privateZones: List<Zone>,
        privacyMargin: Double,
        drawingVertices: List<Point2D>?,
        viewState: MapViewState?,
        onPreviewTap: (() -> Unit)?,
        modifier: Modifier
    ) {
        if (rendered == null) {
            Box(modifier.background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) {
                Text(UiText.get("nav.label.no_map"))
            }
            return
        }
        val image = remember(rendered) { rendered.bitmap.asImageBitmap() }
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
                        // MapNavigation decides: a corner while drawing a private area, otherwise a temporary point.
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
                    UiText.get("nav.label.map_hint"),
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

        /** Compact buttons in the full-screen bar so all of them fit on the robot's screen. */
        val FULLSCREEN_BUTTON_HEIGHT = 40.dp
        val FULLSCREEN_BUTTON_PADDING = PaddingValues(horizontal = 10.dp, vertical = 0.dp)

        /** Largest map zoom factor; a double tap at this zoom returns to the whole map. */
        const val MAX_ZOOM = 8f

        /** GreetBot Mini footprint radius (0.41 m wide, see CLAUDE.md). */
        const val ROBOT_RADIUS_M = 0.205
    }
}
