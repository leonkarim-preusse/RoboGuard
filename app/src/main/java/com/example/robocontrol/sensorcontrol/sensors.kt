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
    /**
     * Called once for every sensor whose state changed; [enabled] is the new state.
     * @param name sensor name
     * @param enabled new state
     */
    fun onSensorChanged(name: String, enabled: Boolean)
}

/**
 * Called when a situational setting ("Discretion Mode", "Pixelate Objects") changes. These settings enable features;
 * they do not switch any sensor.
 */
fun interface SituationalChangeListener {
    /**
     * Called once for every situational feature whose state changed; [enabled] is the new state.
     * @param name feature name
     * @param enabled new state
     */
    fun onSituationalChanged(name: String, enabled: Boolean)
}

/**
 * Current sensor and situational settings of the robot, shared by the process. Thread-safe.
 * @param context any context; only the application context is kept
 */
class Sensors private constructor(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writeJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val _states = MutableStateFlow(readSettingsFile())

    val states: StateFlow<Map<String, Boolean>> = _states.asStateFlow()

    private val listeners = CopyOnWriteArrayList<SensorChangeListener>()

    private val _situational = MutableStateFlow(readSituationalFile())

    val situational: StateFlow<Map<String, Boolean>> = _situational.asStateFlow()

    private val situationalListeners = CopyOnWriteArrayList<SituationalChangeListener>()

    private val switches = SensorSwitches(context)

    val switchReports: StateFlow<Map<String, SwitchReport>> get() = switches.reports

    init {
        switches.apply(_states.value)
    }

    /**
     * Current sensor states.
     * @return sensor states by name
     */
    fun getSensors(): Map<String, Boolean> = _states.value

    /**
     * Whether [name] (e.g. "Camera") is enabled.
     * @param name sensor name
     * @return null if the settings do not mention this sensor at all; the caller decides what that means
     */
    fun isEnabled(name: String): Boolean? = _states.value[name]

    /**
     * Applies new sensor settings: switches every listed sensor and notifies listeners about the changed ones.
     * @param sensors sensor name → enabled
     */
    @Synchronized
    fun update(sensors: Map<String, Boolean>) {
        notifySensors(assignSensors(sensors))
    }

    /**
     * Sets the state and switches the hardware; returns what changed (listeners are not told yet).
     * @param sensors new states
     * @return the sensors that changed
     */
    private fun assignSensors(sensors: Map<String, Boolean>): Map<String, Boolean> {
        val previous = _states.value
        _states.value = sensors.toMap()
        switches.apply(sensors)
        return sensors.filter { (name, enabled) -> previous[name] != enabled }
    }

    /**
     * Logs the changed sensors and calls every listener once per change (exceptions are caught per listener).
     * @param changed changed sensors
     */
    private fun notifySensors(changed: Map<String, Boolean>) {
        if (changed.isEmpty()) return
        Log.i(TAG, "sensor settings changed: $changed")

        for ((name, enabled) in changed) {
            for (listener in listeners) {
                runCatching { listener.onSensorChanged(name, enabled) }
                    .onFailure { Log.e(TAG, "listener failed for $name=$enabled", it) }
            }
        }
    }

    /**
     * Switches one sensor from the robot (e.g. microphone off from the conversation prompt) and writes it to
     * `privacy_settings.json` if the file exists.
     * @param name sensor name
     * @param enabled new state
     * @return true if the change was also written to the file
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
     * Whether a situational setting is enabled; names are compared case-insensitively.
     * @param name setting name
     * @return the stored value, or null if it was never set
     */
    fun isSituationalEnabled(name: String): Boolean? =
        _situational.value.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * Applies the situational settings of a save without switching hardware.
     * @param values new values
     */
    @Synchronized
    fun updateSituational(values: Map<String, Boolean>) {
        notifySituational(assignSituational(values))
    }

    /**
     * Stores the situational values and returns only the ones that changed.
     * @param values new values
     * @return the values that changed
     */
    private fun assignSituational(values: Map<String, Boolean>): Map<String, Boolean> {
        val previous = _situational.value
        _situational.value = values.toMap()
        return values.filter { (name, on) -> previous[name] != on }
    }

    /**
     * Logs the changed situational features and calls every listener once per change.
     * @param changed changed features
     */
    private fun notifySituational(changed: Map<String, Boolean>) {
        if (changed.isEmpty()) return
        Log.i(TAG, "situational settings changed: $changed")
        for ((name, on) in changed) {
            for (listener in situationalListeners) {
                runCatching { listener.onSituationalChanged(name, on) }
                    .onFailure { Log.e(TAG, "situational listener failed for $name=$on", it) }
            }
        }
    }

    /**
     * Registers [listener] for future changes of the situational settings.
     * @param listener listener to add
     */
    fun addSituationalListener(listener: SituationalChangeListener) {
        situationalListeners += listener
    }

    /**
     * Stops calling [listener] for situational changes.
     * @param listener listener to remove
     */
    fun removeSituationalListener(listener: SituationalChangeListener) {
        situationalListeners -= listener
    }

    /**
     * Waits for the hardware answers of the switches started by an [update].
     * @param sensors requested states
     * @param sinceMs start time (elapsedRealtime)
     * @param timeoutMs maximum wait
     * @return whether all, some or none were confirmed
     */
    suspend fun awaitSwitches(sensors: Map<String, Boolean>, sinceMs: Long, timeoutMs: Long): ApplyResult =
        switches.awaitResult(sensors, sinceMs, timeoutMs)

    /**
     * Applies a full settings object, as received by `POST /save`: sensors (with hardware) and situational settings.
     * @param settings received settings
     * @return the switch job
     */
    fun update(settings: AppSettings) = applyBoth(settings.sensors, settings.situationalSettings)

    /**
     * Re-reads `privacy_settings.json` and applies it, for callers that do not have the settings object.
     * @return the switch job
     */
    fun reload() = applyBoth(readSettingsFile(), readSituationalFile())

    /**
     * Applies sensor and situational settings together; listeners are notified only after both are stored.
     * @param sensors sensor name → enabled
     * @param situational setting name → enabled
     */
    @Synchronized
    private fun applyBoth(sensors: Map<String, Boolean>, situational: Map<String, Boolean>) {
        val situationalChanged = assignSituational(situational)
        val sensorsChanged = assignSensors(sensors)
        notifySensors(sensorsChanged)
        notifySituational(situationalChanged)
    }

    /**
     * Registers [listener] for sensor changes.
     * @param listener the listener
     * @param notifyCurrent if true, the listener is called once for every current sensor
     */
    fun addListener(listener: SensorChangeListener, notifyCurrent: Boolean = false) {
        listeners += listener
        if (notifyCurrent) {
            _states.value.forEach { (name, enabled) -> listener.onSensorChanged(name, enabled) }
        }
    }

    /**
     * Stops calling [listener] for sensor changes.
     * @param listener listener to remove
     */
    fun removeListener(listener: SensorChangeListener) {
        listeners -= listener
    }

    /**
     * Sensor states from the settings file, or [DEFAULT_SENSORS] if it is missing or unreadable.
     * @return sensor states by name
     */
    private fun readSettingsFile(): Map<String, Boolean> {
        val file = getSettingsFile(context)
        if (!file.exists()) return DEFAULT_SENSORS
        return runCatching { json.decodeFromString<AppSettings>(file.readText()).sensors }
            .onFailure { Log.e(TAG, "could not read ${file.name}, using defaults", it) }
            .getOrDefault(DEFAULT_SENSORS)
    }

    /**
     * Situational settings from the settings file; empty (= everything off) while the phone has not saved any.
     * @return feature states by name
     */
    private fun readSituationalFile(): Map<String, Boolean> {
        val file = getSettingsFile(context)
        if (!file.exists()) return DEFAULT_SITUATIONAL
        return runCatching { json.decodeFromString<AppSettings>(file.readText()).situationalSettings }
            .onFailure { Log.e(TAG, "could not read the situational settings from ${file.name}", it) }
            .getOrDefault(DEFAULT_SITUATIONAL)
    }

    companion object {
        private const val TAG = "Sensors"

        val DEFAULT_SENSORS: Map<String, Boolean> = mapOf("Camera" to true, "LIDAR" to true, "Microphone" to true)

        val DEFAULT_SITUATIONAL: Map<String, Boolean> = mapOf("Discretion Mode" to true, "Pixelate Objects" to true)

        @Volatile
        private var instance: Sensors? = null

        /**
         * The shared instance for this app process. Only the application context is kept.
         * @param context Android context
         * @return the instance
         */
        fun get(context: Context): Sensors =
            instance ?: synchronized(this) {
                instance ?: Sensors(context.applicationContext).also { instance = it }
            }
    }
}
