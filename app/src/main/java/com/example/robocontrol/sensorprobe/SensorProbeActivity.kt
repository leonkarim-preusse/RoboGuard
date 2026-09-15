package com.example.robocontrol.sensorprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.example.robocontrol.voiceprobe.ProbeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Test screen for [SensorProbe], i.e. for switching LIDAR, microphone and camera through
 * `robocontrol.sensorcontrol.Sensors`. Not part of RoboGuard's UI.
 *
 * Start it on the robot with
 *   adb shell am start -n com.example.roboguard/com.example.robocontrol.sensorprobe.SensorProbeActivity
 * The full procedure is in README.md, "Testing sensor switching".
 *
 * Only one test runs at a time. ✔/✘ and Clear screen stay available while a test runs. Closing the
 * screen switches every sensor back to the saved settings, so nothing is left off by accident. The
 * screen must stay in the foreground: RobotOS only serves the SDK to the foreground app.
 */
class SensorProbeActivity : ComponentActivity() {

    private lateinit var log: ProbeLog
    private lateinit var probe: SensorProbe

    private var running: Job? = null
    private val busy = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = ProbeLog(applicationContext)
        probe = SensorProbe(applicationContext, log)
        log.i("probe", "log file: ${log.filePath}")
        probe.start()

        // Plain MaterialTheme: robocontrol must not depend on roboguard's theme.
        setContent {
            MaterialTheme {
                ProbeScreen()
            }
        }
    }

    override fun onDestroy() {
        running?.cancel()
        probe.stop()
        // Never leave LIDAR, microphone or camera switched off after testing. GlobalScope because this
        // activity's scope is being cancelled; best effort, the SDK may already refuse a background app.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) { probe.restoreSaved() }
        super.onDestroy()
    }

    @Composable
    private fun ProbeScreen() {
        val lines by log.lines.collectAsState()
        val isBusy by busy.collectAsState()

        // The microphone check records one second; ask for the permission up front.
        val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log.i("probe", "RECORD_AUDIO granted=$granted")
        }
        LaunchedEffect(Unit) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !isBusy, onClick = { runTest { probe.status() } }) { Text("1 Status") }
                Button(enabled = !isBusy, onClick = { runTest { probe.restoreSaved() } }) { Text("Restore saved settings") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !isBusy, onClick = { runTest { probe.switchSensor("Microphone", true) } }) { Text("2 Microphone ON") }
                Button(enabled = !isBusy, onClick = { runTest { probe.switchSensor("Microphone", false) } }) { Text("3 Microphone OFF") }
                Button(enabled = !isBusy, onClick = { runTest { probe.switchSensor("Camera", true) } }) { Text("4 Camera ON") }
                Button(enabled = !isBusy, onClick = { runTest { probe.switchSensor("Camera", false) } }) { Text("5 Camera OFF") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !isBusy, onClick = { runTest { probe.switchSensor("LIDAR", false) } }) { Text("6 LIDAR OFF") }
                Button(enabled = !isBusy, onClick = { runTest { probe.switchSensor("LIDAR", true) } }) { Text("7 LIDAR ON") }
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

    /** Runs one test off the main thread; ignored while another test is running. */
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
}
