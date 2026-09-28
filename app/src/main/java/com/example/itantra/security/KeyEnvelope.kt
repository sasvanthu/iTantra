package com.example.itantra.security

import android.os.Build
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Wraps and unwraps the shared AES-256 session key using a non-exportable
 * key held in the Android Keystore.
 *
 * Why this exists
 * ---------------
 * Two paired demo devices must end up holding the SAME AES-256 key, otherwise
 * every packet fails authentication on the receiver. That key is provisioned
 * out of band (see [PairingCode]), which means it has to be persisted. Writing
 * those 32 raw bytes to disk would leave the session key in plaintext, so the
 * raw material is instead sealed with a Keystore-backed AES-256-GCM key that
 * the application cannot export.
 *
 * The Keystore key is created with `setRandomizedEncryptionRequired(true)`, so
 * the provider (and not this code) chooses the wrapping nonce. The sealed blob
 * therefore never repeats, even if the same session key is wrapped twice.
 *
 * @see SessionKeyManager for the lifecycle and the ECDH replacement seam
 */
object KeyEnvelope {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAPPING_KEY_ALIAS = "itantra.setu.session.envelope.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val VERSION = "v1"

    /**
     * Seals [rawKey] with a Keystore-held wrapping key.
     *
     * @return an opaque string safe to store in preferences, or null if the
     *         Keystore is unavailable on this device
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun seal(rawKey: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val sealed = cipher.doFinal(rawKey)
            "$VERSION.${Base64.encode(cipher.iv)}.${Base64.encode(sealed)}"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Opens a blob produced by [seal].
     *
     * @return the raw 32-byte session key, or null when the blob is corrupt,
     *         the wrong shape, or the Keystore key was invalidated (for
     *         example after a lock-screen credential change)
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun open(sealed: String?): ByteArray? {
        if (sealed.isNullOrBlank()) return null
        val parts = sealed.split('.')
        if (parts.size != 3 || parts[0] != VERSION) return null

        return try {
            val iv = Base64.decode(parts[1])
            val blob = Base64.decode(parts[2])
            if (iv.size != NONCE_BYTES) return null

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val raw = cipher.doFinal(blob)
            if (raw.size == PairingCode.KEY_LENGTH_BYTES) raw else null
        } catch (_: Exception) {
            null
        }
    }

    /** True when this device can actually seal and open an envelope. */
    fun isAvailable(): Boolean = try {
        wrappingKey()
        true
    } catch (_: Exception) {
        false
    }

    /** Drops the wrapping key. Any sealed session key becomes permanently unreadable. */
    fun destroy() {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAPPING_KEY_ALIAS)
        } catch (_: Exception) {
            // Nothing to destroy.
        }
    }

    /**
     * Returns the non-exportable AES-256 wrapping key, creating it on first use.
     *
     * @throws Exception when the platform refuses to provide Keystore-backed
     *         keys. The caller must surface that as "pairing unavailable"
     *         rather than silently falling back to unprotected storage.
     */
    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(WRAPPING_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }

        val generator = KeyGenerator.getInstance("AES", KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            WRAPPING_KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(false)
                }
            }
            .build()

        generator.init(spec)
        return generator.generateKey()
    }
}
