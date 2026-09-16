package com.example.robocontrol.movement

import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the framework needs from the robot, expressed without naming the
 * OrionStar SDK once.
 *
 * Two reasons this interface exists rather than calling RobotApi directly:
 *
 *  1. robotservice.jar is a local jar available only on the robot's developer
 *     platform. Without this seam, nothing in the project compiles or tests
 *     until that jar is in place.
 *  2. The thesis claims the privacy behaviour is a property of the control
 *     layer, not of OrionStar's hardware. That claim is only demonstrable if
 *     the guard logic can be exercised against a substitutable robot — see
 *     [FakeRobotBridge].
 */
interface RobotBridge {

    /** Live pose stream. Null until the robot reports its first pose. */
    val pose: StateFlow<RobotPose?>

    /** True once the SDK's connection callback has fired. */
    val connected: StateFlow<Boolean>

    fun connect()
    fun disconnect()

    /**
     * Whether the robot knows where it is on the current map.
     * Navigation fails with ERROR_NOT_ESTIMATE if this is false.
     */
    fun isLocalized(callback: (Boolean) -> Unit)

    /** Seed the robot's position when it is not localized. */
    fun setPoseEstimate(x: Double, y: Double, theta: Double, callback: (Boolean) -> Unit)

    fun currentMapName(callback: (String?) -> Unit)
    fun switchMap(mapName: String, callback: (Boolean) -> Unit)

    /** Named single points stored by RobotOS against the active map. */
    fun listPlaces(callback: (List<String>) -> Unit)
    fun saveCurrentPositionAs(placeName: String, callback: (Boolean) -> Unit)
    fun removePlace(placeName: String, callback: (Boolean) -> Unit)
    fun locationOf(placeName: String, callback: (Point2D?) -> Unit)

    fun navigateTo(placeName: String, listener: NavigationListener)
    fun navigateTo(target: Point2D, listener: NavigationListener)

    /**
     * Stops navigation specifically. Note the SDK's `stopMove` does NOT stop
     * navigation — only forward/backward/rotation — so implementations must
     * call `stopNavigation`, not `stopMove`.
     */
    fun stopNavigation()

    /**
     * Turns on the spot by [angleRad] (positive = counter-clockwise, i.e. increasing theta), e.g. to face away from a
     * private area after a stop. No forward or backward motion. Implementations must stop this in [stopNavigation] too.
     */
    fun turnInPlace(angleRad: Double, callback: (Boolean) -> Unit)
}

/** Progress reported while navigating. Mirrors the RobotOS status codes we care about. */
sealed interface NavEvent {
    data object Started : NavEvent
    data object AvoidingObstacle : NavEvent
    data object ObstacleCleared : NavEvent
    data object OutsideMap : NavEvent
    data object WaitingForOtherRobot : NavEvent
    data class Other(val statusCode: Int, val data: String?) : NavEvent
}

/** Why a navigation ended without arriving. */
sealed interface NavFailure {
    /** The robot does not know where it is. Call setPoseEstimate first. */
    data object NotLocalized : NavFailure
    data object DestinationUnknown : NavFailure
    data object Unreachable : NavFailure
    data object AlreadyRunning : NavFailure
    data object TimedOut : NavFailure

    /** Refused by [PrivacyGuard] before any motion happened. */
    data class BlockedByPrivacy(val zone: Zone) : NavFailure

    /** Aborted mid-route because the robot entered a private zone. */
    data class AbortedOnPrivateZoneEntry(val zone: Zone) : NavFailure

    data object CancelledByCaller : NavFailure
    data class Sdk(val code: Int, val message: String?) : NavFailure
}

interface NavigationListener {
    fun onStarted() {}
    fun onProgress(event: NavEvent) {}
    fun onArrived() {}
    fun onFailed(failure: NavFailure) {}
}
