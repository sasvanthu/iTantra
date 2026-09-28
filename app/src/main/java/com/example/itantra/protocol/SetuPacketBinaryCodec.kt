package com.example.itantra.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Compact binary codec for [SetuPacket] (Zero-Serialization Binary Wire Envelope - Z-BWE).
 *
 * Eliminates JSON string reflection and Base64 expansion on the wireless wire,
 * providing:
 * - Fixed 4-byte magic ("SETU")
 * - 1-byte wire format version
 * - Structured binary header (timestamps, sequence numbers, routing flags)
 * - Raw 12-byte AES-GCM nonce and 16-byte authentication tag (no Base64 overhead)
 * - Length-prefixed UTF-8 identifiers
 * - Raw binary ciphertext payload
 *
 * Achieves significant wire-size reduction compared to legacy JSON while preserving
 * full cryptographic integrity and AAD metadata authentication.
 */
@OptIn(ExperimentalEncodingApi::class)
object SetuPacketBinaryCodec {

    val MAGIC = byteArrayOf(0x53, 0x45, 0x54, 0x55) // 'S', 'E', 'T', 'U'
    const val WIRE_VERSION: Byte = 1
    const val MAX_PAYLOAD_SIZE = 65535

    private const val PRIORITY_NORMAL: Byte = 0
    private const val PRIORITY_HIGH: Byte = 1
    private const val PRIORITY_CRITICAL: Byte = 2
    private const val PRIORITY_LOW: Byte = 3
    private const val PRIORITY_CUSTOM: Byte = 0x7F

    private const val TRANSPORT_BLUETOOTH: Byte = 0
    private const val TRANSPORT_WIFI: Byte = 1
    private const val TRANSPORT_WIFI_DIRECT: Byte = 2
    private const val TRANSPORT_CUSTOM: Byte = 0x7F

    private const val TYPE_TEXT: Byte = 0
    private const val TYPE_AUDIO: Byte = 1
    private const val TYPE_CONTROL: Byte = 2
    private const val TYPE_CUSTOM: Byte = 0x7F

    /**
     * Serializes [packet] into compact Z-BWE binary wire format.
     */
    fun encode(packet: SetuPacket): ByteArray {
        val txIdBytes = packet.transmissionId.toByteArray(StandardCharsets.UTF_8)
        val msgIdBytes = packet.messageId.toByteArray(StandardCharsets.UTF_8)
        val pktIdBytes = packet.packetId.toByteArray(StandardCharsets.UTF_8)
        val senderBytes = packet.senderId.toByteArray(StandardCharsets.UTF_8)
        val receiverBytes = packet.receiverId.toByteArray(StandardCharsets.UTF_8)
        val hasDistinctOrigSender = packet.originalSenderId != packet.senderId
        val origSenderBytes = if (hasDistinctOrigSender) {
            packet.originalSenderId.toByteArray(StandardCharsets.UTF_8)
        } else {
            ByteArray(0)
        }
        val langBytes = packet.language.toByteArray(StandardCharsets.UTF_8)

        val rawNonce = if (packet.nonce.isNotEmpty()) {
            Base64.decode(packet.nonce)
        } else {
            ByteArray(12)
        }
        val rawTag = if (packet.authenticationTag.isNotEmpty()) {
            Base64.decode(packet.authenticationTag)
        } else {
            ByteArray(16)
        }
        val rawPayload = if (packet.encryptedPayload.isNotEmpty()) {
            Base64.decode(packet.encryptedPayload)
        } else {
            ByteArray(0)
        }

        require(rawPayload.size <= MAX_PAYLOAD_SIZE) {
            "Payload size ${rawPayload.size} exceeds maximum allowable $MAX_PAYLOAD_SIZE"
        }

        val priorityCode = encodePriority(packet.priority)
        val transportCode = encodeTransport(packet.transport)
        val typeCode = encodeMessageType(packet.messageType)

        val customPriorityBytes = if (priorityCode == PRIORITY_CUSTOM) {
            packet.priority.toByteArray(StandardCharsets.UTF_8)
        } else ByteArray(0)
        val customTransportBytes = if (transportCode == TRANSPORT_CUSTOM) {
            packet.transport.toByteArray(StandardCharsets.UTF_8)
        } else ByteArray(0)
        val customTypeBytes = if (typeCode == TYPE_CUSTOM) {
            packet.messageType.toByteArray(StandardCharsets.UTF_8)
        } else ByteArray(0)

        // Calculate exact buffer capacity to avoid intermediate reallocations
        var totalSize = 4 + // MAGIC
                1 + // WIRE_VERSION
                1 + // protocolVersion
                8 + // timestamp
                2 + // sequenceNumber
                2 + // totalPackets
                1 + // hopCount
                1 + // priorityCode
                1 + // transportCode
                1 + // typeCode
                1 + // flags (bit 0 = hasDistinctOrigSender)
                12 + // rawNonce (always 12 bytes)
                16 + // rawTag (always 16 bytes)
                1 + txIdBytes.size +
                1 + msgIdBytes.size +
                1 + pktIdBytes.size +
                1 + senderBytes.size +
                1 + receiverBytes.size +
                (if (hasDistinctOrigSender) 1 + origSenderBytes.size else 0) +
                1 + langBytes.size +
                (if (priorityCode == PRIORITY_CUSTOM) 1 + customPriorityBytes.size else 0) +
                (if (transportCode == TRANSPORT_CUSTOM) 1 + customTransportBytes.size else 0) +
                (if (typeCode == TYPE_CUSTOM) 1 + customTypeBytes.size else 0) +
                2 + rawPayload.size // payloadLength + payload

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC)
        buffer.put(WIRE_VERSION)
        buffer.put(packet.protocolVersion.toByte())
        buffer.putLong(packet.timestamp)
        buffer.putShort(packet.sequenceNumber.toShort())
        buffer.putShort(packet.totalPackets.toShort())
        buffer.put(packet.hopCount.toByte())
        buffer.put(priorityCode)
        buffer.put(transportCode)
        buffer.put(typeCode)

        var flags: Byte = 0
        if (hasDistinctOrigSender) flags = (flags.toInt() or 0x01).toByte()
        buffer.put(flags)

        // Write Nonce (pad or truncate to exactly 12)
        if (rawNonce.size == 12) {
            buffer.put(rawNonce)
        } else {
            val paddedNonce = ByteArray(12)
            System.arraycopy(rawNonce, 0, paddedNonce, 0, minOf(rawNonce.size, 12))
            buffer.put(paddedNonce)
        }

        // Write Tag (pad or truncate to exactly 16)
        if (rawTag.size == 16) {
            buffer.put(rawTag)
        } else {
            val paddedTag = ByteArray(16)
            System.arraycopy(rawTag, 0, paddedTag, 0, minOf(rawTag.size, 16))
            buffer.put(paddedTag)
        }

        // Strings
        writeVarString(buffer, txIdBytes)
        writeVarString(buffer, msgIdBytes)
        writeVarString(buffer, pktIdBytes)
        writeVarString(buffer, senderBytes)
        writeVarString(buffer, receiverBytes)
        if (hasDistinctOrigSender) {
            writeVarString(buffer, origSenderBytes)
        }
        writeVarString(buffer, langBytes)

        if (priorityCode == PRIORITY_CUSTOM) writeVarString(buffer, customPriorityBytes)
        if (transportCode == TRANSPORT_CUSTOM) writeVarString(buffer, customTransportBytes)
        if (typeCode == TYPE_CUSTOM) writeVarString(buffer, customTypeBytes)

        // Payload
        buffer.putShort(rawPayload.size.toShort())
        buffer.put(rawPayload)

        return buffer.array()
    }

    /**
     * Deserializes [bytes] into a [SetuPacket].
     * Returns null if [bytes] is not a valid Z-BWE packet or is corrupted.
     */
    fun decode(bytes: ByteArray): SetuPacket? {
        if (bytes.size < 40) return null // Minimum header size threshold

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Check magic
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) return null

        val wireVersion = buffer.get()
        if (wireVersion != WIRE_VERSION) return null

        try {
            val protocolVersion = buffer.get().toInt() and 0xFF
            val timestamp = buffer.getLong()
            val sequenceNumber = buffer.getShort().toInt() and 0xFFFF
            val totalPackets = buffer.getShort().toInt() and 0xFFFF
            val hopCount = buffer.get().toInt() and 0xFF
            val priorityCode = buffer.get()
            val transportCode = buffer.get()
            val typeCode = buffer.get()
            val flags = buffer.get().toInt()

            val rawNonce = ByteArray(12)
            buffer.get(rawNonce)

            val rawTag = ByteArray(16)
            buffer.get(rawTag)

            val txId = readVarString(buffer) ?: return null
            val msgId = readVarString(buffer) ?: return null
            val pktId = readVarString(buffer) ?: return null
            val senderId = readVarString(buffer) ?: return null
            val receiverId = readVarString(buffer) ?: return null

            val hasDistinctOrigSender = (flags and 0x01) != 0
            val origSenderId = if (hasDistinctOrigSender) {
                readVarString(buffer) ?: return null
            } else {
                senderId
            }

            val language = readVarString(buffer) ?: return null

            val priority = if (priorityCode == PRIORITY_CUSTOM) {
                readVarString(buffer) ?: "NORMAL"
            } else decodePriority(priorityCode)

            val transport = if (transportCode == TRANSPORT_CUSTOM) {
                readVarString(buffer) ?: "BLUETOOTH"
            } else decodeTransport(transportCode)

            val messageType = if (typeCode == TYPE_CUSTOM) {
                readVarString(buffer) ?: "TEXT"
            } else decodeMessageType(typeCode)

            if (buffer.remaining() < 2) return null
            val payloadLength = buffer.getShort().toInt() and 0xFFFF

            if (buffer.remaining() != payloadLength) {
                return null // Corrupted length or truncated packet
            }

            val rawPayload = ByteArray(payloadLength)
            buffer.get(rawPayload)

            return SetuPacket(
                protocolVersion = protocolVersion,
                transmissionId = txId,
                messageId = msgId,
                packetId = pktId,
                senderId = senderId,
                receiverId = receiverId,
                originalSenderId = origSenderId,
                transport = transport,
                timestamp = timestamp,
                sequenceNumber = sequenceNumber,
                totalPackets = totalPackets,
                language = language,
                messageType = messageType,
                priority = priority,
                hopCount = hopCount,
                encryptedPayload = Base64.encode(rawPayload),
                nonce = Base64.encode(rawNonce),
                authenticationTag = Base64.encode(rawTag)
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun writeVarString(buffer: ByteBuffer, bytes: ByteArray) {
        val len = minOf(bytes.size, 255)
        buffer.put(len.toByte())
        buffer.put(bytes, 0, len)
    }

    private fun readVarString(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 1) return null
        val len = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < len) return null
        val bytes = ByteArray(len)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun encodePriority(priority: String): Byte = when (priority.uppercase()) {
        "NORMAL" -> PRIORITY_NORMAL
        "HIGH" -> PRIORITY_HIGH
        "CRITICAL" -> PRIORITY_CRITICAL
        "LOW" -> PRIORITY_LOW
        else -> PRIORITY_CUSTOM
    }

    private fun decodePriority(code: Byte): String = when (code) {
        PRIORITY_NORMAL -> "NORMAL"
        PRIORITY_HIGH -> "HIGH"
        PRIORITY_CRITICAL -> "CRITICAL"
        PRIORITY_LOW -> "LOW"
        else -> "NORMAL"
    }

    private fun encodeTransport(transport: String): Byte = when (transport.uppercase()) {
        "BLUETOOTH" -> TRANSPORT_BLUETOOTH
        "WIFI" -> TRANSPORT_WIFI
        "WIFI_DIRECT" -> TRANSPORT_WIFI_DIRECT
        else -> TRANSPORT_CUSTOM
    }

    private fun decodeTransport(code: Byte): String = when (code) {
        TRANSPORT_BLUETOOTH -> "BLUETOOTH"
        TRANSPORT_WIFI -> "WIFI"
        TRANSPORT_WIFI_DIRECT -> "WIFI_DIRECT"
        else -> "BLUETOOTH"
    }

    private fun encodeMessageType(type: String): Byte = when (type.uppercase()) {
        "TEXT" -> TYPE_TEXT
        "AUDIO" -> TYPE_AUDIO
        "CONTROL" -> TYPE_CONTROL
        else -> TYPE_CUSTOM
    }

    private fun decodeMessageType(code: Byte): String = when (code) {
        TYPE_TEXT -> "TEXT"
        TYPE_AUDIO -> "AUDIO"
        TYPE_CONTROL -> "CONTROL"
        else -> "TEXT"
    }
}
