package com.example.roboguard

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.roboguard.ui.theme.RoboGuardTheme
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

class MainActivity : ComponentActivity() {

    private var serviceConnection: android.content.ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoboGuardTheme {
                Scaffold { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        val (robotService, setRobotService) = remember { mutableStateOf<RobotServerService?>(null) }

                        // BindService nur einmal starten
                        LaunchedEffect(Unit) {
                            val intent = android.content.Intent(this@MainActivity, RobotServerService::class.java)
                            startService(intent)

                            serviceConnection = object : android.content.ServiceConnection {
                                override fun onServiceConnected(
                                    name: android.content.ComponentName?,
                                    service: android.os.IBinder?
                                ) {
                                    val binder = service as RobotServerService.LocalBinder
                                    setRobotService(binder.getService())
                                }

                                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                                    setRobotService(null)
                                }
                            }

                            bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
                        }

                        // QR-Code anzeigen, sobald Service verfügbar
                        robotService?.let { QRCodeScreen(it) } ?: Text("Starting server...")
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        serviceConnection?.let { unbindService(it) }
    }
}

@Composable
fun QRCodeScreen(serverService: RobotServerService) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // QR-Code generieren, wenn Authentification verfügbar ist
    LaunchedEffect(serverService) {
        serverService.authentification?.let {
            qrBitmap = generateQRCode(it.createAuthMessage())
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.size(500.dp)
            )
        } ?: Text("Generating QR code...")
    }
}

// --- Helper functions ---

fun loadServerPublicKey(): String? {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val cert = keyStore.getCertificate("serverKey") as? X509Certificate
    return cert?.publicKey?.encoded?.let { Base64.getEncoder().encodeToString(it) }
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

