package com.example.robocontrol.movement

/**
 * Entry point for driving: wraps a [RobotBridge] with [PrivacyGuard] checks. A target inside a private zone is refused
 * before moving, and a drive is stopped when the robot enters a private zone. A drive that starts inside a zone may
 * leave it; it is only stopped if it gets closer to the zone again than [EXIT_TOLERANCE_M] below its best clearance.
 * Poses are passed in through [onPose].
 * @param bridge connection to the robot
 * @param guard privacy rules
 */
class NavigationController(
    private val bridge: RobotBridge,
    private val guard: PrivacyGuard
) {

    private var activeListener: NavigationListener? = null
    private var activeTarget: Point2D? = null

    /** Id of the current drive; callbacks from older drives are ignored. */
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
     * Drives to a zone or RobotOS place by name. A zone uses its entry point if set, otherwise its centroid.
     * @param name name of the zone or place
     * @param listener receives progress and the result
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

    /**
     * Stops the running drive (navigation and turning) and reports [NavFailure.CancelledByCaller] to its listener.
     * Later SDK callbacks of that drive are ignored.
     */
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
     * Checks a new pose and stops the drive if the robot entered a private zone.
     * @param pose the new pose
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

    /** Names of the RobotOS places on the active map. */
    fun places(callback: (List<String>) -> Unit) = bridge.listPlaces(callback)

    // ---- internals -------------------------------------------------------

    /**
     * Common start of every drive: stops a drive that is still running, remembers the target and calls [launch]
     * with a listener that ignores callbacks from older drives.
     */
    private fun start(
        target: Point2D,
        listener: NavigationListener,
        launch: (NavigationListener) -> Unit
    ) {
        // Never start over a running drive: stop it first, so a rejected second start cannot leave RobotOS driving the
        // first one while this controller believes it is idle. MapNavigation refuses this case
        // already; this is the second line.
        if (isNavigating) {
            driveId++
            activeTarget = null
            activeListener?.onFailed(NavFailure.CancelledByCaller)
            activeListener = null
            bridge.stopNavigation()
        }
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
            // SDK callbacks arrive on RobotOS's thread: check and clear under this controller's lock (the same one
            // MapNavigation holds for onPose / stop), and call the listener after leaving it.
            override fun onArrived() {
                synchronized(this@NavigationController) {
                    if (!current) return
                    activeTarget = null
                    activeListener = null
                }
                listener.onArrived()
            }
            override fun onFailed(failure: NavFailure) {
                synchronized(this@NavigationController) {
                    if (!current) return
                    activeTarget = null
                    activeListener = null
                }
                listener.onFailed(failure)
            }
        })
    }

    /**
     * True if the robot is only inside zones it started this drive in and is not getting closer to them.
     * @param pose current pose
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
