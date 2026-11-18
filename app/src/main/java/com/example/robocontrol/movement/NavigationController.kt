package com.example.robocontrol.movement

/**
 * Entry point for driving: wraps a [RobotBridge] with [PrivacyGuard] checks before and during a drive.
 * @param bridge connection to the robot
 * @param guard privacy rules
 */
class NavigationController(
    private val bridge: RobotBridge,
    private val guard: PrivacyGuard
) {
    private var activeListener: NavigationListener? = null
    private var activeTarget: Point2D? = null

    @Volatile
    private var driveId = 0

    private var lastPose: RobotPose? = null

    private val exitExemptions = mutableMapOf<String, Double>()

    val isNavigating: Boolean get() = activeTarget != null

    var lastAbort: Violation? = null
        private set

    /**
     * Navigates to [target] after the pre-move privacy check.
     * @param target target position
     * @param listener receives progress and outcome
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

    /**
     * Checks a new pose and stops the drive if the robot entered a private zone.
     * @param pose the new pose
     */
    fun onPose(pose: RobotPose) {
        lastPose = pose
        if (isNavigating && pose.status != PoseStatus.FORBIDDEN && exitExemptions.isNotEmpty() && onlyLeavingExemptZones(pose)) {
            return
        }
        val violation = guard.violationAt(pose) ?: return
        lastAbort = violation

        if (isNavigating) {
            val listener = activeListener
            driveId++
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
            onIdleViolation?.invoke(violation)
        }
    }

    var onIdleViolation: ((Violation) -> Unit)? = null

    /**
     * Ensures the robot is localized before navigating.
     * @param fallback pose to seed if not localized, or null
     * @param callback receives true if localized
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

    /**
     * Names the robot's current position; used to define zones by driving.
     * @param placeName name of the place
     * @param callback receives true on success
     */
    fun nameCurrentPosition(placeName: String, callback: (Boolean) -> Unit) =
        bridge.saveCurrentPositionAs(placeName, callback)

    /**
     * Names of the RobotOS places on the active map.
     * @param callback receives the names
     */
    fun places(callback: (List<String>) -> Unit) = bridge.listPlaces(callback)

    /**
     * Common start of every drive: stops a running drive and starts the new one.
     * @param target target position
     * @param listener receives progress and outcome
     * @param launch starts the drive with the wrapped listener
     */
    private fun start(
        target: Point2D,
        listener: NavigationListener,
        launch: (NavigationListener) -> Unit
    ) {
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
            /** Forwards the start. */
            override fun onStarted() { if (current) listener.onStarted() }
            /**
             * Forwards progress.
             * @param event progress event
             */
            override fun onProgress(event: NavEvent) { if (current) listener.onProgress(event) }
            // SDK callbacks arrive on RobotOS's thread: check under the lock, notify outside it.
            /** Forwards the arrival. */
            override fun onArrived() {
                synchronized(this@NavigationController) {
                    if (!current) return
                    activeTarget = null
                    activeListener = null
                }
                listener.onArrived()
            }
            /**
             * Forwards the failure.
             * @param failure reason
             */
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
     * @return true if the robot is only leaving zones it started in
     */
    private fun onlyLeavingExemptZones(pose: RobotPose): Boolean {
        val p = pose.point
        val current = guard.zones.privateZones.filter { it.containsWithMargin(p, guard.margin) }
        exitExemptions.keys.retainAll(current.map { it.name }.toSet())
        if (current.isEmpty()) return false
        for (zone in current) {
            val best = exitExemptions[zone.name] ?: return false
            val now = clearance(zone, p)
            if (now < best - EXIT_TOLERANCE_M) return false
            exitExemptions[zone.name] = maxOf(best, now)
        }
        return true
    }

    /**
     * Distance from the zone boundary in metres: positive outside the polygon, negative inside.
     * @param zone area
     * @param p point
     * @return the signed distance
     */
    private fun clearance(zone: Zone, p: Point2D): Double =
        if (zone.contains(p)) -zone.distanceToEdge(p) else zone.distanceToEdge(p)

    companion object {
        private const val FORBIDDEN_AREA_CODE = -1001

        const val EXIT_TOLERANCE_M = 0.05
    }
}
