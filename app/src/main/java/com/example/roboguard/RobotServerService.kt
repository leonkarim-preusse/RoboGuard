package com.example.roboguard

// Ktor 2.3.12 Imports

// QR Code

// Serialization

// Security

// Für die Keystore-Korrektur (Signatur-Details)
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.engine.EngineSSLConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.Serializable
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
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

        val CHANNEL_ID = "robot_server_channel"

        // Notification Channel ist ab Android 8 (API 26) Pflicht!
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RoboGuard Server Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RoboGuard Server")
            .setContentText("Server läuft im Hintergrund...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

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

                    val id = authentification.authHandshake(otp, clientName, applicationContext)
                    val secretBytes = Authentification.getSharedSecret(id.toInt(), applicationContext)
                    val secret = Base64.encodeToString(secretBytes, Base64.NO_WRAP)

                    call.respond(HttpStatusCode.OK, AuthCred(id, secret))
                }

                get("/ping") {
                    call.respondText("alive")
                }

                securePost("/save", applicationContext) {
                    saveToFile(call.receiveText())
                    call.respondText("OK")
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

        Log.i("Server", "Ktor 2.3.12 läuft stabil auf Port 8443")
    }

    /* ---------- Helpers ---------- */

    private fun saveToFile(data: String) {
        File(filesDir, "robot_data.txt").appendText("$data\n")
    }

    /* ---------- AndroidKeyStore RSA (Auth / QR only) ---------- */

    fun createOrGetAuthCertificate(alias: String = "serverAuthKey"): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (ks.containsAlias(alias)) {
            val cert = ks.getCertificate(alias) as X509Certificate
            return cert
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()

        kpg.initialize(spec)
        val kp = kpg.generateKeyPair()

        // Zertifikat erstellen
        val now = Date()
        val validity = Date(now.time + 365L * 24 * 60 * 60 * 1000)

        // WICHTIG: Hier keinen Provider "BC" für das Signing erzwingen!
        // Der AndroidKeyStore Key muss von seinem eigenen Provider signiert werden.
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)

        val certHolder = JcaX509v3CertificateBuilder(
            X500Principal("CN=RoboGuard Auth"),
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            validity,
            X500Principal("CN=RoboGuard Auth"),
            kp.public
        ).build(signer)

        return JcaX509CertificateConverter().getCertificate(certHolder)
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
        body: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit
    ) {
        post(path) {
            if (!call.requireClientAuth(context)) return@post
            body(Unit)
        }
    }

    private suspend fun ApplicationCall.requireClientAuth(context: Context): Boolean {
        val id = request.headers["X-Client-Id"]?.toIntOrNull()
        val secret = request.headers["X-Client-Secret"]

        if (id == null || secret == null) {
            respond(HttpStatusCode.Unauthorized)
            return false
        }

        if (!Authentification.authenticate(context, id, secret)) {
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
    fun showPopup(message: String) {
        // UI-Operationen müssen auf dem Main-Looper laufen
        Handler(Looper.getMainLooper()).post {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // Korrekt für Android 8+
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100 // Abstand von oben
            }

            val textView = TextView(this).apply {
                text = message
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                setPadding(40, 20, 40, 20)
                elevation = 10f
            }

            try {
                windowManager.addView(textView, params)

                // Nach 5 Sekunden automatisch entfernen
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        windowManager.removeView(textView)
                    } catch (e: Exception) {
                        Log.e("Popup", "Fehler beim Entfernen: ${e.message}")
                    }
                }, 5000)

            } catch (e: Exception) {
                Log.e("Popup", "Konnte Fenster nicht hinzufügen: ${e.message}")
            }
        }
    }
}
