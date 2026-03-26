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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Handles the authentication logic for the RoboGuard system.
 * This class manages OTP (One-Time Password) generation, validation, and the initial
 * handshake process to establish a secure shared secret with a client.
 *
 * @property publickey The public key (Base64 encoded) used for HTTPS/TLS identification.
 * @property ip The hostname or IP address of the robot (e.g., robot-ID.local).
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
class Authentification(var publickey: String, var ip: String?) {

    /** The current active OTP for pairing. */
    var otp = createOTP()

    /** Duration in seconds for which an OTP remains valid. */
    @Transient
    private val validityDuration = 300

    /** Timestamp of when the current OTP was generated. */
    @Transient
    private var createdAt: Long = System.currentTimeMillis()

    /**
     * Generates a new random UUID-based OTP and updates the creation timestamp.
     * @return A new OTP string.
     */
    protected fun createOTP(): String {
        createdAt = System.currentTimeMillis()
        return UUID.randomUUID().toString()
    }

    /**
     * Validates a provided OTP against the current active one.
     * Checks if the OTP matches and if it is still within its validity duration.
     * @param otp The OTP string to validate.
     * @return True if valid, false otherwise.
     */
    internal fun validateOTP(otp: String): Boolean {
        if (System.currentTimeMillis() - createdAt > validityDuration * 1000) {
            updateOTP()
            return false
        }
        return this.otp == otp
    }

    /**
     * Forces the generation of a new OTP.
     */
    protected fun updateOTP() {
        this.otp = createOTP()
    }

    /**
     * Serializes this authentication object to a JSON string for transmission (e.g., via QR code).
     * @return JSON string containing public key, IP, and OTP.
     */
    internal fun createAuthMessage(): String {
        val json = Json.encodeToString(serializer(), this)
        return json
    }

    /**
     * Generates a cryptographically strong 64-byte shared secret.
     * @return A ByteArray containing the shared secret.
     */
    internal fun generateSharedSecret(): ByteArray {
        val sharedSecret = ByteArray(64)
        SecureRandom().nextBytes(sharedSecret)
        return sharedSecret
    }

    /**
     * Executes the initial authentication handshake.
     * Validates the OTP, generates a shared secret, and saves the new client to the database.
     *
     * @param otp The OTP provided by the client.
     * @param clientName A descriptive name for the client device.
     * @param context Android context for database access.
     * @return The unique ID assigned to the new client in the database.
     * @throws SecurityException If the OTP is invalid or expired.
     */
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

    /**
     * Retrieves or creates the master password used to encrypt the local client database.
     * The password itself is protected by the Android Keystore via [PasswordManager].
     *
     * @param context Android context.
     * @return The 32-byte master password.
     */
    fun getOrCreateDBPassword(context: Context): ByteArray {
        return PasswordManager.loadPassword(context) ?: run {
            val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
            PasswordManager.savePassword(password, context)
            password
        }
    }

    companion object {
        /**
         * Retrieves the shared secret for a specific client from the encrypted database.
         *
         * @param clientId The ID of the client.
         * @param context Android context.
         * @return The decoded shared secret bytes, or null if not found.
         */
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

        /**
         * Authenticates an incoming request by verifying an HMAC signature.
         *
         * @param context Android context.
         * @param clientId The ID of the client making the request.
         * @param receivedSignatureBase64 The HMAC signature sent by the client.
         * @param payload The raw body of the request used to verify the signature.
         * @return True if the signature is valid, false otherwise.
         */
        internal suspend fun authenticate(
            context: Context,
            clientId: Int,
            receivedSignatureBase64: String,
            payload: String
        ): Boolean {
            val passphrase = PasswordManager.loadPassword(context) ?: return false
            val db = ClientDatabase.getDatabase(context, passphrase)
            val client = db.clientDao().getClientById(clientId) ?: return false

            val storedSecretBase64 = client.sharedSecret

            val expectedSignature = try {
                createHmacSignature(payload, storedSecretBase64)
            } catch (e: Exception) {
                Log.e("Auth", "Error calculating HMAC: ${e.message}")
                return false
            }
            Log.i("Auth", "Expected Signature: $expectedSignature, Base64_signature: $receivedSignatureBase64")
            
            return receivedSignatureBase64.trim() == expectedSignature.trim()
        }

        /**
         * Calculates an HMAC-SHA256 signature for a given payload using a Base64 encoded secret.
         *
         * @param payload The data to sign.
         * @param secretBase64 The shared secret in Base64 format.
         * @return The resulting HMAC signature as a Base64 string.
         */
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
