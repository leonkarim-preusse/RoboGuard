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
            receivedSignatureBase64: String, // Vom Handy gesendet
            payload: String                  // Das JSON
        ): Boolean {

            val passphrase = PasswordManager.loadPassword(context) ?: return false
            val db = ClientDatabase.getDatabase(context, passphrase)
            val client = db.clientDao().getClientById(clientId) ?: return false

            // get secret
            val storedSecretBase64 = client.sharedSecret

            // HMAC
            val expectedSignature = try {
                createHmacSignature(payload, storedSecretBase64)
            } catch (e: Exception) {
                Log.e("Auth", "Fehler bei HMAC Berechnung: ${e.message}")
                return false
            }

            // verify hmac
            return receivedSignatureBase64.trim() == expectedSignature.trim()
        }

        private fun createHmacSignature(payload: String, secretBase64: String): String {

            val secretBytes = java.util.Base64.getDecoder().decode(secretBase64)
            val hmacKey = javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA256")

            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(hmacKey)

            val resultBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))

            return java.util.Base64.getEncoder().encodeToString(resultBytes)
        }
}
}

