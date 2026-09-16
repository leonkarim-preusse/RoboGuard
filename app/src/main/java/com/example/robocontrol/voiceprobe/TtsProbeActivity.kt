package com.example.robocontrol.voiceprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Test screen for [TtsProbe], i.e. for `robocontrol.audio.OrionStarTts` on the robot.
 * Not part of RoboGuard's UI.
 *
 * Start it on the robot with
 *   adb shell am start -n com.example.roboguard/com.example.robocontrol.voiceprobe.TtsProbeActivity
 * The full procedure is in README.md, "Testing text-to-speech".
 *
 * Only one test runs at a time; test buttons are disabled while one is running. ✔/✘, Stop and
 * Clear screen stay available. Must stay in the foreground: RobotOS only serves the SDK to the
 * foreground app.
 */
class TtsProbeActivity : ComponentActivity() {

    private lateinit var log: ProbeLog
    private lateinit var probe: TtsProbe

    /** The test currently running, so Stop can cancel it. */
    private var running: Job? = null

    /** True while a test runs; drives the enabled state of the test buttons. */
    private val busy = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application context: SDK listeners must not keep this activity alive.
        log = ProbeLog(applicationContext)
        probe = TtsProbe(applicationContext, log)
        log.i("probe", "log file: ${log.filePath}")
        probe.connect()

        // Plain MaterialTheme: robocontrol must not depend on roboguard's theme.
        setContent {
            MaterialTheme {
                ProbeScreen()
            }
        }
    }

    /**
     * Leaving the screen (home button, another app coming to the front) stops a running test and the speech.
     * The SDK would stop serving this app in the background anyway, and a test must never keep running unseen.
     */
    override fun onStop() {
        if (running?.isActive == true) stopTest("screen left")
        super.onStop()
    }

    override fun onDestroy() {
        running?.cancel()
        probe.disconnect()
        super.onDestroy()
    }

    /** Three rows of buttons above a scrolling, monospaced log. */
    @Composable
    private fun ProbeScreen() {
        val lines by log.lines.collectAsState()
        val isBusy by busy.collectAsState()

        // Keep the newest line visible.
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // Always enabled and always at the top, so a test can be stopped no matter what state it is in.
            Button(
                onClick = { stopTest("STOP pressed") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) { Text(if (isBusy) "STOP (test running)" else "STOP", fontSize = 26.sp) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !isBusy, onClick = { runTest { probe.saySentences() } }) { Text("0 Say sentences") }
                Button(enabled = !isBusy, onClick = { runTest { probe.listVoices() } }) { Text("1 Voices") }
                Button(enabled = !isBusy, onClick = { runTest { probe.english() } }) { Text("2 English") }
                Button(enabled = !isBusy, onClick = { runTest { probe.german() } }) { Text("2 German") }
                Button(enabled = !isBusy, onClick = { runTest { probe.germanNumericCode() } }) { Text("3 German (code 7)") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !isBusy, onClick = { runTest { probe.validation() } }) { Text("4 Validation") }
                Button(enabled = !isBusy, onClick = { runTest { probe.interrupt() } }) { Text("5 Interrupt") }
                Button(enabled = !isBusy, onClick = { runTest { probe.overlap() } }) { Text("6 Overlap") }
                Button(enabled = !isBusy, onClick = { runTest { probe.playStatus() } }) { Text("7 Play status") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { probe.verdict(asExpected = true) }) { Text("✔ As expected") }
                OutlinedButton(onClick = { probe.verdict(asExpected = false) }) { Text("✘ Wrong") }
                OutlinedButton(onClick = { log.clearScreen() }) { Text("Clear screen") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }

    /**
     * Runs one test off the main thread (the tests wait on callbacks and binder calls).
     * Ignored if a test is already running.
     */
    private fun runTest(test: suspend () -> Unit) {
        if (running?.isActive == true) return
        busy.value = true
        running = lifecycleScope.launch(Dispatchers.IO) {
            try {
                test()
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Cancels the running test, silences the robot and re-enables the test buttons immediately,
     * without waiting for the cancelled coroutine to finish.
     */
    private fun stopTest(reason: String) {
        running?.cancel()
        running = null
        probe.stopSpeech()
        busy.value = false
        log.i("probe", "stopped: $reason")
    }
}
