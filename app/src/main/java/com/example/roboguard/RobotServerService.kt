package com.example.roboguard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.CallLogging

import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.security.KeyStore

class RobotServerService : Service() {

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    override fun onCreate() {
        super.onCreate()
        copyKeystoreFromAssets() // Stelle sicher, dass server.p12 existiert
        startKtorServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startKtorServer() {

        System.setProperty("io.netty.transport.noUnsafe", "true")
        System.setProperty("io.netty.epollDisabled", "true")
        System.setProperty("io.netty.kqueueDisabled", "true")


        val keyStoreFile = File(filesDir, "server.p12")
        val keyStorePassword = "testpasswort".toCharArray()

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(keyStoreFile).use { load(it, keyStorePassword) }
        }

        server = embeddedServer(
            factory = Netty,
            module = {
                install(ContentNegotiation) { json() }
                install(CallLogging)

                routing {
                    get("/ping") { call.respondText("alive")
                    Log.i("Server", "Ping received")}
                    post("/save") {
                        val text = call.receive<String>()
                        saveToFile(text)
                        call.respondText("OK")
                        Log.i("Server", "Data saved: $text")
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

    private fun copyKeystoreFromAssets() {
        val fileName = "server.p12"
        val file = File(filesDir, fileName)
        if (!file.exists()) {
            try {
                applicationContext.assets.open(fileName).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e("RobotServerService", "Failed to copy keystore from assets", e)
            }
        }
    }

    private fun saveToFile(data: String) {
        val file = File(filesDir, "robot_data.txt")
        file.appendText("$data\n")
    }

    private fun getDeviceIp(): String {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "No network"
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return "No network"
        return linkProperties.linkAddresses.firstOrNull {
            it.address.hostAddress?.contains(".") == true && it.address.hostAddress != "127.0.0.1"
        }?.address?.hostAddress ?: "Unknown IP"
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

