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
import com.example.robocontrol.audio.OrionStarTts
import com.example.robocontrol.audio.TtsFailure
import com.example.robocontrol.audio.TtsListener
import com.example.robocontrol.sensorcontrol.Sensors
import com.example.robocontrol.conversation.ConversationMonitor
import com.example.robocontrol.text.UiText
import com.example.robocontrol.movement.NavigationHub
import com.example.robocontrol.movement.navigationCommand
import com.example.robocontrol.movement.navigationMapJson
import com.example.robocontrol.movement.navigationMapPng
import com.example.robocontrol.movement.navigationStateJson
import com.example.robocontrol.vision.CalendarMonitor
import com.ainirobot.coreservice.client.RobotApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // Screen and speech texts come from assets/texts/texts.json; load them before anything can show or say something.
        UiText.init(applicationContext)

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
        manager.createNotificationChannel(NotificationChannel(channelId, UiText.get("service.notification.server_channel"), NotificationManager.IMPORTANCE_LOW))

        val notification = Notification.Builder(this, channelId)
            .setContentTitle(UiText.get("service.notification.server_title"))
            .setContentText(UiText.get("service.notification.server_host", "host" to "$robotHostname.local"))
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
        // Conversation detection: listens while RoboGuard runs (and the privacy settings allow the microphone)
        ConversationMonitor.start(applicationContext)
        // Calendar detection: watches the camera while RoboGuard runs (and the privacy settings allow the camera)
        CalendarMonitor.start(applicationContext)
        // One navigation for the robot screen and the phone app; drives continue when the robot shows another screen.
        NavigationHub.start(applicationContext)
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
                                    sensors = listOf("Camera", "LIDAR", "Microphone"),
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
                                val settings = jsonConfig.decodeFromString<AppSettings>(payload)
                                getSettingsFile(applicationContext).writeText(payload)

                                // The popup brings RoboGuard to the front; RobotOS gives SDK control back only a moment later.
                                this@RobotServerService.notification("Settings Saved, check RoboGuard App for details!")
                                this@RobotServerService.showPopup(UiText.get("popup.settings_saved"))
                                // Sensors (general settings only, rooms ignored for now) and speech wait for that control.
                                // The phone gets its answer right away.
                                applySettingsWhenInControl(settings)
                                call.respondText("OK")
                                


                            } catch (e: Exception) {
                                Log.e("Server", "Failed to save settings: $e")
                                // Bring RoboGuard to the front so the robot is allowed to say that saving failed.
                                runCatching { this@RobotServerService.showPopup(UiText.get("popup.settings_failed")) }
                                speakWhenInControl(UiText.get("speech.settings_save_failed"))
                                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
                            }
                        }

                        // The "Navigation and Map" screen for the phone app. Same authentication as every other route:
                        // secureGet/securePost -> requireClientAuth (client id + HMAC over the payload). The server binds
                        // 0.0.0.0 like all its routes, so these are reachable from the robot's own network and no further.
                        secureGet("/nav/state", applicationContext) {
                            call.respondText(navigationStateJson(), ContentType.Application.Json)
                        }
                        secureGet("/nav/map.json", applicationContext) {
                            call.respondText(navigationMapJson(), ContentType.Application.Json)
                        }
                        secureGet("/nav/map.png", applicationContext) {
                            val png = navigationMapPng()
                            if (png == null) call.respond(HttpStatusCode.ServiceUnavailable, "no map loaded")
                            else call.respondBytes(png, ContentType.Image.PNG)
                        }
                        securePost("/nav/command", applicationContext) { payload ->
                            // The phone's name is only a label for the robot's screen; the signature decided who may call.
                            val client = call.request.headers["X-Client-name"] ?: "phone"
                            val (status, answer) = navigationCommand(payload, client)
                            call.respondText(answer, ContentType.Application.Json, status)
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

     fun getSensors(): Map<String,Boolean> {
        return getCurrentSettings().sensors

    }
    /**
     * Defines the default privacy profile for the robot.
     */
    private fun getDefaultSettings(): AppSettings {
        val sensorNames = listOf("Camera", "LIDAR", "Microphone")
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
        serviceScope.cancel()
        ConversationMonitor.stop()
        CalendarMonitor.stop()
        NavigationHub.stop()
        runCatching { tts?.disconnect() }
        super.onDestroy()
    }

    /* ---------- Sensors and speech (robocontrol) ---------- */

    /** Background work of the service (waiting for SDK control); cancelled in onDestroy. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The pending "apply settings" of the latest save; a newer save replaces it. */
    private var applyJob: Job? = null

    /**
     * Switches the sensors and confirms by speech once RoboGuard has SDK control.
     *
     * Found on the robot: when the phone saves while RoboGuard is in the background, RobotOS refuses every SDK call
     * ("skillType is SUSPEND", RobotApi code -7) until RoboGuard is in front again. `startActivity` (the popup) only
     * queues the start; control returns ~0.5 s later, and RobotOS then calls stopTTS on the app change. So this waits
     * for [waitForSdkControl] first. Android-level switches (camera policy, mic mute) work either way.
     */
    private fun applySettingsWhenInControl(settings: AppSettings) {
        applyJob?.cancel()
        applyJob = serviceScope.launch {
            waitForSdkControl()
            val sensorsApplied = applySensorSettings(settings)
            speakGerman(
                if (sensorsApplied) UiText.get("speech.settings_updated")
                else UiText.get("speech.settings_sensors_failed")
            )
        }
    }

    /** Speaks [sentence] once RoboGuard has SDK control (see [applySettingsWhenInControl]). */
    private fun speakWhenInControl(sentence: String) {
        serviceScope.launch {
            waitForSdkControl()
            speakGerman(sentence)
        }
    }

    /**
     * Waits until RobotOS reports RoboGuard as the active app (`RobotApi.isActive()`, polled every
     * [CONTROL_POLL_MS] ms, at most [CONTROL_WAIT_TIMEOUT_MS] ms). If control had to be regained, it also waits
     * [CONTROL_SETTLE_MS] ms, past the stopTTS RobotOS sends right after an app change.
     *
     * @return true if RoboGuard is in control; false after the timeout (logged; SDK parts will then be refused)
     */
    private suspend fun waitForSdkControl(): Boolean {
        return try {
            // Creating Sensors starts the RobotApi connection if nothing else has; isActive() is false until connected.
            Sensors.get(applicationContext)
            val api = RobotApi.getInstance()
            val wasActive = api.isActive()
            val deadline = SystemClock.elapsedRealtime() + CONTROL_WAIT_TIMEOUT_MS
            while (!api.isActive()) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Log.w("Server", "No SDK control after $CONTROL_WAIT_TIMEOUT_MS ms: RobotOS will refuse speech and SDK sensor switches")
                    return false
                }
                delay(CONTROL_POLL_MS)
            }
            if (!wasActive) {
                Log.i("Server", "SDK control regained, waiting $CONTROL_SETTLE_MS ms before speaking")
                delay(CONTROL_SETTLE_MS)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Server", "Could not check SDK control: $e", e)
            false
        }
    }

    /**
     * Applies the general sensor settings (Camera, LIDAR, Microphone) to the robot via [Sensors].
     * Room-specific sensor settings are not applied yet.
     *
     * @return false if switching threw an error. Whether each switch really took effect is reported
     *         asynchronously in `Sensors.switchReports` (and Logcat tag "SensorSwitches").
     */
    private fun applySensorSettings(settings: AppSettings): Boolean =
        try {
            Sensors.get(applicationContext).update(settings)
            Log.i("Server", "Sensor settings applied: ${settings.sensors}")
            true
        } catch (e: Exception) {
            Log.e("Server", "Failed to apply sensor settings: $e", e)
            false
        }

    private companion object {
        /** How long to wait for the speech service before giving up on a sentence. */
        const val TTS_CONNECT_TIMEOUT_MS = 5_000L

        /** Waiting for RobotOS to give SDK control back after the popup brought RoboGuard to the front. */
        const val CONTROL_WAIT_TIMEOUT_MS = 3_000L
        const val CONTROL_POLL_MS = 100L

        /** Pause after regaining control; RobotOS calls stopTTS ~0.5 s after the app change. */
        const val CONTROL_SETTLE_MS = 1_000L
    }

    /** Created on first use; RobotOS only serves speech while RoboGuard is the active (foreground) app. */
    private var tts: OrionStarTts? = null
    private val ttsLock = Any()
    private var ttsConnecting = false

    /** Sentence waiting for the speech service to connect; only the newest one is kept. */
    private var pendingSentence: String? = null

    /**
     * Speaks [sentence] in German. Never throws: every problem (not connected, SDK error, speech rejected)
     * is logged under the tag "ServerTts" so a failed announcement cannot break a request.
     */
    private fun speakGerman(sentence: String) {
        try {
            val speech = synchronized(ttsLock) { tts ?: OrionStarTts(applicationContext).also { tts = it } }
            if (speech.connected.value) {
                say(speech, sentence)
                return
            }
            val connectNow = synchronized(ttsLock) {
                pendingSentence = sentence
                (!ttsConnecting).also { ttsConnecting = true }
            }
            if (connectNow) {
                speech.connect {
                    val next = synchronized(ttsLock) {
                        ttsConnecting = false
                        pendingSentence.also { pendingSentence = null }
                    }
                    next?.let { say(speech, it) }
                }
                // The SDK gives no callback when it refuses the connection (e.g. RoboGuard not in the foreground).
                Handler(Looper.getMainLooper()).postDelayed({
                    val lost = synchronized(ttsLock) {
                        if (!speech.connected.value) { ttsConnecting = false; pendingSentence.also { pendingSentence = null } } else null
                    }
                    lost?.let { Log.w("ServerTts", "Speech service not connected after ${TTS_CONNECT_TIMEOUT_MS} ms, not spoken: \"$it\"") }
                }, TTS_CONNECT_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.e("ServerTts", "Could not speak \"$sentence\": $e", e)
        }
    }

    private fun say(speech: OrionStarTts, sentence: String) {
        val accepted = speech.speakGerman(sentence, object : TtsListener {
            override fun onFailed(failure: TtsFailure) {
                Log.e("ServerTts", "Not spoken: \"$sentence\" ($failure)")
            }
        })
        if (accepted) Log.i("ServerTts", "Speaking: \"$sentence\"")
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
        val channel = NotificationChannel(channelId, UiText.get("service.notification.status_channel"), NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)

        val builder = Notification.Builder(this, channelId)
            .setContentTitle(UiText.get("service.notification.status_title"))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
