package com.example.robocontrol.movement

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.actionbean.Pose
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Real [RobotBridge] over the OrionStar RobotOS SDK.
 *
 * Initialization order matters and is not optional:
 *   connectServer -> handleApiConnected -> setCallback -> registerStatusListener
 * "All APIs can only be called after successfully connecting to the server."
 */
class OrionStarBridge(
    private val context: Context,
    private val reqIdSource: () -> Int = { nextReqId() }
) : RobotBridge {

    private val _pose = MutableStateFlow<RobotPose?>(null)
    override val pose: StateFlow<RobotPose?> = _pose.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Speed limit for the next navigation, or null for RobotOS's default speed. Applied only when both
     * [linearSpeed] and [angularSpeed] are set. The jar names the parameters `linearSpeed` and `angularSpeed`;
     * the units are not documented and assumed to be m/s and rad/s (check against the `navi_speed` status).
     */
    @Volatile
    var linearSpeed: Double? = null

    /** See [linearSpeed]. */
    @Volatile
    var angularSpeed: Double? = null

    override fun connect() {
        RobotApi.getInstance().connectServer(context, object : ApiListener {
            override fun handleApiConnected() {
                // ⚠ setCallback expects a ModuleCallbackApi subclass. Supply the
                // app's ModuleCallback here once voice/system dispatch is wired.
                // RobotApi.getInstance().setCallback(ModuleCallback())
                registerPoseListener()
                _connected.value = true
            }

            override fun handleApiDisconnected() { _connected.value = false }
            override fun handleApiDisabled() { _connected.value = false }
        })
    }

    override fun disconnect() {
        stopNavigation()
        RobotApi.getInstance().disconnectApi()
        _connected.value = false
    }

    private fun registerPoseListener() {
        RobotApi.getInstance().registerStatusListener(
            Definition.STATUS_POSE,
            object : StatusListener() {
                override fun onStatusUpdate(type: String?, value: String?) {
                    val json = runCatching { JSONObject(value ?: return) }.getOrNull() ?: return
                    _pose.value = RobotPose(
                        x = json.optDouble("px", json.optDouble("x", 0.0)),
                        y = json.optDouble("py", json.optDouble("y", 0.0)),
                        theta = json.optDouble("theta", 0.0),
                        // ⚠ confirm the key name for the safety field against the jar;
                        // the docs describe the enum but not the JSON key.
                        status = PoseStatus.fromCode(json.optInt("status", -1))
                    )
                }
            }
        )
    }

    // ---- localization & map ---------------------------------------------

    override fun isLocalized(callback: (Boolean) -> Unit) {
        RobotApi.getInstance().isRobotEstimate(reqIdSource(), object : CommandListener() {
            override fun onResult(result: Int, message: String?, extraData: String?) {
                callback(message?.contains("true", ignoreCase = true) == true)
            }
        })
    }

    override fun setPoseEstimate(x: Double, y: Double, theta: Double, callback: (Boolean) -> Unit) {
        val params = JSONObject()
            .put(Definition.JSON_NAVI_POSITION_X, x)
            .put(Definition.JSON_NAVI_POSITION_Y, y)
            .put(Definition.JSON_NAVI_POSITION_THETA, theta)
        RobotApi.getInstance().setPoseEstimate(reqIdSource(), params.toString(),
            object : CommandListener() {
                override fun onResult(result: Int, message: String?, extraData: String?) =
                    callback(result == Definition.RESULT_OK)
            })
    }

    override fun currentMapName(callback: (String?) -> Unit) {
        RobotApi.getInstance().getMapName(reqIdSource(), object : CommandListener() {
            override fun onResult(result: Int, message: String?, extraData: String?) = callback(message)
        })
    }

    override fun switchMap(mapName: String, callback: (Boolean) -> Unit) {
        RobotApi.getInstance().switchMap(reqIdSource(), mapName, object : CommandListener() {
            override fun onResult(result: Int, message: String?, extraData: String?) =
                callback(result == Definition.RESULT_OK)
        })
        // Caller must re-localize afterward: switching maps drops the estimate,
        // and saved places belong to the map they were created on.
    }

    // ---- places -----------------------------------------------------------

    override fun listPlaces(callback: (List<String>) -> Unit) {
        RobotApi.getInstance().getPlaceList(reqIdSource(), object : CommandListener() {
            override fun onResult(result: Int, message: String?, extraData: String?) {
                // ⚠ response shape is a JSON array of place objects; confirm the
                // name key against the jar before relying on "name".
                val names = runCatching {
                    val arr = JSONArray(message)
                    (0 until arr.length()).mapNotNull {
                        arr.optJSONObject(it)?.optString("name")?.ifBlank { null }
                    }
                }.getOrDefault(emptyList())
                callback(names)
            }
        })
    }

    override fun saveCurrentPositionAs(placeName: String, callback: (Boolean) -> Unit) {
        RobotApi.getInstance().setLocation(reqIdSource(), placeName, object : CommandListener() {
            override fun onResult(result: Int, message: String?, extraData: String?) =
                callback(result == Definition.RESULT_OK)
        })
    }

    override fun removePlace(placeName: String, callback: (Boolean) -> Unit) {
        // ⚠ removeLocation is deprecated AND a no-op in robotservice_12.3.jar: its body is
        // `return 0` and it never calls the listener, so the callback would never fire.
        // No verified replacement yet: editPlace takes an undocumented JSON string and
        // updatePlaceList rewrites the whole place list. Report failure instead of hanging.
        callback(false)
    }

    override fun locationOf(placeName: String, callback: (Point2D?) -> Unit) {
        RobotApi.getInstance().getLocation(reqIdSource(), placeName, object : CommandListener() {
            override fun onResult(result: Int, message: String?, extraData: String?) {
                val p = runCatching {
                    val o = JSONObject(message ?: return@runCatching null)
                    Point2D(
                        o.getDouble(Definition.JSON_NAVI_POSITION_X),
                        o.getDouble(Definition.JSON_NAVI_POSITION_Y)
                    )
                }.getOrNull()
                callback(p)
            }
        })
    }

    // ---- navigation -------------------------------------------------------

    override fun navigateTo(placeName: String, listener: NavigationListener) {
        val linear = linearSpeed
        val angular = angularSpeed
        val code = if (linear != null && angular != null) {
            // startNavigation(int reqId, String destination, double coordinateDeviation, long time,
            //                 double linearSpeed, double angularSpeed, ActionListener): names read from the jar.
            RobotApi.getInstance().startNavigation(
                reqIdSource(), placeName, COORDINATE_DEVIATION, OBSTACLE_TIMEOUT_MS, linear, angular, actionListener(listener)
            )
        } else {
            RobotApi.getInstance().startNavigation(
                reqIdSource(), placeName, COORDINATE_DEVIATION, OBSTACLE_TIMEOUT_MS, actionListener(listener)
            )
        }
        if (code < 0) listener.onFailed(NavFailure.Sdk(code, "startNavigation rejected the request"))
    }

    override fun navigateTo(target: Point2D, listener: NavigationListener) {
        // Pose(float, float, float) and startNavigation(int, Pose, double, long, ActionListener) are verified in
        // robotservice_12.3.jar (actionbean.Pose). Theta 0 = the robot ends facing +x; orientation is not needed yet.
        val pose = Pose(target.x.toFloat(), target.y.toFloat(), 0f)
        val linear = linearSpeed
        val angular = angularSpeed
        val code = if (linear != null && angular != null) {
            // startNavigation(int reqId, Pose pose, double coordinateDeviation, long time,
            //                 double linearSpeed, double angularSpeed, ActionListener): names read from the jar.
            RobotApi.getInstance().startNavigation(
                reqIdSource(), pose, COORDINATE_DEVIATION, OBSTACLE_TIMEOUT_MS, linear, angular, actionListener(listener)
            )
        } else {
            RobotApi.getInstance().startNavigation(
                reqIdSource(), pose, COORDINATE_DEVIATION, OBSTACLE_TIMEOUT_MS, actionListener(listener)
            )
        }
        // A negative return value means the request was not accepted, so no callback will follow.
        if (code < 0) listener.onFailed(NavFailure.Sdk(code, "startNavigation rejected the request"))
    }

    override fun stopNavigation() {
        // stopMove() alone cannot stop navigation (per the docs), so stopNavigation is the essential call.
        // stopMove() additionally ends direct motions such as turnInPlace's turnLeft/turnRight.
        RobotApi.getInstance().stopNavigation(reqIdSource())
        runCatching { RobotApi.getInstance().stopMove(reqIdSource(), null) }
    }

    override fun turnInPlace(angleRad: Double, callback: (Boolean) -> Unit) {
        // turnLeft/turnRight(reqId, speed, angle, CommandListener): both in DEGREES (deg/s and deg); the jar converts
        // them with Math.toRadians before sending "turn_left"/"turn_right". Left assumed = counter-clockwise = +theta.
        val degrees = Math.toDegrees(abs(angleRad)).toFloat()
        val listener = object : CommandListener() {
            override fun onResult(result: Int, message: String?, extraData: String?) = callback(result == Definition.RESULT_OK)
            override fun onError(errorCode: Int, errorString: String?, extraData: String?) = callback(false)
        }
        val api = RobotApi.getInstance()
        val code = if (angleRad >= 0) api.turnLeft(reqIdSource(), TURN_SPEED_DEG_PER_S, degrees, listener)
        else api.turnRight(reqIdSource(), TURN_SPEED_DEG_PER_S, degrees, listener)
        if (code < 0) callback(false)
    }

    // The SDK dispatcher calls the (…, extraData) variant AND then the deprecated 2-arg
    // variant for every event. Override only the 3-arg ones, or each event fires twice.
    private fun actionListener(listener: NavigationListener) = object : ActionListener() {
        override fun onResult(status: Int, response: String?, extraData: String?) {
            when (status) {
                Definition.RESULT_OK -> listener.onArrived()
                else -> listener.onFailed(NavFailure.Sdk(status, response))
            }
        }

        override fun onError(errorCode: Int, errorString: String?, extraData: String?) {
            listener.onFailed(
                when (errorCode) {
                    Definition.ERROR_NOT_ESTIMATE -> NavFailure.NotLocalized
                    Definition.ERROR_DESTINATION_NOT_EXIST -> NavFailure.DestinationUnknown
                    // Yes, "ARRAIVE" — the typo is in the SDK, not here.
                    Definition.ERROR_DESTINATION_CAN_NOT_ARRAIVE -> NavFailure.Unreachable
                    Definition.ACTION_RESPONSE_ALREADY_RUN -> NavFailure.AlreadyRunning
                    Definition.ERROR_MULTI_ROBOT_WAITING_TIMEOUT -> NavFailure.TimedOut
                    else -> NavFailure.Sdk(errorCode, errorString)
                }
            )
        }

        override fun onStatusUpdate(status: Int, data: String?, extraData: String?) {
            listener.onProgress(
                when (status) {
                    Definition.STATUS_START_NAVIGATION -> NavEvent.Started
                    Definition.STATUS_NAVI_AVOID -> NavEvent.AvoidingObstacle
                    Definition.STATUS_NAVI_AVOID_END -> NavEvent.ObstacleCleared
                    Definition.STATUS_NAVI_OUT_MAP -> NavEvent.OutsideMap
                    Definition.STATUS_NAVI_MULTI_ROBOT_WAITING -> NavEvent.WaitingForOtherRobot
                    else -> NavEvent.Other(status, data)
                }
            )
        }
    }

    companion object {
        /** Success radius in metres. */
        const val COORDINATE_DEVIATION = 0.3

        /** Obstacle-avoidance timeout, milliseconds (SDK unit). */
        const val OBSTACLE_TIMEOUT_MS = 30_000L

        /** Rotation speed for turnInPlace, degrees per second. */
        const val TURN_SPEED_DEG_PER_S = 30f

        private var counter = 1000
        private fun nextReqId(): Int = ++counter
    }
}

// ============================================================================
//  Optional, later: making a private zone a *real* obstacle
//
//  Everything above enforces privacy in the app. If the thesis wants the robot's
//  own planner to refuse to route through a private zone, the only documented
//  lever is the raw occupancy grid:
//
//      val pgm = ShareMemoryApi.getInstance().getMapPgmPFD(mapName)
//      // paint the polygon's cells as occupied
//      ShareMemoryApi.getInstance().setMapPgmPFD(mapName, modifiedBytes)
//
//  Caveats worth stating in the write-up rather than discovering on hardware:
//   - semantics are undocumented; resolution and origin of the grid must be
//     derived from the map's .yaml before world->cell conversion is possible
//   - it mutates the shared map, so it affects every app on the robot and
//     persists past uninstall
//   - it is irreversible without keeping the original grid
//  The app-level guard is the safer primary mechanism; this is the fallback if
//  a reviewer asks "but what stops the planner routing through the bedroom?"
// ============================================================================
