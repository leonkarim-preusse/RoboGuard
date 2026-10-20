package com.example.testing.voiceprobe

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Test screen for the voice probes: microphone access ([MicAccessProbe]), speech service callbacks
 * ([SpeechServiceProbe]) and sound direction ([SoundDirectionProbe]).
 */
class VoiceProbeActivity : ComponentActivity() {

    private lateinit var log: ProbeLog
    private lateinit var mic: MicAccessProbe
    private lateinit var speech: SpeechServiceProbe
    private lateinit var direction: SoundDirectionProbe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application context: the probes register SDK listeners and a broadcast receiver
        // that must not keep this activity alive after it closes.
        log = ProbeLog(applicationContext)
        mic = MicAccessProbe(applicationContext, log)
        speech = SpeechServiceProbe(applicationContext, log)
        direction = SoundDirectionProbe(applicationContext, log)
        log.i("probe", "log file: ${log.filePath}")

        // Plain MaterialTheme instead of RoboGuard's theme: robocontrol must not depend on roboguard.
        setContent {
            MaterialTheme {
                ProbeScreen()
            }
        }
    }

    /** Leaves no SDK listeners, callbacks or receivers behind when the test screen closes. */
    override fun onDestroy() {
        speech.stop()
        direction.stop()
        super.onDestroy()
    }

    /** Two rows of buttons above a scrolling, monospaced log. */
    @Composable
    private fun ProbeScreen() {
        val lines by log.lines.collectAsState()

        // Disables the mic button while a run is in progress, so two runs cannot fight over the mic.
        var micRunning by remember { mutableStateOf(false) }

        // RECORD_AUDIO is a runtime permission. If the user grants it, the probe starts right away.
        val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log.i("probe", "RECORD_AUDIO granted=$granted")
            if (granted) runMicProbe { micRunning = it }
        }

        // Keep the newest line visible.
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !micRunning,
                    onClick = {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            runMicProbe { micRunning = it }
                        } else {
                            requestMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                ) { Text(if (micRunning) "Mic probe running…" else "1 Mic access") }
                Button(onClick = { speech.start() }) { Text("2 Speech service") }
                Button(onClick = { speech.toggleMultipleMode() }) { Text("Toggle multiple mode") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { direction.start() }) { Text("3 Sound direction") }
                OutlinedButton(onClick = { speech.logTtsStatus() }) { Text("TTS status") }
                OutlinedButton(onClick = { speech.stop(); direction.stop() }) { Text("Stop listeners") }
                OutlinedButton(onClick = { log.clearScreen() }) { Text("Clear screen") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }

    /**
     * Runs [MicAccessProbe] on the IO dispatcher.
     * @param setRunning called with true at the start and false at the end
     */
    private fun runMicProbe(setRunning: (Boolean) -> Unit) {
        setRunning(true)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { mic.run() }
            setRunning(false)
        }
    }
}
