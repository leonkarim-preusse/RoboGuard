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
import com.example.robocontrol.movement.MapNavigationActivity
import com.example.robocontrol.system.DefaultAppSetting
import com.example.robocontrol.text.UiText
import kotlinx.coroutines.delay

/**
 * The primary entry point for the RoboGuard Robot application.
 * This Activity displays the pairing QR code and allows the user to view 
 * current privacy settings applied to the robot.
 */
class MainActivity : ComponentActivity() {

    private var serviceConnection: ServiceConnection? = null
    private var isBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen texts come from assets/texts/texts.json (no-op if the service already loaded them).
        UiText.init(applicationContext)
        // Make RoboGuard the robot's default app, so RobotOS gives it SDK control whenever it is in the foreground
        // (not only after a launch from the home screen). Only writes if not already set; see DefaultAppSetting.
        DefaultAppSetting.ensureRoboGuardIsDefault(this)
        enableEdgeToEdge()
        setContent {
            RoboGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    // State to hold the reference to the bound service
                    val (robotService, setRobotService) = remember { mutableStateOf<RobotServerService?>(null) }
                    // State to toggle between the QR code view and settings list
                    var showSettings by remember { mutableStateOf(false) }

                    // Start and bind the RobotServerService on launch
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
                                        text = UiText.get("app.qr.hint"),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).widthIn(max = 400.dp)
                                    )

                                    // QR Code display area
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        QRCodeDisplay(service)
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth(0.8f)
                                            .padding(bottom = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = { showSettings = true },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(UiText.get("app.button.settings"), fontSize = 16.sp)
                                        }
                                        // Opens the map, driving and private areas screen from robocontrol.movement
                                        Button(
                                            onClick = { startActivity(Intent(this@MainActivity, MapNavigationActivity::class.java)) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(UiText.get("app.button.navigation"), fontSize = 16.sp)
                                        }
                                    }
                                }
                            }
                        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(UiText.get("app.waiting_for_server"))
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Unbind from the service to avoid leaks when activity is stopped
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

/* ---------- UI Components ---------- */

/**
 * Composable that displays a QR code for client pairing.
 * The QR code content is dynamically refreshed whenever the service's OTP changes.
 */
@Composable
fun QRCodeDisplay(service: RobotServerService) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(service) {
        val auth = service.authentification
        // Initial QR generation
        qrBitmap = generateQRCode(auth.createAuthMessage())
        var currOtp = auth.otp
        
        // Loop to check for OTP updates and refresh the QR code
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
                contentDescription = UiText.get("app.qr.description"),
                modifier = Modifier.fillMaxSize()
            )
        } ?: CircularProgressIndicator()
    }
}

/**
 * Screen displaying the robot's current privacy settings.
 * Shows general settings, global sensor status, situational modes, and room-specific constraints.
 */
@Composable
fun SettingsListScreen(service: RobotServerService, onBack: () -> Unit) {
    val settings = service.getCurrentSettings()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text(UiText.get("settings.back")) }
            Spacer(modifier = Modifier.width(12.dp))
            Text(UiText.get("settings.title"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(UiText.get("settings.section.general"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SettingRow(name = UiText.get("settings.sleep_timer"), value = settings.sleepTime)
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Text(UiText.get("settings.section.sensors"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            items(settings.sensors.toList()) { (sensor, enabled) ->
                SettingRow(name = sensor, value = if (enabled) UiText.get("settings.value.on") else UiText.get("settings.value.off"))
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Text(UiText.get("settings.section.situational"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            items(settings.situationalSettings.toList()) { (setting, enabled) ->
                SettingRow(name = setting, value = if (enabled) UiText.get("settings.value.on") else UiText.get("settings.value.off"))
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                Text(UiText.get("settings.section.rooms"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            settings.rooms.forEach { room ->
                item {
                    Text(room.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    room.sensors.forEach { (sensor, enabled) ->
                        SettingRow(name = "  $sensor", value = if (enabled) UiText.get("settings.value.allowed") else UiText.get("settings.value.blocked"), isSmall = true)
                    }
                }
            }
        }
    }
}

/**
 * Simple row component to display a single setting's name and value with status-aware coloring.
 */
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
            // Compared against the texts themselves, so the colours still fit after a wording or language change.
            color = when (value) {
                UiText.get("settings.value.on"), UiText.get("settings.value.allowed") -> Color(0xFF4CAF50) // Green
                UiText.get("settings.value.off"), UiText.get("settings.value.blocked") -> Color.Red
                else -> Color.Black
            }
        )
    }
}

/**
 * Generates a QR code bitmap from a given string.
 * Uses ZXing for encoding.
 * 
 * @param json The string content for the QR code.
 * @param size The width and height of the resulting bitmap in pixels.
 * @return A Bitmap object of the generated QR code.
 */
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
