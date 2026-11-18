package com.example.robocontrol.movement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2

/**
 * A simulated robot in memory for tests: drives in a straight line towards the target, one step per [tick].
 * @param startAt starting position
 * @param stepMetres distance moved per tick
 * @param arrivalRadius distance at which the target counts as reached
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

    var forcedStatus: PoseStatus? = null

    /** Marks the fake robot as connected. */
    override fun connect() {
        _connected.value = true
        emitPose()
    }

    /** Marks the fake robot as disconnected. */
    override fun disconnect() {
        stopNavigation()
        _connected.value = false
    }

    /**
     * Reports the fake localization state.
     * @param callback receives the state
     */
    override fun isLocalized(callback: (Boolean) -> Unit) = callback(localized)

    /**
     * Sets whether the fake robot counts as localised.
     * @param value true if localized
     */
    fun setLocalized(value: Boolean) { localized = value }

    /**
     * Sets the fake pose and marks the robot as localized.
     * @param x x in metres
     * @param y y in metres
     * @param theta heading in radians
     * @param callback receives true
     */
    override fun setPoseEstimate(x: Double, y: Double, theta: Double, callback: (Boolean) -> Unit) {
        position = Point2D(x, y)
        heading = theta
        localized = true
        emitPose()
        callback(true)
    }

    /**
     * Reports the fake map name.
     * @param callback receives the name
     */
    override fun currentMapName(callback: (String?) -> Unit) = callback(mapName)

    /**
     * Switches the fake map; the robot is no longer localized.
     * @param mapName name of the map
     * @param callback receives true
     */
    override fun switchMap(mapName: String, callback: (Boolean) -> Unit) {
        this.mapName = mapName
        localized = false
        places.clear()
        callback(true)
    }

    /**
     * Lists the fake places.
     * @param callback receives the names
     */
    override fun listPlaces(callback: (List<String>) -> Unit) = callback(places.keys.toList())

    /**
     * Saves the current fake position as a place.
     * @param placeName name of the place
     * @param callback receives true
     */
    override fun saveCurrentPositionAs(placeName: String, callback: (Boolean) -> Unit) {
        places[placeName] = position
        callback(true)
    }

    /**
     * Removes a fake place.
     * @param placeName name of the place
     * @param callback receives true if it existed
     */
    override fun removePlace(placeName: String, callback: (Boolean) -> Unit) {
        callback(places.remove(placeName) != null)
    }

    /**
     * Looks up a fake place.
     * @param placeName name of the place
     * @param callback receives the position, or null
     */
    override fun locationOf(placeName: String, callback: (Point2D?) -> Unit) =
        callback(places[placeName])

    /**
     * Adds a named place without driving there.
     * @param name name of the place
     * @param at position
     */
    fun definePlace(name: String, at: Point2D) { places[name] = at }

    /**
     * Starts a simulated drive to a place.
     * @param placeName name of the place
     * @param listener receives progress and outcome
     */
    override fun navigateTo(placeName: String, listener: NavigationListener) {
        val p = places[placeName]
        if (p == null) {
            listener.onFailed(NavFailure.DestinationUnknown)
            return
        }
        navigateTo(p, listener)
    }

    /**
     * Starts a simulated drive to a point.
     * @param target target position
     * @param listener receives progress and outcome
     */
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

    /** Stops the simulated drive. */
    override fun stopNavigation() {
        val l = listener
        target = null
        listener = null
        l?.onFailed(NavFailure.CancelledByCaller)
    }

    /**
     * Turns instantly.
     * @param angleRad angle in radians
     * @param callback receives true
     */
    override fun turnInPlace(angleRad: Double, callback: (Boolean) -> Unit) {
        heading = EscapeHeading.normalize(heading + angleRad)
        emitPose()
        callback(true)
    }

    /**
     * Advances the simulation one step.
     * @return true while still navigating
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

    /**
     * Runs the simulation until the drive ends or [maxSteps] is reached.
     * @param maxSteps maximum number of steps
     * @return steps taken
     */
    fun runToCompletion(maxSteps: Int = 10_000): Int {
        var n = 0
        while (n < maxSteps && tick()) n++
        return n
    }

    /**
     * Moves the fake robot to [p] instantly.
     * @param p new position
     */
    fun placeAt(p: Point2D) {
        position = p
        emitPose()
    }

    /** Ends the fake drive at its target and calls onArrived. */
    private fun finishArrived() {
        val l = listener
        target = null
        listener = null
        emitPose()
        l?.onArrived()
    }

    /** Publishes the fake position as a new [RobotPose]. */
    private fun emitPose() {
        _pose.value = RobotPose(
            x = position.x,
            y = position.y,
            theta = heading,
            status = forcedStatus ?: PoseStatus.SAFE
        )
    }
}
