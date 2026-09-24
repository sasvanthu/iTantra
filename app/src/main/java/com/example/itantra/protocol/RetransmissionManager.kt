package com.example.itantra.protocol

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-message ACK / NACK bookkeeping for RETRO links.
 *
 * Deliberately pure Kotlin (no android.util.Log) so it is unit-testable on the
 * JVM. The transport engine feeds it every tracked DATA packet it sends and
 * every ACK/NACK it receives; it tells the engine what to retransmit.
 *
 * Rules:
 *  - Only DATA packets (sequenceId > 0) are tracked. START/END are not.
 *  - ACK for a tracked packet removes it and reports the RTT.
 *  - NACK triggers an immediate re-send (up to [maxRetries]).
 *  - A packet whose age exceeds [ackTimeoutMs] and is not ack'd is re-sent.
 *  - Once [maxRetries] is exhausted the packet is dropped and reported as
 *    failed via [onMaxRetries].
 */
class RetransmissionManager(
    val ackTimeoutMs: Long = 2000,
    val maxRetries: Int = 3
) {

    data class PacketKey(val messageId: Long, val sequenceId: Int)

    internal data class PendingEntry(
        val packet: Packet,
        var transmitTimeMs: Long,
        var retries: Int = 0
    )

    private val pending = ConcurrentHashMap<PacketKey, PendingEntry>()
    private var now: () -> Long = System::currentTimeMillis

    val retransmissionCount: Int get() = retransmissions.get()
    val failedCount: Int get() = failed.get()
    val pendingCount: Int get() = pending.size

    private val retransmissions = AtomicInteger(0)
    private val failed = AtomicInteger(0)

    /** Invoked when a tracked packet is dropped after exhausting retries. */
    var onMaxRetries: ((PacketKey) -> Unit)? = null

    internal fun setClock(clock: () -> Long) {
        this.now = clock
    }

    fun track(packet: Packet) {
        pending[PacketKey(packet.messageId, packet.sequenceId)] =
            PendingEntry(packet, now())
    }

    fun keyFor(packet: Packet): PacketKey = PacketKey(packet.messageId, packet.sequenceId)

    /** Returns the last RTT (ms) for the acked packet, or null if unknown. */
    fun ack(messageId: Long, sequenceId: Int): Long? {
        val entry = pending.remove(PacketKey(messageId, sequenceId)) ?: return null
        return now() - entry.transmitTimeMs
    }

    /** Drop all tracking for a message without touching the counters. */
    fun dropMessage(messageId: Long) {
        val keys = pending.keys.filter { it.messageId == messageId }
        for (k in keys) pending.remove(k)
    }

    /**
     * Call on NACK: re-schedules the packet for re-transmission.
     * Returns the packet if it may still be re-sent, or null once retries are
     * exhausted (it is marked failed).
     *
     * A NACK for a packet that was re-sent very recently is a duplicate of the
     * same gap report and is ignored: it must not consume the retry budget.
     */
    fun nack(messageId: Long, sequenceId: Int): Packet? {
        val key = PacketKey(messageId, sequenceId)
        val entry = pending[key] ?: return null
        if (now() - entry.transmitTimeMs <= ackTimeoutMs) {
            // Already re-sent within the ACK window: same gap, ignore.
            return null
        }
        entry.retries++
        if (entry.retries > maxRetries) {
            pending.remove(key)
            failed.incrementAndGet()
            onMaxRetries?.invoke(key)
            return null
        }
        retransmissions.incrementAndGet()
        entry.transmitTimeMs = now()
        return entry.packet
    }

    /**
     * Returns packets that have aged past [ackTimeoutMs]: these must be
     * re-sent. Re-sent packets keep their retry budget and simply bump the
     * transmit time so they do not loop unboundedly inside one timeout window.
     */
    fun checkTimeouts(): List<Packet> {
        val nowMs = now()
        val overdue = ArrayList<Packet>()
        for (entry in pending.values) {
            val age = nowMs - entry.transmitTimeMs
            if (age > ackTimeoutMs) {
                entry.retries++
                if (entry.retries > maxRetries) {
                    val key = PacketKey(entry.packet.messageId, entry.packet.sequenceId)
                    pending.remove(key)
                    failed.incrementAndGet()
                    onMaxRetries?.invoke(key)
                    continue
                }
                retransmissions.incrementAndGet()
                entry.transmitTimeMs = nowMs
                overdue.add(entry.packet)
            }
        }
        return overdue
    }

    fun clear() {
        pending.clear()
        retransmissions.set(0)
        failed.set(0)
    }

    fun snapshot(): RetransmissionSnapshot = RetransmissionSnapshot(
        pendingCount = pending.size,
        retransmissions = retransmissions.get(),
        failed = failed.get()
    )
}

data class RetransmissionSnapshot(
    val pendingCount: Int,
    val retransmissions: Int,
    val failed: Int
)