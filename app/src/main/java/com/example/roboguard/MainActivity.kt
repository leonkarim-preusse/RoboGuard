package com.example.roboguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.roboguard.ui.theme.RoboGuardTheme
import kotlinx.coroutines.delay
import java.security.KeyStore
import java.security.cert.X509Certificate

class MainActivity : ComponentActivity() {

    private var serviceConnection: ServiceConnection? = null
    private var isBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions, 100)
        }
        enableEdgeToEdge()
        setContent {
            RoboGuardTheme {
                Scaffold { paddingValues ->
                    val (robotService, setRobotService) = remember { mutableStateOf<RobotServerService?>(null) }

                    LaunchedEffect(Unit) {
                        val intent = Intent(this@MainActivity, RobotServerService::class.java)

                        // Foregroundservice start
                        startForegroundService(intent)

                        // Connection
                        serviceConnection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                val binder = service as RobotServerService.LocalBinder
                                setRobotService(binder.getService())
                                isBound = true
                                Log.d("MainActivity", "Service connected")
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {
                                setRobotService(null)
                                isBound = false
                            }
                        }

                        // UI communication binder
                        bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        robotService?.let {
                            QRCodeScreen(it)
                        } ?: Text("Waiting for Server...")
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
fun QRCodeScreen(serverService: RobotServerService) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // QR-Code generieren, wenn Authentification verfügbar ist
    LaunchedEffect(serverService) {
        serverService.authentification?.let { auth ->
            qrBitmap= generateQRCode(auth.createAuthMessage())
        }
        var curr_otp = serverService.authentification.otp
        while(true){
            if (serverService.authentification.otp != curr_otp){
                serverService.authentification?.let { auth ->
                    qrBitmap= generateQRCode(auth.createAuthMessage())
                    curr_otp = serverService.authentification.otp
                }
            }
            delay(1000)
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

/* ---------- Helper functions ---------- */

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

fun getServerCertificatePem(): String {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val cert = keyStore.getCertificate("serverAuthKey") as X509Certificate // Alias angepasst auf deinen Service

    val base64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)

    return """
        -----BEGIN CERTIFICATE-----
        $base64
        -----END CERTIFICATE-----
    """.trimIndent()
}