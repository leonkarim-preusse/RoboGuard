package com.example.robocontrol.system

import android.content.Context
import android.util.Log
import com.ainirobot.coreservice.client.ApiListener
import com.ainirobot.coreservice.client.RobotApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The single RobotApi connection of the process. All components connect through it; it is never disconnected. */
object RobotApiConnection {

    private const val TAG = "RobotApiConnection"

    /** Where the one connection of the process stands. */
    private enum class State { IDLE, CONNECTING, CONNECTED }

    private var state = State.IDLE
    private val listeners = mutableListOf<ApiListener>()

    private val _connected = MutableStateFlow(false)

    /** True while the process-wide RobotApi connection is up. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Connects RobotApi if needed and registers [listener] for connection events. If already connected,
     * `handleApiConnected` is called immediately on the caller's thread.
     * @param context any context
     * @param listener receives connection events until [removeListener]; may be null
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

    /** Stops passing connection events to [listener]. */
    fun removeListener(listener: ApiListener) {
        synchronized(this) { listeners -= listener }
    }
}
