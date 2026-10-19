package com.example.robocontrol.vision

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.robocontrol.text.UiText

/**
 * Popup shown by [CalendarMonitor] when the calendar was recognised (owner, 2026-10-19): "Blur calendar", "Save dates" and
 * "Dismiss". Blur and Save do nothing yet. Dismiss (or closing it any other way) stops calendar announcements for
 * [CalendarDetectionSettings.POPUP_COOLDOWN_MS], except while the camera view is open.
 */
class CalendarPromptActivity : ComponentActivity() {

    private var choice = CalendarMonitor.PopupChoice.DISMISS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen texts come from assets/texts/texts.json (no-op if the service already loaded them).
        UiText.init(applicationContext)
        setFinishOnTouchOutside(false)
        setContent { MaterialTheme { Prompt() } }
        // After setContent: the dialog theme would otherwise keep the window narrow.
        window.setLayout((resources.displayMetrics.widthPixels * 0.8).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroy() {
        if (isFinishing) CalendarMonitor.popupClosed(choice)
        super.onDestroy()
    }

    private fun close(chosen: CalendarMonitor.PopupChoice) {
        choice = chosen
        finish()
    }

    @Composable
    private fun Prompt() {
        val padding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        val height = 44.dp
        // The robot asks the question aloud; the popup only needs a short title and the choices.
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(UiText.get("calendar_popup.title"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { close(CalendarMonitor.PopupChoice.BLUR) },
                    contentPadding = padding,
                    modifier = Modifier.weight(1f).height(height)
                ) { OneLine(UiText.get("calendar_popup.blur")) }
                Button(
                    onClick = { close(CalendarMonitor.PopupChoice.SAVE_DATES) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White),
                    contentPadding = padding,
                    modifier = Modifier.weight(1f).height(height)
                ) { OneLine(UiText.get("calendar_popup.save_dates")) }
            }
            OutlinedButton(
                onClick = { close(CalendarMonitor.PopupChoice.DISMISS) },
                contentPadding = padding,
                modifier = Modifier.fillMaxWidth().height(height)
            ) { OneLine(UiText.get("calendar_popup.dismiss")) }
        }
    }

    @Composable
    private fun OneLine(text: String) {
        Text(text, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
}
