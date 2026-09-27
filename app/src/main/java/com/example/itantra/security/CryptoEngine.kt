package com.example.itantra.security

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * AES-256-GCM Cryptographic Engine for iTantra: Setu.
 *
 * Implements authenticated encryption with associated data (AEAD):
 * - Algorithm: AES/GCM/NoPadding
 * - Key Length: 256 bits (32 bytes)
 * - Nonce/IV Length: 96 bits (12 bytes) uniquely generated per encryption via SecureRandom
 * - Authentication Tag Length: 128 bits (16 bytes)
 *
 * Never reuses nonces with the same key. Detects ciphertext tampering, authentication tag tampering,
 * and authenticated metadata (AAD) tampering.
 */
object CryptoEngine {

    const val AES_KEY_SIZE_BITS = 256
    const val GCM_NONCE_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    const val GCM_TAG_LENGTH_BYTES = 16

    private val secureRandom = SecureRandom()

    // Demo/shared session key provisioned for prototype paired nodes
    // In production, this is established via ECDH/X25519 key exchange or Android Keystore
    @Volatile
    private var defaultSessionKey: SecretKey = generateSessionKey()

    /**
     * Data class holding encryption outputs:
     * - [ciphertext]: Raw encrypted data without tag
     * - [tag]: 16-byte authentication tag
     * - [nonce]: 12-byte initialization vector
     */
    data class EncryptedResult(
        val ciphertext: ByteArray,
        val tag: ByteArray,
        val nonce: ByteArray
    ) {
        @OptIn(ExperimentalEncodingApi::class)
        fun ciphertextBase64(): String = Base64.encode(ciphertext)

        @OptIn(ExperimentalEncodingApi::class)
        fun tagBase64(): String = Base64.encode(tag)

        @OptIn(ExperimentalEncodingApi::class)
        fun nonceBase64(): String = Base64.encode(nonce)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptedResult
            return ciphertext.contentEquals(other.ciphertext) &&
                    tag.contentEquals(other.tag) &&
                    nonce.contentEquals(other.nonce)
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + tag.contentHashCode()
            result = 31 * result + nonce.contentHashCode()
            return result
        }
    }

    /**
     * Generates a cryptographically strong 256-bit AES session key.
     */
    fun generateSessionKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE_BITS, secureRandom)
        return keyGen.generateKey()
    }

    /**
     * Creates a 256-bit AES SecretKey from raw 32-byte key material.
     */
    fun importKey(rawBytes: ByteArray): SecretKey {
        require(rawBytes.size == 32) { "AES-256 requires exactly 32 bytes (256 bits) of key material" }
        return SecretKeySpec(rawBytes, "AES")
    }

    /**
     * Sets the active session key used for default encryption/decryption in the prototype.
     */
    fun setSessionKey(key: SecretKey) {
        defaultSessionKey = key
    }

    /**
     * Gets the active session key.
     */
    fun getSessionKey(): SecretKey = defaultSessionKey

    /**
     * Generates a fresh, unpredictable 12-byte nonce for AES-GCM.
     * Crucial: A nonce must NEVER be reused with the same key.
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(GCM_NONCE_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Encrypts [plaintext] using AES-256-GCM.
     *
     * @param plaintext Plaintext bytes to encrypt
     * @param key 256-bit AES SecretKey (defaults to current session key)
     * @param aad Optional Associated Authenticated Data (e.g. packet metadata)
     * @return [EncryptedResult] containing ciphertext, authentication tag, and nonce
     */
    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey = defaultSessionKey,
        aad: ByteArray? = null
    ): EncryptedResult {
        val nonce = generateNonce()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        // In standard Java JCE, cipher.doFinal() produces ciphertext concatenated with the 16-byte tag
        val combined = cipher.doFinal(plaintext)
        val ciphertextSize = combined.size - GCM_TAG_LENGTH_BYTES
        require(ciphertextSize >= 0) { "Encryption produced invalid payload length" }

        val ciphertext = combined.copyOfRange(0, ciphertextSize)
        val tag = combined.copyOfRange(ciphertextSize, combined.size)

        return EncryptedResult(ciphertext = ciphertext, tag = tag, nonce = nonce)
    }

    /**
     * Decrypts ciphertext and verifies the authentication tag using AES-256-GCM.
     *
     * @param ciphertext Encrypted payload without tag
     * @param tag 16-byte authentication tag
     * @param nonce 12-byte initialization vector
     * @param key 256-bit AES SecretKey
     * @param aad Optional Associated Authenticated Data (must match AAD provided at encryption)
     * @return Decrypted plaintext bytes
     * @throws AEADBadTagException if ciphertext, tag, or AAD has been tampered with or corrupted
     */
    @Throws(AEADBadTagException::class)
    fun decrypt(
        ciphertext: ByteArray,
        tag: ByteArray,
        nonce: ByteArray,
        key: SecretKey = defaultSessionKey,
        aad: ByteArray? = null
    ): ByteArray {
        require(tag.size == GCM_TAG_LENGTH_BYTES) { "Invalid GCM tag length: ${tag.size}, expected $GCM_TAG_LENGTH_BYTES" }
        require(nonce.size == GCM_NONCE_LENGTH_BYTES) { "Invalid GCM nonce length: ${nonce.size}, expected $GCM_NONCE_LENGTH_BYTES" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        // Recombine ciphertext and tag for JCE GCM provider
        val combined = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

        return cipher.doFinal(combined)
    }

    /**
     * Convenient Base64-based decryption helper.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decryptBase64(
        ciphertextBase64: String,
        tagBase64: String,
        nonceBase64: String,
        key: SecretKey = defaultSessionKey,
        aad: ByteArray? = null
    ): ByteArray {
        val ciphertext = Base64.decode(ciphertextBase64)
        val tag = Base64.decode(tagBase64)
        val nonce = Base64.decode(nonceBase64)
        return decrypt(ciphertext, tag, nonce, key, aad)
    }
}
