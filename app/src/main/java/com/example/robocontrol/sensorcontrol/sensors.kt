package com.example.robocontrol.sensorcontrol

import android.content.Context
import android.util.Log
import com.example.roboguard.AppSettings
import com.example.roboguard.getSettingsFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reacts to one sensor being switched on or off, e.g. stopping the camera stream when
 * "Camera" is disabled. Called once per sensor whose state actually changed.
 */
fun interface SensorChangeListener {
    fun onSensorChanged(name: String, enabled: Boolean)
}

/**
 * Called when a situational setting changes ("Discretion Mode", "Pixelate Objects").
 *
 * These are not sensors: they do not switch the microphone or the camera on or off, they only say whether a feature that
 * USES them may run. Discretion Mode gates the conversation detection, Pixelate Objects the object detection.
 */
fun interface SituationalChangeListener {
    fun onSituationalChanged(name: String, enabled: Boolean)
}

/**
 * The robot's current sensor permissions, as set by the user in the RoboGuard phone app.
 *
 * Flow:
 *  1. On first use ([get]) the state is loaded from `privacy_settings.json`. If the phone has never
 *     saved settings, that file does not exist, and [DEFAULT_SENSORS] is used instead.
 *  2. `RobotServerService` calls [update] after every successful `POST /save`.
 *  3. [update] replaces the state and tries to switch the robot's sensors accordingly: LIDAR through the
 *     SDK, microphone and camera through both the SDK and Android (see [SensorSwitches]). It then notifies
 *     every [SensorChangeListener] for each sensor that changed. What each switch achieved is in [switchReports].
 *
 * The switches are also applied once when this object is created, so a sensor the user turned off
 * stays off after the app restarts.
 *
 * There is one instance per app process, obtained with [get], so the server and robocontrol code
 * share the same state without binding to the service.
 *
 * Thread-safe. [update] may be called from the server's network thread; listeners run on that
 * thread, in registration order, and should return quickly (hand longer work off to a coroutine).
 */
class Sensors private constructor(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val writeJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val _states = MutableStateFlow(readSettingsFile())

    /** Sensor name -> enabled, e.g. {"Camera": false, "LIDAR": true, "Microphone": true}. */
    val states: StateFlow<Map<String, Boolean>> = _states.asStateFlow()

    private val listeners = CopyOnWriteArrayList<SensorChangeListener>()

    private val _situational = MutableStateFlow(readSituationalFile())

    /**
     * Situational setting -> on, e.g. {"Discretion Mode": true, "Pixelate Objects": false}. A setting that is not in the
     * map has never been sent by the phone and counts as off, so nothing starts watching or listening by itself.
     */
    val situational: StateFlow<Map<String, Boolean>> = _situational.asStateFlow()

    private val situationalListeners = CopyOnWriteArrayList<SituationalChangeListener>()

    private val switches = SensorSwitches(context)

    /**
     * The latest attempt per sensor and mechanism (SDK or Android), with CONFIRMED / SENT / FAILED /
     * PENDING. Use it to show the user which sensors are really off, not just requested off.
     */
    val switchReports: StateFlow<Map<String, SwitchReport>> get() = switches.reports

    init {
        switches.apply(_states.value)
    }

    /** Current sensor states. Always up to date, unlike a value copied once into a field. */
    fun getSensors(): Map<String, Boolean> = _states.value

    /**
     * Whether [name] (e.g. "Camera") is enabled.
     * @return null if the settings do not mention this sensor at all; the caller decides what that means
     */
    fun isEnabled(name: String): Boolean? = _states.value[name]

    /**
     * Applies new sensor settings: switches the robot's sensors (false = off, true = on) and notifies
     * listeners about every sensor whose state changed.
     *
     * The hardware switches run for every sensor in [sensors], not only the changed ones, because RobotOS
     * may have switched something back on in the meantime; the calls are idempotent.
     * A sensor that is new in [sensors] counts as changed. A sensor missing from [sensors] is removed
     * from the state without a notification, because there is no on/off value to report for it.
     */
    @Synchronized
    fun update(sensors: Map<String, Boolean>) {
        val previous = _states.value
        _states.value = sensors.toMap()
        switches.apply(sensors)

        val changed = sensors.filter { (name, enabled) -> previous[name] != enabled }
        if (changed.isEmpty()) return
        Log.i(TAG, "sensor settings changed: $changed")

        for ((name, enabled) in changed) {
            for (listener in listeners) {
                // One failing listener must not stop the other sensors from being switched.
                runCatching { listener.onSensorChanged(name, enabled) }
                    .onFailure { Log.e(TAG, "listener failed for $name=$enabled", it) }
            }
        }
    }

    /**
     * Switches one sensor from the robot itself (e.g. "Microphone" off from the conversation popup): applies it like
     * [update] and writes it into `privacy_settings.json`, so it survives a restart. The phone app's next save replaces
     * it again. If the file does not exist yet (phone never saved), the change is applied in memory only.
     *
     * @return true if it was also written to the file
     */
    fun setSensor(name: String, enabled: Boolean): Boolean {
        val current = getSensors()
        val key = current.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        update(current + (key to enabled))
        val file = getSettingsFile(context)
        if (!file.exists()) {
            Log.w(TAG, "$key=$enabled applied, but not saved: no privacy_settings.json yet")
            return false
        }
        return runCatching {
            val settings = json.decodeFromString<AppSettings>(file.readText())
            val sensors = settings.sensors.toMutableMap()
            sensors[settings.sensors.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: key] = enabled
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(writeJson.encodeToString(AppSettings.serializer(), settings.copy(sensors = sensors)))
            check(tmp.renameTo(file)) { "rename failed" }
            Log.i(TAG, "$key=$enabled applied and saved")
        }.onFailure { Log.e(TAG, "could not save $key=$enabled", it) }.isSuccess
    }

    /**
     * Is a situational setting on? Names are matched case-insensitively, because the robot's own defaults write
     * "pixelate objects" while the capabilities list and the phone use "Pixelate Objects".
     *
     * @return null if the phone has never sent this setting; callers treat that as off.
     */
    fun isSituationalEnabled(name: String): Boolean? =
        _situational.value.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * Applies the situational settings of a save. Unlike [update] this switches NO hardware: the microphone and the
     * camera stay exactly as the sensor settings say. It only tells the features that use them whether they may run.
     */
    @Synchronized
    fun updateSituational(values: Map<String, Boolean>) {
        val previous = _situational.value
        _situational.value = values.toMap()
        val changed = values.filter { (name, on) -> previous[name] != on }
        if (changed.isEmpty()) return
        Log.i(TAG, "situational settings changed: $changed")
        for ((name, on) in changed) {
            for (listener in situationalListeners) {
                runCatching { listener.onSituationalChanged(name, on) }
                    .onFailure { Log.e(TAG, "situational listener failed for $name=$on", it) }
            }
        }
    }

    /** Registers [listener] for future changes of the situational settings. */
    fun addSituationalListener(listener: SituationalChangeListener) {
        situationalListeners += listener
    }

    fun removeSituationalListener(listener: SituationalChangeListener) {
        situationalListeners -= listener
    }

    /** Applies a full settings object, as received by `POST /save`: sensors (with hardware) and situational settings. */
    fun update(settings: AppSettings) {
        updateSituational(settings.situationalSettings)
        update(settings.sensors)
    }

    /** Re-reads `privacy_settings.json` and applies it, for callers that do not have the settings object. */
    fun reload() {
        updateSituational(readSituationalFile())
        update(readSettingsFile())
    }

    /**
     * Registers [listener] for future changes.
     * @param notifyCurrent if true, the listener is immediately called once for every current sensor,
     *        so it can bring the hardware in line with the state that existed before it registered
     */
    fun addListener(listener: SensorChangeListener, notifyCurrent: Boolean = false) {
        listeners += listener
        if (notifyCurrent) {
            _states.value.forEach { (name, enabled) -> listener.onSensorChanged(name, enabled) }
        }
    }

    fun removeListener(listener: SensorChangeListener) {
        listeners -= listener
    }

    /** Sensor states from the settings file, or [DEFAULT_SENSORS] if it is missing or unreadable. */
    private fun readSettingsFile(): Map<String, Boolean> {
        val file = getSettingsFile(context)
        if (!file.exists()) return DEFAULT_SENSORS
        return runCatching { json.decodeFromString<AppSettings>(file.readText()).sensors }
            .onFailure { Log.e(TAG, "could not read ${file.name}, using defaults", it) }
            .getOrDefault(DEFAULT_SENSORS)
    }

    /** Situational settings from the settings file; empty (= everything off) while the phone has not saved any. */
    private fun readSituationalFile(): Map<String, Boolean> {
        val file = getSettingsFile(context)
        if (!file.exists()) return emptyMap()
        return runCatching { json.decodeFromString<AppSettings>(file.readText()).situationalSettings }
            .onFailure { Log.e(TAG, "could not read the situational settings from ${file.name}", it) }
            .getOrDefault(emptyMap())
    }

    companion object {
        private const val TAG = "Sensors"

        /**
         * Used until the phone app has saved settings once.
         * Mirrors `RobotServerService.getDefaultSettings()`; keep both in sync.
         */
        val DEFAULT_SENSORS: Map<String, Boolean> = mapOf("Camera" to true, "LIDAR" to true, "Microphone" to true)

        @Volatile
        private var instance: Sensors? = null

        /** The shared instance for this app process. Only the application context is kept. */
        fun get(context: Context): Sensors =
            instance ?: synchronized(this) {
                instance ?: Sensors(context.applicationContext).also { instance = it }
            }
    }
}
