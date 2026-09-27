package com.example.itantra.security

import com.example.itantra.protocol.SetuPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Replay protection filter for SetuPackets.
 *
 * Tracks unique packet signatures: (messageId, packetId, sequenceNumber).
 * Bounded retention window prevents duplicate replay attacks while keeping memory footprint constant.
 */
class ReplayDetector(
    private val maxCapacity: Int = 1024,
    private val maxAgeMs: Long = 120_000L // 2 minutes
) {
    data class Entry(
        val timestamp: Long,
        val packetId: String
    )

    private val seenPackets = ConcurrentHashMap<String, Entry>()

    /**
     * Checks if a packet is a duplicate/replay.
     *
     * @param packet Inbound packet to inspect
     * @return true if packet was already received and processed, false if fresh
     */
    fun isDuplicate(packet: SetuPacket): Boolean {
        purgeExpired()
        val key = "${packet.messageId}:${packet.packetId}:${packet.sequenceNumber}"
        val now = System.currentTimeMillis()

        val existing = seenPackets[key]
        if (existing != null) {
            return true
        }

        if (seenPackets.size >= maxCapacity) {
            // Evict oldest entries
            val oldest = seenPackets.entries.sortedBy { it.value.timestamp }.take(maxCapacity / 4)
            oldest.forEach { seenPackets.remove(it.key) }
        }

        seenPackets[key] = Entry(now, packet.packetId)
        return false
    }

    fun clear() {
        seenPackets.clear()
    }

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        seenPackets.entries.removeIf { it.value.timestamp < cutoff }
    }
}
