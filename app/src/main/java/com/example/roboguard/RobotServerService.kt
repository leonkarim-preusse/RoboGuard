package com.example.roboguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
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
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.InternalSerializationApi
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
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.security.auth.x500.X500Principal

/* ---------- Serializable DTOs matching Phone App ---------- */

/**
 * Settings for a specific room.
 * @property name Name of the room.
 * @property sensors Map of sensor names to their enabled status.
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class RoomSettings(
    val name: String,
    val sensors: Map<String, Boolean>
)

/**
 * Global application and robot settings.
 * @property sensors Global sensor status.
 * @property rooms List of per-room settings.
 * @property situationalSettings Toggles for specific modes (e.g., Pixelate Objects).
 * @property sleepTime Configuration for the sleep timer.
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class AppSettings(
    val sensors: Map<String, Boolean>,
    val rooms: List<RoomSettings>,
    val situationalSettings: Map<String, Boolean>,
    val sleepTime: String
)

/**
 * Defines the physical and logical capabilities of the robot.
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class RobotCapabilities(
    val sensors: List<String>,
    val rooms: List<String>,
    val situational: List<String>
)

/**
 * Data transfer object for authentication credentials returned to the client.
 */
@OptIn(InternalSerializationApi::class)
@Serializable
data class AuthCred(val id: Long, val secret: String)

/* ---------- File Helpers ---------- */

/**
 * Returns the file location for storing robot capabilities.
 */
internal fun getCapabilitiesFile(context: Context): File {
    val directory = File(context.filesDir, "RoboSettings")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "capabilities.json")
}

/**
 * Returns the file location for storing privacy settings.
 */
internal fun getSettingsFile(context: Context): File {
    val directory = File(context.filesDir, "RoboSettings")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "privacy_settings.json")
}

/* ---------- Robot Server Service ---------- */

/**
 * The core background service that runs the RoboGuard HTTPS server and handles NSD.
 * This service runs as a foreground service to ensure it remains active.
 */
class RobotServerService : Service() {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var robotHostname: String = ""

    /** Binder for local activity communication. */
    inner class LocalBinder : Binder() {
        fun getService(): RobotServerService = this@RobotServerService
    }

    private val binder = LocalBinder()
    private val HTTPS_KEY_ALIAS = "https"
    private var server: ApplicationEngine? = null
    lateinit var authentification: Authentification

    /** JSON configuration for serialization. */
    private val jsonConfig = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
        encodeDefaults = true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        // Ensure a unique robot ID exists for hostname generation
        val sharedPrefs = getSharedPreferences("robot_prefs", Context.MODE_PRIVATE)
        var robotId = sharedPrefs.getString("robot_id", null)
        if (robotId == null) {
            robotId = (1..12).map { (0..9).random() }.joinToString("")
            sharedPrefs.edit().putString("robot_id", robotId).apply()
        }
        robotHostname = "robot-$robotId"

        // Setup notification channel for foreground service
        val channelId = "robot_server_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelId, "RoboGuard Server", NotificationManager.IMPORTANCE_LOW))

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("RoboGuard Server")
            .setContentText("Host: $robotHostname.local")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        // Register BouncyCastle for certificate generation
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        // Initialize HTTPS keystore and authentication module
        val (httpsKS, _) = loadHttpsKeyStore(applicationContext)
        val cert = httpsKS.getCertificate(HTTPS_KEY_ALIAS) as X509Certificate
        val pubKeyBase64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)

        authentification = Authentification(pubKeyBase64, "$robotHostname.local")

        // Start networking components
        registerMdnsService(8443)
        startKtorServer()
        startForeground(1, notification)
    }

    /**
     * Registers the robot via mDNS (Network Service Discovery).
     * Allows clients to find the robot as 'robot-ID.local' without IP addresses.
     */
    private fun registerMdnsService(port: Int) {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = "_http._tcp."
            serviceName = robotHostname
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i("NSD", "Registered mDNS: ${info.serviceName}.local")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) {
                Log.e("NSD", "mDNS Failed: $err")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {}
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Initializes and starts the Ktor HTTPS server.
     * Configures endpoints for authentication, capability discovery, and settings management.
     */
    private fun startKtorServer() {
        try {
            val (loadedKeyStore, passwordCharArray) = loadHttpsKeyStore(applicationContext)

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
                    install(ContentNegotiation) { json(jsonConfig) }

                    routing {
                        // Health check endpoint
                        get("/ping") { call.respondText("alive") }

                        // Initial pairing endpoint using OTP
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
                            } catch (e: SecurityException) {
                                call.respond(HttpStatusCode.Unauthorized)
                            }
                        }

                        // Authenticated retrieval of robot capabilities
                        secureGet("/capabilities", applicationContext) {
                            val file = getCapabilitiesFile(applicationContext)
                            if (file.exists()) {
                                call.respondText(file.readText(), ContentType.Application.Json)
                            } else {
                                // Provide default capabilities if no file exists
                                val default = RobotCapabilities(
                                    sensors = listOf("Camera", "LIDAR", "Ultrasonic", "Collision", "Microphone"),
                                    rooms = listOf("Living Room", "Kitchen", "Bedroom", "Bath", "Other"),
                                    situational = listOf("Discretion Mode", "Pixelate Objects")
                                )
                                call.respond(default)
                            }
                        }

                        // Local-only update of capabilities (intended for robot hardware logic)
                        post("/update_capabilities") {
                            val remoteHost = call.request.local.remoteHost
                            if (remoteHost != "127.0.0.1" && remoteHost != "localhost") {
                                call.respond(HttpStatusCode.Forbidden, "Local applications only.")
                                return@post
                            }
                            try {
                                val payload = call.receiveText()
                                jsonConfig.decodeFromString<RobotCapabilities>(payload)
                                getCapabilitiesFile(applicationContext).writeText(payload)
                                call.respond(HttpStatusCode.OK, "Capabilities Updated")
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.BadRequest, "Invalid Format")
                            }
                        }

                        // Authenticated saving of privacy settings
                        securePost("/save", applicationContext) { payload ->
                            try {
                                jsonConfig.decodeFromString<AppSettings>(payload)
                                getSettingsFile(applicationContext).writeText(payload)
                                this@RobotServerService.notification("Settings Saved, check RoboGuard App for details!")
                                this@RobotServerService.showPopup("Privacy Settings Saved")
                                call.respondText("OK")
                            } catch (e: Exception) {
                                Log.e("Server", "Failed to save settings: $e")
                                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
                            }
                        }
                    }
                }
            }
            this.server = embeddedServer(Netty, env) {
                requestQueueLimit = 16
                runningLimit = 10
                shareWorkGroup = true
            }.start(wait = false)
        } catch (e: Exception) {
            Log.e("Server", "Netty start failed: ${e.message}")
        }
    }

    /**
     * Loads or generates a PKCS12 keystore for HTTPS.
     * Uses BouncyCastle to generate a self-signed RSA certificate with SAN (Subject Alternative Name).
     *
     * @param context Android context.
     * @return A Pair containing the KeyStore and its password.
     */
    private fun loadHttpsKeyStore(context: Context): Pair<KeyStore, CharArray> {
        val passwordBytes = PasswordManager.loadPassword(context) ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            PasswordManager.savePassword(it, context)
        }
        val password = Base64.encodeToString(passwordBytes, Base64.NO_WRAP).toCharArray()
        val ksFile = File(context.filesDir, "https_keystore.p12")
        val keyStore = KeyStore.getInstance("PKCS12")

        if (ksFile.exists()) {
            try {
                ksFile.inputStream().use { keyStore.load(it, password) }
                return keyStore to password
            } catch (e: Exception) { ksFile.delete() }
        }

        // Generate new keypair and certificate if keystore doesn't exist
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val robotIP = getIP() ?: "127.0.0.1"

        val certBuilder = JcaX509v3CertificateBuilder(
            X500Principal("CN=RoboGuard"),
            BigInteger.valueOf(System.currentTimeMillis()),
            Date(),
            Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000),
            X500Principal("CN=RoboGuard"),
            keyPair.public
        )

        // Add IP and DNS SAN for connectivity reliability
        val sanList = mutableListOf<GeneralName>()
        sanList.add(GeneralName(GeneralName.iPAddress, robotIP))
        sanList.add(GeneralName(GeneralName.dNSName, "$robotHostname.local"))

        val san = GeneralNames(sanList.toTypedArray())
        certBuilder.addExtension(Extension.subjectAlternativeName, false, san)

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(Security.getProvider("AndroidOpenSSL") ?: Security.getProvider("BC"))
            .build(keyPair.private)

        val x509Cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        keyStore.load(null, null)
        keyStore.setKeyEntry(HTTPS_KEY_ALIAS, keyPair.private, password, arrayOf(x509Cert))
        ksFile.outputStream().use { keyStore.store(it, password) }

        return keyStore to password
    }

    /** Helper for defining POST routes that require HMAC authentication. */
    private fun Route.securePost(path: String, context: Context, body: suspend PipelineContext<Unit, ApplicationCall>.(String) -> Unit) {
        post(path) {
            val payload = call.receiveText()
            if (!call.requireClientAuth(context, payload)) return@post
            body(payload)
        }
    }

    /** Helper for defining GET routes that require HMAC authentication. */
    private fun Route.secureGet(path: String, context: Context, body: suspend PipelineContext<Unit, ApplicationCall>.() -> Unit) {
        get(path) {
            if (!call.requireClientAuth(context, "")) return@get
            body()
        }
    }

    /**
     * Extension for verifying client authenticity using HMAC headers.
     */
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

    /**
     * Reads current settings from storage or returns defaults if file not found.
     */
    fun getCurrentSettings(): AppSettings {
        val file = getSettingsFile(applicationContext)
        if (!file.exists()) return getDefaultSettings()
        return try {
            jsonConfig.decodeFromString<AppSettings>(file.readText())
        } catch (e: Exception) {
            getDefaultSettings()
        }
    }

    /**
     * Defines the default privacy profile for the robot.
     */
    private fun getDefaultSettings(): AppSettings {
        val sensorNames = listOf("Camera", "LIDAR", "Ultrasonic", "Collision", "Microphone")
        val roomNames = listOf("Living Room", "Kitchen", "Bedroom", "Bath", "Other")
        val situationalNames = listOf("Discretion Mode", "pixelate objects")

        val sensors = sensorNames.associateWith { true }
        val situational = situationalNames.associateWith { false }
        val rooms = roomNames.map { name -> RoomSettings(name, sensors) }

        return AppSettings(
            sensors = sensors,
            rooms = rooms,
            situationalSettings = situational,
            sleepTime = "Dont"
        )
    }

    override fun onDestroy() {
        server?.stop(1000, 2000)
        super.onDestroy()
    }

    /**
     * Launches a PopupActivity to display a transient message to the user.
     */
    fun showPopup(message: String) {
        val intent = Intent(this, PopupActivity::class.java).apply {
            putExtra("message", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * Resolves the device's current IPv4 address.
     */
    private fun getIP(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    /**
     * Posts a system notification for robot status updates.
     */
    private fun notification(message: String) {
        val channelId = "robot_status_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "RoboGuard Status", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)

        val builder = Notification.Builder(this, channelId)
            .setContentTitle("RoboGuard")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
