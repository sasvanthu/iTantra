package com.example.itantra.protocol

import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType

/**
 * A message that completed reassembly on a mesh node.
 */
data class MeshMessage(
    val messageId: Long,
    val payload: ByteArray,
    val language: Language,
    val isEmergency: Boolean,
    val receivedAt: Long,
    val dataPackets: Int
)

/**
 * Broadcast reassembly for the mesh overlay.
 *
 * The point-to-point transports can NACK a missing packet back to *one* peer;
 * a flooded broadcast has no single known sender to ask, so this reassembler
 * trades recovery for simplicity:
 *
 *  - START / DATA* / END are accumulated by [messageId] exactly like the
 *    reliable path, including out-of-order tolerance and duplicate parts
 *    (never re-processed).
 *  - The END packet's dataCount detects a lost tail: the partial message is
 *    **dropped** and counted, never half-delivered with holes.
 *  - Gaps discovered while accumulating (a DATA arrives with sequence numbers
 *    skipped) are recorded as packet loss — but only *unresolved* loss: a gap
 *    that is later healed by a retransmitted packet nets back out, so reordered
 *    (not lost) traffic is reported honestly as zero loss.
 *  - Reassembly is honest about its limits: metrics are surfaced through the
 *    counters below, and stale half-messages are purged so a hostile or chatty
 *    mesh cannot grow memory without bound.
 *
 * Pure Kotlin and framework-free so the entire mesh core is JVM-testable.
 */
class MeshReassembler(
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    private class Buf(
        var language: Language,
        val firstReceivedAt: Long
    ) {
        val parts = mutableMapOf<Int, ByteArray>()
        var maxSeq = 0
        var endSeen = false
        var expectedDataCount = -1
        var isEmergency = false
        var gapsReported = false

        /** Number of seq range slots currently missing and not yet healed. */
        var pendingGaps = 0
    }

    private val buffers = mutableMapOf<Long, Buf>()

    /** Counters the hosting transport reads/sums after feeding packets. */
    var duplicatesSeen = 0; private set
    var gapsDetected = 0; private set
    var droppedMessages = 0; private set

    /**
     * Feed one decoded packet from the mesh. Returns a complete [MeshMessage]
     * exactly once per successful delivery.
     */
    fun onPacket(packet: Packet): MeshMessage? {
        purgeStale()
        val messageId = packet.messageId
        when (packet.packetType) {
            PacketType.START -> {
                buffers.getOrPut(messageId) { Buf(packet.language, nowMillis()) }
                return null
            }
            PacketType.TEXT_DATA -> {
                val buf = bufferFor(packet)
                val seq = packet.sequenceId
                if (buf.parts.containsKey(seq)) {
                    duplicatesSeen++
                    return null
                }
                if (seq > buf.maxSeq) {
                    buf.maxSeq = seq
                }
                buf.parts[seq] = packet.payload
                reconcileGap(buf)
                // A flooded network can reorder: the END may arrive while the
                // last DATA packets are still in flight, so re-check completion
                // whenever new data lands after we already saw the END.
                return if (buf.endSeen) tryAssemble(buf, messageId) else null
            }
            PacketType.END -> {
                val buf = bufferFor(packet)
                if (buf.endSeen) {
                    duplicatesSeen++
                    return null
                }
                buf.expectedDataCount = packet.payload.toDataCount()
                buf.endSeen = true
                return tryAssemble(buf, messageId)
            }
            else -> return null
        }
    }

    private fun bufferFor(packet: Packet): Buf {
        val buf = buffers.getOrPut(packet.messageId) { Buf(packet.language, nowMillis()) }
        buf.language = packet.language
        if (packet.priority == 3.toByte()) buf.isEmergency = true
        return buf
    }

    private fun tryAssemble(buf: Buf, messageId: Long): MeshMessage? {
        if (buf.parts.isEmpty() && buf.expectedDataCount <= 0) return null
        if (buf.expectedDataCount > MAX_DATA_PACKETS) {
            // Hostile END guard: never allocate a multi-billion-element Set.
            buffers.remove(messageId)
            droppedMessages++
            return null
        }
        val expected = if (buf.expectedDataCount > 0) {
            (1..buf.expectedDataCount).toSet()
        } else {
            (1..buf.maxSeq).toSet()
        }
        if (buf.parts.keys == expected) {
            buffers.remove(messageId)
            return MeshMessage(
                messageId = messageId,
                payload = Packetizer.assemble(buf.parts),
                language = buf.language,
                isEmergency = buf.isEmergency,
                receivedAt = nowMillis(),
                dataPackets = buf.parts.size
            )
        }
        // Gap: a flooded broadcast can reorder, so the missing packets may
        // still be in flight — never drop a buffer that could still complete.
        // Report the gap once (honest packet-loss signal until healed) and
        // leave the buffer for the purge to reclaim if it never fills in.
        if (!buf.gapsReported) {
            reconcileGap(buf, expected.count { !buf.parts.containsKey(it) })
            buf.gapsReported = true
        }
        return null
    }

    /**
     * Keep [gapsDetected] equal to the number of *currently unresolved* gaps:
     * new holes raise it, stragglers that heal a hole lower it back to zero.
     */
    private fun reconcileGap(buf: Buf, missingCount: Int? = null) {
        val missing = missingCount ?: (1..buf.maxSeq).count { !buf.parts.containsKey(it) }
        val delta = missing - buf.pendingGaps
        if (delta != 0) {
            gapsDetected = (gapsDetected + delta).coerceAtLeast(0)
            buf.pendingGaps = missing
        }
    }

    /** Drop half-messages that have not completed within [retentionMillis]. */
    fun purgeStale(retentionMillis: Long = DEFAULT_RETENTION_MS) {
        if (buffers.isEmpty()) return
        val cutoff = nowMillis() - retentionMillis
        val it = buffers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.firstReceivedAt < cutoff) {
                it.remove()
                droppedMessages++
            }
        }
    }

    internal fun snapshotSize(): Int = buffers.size

    private fun ByteArray.toDataCount(): Int {
        if (size == 4) {
            return ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        }
        return -1
    }

    companion object {
        const val DEFAULT_RETENTION_MS: Long = 30_000L

        /** Upper bound on packets in one mesh message (hostile END guard). */
        const val MAX_DATA_PACKETS = 16_384
    }
}