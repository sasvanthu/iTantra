package com.example.itantra.protocol

import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType

/**
 * Pure (framework-free) packet chunking and reassembly.
 *
 * Used by both [PacketManager] and the Codec Lab so all paths share one
 * implementation of ordering / framing. No Android dependencies.
 */
object Packetizer {

    const val DEFAULT_MAX_PAYLOAD = 1024

    fun chunk(payload: ByteArray, maxSize: Int = DEFAULT_MAX_PAYLOAD): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxSize, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    /** Assembles ordered parts. Handles out-of-order arrival by sequence id. */
    fun assemble(parts: Map<Int, ByteArray>): ByteArray {
        if (parts.isEmpty()) return ByteArray(0)
        val sorted = parts.toSortedMap()
        val total = sorted.values.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        for ((_, part) in sorted) {
            System.arraycopy(part, 0, result, offset, part.size)
            offset += part.size
        }
        return result
    }

    /**
     * Builds the full START / DATA* / END packet sequence for a payload,
     * mirroring the wire protocol that PacketManager uses.
     */
    fun buildPackets(
        payload: ByteArray,
        language: Language,
        messageId: Long,
        priority: Int,
        isEmergency: Boolean,
        maxPayload: Int = DEFAULT_MAX_PAYLOAD
    ): List<Packet> {
        val packets = mutableListOf<Packet>()

        packets.add(Packet.createStartPacket(messageId, language))

        val chunks = chunk(payload, maxPayload)
        chunks.forEachIndexed { index, chunk ->
            val seq = index + 1
            packets.add(
                if (isEmergency) {
                    Packet.createEmergencyPacket(messageId, seq, language, chunk)
                } else {
                    Packet.createTextPacket(messageId, seq, language, chunk, priority.toByte())
                }
            )
        }

        packets.add(Packet.createEndPacket(messageId, language, chunks.size))
        return packets
    }
}