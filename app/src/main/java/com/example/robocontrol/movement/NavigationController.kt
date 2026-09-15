package com.example.robocontrol.movement

/**
 * The movement entry point for the rest of the app.
 *
 * Wraps a [RobotBridge] with [PrivacyGuard] enforcement so that no caller can
 * drive the robot without passing the zone checks. Nothing above this class
 * should ever touch the bridge directly.
 *
 * Enforcement happens twice, on purpose:
 *
 *  1. **Before moving** — the target is rejected if it lies in a private zone.
 *     Cheap, and means the robot never even sets off toward the bathroom.
 *  2. **While moving** — every pose is checked, and navigation is aborted if
 *     the robot enters a private zone anyway. Necessary because RobotOS plans
 *     its own path: a legal target in the living room can still be routed
 *     through the bedroom, and we have no API to constrain the planner.
 *
 * Feed poses in via [onPose] (from `bridge.pose` in the app, or by hand in a
 * test). This class deliberately does not own a coroutine scope.
 */
class NavigationController(
    private val bridge: RobotBridge,
    private val guard: PrivacyGuard
) {

    private var activeListener: NavigationListener? = null
    private var activeTarget: Point2D? = null

    val isNavigating: Boolean get() = activeTarget != null

    /** Last violation that caused an abort, for UI display. Cleared on next start. */
    var lastAbort: Violation? = null
        private set

    // ---- going somewhere ------------------------------------------------

    /**
     * Navigate to a point in map coordinates.
     * Runs the pre-move privacy check, then delegates to the bridge.
     */
    fun goTo(target: Point2D, listener: NavigationListener) {
        when (val decision = guard.evaluateTarget(target)) {
            is Decision.Refused -> {
                listener.onFailed(NavFailure.BlockedByPrivacy(decision.zone))
                return
            }
            else -> Unit // Allowed or AllowedByOverride
        }
        start(target, listener) { bridge.navigateTo(target, it) }
    }

    /**
     * Navigate to a place the robot knows by name.
     *
     * Resolution order:
     *  1. A [Zone] of that name — uses its `entryPoint` place if set, else its centroid.
     *  2. A RobotOS place saved with `setLocation`.
     *
     * Zones win so that "go to the kitchen" means the room, not a stale point.
     */
    fun goTo(name: String, listener: NavigationListener) {
        val zone = guard.zones.byName(name)
        if (zone != null) {
            val entry = zone.entryPoint
            if (entry != null) {
                bridge.locationOf(entry) { point ->
                    if (point == null) {
                        // Entry point vanished (map switched, or it was deleted).
                        // Fall back to the centroid rather than failing outright.
                        goTo(zone.centroid(), listener)
                    } else {
                        goTo(point, listener)
                    }
                }
            } else {
                goTo(zone.centroid(), listener)
            }
            return
        }

        bridge.locationOf(name) { point ->
            if (point == null) {
                listener.onFailed(NavFailure.DestinationUnknown)
            } else {
                goTo(point, listener)
            }
        }
    }

    fun stop() {
        activeTarget = null
        activeListener = null
        bridge.stopNavigation()
    }

    // ---- pose monitoring -------------------------------------------------

    /**
     * Feed every pose update here. Aborts an in-flight navigation if the robot
     * has entered somewhere it should not be.
     *
     * Also checked when idle: the robot can be pushed, or can drift into a zone
     * while parked, and the thesis's position is that presence in the room is
     * the problem, not the act of navigating there.
     */
    fun onPose(pose: RobotPose) {
        val violation = guard.violationAt(pose) ?: return
        lastAbort = violation

        if (isNavigating) {
            val listener = activeListener
            activeTarget = null
            activeListener = null
            bridge.stopNavigation()
            listener?.onFailed(
                when (violation) {
                    is Violation.PrivateZone ->
                        NavFailure.AbortedOnPrivateZoneEntry(violation.zone)
                    Violation.SdkForbiddenArea ->
                        NavFailure.Sdk(FORBIDDEN_AREA_CODE, "Entered a RobotOS forbidden area")
                }
            )
        } else {
            // Parked somewhere it shouldn't be. Stopping achieves nothing; the
            // caller has to decide (leave, sleep, ask). Surfaced via lastAbort
            // and the guard's audit log.
            onIdleViolation?.invoke(violation)
        }
    }

    /** Called when a violation is detected while not navigating. */
    var onIdleViolation: ((Violation) -> Unit)? = null

    // ---- map & place management -----------------------------------------

    /**
     * Ensure the robot is localized before any navigation is attempted,
     * since RobotOS fails with ERROR_NOT_ESTIMATE otherwise.
     */
    fun ensureLocalized(fallback: RobotPose?, callback: (Boolean) -> Unit) {
        bridge.isLocalized { localized ->
            if (localized) {
                callback(true)
            } else if (fallback != null) {
                bridge.setPoseEstimate(fallback.x, fallback.y, fallback.theta, callback)
            } else {
                callback(false)
            }
        }
    }

    /** Name the robot's current position — the building block for defining zones by driving. */
    fun nameCurrentPosition(placeName: String, callback: (Boolean) -> Unit) =
        bridge.saveCurrentPositionAs(placeName, callback)

    fun places(callback: (List<String>) -> Unit) = bridge.listPlaces(callback)

    // ---- internals -------------------------------------------------------

    private fun start(
        target: Point2D,
        listener: NavigationListener,
        launch: (NavigationListener) -> Unit
    ) {
        lastAbort = null
        activeTarget = target
        activeListener = listener
        launch(object : NavigationListener {
            override fun onStarted() = listener.onStarted()
            override fun onProgress(event: NavEvent) = listener.onProgress(event)
            override fun onArrived() {
                activeTarget = null
                activeListener = null
                listener.onArrived()
            }
            override fun onFailed(failure: NavFailure) {
                activeTarget = null
                activeListener = null
                listener.onFailed(failure)
            }
        })
    }

    private companion object {
        const val FORBIDDEN_AREA_CODE = -1001
    }
}
