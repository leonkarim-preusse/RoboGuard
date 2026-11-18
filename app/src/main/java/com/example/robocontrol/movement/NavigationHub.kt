package com.example.robocontrol.movement

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the single [MapNavigation] of the process, shared by the robot screen and the phone app. */
object NavigationHub {
    private const val TAG = "NavigationHub"

    const val REMOTE_ACTIVE_MS = 30_000L

    private var scope: CoroutineScope? = null

    @Volatile
    private var navigation: MapNavigation? = null

    val log = NavigationLog()

    private val _remoteControl = MutableStateFlow<String?>(null)

    val remoteControl: StateFlow<String?> = _remoteControl.asStateFlow()

    @Volatile
    private var lastRemoteAt = 0L

    val current: MapNavigation? get() = navigation

    /**
     * Starts the shared navigation (idempotent). Call from RobotServerService.onCreate.
     * @param context Android context
     */
    @Synchronized
    fun start(context: Context) {
        if (navigation != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        navigation = MapNavigation(context.applicationContext, log, s).also { it.start() }
        Log.i(TAG, "navigation started in the service")
    }

    /** Stops driving and releases the SDK connection. Call from RobotServerService.onDestroy. */
    @Synchronized
    fun stop() {
        navigation?.let {
            it.stop("service stopped")
            it.shutdown()
        }
        navigation = null
        scope?.cancel()
        scope = null
        _remoteControl.value = null
    }

    /**
     * Called by the phone routes before each command, so the robot screen can show who is steering.
     * @param client name of the phone
     */
    @Synchronized
    fun noteRemoteCommand(client: String) {
        lastRemoteAt = android.os.SystemClock.elapsedRealtime()
        if (_remoteControl.value != client) {
            _remoteControl.value = client
            Log.i(TAG, "remote control by $client")
        }
    }

    /** Clears [remoteControl] once the phone has been quiet for [REMOTE_ACTIVE_MS]; called from the state poll and pollLoop. */
    @Synchronized
    fun expireRemoteControl() {
        val active = _remoteControl.value ?: return
        if (android.os.SystemClock.elapsedRealtime() - lastRemoteAt > REMOTE_ACTIVE_MS) {
            _remoteControl.value = null
            Log.i(TAG, "remote control by $active ended (quiet for ${REMOTE_ACTIVE_MS / 1000} s)")
        }
    }
}
