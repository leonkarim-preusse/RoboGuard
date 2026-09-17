package com.example.robocontrol.movement

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.example.robocontrol.audio.OrionStarTts
import com.example.robocontrol.audio.TtsFailure
import com.example.robocontrol.audio.TtsListener
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
    DEFAULT("Default", null, null)
}

/**
 * State and actions behind RoboGuard's "Navigation and Map" screen ([MapNavigationActivity]): the current RobotOS map
 * with the robot's live position, saved places, named locations and tapped points; driving to a selected point; and
 * private areas (circle around a point or drawn polygon) that every drive must respect.
 *
 * Copied from the movement test (`movementprobe.MovementProbe`, which stays for dedicated hardware tests) without the
 * no-go line experiment, which wrote the robot's map and did not work.
 *
 * Every drive goes through [NavigationController] + [PrivacyGuard]: a target inside a private area is refused before
 * moving; a route that enters one is stopped (pose checked every [POLL_NAVIGATING_MS] ms while driving), with a spoken
 * German announcement and a turn on the spot away from the area.
 *
 * Data sources (verified on the robot): map from `RobotMapFile` (`/sdcard/robot/map/<name>/navi_data/`, needs
 * READ_EXTERNAL_STORAGE); map name, places, pose and localization from synchronous `RobotApi` calls.
 *
 * Must run while RoboGuard holds SDK control, i.e. the app was started from the robot's home launcher (activation is
 * per package, so opening this screen from MainActivity works).
 *
 * Private areas are saved per map, encrypted ([ZoneRegistry] + [KeystoreZoneCipher], `files/zones/<map>.zones`), on
 * every change, and loaded with the map, so they survive closing the screen, app restarts and reboots. If a saved file
 * exists but cannot be read (damaged, or the Keystore key is gone), the screen FAILS CLOSED: driving and editing areas
 * are blocked until the user resets the private areas.
 */
class MapNavigation(
    context: Context,
    private val log: NavigationLog,
    private val scope: CoroutineScope
) {
    private val bridge = OrionStarBridge(context)
    private val pointStore = SavedPointStore(context)
    private val zoneRegistry = ZoneRegistry(context.filesDir, KeystoreZoneCipher())

    /** Set when the saved private areas of the current map exist but cannot be read: driving and editing are blocked. */
    private val _zoneStoreError = MutableStateFlow<String?>(null)
    val zoneStoreError: StateFlow<String?> = _zoneStoreError.asStateFlow()

    /** Set when the latest change to the private areas could not be saved (they still apply until the screen closes). */
    private val _zoneSaveWarning = MutableStateFlow<String?>(null)
    val zoneSaveWarning: StateFlow<String?> = _zoneSaveWarning.asStateFlow()

    /**
     * False until the saved areas of the current map were read. Before that, driving is refused (areas not known yet)
     * and editing is refused (a save would overwrite the stored areas with an incomplete set).
     */
    private val _zonesLoaded = MutableStateFlow(false)
    val zonesLoaded: StateFlow<Boolean> = _zonesLoaded.asStateFlow()

    /**
     * Set when the saved named locations of the current map exist but cannot be read. Saving and deleting locations is
     * then refused (it would overwrite them) until they are reset; driving stays allowed.
     */
    private val _pointStoreError = MutableStateFlow<String?>(null)
    val pointStoreError: StateFlow<String?> = _pointStoreError.asStateFlow()

    /** Orders background saves: a save older than the last one written is skipped. */
    private var zoneSaveSeq = 0L
    private var zoneWrittenSeq = 0L

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

    /** Keeps [measuredSpeed] current. */
    private val speedStatus = object : StatusListener() {
        override fun onStatusUpdate(type: String?, value: String?) {
            _measuredSpeed.value = value
        }
    }

    private var pollJob: Job? = null
    private var customCounter = 0

    /** Connects to the SDK; once connected, loads map and places and starts polling the pose. */
    fun start() {
        log.section("Navigation and Map")
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
        PrivacyOverridePrompt.cancel()
        runCatching { RobotApi.getInstance().unregisterStatusListener(speedStatus) }
        runCatching { bridge.disconnect() }
        runCatching { tts.disconnect() }
    }

    // ---- privacy areas ------------------------------------------------------------------

    /**
     * Marks a circular area named [rawName] around the selected point as private ([ZONE_RADIUS_M], polygon with
     * [ZONE_SEGMENTS] corners). From then on, drives into or through it are refused or aborted, with a spoken German
     * announcement and a question on the screen. Saved.
     *
     * @return null on success, otherwise a message for the name dialog
     */
    fun makeSelectedAreaPrivate(rawName: String): String? {
        val point = _selected.value ?: return "Select a point first."
        lockedMessage()?.let { return it }
        val name = validateAreaName(rawName).let { (ok, error) -> ok ?: return error }
        val zone = Zone(name = name, polygon = circlePolygon(point.position, ZONE_RADIUS_M, ZONE_SEGMENTS), privacy = PrivacyLevel.PRIVATE)
        changeZones { it.withZone(zone) }
        idleViolationZone = null
        log.i(TAG, "PRIVATE area \"${zone.name}\": radius $ZONE_RADIUS_M m around ${fmt(point.position)}, " +
            "plus ${guard.margin} m margin → the robot stops about %.1f m from the centre".format(ZONE_RADIUS_M + guard.margin))
        return null
    }

    /** Default name for a new area: "Bereich N", counting up from the highest number already used on this map. */
    fun nextAreaName(): String {
        val names = guard.zones.zones.map { it.name }
        var n = (names.mapNotNull { AREA_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        while (names.any { it.equals("Bereich $n", ignoreCase = true) }) n++
        return "Bereich $n"
    }

    /** Trimmed name, or an error message: empty, too long, or already used by another area of this map. */
    private fun validateAreaName(raw: String): Pair<String?, String?> {
        val name = raw.trim()
        return when {
            name.isEmpty() -> null to "Please enter a name."
            name.length > MAX_NAME_LENGTH -> null to "The name is too long (at most $MAX_NAME_LENGTH characters)."
            guard.zones.byName(name) != null -> null to "An area named \"$name\" already exists."
            else -> name to null
        }
    }

    /**
     * Deletes the single private area [name] (also from storage) and ends any temporary permission for it.
     */
    fun removePrivateArea(name: String) {
        if (zonesLocked()) return
        if (guard.zones.byName(name) == null) return
        synchronized(controller) { guard.revokeZoneOverride(name, revokedBy = "area deleted on screen") }
        changeZones { it.withoutZone(name) }
        refreshOverrides()
        idleViolationZone = null
        log.i(TAG, "private area \"$name\" deleted")
    }

    // ---- drawing a private area by hand ----------------------------------------------------

    /** Corners of the area being drawn, in world coordinates; null when not in drawing mode. */
    private val _drawingVertices = MutableStateFlow<List<Point2D>?>(null)
    val drawingVertices: StateFlow<List<Point2D>?> = _drawingVertices.asStateFlow()

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
     * Turns the drawn corners (at least 3, in drawing order) into a private [Zone] named [rawName], enforced exactly like
     * the circular areas. The polygon is taken as drawn; a self-crossing outline still works with the ray-casting test
     * but may not cover the intended region.
     *
     * @return null on success (drawing ends), otherwise a message for the name dialog (drawing stays open)
     */
    fun finishDrawingArea(rawName: String): String? {
        val corners = _drawingVertices.value ?: return "Not drawing an area."
        if (corners.size < 3) return "An area needs at least 3 corners (have ${corners.size})."
        lockedMessage()?.let { return it }
        val name = validateAreaName(rawName).let { (ok, error) -> ok ?: return error }
        val zone = Zone(name = name, polygon = corners, privacy = PrivacyLevel.PRIVATE)
        changeZones { it.withZone(zone) }
        _drawingVertices.value = null
        idleViolationZone = null
        log.i(TAG, "PRIVATE area \"${zone.name}\" drawn with ${corners.size} corners: " + corners.joinToString { fmt(it) } +
            ", plus ${guard.margin} m margin")
        _pose.value?.let { if (zone.containsWithMargin(it.point, guard.margin)) log.i(TAG, "note: the robot is currently inside this area") }
        return null
    }

    /**
     * Removes all private areas of the current map, also from storage. This is also the way out when the saved areas
     * could not be read: the unreadable file is deleted and the areas can be drawn again.
     */
    fun clearPrivateAreas() {
        if (!_zonesLoaded.value && _zoneStoreError.value == null) return log.i(TAG, "private areas are not loaded yet")
        val map = guard.zones.mapName
        synchronized(controller) {
            guard.updateZones(MapZones(map))
            _privateZones.value = emptyList()
        }
        idleViolationZone = null
        synchronized(controller) { guard.activeZoneOverrides().keys.forEach { guard.revokeZoneOverride(it, "areas cleared on screen") } }
        refreshOverrides()
        val wasUnreadable = _zoneStoreError.value != null
        scope.launch(Dispatchers.IO) {
            synchronized(zoneRegistry) {
                zoneWrittenSeq = ++zoneSaveSeq
                val deleted = map.isBlank() || !zoneRegistry.exists(map) || zoneRegistry.delete(map)
                if (deleted) {
                    _zoneStoreError.value = null
                    _zoneSaveWarning.value = null
                    log.i(TAG, if (wasUnreadable) "unreadable private areas deleted; areas can be drawn again" else "private areas cleared and deleted from storage")
                } else {
                    _zoneSaveWarning.value = "Could not delete the saved private areas; they will come back after a restart."
                    log.i(TAG, "private areas cleared, but deleting the saved file FAILED")
                }
            }
        }
    }

    /** True (and logged) while the saved areas are unreadable: editing them now would overwrite what could still be recovered. */
    private fun zonesLocked(): Boolean = lockedMessage()?.also { log.i(TAG, it) } != null

    private fun lockedMessage(): String? = when {
        !_zonesLoaded.value -> "The private areas are not loaded yet; try again in a moment."
        _zoneStoreError.value != null -> "Private areas cannot be changed: ${_zoneStoreError.value}"
        else -> null
    }

    /** Applies [update] to the zones in force, then saves the result for the current map in the background. */
    private fun changeZones(update: (MapZones) -> MapZones) {
        val snapshot = synchronized(controller) {
            guard.updateZones(update(guard.zones))
            _privateZones.value = guard.zones.privateZones
            guard.zones
        }
        if (snapshot.mapName.isBlank()) {
            _zoneSaveWarning.value = "No map active: private areas are not saved."
            return
        }
        val seq = synchronized(zoneRegistry) { ++zoneSaveSeq }
        scope.launch(Dispatchers.IO) {
            synchronized(zoneRegistry) {
                if (seq < zoneWrittenSeq) return@synchronized // a newer change was written already
                runCatching { zoneRegistry.save(snapshot) }
                    .onSuccess {
                        zoneWrittenSeq = seq
                        _zoneSaveWarning.value = null
                        log.i(TAG, "private areas saved (${snapshot.zones.size}, encrypted)")
                    }
                    .onFailure {
                        _zoneSaveWarning.value = "Private areas could NOT be saved: $it"
                        log.i(TAG, "saving private areas FAILED: $it")
                    }
            }
        }
    }

    /**
     * Loads the saved private areas of [map] into the guard. A missing file means none; an unreadable one blocks
     * driving and editing ([zoneStoreError]) instead of silently driving as if nothing were private.
     */
    private fun loadZones(map: String?) {
        if (map.isNullOrBlank()) {
            synchronized(controller) {
                guard.updateZones(MapZones(""))
                _privateZones.value = emptyList()
            }
            _zoneStoreError.value = null
            _zonesLoaded.value = true
            return
        }
        val loaded = synchronized(zoneRegistry) { runCatching { zoneRegistry.load(map) } }
        loaded.onSuccess { zones ->
            synchronized(controller) {
                guard.updateZones(zones)
                _privateZones.value = zones.privateZones
            }
            _zoneStoreError.value = null
            _zonesLoaded.value = true
            log.i(TAG, "private areas loaded: ${zones.privateZones.size}" +
                zones.privateZones.joinToString(prefix = if (zones.privateZones.isEmpty()) "" else " (", postfix = if (zones.privateZones.isEmpty()) "" else ")") { it.name })
        }.onFailure { e ->
            synchronized(controller) {
                guard.updateZones(MapZones(map))
                _privateZones.value = emptyList()
            }
            _zoneStoreError.value = "The saved private areas of this map cannot be read (${e.cause ?: e}). " +
                "Driving is blocked. Reset the private areas to continue."
            log.i(TAG, "LOADING PRIVATE AREAS FAILED, driving blocked: $e / ${e.cause}")
        }
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

    // ---- temporary permission to cross a private area ---------------------------------------

    /** Zone name → expiry time (ms since epoch) of each active temporary permission, for display. */
    private val _activeOverrides = MutableStateFlow<Map<String, Long>>(emptyMap())
    val activeOverrides: StateFlow<Map<String, Long>> = _activeOverrides.asStateFlow()

    /** Re-reads the active permissions from the guard (which also drops and audits expired ones). */
    private fun refreshOverrides() {
        val current = synchronized(controller) { guard.activeZoneOverrides().mapValues { it.value.expiresAt } }
        val before = _activeOverrides.value
        (before.keys - current.keys).forEach { log.i(TAG, "temporary permission for \"$it\" ended") }
        if (current != before) _activeOverrides.value = current
    }

    /** Ends the temporary permission for area [name] early. A drive currently inside that area is then stopped. */
    fun revokeCrossingPermission(name: String) {
        synchronized(controller) { guard.revokeZoneOverride(name, revokedBy = "revoked on screen") }
        refreshOverrides()
        log.i(TAG, "temporary permission for \"$name\" revoked")
    }

    /**
     * Asks on the screen (popup [PrivacyOverrideActivity]) whether the robot may temporarily cross [zone] on its way to
     * [target]. Yes → permission for that one area for the chosen minutes, then the drive to [target] starts again.
     * No → the robot stays stopped; after a stop on the way it turns away from the area.
     */
    private fun askToCross(zone: Zone, target: MapPoint, stoppedOnTheWay: Boolean) {
        log.i(TAG, "asking on screen: allow crossing \"${zone.name}\"?")
        PrivacyOverridePrompt.ask(zone.name) { minutes ->
            // An answer ends the announcement that may still be running, and the robot confirms the answer.
            runCatching { tts.stop() }
            speakGerman(
                if (minutes == null) "Keine Erlaubnis für ${zone.name} erteilt, stoppe Navigation"
                else "Ich darf ${zone.name} für ${germanMinutes(minutes)} betreten und setze meinen Weg fort"
            )
            if (minutes == null) {
                log.i(TAG, "crossing \"${zone.name}\" NOT allowed; robot stays stopped")
                _navState.value = "not allowed to cross ${zone.name}"
                if (stoppedOnTheWay) turnAwayAfterPrivacyStop()
                return@ask
            }
            val granted = runCatching {
                synchronized(controller) {
                    guard.grantZoneOverride(zone.name, OverrideReason.UserSummoned, minutes * 60_000L, grantedBy = "person at robot screen")
                }
            }
            granted.onFailure { log.i(TAG, "permission could not be granted: $it") }.onSuccess {
                refreshOverrides()
                log.i(TAG, "crossing \"${zone.name}\" ALLOWED for $minutes min; driving on to ${target.name}")
                driveTo(target)
            }
        }
    }

    private fun germanMinutes(minutes: Int) = if (minutes == 1) "eine Minute" else "$minutes Minuten"

    private fun speakGerman(sentence: String) {
        log.i(TAG, "saying: \"$sentence\"")
        tts.speakGerman(sentence, object : TtsListener {
            override fun onFailed(failure: TtsFailure) = log.i(TAG, "not spoken: $failure")
        })
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
        _pointStoreError.value?.let { return@withContext it }

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

    /**
     * Deletes all saved named locations of the current map, including an unreadable file. The way out when
     * [pointStoreError] is set.
     */
    fun resetSavedLocations() {
        val map = _mapName.value ?: return
        scope.launch(Dispatchers.IO) {
            if (runCatching { pointStore.delete(map) }.getOrDefault(false)) {
                _pointStoreError.value = null
                _customPoints.value = _customPoints.value.filter { !it.persistent }
                _selected.value?.let { if (it.persistent) _selected.value = null }
                log.i(TAG, "saved locations of \"$map\" deleted")
            } else {
                log.i(TAG, "deleting the saved locations FAILED")
            }
        }
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
        driveTo(target)
    }

    private fun driveTo(target: MapPoint) {
        if (SystemClock.elapsedRealtime() - lastPoseAt > POSE_TIMEOUT_MS) {
            _navState.value = "refused: robot position unknown"
            log.i(TAG, "DRIVE REFUSED: no current robot position, so private areas could not be enforced")
            speakGerman(POSITION_UNKNOWN_SENTENCE)
            return
        }
        if (!_zonesLoaded.value) {
            _navState.value = "waiting for private areas to load"
            return log.i(TAG, "DRIVE REFUSED: private areas not loaded yet")
        }
        _zoneStoreError.value?.let { error ->
            _navState.value = "blocked: private areas unreadable"
            return log.i(TAG, "DRIVE REFUSED: $error")
        }
        if (_localized.value == false) {
            log.i(TAG, "warning: the robot reports it is NOT localized; navigation will most likely fail (NotLocalized)")
        }
        if (!_sdkActive.value) {
            log.i(TAG, "warning: no SDK control; start RoboGuard from the robot's home launcher")
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
                        askToCross(failure.zone, target, stoppedOnTheWay = false)
                    }
                    is NavFailure.AbortedOnPrivateZoneEntry -> {
                        log.i(TAG, "ABORTED at ${_pose.value?.let { fmt(it.point) }}: route entered private area \"${failure.zone.name}\"")
                        _navState.value = "stopped before ${failure.zone.name}"
                        announcePrivacyStop("route entered ${failure.zone.name}")
                        askToCross(failure.zone, target, stoppedOnTheWay = true)
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

    /** Time of the last position read from RobotOS (elapsedRealtime); 0 = never. */
    @Volatile
    private var lastPoseAt = 0L

    /** A drive is running but no position arrived for [POSE_TIMEOUT_MS]: stop it, say why. */
    private fun stopForUnknownPosition() {
        log.i(TAG, "NO ROBOT POSITION for more than $POSE_TIMEOUT_MS ms while driving: stopping (private areas cannot be checked)")
        stop("robot position unknown")
        speakGerman(POSITION_UNKNOWN_SENTENCE)
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
        log.i(TAG, "current map: ${name ?: "none"}")
        // Zones are bound to a map: coordinates from one map are meaningless on another. Loaded from storage every time.
        loadZones(name)
        if (name.isNullOrBlank()) {
            log.i(TAG, "no map is active on the robot; create or select one in the map tool")
        } else {
            loadMap(name)
        }

        val poses = runCatching { api.getPlaceList() }.getOrNull().orEmpty()
        _places.value = poses.map { MapPoint(it.name ?: "(unnamed)", Point2D(it.x.toDouble(), it.y.toDouble()), saved = true) }
        log.i(TAG, "saved places (${poses.size}): " + _places.value.joinToString { "${it.name} ${fmt(it.position)}" })

        // RoboGuard's own named locations for this map, followed by any tapped points that already exist.
        val stored = if (name.isNullOrBlank()) {
            _pointStoreError.value = null
            emptyList()
        } else {
            runCatching { pointStore.load(name) }
                .onSuccess { _pointStoreError.value = null }
                .onFailure { e ->
                    _pointStoreError.value = "The saved locations of this map cannot be read (${e.cause ?: e}). " +
                        "Saving locations is blocked. Reset the saved locations to continue."
                    log.i(TAG, "LOADING SAVED LOCATIONS FAILED: $e / ${e.cause}")
                }
                .getOrDefault(emptyList())
        }
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

    }

    /** Polls the pose every [POLL_MS] and localization / SDK control every few polls. */
    private suspend fun pollLoop() {
        val api = RobotApi.getInstance()
        var tick = 0
        while (currentCoroutineContext().isActive) {
            runCatching { api.getCurrentPose() }.getOrNull()?.let { p ->
                val pose = RobotPose(p.x.toDouble(), p.y.toDouble(), p.theta.toDouble(), PoseStatus.fromCode(p.status))
                _pose.value = pose
                lastPoseAt = SystemClock.elapsedRealtime()
                // In-motion privacy enforcement: aborts a drive as soon as the robot is inside a private area.
                synchronized(controller) { controller.onPose(pose) }
                if (idleViolationZone != null && _privateZones.value.none { it.containsWithMargin(pose.point, guard.margin) }) {
                    idleViolationZone = null
                }
            }
            if (tick % STATUS_EVERY_N_POLLS == 0) {
                refreshOverrides() // expired permissions end here (and are logged)
                val localized = runCatching { api.isRobotEstimate() }.getOrNull()
                if (localized != _localized.value) log.i(TAG, "localized: $localized")
                _localized.value = localized
                _sdkActive.value = runCatching { api.isApiConnectedService() && api.isActive() }.getOrDefault(false)
            }
            // Fail closed: without a fresh position the in-motion privacy check cannot work, so the drive must stop.
            if (controller.isNavigating && SystemClock.elapsedRealtime() - lastPoseAt > POSE_TIMEOUT_MS) {
                stopForUnknownPosition()
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
        const val TAG = "nav"
        const val POLL_MS = 500L
        const val STATUS_EVERY_N_POLLS = 4
        const val MAP_MARGIN_CELLS = 20
        const val MAX_NAME_LENGTH = 40

        /** Longest time without a robot position before a drive is stopped / refused (fail closed). */
        const val POSE_TIMEOUT_MS = 1_000L

        /** Spoken when a drive is stopped or refused because the robot's position cannot be read. */
        const val POSITION_UNKNOWN_SENTENCE = "Ich kann meine Position gerade nicht bestimmen und halte deshalb an."

        /** Pose poll interval while a drive is running (privacy checks happen on each poll). */
        const val POLL_NAVIGATING_MS = 150L

        /** Turning away after a privacy stop: wait until the robot is at rest; turns smaller than this are skipped. */
        const val TURN_AWAY_DELAY_MS = 800L
        const val MIN_TURN_DEG = 10.0

        /** Radius of a private area created around a point; PrivacyGuard adds its margin on top. */
        const val ZONE_RADIUS_M = 1.0
        const val ZONE_SEGMENTS = 24

        /** Finds N in "Bereich N" (also older "Privat: Bereich N"), to continue the default numbering. */
        val AREA_NUMBER = Regex("""Bereich (\d+)""")

        /** Spoken (German) when a drive is refused or stopped because of a private area. Owner's wording. */
        const val PRIVACY_STOP_SENTENCE = "Dieser Weg führt mich durch einen als privat gekennzeichneten Bereich, darum bleibe ich " +
            "vorerst stehen. Wenn du mir temporär erlauben möchtest den privaten Bereich zu betreten, dann kannst du das an " +
            "meinem Bildschirm tun."

        /** Cell values at or above this are drawn as walls. Seen on the robot: 0, 1, 5267, 8507, 12288, 32767. */
        const val OCCUPIED_VALUE = 8000
    }
}
