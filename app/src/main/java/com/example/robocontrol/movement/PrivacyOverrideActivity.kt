package com.example.robocontrol.movement

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.example.robocontrol.text.UiText
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Popup on the robot's screen after a drive was stopped at a private area: "Allow robot to temporarily cross <area>?"
 *
 * - green **Yes**: allowed for the chosen time, [DEFAULT_MINUTES] minutes unless changed
 * - red **No**: not allowed (also Back)
 * - **Custom time**: choose 1, 2, 5, 10 or 60 minutes or type minutes (1–60, PrivacyGuard's maximum); this only changes
 *   the time shown on the first page, crossing still needs Yes
 *
 * Question and answer go through [PrivacyOverridePrompt]; the popup closes itself when the question is gone.
 */
class PrivacyOverrideActivity : ComponentActivity() {

    /** The question this popup was opened for; answered at most once. */
    private var requestId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen texts come from assets/texts/texts.json (no-op if the service already loaded them).
        UiText.init(applicationContext)
        // Only the buttons answer; a tap next to the popup must not count as "no".
        setFinishOnTouchOutside(false)
        requestId = PrivacyOverridePrompt.pending.value?.id
        if (requestId == null) {
            finish()
            return
        }
        // The dialog theme's default window is narrow and may cut off content; use most of the robot's screen width.
        setContent { MaterialTheme { Prompt() } }
        // After setContent: the dialog theme sizes the window when content is attached and would keep it narrow.
        window.setLayout((resources.displayMetrics.widthPixels * 0.8).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    @Deprecated("Back = No")
    override fun onBackPressed() {
        answer(null)
    }

    private fun answer(minutes: Int?) {
        requestId?.let { PrivacyOverridePrompt.answer(it, minutes) }
        requestId = null
        finish()
    }

    @Composable
    private fun Prompt() {
        val pending by PrivacyOverridePrompt.pending.collectAsState()
        // Closed elsewhere (cancelled, or replaced by a newer question that opens its own popup).
        LaunchedEffect(pending?.id) {
            if (pending == null || pending?.id != requestId) {
                requestId = null
                finish()
            }
        }
        val zoneName = pending?.zoneName ?: return
        // Chosen duration; "Custom time" only changes it, crossing is still allowed only by pressing Yes.
        var minutes by remember { mutableStateOf(DEFAULT_MINUTES) }
        var showCustom by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(UiText.get("privacy_popup.title", "area" to zoneName), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (!showCustom) {
                Text(UiText.get("privacy_popup.explanation", "minutes" to minutesText(minutes)), fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { answer(minutes) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White),
                        contentPadding = COMPACT_PADDING,
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { OneLine(UiText.get("privacy_popup.yes"), 20) }
                    Button(
                        onClick = { answer(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                        contentPadding = COMPACT_PADDING,
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { OneLine(UiText.get("privacy_popup.no"), 16) }
                }
                OutlinedButton(
                    onClick = { showCustom = true },
                    contentPadding = COMPACT_PADDING,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) { OneLine(UiText.get("privacy_popup.custom_time"), 16) }
            } else {
                Text(UiText.get("privacy_popup.choose_time", "minutes" to minutesText(minutes)), fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_MINUTES.forEach { preset ->
                        val chosen = preset == minutes
                        val select = { minutes = preset; showCustom = false }
                        if (chosen) {
                            Button(onClick = select, contentPadding = COMPACT_PADDING, modifier = Modifier.weight(1f).height(56.dp)) {
                                OneLine(UiText.get("privacy_popup.preset_minutes", "minutes" to preset), 16)
                            }
                        } else {
                            OutlinedButton(onClick = select, contentPadding = COMPACT_PADDING, modifier = Modifier.weight(1f).height(56.dp)) {
                                OneLine(UiText.get("privacy_popup.preset_minutes", "minutes" to preset), 16)
                            }
                        }
                    }
                }
                val typedMinutes = typed.toIntOrNull()
                val valid = typedMinutes != null && typedMinutes in 1..MAX_MINUTES
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.filter(Char::isDigit).take(2) },
                        label = { Text(UiText.get("privacy_popup.custom_label", "max" to MAX_MINUTES)) },
                        singleLine = true,
                        isError = typed.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = valid,
                        onClick = { minutes = typedMinutes!!; showCustom = false },
                        contentPadding = COMPACT_PADDING,
                        modifier = Modifier.height(56.dp)
                    ) { OneLine(UiText.get("privacy_popup.set"), 18) }
                }
                OutlinedButton(onClick = { showCustom = false }, contentPadding = COMPACT_PADDING, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    OneLine(UiText.get("privacy_popup.back"), 18)
                }
            }
        }
    }

    /** Button label that never wraps. */
    @Composable
    private fun OneLine(text: String, sizeSp: Int) {
        Text(text, fontSize = sizeSp.sp, maxLines = 1, softWrap = false)
    }

    private fun minutesText(minutes: Int) =
        if (minutes == 1) UiText.get("privacy_popup.minutes_one") else UiText.get("privacy_popup.minutes_many", "minutes" to minutes)

    override fun onDestroy() {
        // Closed without an answer (e.g. the screen went away): that is a "no", the robot stays stopped.
        if (isFinishing) requestId?.let { PrivacyOverridePrompt.answer(it, null) }
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_MINUTES = 5
        val PRESET_MINUTES = listOf(1, 2, 5, 10, 60)

        /** PrivacyGuard.MAX_OVERRIDE_MILLIS in minutes. */
        val MAX_MINUTES = (PrivacyGuard.MAX_OVERRIDE_MILLIS / 60_000L).toInt()

        /** Small side padding so labels fit inside the buttons. */
        val COMPACT_PADDING = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    }
}
