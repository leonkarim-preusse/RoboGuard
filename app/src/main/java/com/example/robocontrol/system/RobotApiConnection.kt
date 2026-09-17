package com.example.robocontrol.system

import android.content.Context
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.RobotApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one RobotApi connection of this app process. Every robocontrol component connects through here instead of
 * calling `RobotApi.connectServer` itself.
 *
 * Why (found on the robot, 2026-09-17): `RobotApi` is a process-wide singleton. Its pose, localization and
 * "have control?" calls (`getCurrentPose`, `isRobotEstimate`, `isActive`) use an internal module registry that
 * `connectServer`'s service connection sets on connect and sets to null on disconnect. With two `connectServer` calls
 * (DefaultAppSetting in MainActivity, then OrionStarBridge in the Navigation screen) those calls stopped working
 * while `startNavigation` still did: the robot drove without a position, so private areas were not enforced.
 * Likewise, one component calling `disconnectApi()` would cut the connection for all others; so nothing here ever
 * disconnects. The connection lives as long as the process.
 */
object RobotApiConnection {

    private const val TAG = "RobotApiConnection"

    private enum class State { IDLE, CONNECTING, CONNECTED }

    private var state = State.IDLE
    private val listeners = mutableListOf<ApiListener>()

    private val _connected = MutableStateFlow(false)

    /** True while the process-wide RobotApi connection is up. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Makes sure RobotApi is connected and registers [listener] for connection events. If the connection is already up,
     * [listener]'s `handleApiConnected` runs right away on the caller's thread; otherwise it runs once the (single)
     * connection comes up. The listener stays registered until [removeListener], and is called again after a reconnect.
     */
    fun connect(context: Context, listener: ApiListener? = null) {
        val alreadyConnected: Boolean
        val startConnecting: Boolean
        synchronized(this) {
            if (listener != null && listener !in listeners) listeners += listener
            // Connected by code outside this helper (e.g. a probe screen): adopt it instead of connecting again.
            if (state != State.CONNECTED && runCatching { RobotApi.getInstance().isApiConnectedService() }.getOrDefault(false)) {
                state = State.CONNECTED
                _connected.value = true
            }
            alreadyConnected = state == State.CONNECTED
            startConnecting = state == State.IDLE
            if (startConnecting) state = State.CONNECTING
        }
        if (alreadyConnected) {
            listener?.handleApiConnected()
            return
        }
        if (!startConnecting) return // a connection is on its way; listener gets called then

        Log.i(TAG, "connecting RobotApi (once for the whole app)")
        RobotApi.getInstance().connectServer(context.applicationContext, object : ApiListener {
            override fun handleApiConnected() {
                val snapshot = synchronized(this@RobotApiConnection) {
                    state = State.CONNECTED
                    listeners.toList()
                }
                _connected.value = true
                Log.i(TAG, "RobotApi connected (${snapshot.size} listeners)")
                snapshot.forEach { runCatching { it.handleApiConnected() }.onFailure { e -> Log.e(TAG, "listener failed", e) } }
            }

            override fun handleApiDisconnected() {
                val snapshot = synchronized(this@RobotApiConnection) {
                    state = State.IDLE
                    listeners.toList()
                }
                _connected.value = false
                Log.w(TAG, "RobotApi disconnected")
                snapshot.forEach { runCatching { it.handleApiDisconnected() } }
            }

            override fun handleApiDisabled() {
                val snapshot = synchronized(this@RobotApiConnection) {
                    state = State.IDLE
                    listeners.toList()
                }
                _connected.value = false
                Log.w(TAG, "RobotApi disabled (app not authorized / not in control)")
                snapshot.forEach { runCatching { it.handleApiDisabled() } }
            }
        })
    }

    fun removeListener(listener: ApiListener) {
        synchronized(this) { listeners -= listener }
    }
}
