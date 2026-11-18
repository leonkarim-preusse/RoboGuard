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
        if (!startConnecting) return

        Log.i(TAG, "connecting RobotApi (once for the whole app)")
        RobotApi.getInstance().connectServer(context.applicationContext, object : ApiListener {
            /** Marks the connection as up and notifies the listeners. */
            override fun handleApiConnected() {
                val snapshot = synchronized(this@RobotApiConnection) {
                    state = State.CONNECTED
                    listeners.toList()
                }
                _connected.value = true
                Log.i(TAG, "RobotApi connected (${snapshot.size} listeners)")
                snapshot.forEach { runCatching { it.handleApiConnected() }.onFailure { e -> Log.e(TAG, "listener failed", e) } }
            }

            /** Marks the connection as down and notifies the listeners. */
            override fun handleApiDisconnected() {
                val snapshot = synchronized(this@RobotApiConnection) {
                    state = State.IDLE
                    listeners.toList()
                }
                _connected.value = false
                Log.w(TAG, "RobotApi disconnected")
                snapshot.forEach { runCatching { it.handleApiDisconnected() } }
            }

            /** Notifies the listeners that the API is unavailable. */
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

    /**
     * Stops passing connection events to [listener].
     * @param listener listener to remove
     */
    fun removeListener(listener: ApiListener) {
        synchronized(this) { listeners -= listener }
    }
}
