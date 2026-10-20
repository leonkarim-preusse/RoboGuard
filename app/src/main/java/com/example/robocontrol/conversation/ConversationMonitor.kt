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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

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
    private const val KEY_SPLIT = "split_at_changes"
    private const val KEY_DIFFERENT_BELOW_COHORT = "different_voice_below_cohort"

    /** A detector that stopped by itself is started again after 5, 10, 20, then every 60 s (item 35). */
    private val RESTART_BACKOFF_MS = longArrayOf(5_000, 10_000, 20_000, 60_000)

    /** A detector that ran this long counts as healthy again: the next failure starts the backoff from the beginning. */
    private const val HEALTHY_AFTER_MS = 60_000L

    /** A running detector that has not published anything for this long is stuck. */
    private const val STALE_SNAPSHOT_MS = 5_000L

    /** A new detector gets this long to publish its first snapshot (loading models, opening the microphone). */
    private const val START_GRACE_MS = 30_000L

    /** How long NO_SPEECH must last before the test announcement may come again (sentence + tail + hold). */
    private const val TEST_REARM_SILENCE_MS = 5_000L

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
        detector?.debugTimeline = true
    }

    /** The debug screen closed; when no viewer is left the detector stops collecting the timeline. */
    @Synchronized
    fun removeDebugViewer() {
        if (debugViewers.decrementAndGet() <= 0) {
            debugViewers.set(0)
            detector?.debugTimeline = false
        }
    }

    /** True while the speaker debug screen is open (the test announcement and the prompt look at it). */
    val debugViewerOpen: Boolean get() = debugViewers.get() > 0

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null

    /**
     * Every start and stop of a detector runs on this ONE thread, in order. Before, the restart, the health check, the
     * watchdog and the listeners each stopped and started from their own thread, and a start in the middle of a stop could
     * leave a detector without its collector — recording, but silently ignored (review 2026-10-02, item 30).
     */
    private var lifecycle: kotlinx.coroutines.ExecutorCoroutineDispatcher? = null

    /** The running detector and the coroutine that reads its snapshots, as one pair. */
    private class Running(val detector: ConversationDetector, val startedAt: Long) {
        var job: Job? = null
    }

    @Volatile
    private var running: Running? = null
    private val detector: ConversationDetector? get() = running?.detector

    @Volatile
    private var lastSnapshotAt = 0L
    private var pauseJob: Job? = null
    private var tts: OrionStarTts? = null

    private var pausedUntil: Long? = null
    private var promptOpen = false

    /** Who holds the microphone instead of the monitor (enrolment, test screens); it listens only while this is empty. */
    private val micLeases = mutableSetOf<MicLease>()

    /** A lease on the microphone: while it is held, the monitor does not listen. [close] gives it back (idempotent). */
    class MicLease internal constructor(val tag: String) : AutoCloseable {
        override fun close() = releaseMicrophone(this)
    }

    /** Self-stops in a row, for the backoff. */
    private var failures = 0
    private var startNotBefore = 0L
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
        lifecycle = Executors.newSingleThreadExecutor { Thread(it, "ConversationLifecycle").apply { isDaemon = true } }
            .asCoroutineDispatcher()
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
        // On by default since 2026-10-19: with a per-voice unclear band it gave no false episode in the replay (item 10).
        _confirmTwice.value = prefs.getBoolean(KEY_CONFIRM_TWICE, true)
        _continuousPieces.value = prefs.getBoolean(KEY_CONTINUOUS, ConversationSettings().continuousPieces)
        _splitAtChanges.value = prefs.getBoolean(KEY_SPLIT, ConversationSettings().splitAtChanges)
        Sensors.get(appContext).addListener(sensorListener)
        Sensors.get(appContext).addSituationalListener(situationalListener)
        _status.value = _status.value.copy(started = true)
        Log.i(TAG, "started")
        reevaluate()
        scope?.let { startHealthCheck(it) }
        scope?.launch {
            // A voice stored, deleted or switched, its threshold changed, or the cohort replaced: the running detector loaded
            // them at its start, so it has to start again to use the new ones (review 2026-10-02, item 6).
            var seen = OwnerVoiceprintStore.get(appContext).generation.value
            OwnerVoiceprintStore.get(appContext).generation.collect { g ->
                if (g == seen) return@collect
                seen = g
                if (_method.value == DetectionMethod.OWNER || _method.value == DetectionMethod.PAIRWISE) {
                    restartDetector("stored voice or cohort changed")
                }
            }
        }
    }

    /** Stops everything. Call from RobotServerService.onDestroy. */
    @Synchronized
    fun stop() {
        if (scope == null) return
        runCatching { Sensors.get(appContext).removeListener(sensorListener) }
        runCatching { Sensors.get(appContext).removeSituationalListener(situationalListener) }
        val l = lifecycle
        scope?.cancel()
        scope = null
        lifecycle = null
        // The last stop on the lifecycle thread, so it cannot overlap a start that is still running there.
        runCatching { l?.let { d -> runBlocking(d) { stopDetector("service stopped") } } }
        runCatching { l?.close() }
        runCatching { tts?.disconnect() }
        tts = null
        _status.value = Status()
    }

    /** Runs [block] on the lifecycle thread (see [lifecycle]). */
    private fun post(block: () -> Unit): Job? {
        val s = scope ?: return null
        val l = lifecycle ?: return null
        return s.launch(l) { block() }
    }

    /** Pauses the detection for [minutes] (from the prompt). */
    @Synchronized
    fun pauseFor(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        pausedUntil = until
        // The chosen pause replaces the 2-minute gap between prompts: "don't ask again for 1 min" means the robot may ask
        // again after 1 min, not after 2 (owner, 2026-10-19).
        lastPromptAt = Long.MIN_VALUE / 2
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

    /**
     * Takes the microphone away from the monitor and WAITS until its detector has really let go of it, so two
     * AudioRecords never run at once (on Android 9 the second one gets silence, not an error). Every holder gets its own
     * lease and gives back only its own, so one user releasing it can no longer hand it to the monitor while another one
     * still records (item 31). Call from a background thread: it blocks for up to a few seconds.
     */
    fun acquireMicrophone(tag: String): MicLease {
        val lease = MicLease(tag)
        synchronized(micLeases) { micLeases += lease }
        Log.i(TAG, "microphone taken by $tag")
        runCatching { runBlocking { withTimeoutOrNull(4_000) { post { reevaluateNow() }?.join() } } }
        return lease
    }

    /** Takes a microphone lease back; when the last one is gone the monitor may listen again. */
    private fun releaseMicrophone(lease: MicLease) {
        val removed = synchronized(micLeases) { micLeases.remove(lease) }
        if (removed) {
            Log.i(TAG, "microphone given back by ${lease.tag}")
            reevaluate()
        }
    }

    /** The speaker test screen's lease, for [setProbeUsingMicrophone]. */
    private var probeLease: MicLease? = null

    /** Test screens that record themselves call this, so two recordings do not compete for the microphone. */
    fun setProbeUsingMicrophone(active: Boolean) {
        if (active) {
            if (probeLease != null) return
            val lease = MicLease("speaker test")
            synchronized(micLeases) { micLeases += lease }
            probeLease = lease
            reevaluate()
        } else {
            probeLease?.close()
            probeLease = null
        }
    }

    /** elapsedRealtime of the last cooldown reset: a detector started before it may not open a prompt any more. */
    @Volatile
    private var cooldownResetAt = 0L

    /**
     * The speaker screen's test button: forgets everything that would hold the next prompt back and starts the detector
     * over — the "already asked in this conversation" flag, the two minutes between prompts, a running "don't ask again"
     * pause, the once-per-episode test announcement, and the detector's own window of speech. Everything heard from this
     * moment counts as a new conversation, so the prompt can come again straight away.
     *
     * Fixed 2026-10-19: the pause used to stay (the button then seemed to do nothing), and the old detector, which still ran
     * for the restart's debounce, could open a prompt from speech heard BEFORE the reset.
     */
    fun resetCooldown() {
        synchronized(this) {
            promptOpen = false
            promptedThisConversation = false
            lastPromptAt = Long.MIN_VALUE / 2
            announcedThisEpisode = false
            cooldownResetAt = SystemClock.elapsedRealtime()
            pausedUntil = null
            pauseJob?.cancel()
        }
        Log.i(TAG, "detection cooldown reset (pause lifted): everything from now counts as a new conversation")
        // At once, not after the debounce: the old detector must not decide anything any more.
        post {
            stopDetector("cooldown reset")
            reevaluateNow()
        }
    }

    /** Called by the prompt when it closes. */
    fun promptClosed() {
        promptOpen = false
    }

    /** Starts or stops listening according to the current conditions, on the lifecycle thread. */
    private fun reevaluate() {
        post { reevaluateNow() }
    }

    /**
     * Checks every condition (Discretion Mode, microphone setting, permission, pause, leases, stored voice) and starts
     * or stops the detector accordingly. Runs on the monitor's lifecycle thread.
     */
    private fun reevaluateNow() {
        if (scope == null) return
        val micAllowed = Sensors.get(appContext).getSensors().entries
            .firstOrNull { it.key.equals("microphone", ignoreCase = true) }?.value != false
        val permission = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val paused = pausedUntil?.let { it > System.currentTimeMillis() } == true
        // Owner, 2026-09-21: conversation detection is a feature of Discretion Mode. Off (or never sent by the phone)
        // means the robot does not listen for conversations — WITHOUT switching the microphone itself off; that stays
        // the sensor setting's job, so speech recognition and the other users of the microphone are untouched.
        // On unless the phone switched it off (owner 2026-10-19: features are on by default).
        val discretion = Sensors.get(appContext).isSituationalEnabled(DISCRETION_MODE) != false
        val leaseHolder = synchronized(micLeases) { micLeases.firstOrNull()?.tag }
        val waitMs = startNotBefore - SystemClock.elapsedRealtime()
        var reason = when {
            !discretion -> "Discretion Mode is off in the privacy settings"
            !micAllowed -> "microphone switched off in the privacy settings"
            !permission -> "RECORD_AUDIO not granted"
            paused -> "paused"
            leaseHolder != null -> "the microphone is in use ($leaseHolder)"
            else -> null
        }
        if (reason == null && running == null && waitMs > 0) {
            // A stopped detector did not let go of the microphone in time, or the last one failed: start a little later.
            scope?.launch { delay(waitMs); reevaluate() }
            reason = "waiting ${(waitMs + 999) / 1000} s before starting again"
        } else if (reason == null) {
            // A condition that will not go away by itself (no voice stored, a threshold for another cohort) is a reason,
            // not a failure: the health check must not restart it every 10 s (item 81).
            reason = startDetector()
        } else {
            stopDetector(reason)
        }
        if (reason != lastReason) {
            lastReason = reason
            Log.i(TAG, reason?.let { "not listening: $it" } ?: "conditions met, listening")
        }
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

    /**
     * Owner method: true = one other voice is already a conversation, false = the owner must have been heard too. Saved; restarts the detector.
     */
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

    /** Starts the chosen detector; returns why it cannot, or null. */
    private fun startDetector(): String? {
        if (detector != null) return null
        val s = scope ?: return "not started"
        when (_method.value) {
            DetectionMethod.OWNER -> return startOwnerDetector(s)
            DetectionMethod.PAIRWISE -> return startPairwiseDetector(s)
            DetectionMethod.ACTIVITY -> { startActivityDetector(s); return null }
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
            robotSpeaking = { t -> robotSpeaking.active(t) }
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
        return null
    }

    /**
     * Starts the owner comparison instead of the change detector. Needs a stored voice; without one it says so and
     * nothing listens, rather than silently falling back to the other method.
     */
    private fun startOwnerDetector(s: CoroutineScope): String? {
        // Missing, unreadable, or set for another cohort: say so instead of starting a detector that fails every 5 s
        // or decides on the wrong scale (items 11 and 81).
        OwnerVoiceprintStore.get(appContext).problem()?.let {
            Log.w(TAG, "owner comparison not started: $it")
            return it
        }
        val any = _anyOtherIsConversation.value
        // "Any other voice" reacts to the first piece that is not the owner — but one stray piece must not be enough on
        // its own, so without the outlier filter two pieces are needed there too (item 10).
        val settings = audioSettings().copy(
            anyOtherIsConversation = any,
            piecesForOther = if (any && _confirmTwice.value) 1 else ConversationSettings().piecesForOther,
            confirmTwice = _confirmTwice.value
        )
        Log.i(TAG, "config: owner comparison, piece ${settings.pieceSeconds} s, " +
            "${settings.piecesForOther} pieces, window ${settings.windowMs / 1000} s, " +
            (if (settings.anyOtherIsConversation) "any other voice counts" else "owner + other") +
            ", confirm twice ${settings.confirmTwice}, pieces ${settings.assemblyText()}")
        watch(s, OwnerVoiceDetector(appContext, settings = settings))
        Log.i(TAG, "listening")
        return null
    }

    /**
     * Experimental: compares the pieces of speech with each other, so no voice has to be taught. Needs no template; a
     * recorded cohort is used only to centre the comparison.
     */
    private fun startPairwiseDetector(s: CoroutineScope): String? {
        // The pairwise threshold is on the scale of the cohort it was set with, like the owner's (item 11).
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_DIFFERENT_BELOW_COHORT) &&
            prefs.getLong(KEY_DIFFERENT_BELOW_COHORT, -1) != OwnerVoiceprintStore.get(appContext).currentCohortId()) {
            val reason = "the pairwise threshold was set for a different cohort: set it again"
            Log.w(TAG, "compare voices not started: $reason")
            return reason
        }
        val settings = audioSettings().copy(
            differentVoiceBelow = _differentBelow.value,
            confirmTwice = _confirmTwice.value
        )
        Log.i(TAG, "config: comparing voices with each other, piece ${settings.pieceSeconds} s, " +
            "different below ${settings.differentVoiceBelow}, ${settings.piecesKept} pieces held, " +
            "window ${settings.windowMs / 1000} s, confirm twice ${settings.confirmTwice}")
        watch(s, PairwiseVoiceDetector(appContext, settings = settings))
        Log.i(TAG, "listening")
        return null
    }

    /**
     * Which gate decides that a frame is speech (see [SpeechGateMode]). Applies to the owner and the compare methods; the
     * change detector has its own adaptive loudness floor, so there only "level only" reaches it, as its LOUDNESS gate.
     */
    private val _gateMode = MutableStateFlow(SpeechGateMode.SILERO_AND_LEVEL)
    val gateMode: StateFlow<SpeechGateMode> = _gateMode.asStateFlow()

    /** Chooses the speech gate (Silero and level, Silero only, level only). Saved; restarts the detector. */
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

    /** Silero probability at which speech starts (0.05–0.95). Saved; restarts the detector. */
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

    /** How far below the threshold Silero may drop before speech ends. Saved; restarts the detector. */
    fun setSpeechHysteresis(value: Float) {
        // Below the threshold, so speech can always end again (item 78).
        val clamped = value.coerceIn(0f, minOf(0.4f, _speechThreshold.value / 2))
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

    /** A verdict that changes must be repeated by the next piece before it counts. Saved; restarts the detector. */
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

    /**
     * Cut pieces as one continuous stretch (with pre-roll and short gaps kept) instead of gated frames only. Saved; restarts the detector.
     */
    fun setContinuousPieces(on: Boolean) {
        if (_continuousPieces.value == on) return
        _continuousPieces.value = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONTINUOUS, on).apply()
        Log.i(TAG, "continuous pieces (padding + bridged gaps): $on")
        restartDetector("piece assembly changed")
    }

    /** Cut pieces at the voice changes found in the same audio (see [ConversationSettings.splitAtChanges]). */
    private val _splitAtChanges = MutableStateFlow(ConversationSettings().splitAtChanges)
    val splitAtChanges: StateFlow<Boolean> = _splitAtChanges.asStateFlow()

    /** Cut pieces at the voice changes found in the same audio. Saved; restarts the detector. */
    fun setSplitAtChanges(on: Boolean) {
        if (_splitAtChanges.value == on) return
        _splitAtChanges.value = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPLIT, on).apply()
        Log.i(TAG, "split pieces at voice changes: $on")
        restartDetector("piece assembly changed")
    }

    /** Activity method: how long a gap still counts as speech. */
    private val _activityMinSilenceMs = MutableStateFlow(ConversationSettings().activityMinSilenceMs)
    val activityMinSilenceMs: StateFlow<Long> = _activityMinSilenceMs.asStateFlow()

    /** Activity method: gaps shorter than this still count as speech (0–2000 ms). Saved; restarts the detector. */
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

    /** Loudness floor of the speech gate (−90…−20 dBFS). Saved; restarts the detector. */
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

    /** Whether the robot says "Conversation detected" while the speaker debug screen is open. */
    fun setAnnounceInTest(on: Boolean) {
        if (_announceInTest.value == on) return
        _announceInTest.value = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANNOUNCE_TEST, on).apply()
        Log.i(TAG, "say \"conversation detected\" on the speaker screen: $on")
    }

    /** The test announcement is said once per episode of "more than one speaker" (item 45). */
    private var announcedThisEpisode = false
    private var noSpeechSince = 0L

    private var restartJob: Job? = null
    private var healthJob: Job? = null

    /**
     * Speaks on the speaker screen, and only there: once per episode of "more than one speaker". It used to repeat every
     * 5 s while the state held, and each sentence blanked the microphone for ~2 s, so the owner and pairwise methods could
     * never finish a piece while it was talking (item 45). It comes again after the detector has said "one speaker" or
     * "listening", or after a real silence — a plain edge would re-fire on the flicker the sentence itself causes.
     */
    private fun maybeAnnounceInTest(snap: ConversationSnapshot) {
        when (snap.state) {
            ConversationState.ONE_SPEAKER, ConversationState.LISTENING -> { announcedThisEpisode = false; noSpeechSince = 0L }
            ConversationState.NO_SPEECH -> {
                val now = SystemClock.elapsedRealtime()
                if (noSpeechSince == 0L) noSpeechSince = now
                else if (now - noSpeechSince > TEST_REARM_SILENCE_MS) announcedThisEpisode = false
            }
            ConversationState.MULTIPLE_SPEAKERS -> {
                noSpeechSince = 0L
                if (!_announceInTest.value || debugViewers.get() <= 0 || announcedThisEpisode || promptOpen) return
                announcedThisEpisode = true
                Log.i(TAG, "speaker screen: saying \"conversation detected\"")
                scope?.launch { speak(UiText.get("speech.conversation_detected")) }
            }
        }
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

    /** Activity method: share of speech in the window that counts as a conversation (0.3–1.0). Saved; restarts the detector. */
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

    /** Activity method: length of the window (3–60 s). Saved; restarts the detector. */
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
        val store = OwnerVoiceprintStore.get(appContext)
        val error = store.setThreshold(value)
        if (error != null) {
            Log.w(TAG, "owner threshold not saved: $error")
            return
        }
        // The store clamps the value and saves only a change; a saved change restarts the detector through its
        // generation counter, so nothing to do here but log what was really applied (item 81).
        Log.i(TAG, "owner threshold %.2f".format(store.info.value?.threshold ?: value))
    }

    /** Owner method: the unclear band below the threshold, stored with the voice (see [VoiceprintInfo.margin]). */
    fun setOwnerMargin(value: Float) {
        val store = OwnerVoiceprintStore.get(appContext)
        val error = store.setMargin(value)
        if (error != null) Log.w(TAG, "owner margin not saved: $error")
        else Log.i(TAG, "owner margin %.2f".format(store.info.value?.margin ?: value))
    }

    /** Pairwise method: similarity below which two pieces count as two voices. Measured on the robot, so adjustable. */
    private val _differentBelow = MutableStateFlow(ConversationSettings().differentVoiceBelow)
    val differentBelow: StateFlow<Float> = _differentBelow.asStateFlow()

    /** Pairwise method: two pieces below this similarity are two different voices. Saved; restarts the detector. */
    fun setDifferentBelow(value: Float) {
        val clamped = value.coerceIn(-0.5f, 0.95f)
        if (_differentBelow.value == clamped) return
        _differentBelow.value = clamped
        // Remember which cohort this was set against (item 11).
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_DIFFERENT_BELOW, clamped)
            .putLong(KEY_DIFFERENT_BELOW_COHORT, OwnerVoiceprintStore.get(appContext).currentCohortId()).apply()
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
        continuousPieces = _continuousPieces.value,
        splitAtChanges = _splitAtChanges.value
    )

    /**
     * Publishes a detector's snapshots and turns MULTIPLE_SPEAKERS into the prompt. Shared by all methods. Runs on the
     * lifecycle thread; the collector writes only while its detector is still the current one.
     */
    private fun watch(s: CoroutineScope, d: ConversationDetector) {
        d.debugTimeline = debugViewers.get() > 0
        val me = Running(d, SystemClock.elapsedRealtime())
        running = me
        lastSnapshotAt = 0L
        var multipleSince: Long? = null
        me.job = s.launch {
            d.snapshot.collect { snap ->
                if (running !== me) return@collect
                lastSnapshotAt = SystemClock.elapsedRealtime()
                _snapshot.value = snap
                if (snap.error != null && !snap.running) Log.w(TAG, "detector: ${snap.error}")
                // A detector that ends on its own (the microphone could not be opened, the model failed to load, an
                // exception in its thread) is given back and started again later — with a growing pause, so a
                // permanent failure does not reload the models every 5 s (item 35).
                if (!snap.running && snap.error != null) {
                    val delayMs = RESTART_BACKOFF_MS[minOf(failures, RESTART_BACKOFF_MS.size - 1)]
                    failures++
                    Log.w(TAG, "detector stopped by itself (${snap.error}); trying again in ${delayMs / 1000} s")
                    post {
                        if (running === me) {
                            stopDetector("detector had stopped by itself")
                            startNotBefore = SystemClock.elapsedRealtime() + delayMs
                            reevaluateNow()
                        }
                    }
                    return@collect
                }
                if (snap.running && SystemClock.elapsedRealtime() - me.startedAt > HEALTHY_AFTER_MS) failures = 0
                _status.value = _status.value.copy(state = snap.state)
                if (snap.running && snap.speechSeconds == 0.0) promptedThisConversation = false // new conversation
                if (snap.state == ConversationState.MULTIPLE_SPEAKERS) {
                    val now = SystemClock.elapsedRealtime()
                    val since = multipleSince ?: now.also { multipleSince = it }
                    // The prompt first; on the snapshot that opens it, the test sentence stays quiet, so the two
                    // sentences do not cut each other off.
                    // A detector from before a cooldown reset still carries speech from before it: it may not ask.
                    val prompted = me.startedAt >= cooldownResetAt && now - since >= MULTIPLE_FOR_MS && maybePrompt()
                    if (!prompted) maybeAnnounceInTest(snap)
                } else {
                    multipleSince = null
                    maybeAnnounceInTest(snap)
                }
            }
        }
        d.start()
    }

    /**
     * Stops the detector and starts it again, on the lifecycle thread.
     *
     * Every setting on the speaker screen ends here. Several switches flipped quickly become one restart (debounce), and
     * the stop and the start run in order on that one thread, so the next detector never opens the microphone while the
     * previous one still holds it (owner, 2026-09-28).
     */
    private fun restartDetector(reason: String) {
        val s = scope ?: return
        restartJob?.cancel()
        restartJob = s.launch {
            delay(RESTART_DEBOUNCE_MS)
            post {
                stopDetector(reason)
                reevaluateNow()
            }
        }
    }

    /**
     * Checks every [HEALTH_EVERY_MS] that something is actually listening: the conditions are met but no detector
     * exists, the detector reports "not running", or it has not published anything for [STALE_SNAPSHOT_MS] — then start
     * it again. A detector that is still starting gets [START_GRACE_MS].
     */
    private fun startHealthCheck(s: CoroutineScope) {
        healthJob?.cancel()
        healthJob = s.launch {
            while (isActive) {
                delay(HEALTH_EVERY_MS)
                post {
                    val now = SystemClock.elapsedRealtime()
                    val shouldListen = _status.value.reason == null && _status.value.started
                    val r = running
                    val alive = r != null && if (lastSnapshotAt == 0L) now - r.startedAt < START_GRACE_MS
                    else _snapshot.value.running && now - lastSnapshotAt < STALE_SNAPSHOT_MS
                    if (shouldListen && !alive) {
                        Log.w(TAG, "nothing is listening although it should be; starting the detector again")
                        stopDetector("health check")
                        reevaluateNow()
                    }
                }
            }
        }
    }

    /** Lifecycle thread only. */
    private fun stopDetector(reason: String) {
        val r = running ?: return
        running = null
        _snapshot.value = ConversationSnapshot()
        val ended = r.detector.stop()
        r.job?.cancel()
        if (!ended) {
            // Its thread still holds the microphone (an embedding that has not returned yet): do not start the next one
            // on top of it.
            Log.w(TAG, "the stopped detector has not let go of the microphone yet; waiting before the next start")
            startNotBefore = SystemClock.elapsedRealtime() + 3_000
        }
        Log.i(TAG, "not listening: $reason")
    }

    /** Opens the prompt if it may; returns whether it did. */
    private fun maybePrompt(): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (promptOpen || promptedThisConversation || now - lastPromptAt < MIN_PROMPT_GAP_MS) return false
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
        return true
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
