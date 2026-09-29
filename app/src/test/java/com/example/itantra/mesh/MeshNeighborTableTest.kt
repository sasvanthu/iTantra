package com.example.itantra.mesh

import com.example.itantra.codec.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Topology-table semantics: promotion, precedence, expiry, honesty. */
class MeshNeighborTableTest {

    private fun beacon(
        origin: String,
        peers: List<String> = emptyList(),
        languages: List<Language> = emptyList()
    ) = MeshBeacon(
        origin = origin,
        bootId = 1L,
        peers = peers,
        languageMask = MeshBeacon.languageMaskOf(languages)
    )

    @Test
    fun `a heard beacon makes the sender a direct neighbor`() {
        val table = MeshNeighborTable("DLOCAL")
        assertTrue(table.snapshot(0).isAlone)

        table.observe(beacon("DAAAA", languages = listOf(Language.TAMIL)), relayHops = 0, nowMs = 1_000)

        val snap = table.snapshot(1_000)
        assertEquals(1, snap.direct.size)
        assertEquals("DAAAA", snap.direct.single().deviceId)
        assertEquals(1, snap.direct.single().hops)
        assertEquals(setOf(Language.TAMIL), snap.direct.single().languages)
        assertTrue(snap.twoHop.isEmpty())
        assertEquals(1, snap.beaconsHeard)
    }

    @Test
    fun `advertised peers are learned as two hop`() {
        val table = MeshNeighborTable("DLOCAL")
        table.observe(beacon("DAAAA", peers = listOf("DBBBB", "DCCCC")), relayHops = 0, nowMs = 1_000)

        val snap = table.snapshot(1_000)
        assertEquals(listOf("DAAAA"), snap.direct.map { it.deviceId })
        assertEquals(listOf("DBBBB", "DCCCC"), snap.twoHop.map { it.deviceId })
        assertTrue(snap.twoHop.all { it.hops == 2 })
        assertEquals(3, snap.neighborCount)
    }

    @Test
    fun `hearing a node directly promotes it above hearsay`() {
        val table = MeshNeighborTable("DLOCAL")
        // C hears about B through A first.
        table.observe(beacon("DAAAA", peers = listOf("DBBBB")), relayHops = 0, nowMs = 1_000)
        assertEquals(2, table.snapshot(1_000).twoHop.single().hops)

        // Then B's own beacon arrives: B must become direct, not stay hearsay.
        table.observe(beacon("DBBBB", languages = listOf(Language.HINDI)), relayHops = 0, nowMs = 2_000)

        val snap = table.snapshot(2_000)
        assertEquals(listOf("DAAAA", "DBBBB"), snap.direct.map { it.deviceId })
        assertTrue("promotion must clear the two hop entry", snap.twoHop.isEmpty())
        assertEquals(setOf(Language.HINDI), snap.direct.first { it.deviceId == "DBBBB" }.languages)
    }

    @Test
    fun `stale hearsay never overwrites directly observed evidence`() {
        val table = MeshNeighborTable("DLOCAL")
        table.observe(beacon("DAAAA", languages = listOf(Language.TAMIL)), relayHops = 0, nowMs = 1_000)
        val learnedAt = table.snapshot(1_000).direct.single()

        // A hears DAAAA's claim about itself later; that must not rewrite what
        // DAAAA directly advertised, and must not demote it.
        table.observe(beacon("DZZZZ", peers = listOf("DAAAA")), relayHops = 0, nowMs = 2_000)

        val direct = table.snapshot(2_000).direct.first { it.deviceId == "DAAAA" }
        assertEquals(setOf(Language.TAMIL), direct.languages)
        assertEquals(learnedAt.firstSeenMs, direct.firstSeenMs)
    }

    @Test
    fun `a departed neighbor expires instead of lingering as a ghost`() {
        val table = MeshNeighborTable("DLOCAL", neighborTtlMs = 10_000)
        table.observe(beacon("DAAAA"), relayHops = 0, nowMs = 1_000)
        assertEquals(1, table.snapshot(5_000).neighborCount)

        val expired = table.expire(20_000)
        assertEquals(listOf("DAAAA"), expired)
        val snap = table.snapshot(20_000)
        assertTrue("a node past its TTL is not a neighbor", snap.isAlone)
        assertEquals(0, snap.neighborCount)
    }

    @Test
    fun `a fresh beacon keeps a neighbor alive indefinitely`() {
        val table = MeshNeighborTable("DLOCAL", neighborTtlMs = 10_000)
        var now = 1_000L
        repeat(20) {
            table.observe(beacon("DAAAA"), relayHops = 0, nowMs = now)
            now += 9_000
        }
        assertEquals(1, table.snapshot(now).neighborCount)
    }

    @Test
    fun `our own beacon is never counted as a neighbor`() {
        val table = MeshNeighborTable("DLOCAL")
        assertFalse(table.observe(beacon("DLOCAL"), relayHops = 0, nowMs = 1_000))
        assertTrue(table.snapshot(1_000).isAlone)
    }

    @Test
    fun `a node never becomes a neighbor of itself via hearsay`() {
        val table = MeshNeighborTable("DLOCAL")
        table.observe(beacon("DAAAA", peers = listOf("DLOCAL", "DAAAA")), relayHops = 0, nowMs = 1_000)
        val snap = table.snapshot(1_000)
        assertEquals(listOf("DAAAA"), snap.direct.map { it.deviceId })
        assertTrue(snap.twoHop.none { it.deviceId == "DLOCAL" })
    }

    @Test
    fun `sent beacons are counted and timestamped for honest reporting`() {
        val table = MeshNeighborTable("DLOCAL")
        table.noteSent(500)
        table.noteSent(1_500)
        val snap = table.snapshot(1_500)
        assertEquals(2, snap.beaconsSent)
        assertEquals(1_500, snap.lastBeaconMs)
        assertEquals(0, snap.beaconsHeard)
    }

    @Test
    fun `a beacon that arrived over a relay is two hop, never a direct link`() {
        val table = MeshNeighborTable("DLOCAL")
        // B relays A's beacon to us: A is reachable, but only through B.
        table.observe(
            beacon("DAAAA", peers = listOf("DCCCC"), languages = listOf(Language.TAMIL)),
            relayHops = 1,
            nowMs = 1_000
        )

        val snap = table.snapshot(1_000)
        assertTrue("a relayed origin is not adjacent", snap.direct.isEmpty())
        assertEquals(listOf("DAAAA"), snap.twoHop.map { it.deviceId })
        assertEquals(2, snap.twoHop.single().hops)
        assertTrue("a relayed beacon's peers are three hops out, not neighbors", snap.twoHop.none { it.deviceId == "DCCCC" })
    }

    @Test
    fun `a directly heard node outranks the same node heard over a relay`() {
        val table = MeshNeighborTable("DLOCAL")
        table.observe(beacon("DAAAA", languages = listOf(Language.TAMIL)), relayHops = 0, nowMs = 1_000)
        // A stale relay copy of the same node must not demote the direct link.
        table.observe(beacon("DAAAA", languages = listOf(Language.TAMIL)), relayHops = 2, nowMs = 2_000)

        val snap = table.snapshot(2_000)
        assertEquals(listOf("DAAAA"), snap.direct.map { it.deviceId })
        assertTrue("a relayed copy must not create a two hop duplicate", snap.twoHop.isEmpty())
    }

    @Test
    fun `hearing a relayed node then hearing it directly promotes it`() {
        val table = MeshNeighborTable("DLOCAL")
        table.observe(beacon("DAAAA"), relayHops = 1, nowMs = 1_000)
        assertEquals(2, table.snapshot(1_000).twoHop.single().hops)

        table.observe(beacon("DAAAA", languages = listOf(Language.TAMIL)), relayHops = 0, nowMs = 2_000)
        val snap = table.snapshot(2_000)
        assertEquals(listOf("DAAAA"), snap.direct.map { it.deviceId })
        assertTrue("promotion must clear the two hop entry", snap.twoHop.isEmpty())
    }

    @Test
    fun `clear wipes the topology rather than leaving stale entries`() {
        val table = MeshNeighborTable("DLOCAL")
        table.observe(beacon("DAAAA", peers = listOf("DBBBB")), relayHops = 0, nowMs = 1_000)
        table.noteSent(1_000)
        table.clear()
        val snap = table.snapshot(1_000)
        assertTrue(snap.isAlone)
        assertEquals(0, snap.beaconsSent)
        assertEquals(0, snap.beaconsHeard)
    }

    @Test
    fun `snapshot ordering is stable so the UI does not jitter`() {
        val table = MeshNeighborTable("DLOCAL")
        table.observe(beacon("DZZZZ"), relayHops = 0, nowMs = 1_000)
        table.observe(beacon("DAAAA"), relayHops = 0, nowMs = 1_000)
        table.observe(beacon("DMMMM"), relayHops = 0, nowMs = 1_000)
        assertEquals(listOf("DAAAA", "DMMMM", "DZZZZ"), table.snapshot(1_000).direct.map { it.deviceId })
    }
}
