package com.example.robocontrol.conversation

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.robocontrol.text.UiText

/** Colours of the timeline; kept next to the drawing code because they only mean something together with it. */
private val SPEECH_FILL = Color(0x3300E676)
private val LEVEL_LINE = Color(0xFF2A78D6)
private val PROBABILITY_LINE = Color(0xFFEB6834)
private val THRESHOLD_LINE = Color(0xFFB5B4AD)
private val CHANGE_MARK = Color(0xFFD32F2F)
private val REJECTED_MARK = Color(0xFFBDBDBD)
private val RESET_MARK = Color(0xFF7B1FA2)
private val MULTIPLE_MARK = Color(0xFF2E7D32)

/**
 * Debug screen of the conversation detection ([ConversationMonitor]): what the microphone hears, what Silero makes of it, and
 * when the robot counts a speaker change.
 *
 * The timeline shows the last 15 seconds, 10 ms per frame, newest on the right:
 *  - blue line: how loud it is (−90…0 dBFS)
 *  - orange line: Silero's speech probability (0…1), with its threshold as a grey line
 *  - green background: the frames the gate counted as speech — everything white is what Silero cut out
 *  - red mark: counted speaker change · grey mark: candidate rejected · purple: conversation reset · green: "more than one" reached
 *
 * Only numbers are shown, and the timeline is only collected while this screen is open
 * ([ConversationMonitor.addDebugViewer]); no audio is stored anywhere.
 */
@Composable
fun SpeakerDebugScreen(onBack: () -> Unit, topControls: @Composable () -> Unit = {}) {
    DisposableEffect(Unit) {
        ConversationMonitor.addDebugViewer()
        onDispose { ConversationMonitor.removeDebugViewer() }
    }
    val status by ConversationMonitor.status.collectAsState()
    val snapshot by ConversationMonitor.snapshot.collectAsState()
    val method by ConversationMonitor.method.collectAsState()

    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topControls()
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(UiText.get("speaker_view.back"), fontSize = 16.sp) }

            // Which method decides "one voice or several". Switching restarts the detector straight away.
            Text(UiText.get("speaker_view.method"), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DetectionMethod.entries.forEach { m ->
                    val label = UiText.get(
                        when (m) {
                            DetectionMethod.CHANGE -> "speaker_view.method.change"
                            DetectionMethod.OWNER -> "speaker_view.method.owner"
                            DetectionMethod.PAIRWISE -> "speaker_view.method.pairwise"
                        }
                    )
                    if (m == method) {
                        Button(onClick = { }, modifier = Modifier.weight(1f)) { Text(label, fontSize = 12.sp, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { ConversationMonitor.setMethod(m) },
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 12.sp, maxLines = 1) }
                    }
                }
            }
            if (method == DetectionMethod.PAIRWISE) {
                val differentBelow by ConversationMonitor.differentBelow.collectAsState()
                Text(UiText.get("speaker_view.pairwise.hint"), fontSize = 12.sp)
                Text(UiText.get("speaker_view.pairwise.threshold", "value" to "%.2f".format(differentBelow)), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.10f, -0.05f, +0.05f, +0.10f).forEach { step ->
                        OutlinedButton(
                            onClick = { ConversationMonitor.setDifferentBelow(differentBelow + step) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text(if (step > 0) "+%.2f".format(step) else "%.2f".format(step), fontSize = 12.sp) }
                    }
                }
                Text(
                    UiText.get(
                        "speaker_view.pairwise.now",
                        "lowest" to (snapshot.pairwiseMin?.let { "%.2f".format(it) } ?: "—"),
                        "held" to snapshot.piecesHeld
                    ),
                    fontSize = 13.sp
                )
                if (snapshot.pairwiseReadings.isNotEmpty()) {
                    Text(
                        UiText.get("speaker_view.owner.readings",
                            "readings" to snapshot.pairwiseReadings.joinToString("  ") { "%.2f".format(it) }),
                        fontSize = 12.sp
                    )
                }
            }
            if (method == DetectionMethod.OWNER) {
                val anyOther by ConversationMonitor.anyOtherIsConversation.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = anyOther, onCheckedChange = { ConversationMonitor.setAnyOtherIsConversation(it) })
                    Text(
                        UiText.get(if (anyOther) "speaker_view.rule.any" else "speaker_view.rule.owner_and_other"),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                val similarity = snapshot.ownerSimilarity
                Text(
                    if (similarity == null) UiText.get("speaker_view.owner.waiting")
                    else UiText.get(
                        "speaker_view.owner.last",
                        "similarity" to "%.2f".format(similarity),
                        "threshold" to "%.2f".format(snapshot.ownerThreshold)
                    ),
                    fontSize = 13.sp
                )
                Text(
                    UiText.get("speaker_view.owner.counts", "owner" to snapshot.ownerPieces, "other" to snapshot.otherPieces),
                    fontSize = 13.sp
                )
                if (snapshot.ownerReadings.isNotEmpty()) {
                    Text(
                        UiText.get("speaker_view.owner.readings",
                            "readings" to snapshot.ownerReadings.joinToString("  ") { "%.2f".format(it) }),
                        fontSize = 12.sp
                    )
                }
            }
            val stateKey = when (snapshot.state) {
                ConversationState.NO_SPEECH -> "speaker_view.state.no_speech"
                ConversationState.LISTENING -> "speaker_view.state.listening"
                ConversationState.ONE_SPEAKER -> "speaker_view.state.one"
                ConversationState.MULTIPLE_SPEAKERS -> "speaker_view.state.multiple"
            }
            Text(
                UiText.get(stateKey),
                fontWeight = FontWeight.Bold, fontSize = 20.sp,
                color = when (snapshot.state) {
                    ConversationState.MULTIPLE_SPEAKERS -> Color(0xFFEF6C00)
                    ConversationState.ONE_SPEAKER -> Color(0xFF2E7D32)
                    else -> Color.Gray
                }
            )
            if (!status.listening) Text(UiText.get("speaker_view.not_listening", "reason" to (status.reason ?: "")), fontSize = 13.sp)
            snapshot.error?.let { Text(UiText.get("speaker_view.error", "reason" to it), color = CHANGE_MARK, fontSize = 13.sp) }
            Text(UiText.get("speaker_view.level", "level" to "%.0f".format(snapshot.levelDb), "floor" to "%.0f".format(snapshot.noiseFloorDb)), fontSize = 14.sp)
            Text(UiText.get("speaker_view.gate", "gate" to snapshot.speechGate.name,
                "probability" to (snapshot.speechProbability?.let { "%.2f".format(it) } ?: "—"),
                "speech" to UiText.get(if (snapshot.speechNow) "speaker_view.yes" else "speaker_view.no")), fontSize = 14.sp)
            Text(UiText.get("speaker_view.speech_seconds", "seconds" to "%.1f".format(snapshot.speechSeconds)), fontSize = 14.sp)
            Text(UiText.get("speaker_view.changes", "window" to snapshot.changesInWindow, "total" to snapshot.totalChanges), fontSize = 14.sp)
            Text(UiText.get("speaker_view.evidence", "evidence" to "%.2f".format(snapshot.evidence), "rule" to snapshot.decisionRule.name), fontSize = 14.sp)
            Text(UiText.get("speaker_view.kl2", "last" to "%.1f".format(snapshot.lastKl2), "mean" to "%.1f".format(snapshot.kl2Mean)), fontSize = 14.sp)
            Text(UiText.get("speaker_view.legend"), fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(UiText.get("speaker_view.timeline_title"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Timeline(snapshot, Modifier.fillMaxWidth().weight(1f).background(Color(0xFFFCFCFB)))
            Text(UiText.get("speaker_view.events_title"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Column(Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState())) {
                val now = snapshot.timeline.lastOrNull()?.timeMs ?: 0
                for (event in snapshot.events.asReversed().take(12)) {
                    val key = when (event.kind) {
                        ConversationEventKind.CHANGE -> "speaker_view.event.change"
                        ConversationEventKind.CANDIDATE_REJECTED -> "speaker_view.event.rejected"
                        ConversationEventKind.RESET -> "speaker_view.event.reset"
                        ConversationEventKind.MULTIPLE -> "speaker_view.event.multiple"
                    }
                    Text(
                        UiText.get(key, "seconds" to "%.1f".format((now - event.timeMs) / 1000.0), "ratio" to "%.2f".format(event.ratio)),
                        fontSize = 13.sp,
                        color = when (event.kind) {
                            ConversationEventKind.CHANGE -> CHANGE_MARK
                            ConversationEventKind.RESET -> RESET_MARK
                            ConversationEventKind.MULTIPLE -> MULTIPLE_MARK
                            else -> Color.Gray
                        }
                    )
                }
            }
        }
    }
}

/** Draws the last seconds of audio: level, Silero probability, the speech gate as background, and the events as marks. */
@Composable
private fun Timeline(snapshot: ConversationSnapshot, modifier: Modifier) {
    Canvas(modifier) {
        val frames = snapshot.timeline
        if (frames.isEmpty()) return@Canvas
        val first = frames.first().timeMs
        val last = frames.last().timeMs
        val span = (last - first).coerceAtLeast(1L).toDouble()
        fun x(timeMs: Long) = ((timeMs - first) / span * size.width).toFloat()
        // Level −90…0 dBFS in the upper 60 %, Silero 0…1 in the lower 40 %.
        val levelBottom = size.height * 0.6f
        fun levelY(db: Double) = (levelBottom - ((db.coerceIn(-90.0, 0.0) + 90.0) / 90.0 * levelBottom)).toFloat()
        fun probabilityY(p: Double) = (size.height - p.coerceIn(0.0, 1.0) * (size.height - levelBottom)).toFloat()

        // Green background wherever the gate counted speech (so the white gaps are what Silero cut out).
        var runStart: Long? = null
        for (frame in frames) {
            if (frame.speech && runStart == null) runStart = frame.timeMs
            if (!frame.speech && runStart != null) {
                drawRect(SPEECH_FILL, Offset(x(runStart), 0f), Size(x(frame.timeMs) - x(runStart), size.height))
                runStart = null
            }
        }
        runStart?.let { drawRect(SPEECH_FILL, Offset(x(it), 0f), Size(size.width - x(it), size.height)) }

        drawLine(THRESHOLD_LINE, Offset(0f, levelBottom), Offset(size.width, levelBottom), strokeWidth = 1f)
        val thresholdY = probabilityY(ChangeDetectorConfig().sileroThreshold)
        drawLine(THRESHOLD_LINE, Offset(0f, thresholdY), Offset(size.width, thresholdY), strokeWidth = 1f)

        val levelPoints = frames.map { Offset(x(it.timeMs), levelY(it.levelDb)) }
        drawPoints(levelPoints, androidx.compose.ui.graphics.PointMode.Polygon, LEVEL_LINE, strokeWidth = 2f)
        val probabilityPoints = frames.mapNotNull { f -> f.probability?.let { Offset(x(f.timeMs), probabilityY(it)) } }
        if (probabilityPoints.size > 1) drawPoints(probabilityPoints, androidx.compose.ui.graphics.PointMode.Polygon, PROBABILITY_LINE, strokeWidth = 2f)

        for (event in snapshot.events) {
            if (event.timeMs < first) continue
            val color = when (event.kind) {
                ConversationEventKind.CHANGE -> CHANGE_MARK
                ConversationEventKind.CANDIDATE_REJECTED -> REJECTED_MARK
                ConversationEventKind.RESET -> RESET_MARK
                ConversationEventKind.MULTIPLE -> MULTIPLE_MARK
            }
            val width = if (event.kind == ConversationEventKind.CANDIDATE_REJECTED) 1f else 3f
            drawLine(color, Offset(x(event.timeMs), 0f), Offset(x(event.timeMs), size.height), strokeWidth = width)
        }
    }
}
