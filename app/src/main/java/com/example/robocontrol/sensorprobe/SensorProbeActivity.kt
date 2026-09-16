package com.example.robocontrol.sensorprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
 * Start it from the robot's home launcher icon "RG Sensor Test". Screens started with `adb shell am start`
 * get no SDK control from RobotOS. The full procedure is in README.md, "Testing sensor switching".
 *
 * Layout: buttons in a scrollable column on the left, log on the right. The robot's display renders
 * buttons very large, and a row-based layout pushed buttons and the log off-screen, so every control
 * stays reachable by scrolling here.
 *
 * Only one test runs at a time. ✔/✘ and Clear screen stay available while a test runs. Closing the
 * screen switches every sensor back to the saved settings (best effort; pressing "Restore saved settings"
 * first is safer). The screen must stay in the foreground: RobotOS only serves the SDK to the foreground app.
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
        val shown by probe.lastImage.collectAsState()

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

        Row(Modifier.fillMaxSize().padding(12.dp)) {
            // Left: all controls, one per line, scrollable so none can be pushed off-screen.
            Column(
                modifier = Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TestButton("1 Status", isBusy) { probe.status() }
                TestButton("2 Microphone ON", isBusy) { probe.switchSensor("Microphone", true) }
                TestButton("3 Microphone OFF", isBusy) { probe.switchSensor("Microphone", false) }
                TestButton("4 Camera ON", isBusy) { probe.switchSensor("Camera", true) }
                TestButton("5 Camera OFF", isBusy) { probe.switchSensor("Camera", false) }
                TestButton("6 LIDAR OFF", isBusy) { probe.switchSensor("LIDAR", false) }
                TestButton("7 LIDAR ON", isBusy) { probe.switchSensor("LIDAR", true) }
                TestButton("Restore saved settings", isBusy) { probe.restoreSaved() }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { probe.verdict(asExpected = true) }, modifier = Modifier.weight(1f)) { Text("✔") }
                    OutlinedButton(onClick = { probe.verdict(asExpected = false) }, modifier = Modifier.weight(1f)) { Text("✘") }
                }
                OutlinedButton(onClick = { log.clearScreen() }, modifier = Modifier.fillMaxWidth()) { Text("Clear screen") }
            }

            Spacer(Modifier.width(16.dp))

            // Right: the latest camera snapshot (if any) above the log, taking the remaining width.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                shown?.let { image ->
                    Text(image.caption, fontSize = 14.sp)
                    image.bitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = image.caption,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(360.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        }
    }

    /** One full-width test button; disabled while any test is running. */
    @Composable
    private fun TestButton(label: String, isBusy: Boolean, test: suspend () -> Unit) {
        Button(enabled = !isBusy, onClick = { runTest(test) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
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
