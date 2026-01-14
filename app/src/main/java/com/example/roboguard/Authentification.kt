package com.example.roboguard

import android.annotation.SuppressLint
import kotlinx.serialization.Transient
import android.content.Context
import android.util.Log
import io.ktor.util.encodeBase64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@SuppressLint("UnsafeOptInUsageError")
@Serializable
class Authentification(var publickey: String, var ip: String?) {

    protected var otp = createOTP()

    @Transient
    private val validityDuration = 300

    @Transient
    private val createdAt: Long = System.currentTimeMillis()


    protected fun createOTP(): String {
        return UUID.randomUUID().toString()

    }

    internal fun validateOTP(otp: String): Boolean {

        if (System.currentTimeMillis() - createdAt > validityDuration * 1000) {
            updateOTP()
            return false
        }
        return this.otp == otp
    }

    protected fun updateOTP() {
        this.otp = createOTP()

    }

    internal fun createAuthMessage(): String {

        val json = Json.encodeToString(serializer(), this)
        return json


    }

    internal fun generateSharedSecret(): ByteArray {
        val sharedSecret = ByteArray(64)
        SecureRandom().nextBytes(sharedSecret)
        return sharedSecret
    }

    internal suspend fun authHandshake(otp: String, clientName: String, context: Context): Long {
        if (!validateOTP(otp)) throw SecurityException("Bad OTP")

        val sharedSecret = generateSharedSecret()
        val passphrase = getOrCreateDBPassword(context)
        val database = ClientDatabase.getDatabase(context, passphrase)
        val clientDao = database.clientDao()

        val client =
            ClientEntity(sharedSecret = sharedSecret.encodeBase64(), clientName = clientName)
        val id = clientDao.insertClient(client)
        Log.i("DB", "Client added, ID $id")

        return id
    }

    fun getOrCreateDBPassword(context: Context): ByteArray {
        // Versuche, gespeichertes Passwort zu laden
        return PasswordManager.loadPassword(context) ?: run {
            // Neues Passwort erstellen, falls noch keins existiert
            val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
            PasswordManager.savePassword(password, context)
            password
        }
    }

    companion object {
        internal suspend fun getSharedSecret(
            clientId: Int,
            context: Context
        ): ByteArray? {

            val passphrase = PasswordManager.loadPassword(context)
                ?: return null

            val db = ClientDatabase.getDatabase(context, passphrase)
            val client = db.clientDao().getClientById(clientId)
                ?: return null

            return try {
                Base64.getDecoder().decode(client.sharedSecret)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        internal suspend fun authenticate(
            context: Context,
            clientId: Int,
            sharedSecretClientBase64: String
        ): Boolean {

            val passphrase = PasswordManager.loadPassword(context)
                ?: return false

            val db = ClientDatabase.getDatabase(context, passphrase)
            val client = db.clientDao().getClientById(clientId)
                ?: return false

            val clientSecretBytes = try {
                Base64.getDecoder().decode(sharedSecretClientBase64)
            } catch (e: IllegalArgumentException) {
                return false
            }

            val storedSecretBytes = try {
                Base64.getDecoder().decode(client.sharedSecret)
            } catch (e: IllegalArgumentException) {
                return false
            }

            return storedSecretBytes.contentEquals(clientSecretBytes)
        }
    }
}

