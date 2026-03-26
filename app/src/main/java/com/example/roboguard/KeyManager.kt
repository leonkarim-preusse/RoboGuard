package com.example.roboguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the secure storage and retrieval of passwords/secrets using the Android Keystore.
 * It uses AES encryption in GCM mode to ensure both confidentiality and integrity.
 */
object PasswordManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_ALIAS = "RoboGuardAESKey"
    private const val PREFS_NAME = "secure_prefs"
    private const val PASSWORD_KEY = "stored_password"

    /**
     * Encrypts and saves a byte array (password/secret) to SharedPreferences.
     * The encryption key is securely managed within the Android Keystore.
     * 
     * @param password The byte array to be securely stored.
     * @param context Android context.
     */
    fun savePassword(password: ByteArray, context: Context) {
        val key = getOrCreateAESKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(password)
        
        // Combine IV and encrypted data for storage
        val encryptedB64 = Base64.encodeToString(iv + encrypted, Base64.DEFAULT)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PASSWORD_KEY, encryptedB64)
            .apply()
    }

    /**
     * Loads and decrypts a byte array (password/secret) from SharedPreferences.
     * 
     * @param context Android context.
     * @return The decrypted byte array, or null if no password was found.
     */
    fun loadPassword(context: Context): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedB64 = prefs.getString(PASSWORD_KEY, null) ?: return null
        val decoded = Base64.decode(encryptedB64, Base64.DEFAULT)
        
        // Extract the 12-byte IV and the encrypted payload
        val iv = decoded.copyOfRange(0, 12)
        val encrypted = decoded.copyOfRange(12, decoded.size)
        
        val key = getOrCreateAESKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    /**
     * Retrieves the master AES key from the Android Keystore, or generates a new one if it doesn't exist.
     * The key is configured for AES/GCM/NoPadding with a 256-bit size.
     * 
     * @return The SecretKey instance.
     */
    private fun getOrCreateAESKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(AES_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            AES_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
