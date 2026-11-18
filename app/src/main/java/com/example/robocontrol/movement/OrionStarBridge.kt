package com.example.robocontrol.movement

import android.content.Context
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.Definition
import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.StatusListener
import com.ainirobot.coreservice.client.actionbean.Pose
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.example.robocontrol.system.RobotApiConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * [RobotBridge] implementation on the OrionStar RobotOS SDK.
 * @param context used to connect to the SDK
 * @param reqIdSource source of request ids
 */
class OrionStarBridge(
    private val context: Context,
    private val reqIdSource: () -> Int = { nextReqId() }
) : RobotBridge {
    private val _pose = MutableStateFlow<RobotPose?>(null)
    override val pose: StateFlow<RobotPose?> = _pose.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    var linearSpeed: Double? = null

    @Volatile
    var angularSpeed: Double? = null

    private val apiListener = object : ApiListener {
        /** Registers the pose listener once connected. */
        override fun handleApiConnected() {
            registerPoseListener()
            _connected.value = true
        }

        /** Marks the bridge as disconnected. */
        override fun handleApiDisconnected() { _connected.value = false }
        /** Marks the SDK as unavailable. */
        override fun handleApiDisabled() { _connected.value = false }
    }

    /** Connects through the shared [RobotApiConnection]. */
    override fun connect() {
        RobotApiConnection.connect(context, apiListener)
    }

    /**
     * Stops driving and detaches this bridge. Does NOT call `RobotApi.disconnectApi()`: the connection is shared by the
     * whole app (server, sensor switches), and closing it broke their SDK calls.
     */
    override fun disconnect() {
        stopNavigation()
        RobotApiConnection.removeListener(apiListener)
        poseListener?.let { runCatching { RobotApi.getInstance().unregisterStatusListener(it) } }
        poseListener = null
        _connected.value = false
    }

    private var poseListener: StatusListener? = null

    /** Listens to RobotOS's pose status and publishes every new pose in [pose]. Replaces an older listener. */
    private fun registerPoseListener() {
        poseListener?.let { runCatching { RobotApi.getInstance().unregisterStatusListener(it) } }
        poseListener = object : StatusListener() {
                /**
                 * Forwards the pose status.
                 * @param type status type
                 * @param value status JSON
                 */
                override fun onStatusUpdate(type: String?, value: String?) {
                    val json = runCatching { JSONObject(value ?: return) }.getOrNull() ?: return
                    _pose.value = RobotPose(
                        x = json.optDouble("px", json.optDouble("x", 0.0)),
                        y = json.optDouble("py", json.optDouble("y", 0.0)),
                        theta = json.optDouble("theta", 0.0),
                        // TODO: confirm the JSON key of the safety field; the docs describe only the enum.
                        status = PoseStatus.fromCode(json.optInt("status", -1))
                    )
                }
            }
        RobotApi.getInstance().registerStatusListener(Definition.STATUS_POSE, poseListener)
    }

    /**
     * Asks RobotOS whether the robot is localized.
     * @param callback receives the answer
     */
    override fun isLocalized(callback: (Boolean) -> Unit) {
        RobotApi.getInstance().isRobotEstimate(reqIdSource(), object : CommandListener() {
            /**
             * Passes the answer on.
             * @param result result code
             * @param message answer
             * @param extraData extra data
             */
            override fun onResult(result: Int, message: String?, extraData: String?) {
                callback(message?.contains("true", ignoreCase = true) == true)
            }
        })
    }

    /**
     * Sets the robot's pose estimate.
     * @param x x in metres
     * @param y y in metres
     * @param theta heading in radians
     * @param callback receives true on success
     */
    override fun setPoseEstimate(x: Double, y: Double, theta: Double, callback: (Boolean) -> Unit) {
        val params = JSONObject()
            .put(Definition.JSON_NAVI_POSITION_X, x)
            .put(Definition.JSON_NAVI_POSITION_Y, y)
            .put(Definition.JSON_NAVI_POSITION_THETA, theta)
        RobotApi.getInstance().setPoseEstimate(reqIdSource(), params.toString(),
            object : CommandListener() {
                /**
                 * Passes the result on.
                 * @param result result code
                 * @param message answer
                 * @param extraData extra data
                 */
                override fun onResult(result: Int, message: String?, extraData: String?) =
                    callback(result == Definition.RESULT_OK)
            })
    }

    /**
     * Asks RobotOS for the current map name.
     * @param callback receives the name, or null
     */
    override fun currentMapName(callback: (String?) -> Unit) {
        RobotApi.getInstance().getMapName(reqIdSource(), object : CommandListener() {
            /**
             * Passes the name on.
             * @param result result code
             * @param message map name
             * @param extraData extra data
             */
            override fun onResult(result: Int, message: String?, extraData: String?) = callback(message)
        })
    }

    /**
     * Switches RobotOS to another map.
     * @param mapName map name
     * @param callback receives true on success
     */
    override fun switchMap(mapName: String, callback: (Boolean) -> Unit) {
        RobotApi.getInstance().switchMap(reqIdSource(), mapName, object : CommandListener() {
            /**
             * Passes the result on.
             * @param result result code
             * @param message answer
             * @param extraData extra data
             */
            override fun onResult(result: Int, message: String?, extraData: String?) =
                callback(result == Definition.RESULT_OK)
        })
    }

    /**
     * Lists the RobotOS places of the current map.
     * @param callback receives the names
     */
    override fun listPlaces(callback: (List<String>) -> Unit) {
        RobotApi.getInstance().getPlaceList(reqIdSource(), object : CommandListener() {
            /**
             * Parses the place list.
             * @param result result code
             * @param message place list as JSON
             * @param extraData extra data
             */
            override fun onResult(result: Int, message: String?, extraData: String?) {
                // TODO: confirm the name key of the place objects against the jar.
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

    /**
     * Saves the current position as a RobotOS place.
     * @param placeName name of the place
     * @param callback receives true on success
     */
    override fun saveCurrentPositionAs(placeName: String, callback: (Boolean) -> Unit) {
        RobotApi.getInstance().setLocation(reqIdSource(), placeName, object : CommandListener() {
            /**
             * Passes the result on.
             * @param result result code
             * @param message answer
             * @param extraData extra data
             */
            override fun onResult(result: Int, message: String?, extraData: String?) =
                callback(result == Definition.RESULT_OK)
        })
    }

    /**
     * Reports false: removing places is not supported by the SDK.
     * @param placeName name of the place
     * @param callback receives false
     */
    override fun removePlace(placeName: String, callback: (Boolean) -> Unit) {
        // removeLocation is a no-op in robotservice_12.3.jar and never calls the listener.
        callback(false)
    }

    /**
     * Looks up a RobotOS place.
     * @param placeName name of the place
     * @param callback receives the position, or null
     */
    override fun locationOf(placeName: String, callback: (Point2D?) -> Unit) {
        RobotApi.getInstance().getLocation(reqIdSource(), placeName, object : CommandListener() {
            /**
             * Parses the place.
             * @param result result code
             * @param message place as JSON
             * @param extraData extra data
             */
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

    /**
     * Starts a drive to a RobotOS place.
     * @param placeName name of the place
     * @param listener receives progress and outcome
     */
    override fun navigateTo(placeName: String, listener: NavigationListener) {
        val linear = linearSpeed
        val angular = angularSpeed
        val code = if (linear != null && angular != null) {
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

    /**
     * Starts a drive to map coordinates.
     * @param target target position
     * @param listener receives progress and outcome
     */
    override fun navigateTo(target: Point2D, listener: NavigationListener) {
        val pose = Pose(target.x.toFloat(), target.y.toFloat(), 0f)
        val linear = linearSpeed
        val angular = angularSpeed
        val code = if (linear != null && angular != null) {
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

    /** Stops navigation and direct motions. */
    override fun stopNavigation() {
        // stopMove() does not stop navigation, but ends direct motions such as turnLeft/turnRight.
        RobotApi.getInstance().stopNavigation(reqIdSource())
        runCatching { RobotApi.getInstance().stopMove(reqIdSource(), null) }
    }

    /**
     * Turns on the spot.
     * @param angleRad angle in radians, positive = counter-clockwise
     * @param callback receives true on success
     */
    override fun turnInPlace(angleRad: Double, callback: (Boolean) -> Unit) {
        // turnLeft/turnRight take degrees (deg/s and deg).
        val degrees = Math.toDegrees(abs(angleRad)).toFloat()
        val listener = object : CommandListener() {
            /**
             * Passes the result on.
             * @param result result code
             * @param message answer
             * @param extraData extra data
             */
            override fun onResult(result: Int, message: String?, extraData: String?) = callback(result == Definition.RESULT_OK)
            /**
             * Reports the failure.
             * @param errorCode error code
             * @param errorString error text
             * @param extraData extra data
             */
            override fun onError(errorCode: Int, errorString: String?, extraData: String?) = callback(false)
        }
        val api = RobotApi.getInstance()
        val code = if (angleRad >= 0) api.turnLeft(reqIdSource(), TURN_SPEED_DEG_PER_S, degrees, listener)
        else api.turnRight(reqIdSource(), TURN_SPEED_DEG_PER_S, degrees, listener)
        if (code < 0) callback(false)
    }

    /**
     * Turns the SDK's action callbacks into [NavigationListener] calls.
     * @param listener receives the converted events
     * @return the SDK listener
     */
    private fun actionListener(listener: NavigationListener) = object : ActionListener() {
        /**
         * Reports arrival or failure.
         * @param status result status
         * @param response answer
         * @param extraData extra data
         */
        override fun onResult(status: Int, response: String?, extraData: String?) {
            when (status) {
                Definition.RESULT_OK -> listener.onArrived()
                else -> listener.onFailed(NavFailure.Sdk(status, response))
            }
        }

        /**
         * Reports an SDK error.
         * @param errorCode error code
         * @param errorString error text
         * @param extraData extra data
         */
        override fun onError(errorCode: Int, errorString: String?, extraData: String?) {
            listener.onFailed(
                when (errorCode) {
                    Definition.ERROR_NOT_ESTIMATE -> NavFailure.NotLocalized
                    Definition.ERROR_DESTINATION_NOT_EXIST -> NavFailure.DestinationUnknown
                    // "ARRAIVE" is the SDK's spelling.
                    Definition.ERROR_DESTINATION_CAN_NOT_ARRAIVE -> NavFailure.Unreachable
                    Definition.ACTION_RESPONSE_ALREADY_RUN -> NavFailure.AlreadyRunning
                    Definition.ERROR_MULTI_ROBOT_WAITING_TIMEOUT -> NavFailure.TimedOut
                    else -> NavFailure.Sdk(errorCode, errorString)
                }
            )
        }

        /**
         * Reports progress.
         * @param status status code
         * @param data status data
         * @param extraData extra data
         */
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
        const val COORDINATE_DEVIATION = 0.3

        const val OBSTACLE_TIMEOUT_MS = 30_000L

        const val TURN_SPEED_DEG_PER_S = 30f

        private var counter = 1000
        /**
         * A new request id for an SDK call.
         * @return the request id
         */
        private fun nextReqId(): Int = ++counter
    }
}

// Possible extension: private zones as real obstacles by writing them into the occupancy grid
// (ShareMemoryApi.getMapPgmPFD / setMapPgmPFD). This is undocumented, changes the map shared by all apps and
// cannot be undone without a copy of the original grid, so the app-level guard is used instead.
