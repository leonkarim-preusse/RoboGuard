package com.example.robocontrol.movement

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts stored zone files. [associated] is authenticated but not encrypted: [ZoneRegistry] passes the
 * file name, so a file copied over another map's file fails to decrypt instead of loading the wrong areas.
 */
interface ZoneCipher {
    fun encrypt(plain: ByteArray, associated: ByteArray): ByteArray
    fun decrypt(data: ByteArray, associated: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves the robot. Same pattern as RoboGuard's
 * `KeyManager`, but with its own key alias: one key per purpose, and robocontrol does not depend on roboguard.
 *
 * File layout: [MAGIC] (4 bytes) + IV (12 bytes) + ciphertext incl. 128-bit GCM tag.
 *
 * If the key is gone (factory reset, app data cleared), a new key is created and old files fail to decrypt with an
 * exception. [ZoneRegistry] turns that into [ZoneStoreCorrupt], and the caller must fail closed.
 */
class KeystoreZoneCipher(private val alias: String = DEFAULT_ALIAS) : ZoneCipher {

    override fun encrypt(plain: ByteArray, associated: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore generates a random IV
        cipher.updateAAD(associated)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        return MAGIC + iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray, associated: ByteArray): ByteArray {
        require(data.size > MAGIC.size + IV_BYTES && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "not an encrypted zone file"
        }
        val iv = data.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associated)
        return cipher.doFinal(data, MAGIC.size + IV_BYTES, data.size - MAGIC.size - IV_BYTES)
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "robocontrol_private_zones"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val MAGIC = "RGZ1".toByteArray(Charsets.US_ASCII)
    }
}
