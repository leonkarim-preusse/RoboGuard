package com.example.roboguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.roboguard.ui.theme.RoboGuardTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var serviceConnection: ServiceConnection? = null
    private var isBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoboGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    val (robotService, setRobotService) = remember { mutableStateOf<RobotServerService?>(null) }
                    var showSettings by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        val intent = Intent(this@MainActivity, RobotServerService::class.java)
                        startForegroundService(intent)

                        serviceConnection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                val binder = service as RobotServerService.LocalBinder
                                setRobotService(binder.getService())
                                isBound = true
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {
                                setRobotService(null)
                                isBound = false
                            }
                        }
                        bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        robotService?.let { service ->
                            if (showSettings) {
                                SettingsListScreen(service) { showSettings = false }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Scan with the RoboGuard App to copple!",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).widthIn(max = 400.dp)
                                    )

                                    // Moved weight here to ensure it's in ColumnScope
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        QRCodeDisplay(service)
                                    }

                                    Button(
                                        onClick = { showSettings = true },
                                        modifier = Modifier
                                            .fillMaxWidth(0.8f)
                                            .padding(bottom = 16.dp)
                                    ) {
                                        Text("Show Current Settings", fontSize = 16.sp)
                                    }
                                }
                            }
                        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Waiting for Server...")
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound && serviceConnection != null) {
            try {
                unbindService(serviceConnection!!)
                isBound = false
            } catch (e: Exception) {
                Log.e("MainActivity", "Error unbinding: ${e.message}")
            }
        }
    }
}

/* ---------- UI Komponenten ---------- */

@Composable
fun QRCodeDisplay(service: RobotServerService) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(service) {
        val auth = service.authentification
        qrBitmap = generateQRCode(auth.createAuthMessage())
        var currOtp = auth.otp
        while (true) {
            if (auth.otp != currOtp) {
                qrBitmap = generateQRCode(auth.createAuthMessage())
                currOtp = auth.otp
            }
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .padding(8.dp)
            .aspectRatio(1f)
            .sizeIn(maxWidth = 450.dp, maxHeight = 450.dp),
        contentAlignment = Alignment.Center
    ) {
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.fillMaxSize()
            )
        } ?: CircularProgressIndicator()
    }
}

@Composable
fun SettingsListScreen(service: RobotServerService, onBack: () -> Unit) {
    val settings = service.getCurrentSettings()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Robot Privacy Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text("General", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SettingRow(name = "Sleep Timer", value = settings.sleepTime)
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Text("Global Sensors", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            items(settings.sensors.toList()) { (sensor, enabled) ->
                SettingRow(name = sensor, value = if (enabled) "ON" else "OFF")
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Text("Situational Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            items(settings.situationalSettings.toList()) { (setting, enabled) ->
                SettingRow(name = setting, value = if (enabled) "ON" else "OFF")
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Text("Room Constraints", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            settings.rooms.forEach { room ->
                item {
                    Text(room.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    room.sensors.forEach { (sensor, enabled) ->
                        SettingRow(name = "  $sensor", value = if (enabled) "ALLOWED" else "BLOCKED", isSmall = true)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingRow(name: String, value: String, isSmall: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, fontSize = if (isSmall) 14.sp else 16.sp)
        Text(
            text = value,
            fontSize = if (isSmall) 14.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = when(value) {
                "ON", "ALLOWED" -> Color(0xFF4CAF50)
                "OFF", "BLOCKED" -> Color.Red
                else -> Color.Black
            }
        )
    }
}

fun generateQRCode(json: String, size: Int = 1024): Bitmap {
    val bitMatrix = com.google.zxing.MultiFormatWriter().encode(
        json,
        com.google.zxing.BarcodeFormat.QR_CODE,
        size,
        size
    )
    val barcodeEncoder = com.journeyapps.barcodescanner.BarcodeEncoder()
    Log.i("Certificate", "Generated QR code")
    return barcodeEncoder.createBitmap(bitMatrix)
}
