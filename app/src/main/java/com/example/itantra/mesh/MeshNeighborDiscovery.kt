package com.example.itantra.mesh

import com.example.itantra.codec.Language
import com.example.itantra.protocol.HopPacket
import com.example.itantra.protocol.Packet
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Mesh neighbor discovery.
 *
 * A flooding mesh has no directory service: nothing tells a node who else is
 * reachable. This adds a tiny, periodic, self-describing beacon so every node
 * builds an honest picture of its neighborhood from observed traffic only.
 *
 * ```
 *  +--------+---------+--------+--------+--------+---------+--------+--------+
 *  | magic  | version | bootId | origin | peersN | peers   | lang   | crc+fl |
 *  | 4 bytes| 1 byte  | 8 bytes|16 bytes| 1 byte | 1+N*(1+8)| 2 bytes| 5 bytes|
 *  +--------+---------+--------+--------+--------+---------+--------+--------+
 * ```
 *
 * A node floods its own beacon out of every connected edge every
 * [DEFAULT_BEACON_INTERVAL_MS]. Peers record the sender as a **direct**
 * neighbor and the sender's advertised peers as **two-hop** neighbors, which is
 * how topology is learned without any central registry. Relay, dedup and TTL
 * come from the existing [com.example.itantra.protocol.MeshRouter]: the beacon
 * rides the same hop envelope as a message.
 *
 * The beacon is deliberately tiny and periodic, not chatty. Radio time is the
 * scarcest resource in a disaster, and a topology map that costs more than the
 * traffic it describes is worse than none.
 */
data class MeshBeacon(
    val origin: String,
    val bootId: Long,
    val peers: List<String>,
    val languageMask: Int,
    val flags: Int = 0
) {
    fun serialize(): ByteArray {
        val bounded = peers.take(MAX_PEERS)
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION)
        for (shift in 56 downTo 0 step 8) out.write(((bootId ushr shift) and 0xFF).toInt())
        out.write(HopPacket.encodeOrigin(origin))
        out.write(bounded.size)
        for (peer in bounded) {
            val id = peer.take(PEER_ID_BYTES).toByteArray(Charsets.US_ASCII)
            out.write(id.size)
            out.write(id)
        }
        out.write((languageMask shr 8) and 0xFF)
        out.write(languageMask and 0xFF)
        val header = out.toByteArray()
        val crc = Packet.computeCRC(header)
        out.write(
            byteArrayOf(
                (crc shr 24).toByte(), (crc shr 16).toByte(),
                (crc shr 8).toByte(), crc.toByte()
            )
        )
        out.write(flags and 0xFF)
        return out.toByteArray()
    }

    /** Languages this node claims, decoded from the advertisement mask. */
    fun advertisedLanguages(): Set<Language> = decodeLanguageMask(languageMask)

    companion object {
        val MAGIC = byteArrayOf(0x4E, 0x42, 0x43, 0x4E) // 'N','B','C','N'
        const val VERSION: Int = 1

        /**
         * Peer ids share the hop envelope's origin width (16 B). The mesh node
         * id itself is 9 characters, so anything narrower would truncate the id
         * we most need to read back exactly.
         */
        const val PEER_ID_BYTES: Int = 16
        const val MAX_PEERS: Int = 8
        const val CRC_BYTES: Int = 4

        /** Fixed header before the variable peer list: magic..bootId+origin+count. */
        private const val FIXED_HEADER = 4 + 1 + 8 + 16 + 1
        /** Fixed trailer after the peer list: language mask + crc + flags. */
        private const val FIXED_TRAILER = 2 + CRC_BYTES + 1

        const val MAX_BYTES: Int = FIXED_HEADER + (MAX_PEERS * (1 + PEER_ID_BYTES)) + FIXED_TRAILER

        fun hasMagic(data: ByteArray): Boolean =
            data.size >= 4 && data[0] == MAGIC[0] && data[1] == MAGIC[1] &&
                data[2] == MAGIC[2] && data[3] == MAGIC[3]

        /** Pack a language set into the 16-bit advertisement mask. */
        fun languageMaskOf(languages: Collection<Language>): Int =
            languages.fold(0) { acc, lang -> acc or (1 shl lang.wireByte.toInt()) }

        fun decodeLanguageMask(mask: Int): Set<Language> =
            Language.values().filter { (mask shr it.wireByte.toInt()) and 1 == 1 }.toSet()

        /** A fresh boot id: distinguishes a rebooted device from the same id online. */
        fun newBootId(): Long = UUID.randomUUID().mostSignificantBits

        fun deserialize(data: ByteArray): MeshBeacon? {
            if (data.size < FIXED_HEADER + FIXED_TRAILER || !hasMagic(data)) return null
            if ((data[4].toInt() and 0xFF) != VERSION) return null

            var offset = 5
            var bootId = 0L
            for (i in 0 until 8) bootId = (bootId shl 8) or (data[offset + i].toLong() and 0xFF)
            offset += 8
            val origin = String(data, offset, 16, Charsets.US_ASCII)
                .substringBefore('\u0000').trim()
            offset += 16

            val peerCount = data[offset].toInt() and 0xFF
            offset += 1
            if (peerCount > MAX_PEERS) return null

            val peers = ArrayList<String>(peerCount)
            repeat(peerCount) {
                if (offset >= data.size) return null
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (len > PEER_ID_BYTES || offset + len > data.size) return null
                peers.add(String(data, offset, len, Charsets.US_ASCII))
                offset += len
            }
            if (offset + FIXED_TRAILER > data.size) return null

            val mask = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            val header = data.copyOfRange(0, offset)
            val storedCrc = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (Packet.computeCRC(header) != storedCrc) return null
            offset += CRC_BYTES

            val flags = data[offset].toInt() and 0xFF
            return MeshBeacon(
                origin = origin,
                bootId = bootId,
                peers = peers,
                languageMask = mask,
                flags = flags
            )
        }
    }
}

/** One discovered neighbor, as observed from traffic — never invented. */
data class MeshNeighbor(
    val deviceId: String,
    val direct: Boolean,
    val hops: Int,
    val languages: Set<Language>,
    val firstSeenMs: Long,
    val lastSeenMs: Long
) {
    /**
     * Seconds since this neighbor last announced itself, for display. Computed
     * against [nowMs] so the UI does not need its own timer to stay honest
     * about how stale an entry is.
     */
    fun ageSeconds(nowMs: Long): Long =
        ((nowMs - lastSeenMs).coerceAtLeast(0L)) / 1000L
}

/** Honest neighborhood view for this mesh node. */
data class MeshNeighborSnapshot(
    val localDeviceId: String,
    val direct: List<MeshNeighbor> = emptyList(),
    val twoHop: List<MeshNeighbor> = emptyList(),
    val beaconsSent: Long = 0,
    val beaconsHeard: Long = 0,
    val lastBeaconMs: Long = 0
) {
    val neighborCount: Int get() = direct.size + twoHop.size
    val isAlone: Boolean get() = direct.isEmpty()
}

/**
 * Thread-safe neighbor registry.
 *
 * A peer is **direct** when we heard its own beacon (one hop away) and
 * **two-hop** when we only know it because a direct neighbor advertised it.
 * Direct evidence always wins: hearing from a node directly promotes it and
 * demotes any stale two-hop entry for the same id. Entries expire after
 * [neighborTtlMs] without a fresh beacon, so a departed node disappears instead
 * of lingering as a ghost in the topology.
 */
class MeshNeighborTable(
    val localDeviceId: String,
    private val neighborTtlMs: Long = DEFAULT_NEIGHBOR_TTL_MS
) {
    companion object {
        const val DEFAULT_NEIGHBOR_TTL_MS: Long = 45_000L
        const val DEFAULT_BEACON_INTERVAL_MS: Long = 10_000L
    }

    private data class Entry(
        val deviceId: String,
        val direct: Boolean,
        val hops: Int,
        val languages: Set<Language>,
        val firstSeenMs: Long,
        val lastSeenMs: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val beaconsSent = AtomicLong(0)
    private val beaconsHeard = AtomicLong(0)
    private val lastBeaconMs = AtomicLong(0)

    /**
     * Fold one received [beacon] into the table.
     *
     * [relayHops] is how many mesh hops the envelope travelled to reach us:
     * 0 means the sender is on the far end of a link we hold ourselves, so it is
     * a **direct** neighbor. A beacon that reached us over a relay is proof that
     * the origin is *not* adjacent, so it is filed as two-hop instead of being
     * passed off as a direct link. That relayed beacon's peer list is already
     * three or more hops away from us, so it is ignored rather than counted as
     * a neighbor we can actually reach.
     */
    fun observe(beacon: MeshBeacon, relayHops: Int, nowMs: Long): Boolean {
        if (beacon.origin.isBlank() || beacon.origin == localDeviceId) return false
        val direct = relayHops <= 0
        merge(
            beacon.origin,
            direct = direct,
            languages = MeshBeacon.decodeLanguageMask(beacon.languageMask),
            nowMs = nowMs
        )
        if (direct) {
            for (peer in beacon.peers) {
                if (peer == localDeviceId || peer == beacon.origin) continue
                merge(peer, direct = false, languages = emptySet(), nowMs = nowMs)
            }
        }
        beaconsHeard.incrementAndGet()
        return true
    }

    /** Record a beacon this node just flooded. */
    fun noteSent(nowMs: Long) {
        beaconsSent.incrementAndGet()
        lastBeaconMs.set(nowMs)
    }

    private fun merge(deviceId: String, direct: Boolean, languages: Set<Language>, nowMs: Long) {
        if (deviceId.isBlank() || deviceId == localDeviceId) return
        entries.compute(deviceId) { _, existing ->
            when {
                existing == null -> Entry(
                    deviceId = deviceId,
                    direct = direct,
                    hops = if (direct) 1 else 2,
                    languages = languages,
                    firstSeenMs = nowMs,
                    lastSeenMs = nowMs
                )
                // Direct evidence promotes a two-hop entry and keeps its history.
                direct && !existing.direct -> existing.copy(
                    direct = true,
                    hops = 1,
                    languages = if (languages.isEmpty()) existing.languages else languages,
                    lastSeenMs = nowMs
                )
                // Stale two-hop hearsay must not overwrite what we heard directly.
                !direct && existing.direct -> existing
                else -> existing.copy(
                    languages = if (languages.isEmpty()) existing.languages else languages,
                    lastSeenMs = nowMs
                )
            }
        }
    }

    /** Drop entries not heard within [neighborTtlMs]; returns the ids that expired. */
    fun expire(nowMs: Long): List<String> {
        val expired = entries.values
            .filter { nowMs - it.lastSeenMs > neighborTtlMs }
            .map { it.deviceId }
        expired.forEach { entries.remove(it) }
        return expired
    }

    fun snapshot(nowMs: Long): MeshNeighborSnapshot {
        val live = entries.values.filter { nowMs - it.lastSeenMs <= neighborTtlMs }
        val toNeighbor = { e: Entry ->
            MeshNeighbor(
                deviceId = e.deviceId,
                direct = e.direct,
                hops = e.hops,
                languages = e.languages,
                firstSeenMs = e.firstSeenMs,
                lastSeenMs = e.lastSeenMs
            )
        }
        return MeshNeighborSnapshot(
            localDeviceId = localDeviceId,
            direct = live.filter { it.direct }.map(toNeighbor).sortedBy { it.deviceId },
            twoHop = live.filterNot { it.direct }.map(toNeighbor).sortedBy { it.deviceId },
            beaconsSent = beaconsSent.get(),
            beaconsHeard = beaconsHeard.get(),
            lastBeaconMs = lastBeaconMs.get()
        )
    }

    fun clear() {
        entries.clear()
        beaconsSent.set(0)
        beaconsHeard.set(0)
        lastBeaconMs.set(0)
    }
}