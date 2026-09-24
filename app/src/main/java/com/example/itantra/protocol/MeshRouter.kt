package com.example.itantra.protocol

import java.util.concurrent.atomic.AtomicLong

/**
 * Routing decision for an incoming [HopPacket].
 *
 * Flooding never addresses a specific peer: every *new* packet is delivered to
 * the local application and (until the TTL runs out) re-broadcast to relay
 * nodes. Duplicates, our own echoes and malformed headers are dropped outright.
 */
sealed class MeshRouting {
    /** New packet: deliver the payload; relay onward with [forwardTtl] if > 0. */
    data class Accept(val forwardTtl: Int) : MeshRouting()

    /** Duplicate / own echo / relay-exhausted: drop entirely. */
    object Drop : MeshRouting()
}

/**
 * Per-node brain of the mesh overlay.
 *
 * Fully pure Kotlin (JVM-testable, framework-free). Responsibilities:
 *
 *  - **Origin**: craft hop envelopes under this node's device id with a unique
 *    per-node [hopId], so their (origin, hopId) pair globally identifies one
 *    transmission instance for duplicate suppression.
 *  - **Duplication**: a bounded seen-set keys on the origin + hopId pair, so two
 *    different nodes can never collide and a packet that arrives over multiple
 *    links is relayed only once.
 *  - **TTL**: prevents infinite flooding; the origin sets a max relay budget and
 *    every relay decrements it.
 *  - **Self-echo drop**: never re-accept or re-broadcast our own envelopes.
 *
 * Delivery/relay policy lives here; the store-and-forward plumbing (which
 * physical edges a node has, one-at-a-time per-hop reliability) lives in the
 * [com.example.itantra.transport.TransportEngine] that hosts this router.
 */
class MeshRouter(
    private val localDeviceId: String,
    private val maxTtl: Int = DEFAULT_MAX_TTL,
    private val seenCapacity: Int = 8192
) {
    companion object {
        const val DEFAULT_MAX_TTL: Int = 8

        /** Envelope header overhead added by [HopPacket] to every relayed frame. */
        const val HOP_HEADER_BYTES: Int = HopPacket.HEADER_SIZE
    }

    private val counter = AtomicLong(System.nanoTime() and 0x000F_FFFFL)

    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > seenCapacity
    }

    /** Unique per-node id for the next [craft]. */
    fun nextHopId(): Long = counter.incrementAndGet()

    /**
     * Wrap one RETRO frame into a new hop envelope originating at this node.
     * [ttl] is clamped to [maxTtl].
     */
    fun craft(frame: ByteArray, ttl: Int = maxTtl): HopPacket =
        HopPacket(
            hopId = nextHopId(),
            origin = localDeviceId,
            ttl = ttl.coerceIn(1, maxTtl),
            hops = 0,
            payload = frame
        )

    /**
     * Decide what to do with an envelope received from the mesh.
     * New packets accept (deliver + possibly relay); anything else is dropped.
     */
    fun receive(packet: HopPacket): MeshRouting {
        if (packet.origin == localDeviceId) return MeshRouting.Drop
        val key = packet.origin + ":" + packet.hopId
        synchronized(seen) {
            if (seen.containsKey(key)) return MeshRouting.Drop
            seen[key] = true
        }
        // The packet has a remaining relay budget of (ttl - 1); a relay is only
        // worth doing when the next receiver still can keep the chain alive.
        return MeshRouting.Accept(if (packet.ttl > 1) packet.ttl - 1 else 0)
    }
}