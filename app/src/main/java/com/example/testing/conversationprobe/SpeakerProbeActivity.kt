package com.example.testing.conversationprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.robocontrol.conversation.ConversationSnapshot
import com.example.robocontrol.conversation.ConversationState
import com.example.robocontrol.conversation.SpeechGate
import com.example.robocontrol.conversation.DecisionRule
import com.example.testing.voiceprobe.ProbeLog
import kotlin.math.max

/**
 * Test screen for speaker change detection ("RG Speaker Test" launcher icon). Not part of RoboGuard's UI.
 * Procedure: README.md, "Testing speaker change detection".
 *
 * Left: controls and ✔/✘ verdicts. Right: the current decision in large letters, live numbers, the KL2 graph and the
 * log. Leaving the screen stops listening. The microphone is not used unless "Start listening" is pressed, and not at
 * all if RoboGuard's privacy settings switch it off.
 */
class SpeakerProbeActivity : ComponentActivity() {

    private lateinit var log: ProbeLog
    private lateinit var probe: SpeakerProbe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = ProbeLog(applicationContext)
        probe = SpeakerProbe(applicationContext, log, lifecycleScope)
        log.i("probe", "log file: ${log.filePath}")
        setContent { MaterialTheme { Screen() } }
    }

    /** Never listen while nobody sees the screen. */
    override fun onStop() {
        probe.stop("screen left")
        super.onStop()
    }

    @Composable
    private fun Screen() {
        val lines by log.lines.collectAsState()
        val snapshot by probe.snapshot.collectAsState()
        val source by probe.source.collectAsState()
        val busy by probe.busy.collectAsState()
        val config by probe.config.collectAsState()

        val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log.i("probe", "RECORD_AUDIO granted=$granted")
            if (granted) probe.startMicrophone()
        }

        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

        Row(Modifier.fillMaxSize().padding(12.dp)) {
            // ---- left: controls
            Column(
                Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { probe.stop("STOP pressed") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("STOP", fontSize = 20.sp) }
                Text(if (source != null) "Running: $source" else "Not running", fontWeight = FontWeight.Bold)
                Button(
                    enabled = !busy,
                    onClick = {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) probe.startMicrophone()
                        else requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start listening (microphone)") }
                OutlinedButton(enabled = !busy, onClick = { probe.runSyntheticSelfTest() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Self-test running…" else "Synthetic self-test (fast)")
                }
                OutlinedButton(enabled = !busy, onClick = { probe.startSyntheticDemo() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Synthetic demo (40 s, live)")
                }

                Spacer(Modifier.height(6.dp))
                Text("Settings (restart listening)", fontWeight = FontWeight.Bold)
                Text("λ (higher = fewer false changes)", fontSize = 13.sp)
                Choice(listOf(1.0, 1.5, 2.0, 3.0), config.bicLambda, { "%.1f".format(it) }) { probe.setLambda(it) }
                Text("Window (speech per block)", fontSize = 13.sp)
                Choice(listOf(100, 150, 250), config.windowFrames, { "%.1f s".format(it / 100.0) }) { probe.setWindowFrames(it) }
                Text("Decision rule (shown)", fontSize = 13.sp)
                Choice(DecisionRule.entries, config.decisionRule, { if (it == DecisionRule.EVIDENCE) "Evidence" else "Count" }) { probe.setDecisionRule(it) }
                Text("Evidence: r₀ (ratio that starts to count)", fontSize = 13.sp)
                Choice(listOf(1.5, 1.65, 1.8, 2.0), config.evidenceBaseRatio, { "%.2f".format(it) }) { probe.setEvidenceBaseRatio(it) }
                Text("Evidence: threshold", fontSize = 13.sp)
                Choice(listOf(0.3, 0.5, 0.8), config.evidenceThreshold, { "%.1f".format(it) }) { probe.setEvidenceThreshold(it) }
                Text("Evidence: half-life", fontSize = 13.sp)
                Choice(listOf(5_000L, 10_000L, 20_000L), config.evidenceHalfLifeMs, { "${it / 1000} s" }) { probe.setEvidenceHalfLife(it) }
                Text("Speech detection", fontSize = 13.sp)
                Choice(SpeechGate.entries, config.speechGate, { if (it == SpeechGate.SILERO) "Silero" else "Loudness" }) { probe.setSpeechGate(it) }
                Text("Silero threshold (higher = stricter)", fontSize = 13.sp)
                Choice(listOf(0.3, 0.5, 0.7), config.sileroThreshold, { "%.1f".format(it) }) { probe.setSileroThreshold(it) }
                Text("Loudness: speech above noise floor", fontSize = 13.sp)
                Choice(listOf(10.0, 15.0, 20.0), config.speechMarginDb, { "%.0f dB".format(it) }) { probe.setSpeechMargin(it) }

                Spacer(Modifier.height(6.dp))
                Text("Verdicts (after each scenario)", fontWeight = FontWeight.Bold)
                SCENARIOS.forEach { scenario -> VerdictRow(scenario) }

                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { log.clearScreen() }, modifier = Modifier.fillMaxWidth()) { Text("Clear log") }
            }

            Spacer(Modifier.width(16.dp))

            // ---- right: decision, numbers, graph, log
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StateBanner(snapshot)
                Numbers(snapshot)
                EvidenceBar(snapshot, config.evidenceThreshold)
                Kl2Graph(snapshot, config.kl2Relative, Modifier.fillMaxWidth().height(120.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        }
    }

    /** A row of option buttons; the current one is filled. */
    @Composable
    private fun <T> Choice(options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val padding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            options.forEach { option ->
                if (option == current) {
                    Button(onClick = { onPick(option) }, contentPadding = padding, modifier = Modifier.weight(1f)) {
                        Text(label(option), maxLines = 1, softWrap = false, fontSize = 13.sp)
                    }
                } else {
                    OutlinedButton(onClick = { onPick(option) }, contentPadding = padding, modifier = Modifier.weight(1f)) {
                        Text(label(option), maxLines = 1, softWrap = false, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun VerdictRow(scenario: String) {
        Column {
            Text(scenario, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val padding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                OutlinedButton(onClick = { probe.verdict(scenario, true) }, contentPadding = padding, modifier = Modifier.weight(1f)) {
                    Text("✔ correct", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { probe.verdict(scenario, false) }, contentPadding = padding, modifier = Modifier.weight(1f)) {
                    Text("✘ wrong", maxLines = 1, softWrap = false)
                }
            }
        }
    }

    @Composable
    private fun StateBanner(s: ConversationSnapshot) {
        val (label, color) = when {
            !s.running -> "not listening" to Color(0xFF9E9E9E)
            s.state == ConversationState.NO_SPEECH -> "no speech" to Color(0xFF607D8B)
            s.state == ConversationState.LISTENING -> "listening…" to Color(0xFF1976D2)
            s.state == ConversationState.ONE_SPEAKER -> "ONE speaker" to Color(0xFF2E7D32)
            else -> "MORE THAN ONE speaker" to Color(0xFFE65100)
        }
        Column(
            Modifier.fillMaxWidth().background(color, RoundedCornerShape(8.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            s.error?.let { Text(it, color = Color.White, fontSize = 13.sp) }
        }
    }

    @Composable
    private fun Numbers(s: ConversationSnapshot) {
        val level = if (s.levelDb.isFinite()) "%.0f dBFS".format(s.levelDb) else "silent"
        val gate = if (s.speechProbability != null) "Silero p=%.2f".format(s.speechProbability) else "loudness gate"
        Text(
            "$gate · Level $level (floor %.0f) ${if (s.speechNow) "SPEECH" else ""} · speech %.1f s · KL2 %.1f (mean %.1f) · changes: %d in window, %d total"
                .format(s.noiseFloorDb, s.speechSeconds, s.lastKl2, s.kl2Mean, s.changesInWindow, s.totalChanges),
            fontSize = 14.sp
        )
    }

    /** Evidence (evidence rule) against its threshold, plus what both rules currently say. */
    @Composable
    private fun EvidenceBar(s: ConversationSnapshot, threshold: Double) {
        fun verdict(multiple: Boolean) = if (multiple) "MULTIPLE" else "one"
        Text(
            "Evidence %.2f / %.1f → ${verdict(s.multipleByEvidence)}   ·   Count rule: ${s.changesInWindow} changes → ${verdict(s.multipleByCount)}   ·   shown: ${s.decisionRule}"
                .format(s.evidence, threshold),
            fontSize = 14.sp
        )
        Canvas(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFFE0E0E0))) {
            val full = (threshold * 2).coerceAtLeast(0.1)
            val w = (size.width * (s.evidence / full)).toFloat().coerceIn(0f, size.width)
            drawRect(if (s.evidence >= threshold) Color(0xFFE65100) else Color(0xFF1976D2), size = size.copy(width = w))
            val tx = size.width / 2
            drawLine(Color.Black, Offset(tx, 0f), Offset(tx, size.height), strokeWidth = 3f)
        }
    }

    /** KL2 over time (blue), candidate threshold (dashed orange), accepted changes as markers along the bottom. */
    @Composable
    private fun Kl2Graph(s: ConversationSnapshot, relative: Double, modifier: Modifier) {
        Canvas(modifier.background(Color(0xFFF2F2F2))) {
            val values = s.kl2History
            if (values.size < 2) return@Canvas
            val threshold = relative * s.kl2Mean
            val top = max(values.max(), threshold) * 1.1
            fun y(v: Double) = (size.height * (1 - v / top)).toFloat()
            val dx = size.width / (values.size - 1)
            for (i in 1 until values.size) {
                drawLine(Color(0xFF1565C0), Offset((i - 1) * dx, y(values[i - 1])), Offset(i * dx, y(values[i])), strokeWidth = 3f)
            }
            drawLine(
                Color(0xFFE65100), Offset(0f, y(threshold)), Offset(size.width, y(threshold)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            )
        }
    }

    private companion object {
        val SCENARIOS = listOf(
            "1 One person talks for 30 s",
            "2 Two people take turns (a few sentences each)",
            "3 Two people talk at the same time",
            "4 Silence / background noise only",
            "5 One person, then a pause, then the same person"
        )
    }
}
