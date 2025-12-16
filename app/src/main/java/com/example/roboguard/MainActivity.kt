package com.example.roboguard

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
                        QRCodeScreen()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startService(android.content.Intent(this, RobotServerService::class.java))
    }
}

@Composable
fun QRCodeScreen() {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        val publicKey = loadServerPublicKey(context)
        if (publicKey != null) {
            val authentification = Authentification(publicKey)
            qrBitmap = generateQRCode(authentification.createAuthMessage())
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier.size(500.dp)
            )
        } ?: run {
            Text(text = "Generating QR code...")
        }
    }
}

// --- Helper functions ---

fun loadServerPublicKey(context: android.content.Context): String? {
    val keyStoreFile = File(context.filesDir, "server.p12")
    if (!keyStoreFile.exists()) return null

    val password = KeyStorePasswordManager.getOrCreatePassword(context)
        .toString(Charsets.UTF_8).toCharArray()

    val keyStore = KeyStore.getInstance("PKCS12").apply {
        FileInputStream(keyStoreFile).use { load(it, password) }
    }

    val cert = keyStore.getCertificate("serverkey") as? X509Certificate
    Log.i("Certificate", "Loaded publickey")
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

