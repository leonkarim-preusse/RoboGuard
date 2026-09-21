package com.example.testing.conversationprobe

import android.content.Context
import com.example.robocontrol.conversation.ChangeCandidate
import com.example.robocontrol.conversation.ChangeDetectorConfig
import com.example.robocontrol.conversation.ConversationSnapshot
import com.example.robocontrol.conversation.ConversationState
import com.example.robocontrol.conversation.AndroidMicSource
import com.example.robocontrol.conversation.PcmSource
import com.example.robocontrol.conversation.SpeakerChangeDetector
import com.example.robocontrol.conversation.SyntheticVoicesSource
import com.example.robocontrol.conversation.SyntheticVoicesSource.Segment
import com.example.robocontrol.conversation.SileroVad
import com.example.robocontrol.conversation.SpeechGate
import com.example.robocontrol.conversation.DecisionRule
import com.example.robocontrol.conversation.ConversationMonitor
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.testing.voiceprobe.ProbeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Hardware test for speaker change detection (`robocontrol.conversation`): runs [SpeakerChangeDetector] on the robot's
 * microphone or on synthetic voices and logs what it decides, so thresholds can be tuned and the tester can judge the
 * result (✔/✘ per scenario).
 *
 * The log file gets numbers only (levels, distances, times, states): no audio and no features, like the detector itself.
 */
class SpeakerProbe(private val context: Context, private val log: ProbeLog, private val scope: CoroutineScope) {

    private var detector: SpeakerChangeDetector? = null
    private var watchJob: Job? = null

    private val _snapshot = MutableStateFlow(ConversationSnapshot())
    val snapshot: StateFlow<ConversationSnapshot> = _snapshot.asStateFlow()

    /** "microphone" or "synthetic demo" while running, else null. */
    private val _source = MutableStateFlow<String?>(null)
    val source: StateFlow<String?> = _source.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** True while the fast synthetic self-test runs. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _config = MutableStateFlow(ChangeDetectorConfig())

    /** Settings used by the next start; changed from the screen. Changing them while listening restarts listening. */
    val config: StateFlow<ChangeDetectorConfig> = _config.asStateFlow()

    fun setLambda(lambda: Double) = changeConfig("λ", lambda.toString()) { it.copy(bicLambda = lambda) }

    fun setWindowFrames(frames: Int) = changeConfig("window", "%.1f s".format(frames / 100.0)) { it.copy(windowFrames = frames) }

    fun setSpeechMargin(db: Double) = changeConfig("speech margin", "$db dB") { it.copy(speechMarginDb = db) }

    fun setDecisionRule(rule: DecisionRule) = changeConfig("decision rule", rule.name) { it.copy(decisionRule = rule) }

    fun setEvidenceBaseRatio(r: Double) = changeConfig("evidence r₀", r.toString()) { it.copy(evidenceBaseRatio = r) }

    fun setEvidenceThreshold(t: Double) = changeConfig("evidence threshold", t.toString()) { it.copy(evidenceThreshold = t) }

    fun setEvidenceHalfLife(ms: Long) = changeConfig("evidence half-life", "${ms / 1000} s") { it.copy(evidenceHalfLifeMs = ms) }

    fun setSpeechGate(gate: SpeechGate) = changeConfig("speech gate", gate.name) { it.copy(speechGate = gate) }

    fun setSileroThreshold(t: Double) = changeConfig("Silero threshold", t.toString()) { it.copy(sileroThreshold = t) }

    private fun changeConfig(name: String, value: String, change: (ChangeDetectorConfig) -> ChangeDetectorConfig) {
        _config.value = change(_config.value)
        log.i(TAG, "setting: $name = $value")
        when (_source.value) {
            "microphone" -> startMicrophone()
            "synthetic demo" -> startSyntheticDemo()
        }
    }

    /**
     * Starts listening on the robot's microphone (AudioRecord, source CAMCORDER).
     * Refused if the privacy settings switch the microphone off: this test must respect them like the product would.
     */
    fun startMicrophone() {
        val micSetting = Sensors.get(context).getSensors().entries.firstOrNull { it.key.equals("microphone", ignoreCase = true) }?.value
        if (micSetting == false) {
            log.i(TAG, "NOT STARTED: the microphone is switched off in RoboGuard's privacy settings")
            return
        }
        start("microphone", AndroidMicSource(), _config.value)
    }

    /** Plays a synthetic scene in real time so the screen can be watched: 12 s one voice, 4 s pause, 24 s two voices alternating. */
    fun startSyntheticDemo() {
        val script = listOf(Segment(0, 12_000), Segment(null, 4_000)) + (0 until 8).map { Segment(it % 2, 3_000) }
        // Synthetic voices are a buzz, not speech: Silero would reject them, so the demo tests change detection with loudness.
        start("synthetic demo", SyntheticVoicesSource(script, realtime = true), _config.value.copy(speechGate = SpeechGate.LOUDNESS))
    }

    private fun start(name: String, pcm: PcmSource, config: ChangeDetectorConfig) {
        stop("restart")
        // The background monitor would compete for the microphone: pause it while this test records.
        if (pcm is AndroidMicSource) ConversationMonitor.setProbeUsingMicrophone(true)
        log.section("Speaker change detection: $name")
        log.i(TAG, "speech gate: ${config.speechGate}" + if (config.speechGate == SpeechGate.SILERO) " (threshold ${config.sileroThreshold})" else " (margin ${config.speechMarginDb} dB)")
        log.i(TAG, "decision: ${config.decisionRule} shown; evidence r₀=${config.evidenceBaseRatio}, threshold ${config.evidenceThreshold}, " +
            "half-life ${config.evidenceHalfLifeMs / 1000} s; count ≥${config.minChangesForMultiple}")
        log.i(TAG, "config: window ${config.windowFrames} frames (%.1f s speech), step ${config.stepFrames}, λ=${config.bicLambda}, KL2 ≥ ${config.kl2Relative}×mean, "
            .format(config.windowFrames / 100.0) + "multiple speakers = ≥${config.minChangesForMultiple} changes in ${config.decisionWindowMs / 1000} s")
        val d = SpeakerChangeDetector(pcm, config, sileroFactory = { SileroVad(context) }) { logCandidate(it) }
        detector = d
        _source.value = name
        var lastState: ConversationState? = null
        var lastError: String? = null
        var lastByCount = false
        var lastByEvidence = false
        watchJob = scope.launch(Dispatchers.Default) {
            d.snapshot.collect { s ->
                _snapshot.value = s
                if (s.error != null && s.error != lastError) {
                    lastError = s.error
                    log.i(TAG, "ERROR: ${s.error}")
                }
                // Both rules are logged whenever their verdict flips, so they can be compared afterwards.
                if (s.running && s.multipleByCount != lastByCount) {
                    lastByCount = s.multipleByCount
                    log.i(TAG, "COUNT rule → ${if (s.multipleByCount) "MULTIPLE" else "not multiple"} at %.1f s (changes ${s.changesInWindow})".format(s.timeMs / 1000.0))
                }
                if (s.running && s.multipleByEvidence != lastByEvidence) {
                    lastByEvidence = s.multipleByEvidence
                    log.i(TAG, "EVIDENCE rule → ${if (s.multipleByEvidence) "MULTIPLE" else "not multiple"} at %.1f s (evidence %.2f)".format(s.timeMs / 1000.0, s.evidence))
                }
                if (s.running && s.state != lastState) {
                    lastState = s.state
                    log.i(TAG, "state → ${s.state} at %.1f s (speech %.1f s, changes in window ${s.changesInWindow})".format(s.timeMs / 1000.0, s.speechSeconds))
                }
                if (!s.running && _source.value == name && detector === d) {
                    log.i(TAG, "stopped after %.1f s: ${s.totalChanges} changes accepted".format(s.timeMs / 1000.0))
                    _source.value = null
                }
            }
        }
        d.start()
    }

    fun stop(reason: String) {
        ConversationMonitor.setProbeUsingMicrophone(false)
        val d = detector ?: return
        detector = null
        d.stop()
        watchJob?.cancel()
        watchJob = null
        if (_source.value != null) log.i(TAG, "stopped ($reason)")
        _source.value = null
        _snapshot.value = _snapshot.value.copy(running = false)
    }

    private fun logCandidate(c: ChangeCandidate) {
        log.i(TAG, "  candidate at %.2f s: KL2 %.1f, ΔBIC %.0f (gain %.0f vs penalty %.0f × λ; would need λ < %.2f) → %s".format(
            c.timeMs / 1000.0, c.kl2, c.deltaBic, c.bicGain, c.bicPenalty, if (c.bicPenalty > 0) c.bicGain / c.bicPenalty else 0.0,
            when { c.countsAsChange -> "CHANGE"; c.accepted -> "accepted, same group"; else -> "rejected" }) +
            " · r=%.2f, evidence +%.2f%s".format(c.ratio, c.evidenceAdded(_config.value.evidenceBaseRatio),
                if (c.groupBestRatioBefore > 0) " (same group, best before %.2f)".format(c.groupBestRatioBefore) else ""))
    }

    /**
     * Runs the detector as fast as possible on four synthetic scenes with known truth and logs PASS/FAIL. Checks the whole
     * pipeline (MFCC → KL2 → ΔBIC → decision) on the robot's CPU without anyone talking.
     */
    fun runSyntheticSelfTest() {
        if (_busy.value) return
        stop("self-test")
        _busy.value = true
        scope.launch(Dispatchers.Default) {
            try {
                log.section("Synthetic self-test (λ ${_config.value.bicLambda}, window %.1f s)".format(_config.value.windowFrames / 100.0))
                val results = listOf(
                    scene("one voice (A), 30 s", listOf(Segment(0, 30_000)), expectMultiple = false),
                    scene("one voice (B), 30 s", listOf(Segment(1, 30_000)), expectMultiple = false),
                    scene("A/B alternating every 4 s", (0 until 8).map { Segment(it % 2, 4_000) }, expectMultiple = true),
                    scene("A/B alternating with pauses", (0 until 8).flatMap { listOf(Segment(it % 2, 4_000), Segment(null, 700)) }, expectMultiple = true)
                )
                log.i(TAG, "self-test: ${results.count { it }}/${results.size} scenes PASS")
            } finally {
                _busy.value = false
            }
        }
    }

    private fun scene(name: String, script: List<Segment>, expectMultiple: Boolean): Boolean {
        // Synthetic voices are not real speech (Silero rejects them): the self-test checks change detection with loudness.
        val config = _config.value.copy(speechGate = SpeechGate.LOUDNESS)
        val source = SyntheticVoicesSource(script, realtime = false)
        val accepted = mutableListOf<ChangeCandidate>()
        val d = SpeakerChangeDetector(source, config) { if (it.countsAsChange) accepted += it }
        val started = System.nanoTime()
        d.runBlocking()
        val ms = (System.nanoTime() - started) / 1_000_000
        val expected = source.expectedChangesMs
        val hits = expected.count { e -> accepted.any { abs(it.timeMs - e) <= TOLERANCE_MS } }
        val falseAlarms = accepted.count { a -> expected.none { abs(a.timeMs - it) <= TOLERANCE_MS } }
        val final = d.snapshot.value
        val sawMultiple = accepted.size >= config.minChangesForMultiple
        val pass = if (expectMultiple) sawMultiple && hits >= expected.size / 2 else accepted.isEmpty()
        log.i(TAG, "${if (pass) "PASS" else "FAIL"} $name: expected changes ${expected.size}, found $hits (±${TOLERANCE_MS} ms), " +
            "false $falseAlarms, KL2 mean %.1f, analysed %.0f s of audio in $ms ms".format(final.kl2Mean, script.sumOf { it.durationMs } / 1000.0))
        return pass
    }

    /** Tester's judgement of what the screen showed for [scenario]. */
    fun verdict(scenario: String, correct: Boolean) {
        val s = _snapshot.value
        log.i(TAG, "VERDICT ${if (correct) "✔ correct" else "✘ wrong"}: $scenario (screen showed ${s.state}, changes in window ${s.changesInWindow}, total ${s.totalChanges})")
    }

    private companion object {
        const val TAG = "speakers"
        const val TOLERANCE_MS = 1_000L
    }
}
