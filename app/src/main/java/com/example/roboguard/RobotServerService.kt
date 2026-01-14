package com.example.roboguard

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConfigRequest(
    val sensors: Map<String, Boolean>,
    val rooms: List<Room>,
    val situationalSettings: SituationalSettings,
    val sleepTime: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Room(val name: String, val sensors: Map<String, Boolean>)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class SituationalSettings(val SituationenErkennen: Boolean, val ObjekteVerpixeln: Boolean)

@Serializable
data class authcred(val id: Long, val secret: String)

class RobotServerService : Service() {
    inner class LocalBinder : android.os.Binder() {
        fun getService(): RobotServerService = this@RobotServerService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    lateinit var authentification: Authentification


    override fun onCreate() {
        super.onCreate()

        // Register BouncyCastle provider (needed on Android)
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        // KeyPair + Self-Signed Zertifikat erzeugen oder laden
        createOrGetKeyStoreCertificate()
        val pub = getServerCertificatePem(); if (pub.isNullOrBlank()){ throw IllegalStateException("Public key not available") }
        authentification = Authentification(pub, null )
        startKtorServer()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startKtorServer() {
        System.setProperty("io.netty.transport.noUnsafe", "true")
        System.setProperty("io.netty.epollDisabled", "true")
        System.setProperty("io.netty.kqueueDisabled", "true")

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        server = embeddedServer(
            factory = Netty,
            module = {
                install(ContentNegotiation) { json() }
                install(CallLogging)

                routing {

                    post("/otp_auth"){
                        val otp = call.request.headers["X-Client-otp"]
                        val clientName = call.request.headers["X-Client-name"]
                        if (otp.isNullOrBlank() || clientName.isNullOrBlank()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                "Missing X-Client-otp or X-Client-name"
                            )
                            return@post
                        }
                        try {

                            val id = authentification.authHandshake(otp, clientName, applicationContext)
                            val secret = Authentification.getSharedSecret(id.toInt(), applicationContext)
                            val authJSON = authcred(id, secret.toString())
                            call.respond(status = HttpStatusCode.OK, Json.encodeToString(authJSON))
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                "Authorization failed"
                            )

                        }




                    }
                    get("/ping") {
                        call.respondText("alive")
                        Log.i("Server", "Ping received")
                    }
                    securePost("/save", context = applicationContext) {
                        val rawJson = receiveText()
                        try {
                            val config = parseConfig(rawJson)
                            Log.i("Server", "Received config with ${config.rooms.size} rooms")
                            saveToFile(rawJson)
                            respondText("OK", status = HttpStatusCode.OK)
                        } catch (e: Exception) {
                            Log.e("Server", "Failed to parse JSON: ${e.message}")
                            respond(HttpStatusCode.BadRequest, "Invalid JSON: ${e.message}")
                        }
                    }

                }
            },
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "serverKey",
                    keyStorePassword = { charArrayOf() },       // Passwort nicht nötig
                    privateKeyPassword = { charArrayOf() }      // Passwort nicht nötig
                ) {
                    port = 8443
                    host = "0.0.0.0"
                }
            }
        )

        server?.start(wait = false)

        val ipAddress = getDeviceIp()
        authentification.ip = ipAddress
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


    private fun parseConfig(rawJson: String): ConfigRequest {
        return Json { ignoreUnknownKeys = true }.decodeFromString(rawJson)
    }

    /**
     * Erstellt oder lädt ein KeyPair + Self-Signed Zertifikat direkt im Android KeyStore
     */
    private fun createOrGetKeyStoreCertificate(alias: String = "serverKey"): X509Certificate {
        Security.addProvider(BouncyCastleProvider())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        // Prüfen, ob Key bereits existiert
        if (keyStore.containsAlias(alias)) {
            val cert = keyStore.getCertificate(alias) as X509Certificate
            cert.checkValidity(Date())
            return cert
        }

        // KeyPair generieren
        val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .build()
        keyPairGenerator.initialize(spec)
        val keyPair = keyPairGenerator.generateKeyPair()

        // Self-signed Zertifikat erzeugen
        val startDate = Date()
        val endDate = Date(startDate.time + 365L * 24 * 60 * 60 * 1000) // 1 Jahr gültig
        val serialNumber = BigInteger(64, SecureRandom())
        val subject = X500Principal("CN=RoboGuard, OU=Universität Göttingen, O=CSP, L=Göttingen, ST=Göttingen, C=DE")

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

        // KeyStore-Entry speichern
        keyStore.setEntry(alias, KeyStore.PrivateKeyEntry(keyPair.private, arrayOf(cert)), null)

        return cert
    }

    /**
     * Public Key des Servers aus KeyStore als Base64
     */
    fun getServerPublicKey(): String? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = keyStore.getCertificate("serverKey") as? X509Certificate
        return cert?.publicKey?.encoded?.let { Base64.encodeToString(it, Base64.DEFAULT) }
    }

    /**
     * QR-Code für Authentifikation erzeugen
     */
    fun copple(): Bitmap {
        val authentification = Authentification(getServerPublicKey() ?: throw IllegalStateException("Public key not available"), getDeviceIp())
        val jsonQR = authentification.createAuthMessage()
        return generateQRCode(jsonQR)
    }

    fun generateQRCode(json: String, size: Int = 512): Bitmap {
        val bitMatrix = MultiFormatWriter().encode(json, BarcodeFormat.QR_CODE, size, size)
        val barcodeEncoder = BarcodeEncoder()
        return barcodeEncoder.createBitmap(bitMatrix)
    }
    fun Route.securePost(
        path: String,
        context: Context,
        body: suspend ApplicationCall.() -> Unit
    ) {
        post(path) {
            if (!call.requireClientAuth(context)) return@post
            call.run { body() }
        }
    }

    fun Route.secureGet(
        path: String,
        context: Context,
        body: suspend ApplicationCall.() -> Unit
    ) {
        get(path) {
            if (!call.requireClientAuth(context)) return@get
            call.run { body() }
        }
    }

    suspend fun ApplicationCall.requireClientAuth(
        context: Context
    ): Boolean {

        val clientId = request.headers["X-Client-Id"]?.toIntOrNull()
        val clientSecret = request.headers["X-Client-Secret"]

        if (clientId == null || clientSecret == null) {
            respond(HttpStatusCode.Unauthorized, "Missing auth headers")
            return false
        }

        if (!Authentification.authenticate(context, clientId, clientSecret)) {
            respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            return false
        }

        return true
    }

}

