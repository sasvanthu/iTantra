package com.example.itantra.protocol

import com.example.itantra.security.CryptoEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * Clean structured application packet for iTantra: Setu.
 *
 * Encapsulates:
 * - Identification: transmissionId, messageId, packetId
 * - Routing: senderId, receiverId, originalSenderId, hopCount
 * - Link: transport (BLUETOOTH / WIFI / WIFI_DIRECT), timestamp
 * - Application semantics: sequenceNumber, totalPackets, language, messageType, priority
 * - Authenticated Cryptography: encryptedPayload, nonce (12B), authenticationTag (16B)
 *
 * The plaintext message is NEVER sent inside the packet; it is serialized and encrypted via AES-256-GCM.
 */
@Serializable
data class SetuPacket(
    val protocolVersion: Int = 1,
    val transmissionId: String,
    val messageId: String,
    val packetId: String,
    val senderId: String,
    val receiverId: String,
    val originalSenderId: String = senderId,
    val transport: String = "BLUETOOTH",
    val timestamp: Long = System.currentTimeMillis(),
    val sequenceNumber: Int = 1,
    val totalPackets: Int = 1,
    val language: String = "ta-IN",
    val messageType: String = "TEXT",
    val priority: String = "NORMAL",
    val hopCount: Int = 0,
    val encryptedPayload: String,     // Base64 encoded ciphertext
    val nonce: String,                // Base64 encoded 12-byte initialization vector
    val authenticationTag: String     // Base64 encoded 16-byte GCM authentication tag
) {

    /**
     * Builds Associated Authenticated Data (AAD) for AES-256-GCM.
     * Authenticates critical routing and packet metadata to prevent tampering.
     */
    fun computeAad(): ByteArray {
        val aadString = "$protocolVersion|$transmissionId|$messageId|$packetId|$originalSenderId|$receiverId|$language|$messageType"
        return aadString.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Decrypts and verifies the payload using AES-256-GCM.
     *
     * @param key 256-bit AES SecretKey
     * @return Decrypted plaintext payload
     * @throws AEADBadTagException if payload, tag, or authenticated metadata has been tampered with
     */
    @Throws(AEADBadTagException::class)
    fun decryptPayload(key: SecretKey = CryptoEngine.getSessionKey()): ByteArray {
        val aad = computeAad()
        return CryptoEngine.decryptBase64(
            ciphertextBase64 = encryptedPayload,
            tagBase64 = authenticationTag,
            nonceBase64 = nonce,
            key = key,
            aad = aad
        )
    }

    /**
     * Serializes this packet to JSON string.
     */
    fun toJson(): String = jsonFormat.encodeToString(this)

    /**
     * Serializes this packet to compact Z-BWE binary wire format.
     */
    fun encodeBinary(): ByteArray = SetuPacketBinaryCodec.encode(this)

    /**
     * Serializes this packet to wire bytes using the preferred Z-BWE binary representation.
     */
    fun toWireBytes(): ByteArray = encodeBinary()

    companion object {
        private val jsonFormat = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        fun fromJson(json: String): SetuPacket {
            return jsonFormat.decodeFromString<SetuPacket>(json)
        }

        /**
         * Deserializes a [SetuPacket] from compact Z-BWE binary wire format.
         */
        fun decodeBinary(bytes: ByteArray): SetuPacket? = SetuPacketBinaryCodec.decode(bytes)

        /**
         * Deserializes a [SetuPacket] from either Z-BWE binary format (preferred) or legacy JSON format (fallback).
         */
        fun fromWireBytes(bytes: ByteArray): SetuPacket? {
            if (bytes.size >= 4 &&
                bytes[0] == SetuPacketBinaryCodec.MAGIC[0] &&
                bytes[1] == SetuPacketBinaryCodec.MAGIC[1] &&
                bytes[2] == SetuPacketBinaryCodec.MAGIC[2] &&
                bytes[3] == SetuPacketBinaryCodec.MAGIC[3]
            ) {
                val binary = decodeBinary(bytes)
                if (binary != null) return binary
            }
            return try {
                val json = String(bytes, StandardCharsets.UTF_8)
                fromJson(json)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Factory method to build a fully encrypted, structured SetuPacket from plaintext.
         */
        fun createEncrypted(
            plaintext: ByteArray,
            senderId: String,
            receiverId: String,
            transport: String = "BLUETOOTH",
            language: String = "ta-IN",
            messageType: String = "TEXT",
            priority: String = "NORMAL",
            transmissionId: String = IdGenerator.generateTransmissionId(),
            messageId: String = IdGenerator.generateMessageId(),
            sequenceNumber: Int = 1,
            totalPackets: Int = 1,
            originalSenderId: String = senderId,
            hopCount: Int = 0,
            key: SecretKey = CryptoEngine.getSessionKey()
        ): SetuPacket {
            val packetId = IdGenerator.generatePacketId(messageId, sequenceNumber)

            val tempPacket = SetuPacket(
                protocolVersion = 1,
                transmissionId = transmissionId,
                messageId = messageId,
                packetId = packetId,
                senderId = senderId,
                receiverId = receiverId,
                originalSenderId = originalSenderId,
                transport = transport,
                timestamp = System.currentTimeMillis(),
                sequenceNumber = sequenceNumber,
                totalPackets = totalPackets,
                language = language,
                messageType = messageType,
                priority = priority,
                hopCount = hopCount,
                encryptedPayload = "",
                nonce = "",
                authenticationTag = ""
            )

            val aad = tempPacket.computeAad()
            val encResult = CryptoEngine.encrypt(plaintext, key, aad)

            return tempPacket.copy(
                encryptedPayload = encResult.ciphertextBase64(),
                nonce = encResult.nonceBase64(),
                authenticationTag = encResult.tagBase64()
            )
        }
    }
}
