package com.example.robocontrol.movementprobe

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.example.robocontrol.audio.OrionStarTts
import com.example.robocontrol.audio.TtsFailure
import com.example.robocontrol.audio.TtsListener
import com.example.robocontrol.movement.CellBounds
import com.example.robocontrol.movement.EscapeHeading
import com.example.robocontrol.movement.MapZones
import com.example.robocontrol.movement.NavigationController
import com.example.robocontrol.movement.PrivacyGuard
import com.example.robocontrol.movement.PrivacyLevel
import com.example.robocontrol.movement.Violation
import com.example.robocontrol.movement.Zone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.example.robocontrol.movement.NavEvent
import com.example.robocontrol.movement.NavFailure
import com.example.robocontrol.movement.NavigationListener
import com.example.robocontrol.movement.NoGoLineWriter
import com.example.robocontrol.movement.OrionStarBridge
import com.example.robocontrol.movement.PgmMap
import com.example.robocontrol.movement.Point2D
import com.example.robocontrol.movement.PoseStatus
import com.example.robocontrol.movement.RobotMapFile
import com.example.robocontrol.movement.RobotPose
import com.example.robocontrol.movement.SavedPoint
import com.example.robocontrol.movement.SavedPointStore
import com.example.robocontrol.voiceprobe.ProbeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A point shown on the map.
 *
 * @property saved true for places stored in RobotOS (driven to by name); false for RoboGuard's own points
 *           (driven to by coordinates)
 * @property persistent true for named locations recorded with "Save current position" and stored in
 *           [SavedPointStore]; false for temporary points added by tapping the map
 */
data class MapPoint(val name: String, val position: Point2D, val saved: Boolean, val persistent: Boolean = false)

/** The map prepared for display: [bitmap] shows exactly the cells in [bounds], with the top row = highest world y. */
class RenderedMap(val map: RobotMapFile, val bitmap: Bitmap, val bounds: CellBounds)

/**
 * Navigation speed presets. Values are passed as `linearSpeed` / `angularSpeed` to `startNavigation`;
 * units assumed m/s and rad/s (verify with the measured `navi_speed` shown on screen).
 */
enum class SpeedPreset(val label: String, val linear: Double?, val angular: Double?) {
    SLOW("Slow", 0.25, 0.5),
    MEDIUM("Medium", 0.45, 0.8),
    DEFAULT("Robot default", null, null)
}

/**
 * Hardware test for robot movement: shows the current RobotOS map to scale with the robot's live position and
 * saved places, lets the tester add points by tapping the map, and drives to a selected point.
 *
 * Driving goes through the real movement code, [OrionStarBridge] (`navigateTo(name)` for saved places,
 * `navigateTo(Point2D)` for tapped points), so this also tests that code. Privacy zones are not applied yet.
 *
 * Data sources, all verified on the robot:
 *  - map: `RobotMapFile` from `/sdcard/robot/map/<name>/navi_data/probabilitymap.data` (needs READ_EXTERNAL_STORAGE)
 *  - map name, saved places, live pose, localization: `RobotApi.getMapName()`, `getPlaceList()`,
 *    `getCurrentPose()`, `isRobotEstimate()` (synchronous SDK calls)
 *
 * Must run in an activity started from the robot's home launcher: otherwise RobotOS grants no SDK control and
 * navigation is silently ignored.
 */
class MovementProbe(
    context: Context,
    private val log: ProbeLog,
    private val scope: CoroutineScope
) {
    private val bridge = OrionStarBridge(context)
    private val pointStore = SavedPointStore(context)

    // ---- privacy areas: every drive goes through NavigationController, which checks the target before starting
    //      and aborts when a pose from the poll loop lies inside a private zone (plus PrivacyGuard's margin).
    private val guard = PrivacyGuard(MapZones(mapName = ""))
    private val controller = NavigationController(bridge, guard)
    private val tts = OrionStarTts(context)

    private val _privateZones = MutableStateFlow<List<Zone>>(emptyList())
    val privateZones: StateFlow<List<Zone>> = _privateZones.asStateFlow()

    /** Margin PrivacyGuard adds around every private zone, in metres (shown on the map as an outer ring). */
    val privacyMargin: Double get() = guard.margin

    /** Name of the zone the robot was last found standing in while idle, to log each entry only once. */
    private var idleViolationZone: String? = null

    init {
        controller.onIdleViolation = { violation ->
            val name = (violation as? Violation.PrivateZone)?.zone?.name ?: "RobotOS forbidden area"
            if (name != idleViolationZone) {
                idleViolationZone = name
                log.i(TAG, "robot is standing inside \"$name\" while not driving (pushed or parked there)")
            }
        }
    }

    private var noGoReqId = NO_GO_REQ_ID_START
    private val noGoWriter = NoGoLineWriter(context, log = { log.i(TAG, it) }, reqIdSource = { ++noGoReqId })

    /** The map tool's image of the current map, including its no-go lines; null if it could not be read. */
    private val _pgm = MutableStateFlow<PgmMap?>(null)
    val pgm: StateFlow<PgmMap?> = _pgm.asStateFlow()

    private val _lineA = MutableStateFlow<MapPoint?>(null)
    val lineA: StateFlow<MapPoint?> = _lineA.asStateFlow()

    private val _lineB = MutableStateFlow<MapPoint?>(null)
    val lineB: StateFlow<MapPoint?> = _lineB.asStateFlow()

    /** The previewed no-go line (end points in world coordinates), not yet written. */
    private val _lineCandidate = MutableStateFlow<Pair<Point2D, Point2D>?>(null)
    val lineCandidate: StateFlow<Pair<Point2D, Point2D>?> = _lineCandidate.asStateFlow()

    /** True once RoboGuard has saved the original map image of the current map, i.e. a restore is possible. */
    private val _hasMapBackup = MutableStateFlow(false)
    val hasMapBackup: StateFlow<Boolean> = _hasMapBackup.asStateFlow()

    private val _rendered = MutableStateFlow<RenderedMap?>(null)
    val rendered: StateFlow<RenderedMap?> = _rendered.asStateFlow()

    private val _mapName = MutableStateFlow<String?>(null)
    val mapName: StateFlow<String?> = _mapName.asStateFlow()

    private val _pose = MutableStateFlow<RobotPose?>(null)
    val pose: StateFlow<RobotPose?> = _pose.asStateFlow()

    /** null until first read. */
    private val _localized = MutableStateFlow<Boolean?>(null)
    val localized: StateFlow<Boolean?> = _localized.asStateFlow()

    private val _sdkActive = MutableStateFlow(false)
    val sdkActive: StateFlow<Boolean> = _sdkActive.asStateFlow()

    private val _places = MutableStateFlow<List<MapPoint>>(emptyList())
    val places: StateFlow<List<MapPoint>> = _places.asStateFlow()

    private val _customPoints = MutableStateFlow<List<MapPoint>>(emptyList())
    val customPoints: StateFlow<List<MapPoint>> = _customPoints.asStateFlow()

    private val _selected = MutableStateFlow<MapPoint?>(null)
    val selected: StateFlow<MapPoint?> = _selected.asStateFlow()

    private val _navState = MutableStateFlow("idle")
    val navState: StateFlow<String> = _navState.asStateFlow()

    private val _speed = MutableStateFlow(SpeedPreset.SLOW)
    val speed: StateFlow<SpeedPreset> = _speed.asStateFlow()

    /** Latest raw `navi_speed` status from RobotOS (device state, format undocumented). */
    private val _measuredSpeed = MutableStateFlow<String?>(null)
    val measuredSpeed: StateFlow<String?> = _measuredSpeed.asStateFlow()

    private var lastSpeedLogAt = 0L

    /** Logs the measured speed while driving, at most once per [SPEED_LOG_INTERVAL_MS], to verify the speed units. */
    private val speedStatus = object : StatusListener() {
        override fun onStatusUpdate(type: String?, value: String?) {
            _measuredSpeed.value = value
            val now = SystemClock.elapsedRealtime()
            if (_navState.value.startsWith("driving") || _navState.value.contains(":")) {
                if (now - lastSpeedLogAt >= SPEED_LOG_INTERVAL_MS) {
                    lastSpeedLogAt = now
                    log.i(TAG, "  measured navi_speed: $value")
                }
            }
        }
    }

    private var pollJob: Job? = null
    private var customCounter = 0

    /** Connects to the SDK; once connected, loads map and places and starts polling the pose. */
    fun start() {
        log.section("Movement")
        setSpeed(_speed.value)
        tts.connect { log.i(TAG, "text-to-speech connected (for privacy announcements)") }
        bridge.connect()
        scope.launch(Dispatchers.IO) {
            bridge.connected.first { it }
            log.i(TAG, "RobotApi connected")
            runCatching { RobotApi.getInstance().registerStatusListener(Definition.STATUS_SPEED, speedStatus) }
                .onFailure { log.i(TAG, "could not subscribe to navi_speed: $it") }
            reloadMapAndPlaces()
            pollJob = scope.launch(Dispatchers.IO) { pollLoop() }
        }
    }

    /** Stops navigation, polling and the SDK connection. Call when the screen closes. */
    fun shutdown() {
        pollJob?.cancel()
        runCatching { RobotApi.getInstance().unregisterStatusListener(speedStatus) }
        runCatching { bridge.disconnect() }
        runCatching { tts.disconnect() }
    }

    // ---- privacy areas ------------------------------------------------------------------

    /**
     * Marks a circular area around the selected point as private ([ZONE_RADIUS_M], polygon with [ZONE_SEGMENTS] corners).
     * From then on, drives into or through it are refused or aborted, with a spoken German announcement.
     * Kept in memory for this session only.
     */
    fun makeSelectedAreaPrivate() {
        val point = _selected.value ?: return log.i(TAG, "select a point first")
        val zone = Zone(
            name = "Privat: ${point.name}",
            polygon = circlePolygon(point.position, ZONE_RADIUS_M, ZONE_SEGMENTS),
            privacy = PrivacyLevel.PRIVATE
        )
        synchronized(controller) {
            guard.updateZones(guard.zones.withZone(zone))
            _privateZones.value = guard.zones.privateZones
        }
        idleViolationZone = null
        log.i(TAG, "PRIVATE area \"${zone.name}\": radius $ZONE_RADIUS_M m around ${fmt(point.position)}, " +
            "plus ${guard.margin} m margin → the robot stops about %.1f m from the centre".format(ZONE_RADIUS_M + guard.margin))
    }

    // ---- drawing a private area by hand ----------------------------------------------------

    /** Corners of the area being drawn, in world coordinates; null when not in drawing mode. */
    private val _drawingVertices = MutableStateFlow<List<Point2D>?>(null)
    val drawingVertices: StateFlow<List<Point2D>?> = _drawingVertices.asStateFlow()

    private var drawnAreaCounter = 0

    /** A tap on the map: adds a corner while drawing an area, otherwise adds a temporary point. */
    fun handleMapTap(position: Point2D) {
        val corners = _drawingVertices.value
        if (corners == null) {
            addPoint(position)
        } else {
            _drawingVertices.value = corners + position
            log.i(TAG, "area corner ${corners.size + 1} at ${fmt(position)}")
        }
    }

    fun startDrawingArea() {
        _drawingVertices.value = emptyList()
        log.i(TAG, "drawing a private area: tap the map to add corners, then Finish")
    }

    fun undoDrawingVertex() {
        val corners = _drawingVertices.value ?: return
        if (corners.isNotEmpty()) _drawingVertices.value = corners.dropLast(1)
    }

    fun cancelDrawingArea() {
        if (_drawingVertices.value == null) return
        _drawingVertices.value = null
        log.i(TAG, "drawing cancelled")
    }

    /**
     * Turns the drawn corners (at least 3, in drawing order) into a private [Zone], enforced exactly like the circular
     * areas: refused targets, aborted routes, spoken announcement. The polygon is taken as drawn; a self-crossing outline
     * still works with the ray-casting test but may not cover the intended region.
     */
    fun finishDrawingArea() {
        val corners = _drawingVertices.value ?: return
        if (corners.size < 3) return log.i(TAG, "an area needs at least 3 corners (have ${corners.size})")
        drawnAreaCounter++
        val zone = Zone(name = "Privat: Bereich $drawnAreaCounter", polygon = corners, privacy = PrivacyLevel.PRIVATE)
        synchronized(controller) {
            guard.updateZones(guard.zones.withZone(zone))
            _privateZones.value = guard.zones.privateZones
        }
        _drawingVertices.value = null
        idleViolationZone = null
        log.i(TAG, "PRIVATE area \"${zone.name}\" drawn with ${corners.size} corners: " + corners.joinToString { fmt(it) } +
            ", plus ${guard.margin} m margin")
        _pose.value?.let { if (zone.containsWithMargin(it.point, guard.margin)) log.i(TAG, "note: the robot is currently inside this area") }
    }

    fun clearPrivateAreas() {
        synchronized(controller) {
            guard.updateZones(MapZones(guard.zones.mapName))
            _privateZones.value = emptyList()
        }
        idleViolationZone = null
        log.i(TAG, "private areas cleared")
    }

    /**
     * After a stop at a private area: let the robot come to rest, then turn ON THE SPOT to face away from private areas
     * (EscapeHeading: a direction clear of every private area for at least 1 m, else the clearest one). No driving
     * backwards: the SDK has no obstacle avoidance for backward motion. STOP ends the turn.
     */
    private fun turnAwayAfterPrivacyStop() {
        scope.launch(Dispatchers.IO) {
            delay(TURN_AWAY_DELAY_MS)
            val pose = _pose.value ?: return@launch log.i(TAG, "turn away skipped: no pose")
            val choice = EscapeHeading.choose(pose, _privateZones.value)
                ?: return@launch log.i(TAG, "turn away skipped: no private areas")
            log.i(TAG, "turn away: heading %.0f° → %.0f° (turn %+.0f°), free line of sight %.2f m%s".format(
                Math.toDegrees(pose.theta), Math.toDegrees(choice.heading), Math.toDegrees(choice.turn), choice.freeDistance,
                if (choice.meetsRequirement) "" else " (no direction clear for ${EscapeHeading.DEFAULT_REQUIRED_FREE_M} m, using the clearest)"))
            if (abs(Math.toDegrees(choice.turn)) < MIN_TURN_DEG) return@launch log.i(TAG, "already facing away, no turn")
            _navState.value = "turning away from private area"
            runCatching {
                bridge.turnInPlace(choice.turn) { ok ->
                    val now = _pose.value?.let { "%.0f°".format(Math.toDegrees(it.theta)) } ?: "?"
                    log.i(TAG, "turn away ${if (ok) "finished" else "FAILED"}, heading now $now (target %.0f°)".format(Math.toDegrees(choice.heading)))
                    _navState.value = if (ok) "stopped, facing away from private area" else "turn away failed"
                }
            }.onFailure { log.i(TAG, "turn away threw: $it") }
        }
    }

    /** Says the privacy stop sentence in German and logs it. */
    private fun announcePrivacyStop(reason: String) {
        log.i(TAG, "PRIVACY STOP ($reason), saying: \"$PRIVACY_STOP_SENTENCE\"")
        tts.speakGerman(PRIVACY_STOP_SENTENCE, object : TtsListener {
            override fun onFailed(failure: TtsFailure) = log.i(TAG, "announcement not spoken: $failure")
        })
    }

    /** Sets the speed used by the next drive (a drive already running keeps its speed). */
    fun setSpeed(preset: SpeedPreset) {
        _speed.value = preset
        bridge.linearSpeed = preset.linear
        bridge.angularSpeed = preset.angular
        log.i(TAG, "speed: ${preset.label}" + (preset.linear?.let { " (linear $it, angular ${preset.angular})" } ?: " (RobotOS default)"))
    }

    /** Reloads the current map and the saved places, e.g. after a map change in the map tool. */
    fun reload() {
        scope.launch(Dispatchers.IO) { reloadMapAndPlaces() }
    }

    /** Adds a point at [position] (from a tap on the map) and selects it. */
    fun addPoint(position: Point2D) {
        customCounter++
        val point = MapPoint("P$customCounter", position, saved = false)
        _customPoints.value = _customPoints.value + point
        _selected.value = point
        log.i(TAG, "point ${point.name} added at ${fmt(position)} and selected")
    }

    fun select(point: MapPoint) {
        _selected.value = point
        log.i(TAG, "selected ${point.name} ${fmt(point.position)}")
    }

    /** Removes all temporary tapped points; saved places and named locations stay. */
    fun clearCustomPoints() {
        _customPoints.value = _customPoints.value.filter { it.persistent }
        _selected.value?.let { if (!it.saved && !it.persistent) _selected.value = null }
        log.i(TAG, "tapped points cleared")
    }

    /**
     * Records the robot's current position as a named location, stores it for the current map and selects it.
     *
     * Only allowed while the robot is localized: localization is checked again at the moment of saving, because a
     * position read while the robot does not know where it is would point to a wrong spot on the map.
     *
     * @return null on success, otherwise a message for the tester
     */
    suspend fun saveCurrentPosition(rawName: String): String? = withContext(Dispatchers.IO) {
        val name = rawName.trim()
        if (name.isEmpty()) return@withContext "Please enter a name."
        if (name.length > MAX_NAME_LENGTH) return@withContext "The name is too long (at most $MAX_NAME_LENGTH characters)."
        if ((_places.value + _customPoints.value).any { it.name.equals(name, ignoreCase = true) }) {
            return@withContext "A location named \"$name\" already exists."
        }
        val map = _mapName.value
        if (map.isNullOrBlank()) return@withContext "No map is active on the robot."

        val api = RobotApi.getInstance()
        val localized = runCatching { api.isRobotEstimate() }.getOrDefault(false)
        _localized.value = localized
        if (!localized) {
            log.i(TAG, "save \"$name\" refused: robot not localized")
            return@withContext "The robot is not localized, so its position is unknown. Relocalize it first."
        }
        val pose = runCatching { api.getCurrentPose() }.getOrNull()
            ?: return@withContext "Could not read the robot's position."

        val position = Point2D(pose.x.toDouble(), pose.y.toDouble())
        val theta = pose.theta.toDouble()
        runCatching { pointStore.save(map, pointStore.load(map) + SavedPoint(name, position, theta)) }
            .onFailure { return@withContext "Could not store the location: $it" }

        val point = MapPoint(name, position, saved = false, persistent = true)
        _customPoints.value = _customPoints.value + point
        _selected.value = point
        log.i(TAG, "location \"$name\" saved at ${fmt(position)}, heading %.0f° (map %s)".format(Math.toDegrees(theta), map))
        null
    }

    /** Deletes the selected named location from the list and from storage. Does nothing for other point types. */
    fun deleteSelectedLocation() {
        val point = _selected.value?.takeIf { it.persistent } ?: return
        val map = _mapName.value ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { pointStore.save(map, pointStore.load(map).filterNot { it.name == point.name }) }
                .onSuccess {
                    _customPoints.value = _customPoints.value.filterNot { it.persistent && it.name == point.name }
                    if (_selected.value == point) _selected.value = null
                    log.i(TAG, "location \"${point.name}\" deleted")
                }
                .onFailure { log.i(TAG, "could not delete \"${point.name}\": $it") }
        }
    }

    /**
     * Drives to the selected point by its coordinates, through [NavigationController]:
     *  - target inside a private area (incl. margin) → refused before moving, announcement spoken
     *  - robot enters a private area on the way → navigation stopped, announcement spoken, no automatic resume
     * Saved RobotOS places are driven to by coordinates too, so the pre-move check always sees the real target.
     * Events and the result are logged and shown in [navState].
     */
    fun driveToSelected() {
        val target = _selected.value ?: return log.i(TAG, "select a point first: tap the map or a list entry")
        if (_localized.value == false) {
            log.i(TAG, "warning: the robot reports it is NOT localized; navigation will most likely fail (NotLocalized)")
        }
        if (!_sdkActive.value) {
            log.i(TAG, "warning: no SDK control; start this screen from the robot's home launcher icon")
        }
        log.i(TAG, "DRIVE to ${target.name} ${fmt(target.position)}, speed ${_speed.value.label}, private areas: ${_privateZones.value.size}")
        _navState.value = "driving to ${target.name}"

        val listener = object : NavigationListener {
            /** The first outcome wins: after a privacy abort, RobotOS still reports its own "stopped" failure. */
            @Volatile
            private var finished = false

            override fun onProgress(event: NavEvent) {
                if (finished) return
                log.i(TAG, "  ${target.name}: $event")
                _navState.value = "${target.name}: $event"
            }

            override fun onArrived() {
                if (finished) return
                finished = true
                log.i(TAG, "ARRIVED at ${target.name}")
                _navState.value = "arrived at ${target.name}"
            }

            override fun onFailed(failure: NavFailure) {
                if (finished) return
                finished = true
                when (failure) {
                    is NavFailure.BlockedByPrivacy -> {
                        log.i(TAG, "REFUSED: target ${target.name} lies in private area \"${failure.zone.name}\"")
                        _navState.value = "refused: target in ${failure.zone.name}"
                        announcePrivacyStop("target inside ${failure.zone.name}")
                    }
                    is NavFailure.AbortedOnPrivateZoneEntry -> {
                        log.i(TAG, "ABORTED at ${_pose.value?.let { fmt(it.point) }}: route entered private area \"${failure.zone.name}\"")
                        _navState.value = "stopped before ${failure.zone.name}"
                        announcePrivacyStop("route entered ${failure.zone.name}")
                        turnAwayAfterPrivacyStop()
                    }
                    else -> {
                        log.i(TAG, "FAILED to reach ${target.name}: $failure")
                        _navState.value = "failed: $failure"
                    }
                }
            }
        }
        runCatching { synchronized(controller) { controller.goTo(target.position, listener) } }
            .onFailure {
                log.i(TAG, "navigation call threw: $it")
                _navState.value = "error: $it"
            }
    }

    // ---- no-go line test ---------------------------------------------------------------

    /** Uses the selected point as end A ([isA]) or B of the line to block. */
    fun setLineEnd(isA: Boolean) {
        val point = _selected.value ?: return log.i(TAG, "select a point first")
        if (isA) _lineA.value = point else _lineB.value = point
        _lineCandidate.value = null
        log.i(TAG, "no-go test: ${if (isA) "A" else "B"} = ${point.name} ${fmt(point.position)}")
    }

    /**
     * Computes the line that blocks the way between A and B (across the midpoint, wall to wall) and shows it in red.
     * Nothing is written.
     */
    fun previewNoGoLine() {
        val a = _lineA.value ?: return log.i(TAG, "set A first")
        val b = _lineB.value ?: return log.i(TAG, "set B first")
        val map = _pgm.value ?: return log.i(TAG, "map.pgm is not loaded, cannot compute the line")
        runCatching { map.lineAcross(a.position, b.position) }
            .onSuccess { line ->
                _lineCandidate.value = line
                log.i(TAG, "preview: no-go line across ${a.name}–${b.name}: ${fmt(line.first)} → ${fmt(line.second)}, " +
                    "%.2f m (red on the map)".format(map.lengthOf(line.first, line.second)))
            }
            .onFailure { log.i(TAG, "preview failed: $it") }
    }

    /**
     * Writes the previewed line into the robot's map (see [NoGoLineWriter]) and reloads the map so it shows in blue.
     * @return a message for the tester
     */
    suspend fun writeNoGoLine(): String = withContext(Dispatchers.IO) {
        val map = _mapName.value ?: return@withContext "No map is active on the robot."
        val line = _lineCandidate.value ?: return@withContext "Preview a line first."
        if (!_sdkActive.value) return@withContext "No SDK control: start this screen from the robot's home launcher icon."
        log.i(TAG, "WRITING no-go line into map \"$map\"")
        runCatching { noGoWriter.write(map, line.first, line.second) }.fold(
            onSuccess = { message ->
                _lineCandidate.value = null
                reloadMapAndPlaces()
                "$message. Now drive from A to B and watch what the robot does."
            },
            onFailure = { e ->
                log.i(TAG, "writing the no-go line FAILED: $e")
                "Failed: $e"
            }
        )
    }

    /** Restores the map image as it was before RoboGuard's first change and reloads the map. */
    suspend fun restoreOriginalMap(): String = withContext(Dispatchers.IO) {
        val map = _mapName.value ?: return@withContext "No map is active on the robot."
        if (!_sdkActive.value) return@withContext "No SDK control: start this screen from the robot's home launcher icon."
        log.i(TAG, "RESTORING the original map image of \"$map\"")
        runCatching { noGoWriter.restore(map) }.fold(
            onSuccess = { message -> reloadMapAndPlaces(); message },
            onFailure = { e ->
                log.i(TAG, "restore FAILED: $e")
                "Failed: $e"
            }
        )
    }

    /** Stops any navigation immediately. */
    fun stop(reason: String) {
        runCatching { synchronized(controller) { controller.stop() } }
            .onFailure { log.i(TAG, "stopNavigation threw: $it") }
        _navState.value = "stopped ($reason)"
        log.i(TAG, "STOP: $reason")
    }

    // ---- internals ----------------------------------------------------------------

    private fun reloadMapAndPlaces() {
        val api = RobotApi.getInstance()
        val name = runCatching { api.getMapName() }.getOrNull()
        _mapName.value = name
        // Zones are bound to a map: coordinates from one map are meaningless on another.
        if (guard.zones.mapName != (name ?: "")) {
            synchronized(controller) {
                guard.updateZones(MapZones(name ?: ""))
                _privateZones.value = emptyList()
            }
        }
        log.i(TAG, "current map: ${name ?: "none"}")
        if (name.isNullOrBlank()) {
            log.i(TAG, "no map is active on the robot; create or select one in the map tool")
        } else {
            loadMap(name)
        }

        val poses = runCatching { api.getPlaceList() }.getOrNull().orEmpty()
        _places.value = poses.map { MapPoint(it.name ?: "(unnamed)", Point2D(it.x.toDouble(), it.y.toDouble()), saved = true) }
        log.i(TAG, "saved places (${poses.size}): " + _places.value.joinToString { "${it.name} ${fmt(it.position)}" })

        // RoboGuard's own named locations for this map, followed by any tapped points that already exist.
        val stored = if (name.isNullOrBlank()) emptyList() else pointStore.load(name)
        _customPoints.value = stored.map { MapPoint(it.name, it.position, saved = false, persistent = true) } +
            _customPoints.value.filter { !it.persistent }
        log.i(TAG, "my locations (${stored.size}): " + stored.joinToString { "${it.name} ${fmt(it.position)}" })
    }

    private fun loadMap(name: String) {
        runCatching { RobotMapFile.load(name) }
            .onSuccess { map ->
                val bounds = map.knownBounds(margin = MAP_MARGIN_CELLS)
                _rendered.value = RenderedMap(map, renderBitmap(map, bounds), bounds)
                log.i(
                    TAG,
                    "map loaded: ${map.width}×${map.height} cells at ${map.resolution} m, origin (${map.originX}, ${map.originY}), " +
                        "showing ${bounds.width}×${bounds.height} cells"
                )
            }
            .onFailure { log.i(TAG, "could not load the map file: $it (storage permission granted?)") }

        _pgm.value = runCatching { PgmMap.load(name) }
            .onSuccess { g ->
                log.i(TAG, "map.pgm loaded: ${g.width}×${g.height} px at ${g.resolution} m, origin (${g.originX}, ${g.originY}), " +
                    "${g.noGoPixels().size} no-go pixels (blue)")
            }
            .onFailure { log.i(TAG, "could not load map.pgm (no-go lines not shown): $it") }
            .getOrNull()
        _hasMapBackup.value = noGoWriter.hasBackup(name)
    }

    /** Polls the pose every [POLL_MS] and localization / SDK control every few polls. */
    private suspend fun pollLoop() {
        val api = RobotApi.getInstance()
        var tick = 0
        while (currentCoroutineContext().isActive) {
            runCatching { api.getCurrentPose() }.getOrNull()?.let { p ->
                val pose = RobotPose(p.x.toDouble(), p.y.toDouble(), p.theta.toDouble(), PoseStatus.fromCode(p.status))
                _pose.value = pose
                // In-motion privacy enforcement: aborts a drive as soon as the robot is inside a private area.
                synchronized(controller) { controller.onPose(pose) }
                if (idleViolationZone != null && _privateZones.value.none { it.containsWithMargin(pose.point, guard.margin) }) {
                    idleViolationZone = null
                }
            }
            if (tick % STATUS_EVERY_N_POLLS == 0) {
                val localized = runCatching { api.isRobotEstimate() }.getOrNull()
                if (localized != _localized.value) log.i(TAG, "localized: $localized")
                _localized.value = localized
                _sdkActive.value = runCatching { api.isApiConnectedService() && api.isActive() }.getOrDefault(false)
            }
            tick++
            // Faster while driving: at 0.25 m/s the robot moves ~4 cm between checks instead of ~12 cm.
            delay(if (controller.isNavigating) POLL_NAVIGATING_MS else POLL_MS)
        }
    }

    /** Unknown = light grey, free = white, likely occupied = dark; top bitmap row = highest world y. */
    private fun renderBitmap(map: RobotMapFile, bounds: CellBounds): Bitmap {
        val w = bounds.width
        val h = bounds.height
        val pixels = IntArray(w * h)
        for (by in 0 until h) {
            val row = bounds.maxRow - by
            for (bx in 0 until w) {
                pixels[by * w + bx] = colorFor(map.cellValue(bounds.minCol + bx, row))
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun colorFor(value: Int): Int = when {
        value == RobotMapFile.UNKNOWN -> 0xFFD0D0D0.toInt()
        value == RobotMapFile.FREE -> 0xFFFFFFFF.toInt()
        value >= OCCUPIED_VALUE -> 0xFF202020.toInt()
        else -> {
            val g = 255 - value * 150 / OCCUPIED_VALUE
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    private fun fmt(p: Point2D) = "(%.2f, %.2f)".format(p.x, p.y)

    /** A regular polygon approximating a circle, counter-clockwise. */
    private fun circlePolygon(center: Point2D, radius: Double, segments: Int): List<Point2D> =
        (0 until segments).map { i ->
            val angle = 2 * PI * i / segments
            Point2D(center.x + radius * cos(angle), center.y + radius * sin(angle))
        }

    private companion object {
        const val TAG = "move"
        const val POLL_MS = 500L
        const val STATUS_EVERY_N_POLLS = 4
        const val MAP_MARGIN_CELLS = 20
        const val SPEED_LOG_INTERVAL_MS = 1_000L
        const val MAX_NAME_LENGTH = 40

        /** Request ids for map-edit SDK calls, apart from other components' ranges. */
        const val NO_GO_REQ_ID_START = 40_000

        /** Pose poll interval while a drive is running (privacy checks happen on each poll). */
        const val POLL_NAVIGATING_MS = 150L

        /** Turning away after a privacy stop: wait until the robot is at rest; turns smaller than this are skipped. */
        const val TURN_AWAY_DELAY_MS = 800L
        const val MIN_TURN_DEG = 10.0

        /** Radius of a private area created around a point; PrivacyGuard adds its margin on top. */
        const val ZONE_RADIUS_M = 1.0
        const val ZONE_SEGMENTS = 24

        /** Spoken (German) when a drive is refused or stopped because of a private area. Owner's wording. */
        const val PRIVACY_STOP_SENTENCE = "Weg führt durch privaten Bereich. Ich halte an und fahre nicht weiter."

        /** Cell values at or above this are drawn as walls. Seen on the robot: 0, 1, 5267, 8507, 12288, 32767. */
        const val OCCUPIED_VALUE = 8000
    }
}
