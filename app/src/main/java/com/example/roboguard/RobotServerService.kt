package com.example.roboguard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.IBinder
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.*

@Serializable
data class ConfigRequest(
    val sensors: Map<String, Boolean>,
    val rooms: List<Room>,
    val situationalSettings: SituationalSettings,
    val sleepTime: String
)

@Serializable
data class Room(val name: String, val sensors: Map<String, Boolean>)

@Serializable
data class SituationalSettings(val SituationenErkennen: Boolean, val ObjekteVerpixeln: Boolean)

class RobotServerService : Service() {

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private lateinit var keyStorePassword: CharArray

    override fun onCreate() {
        super.onCreate()

        // Register BouncyCastle provider (needed on Android)
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        // Generate or load password for KeyStore
        keyStorePassword = KeyStorePasswordManager.getOrCreatePassword(applicationContext)
            .toString(Charsets.UTF_8).toCharArray()

        createOrValidateCert(context = applicationContext)
        startKtorServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startKtorServer() {
        System.setProperty("io.netty.transport.noUnsafe", "true")
        System.setProperty("io.netty.epollDisabled", "true")
        System.setProperty("io.netty.kqueueDisabled", "true")

        val keyStoreFile = File(filesDir, "server.p12")
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(keyStoreFile).use { load(it, keyStorePassword) }
        }

        server = embeddedServer(
            factory = Netty,
            module = {
                install(ContentNegotiation) { json() }
                install(CallLogging)

                routing {
                    get("/ping") {
                        call.respondText("alive")
                        Log.i("Server", "Ping received")
                    }
                    post("/save") {
                        val rawJson = call.receiveText()
                        try {
                            val config = parseConfig(rawJson)
                            Log.i("Server", "Received config with ${config.rooms.size} rooms")
                            saveToFile(rawJson)
                            call.respondText("OK", status = HttpStatusCode.OK)
                        } catch (e: Exception) {
                            Log.e("Server", "Failed to parse JSON: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
                        }
                    }
                }
            },
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "serverkey",
                    keyStorePassword = { keyStorePassword },
                    privateKeyPassword = { keyStorePassword }
                ) {
                    port = 8443
                    host = "0.0.0.0"
                }
            }
        )

        server?.start(wait = false)

        val ipAddress = getDeviceIp()
        Log.i("Server", "Server started on http://$ipAddress:8080 and https://$ipAddress:8443")
    }

    private fun saveToFile(data: String) {
        val file = File(filesDir, "robot_data.txt")
        file.appendText("$data\n")
    }

    private fun getDeviceIp(): String {
        val connectivityManager =
            applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "No network"
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return "No network"
        return linkProperties.linkAddresses.firstOrNull {
            it.address.hostAddress?.contains(".") == true && it.address.hostAddress != "127.0.0.1"
        }?.address?.hostAddress ?: "Unknown IP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseConfig(rawJson: String): ConfigRequest =
        Json { ignoreUnknownKeys = true }.decodeFromString(rawJson)

    fun createOrValidateCert(
        context: Context,
        fileName: String = "server.p12"
    ) {
        val file = File(context.filesDir, fileName)

        // Passwort einmalig generieren oder abrufen
        val password = KeyStorePasswordManager.getOrCreatePassword(context).toString(Charsets.UTF_8).toCharArray()

        var generateNew = true

        if (file.exists()) {
            try {
                val keyStore = KeyStore.getInstance("PKCS12")
                FileInputStream(file).use { keyStore.load(it, password) }

                val cert = keyStore.getCertificate("serverkey") as? X509Certificate
                if (cert != null) {
                    cert.checkValidity(Date()) // prüfen, ob Zertifikat noch gültig ist
                    generateNew = false
                    Log.i("Certificate", "Existing certificate is valid until: ${cert.notAfter}")
                }
            } catch (e: Exception) {
                Log.d("Certificate", "Certificate invalid or expired, regenerating: ${e.message}")
                generateNew = true
            }
        }

        if (generateNew) {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()

            val startDate = Date()
            val endDate = Date(startDate.time + 365L * 24 * 60 * 60 * 1000) // 1 Jahr gültig
            val serialNumber = BigInteger(64, SecureRandom())

            val subject = X500Name("CN=RoboGuard, OU=Robotics, O=MyCompany, L=Berlin, ST=Berlin, C=DE")

            // Build certificate without specifying "BC" provider
            val certBuilder = JcaX509v3CertificateBuilder(
                subject,
                serialNumber,
                startDate,
                endDate,
                subject,
                keyPair.public
            )

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry("serverkey", keyPair.private, password, arrayOf(cert))

            FileOutputStream(file).use { keyStore.store(it, password) }
            Log.i("Certificate", "New certificate generated, valid until: ${cert.notAfter}")
        }
    }


    fun copple(): Bitmap {
        val authentification = Authentification(getServerPublicKey()?: throw IllegalStateException("Public key not available"))
        val jsonQR = authentification.createAuthMessage()
        return generateQRCode(jsonQR)
    }

    fun generateQRCode(json: String, size: Int = 512): Bitmap {
        val bitMatrix = MultiFormatWriter().encode(json, BarcodeFormat.QR_CODE, size, size)
        val barcodeEncoder = BarcodeEncoder()
        return barcodeEncoder.createBitmap(bitMatrix)
    }

    fun getServerPublicKey(): String? {
        val keyStoreFile = File(filesDir, "server.p12")
        val password = KeyStorePasswordManager.getOrCreatePassword(applicationContext)
            .toString(Charsets.UTF_8)
            .toCharArray()

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(keyStoreFile).use { load(it, password) }
        }

        val cert = keyStore.getCertificate("serverkey") as? X509Certificate
        return cert?.publicKey?.encoded?.let { Base64.getEncoder().encodeToString(it) }
    }
}
