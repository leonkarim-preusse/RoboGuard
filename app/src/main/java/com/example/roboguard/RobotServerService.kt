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
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
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

/* ---------- Service ---------- */

class RobotServerService : Service() {

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RobotServerService = this@RobotServerService
    }

    private val binder = LocalBinder()

    private val HTTPS_KEYSTORE_FILE = "https_keystore.p12"
    private val HTTPS_KEY_ALIAS = "https"

    private var server: EmbeddedServer<*, *>? = null
    lateinit var authentification: Authentification

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        // AndroidKeyStore RSA-Key für Auth / QR (NICHT TLS!)
        val cert = createOrGetAuthCertificate()
        val pub = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)

        authentification = Authentification(pub, null)
        startKtorServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    /* ---------- Ktor HTTPS Server (CIO) ---------- */

    private fun startKtorServer() {
        val (keyStore, password) = loadHttpsKeyStore(applicationContext)

        val kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        ).apply {
            init(keyStore, password)
        }

        server = embeddedServer(
            factory = CIO,
            port = 8443,
            host = "0.0.0.0",
            module = {
                install(ContentNegotiation) { json() }
                install(CallLogging)

                routing {
                    post("/otp_auth") {
                        val otp = call.request.headers["X-Client-otp"]
                        val clientName = call.request.headers["X-Client-name"]

                        if (otp.isNullOrBlank() || clientName.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        val id = authentification.authHandshake(
                            otp,
                            clientName,
                            applicationContext
                        )

                        val secretBytes =
                            Authentification.getSharedSecret(id.toInt(), applicationContext)

                        val secret =
                            Base64.encodeToString(secretBytes, Base64.NO_WRAP)

                        call.respond(HttpStatusCode.OK, AuthCred(id, secret))
                    }

                    get("/ping") {
                        call.respondText("alive")
                    }

                    securePost("/save", applicationContext) {
                        saveToFile(receiveText())
                        respondText("OK")
                    }
                }
            }
        ) {
            https {
                keyManagerFactory = kmf
            }
        }

        server?.start(wait = false)
        authentification.ip = getDeviceIp()

        Log.i("Server", "CIO HTTPS running on ${authentification.ip}:8443")
    }


    /* ---------- Helpers ---------- */

    private fun saveToFile(data: String) {
        File(filesDir, "robot_data.txt").appendText("$data\n")
    }

    private fun getDeviceIp(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "No network"
        val lp = cm.getLinkProperties(network) ?: return "No network"

        return lp.linkAddresses.firstOrNull {
            it.address.hostAddress?.contains(".") == true &&
                    it.address.hostAddress != "127.0.0.1"
        }?.address?.hostAddress ?: "Unknown"
    }

    /* ---------- AndroidKeyStore RSA (Auth / QR only) ---------- */

    private fun createOrGetAuthCertificate(alias: String = "serverAuthKey"): X509Certificate {
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

        return JcaX509CertificateConverter().getCertificate(cert)
    }

    /* ---------- HTTPS PKCS12 ---------- */

    private fun loadHttpsKeyStore(context: Context): Pair<KeyStore, CharArray> {

        val passwordBytes =
            PasswordManager.loadPassword(context)
                ?: ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    PasswordManager.savePassword(it, context)
                }

        val password = Base64.encodeToString(passwordBytes, Base64.NO_WRAP).toCharArray()
        val ksFile = File(context.filesDir, HTTPS_KEYSTORE_FILE)

        val keyStore = KeyStore.getInstance("PKCS12")

        if (ksFile.exists()) {
            ksFile.inputStream().use { keyStore.load(it, password) }
            return keyStore to password
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()

        val now = Date()
        val cert = JcaX509v3CertificateBuilder(
            X500Principal("CN=RoboGuard HTTPS"),
            BigInteger(64, SecureRandom()),
            now,
            Date(now.time + 365L * 24 * 60 * 60 * 1000),
            X500Principal("CN=RoboGuard HTTPS"),
            keyPair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))

        keyStore.load(null, null)
        keyStore.setKeyEntry(
            HTTPS_KEY_ALIAS,
            keyPair.private,
            password,
            arrayOf(JcaX509CertificateConverter().getCertificate(cert))
        )

        ksFile.outputStream().use { keyStore.store(it, password) }
        return keyStore to password
    }

    /* ---------- Secure Routing ---------- */

    fun Route.securePost(
        path: String,
        context: Context,
        body: suspend ApplicationCall.() -> Unit
    ) {
        post(path) {
            if (!call.requireClientAuth(context)) return@post
            body()
        }
    }

    suspend fun ApplicationCall.requireClientAuth(context: Context): Boolean {
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
}
