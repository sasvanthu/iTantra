package com.example.itantra.protocol

import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class Packet(
    val magic: ByteArray = byteArrayOf(0x49, 0x54, 0x4E, 0x54), // "ITNT"
    val version: Byte = 1,
    val messageId: Long = 0,
    val sequenceId: Int = 0,
    val language: Language = Language.ENGLISH,
    val packetType: PacketType = PacketType.TEXT_DATA,
    val priority: Byte = 2, // Importance.NORMAL.level
    val flags: Byte = 0,
    val payloadLength: Int = 0,
    val payload: ByteArray = ByteArray(0),
    val crc: Int = 0
) {
    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.write(magic)
        dos.writeByte(version.toInt())
        dos.writeLong(messageId)
        dos.writeInt(sequenceId)
        dos.writeByte(Language.toByte(language).toInt())
        dos.writeByte(packetType.id.toInt())
        dos.writeByte(priority.toInt())
        dos.writeByte(flags.toInt())
        dos.writeInt(payload.size)
        dos.write(payload)
        dos.writeInt(crc)

        return baos.toByteArray()
    }

    companion object {
        /** Bytes before the payload: magic(4)+version(1)+messageId(8)+sequenceId(4)+language(1)+packetType(1)+priority(1)+flags(1)+payloadLength(4). */
        private const val HEADER_BYTES = 25
        /** CRC trailer size (4 bytes). */
        private const val CRC_BYTES = 4
        /** Maximum accepted payload a wire packet may claim: 64 KiB protocol frame cap. */
        internal const val MAX_WIRE_PAYLOAD = 65536

        fun deserialize(data: ByteArray): Packet? {
            val packet = try {
                val bais = ByteArrayInputStream(data)
                val dis = DataInputStream(bais)

                val magic = ByteArray(4)
                dis.readFully(magic)
                if (magic[0] != 0x49.toByte() || magic[1] != 0x54.toByte() ||
                    magic[2] != 0x4E.toByte() || magic[3] != 0x54.toByte()) {
                    return null
                }

                val version = dis.readByte()
                if (version != 1.toByte()) return null

                val messageId = dis.readLong()
                val sequenceId = dis.readInt()
                val langByte = dis.readByte()
                val language = Language.fromByte(langByte)
                val packetType = PacketType.fromIdOrNull(dis.readByte()) ?: return null
                val priority = dis.readByte()
                val flags = dis.readByte()
                val payloadLength = dis.readInt()

                // Bounds-check the claimed payload BEFORE allocating: a hostile
                // frame must never trigger a huge allocation (OOM is an Error,
                // which the outer catch would not have contained) or skip the
                // CRC trailer.
                if (payloadLength < 0) return null
                if (payloadLength > MAX_WIRE_PAYLOAD) return null
                if (HEADER_BYTES + payloadLength + CRC_BYTES > data.size) return null

                val payload = ByteArray(payloadLength)
                dis.readFully(payload)
                val crc = dis.readInt()

                val result = Packet(
                    magic = magic,
                    version = version,
                    messageId = messageId,
                    sequenceId = sequenceId,
                    language = language,
                    packetType = packetType,
                    priority = priority,
                    flags = flags,
                    payloadLength = payloadLength,
                    payload = payload,
                    crc = crc
                )
                if (!verifyCrc(result)) return null
                result
            } catch (e: Exception) {
                return null
            }
            return packet
        }

        /**
         * Packet-level integrity: recomputes the CRC exactly like the creators
         * (over the 16-byte identity header + payload). Control packets are
         * created with crc = 0 and, lacking integrity coverage, are accepted
         * verbatim — data packets with a non-zero stored CRC must match.
         */
        private fun verifyCrc(packet: Packet): Boolean {
            if (packet.crc == 0) return true
            val headerData = ByteArray(16)
            System.arraycopy(longToBytes(packet.messageId), 0, headerData, 0, 8)
            System.arraycopy(intToBytes(packet.sequenceId), 0, headerData, 8, 4)
            headerData[12] = Language.toByte(packet.language)
            headerData[13] = packet.packetType.id
            headerData[14] = packet.priority
            headerData[15] = 0 // creators always stamp flags = 0 into the CRC
            return computeCRC(headerData + packet.payload) == packet.crc
        }

        fun computeCRC(data: ByteArray): Int {
            var crc = 0xFFFFFFFF.toInt()
            for (byte in data) {
                crc = crc xor (byte.toInt() and 0xFF)
                for (j in 0 until 8) {
                    crc = if (crc and 1 != 0) {
                        (crc ushr 1) xor 0xEDB88320.toInt()
                    } else {
                        crc ushr 1
                    }
                }
            }
            return crc xor 0xFFFFFFFF.toInt()
        }

        fun createTextPacket(
            messageId: Long,
            sequenceId: Int,
            language: Language,
            payload: ByteArray,
            priority: Byte = 2
        ): Packet {
            val headerData = ByteArray(16)
            System.arraycopy(longToBytes(messageId), 0, headerData, 0, 8)
            System.arraycopy(intToBytes(sequenceId), 0, headerData, 8, 4)
            headerData[12] = Language.toByte(language)
            headerData[13] = PacketType.TEXT_DATA.id
            headerData[14] = priority
            headerData[15] = 0

            val crcData = headerData + payload
            val crc = computeCRC(crcData)

            return Packet(
                messageId = messageId,
                sequenceId = sequenceId,
                language = language,
                packetType = PacketType.TEXT_DATA,
                priority = priority,
                payloadLength = payload.size,
                payload = payload,
                crc = crc
            )
        }

        fun createAckPacket(messageId: Long, sequenceId: Int): Packet {
            return Packet(
                messageId = messageId,
                sequenceId = sequenceId,
                packetType = PacketType.ACK,
                payload = byteArrayOf()
            )
        }

        fun createNackPacket(messageId: Long, sequenceId: Int): Packet {
            return Packet(
                messageId = messageId,
                sequenceId = sequenceId,
                packetType = PacketType.NACK,
                payload = byteArrayOf()
            )
        }

        fun createRetransmitPacket(messageId: Long, sequenceId: Int): Packet {
            return Packet(
                messageId = messageId,
                sequenceId = sequenceId,
                packetType = PacketType.RETRANSMIT,
                payload = byteArrayOf()
            )
        }

        fun createEmergencyPacket(
            messageId: Long,
            sequenceId: Int,
            language: Language,
            payload: ByteArray
        ): Packet {
            // Emergency packets keep the codec importance level so metrics on
            // both sides report the true priority (CRITICAL = 3).
            return createTextPacket(messageId, sequenceId, language, payload, priority = 3)
        }

        fun createCapabilityPacket(messageId: Long, language: Language, capability: ByteArray): Packet {
            return createGeneric(messageId, 0, language, PacketType.CAPABILITY, capability, priority = 1)
        }

        fun createCapabilityAckPacket(messageId: Long, language: Language, capability: ByteArray): Packet {
            return createGeneric(messageId, 0, language, PacketType.CAPABILITY_ACK, capability, priority = 1)
        }

        fun createGeneric(
            messageId: Long,
            sequenceId: Int,
            language: Language,
            type: PacketType,
            payload: ByteArray,
            priority: Byte = 1
        ): Packet {
            val crc = if (payload.isEmpty()) 0 else {
                val headerData = ByteArray(16)
                System.arraycopy(longToBytes(messageId), 0, headerData, 0, 8)
                System.arraycopy(intToBytes(sequenceId), 0, headerData, 8, 4)
                headerData[12] = Language.toByte(language)
                headerData[13] = type.id
                headerData[14] = priority
                headerData[15] = 0
                computeCRC(headerData + payload)
            }
            return Packet(
                messageId = messageId,
                sequenceId = sequenceId,
                language = language,
                packetType = type,
                priority = priority,
                payloadLength = payload.size,
                payload = payload,
                crc = crc
            )
        }

        fun createStartPacket(messageId: Long, language: Language, priority: Byte = 2): Packet {
            return Packet(
                messageId = messageId,
                sequenceId = 0,
                language = language,
                packetType = PacketType.START,
                priority = priority,
                payload = byteArrayOf()
            )
        }

        fun createEndPacket(messageId: Long, language: Language, dataCount: Int, priority: Byte = 2): Packet {
            // The END payload carries the total number of DATA packets, so the
            // receiver can detect a lost tail even when the last DATA is dropped.
            val payload = byteArrayOf(
                (dataCount shr 24).toByte(),
                (dataCount shr 16).toByte(),
                (dataCount shr 8).toByte(),
                dataCount.toByte()
            )
            return Packet(
                messageId = messageId,
                sequenceId = -1,
                language = language,
                packetType = PacketType.END,
                priority = priority,
                payload = payload
            )
        }

        private fun longToBytes(value: Long): ByteArray {
            return byteArrayOf(
                (value shr 56).toByte(),
                (value shr 48).toByte(),
                (value shr 40).toByte(),
                (value shr 32).toByte(),
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
        }

        private fun intToBytes(value: Int): ByteArray {
            return byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return messageId == other.messageId && sequenceId == other.sequenceId
    }

    override fun hashCode(): Int {
        return 31 * messageId.hashCode() + sequenceId
    }
}
