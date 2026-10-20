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

/**
 * Holds the single [MapNavigation] of the process, started by RobotServerService, so that the robot screen and the
 * phone app share the same navigation state and drives continue when the robot shows another screen. [remoteControl]
 * names the phone that is currently steering.
 */
object NavigationHub {

    private const val TAG = "NavigationHub"

    /** How long after the last phone command the robot still counts as remotely controlled. */
    const val REMOTE_ACTIVE_MS = 30_000L

    private var scope: CoroutineScope? = null

    @Volatile
    private var navigation: MapNavigation? = null

    /** Event log shared by both screens (also the robot's own navigation screen shows it). */
    val log = NavigationLog()

    private val _remoteControl = MutableStateFlow<String?>(null)

    /** Name of the phone currently steering (null = nobody); cleared [REMOTE_ACTIVE_MS] after its last command. */
    val remoteControl: StateFlow<String?> = _remoteControl.asStateFlow()

    @Volatile
    private var lastRemoteAt = 0L

    /** The navigation, started on first use. Returns null only before [start] (i.e. before the service exists). */
    val current: MapNavigation? get() = navigation

    /** Starts the shared navigation (idempotent). Call from RobotServerService.onCreate. */
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

    /** Called by the phone routes before each command, so the robot screen can show who is steering. */
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
