package com.example.robocontrol.conversation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.example.robocontrol.audio.OrionStarTts
import com.example.robocontrol.audio.TtsFailure
import com.example.robocontrol.audio.TtsListener
import com.example.robocontrol.sensorcontrol.SensorChangeListener
import com.example.robocontrol.sensorcontrol.SituationalChangeListener
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.robocontrol.system.SdkControl
import com.example.robocontrol.text.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Listens for conversations whenever RoboGuard runs (started by RobotServerService) and, when more than one person has
 * been talking for [MULTIPLE_FOR_MS], shows [ConversationPromptActivity] and says [apologySentence]. The person can then
 * send the robot away (Navigation and Map), switch the microphone off, or pause this detection for a while.
 *
 * Listens only while all of these hold: RoboGuard's privacy settings allow the microphone, RECORD_AUDIO is granted, the
 * detection is not paused, and no test screen is using the microphone. Uses [SpeakerChangeDetector] with its default
 * settings (Silero speech gate, evidence rule). No audio is stored; see SpeakerChangeDetector.
 *
 * Not asking again: after a prompt, not again in the same conversation (until the detector's 30 s silence reset) and not
 * within [MIN_PROMPT_GAP_MS].
 */
object ConversationMonitor {

    private const val TAG = "ConversationMonitor"

    /**
     * "More than one speaker" must last this long before the robot reacts. Owner first chose 2 s, then 1 s
     * (2026-09-17), and switched it OFF for now (2026-09-21): the evidence rule already needs several speaker changes
     * before the state flips, so the hold only added latency on top of that. 0 = react to the first snapshot that says
     * MULTIPLE_SPEAKERS, i.e. within the detector's 100 ms publishing interval.
     */
    const val MULTIPLE_FOR_MS = 0L

    /** Minimum time between two prompts. */
    const val MIN_PROMPT_GAP_MS = 2 * 60_000L

    /** Name of the phone's situational switch that turns conversation detection on. */
    const val DISCRETION_MODE = "Discretion Mode"

    private const val PREFS = "robocontrol_conversation"
    private const val KEY_METHOD = "detection_method"
    private const val KEY_ANY_OTHER = "any_other_is_conversation"
    private const val KEY_DIFFERENT_BELOW = "different_voice_below"
    /** How often the spoken test announcement may repeat while the state stays "more than one speaker". */
    const val TEST_ANNOUNCE_COOLDOWN_MS = 5_000L

    /** How long to wait before starting a detector that stopped by itself. */
    private const val RESTART_DELAY_MS = 5_000L

    /** Several switches flipped in a row become one restart. */
    private const val RESTART_DEBOUNCE_MS = 400L

    /** How often the health check looks whether anything is listening. */
    private const val HEALTH_EVERY_MS = 10_000L

    private const val KEY_GATE_MODE = "speech_gate_mode"
    private const val KEY_MIN_LEVEL = "min_level_db"
    private const val KEY_ANNOUNCE_TEST = "announce_in_test"
    private const val KEY_ACTIVITY_SHARE = "activity_share"
    private const val KEY_ACTIVITY_WINDOW = "activity_window_ms"
    private const val KEY_SPEECH_THRESHOLD = "silero_threshold"
    private const val KEY_SPEECH_HYSTERESIS = "silero_hysteresis"
    private const val KEY_ACTIVITY_MIN_SILENCE = "activity_min_silence_ms"
    private const val KEY_CONFIRM_TWICE = "confirm_twice"
    private const val KEY_CONTINUOUS = "continuous_pieces"

    /** Default pause of the detection. Owner: 30 minutes. */
    const val DEFAULT_PAUSE_MINUTES = 30

    /** Spoken with the prompt; wording in assets/texts/texts.json. */
    val apologySentence: String get() = UiText.get("speech.conversation_apology")

    /** Spoken when the microphone is muted from the prompt; wording in assets/texts/texts.json. */
    val micMutedSentence: String get() = UiText.get("speech.microphone_muted")

    /** What the monitor is doing, for screens and logs. */
    data class Status(
        val started: Boolean = false,
        val listening: Boolean = false,
        /** Why it is not listening (null while listening). */
        val reason: String? = null,
        /** Wall-clock end of a pause (System.currentTimeMillis), or null. */
        val pausedUntil: Long? = null,
        val state: ConversationState = ConversationState.NO_SPEECH
    )

    /** Latest detector snapshot, for the speaker debug screen (empty while nothing is listening). */
    private val _snapshot = MutableStateFlow(ConversationSnapshot())
    val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    private val debugViewers = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * A debug screen is open: the detector also collects the audio timeline and the events (speaker changes, resets).
     * Pair every call with [removeDebugViewer]; while nobody watches, nothing extra is collected or copied.
     */
    @Synchronized
    fun addDebugViewer() {
        debugViewers.incrementAndGet()
        (detector as? SpeakerChangeDetector)?.debugTimeline = true
    }

    @Synchronized
    fun removeDebugViewer() {
        if (debugViewers.decrementAndGet() <= 0) {
            debugViewers.set(0)
            (detector as? SpeakerChangeDetector)?.debugTimeline = false
        }
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null
    private var detector: ConversationDetector? = null
    private var watchJob: Job? = null
    private var pauseJob: Job? = null
    private var tts: OrionStarTts? = null

    private var pausedUntil: Long? = null
    private var probeUsingMicrophone = false
    private var promptOpen = false
    private var promptedThisConversation = false
    private var lastPromptAt = Long.MIN_VALUE / 2

    /** Last reason logged by [reevaluate], so the same line is not repeated on every settings change. */
    private var lastReason: String? = "not started"

    private val sensorListener = SensorChangeListener { name, _ ->
        if (name.equals("microphone", ignoreCase = true)) reevaluate()
    }

    /** The phone's "Discretion Mode" switch decides whether conversations are detected at all. */
    private val situationalListener = SituationalChangeListener { name, _ ->
        if (name.equals(DISCRETION_MODE, ignoreCase = true)) reevaluate()
    }

    /** Starts monitoring (idempotent). Call from RobotServerService.onCreate. */
    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        _method.value = runCatching {
            DetectionMethod.valueOf(
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_METHOD, DetectionMethod.CHANGE.name) ?: DetectionMethod.CHANGE.name
            )
        }.getOrDefault(DetectionMethod.CHANGE)
        _anyOtherIsConversation.value =
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ANY_OTHER, false)
        _differentBelow.value = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_DIFFERENT_BELOW, ConversationSettings().differentVoiceBelow)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _gateMode.value = runCatching {
            SpeechGateMode.valueOf(prefs.getString(KEY_GATE_MODE, SpeechGateMode.SILERO_AND_LEVEL.name)!!)
        }.getOrDefault(SpeechGateMode.SILERO_AND_LEVEL)
        _minLevelDb.value = prefs.getFloat(KEY_MIN_LEVEL, ConversationSettings().minLevelDb.toFloat())
        _announceInTest.value = prefs.getBoolean(KEY_ANNOUNCE_TEST, true)
        _activityShare.value = prefs.getFloat(KEY_ACTIVITY_SHARE, ConversationSettings().activityShare)
        _activityWindowMs.value = prefs.getLong(KEY_ACTIVITY_WINDOW, ConversationSettings().activityWindowMs)
        _speechThreshold.value = prefs.getFloat(KEY_SPEECH_THRESHOLD, ConversationSettings().speechThreshold.toFloat())
        _speechHysteresis.value = prefs.getFloat(KEY_SPEECH_HYSTERESIS, ConversationSettings().speechHysteresis.toFloat())
        _activityMinSilenceMs.value = prefs.getLong(KEY_ACTIVITY_MIN_SILENCE, ConversationSettings().activityMinSilenceMs)
        _confirmTwice.value = prefs.getBoolean(KEY_CONFIRM_TWICE, ConversationSettings().confirmTwice)
        _continuousPieces.value = prefs.getBoolean(KEY_CONTINUOUS, ConversationSettings().continuousPieces)
        Sensors.get(appContext).addListener(sensorListener)
        Sensors.get(appContext).addSituationalListener(situationalListener)
        _status.value = _status.value.copy(started = true)
        Log.i(TAG, "started")
        reevaluate()
        scope?.let { startHealthCheck(it) }
    }

    /** Stops everything. Call from RobotServerService.onDestroy. */
    @Synchronized
    fun stop() {
        if (scope == null) return
        stopDetector("service stopped")
        runCatching { Sensors.get(appContext).removeListener(sensorListener) }
        runCatching { Sensors.get(appContext).removeSituationalListener(situationalListener) }
        runCatching { tts?.disconnect() }
        tts = null
        scope?.cancel()
        scope = null
        _status.value = Status()
    }

    /** Pauses the detection for [minutes] (from the prompt). */
    @Synchronized
    fun pauseFor(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        pausedUntil = until
        Log.i(TAG, "paused for $minutes min")
        pauseJob?.cancel()
        pauseJob = scope?.launch {
            delay(minutes * 60_000L)
            synchronized(this@ConversationMonitor) { if (pausedUntil == until) pausedUntil = null }
            Log.i(TAG, "pause over")
            reevaluate()
        }
        reevaluate()
    }

    /** Ends a pause early. */
    @Synchronized
    fun resume() {
        pausedUntil = null
        pauseJob?.cancel()
        reevaluate()
    }

    /** Switches the microphone off in RoboGuard's privacy settings (from the prompt); the monitor stops via the sensor listener. */
    fun microphoneOff() {
        Log.i(TAG, "microphone switched off from the prompt")
        Sensors.get(appContext).setSensor("Microphone", false)
        reevaluate()
        // Tell how to undo it. Speech output does not need the microphone, so this works after muting.
        scope?.launch { speak(micMutedSentence) }
    }

    /** Test screens that record themselves call this, so two recordings do not compete for the microphone. */
    @Synchronized
    fun setProbeUsingMicrophone(active: Boolean) {
        probeUsingMicrophone = active
        if (scope != null) reevaluate()
    }

    /** Called by the prompt when it closes. */
    @Synchronized
    /**
     * Forgets everything that would hold the next prompt back, and starts the detector over: the "already asked in this
     * conversation" flag, the two minutes between prompts, and the detector's own window of speech. Everything heard from
     * this moment counts as a new conversation, so the prompt can come again straight away.
     *
     * A running pause ("don't ask again for N minutes") is NOT lifted — that was a decision of the person at the robot,
     * not a cooldown.
     */
    fun resetCooldown() {
        synchronized(this) {
            promptOpen = false
            promptedThisConversation = false
            lastPromptAt = Long.MIN_VALUE / 2
        }
        Log.i(TAG, "detection cooldown reset: everything from now counts as a new conversation")
        restartDetector("cooldown reset")
    }

    fun promptClosed() {
        promptOpen = false
    }

    /** Starts or stops listening according to the current conditions. */
    @Synchronized
    private fun reevaluate() {
        if (scope == null) return
        val micAllowed = Sensors.get(appContext).getSensors().entries
            .firstOrNull { it.key.equals("microphone", ignoreCase = true) }?.value != false
        val permission = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val paused = pausedUntil?.let { it > System.currentTimeMillis() } == true
        // Owner, 2026-09-21: conversation detection is a feature of Discretion Mode. Off (or never sent by the phone)
        // means the robot does not listen for conversations — WITHOUT switching the microphone itself off; that stays
        // the sensor setting's job, so speech recognition and the other users of the microphone are untouched.
        val discretion = Sensors.get(appContext).isSituationalEnabled(DISCRETION_MODE) == true
        val reason = when {
            !discretion -> "Discretion Mode is off in the privacy settings"
            !micAllowed -> "microphone switched off in the privacy settings"
            !permission -> "RECORD_AUDIO not granted"
            paused -> "paused"
            probeUsingMicrophone -> "a test screen is using the microphone"
            else -> null
        }
        if (reason != lastReason) {
            lastReason = reason
            Log.i(TAG, reason?.let { "not listening: $it" } ?: "conditions met, listening")
        }
        if (reason == null) startDetector() else stopDetector(reason)
        _status.value = _status.value.copy(listening = detector != null, reason = reason, pausedUntil = if (paused) pausedUntil else null)
    }

    /** Which detector is used; survives a restart. Switchable on the speaker debug screen. */
    private val _method = MutableStateFlow(DetectionMethod.CHANGE)
    val method: StateFlow<DetectionMethod> = _method.asStateFlow()

    /**
     * Owner method: does ANY voice that is not the owner count as a conversation (true), or must the owner have been
     * heard in the same window (false, default)?
     *
     * True reacts as soon as somebody else speaks — one piece, about three seconds — and also catches a conversation the
     * owner is not part of. The price is that a television, a radio or a video is a "somebody else" too, so the robot
     * will ask in an empty room.
     */
    private val _anyOtherIsConversation = MutableStateFlow(false)
    val anyOtherIsConversation: StateFlow<Boolean> = _anyOtherIsConversation.asStateFlow()

    fun setAnyOtherIsConversation(any: Boolean) {
        if (_anyOtherIsConversation.value == any) return
        _anyOtherIsConversation.value = any
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANY_OTHER, any).apply()
        Log.i(TAG, "any other voice counts: $any")
        restartDetector("rule changed")
    }

    /** Switches the method and restarts the detector, so the change is audible right away. */
    fun setMethod(method: DetectionMethod) {
        if (_method.value == method) return
        _method.value = method
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_METHOD, method.name).apply()
        Log.i(TAG, "detection method: $method")
        restartDetector("method changed")
    }

    private fun startDetector() {
        if (detector != null) return
        val s = scope ?: return
        when (_method.value) {
            DetectionMethod.OWNER -> { startOwnerDetector(s); return }
            DetectionMethod.PAIRWISE -> { startPairwiseDetector(s); return }
            DetectionMethod.ACTIVITY -> { startActivityDetector(s); return }
            DetectionMethod.CHANGE -> Unit
        }
        // The change detector has no separate level threshold to switch: its loudness gate follows the noise floor.
        // "Level only" therefore means its LOUDNESS gate, everything else its Silero gate.
        val config = ChangeDetectorConfig(
            speechGate = if (_gateMode.value == SpeechGateMode.LEVEL_ONLY) SpeechGate.LOUDNESS else SpeechGate.SILERO,
            sileroThreshold = _speechThreshold.value.toDouble(),
            sileroHysteresis = _speechHysteresis.value.toDouble()
        )
        Log.i(TAG, "config: window ${config.windowFrames / 100.0} s, λ ${config.bicLambda}, ${config.speechGate} ${config.sileroThreshold}, " +
            "${config.decisionRule} r₀ ${config.evidenceBaseRatio} threshold ${config.evidenceThreshold} half-life ${config.evidenceHalfLifeMs / 1000} s")
        // Candidates are logged (numbers only) so a wrong prompt can be traced afterwards.
        val robotSpeaking = RobotSpeaking(appContext)
        val d = SpeakerChangeDetector(
            AndroidMicSource(), config,
            sileroFactory = { SileroVad(appContext) },
            robotSpeaking = robotSpeaking::active
        ) { c ->
            val ratio = if (c.bicPenalty > 0) c.bicGain / c.bicPenalty else 0.0
            Log.i(TAG, "candidate at %.1f s: r=%.2f (evidence +%.2f), ΔBIC %.0f %s".format(
                c.timeMs / 1000.0, ratio, c.evidenceAdded(config.evidenceBaseRatio), c.deltaBic,
                when { c.countsAsChange -> "CHANGE"; c.accepted -> "(same group)"; else -> "" } +
                    if (c.groupBestRatioBefore > 0) " [group best before %.2f]".format(c.groupBestRatioBefore) else ""))
        }
        d.debugTimeline = debugViewers.get() > 0
        watch(s, d)
        Log.i(TAG, "listening")
    }

    /**
     * Starts the owner comparison instead of the change detector. Needs a stored voice; without one it says so and
     * nothing listens, rather than silently falling back to the other method.
     */
    private fun startOwnerDetector(s: CoroutineScope) {
        if (!OwnerVoiceprintStore.get(appContext).exists()) {
            Log.w(TAG, "owner comparison chosen, but no voice is stored — teach it on the voice screen")
            _status.value = _status.value.copy(reason = "no owner voice stored")
            return
        }
        val any = _anyOtherIsConversation.value
        // "Any other voice" is meant to react the moment somebody else speaks, so one piece is enough there.
        val settings = ConversationSettings(
            anyOtherIsConversation = any,
            piecesForOther = if (any) 1 else ConversationSettings().piecesForOther,
            gateMode = _gateMode.value,
            minLevelDb = _minLevelDb.value.toDouble(),
            speechThreshold = _speechThreshold.value.toDouble(),
            speechHysteresis = _speechHysteresis.value.toDouble(),
            confirmTwice = _confirmTwice.value,
            continuousPieces = _continuousPieces.value
        )
        Log.i(TAG, "config: owner comparison, piece ${settings.pieceSeconds} s, margin ${settings.margin}, " +
            "${settings.piecesForOther} pieces, window ${settings.windowMs / 1000} s, " +
            (if (settings.anyOtherIsConversation) "any other voice counts" else "owner + other") +
            ", confirm twice ${settings.confirmTwice}")
        watch(s, OwnerVoiceDetector(appContext, settings = settings))
        Log.i(TAG, "listening")
    }

    /**
     * Experimental: compares the pieces of speech with each other, so no voice has to be taught. Needs no template; a
     * recorded cohort is used only to centre the comparison.
     */
    private fun startPairwiseDetector(s: CoroutineScope) {
        val settings = ConversationSettings(
            differentVoiceBelow = _differentBelow.value,
            gateMode = _gateMode.value,
            minLevelDb = _minLevelDb.value.toDouble(),
            speechThreshold = _speechThreshold.value.toDouble(),
            speechHysteresis = _speechHysteresis.value.toDouble(),
            confirmTwice = _confirmTwice.value,
            continuousPieces = _continuousPieces.value
        )
        Log.i(TAG, "config: comparing voices with each other, piece ${settings.pieceSeconds} s, " +
            "different below ${settings.differentVoiceBelow}, ${settings.piecesKept} pieces held, " +
            "window ${settings.windowMs / 1000} s, confirm twice ${settings.confirmTwice}")
        watch(s, PairwiseVoiceDetector(appContext, settings = settings))
        Log.i(TAG, "listening")
    }

    /**
     * Which gate decides that a frame is speech (see [SpeechGateMode]). Applies to the owner and the compare methods; the
     * change detector has its own adaptive loudness floor, so there only "level only" reaches it, as its LOUDNESS gate.
     */
    private val _gateMode = MutableStateFlow(SpeechGateMode.SILERO_AND_LEVEL)
    val gateMode: StateFlow<SpeechGateMode> = _gateMode.asStateFlow()

    fun setGateMode(mode: SpeechGateMode) {
        if (_gateMode.value == mode) return
        _gateMode.value = mode
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GATE_MODE, mode.name).apply()
        Log.i(TAG, "speech gate: $mode")
        restartDetector("speech gate changed")
    }

    /**
     * Silero's own two numbers, for every method: the probability at which speech starts, and how much lower it has to
     * fall before speech ends again. 0.5 / 0.15 are the values the change detector always used; the other methods ran
     * without hysteresis until 2026-09-28. The model itself cannot be tuned — these decide what is made of its output.
     */
    private val _speechThreshold = MutableStateFlow(ConversationSettings().speechThreshold.toFloat())
    val speechThreshold: StateFlow<Float> = _speechThreshold.asStateFlow()

    fun setSpeechThreshold(value: Float) {
        val clamped = value.coerceIn(0.05f, 0.95f)
        if (_speechThreshold.value == clamped) return
        _speechThreshold.value = clamped
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEECH_THRESHOLD, clamped).apply()
        Log.i(TAG, "silero threshold %.2f".format(clamped))
        restartDetector("silero threshold changed")
    }

    private val _speechHysteresis = MutableStateFlow(ConversationSettings().speechHysteresis.toFloat())
    val speechHysteresis: StateFlow<Float> = _speechHysteresis.asStateFlow()

    fun setSpeechHysteresis(value: Float) {
        val clamped = value.coerceIn(0f, 0.4f)
        if (_speechHysteresis.value == clamped) return
        _speechHysteresis.value = clamped
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEECH_HYSTERESIS, clamped).apply()
        Log.i(TAG, "silero hysteresis %.2f".format(clamped))
        restartDetector("silero hysteresis changed")
    }

    /**
     * Outlier filter: a verdict that changes has to be repeated by the next piece before it counts (see
     * [ConversationSettings.confirmTwice]). Costs about three seconds at every transition and drops a real change that
     * nothing follows; in exchange a single stray piece — the mixture of two voices at a transition, a cough, a door —
     * cannot move the decision on its own.
     */
    private val _confirmTwice = MutableStateFlow(ConversationSettings().confirmTwice)
    val confirmTwice: StateFlow<Boolean> = _confirmTwice.asStateFlow()

    fun setConfirmTwice(on: Boolean) {
        if (_confirmTwice.value == on) return
        _confirmTwice.value = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONFIRM_TWICE, on).apply()
        Log.i(TAG, "a changed verdict must be repeated: $on")
        restartDetector("outlier filter changed")
    }

    /**
     * How a piece of speech is cut out of the stream (see [ConversationSettings.continuousPieces]). Off = only the gated
     * frames, glued together. On = a continuous stretch with a little padding at the start and short pauses left in.
     * Off by default, so every measurement made before 2026-09-28 stays comparable.
     */
    private val _continuousPieces = MutableStateFlow(ConversationSettings().continuousPieces)
    val continuousPieces: StateFlow<Boolean> = _continuousPieces.asStateFlow()

    fun setContinuousPieces(on: Boolean) {
        if (_continuousPieces.value == on) return
        _continuousPieces.value = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONTINUOUS, on).apply()
        Log.i(TAG, "continuous pieces (padding + bridged gaps): $on")
        restartDetector("piece assembly changed")
    }

    /** Activity method: how long a gap still counts as speech. */
    private val _activityMinSilenceMs = MutableStateFlow(ConversationSettings().activityMinSilenceMs)
    val activityMinSilenceMs: StateFlow<Long> = _activityMinSilenceMs.asStateFlow()

    fun setActivityMinSilenceMs(value: Long) {
        val clamped = value.coerceIn(0L, 2_000L)
        if (_activityMinSilenceMs.value == clamped) return
        _activityMinSilenceMs.value = clamped
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_ACTIVITY_MIN_SILENCE, clamped).apply()
        Log.i(TAG, "speech gaps up to $clamped ms still count as speech")
        restartDetector("hangover changed")
    }

    /** The loudness floor of the gate, in dBFS. Adjustable because the right value depends on the room and the distance. */
    private val _minLevelDb = MutableStateFlow(ConversationSettings().minLevelDb.toFloat())
    val minLevelDb: StateFlow<Float> = _minLevelDb.asStateFlow()

    fun setMinLevelDb(value: Float) {
        val clamped = value.coerceIn(-90f, -20f)
        if (_minLevelDb.value == clamped) return
        _minLevelDb.value = clamped
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_MIN_LEVEL, clamped).apply()
        Log.i(TAG, "speech level floor %.0f dBFS".format(clamped))
        restartDetector("level floor changed")
    }

    /**
     * Test aid: while the speaker screen is open, say "Conversation detected" out loud on every detection, at most every
     * [TEST_ANNOUNCE_COOLDOWN_MS]. Completely separate from the prompt — it has no cooldown of two minutes, no
     * "once per conversation" rule, and it never opens a window. Outside that screen nothing is spoken.
     */
    private val _announceInTest = MutableStateFlow(true)
    val announceInTest: StateFlow<Boolean> = _announceInTest.asStateFlow()

    fun setAnnounceInTest(on: Boolean) {
        if (_announceInTest.value == on) return
        _announceInTest.value = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANNOUNCE_TEST, on).apply()
        Log.i(TAG, "say \"conversation detected\" on the speaker screen: $on")
    }

    /** elapsedRealtime of the last spoken test announcement. */
    private var lastTestAnnounceAt = 0L

    /** True while a failed detector is waiting to be started again (see the watchdog in watch()). */
    @Volatile
    private var restarting = false

    /** Only one restart at a time, and only the newest of several quick setting changes. */
    private val restartMutex = Mutex()
    private var restartJob: Job? = null
    private var healthJob: Job? = null

    /**
     * Speaks on the speaker screen, and only there. Called on every snapshot that says more than one speaker, so a held
     * state repeats the sentence every [TEST_ANNOUNCE_COOLDOWN_MS] — which is the point: it is the audible version of the
     * banner while somebody stands in front of the robot and tries things out.
     */
    private fun maybeAnnounceInTest() {
        if (!_announceInTest.value || debugViewers.get() <= 0) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastTestAnnounceAt < TEST_ANNOUNCE_COOLDOWN_MS) return
            lastTestAnnounceAt = now
        }
        Log.i(TAG, "speaker screen: saying \"conversation detected\"")
        scope?.launch { speak(UiText.get("speech.conversation_detected")) }
    }

    /**
     * Counts only how much of the last seconds carried speech: a window that is almost full of speech means somebody is
     * answering. Needs no voice, no template and no embedding at all.
     */
    private fun startActivityDetector(s: CoroutineScope) {
        val settings = ConversationSettings(
            gateMode = _gateMode.value,
            minLevelDb = _minLevelDb.value.toDouble(),
            speechThreshold = _speechThreshold.value.toDouble(),
            speechHysteresis = _speechHysteresis.value.toDouble(),
            activityShare = _activityShare.value,
            activityWindowMs = _activityWindowMs.value,
            activityMinSilenceMs = _activityMinSilenceMs.value,
            confirmTwice = _confirmTwice.value
        )
        Log.i(TAG, "config: speech activity, ${(settings.activityShare * 100).toInt()} %% of the last " +
            "${settings.activityWindowMs / 1000} s, gaps up to ${settings.activityMinSilenceMs} ms count, " +
            "gate ${settings.gateMode} at ${settings.minLevelDb} dBFS, silero ${settings.speechThreshold}, " +
            "confirm twice ${settings.confirmTwice}")
        watch(s, SpeechActivityDetector(appContext, settings = settings))
        Log.i(TAG, "listening")
    }

    /** Activity method: share of the window that must carry speech, and how long that window is. */
    private val _activityShare = MutableStateFlow(ConversationSettings().activityShare)
    val activityShare: StateFlow<Float> = _activityShare.asStateFlow()

    fun setActivityShare(value: Float) {
        val clamped = value.coerceIn(0.3f, 1.0f)
        if (_activityShare.value == clamped) return
        _activityShare.value = clamped
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_ACTIVITY_SHARE, clamped).apply()
        Log.i(TAG, "speech activity share %.0f %%".format(clamped * 100))
        restartDetector("activity share changed")
    }

    private val _activityWindowMs = MutableStateFlow(ConversationSettings().activityWindowMs)
    val activityWindowMs: StateFlow<Long> = _activityWindowMs.asStateFlow()

    fun setActivityWindowMs(value: Long) {
        val clamped = value.coerceIn(3_000L, 60_000L)
        if (_activityWindowMs.value == clamped) return
        _activityWindowMs.value = clamped
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_ACTIVITY_WINDOW, clamped).apply()
        Log.i(TAG, "speech activity window ${clamped / 1000} s")
        restartDetector("activity window changed")
    }

    /**
     * Owner method: the similarity at which a piece counts as the owner. It lives with the stored voice (each voice has
     * its own), so this writes it back to [OwnerVoiceprintStore] and restarts the detector, which reads the template —
     * and the threshold with it — when it starts. The same number can also be set on the "Teach owner's voice" screen.
     */
    fun setOwnerThreshold(value: Float) {
        val error = OwnerVoiceprintStore.get(appContext).setThreshold(value)
        if (error != null) {
            Log.w(TAG, "owner threshold not saved: $error")
            return
        }
        Log.i(TAG, "owner threshold %.2f".format(value))
        if (_method.value == DetectionMethod.OWNER) restartDetector("owner threshold changed")
    }

    /** Pairwise method: similarity below which two pieces count as two voices. Measured on the robot, so adjustable. */
    private val _differentBelow = MutableStateFlow(ConversationSettings().differentVoiceBelow)
    val differentBelow: StateFlow<Float> = _differentBelow.asStateFlow()

    fun setDifferentBelow(value: Float) {
        val clamped = value.coerceIn(-0.5f, 0.95f)
        if (_differentBelow.value == clamped) return
        _differentBelow.value = clamped
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_DIFFERENT_BELOW, clamped).apply()
        Log.i(TAG, "two voices below %.2f".format(clamped))
        restartDetector("threshold changed")
    }

    /**
     * The gate and the piece assembly as they are set right now, WITHOUT the method-specific parts.
     *
     * The voice enrolment uses this so a template is recorded exactly the way the detector will later cut the pieces it
     * is compared against. They used to differ — the enrolment had its own fixed threshold of 0.5, no loudness floor, no
     * hysteresis and always the glued-together assembly — and a template made one way but compared the other way reads
     * as a different channel, which is precisely what the similarity is measuring (owner, 2026-09-28: speaking alone
     * gave 0.20 … 0.73 against a threshold of 0.35).
     */
    fun audioSettings(): ConversationSettings = ConversationSettings(
        gateMode = _gateMode.value,
        speechThreshold = _speechThreshold.value.toDouble(),
        speechHysteresis = _speechHysteresis.value.toDouble(),
        minLevelDb = _minLevelDb.value.toDouble(),
        continuousPieces = _continuousPieces.value
    )

    /** Publishes a detector's snapshots and turns MULTIPLE_SPEAKERS into the prompt. Shared by all methods. */
    private fun watch(s: CoroutineScope, d: ConversationDetector) {
        detector = d
        var multipleSince: Long? = null
        watchJob = s.launch {
            d.snapshot.collect { snap ->
                _snapshot.value = snap
                if (snap.error != null) Log.w(TAG, "detector: ${snap.error}")
                // A detector that ends on its own (the microphone could not be opened, the model failed to load, an
                // exception in its thread) used to stay registered here for ever, so nothing listened again until the app
                // was restarted. Give it back and try once more a few seconds later.
                if (!snap.running && snap.error != null && !restarting) {
                    restarting = true
                    Log.w(TAG, "detector stopped by itself (${snap.error}); trying again in ${RESTART_DELAY_MS / 1000} s")
                    s.launch {
                        delay(RESTART_DELAY_MS)
                        stopDetector("detector had stopped by itself")
                        reevaluate()
                        restarting = false
                    }
                }
                _status.value = _status.value.copy(state = snap.state)
                if (snap.running && snap.speechSeconds == 0.0) promptedThisConversation = false // new conversation
                if (snap.state == ConversationState.MULTIPLE_SPEAKERS) {
                    val now = SystemClock.elapsedRealtime()
                    val since = multipleSince ?: now.also { multipleSince = it }
                    maybeAnnounceInTest()
                    if (now - since >= MULTIPLE_FOR_MS) maybePrompt()
                } else {
                    multipleSince = null
                }
            }
        }
        d.start()
    }

    /**
     * Stops the detector and starts it again, on the monitor's own thread.
     *
     * Every setting on the speaker screen ends here. It must not run on the caller's thread: stopping waits for the
     * detector to release the microphone, and starting the next one before that has happened means two AudioRecords on
     * the same device — on Android 9 the second one opens and returns silence, or fails outright, and then nothing
     * listens at all until the app is restarted (seen on the robot 2026-09-28 after three settings were changed quickly
     * one after another).
     */
    private fun restartDetector(reason: String) {
        val s = scope ?: return
        // Several switches flipped quickly must not produce several restarts running into each other: the previous,
        // still waiting one is dropped, and the actual stop/start is serialised and NOT cancellable. Without that, two
        // restarts could overlap and leave a detector that was started while the one before it still held the
        // microphone — after which nothing arrives at all (owner, 2026-09-28).
        restartJob?.cancel()
        restartJob = s.launch {
            delay(RESTART_DEBOUNCE_MS)
            withContext(NonCancellable) {
                restartMutex.withLock {
                    stopDetector(reason)
                    reevaluate()
                }
            }
        }
    }

    /**
     * Checks every [HEALTH_EVERY_MS] that something is actually listening: the conditions are met but no detector
     * exists, or the detector has been reporting "not running" — then start it again. A safety net for exactly the case
     * the owner reported: change a setting, and nothing comes through any more.
     */
    private fun startHealthCheck(s: CoroutineScope) {
        healthJob?.cancel()
        healthJob = s.launch {
            while (isActive) {
                delay(HEALTH_EVERY_MS)
                if (restartMutex.isLocked || restarting) continue
                val shouldListen = _status.value.reason == null && _status.value.started
                val alive = detector != null && (_snapshot.value.running || _snapshot.value.timeMs == 0L)
                if (shouldListen && !alive) {
                    Log.w(TAG, "nothing is listening although it should be; starting the detector again")
                    withContext(NonCancellable) {
                        restartMutex.withLock {
                            stopDetector("health check")
                            reevaluate()
                        }
                    }
                }
            }
        }
    }

    private fun stopDetector(reason: String) {
        val d = detector ?: return
        detector = null
        _snapshot.value = ConversationSnapshot()
        d.stop()
        watchJob?.cancel()
        watchJob = null
        Log.i(TAG, "not listening: $reason")
    }

    private fun maybePrompt() {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (promptOpen || promptedThisConversation || now - lastPromptAt < MIN_PROMPT_GAP_MS) return
            promptOpen = true
            promptedThisConversation = true
            lastPromptAt = now
        }
        Log.i(TAG, if (MULTIPLE_FOR_MS == 0L) "more than one speaker: showing prompt"
        else "more than one speaker for $MULTIPLE_FOR_MS ms: showing prompt")
        runCatching {
            appContext.startActivity(
                Intent(appContext, ConversationPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.e(TAG, "could not show the prompt", it)
            promptClosed()
        }
        scope?.launch { speak(apologySentence) }
    }

    /** Speaks once RoboGuard has SDK control (the prompt brings it to the front first). Never throws. */
    private suspend fun speak(sentence: String) {
        try {
            SdkControl.awaitControl(appContext)
            val speech = synchronized(this) { tts ?: OrionStarTts(appContext).also { tts = it; it.connect() } }
            if (withTimeoutOrNull(5_000) { speech.connected.first { it } } == null) {
                Log.w(TAG, "speech service not connected, not spoken")
                return
            }
            speech.speakConfigured(sentence, object : TtsListener {
                override fun onFailed(failure: TtsFailure) { Log.w(TAG, "not spoken: $failure") }
            })
        } catch (e: Exception) {
            Log.e(TAG, "could not speak", e)
        }
    }
}
