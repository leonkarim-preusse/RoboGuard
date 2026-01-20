package com.example.roboguard

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Environment
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log

// Ktor 2.3.12 Imports
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.pipeline.PipelineContext
import io.ktor.server.jetty.*

// QR Code
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder

// Serialization
import kotlinx.serialization.Serializable

// Security
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

/* ---------- DTOs ---------- */

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
data class SituationalSettings(
    val SituationenErkennen: Boolean,
    val ObjekteVerpixeln: Boolean
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class AuthCred(val id: Long, val secret: String)

object SslSettings {
    fun getKeyStore(context: Context): KeyStore {
        val keyStore = KeyStore.getInstance("JKS")
        val ksFile = File(context.filesDir, "https_keystore.jks")

        val passwordBytes = PasswordManager.loadPassword(context)
            ?: throw IllegalStateException("Keystore-Passwort nicht gefunden!")
        val password = Base64.encodeToString(passwordBytes, Base64.NO_WRAP).toCharArray()

        if (!ksFile.exists()) {
            throw FileNotFoundException("JKS Datei unter ${ksFile.absolutePath} nicht gefunden.")
        }

        FileInputStream(ksFile).use {
            keyStore.load(it, password)
        }
        return keyStore
    }

    fun getKeyManagerFactory(context: Context): KeyManagerFactory {
        val passwordBytes = PasswordManager.loadPassword(context)!!
        val password = Base64.encodeToString(passwordBytes, Base64.NO_WRAP).toCharArray()

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(getKeyStore(context), password)
        return kmf
    }
}

/* ---------- Service ---------- */

class RobotServerService : Service() {

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RobotServerService = this@RobotServerService
    }

    private val binder = LocalBinder()
    private val HTTPS_KEY_ALIAS = "https"

    private var server: io.ktor.server.engine.ApplicationEngine? = null
    lateinit var authentification: Authentification

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val cert = createOrGetAuthCertificate()
        val pub = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)

        authentification = Authentification(pub, null)
        startKtorServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    /* ---------- Ktor HTTPS Server (CIO) ---------- */

    private fun startKtorServer() {
        val (loadedKeyStore, passwordCharArray) = loadHttpsKeyStore(applicationContext)

        // Hier Jetty statt CIO oder Netty nutzen
        val srv = embeddedServer(Jetty, port = 8443, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json()
            }
            routing {
                post("/otp_auth") {
                    val otp = call.request.headers["X-Client-otp"]
                    val clientName = call.request.headers["X-Client-name"]

                    if (otp.isNullOrBlank() || clientName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    try {
                        val id = authentification.authHandshake(otp, clientName, applicationContext)
                        val secretBytes = Authentification.getSharedSecret(id.toInt(), applicationContext)
                        val secret = Base64.encodeToString(secretBytes, Base64.NO_WRAP)

                        call.respond(HttpStatusCode.OK, AuthCred(id, secret))
                    }catch(e: SecurityException){
                        Log.w("Security", "Bad OTP, Access denied")
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post

                    }

                }

                get("/ping") {
                    call.respondText("alive")
                }
                securePost("/save", applicationContext) {
                    val jsonString = call.receiveText()

                    // Pfad: /sdcard/Documents/RoboSettings/settings.json
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "RoboSettings")
                    if (!directory.exists()) directory.mkdirs()

                    val configFile = File(directory, "privacy_settings.json")

                    try {
                        configFile.writeText(jsonString)
                        Log.d("Settings", "Saved to: ${configFile.absolutePath}")
                        call.respondText("OK")
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "Could not save settings: ${e.message}")
                    }
                }
            }
        }

        val sslConnector = EngineSSLConnectorBuilder(
            keyStore = loadedKeyStore,
            keyAlias = HTTPS_KEY_ALIAS,
            keyStorePassword = { passwordCharArray },
            privateKeyPassword = { passwordCharArray }
        ).apply {
            this.port = 8443
            this.host = "0.0.0.0"
        }

        val connectorList = srv.environment.connectors as MutableList<EngineConnectorConfig>
        connectorList.clear()
        connectorList.add(sslConnector)

        this.server = srv
        srv.start(wait = false)

        Log.i("Server", "Ktor is running on 8443")
    }

    /* ---------- Helpers ---------- */

    private fun saveToFile(data: String) {
        File(filesDir, "robot_data.txt").appendText("$data\n")
    }

    /* ---------- AndroidKeyStore RSA (Auth / QR only) ---------- */

    fun createOrGetAuthCertificate(alias: String = "serverAuthKey"): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (ks.containsAlias(alias)) {
            return ks.getCertificate(alias) as X509Certificate
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        kpg.initialize(spec)
        val kp = kpg.generateKeyPair()

        val now = Date()
        val cert = JcaX509v3CertificateBuilder(
            X500Principal("CN=RoboGuard Auth"),
            BigInteger(64, SecureRandom()),
            now,
            Date(now.time + 365L * 24 * 60 * 60 * 1000),
            X500Principal("CN=RoboGuard Auth"),
            kp.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))

        // Korrektur: Kein .setProvider("BC"), damit Android Standard-Provider nutzt
        return JcaX509CertificateConverter().getCertificate(cert)
    }

    /* ---------- HTTPS PKCS12 ---------- */

    private fun loadHttpsKeyStore(context: Context): Pair<KeyStore, CharArray> {
        val passwordBytes = PasswordManager.loadPassword(context)
            ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
                PasswordManager.savePassword(it, context)
            }

        val password = Base64.encodeToString(passwordBytes, Base64.NO_WRAP).toCharArray()
        val ksFile = File(context.filesDir, "https_keystore.p12")
        val keyStore = KeyStore.getInstance("PKCS12")

        if (ksFile.exists()) {
            ksFile.inputStream().use { keyStore.load(it, password) }
            return keyStore to password
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Date()
        val cert = JcaX509v3CertificateBuilder(
            X500Principal("CN=RoboGuard"),
            BigInteger(64, SecureRandom()),
            now,
            Date(now.time + 365L * 24 * 60 * 60 * 1000),
            X500Principal("CN=RoboGuard"),
            keyPair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))

        keyStore.load(null, null)
        keyStore.setKeyEntry(
            HTTPS_KEY_ALIAS,
            keyPair.private,
            password,
            // Korrektur: Kein .setProvider("BC"), verhindert "X.509 not found" Crash
            arrayOf(JcaX509CertificateConverter().getCertificate(cert))
        )

        ksFile.outputStream().use { keyStore.store(it, password) }
        return keyStore to password
    }

    /* ---------- Secure Routing ---------- */

    private fun Route.securePost(
        path: String,
        context: Context,
        body: suspend PipelineContext<Unit, ApplicationCall>.(String) -> Unit
    ) {
        post(path) {
            val payload = call.receiveText()
            if (!call.requireClientAuth(context, payload)) return@post
            body(payload)
        }
    }

    private suspend fun ApplicationCall.requireClientAuth(context: Context, payload: String): Boolean {
        val id = request.headers["X-Client-Id"]?.toIntOrNull()
        val signature = request.headers["X-Client-Secret"]

        if (id == null || signature == null) {
            respond(HttpStatusCode.Unauthorized)
            return false
        }

        if (!Authentification.authenticate(context, id, signature, payload)) {
            respond(HttpStatusCode.Unauthorized)
            return false
        }

        return true
    }

    /* ---------- QR ---------- */

    fun couple(): Bitmap {
        val json = authentification.createAuthMessage()
        val matrix = MultiFormatWriter()
            .encode(json, BarcodeFormat.QR_CODE, 512, 512)
        return BarcodeEncoder().createBitmap(matrix)
    }

    override fun onDestroy() {
        server?.stop(1000, 2000)
        super.onDestroy()
    }
}
