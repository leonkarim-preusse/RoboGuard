package com.example.roboguard

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
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
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
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
            val keyStore = KeyStore.getInstance("PKCS12") // Von JKS auf PKCS12 ändern
            val ksFile = File(context.filesDir, "https_keystore.p12")

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

            val channel = NotificationChannel(
                CHANNEL_ID,
                "RoboGuard Server Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RoboGuard Server")
                .setContentText("Server running in background...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()

            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }

            val (httpsKS, password) = loadHttpsKeyStore(applicationContext)

            val cert = httpsKS.getCertificate(HTTPS_KEY_ALIAS) as X509Certificate
            val pubKeyBase64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)

            authentification = Authentification(pubKeyBase64, getIP())

            startKtorServer()
            startForeground(1, notification)
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
            START_STICKY

        /* ---------- Ktor HTTPS Server  ---------- */

        private fun startKtorServer() {
            try {
                val (loadedKeyStore, passwordCharArray) = loadHttpsKeyStore(applicationContext)
                Log.d("Server", "Starte Netty Engine auf Port 8443...")

                val env = applicationEngineEnvironment {
                    log = org.slf4j.LoggerFactory.getLogger("ktor.application")
                    sslConnector(
                        keyStore = loadedKeyStore,
                        keyAlias = HTTPS_KEY_ALIAS,
                        keyStorePassword = { passwordCharArray },
                        privateKeyPassword = { passwordCharArray }
                    ) {
                        port = 8443
                        host = "0.0.0.0"
                    }

                    module {
                        install(ContentNegotiation) { json() }

                        routing {
                            get("/ping") { call.respondText("alive") }

                            post("/otp_auth") {
                                val otp = call.request.headers["X-Client-otp"]
                                val clientName = call.request.headers["X-Client-name"]
                                Log.d("Auth", "Handshake attempt from $clientName with OTP $otp")
                                if (otp.isNullOrBlank() || clientName.isNullOrBlank()) {
                                    call.respond(HttpStatusCode.BadRequest)
                                    return@post
                                }
                                try {
                                    val id = authentification.authHandshake(otp, clientName, applicationContext)
                                    val secretBytes = Authentification.getSharedSecret(id.toInt(), applicationContext)
                                    val secret = Base64.encodeToString(secretBytes, Base64.NO_WRAP)
                                    call.respond(HttpStatusCode.OK, AuthCred(id, secret))
                                } catch (e: SecurityException) {
                                    Log.e("Auth", "Handshake failed: ${e.message}")
                                    call.respond(HttpStatusCode.Unauthorized)
                                }
                            }

                            securePost("/save", applicationContext) { payload ->
                                try {
                                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "RoboSettings")
                                    if (!directory.exists()) directory.mkdirs()
                                    val configFile = File(directory, "privacy_settings.json")
                                    configFile.writeText(payload)
                                    showPopup(payload, 20000)
                                    Log.i("Server ","Saved Settings: $payload")
                                    call.respondText("OK")
                                } catch (e: Exception) {
                                    Log.e("Server", "Failed to save Privacy settings: $e")
                                    call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
                                }
                            }
                        }
                    }
                }
                this.server = embeddedServer(Netty, env) {
                    // Hier werden die Werte direkt zugewiesen, ohne "configure"
                    requestQueueLimit = 16
                    runningLimit = 10
                    shareWorkGroup = true

                    // Falls du Zugriff auf die Netty-Pipeline brauchst:
                    // (Auf OrionStar oft besser, die Standardwerte zu lassen)
                }.start(wait = false)

                Log.i("Server", "Netty Server aktiv")

            } catch (e: Exception) {
                Log.e("Server", "Netty Start fehlgeschlagen: ${e.message}")
                e.printStackTrace()
            }
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

            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(java.security.Security.getProvider("AndroidOpenSSL") ?: java.security.Security.getProvider("AndroidKeyStoreBCWorkaround"))
                .build(kp.private)

            val ip = getIP() ?: "127.0.0.1"
            val subjectAltNames = GeneralNames(GeneralName(GeneralName.iPAddress, ip))

            val certHolder = JcaX509v3CertificateBuilder(
                X500Principal("CN=RoboGuard Auth"),
                BigInteger.valueOf(System.currentTimeMillis()),
                now,
                validity,
                X500Principal("CN=RoboGuard Auth"),
                kp.public
            )
                .addExtension(Extension.subjectAlternativeName, false, subjectAltNames) // DAS HIER IST NEU
                .build(signer)

            return JcaX509CertificateConverter().getCertificate(certHolder)
        }

        /* ---------- HTTPS PKCS12 ---------- */

        private fun loadHttpsKeyStore(context: Context): Pair<KeyStore, CharArray> {
            // Sicherstellen, dass BC als Provider registriert ist, falls noch nicht geschehen
            if (java.security.Security.getProvider("BC") == null) {
                java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            }

            val passwordBytes = PasswordManager.loadPassword(context)
                ?: ByteArray(32).also {
                    java.security.SecureRandom().nextBytes(it)
                    PasswordManager.savePassword(it, context)
                }

            val password = android.util.Base64.encodeToString(passwordBytes, android.util.Base64.NO_WRAP).toCharArray()
            val ksFile = java.io.File(context.filesDir, "https_keystore.p12")
            val keyStore = java.security.KeyStore.getInstance("PKCS12")

            if (ksFile.exists()) {
                try {
                    ksFile.inputStream().use { keyStore.load(it, password) }
                    return keyStore to password
                } catch (e: Exception) {
                    ksFile.delete() // Falls korrupt, neu erstellen
                }
            }

            val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = java.util.Date()
            val expiry = java.util.Date(now.time + 365L * 24 * 60 * 60 * 1000)
            val serial = java.math.BigInteger(64, java.security.SecureRandom())
            val robotIP = getIP() ?: "127.0.0.1"

            val certBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                javax.security.auth.x500.X500Principal("CN=RoboGuard"),
                serial,
                now,
                expiry,
                javax.security.auth.x500.X500Principal("CN=RoboGuard"),
                keyPair.public
            )

            val san = org.bouncycastle.asn1.x509.GeneralNames(
                org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.iPAddress,
                    robotIP
                )
            )
            certBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false, san)

            // Den Signer so bauen, dass er NICHT den BC-Provider erzwingt
            val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(java.security.Security.getProvider("AndroidOpenSSL") ?: java.security.Security.getProvider("AndroidKeyStoreBCWorkaround"))
                .build(keyPair.private)

            val x509Cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer))

            keyStore.load(null, null)
            keyStore.setKeyEntry(
                HTTPS_KEY_ALIAS,
                keyPair.private,
                password,
                arrayOf(x509Cert)
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
                Log.i("Server", "Received payload: $payload")
                if (!call.requireClientAuth(context, payload)) {
                    Log.e("Server", "Unable to authenticate client, rejecting")
                    return@post
                }
                body(payload)
            }
        }

        private suspend fun ApplicationCall.requireClientAuth(context: Context, payload: String): Boolean {
            val id = request.headers["X-Client-Id"]?.toIntOrNull()
            val signature = request.headers["X-Client-Secret"]

            Log.i("Server", "Received Signature: $signature")

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
        fun showPopup(message: String, durationMS: Long = 5000) {
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

                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            windowManager.removeView(textView)
                        } catch (e: Exception) {
                            Log.e("Popup", "Fehler beim Entfernen: ${e.message}")
                        }
                    }, durationMS)

                } catch (e: Exception) {
                    Log.e("Popup", "Konnte Fenster nicht hinzufügen: ${e.message}")
                }
            }
        }

        private fun getChangedSettings(oldJson: String?, newJson: String): String {
            if (oldJson == null) return "New Settings:\n$newJson"

            return try {
                val oldData = Json.decodeFromString<ConfigRequest>(oldJson)
                val newData = Json.decodeFromString<ConfigRequest>(newJson)
                val changes = mutableListOf<String>()

                // compare
                newData.sensors.forEach { (key, value) ->
                    if (oldData.sensors[key] != value) {
                        changes.add("$key: ${oldData.sensors[key]} -> $value")
                    }
                }

                // Situational Settings
                if (oldData.situationalSettings != newData.situationalSettings) {
                    changes.add("Situations-Modus geändert")
                }

                if (changes.isEmpty()) "No changes"
                else "Changes:\n" + changes.joinToString("\n")
            } catch (e: Exception) {
                "Settings were adjusted, but Format was not recognized"
            }
        }
        private fun getIP(): String? {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (intf in java.util.Collections.list(interfaces)) {
                    for (addr in java.util.Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("IP", "Fehler beim Abrufen der IP", e)
            }
            return null
        }
    }
