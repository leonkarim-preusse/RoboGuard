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
     * "More than one speaker" must last this long before the robot reacts. Owner first chose 2 s; shortened to 1 s
     * (owner, 2026-09-17) because the evidence rule already needs several changes and the hold mostly added latency.
     */
    const val MULTIPLE_FOR_MS = 1_000L

    /** Minimum time between two prompts. */
    const val MIN_PROMPT_GAP_MS = 2 * 60_000L

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

    @Synchronized
    fun removeDebugViewer() {
        if (debugViewers.decrementAndGet() <= 0) {
            debugViewers.set(0)
            detector?.debugTimeline = false
        }
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null
    private var detector: SpeakerChangeDetector? = null
    private var watchJob: Job? = null
    private var pauseJob: Job? = null
    private var tts: OrionStarTts? = null

    private var pausedUntil: Long? = null
    private var probeUsingMicrophone = false
    private var promptOpen = false
    private var promptedThisConversation = false
    private var lastPromptAt = Long.MIN_VALUE / 2

    private val sensorListener = SensorChangeListener { name, _ ->
        if (name.equals("microphone", ignoreCase = true)) reevaluate()
    }

    /** Starts monitoring (idempotent). Call from RobotServerService.onCreate. */
    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Sensors.get(appContext).addListener(sensorListener)
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
        val reason = when {
            !micAllowed -> "microphone switched off in the privacy settings"
            !permission -> "RECORD_AUDIO not granted"
            paused -> "paused"
            probeUsingMicrophone -> "a test screen is using the microphone"
            else -> null
        }
        if (reason == null) startDetector() else stopDetector(reason)
        _status.value = _status.value.copy(listening = detector != null, reason = reason, pausedUntil = if (paused) pausedUntil else null)
    }

    private fun startDetector() {
        if (detector != null) return
        val s = scope ?: return
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
        Log.i(TAG, "listening")
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
        Log.i(TAG, "more than one speaker for ${MULTIPLE_FOR_MS} ms: showing prompt")
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
