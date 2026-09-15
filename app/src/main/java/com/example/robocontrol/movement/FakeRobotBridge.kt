package com.example.robocontrol.movement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2

/**
 * A robot that exists only in memory.
 *
 * Drives in a straight line from its current position toward the target,
 * emitting a pose every [stepMetres]. Motion is advanced by calling [tick] —
 * there is no timer — so tests are deterministic and the UI can step it by hand.
 *
 * This is what makes the privacy guard demonstrable without hardware: you can
 * place a private zone across the path and assert that navigation aborts at the
 * boundary, in a JVM unit test, with no robot and no jar.
 */
class FakeRobotBridge(
    startAt: Point2D = Point2D(0.0, 0.0),
    private val stepMetres: Double = 0.10,
    private val arrivalRadius: Double = 0.20
) : RobotBridge {

    private val _pose = MutableStateFlow<RobotPose?>(null)
    override val pose: StateFlow<RobotPose?> = _pose.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var position: Point2D = startAt
    private var heading: Double = 0.0
    private var localized = true
    private var mapName: String? = "demo-map"

    private val places = linkedMapOf<String, Point2D>()

    private var target: Point2D? = null
    private var listener: NavigationListener? = null

    /** Set to have the fake report a specific pose status, e.g. to simulate a
     *  RobotOS forbidden area authored in OrionStar's own map tool. */
    var forcedStatus: PoseStatus? = null

    // ---- lifecycle -----------------------------------------------------

    override fun connect() {
        _connected.value = true
        emitPose()
    }

    override fun disconnect() {
        stopNavigation()
        _connected.value = false
    }

    // ---- localization & map --------------------------------------------

    override fun isLocalized(callback: (Boolean) -> Unit) = callback(localized)

    fun setLocalized(value: Boolean) { localized = value }

    override fun setPoseEstimate(x: Double, y: Double, theta: Double, callback: (Boolean) -> Unit) {
        position = Point2D(x, y)
        heading = theta
        localized = true
        emitPose()
        callback(true)
    }

    override fun currentMapName(callback: (String?) -> Unit) = callback(mapName)

    override fun switchMap(mapName: String, callback: (Boolean) -> Unit) {
        this.mapName = mapName
        // RobotOS loses localization on map switch, and places are map-bound.
        localized = false
        places.clear()
        callback(true)
    }

    // ---- places --------------------------------------------------------

    override fun listPlaces(callback: (List<String>) -> Unit) = callback(places.keys.toList())

    override fun saveCurrentPositionAs(placeName: String, callback: (Boolean) -> Unit) {
        places[placeName] = position
        callback(true)
    }

    override fun removePlace(placeName: String, callback: (Boolean) -> Unit) {
        callback(places.remove(placeName) != null)
    }

    override fun locationOf(placeName: String, callback: (Point2D?) -> Unit) =
        callback(places[placeName])

    /** Test helper: place a named point without driving there first. */
    fun definePlace(name: String, at: Point2D) { places[name] = at }

    // ---- navigation ----------------------------------------------------

    override fun navigateTo(placeName: String, listener: NavigationListener) {
        val p = places[placeName]
        if (p == null) {
            listener.onFailed(NavFailure.DestinationUnknown)
            return
        }
        navigateTo(p, listener)
    }

    override fun navigateTo(target: Point2D, listener: NavigationListener) {
        if (!localized) {
            listener.onFailed(NavFailure.NotLocalized)
            return
        }
        if (this.target != null) {
            listener.onFailed(NavFailure.AlreadyRunning)
            return
        }
        this.target = target
        this.listener = listener
        listener.onStarted()
        listener.onProgress(NavEvent.Started)
        if (position.distanceTo(target) <= arrivalRadius) finishArrived()
    }

    override fun stopNavigation() {
        val l = listener
        target = null
        listener = null
        l?.onFailed(NavFailure.CancelledByCaller)
    }

    /**
     * Advance the simulation one step. Returns true while still navigating.
     * Call repeatedly in a test; drive from a coroutine or Handler in the app.
     */
    fun tick(): Boolean {
        val t = target ?: return false
        val remaining = position.distanceTo(t)
        if (remaining <= arrivalRadius) {
            finishArrived()
            return false
        }
        val step = minOf(stepMetres, remaining)
        val dx = (t.x - position.x) / remaining * step
        val dy = (t.y - position.y) / remaining * step
        heading = atan2(t.y - position.y, t.x - position.x)
        position = Point2D(position.x + dx, position.y + dy)
        emitPose()
        return target != null
    }

    /** Run to completion or until [maxSteps]. Returns steps taken. */
    fun runToCompletion(maxSteps: Int = 10_000): Int {
        var n = 0
        while (n < maxSteps && tick()) n++
        return n
    }

    /** Teleport, for setting up a test case. */
    fun placeAt(p: Point2D) {
        position = p
        emitPose()
    }

    private fun finishArrived() {
        val l = listener
        target = null
        listener = null
        emitPose()
        l?.onArrived()
    }

    private fun emitPose() {
        _pose.value = RobotPose(
            x = position.x,
            y = position.y,
            theta = heading,
            status = forcedStatus ?: PoseStatus.SAFE
        )
    }
}
