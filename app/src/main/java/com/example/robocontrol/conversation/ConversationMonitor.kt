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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        Sensors.get(appContext).addListener(sensorListener)
        Sensors.get(appContext).addSituationalListener(situationalListener)
        _status.value = _status.value.copy(started = true)
        Log.i(TAG, "started")
        reevaluate()
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
        if (scope != null) {
            stopDetector("cooldown reset")
            reevaluate()
        }
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
        if (scope != null) {
            stopDetector("rule changed")
            reevaluate()
        }
    }

    /** Switches the method and restarts the detector, so the change is audible right away. */
    fun setMethod(method: DetectionMethod) {
        if (_method.value == method) return
        _method.value = method
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_METHOD, method.name).apply()
        Log.i(TAG, "detection method: $method")
        if (scope != null) {
            stopDetector("method changed")
            reevaluate()
        }
    }

    private fun startDetector() {
        if (detector != null) return
        val s = scope ?: return
        when (_method.value) {
            DetectionMethod.OWNER -> { startOwnerDetector(s); return }
            DetectionMethod.PAIRWISE -> { startPairwiseDetector(s); return }
            DetectionMethod.CHANGE -> Unit
        }
        val config = ChangeDetectorConfig()
        Log.i(TAG, "config: window ${config.windowFrames / 100.0} s, λ ${config.bicLambda}, ${config.speechGate} ${config.sileroThreshold}, " +
            "${config.decisionRule} r₀ ${config.evidenceBaseRatio} threshold ${config.evidenceThreshold} half-life ${config.evidenceHalfLifeMs / 1000} s")
        // Candidates are logged (numbers only) so a wrong prompt can be traced afterwards.
        val d = SpeakerChangeDetector(AndroidMicSource(), config, sileroFactory = { SileroVad(appContext) }) { c ->
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
            piecesForOther = if (any) 1 else ConversationSettings().piecesForOther
        )
        Log.i(TAG, "config: owner comparison, piece ${settings.pieceSeconds} s, margin ${settings.margin}, " +
            "${settings.piecesForOther} pieces, window ${settings.windowMs / 1000} s, " +
            if (settings.anyOtherIsConversation) "any other voice counts" else "owner + other")
        watch(s, OwnerVoiceDetector(appContext, settings = settings))
        Log.i(TAG, "listening")
    }

    /**
     * Experimental: compares the pieces of speech with each other, so no voice has to be taught. Needs no template; a
     * recorded cohort is used only to centre the comparison.
     */
    private fun startPairwiseDetector(s: CoroutineScope) {
        val settings = ConversationSettings(differentVoiceBelow = _differentBelow.value)
        Log.i(TAG, "config: comparing voices with each other, piece ${settings.pieceSeconds} s, " +
            "different below ${settings.differentVoiceBelow}, ${settings.piecesKept} pieces held, window ${settings.windowMs / 1000} s")
        watch(s, PairwiseVoiceDetector(appContext, settings = settings))
        Log.i(TAG, "listening")
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
        if (scope != null) {
            stopDetector("threshold changed")
            reevaluate()
        }
    }

    /** Publishes a detector's snapshots and turns MULTIPLE_SPEAKERS into the prompt. Shared by all methods. */
    private fun watch(s: CoroutineScope, d: ConversationDetector) {
        detector = d
        var multipleSince: Long? = null
        watchJob = s.launch {
            d.snapshot.collect { snap ->
                _snapshot.value = snap
                if (snap.error != null) Log.w(TAG, "detector: ${snap.error}")
                _status.value = _status.value.copy(state = snap.state)
                if (snap.running && snap.speechSeconds == 0.0) promptedThisConversation = false // new conversation
                if (snap.state == ConversationState.MULTIPLE_SPEAKERS) {
                    val now = SystemClock.elapsedRealtime()
                    val since = multipleSince ?: now.also { multipleSince = it }
                    if (now - since >= MULTIPLE_FOR_MS) maybePrompt()
                } else {
                    multipleSince = null
                }
            }
        }
        d.start()
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
