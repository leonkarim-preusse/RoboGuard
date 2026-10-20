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
import androidx.compose.ui.platform.LocalContext
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
private val ROBOT_FILL = Color(0xFF9E9E9E)

/**
 * Debug screen of the conversation detection: timeline of the last 15 s (level, Silero probability, gate decisions,
 * robot speech), detection events, live values and the detection settings.
 * @param onBack called when the screen is closed
 * @param topControls controls shown at the top, e.g. a STOP button
 */
@Composable
fun SpeakerDebugScreen(onBack: () -> Unit, topControls: @Composable () -> Unit = {}) {
    // Registered only while the screen is visible: in the background the test announcement must stop.
    WhileVisible(onStart = { ConversationMonitor.addDebugViewer() }, onStop = { ConversationMonitor.removeDebugViewer() })
    val status by ConversationMonitor.status.collectAsState()
    val snapshot by ConversationMonitor.snapshot.collectAsState()
    val method by ConversationMonitor.method.collectAsState()

    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            topControls()
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(UiText.get("speaker_view.back"), fontSize = 16.sp) }

            // Starts over: the prompt may come again at once, and the detector forgets what it has heard.
            OutlinedButton(
                onClick = { ConversationMonitor.resetCooldown() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(UiText.get("speaker_view.reset_cooldown"), fontSize = 13.sp) }

            // Which method decides "one voice or several". Switching restarts the detector straight away.
            Text(UiText.get("speaker_view.method"), fontSize = 13.sp)
            // Four methods do not fit in one row on the robot's screen, so two rows of two.
            DetectionMethod.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { m ->
                        val label = UiText.get(
                            when (m) {
                                DetectionMethod.CHANGE -> "speaker_view.method.change"
                                DetectionMethod.OWNER -> "speaker_view.method.owner"
                                DetectionMethod.PAIRWISE -> "speaker_view.method.pairwise"
                                DetectionMethod.ACTIVITY -> "speaker_view.method.activity"
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
            }

            // The gate that decides what counts as speech at all — shared by every method, so it sits above them.
            val gateMode by ConversationMonitor.gateMode.collectAsState()
            val minLevel by ConversationMonitor.minLevelDb.collectAsState()
            Text(UiText.get("speaker_view.gate.title"), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                SpeechGateMode.entries.forEach { mode ->
                    val label = UiText.get(
                        when (mode) {
                            SpeechGateMode.SILERO_AND_LEVEL -> "speaker_view.gate.both"
                            SpeechGateMode.SILERO_ONLY -> "speaker_view.gate.silero"
                            SpeechGateMode.LEVEL_ONLY -> "speaker_view.gate.level"
                        }
                    )
                    if (mode == gateMode) {
                        Button(onClick = { }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text(label, fontSize = 11.sp, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(onClick = { ConversationMonitor.setGateMode(mode) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text(label, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (gateMode != SpeechGateMode.LEVEL_ONLY) {
                val threshold by ConversationMonitor.speechThreshold.collectAsState()
                val hysteresis by ConversationMonitor.speechHysteresis.collectAsState()
                Text(UiText.get("speaker_view.gate.threshold", "value" to "%.2f".format(threshold),
                    "ends" to "%.2f".format(threshold - hysteresis)), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.10f, -0.05f, +0.05f, +0.10f).forEach { step ->
                        OutlinedButton(
                            onClick = { ConversationMonitor.setSpeechThreshold(threshold + step) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("%+.2f".format(step), fontSize = 12.sp) }
                    }
                }
                Text(UiText.get("speaker_view.gate.hysteresis", "value" to "%.2f".format(hysteresis)), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.10f, -0.05f, +0.05f, +0.10f).forEach { step ->
                        OutlinedButton(
                            onClick = { ConversationMonitor.setSpeechHysteresis(hysteresis + step) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("%+.2f".format(step), fontSize = 12.sp) }
                    }
                }
            }
            if (gateMode != SpeechGateMode.SILERO_ONLY) {
                Text(UiText.get("speaker_view.gate.level_value", "value" to "%.0f".format(minLevel)), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-5f, -1f, +1f, +5f).forEach { step ->
                        OutlinedButton(
                            onClick = { ConversationMonitor.setMinLevelDb(minLevel + step) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text(if (step > 0) "+%.0f".format(step) else "%.0f".format(step), fontSize = 12.sp) }
                    }
                }
            }
            Text(UiText.get("speaker_view.gate.note"), fontSize = 11.sp, color = Color.Gray)

            // Spoken feedback, only on this screen (see ConversationMonitor.maybeAnnounceInTest).
            val announce by ConversationMonitor.announceInTest.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = announce, onCheckedChange = { ConversationMonitor.setAnnounceInTest(it) })
                Text(
                    UiText.get("speaker_view.announce", "seconds" to (ConversationMonitor.TEST_ANNOUNCE_COOLDOWN_MS / 1000)),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // How a piece is cut out of the stream: only the gated frames, or a continuous stretch.
            if (method == DetectionMethod.OWNER || method == DetectionMethod.PAIRWISE) {
                val continuous by ConversationMonitor.continuousPieces.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = continuous, onCheckedChange = { ConversationMonitor.setContinuousPieces(it) })
                    Text(UiText.get("speaker_view.continuous"), fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
                Text(UiText.get("speaker_view.continuous.note"), fontSize = 11.sp, color = Color.Gray)
                val split by ConversationMonitor.splitAtChanges.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = split, onCheckedChange = { ConversationMonitor.setSplitAtChanges(it) })
                    Text(UiText.get("speaker_view.split"), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Text(UiText.get("speaker_view.split.note"), fontSize = 11.sp, color = Color.Gray)
            }

            // One stray piece should not decide anything: a verdict that changes has to be repeated.
            if (method != DetectionMethod.CHANGE) {
                val confirm by ConversationMonitor.confirmTwice.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = confirm, onCheckedChange = { ConversationMonitor.setConfirmTwice(it) })
                    Text(UiText.get("speaker_view.confirm_twice"), fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
                Text(UiText.get("speaker_view.confirm_twice.note"), fontSize = 11.sp, color = Color.Gray)
            }

            if (method == DetectionMethod.ACTIVITY) {
                val share by ConversationMonitor.activityShare.collectAsState()
                val windowMs by ConversationMonitor.activityWindowMs.collectAsState()
                Text(UiText.get("speaker_view.activity.hint"), fontSize = 12.sp)
                Text(UiText.get("speaker_view.activity.share", "value" to "%.0f".format(share * 100)), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.10f, -0.05f, +0.05f, +0.10f).forEach { step ->
                        OutlinedButton(
                            onClick = { ConversationMonitor.setActivityShare(share + step) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("%+.0f".format(step * 100), fontSize = 12.sp) }
                    }
                }
                Text(UiText.get("speaker_view.activity.window", "value" to (windowMs / 1000)), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-10_000L, -5_000L, +5_000L, +10_000L).forEach { step ->
                        OutlinedButton(
                            onClick = { ConversationMonitor.setActivityWindowMs(windowMs + step) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("%+d s".format(step / 1000), fontSize = 12.sp) }
                    }
                }
                val minSilence by ConversationMonitor.activityMinSilenceMs.collectAsState()
                Text(UiText.get("speaker_view.activity.gap", "value" to minSilence), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-100L, -50L, +50L, +100L).forEach { step ->
                        OutlinedButton(
                            onClick = { ConversationMonitor.setActivityMinSilenceMs(minSilence + step) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("%+d".format(step), fontSize = 12.sp) }
                    }
                }
                Text(UiText.get("speaker_view.activity.gap_note"), fontSize = 11.sp, color = Color.Gray)
                Text(
                    UiText.get(
                        "speaker_view.activity.now",
                        "share" to "%.0f".format(snapshot.speechShare * 100),
                        "needed" to "%.0f".format(snapshot.speechShareNeeded * 100),
                        "filled" to "%.0f".format(snapshot.windowFilled * 100)
                    ),
                    fontSize = 13.sp
                )
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
                // The threshold belongs to the STORED voice, so it is stepped from the store and not from the
                // snapshot: the snapshot carries 0 whenever the detector is not running (the voice screen has the
                // microphone, the method was just switched, no piece yet), and 0 + 0.01 used to be clamped straight
                // down to the minimum — which looked like "setting the threshold does not work" (owner, 2026-09-28).
                val stored by OwnerVoiceprintStore.get(LocalContext.current).info.collectAsState()
                Text(
                    if (stored == null) UiText.get("speaker_view.owner.none")
                    else UiText.get("speaker_view.owner.threshold", "value" to "%.2f".format(stored!!.threshold)),
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.05f, -0.01f, +0.01f, +0.05f).forEach { step ->
                        OutlinedButton(
                            enabled = stored != null,
                            onClick = { stored?.let { ConversationMonitor.setOwnerThreshold(it.threshold + step) } },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("%+.2f".format(step), fontSize = 12.sp) }
                    }
                }
                // The unclear band below the threshold, stored with the voice like the threshold.
                Text(
                    if (stored == null) "" else UiText.get("speaker_view.owner.margin", "value" to "%.2f".format(stored!!.margin)),
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-0.02f, -0.01f, +0.01f, +0.02f).forEach { step ->
                        OutlinedButton(
                            enabled = stored != null,
                            onClick = { stored?.let { ConversationMonitor.setOwnerMargin(it.margin + step) } },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) { Text("%+.2f".format(step), fontSize = 12.sp) }
                    }
                }
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

/**
 * Draws the audio timeline: bands for Silero, level floor and robot speech, the level and probability curves with their
 * thresholds, and the events.
 * @param snapshot detector snapshot with the timeline
 * @param modifier layout modifier
 */
@Composable
private fun Timeline(snapshot: ConversationSnapshot, modifier: Modifier) {
    Canvas(modifier) {
        val frames = snapshot.timeline
        if (frames.isEmpty()) return@Canvas
        val first = frames.first().timeMs
        val last = frames.last().timeMs
        val span = (last - first).coerceAtLeast(1L).toDouble()
        fun x(timeMs: Long) = ((timeMs - first) / span * size.width).toFloat()
        // Bands on top, then the level in the next 50 %, Silero 0…1 in the rest.
        val bandHeight = size.height * 0.05f
        val bandsBottom = bandHeight * 3 + 6f
        val levelBottom = bandsBottom + (size.height - bandsBottom) * 0.55f
        fun levelY(db: Double) = (levelBottom - ((db.coerceIn(-90.0, 0.0) + 90.0) / 90.0 * (levelBottom - bandsBottom))).toFloat()
        fun probabilityY(p: Double) = (size.height - p.coerceIn(0.0, 1.0) * (size.height - levelBottom)).toFloat()

        /** Fills every run of frames where [on] holds, between [top] and [bottom]. */
        fun runs(color: Color, top: Float, bottom: Float, on: (AudioFrame) -> Boolean) {
            var start: Long? = null
            for (frame in frames) {
                if (on(frame) && start == null) start = frame.timeMs
                if (!on(frame) && start != null) {
                    drawRect(color, Offset(x(start), top), Size(x(frame.timeMs) - x(start), bottom - top))
                    start = null
                }
            }
            start?.let { drawRect(color, Offset(x(it), top), Size(size.width - x(it), bottom - top)) }
        }

        // What finally counted as speech, as background below the bands.
        runs(SPEECH_FILL, bandsBottom, size.height) { it.speech }
        runs(PROBABILITY_LINE, 0f, bandHeight) { it.silero }
        runs(LEVEL_LINE, bandHeight + 3f, bandHeight * 2 + 3f) { it.loud }
        runs(ROBOT_FILL, bandHeight * 2 + 6f, bandHeight * 3 + 6f) { it.robot }

        drawLine(THRESHOLD_LINE, Offset(0f, levelBottom), Offset(size.width, levelBottom), strokeWidth = 1f)
        snapshot.levelFloorDb?.let { floor ->
            val y = levelY(floor)
            drawLine(LEVEL_LINE, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        snapshot.sileroThreshold?.let { t ->
            val y = probabilityY(t)
            drawLine(PROBABILITY_LINE, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        snapshot.sileroRelease?.let { r ->
            val y = probabilityY(r)
            drawLine(THRESHOLD_LINE, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        }

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
