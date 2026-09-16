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
 *
 * Leaving is always allowed. Found on the robot: after a stop at the edge of a private
 * zone the robot stands inside the margin, so a strict in-motion check aborted every
 * following drive at once, even turning away. A drive that *starts* inside a zone
 * (or its margin) is therefore only aborted if the robot gets closer to that zone than
 * the best clearance it has reached during the drive, minus [EXIT_TOLERANCE_M]. Once it
 * is outside the margin, the normal rule applies again.
 */
class NavigationController(
    private val bridge: RobotBridge,
    private val guard: PrivacyGuard
) {

    private var activeListener: NavigationListener? = null
    private var activeTarget: Point2D? = null

    /**
     * Increments per drive and on every stop/abort. Bridge callbacks carrying an older id are dropped. Found on the robot:
     * after our privacy abort, RobotOS's own "stopped" result (Sdk code 3) arrived on its callback thread BEFORE our
     * AbortedOnPrivateZoneEntry, so the caller saw a plain failure and never announced the privacy stop.
     */
    @Volatile
    private var driveId = 0

    /** Last pose seen; used to find the zones the robot is already in when a drive starts. */
    private var lastPose: RobotPose? = null

    /**
     * For the current drive: private zones the robot started inside (incl. margin), mapped to the best clearance
     * reached so far (metres from the zone boundary, negative inside the polygon).
     */
    private val exitExemptions = mutableMapOf<String, Double>()

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
        val listener = activeListener
        driveId++
        activeTarget = null
        activeListener = null
        bridge.stopNavigation()
        listener?.onFailed(NavFailure.CancelledByCaller)
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
        lastPose = pose
        // A drive that started inside a zone may continue as long as it does not move further in (see class doc).
        if (isNavigating && pose.status != PoseStatus.FORBIDDEN && exitExemptions.isNotEmpty() && onlyLeavingExemptZones(pose)) {
            return
        }
        val violation = guard.violationAt(pose) ?: return
        lastAbort = violation

        if (isNavigating) {
            val listener = activeListener
            driveId++ // from here on, RobotOS's own "stopped" result for this drive is ignored
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
        val id = ++driveId
        activeTarget = target
        activeListener = listener

        exitExemptions.clear()
        val startPose = lastPose
        if (startPose != null && !guard.overrideActive()) {
            guard.zones.privateZones
                .filter { it.containsWithMargin(startPose.point, guard.margin) }
                .forEach { exitExemptions[it.name] = clearance(it, startPose.point) }
        }
        launch(object : NavigationListener {
            private val current get() = driveId == id
            override fun onStarted() { if (current) listener.onStarted() }
            override fun onProgress(event: NavEvent) { if (current) listener.onProgress(event) }
            override fun onArrived() {
                if (!current) return
                activeTarget = null
                activeListener = null
                listener.onArrived()
            }
            override fun onFailed(failure: NavFailure) {
                if (!current) return
                activeTarget = null
                activeListener = null
                listener.onFailed(failure)
            }
        })
    }

    /**
     * True if every private zone the robot is in right now is one it started this drive in, and it is not closer to
     * any of them than its best clearance so far (minus the tolerance). Zones it has left drop out of the exemption,
     * so re-entering them later counts as a normal violation.
     */
    private fun onlyLeavingExemptZones(pose: RobotPose): Boolean {
        val p = pose.point
        val current = guard.zones.privateZones.filter { it.containsWithMargin(p, guard.margin) }
        exitExemptions.keys.retainAll(current.map { it.name }.toSet())
        if (current.isEmpty()) return false // no zone at all: the normal check finds no violation
        for (zone in current) {
            val best = exitExemptions[zone.name] ?: return false
            val now = clearance(zone, p)
            if (now < best - EXIT_TOLERANCE_M) return false
            exitExemptions[zone.name] = maxOf(best, now)
        }
        return true
    }

    /** Distance from the zone boundary in metres: positive outside the polygon, negative inside. */
    private fun clearance(zone: Zone, p: Point2D): Double =
        if (zone.contains(p)) -zone.distanceToEdge(p) else zone.distanceToEdge(p)

    companion object {
        private const val FORBIDDEN_AREA_CODE = -1001

        /** How much closer to a zone a drive that started inside it may get before it is aborted (localization jitter). */
        const val EXIT_TOLERANCE_M = 0.05
    }
}
