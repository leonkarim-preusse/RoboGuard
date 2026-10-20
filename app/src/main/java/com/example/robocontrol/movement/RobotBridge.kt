package com.example.robocontrol.movement

import kotlinx.coroutines.flow.StateFlow

/**
 * Robot functions needed by the navigation code, independent of the OrionStar SDK. Implemented by [OrionStarBridge]
 * on the robot and by [FakeRobotBridge] for tests.
 */
interface RobotBridge {

    /** Live pose stream. Null until the robot reports its first pose. */
    val pose: StateFlow<RobotPose?>

    /** True once the SDK's connection callback has fired. */
    val connected: StateFlow<Boolean>

    /** Opens the connection to the robot's SDK; [connected] turns true once it is up. */
    fun connect()
    /** Releases the SDK listeners again. Must not end a connection other parts of the app still use. */
    fun disconnect()

    /**
     * Whether the robot knows where it is on the current map.
     * Navigation fails with ERROR_NOT_ESTIMATE if this is false.
     */
    fun isLocalized(callback: (Boolean) -> Unit)

    /** Seed the robot's position when it is not localized. */
    fun setPoseEstimate(x: Double, y: Double, theta: Double, callback: (Boolean) -> Unit)

    /** Asks for the name of the map RobotOS currently uses; null if it cannot be read. */
    fun currentMapName(callback: (String?) -> Unit)
    /**
     * Switches RobotOS to another map. The robot has to be localised again afterwards.
     * @param callback true if RobotOS accepted the switch.
     */
    fun switchMap(mapName: String, callback: (Boolean) -> Unit)

    /** Named single points stored by RobotOS against the active map. */
    fun listPlaces(callback: (List<String>) -> Unit)
    /** Saves the robot's current position as a RobotOS place on the active map. */
    fun saveCurrentPositionAs(placeName: String, callback: (Boolean) -> Unit)
    /** Removes a RobotOS place. May not be supported by the SDK (removeLocation is a no-op), then reports false. */
    fun removePlace(placeName: String, callback: (Boolean) -> Unit)
    /** Looks up the coordinates of a RobotOS place; null if it does not exist. */
    fun locationOf(placeName: String, callback: (Point2D?) -> Unit)

    /** Starts a drive to a RobotOS place by its name. Progress and outcome arrive at [listener]. */
    fun navigateTo(placeName: String, listener: NavigationListener)
    /** Starts a drive to map coordinates. Progress and outcome arrive at [listener]. */
    fun navigateTo(target: Point2D, listener: NavigationListener)

    /** Stops a running navigation (the SDK's `stopNavigation`; `stopMove` does not stop navigation). */
    fun stopNavigation()

    /**
     * Turns on the spot by [angleRad] (positive = counter-clockwise, i.e. increasing theta), e.g. to face away from a
     * private area after a stop. No forward or backward motion. Implementations must stop this in [stopNavigation] too.
     */
    fun turnInPlace(angleRad: Double, callback: (Boolean) -> Unit)
}

/** Progress reported while navigating. Mirrors the RobotOS status codes we care about. */
sealed interface NavEvent {
    /** The robot has started to move towards the target. */
    data object Started : NavEvent
    /** The robot is driving around an obstacle. */
    data object AvoidingObstacle : NavEvent
    /** The obstacle is cleared and the robot continues on its route. */
    data object ObstacleCleared : NavEvent
    /** The robot has left the known map. */
    data object OutsideMap : NavEvent
    /** The robot waits because another robot blocks the way. */
    data object WaitingForOtherRobot : NavEvent
    /** Any other status code of the SDK, passed through unchanged. */
    data class Other(val statusCode: Int, val data: String?) : NavEvent
}

/** Why a navigation ended without arriving. */
sealed interface NavFailure {
    /** The robot does not know where it is. Call setPoseEstimate first. */
    data object NotLocalized : NavFailure
    /** The target place does not exist on the active map. */
    data object DestinationUnknown : NavFailure
    /** RobotOS found no route to the target. */
    data object Unreachable : NavFailure
    /** Another drive is still running. */
    data object AlreadyRunning : NavFailure
    /** The drive took longer than the allowed time. */
    data object TimedOut : NavFailure

    /** Refused by [PrivacyGuard] before any motion happened. */
    data class BlockedByPrivacy(val zone: Zone) : NavFailure

    /** Aborted mid-route because the robot entered a private zone. */
    data class AbortedOnPrivateZoneEntry(val zone: Zone) : NavFailure

    /** The drive was stopped on purpose (STOP button, a new drive, or a privacy stop). */
    data object CancelledByCaller : NavFailure
    /** An SDK error code that has no own type here. */
    data class Sdk(val code: Int, val message: String?) : NavFailure
}

/** Receives the progress and the outcome of one drive. All methods are optional. */
interface NavigationListener {
    /** Called once the robot starts to move. */
    fun onStarted() {}
    /** Called for every status change during the drive. */
    fun onProgress(event: NavEvent) {}
    /** Called when the robot reached the target. */
    fun onArrived() {}
    /** Called when the drive ended without reaching the target. */
    fun onFailed(failure: NavFailure) {}
}
