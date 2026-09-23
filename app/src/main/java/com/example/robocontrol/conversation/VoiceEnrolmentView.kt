package com.example.robocontrol.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.robocontrol.text.UiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Teaches the robot the owner's voice and shows what it stored.
 *
 * Opened from "Navigation and Map" → Show debug → "Teach owner's voice". The screen makes the three things visible that
 * the thesis asks for: that a voice is stored at all, what exactly is stored (statistics, never the vector), and a way
 * to delete it again.
 *
 * "Try the voice" runs the same pipeline without storing anything: every ~3 s of speech is compared with the stored
 * template and only the similarity is shown — the number the threshold is set from, and the one to write down when
 * testing with other people.
 */
@Composable
fun VoiceEnrolmentScreen(onBack: () -> Unit, topControls: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val enrolment = remember { VoiceEnrolment(context) }
    val store = remember { OwnerVoiceprintStore.get(context) }
    val state by enrolment.state.collectAsState()
    val stored by store.info.collectAsState()
    val cohortPieces by store.cohortPieces.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var missingPermission by remember { mutableStateOf(false) }
    // Derived from the published state, NOT from VoiceEnrolment.running: that is a plain property, so Compose never
    // redraws when the recording thread ends — the screen kept showing "Stop" and never offered "Try the voice".
    val running = state.phase == EnrolmentState.Phase.RECORDING || state.phase == EnrolmentState.Phase.TESTING

    // Recording must stop when the screen goes away, or the microphone would stay taken.
    DisposableEffect(Unit) { onDispose { enrolment.stop() } }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        missingPermission = !granted
        if (granted) enrolment.startEnrolment()
    }
    fun withMicrophone(action: () -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else askPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(UiText.get("voice.dialog.delete.title")) },
            text = { Text(UiText.get("voice.dialog.delete.text")) },
            confirmButton = {
                TextButton(onClick = { store.delete(); confirmDelete = false }) {
                    Text(UiText.get("voice.button.delete"), color = Color(0xFFD32F2F))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(UiText.get("voice.button.cancel")) } }
        )
    }

    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            topControls()
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(UiText.get("voice.button.back"), fontSize = 16.sp)
            }

            Text(UiText.get("voice.title"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(UiText.get("voice.explanation"), fontSize = 13.sp)

            if (running) {
                Button(
                    onClick = { enrolment.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(UiText.get("voice.button.stop")) }
            } else {
                Button(
                    onClick = { withMicrophone { enrolment.startEnrolment() } },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(UiText.get(if (stored == null) "voice.button.record" else "voice.button.record_again")) }
                if (stored != null) {
                    OutlinedButton(
                        onClick = { withMicrophone { enrolment.startTest() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(UiText.get("voice.button.test")) }
                    OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(UiText.get("voice.button.delete"), color = Color(0xFFD32F2F))
                    }
                }
                // Other voices: press, then play the recording; stop when it has finished.
                Text(UiText.get("voice.cohort.title"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    cohortPieces?.let { UiText.get("voice.cohort.stored", "pieces" to it) }
                        ?: UiText.get("voice.cohort.none"),
                    fontSize = 13.sp
                )
                OutlinedButton(
                    onClick = { withMicrophone { enrolment.startCohort() } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(UiText.get("voice.button.cohort"), fontSize = 13.sp) }
                if (cohortPieces != null) {
                    OutlinedButton(onClick = { store.deleteCohort() }, modifier = Modifier.fillMaxWidth()) {
                        Text(UiText.get("voice.button.cohort_delete"), fontSize = 13.sp, color = Color(0xFFD32F2F))
                    }
                }
            }
            if (missingPermission) {
                Text(UiText.get("voice.error.permission"), color = Color(0xFFD32F2F), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- what is stored
            Text(UiText.get("voice.stored.title"), fontWeight = FontWeight.Bold)
            val info = stored
            if (info == null) {
                Text(UiText.get("voice.stored.none"), fontSize = 14.sp)
            } else {
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(info.createdAtMs))
                Text(UiText.get("voice.stored.date", "date" to date), fontSize = 14.sp)
                Text(
                    UiText.get(
                        "voice.stored.detail",
                        "pieces" to info.pieces,
                        "seconds" to "%.0f".format(info.speechSeconds),
                        "dimensions" to info.dimensions
                    ),
                    fontSize = 14.sp
                )
                Text(
                    UiText.get(
                        "voice.stored.quality",
                        "mean" to "%.2f".format(info.selfSimilarityMean),
                        "worst" to "%.2f".format(info.selfSimilarityMin),
                        "threshold" to "%.2f".format(info.threshold)
                    ),
                    fontSize = 14.sp
                )
                // The computed threshold comes from ONE recording and is far too strict (owner measured 0.89-0.91
                // against a computed 0.94). Set it by hand from what "Try the voice" actually reads.
                Text(UiText.get("voice.threshold.title", "value" to "%.2f".format(info.threshold)), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.05f, -0.01f, +0.01f, +0.05f).forEach { step ->
                        OutlinedButton(
                            onClick = { store.setThreshold(info.threshold + step) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) { Text(if (step > 0) "+%.2f".format(step) else "%.2f".format(step), fontSize = 13.sp) }
                    }
                }
                Text(UiText.get("voice.threshold.hint"), fontSize = 12.sp, color = Color(0xFF555555))
            }

            // ---- what is happening now
            Spacer(Modifier.height(4.dp))
            Text(UiText.get("voice.now.title"), fontWeight = FontWeight.Bold)
            when (state.phase) {
                EnrolmentState.Phase.RECORDING -> if (state.mode == VoiceEnrolment.Mode.COHORT) {
                    Text(
                        UiText.get("voice.now.cohort", "seconds" to "%.0f".format(state.speechSeconds), "pieces" to state.pieces),
                        fontSize = 16.sp
                    )
                    Text(UiText.get("voice.hint.cohort"), fontSize = 13.sp)
                } else {
                    Text(
                        UiText.get(
                            "voice.now.recording",
                            "seconds" to "%.0f".format(state.speechSeconds),
                            "target" to "%.0f".format(VoiceEnrolment.TARGET_SECONDS),
                            "pieces" to state.pieces
                        ),
                        fontSize = 16.sp
                    )
                    LinearProgressIndicator(
                        progress = { (state.speechSeconds / VoiceEnrolment.TARGET_SECONDS).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                    Text(UiText.get("voice.hint.speak"), fontSize = 13.sp)
                }
                EnrolmentState.Phase.TESTING -> {
                    // Always visible while trying, so it never looks as if nothing is happening.
                    Text(UiText.get("voice.now.testing_wait"), fontSize = 14.sp, color = Color(0xFF2E7D32))
                    val similarity = state.similarity
                    if (similarity != null) {
                        Text(
                            UiText.get(
                                if (state.isOwner == true) "voice.now.owner" else "voice.now.other",
                                "similarity" to "%.2f".format(similarity)
                            ),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.isOwner == true) Color(0xFF2E7D32) else Color(0xFFEF6C00)
                        )
                        SimilarityBar(similarity, stored?.threshold ?: 0.5f)
                        state.rawSimilarity?.takeIf { cohortPieces != null }?.let { raw ->
                            Text(UiText.get("voice.now.raw", "raw" to "%.2f".format(raw)), fontSize = 12.sp)
                        }
                    }
                    Text(UiText.get("voice.now.pieces", "pieces" to state.pieces), fontSize = 13.sp)
                    if (state.readings.isNotEmpty()) {
                        Text(
                            UiText.get("voice.now.readings", "readings" to state.readings.joinToString("  ") { "%.2f".format(it) }),
                            fontSize = 13.sp
                        )
                    }
                }
                EnrolmentState.Phase.SAVED -> Text(
                    state.message ?: UiText.get("voice.now.saved"),
                    fontSize = 16.sp, color = Color(0xFF2E7D32)
                )
                EnrolmentState.Phase.ERROR -> Text(
                    UiText.get("voice.now.error", "error" to (state.error ?: "?")),
                    fontSize = 15.sp, color = Color(0xFFD32F2F)
                )
                EnrolmentState.Phase.IDLE -> Text(UiText.get("voice.now.idle"), fontSize = 14.sp)
            }

            if (running) {
                Text(
                    UiText.get(
                        "voice.now.microphone",
                        "probability" to "%.2f".format(state.speechProbability),
                        "level" to if (state.levelDb.isFinite()) "%.0f".format(state.levelDb) else "-∞"
                    ),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(UiText.get("voice.privacy"), fontSize = 12.sp, color = Color(0xFF555555))
        }
    }
}

/** Similarity from 0 to 1 with the stored threshold marked, so a test run can be read off at a glance. */
@Composable
private fun SimilarityBar(similarity: Float, threshold: Float) {
    Box(Modifier.fillMaxWidth().height(26.dp).background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth(similarity.coerceIn(0f, 1f))
                .background(
                    if (similarity >= threshold) Color(0xFF81C784) else Color(0xFFFFCC80),
                    RoundedCornerShape(4.dp)
                )
        )
        Box(Modifier.fillMaxHeight().fillMaxWidth(threshold.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFFD32F2F)))
        }
        Text(
            "%.2f".format(similarity),
            modifier = Modifier.padding(start = 6.dp).align(Alignment.CenterStart),
            fontSize = 13.sp
        )
    }
}
