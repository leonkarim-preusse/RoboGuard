package com.example.robocontrol.conversation

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.robocontrol.movement.MapNavigationActivity

/**
 * Popup shown by [ConversationMonitor] when more than one person has been talking: offers to leave the room (opens
 * Navigation and Map), to switch the microphone off, or to pause the conversation detection (default 30 minutes, or a
 * chosen time). Closing it changes nothing.
 */
class ConversationPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContent { MaterialTheme { Prompt() } }
        // After setContent: the dialog theme would otherwise keep the window narrow.
        window.setLayout((resources.displayMetrics.widthPixels * 0.8).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroy() {
        ConversationMonitor.promptClosed()
        super.onDestroy()
    }

    @Composable
    private fun Prompt() {
        var minutes by remember { mutableStateOf(ConversationMonitor.DEFAULT_PAUSE_MINUTES) }
        var choosingTime by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }
        val padding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        val height = 44.dp

        // The robot says the full sentence; the popup only needs a short title and the choices.
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Conversation detected", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!choosingTime) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            startActivity(Intent(this@ConversationPromptActivity, MapNavigationActivity::class.java))
                            finish()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White),
                        contentPadding = padding,
                        modifier = Modifier.weight(1f).height(height)
                    ) { OneLine("Leave room", 15) }
                    Button(
                        onClick = {
                            ConversationMonitor.microphoneOff()
                            finish()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                        contentPadding = padding,
                        modifier = Modifier.weight(1f).height(height)
                    ) { OneLine("Mute mic", 15) }
                }
                Button(
                    onClick = {
                        ConversationMonitor.pauseFor(minutes)
                        finish()
                    },
                    contentPadding = padding,
                    modifier = Modifier.fillMaxWidth().height(height)
                ) { OneLine("Don't ask again for $minutes min", 15) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choosingTime = true }, contentPadding = padding, modifier = Modifier.weight(1f).height(height)) {
                        OneLine("Other time", 15)
                    }
                    OutlinedButton(onClick = { finish() }, contentPadding = padding, modifier = Modifier.weight(1f).height(height)) {
                        OneLine("Close", 15)
                    }
                }
            } else {
                Text("Pause detection for (now $minutes min):", fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 15, 30, 60, 120).forEach { option ->
                        val pick = { minutes = option; choosingTime = false }
                        if (option == minutes) {
                            Button(onClick = pick, contentPadding = padding, modifier = Modifier.weight(1f).height(height)) { OneLine("$option", 15) }
                        } else {
                            OutlinedButton(onClick = pick, contentPadding = padding, modifier = Modifier.weight(1f).height(height)) { OneLine("$option", 15) }
                        }
                    }
                }
                val typedMinutes = typed.toIntOrNull()
                val valid = typedMinutes != null && typedMinutes in 1..MAX_MINUTES
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.filter(Char::isDigit).take(4) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        isError = typed.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Button(enabled = valid, onClick = { minutes = typedMinutes!!; choosingTime = false }, contentPadding = padding, modifier = Modifier.height(height)) {
                        OneLine("Set", 15)
                    }
                    OutlinedButton(onClick = { choosingTime = false }, contentPadding = padding, modifier = Modifier.height(height)) {
                        OneLine("Back", 15)
                    }
                }
            }
        }
    }

    @Composable
    private fun OneLine(text: String, sizeSp: Int) {
        Text(text, fontSize = sizeSp.sp, maxLines = 1, softWrap = false)
    }

    private companion object {
        /** Longest pause: one day. */
        const val MAX_MINUTES = 24 * 60
    }
}
