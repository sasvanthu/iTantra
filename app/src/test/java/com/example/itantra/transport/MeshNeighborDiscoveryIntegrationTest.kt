package com.example.itantra.transport

import com.example.itantra.codec.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Neighbor discovery over the real mesh overlay.
 *
 * Nodes are [MeshTransportEngine]s whose edges are `SimulatedTransport` pairs,
 * so beacons travel the same framing, CRC, per-hop reliability, dedup and TTL
 * relay path as a message. A three-node line A -> B -> C proves the property
 * that matters in a disaster: the middle node learns who it can reach, and who
 * its own peers can reach, without any directory service.
 */
class MeshNeighborDiscoveryIntegrationTest {

    private class SimPair(val host: SimulatedTransport, val device: SimulatedTransport)

    private fun enginePair(ackTimeout: Long = 200, maxRetries: Int = 8, seed: Long? = null): SimPair {
        val host = SimulatedTransport(NetworkSimulator(seed), ackTimeoutMs = ackTimeout, maxRetries = maxRetries)
        val device = SimulatedTransport(
            NetworkSimulator(seed?.let { it + 1 }),
            ackTimeoutMs = ackTimeout,
            maxRetries = maxRetries
        )
        host.bindPeer(device)
        return SimPair(host, device)
    }

    private suspend fun SimPair.up() = coroutineScope {
        val results = listOf(
            async { host.startHost(0) },
            async { device.connect("peer", 0) }
        ).awaitAll()
        assertTrue("host handshake", results[0])
        assertTrue("device handshake", results[1])
    }

    private fun node(beaconIntervalMs: Long = 1_000): MeshTransportEngine =
        MeshTransportEngine(
            neighborDiscoveryEnabled = true,
            beaconIntervalMs = beaconIntervalMs
        )

    private suspend fun collectPayloads(
        engine: TransportEngine,
        scope: CoroutineScope
    ): Pair<Job, MutableList<ReassembledPayload>> {
        val received = mutableListOf<ReassembledPayload>()
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            ready.complete(Unit)
            engine.incomingPayloads.collect { received.add(it) }
        }
        ready.await()
        return job to received
    }

    @Test
    fun `a three node line discovers its own topology without a directory service`() = runBlocking {
        val ab = enginePair(seed = 11L); ab.up()
        val bc = enginePair(seed = 12L); bc.up()

        val a = node()
        val b = node()
        val c = node()

        a.addEdge(ab.host)
        b.addEdge(ab.device)
        b.addEdge(bc.host)
        c.addEdge(bc.device)

        a.awaitEdgeReady(ab.host)
        b.awaitEdgeReady(ab.device)
        b.awaitEdgeReady(bc.host)
        c.awaitEdgeReady(bc.device)

        // Each end node must come to see the relay directly.
        withTimeout(30_000) { while (a.neighbors.value.direct.isEmpty()) delay(20) }
        withTimeout(30_000) { while (c.neighbors.value.direct.isEmpty()) delay(20) }

        val aId = a.getDeviceId()
        val bId = b.getDeviceId()
        val cId = c.getDeviceId()

        assertTrue("A must see B as a direct neighbor", a.neighbors.value.direct.any { it.deviceId == bId })
        assertTrue("C must see B as a direct neighbor", c.neighbors.value.direct.any { it.deviceId == bId })

        // The relay learns both ends, and must never list itself.
        withTimeout(30_000) {
            while (b.neighbors.value.direct.count { it.deviceId == aId || it.deviceId == cId } < 2) delay(20)
        }
        val bSnapshot = b.neighbors.value
        assertTrue("B must see A directly", bSnapshot.direct.any { it.deviceId == aId })
        assertTrue("B must see C directly", bSnapshot.direct.any { it.deviceId == cId })
        assertFalse("B must never be its own neighbor", bSnapshot.direct.any { it.deviceId == bId })
        assertTrue(bSnapshot.beaconsHeard > 0)
        assertTrue(bSnapshot.beaconsSent > 0)

        // Topology really propagates: A hears only B's own beacon, but B's
        // beacon advertises C, so A learns C at two hops without ever hearing
        // from C. That is the feature working, not a node inventing a peer.
        withTimeout(30_000) { while (a.neighbors.value.twoHop.none { it.deviceId == cId }) delay(20) }
        val aSnapshot = a.neighbors.value
        assertTrue("A must see B directly", aSnapshot.direct.any { it.deviceId == bId })
        assertTrue("A must learn C at two hops via B", aSnapshot.twoHop.any { it.deviceId == cId })
        assertTrue("A must not claim itself", aSnapshot.direct.none { it.deviceId == aId })
        assertFalse("A must never list itself at two hops", aSnapshot.twoHop.any { it.deviceId == aId })
        assertEquals("A's view is B directly plus C via B", 2, aSnapshot.neighborCount)

        // A is three hops' worth of traffic away, so it must never claim C as
        // a direct link just because B relayed C's beacon.
        assertFalse(
            "a relayed beacon must not become a direct link",
            aSnapshot.direct.any { it.deviceId == cId }
        )
        assertTrue(aSnapshot.twoHop.first { it.deviceId == cId }.hops == 2)
    }

    @Test
    fun `the far end of a line is never reported as a direct neighbor`() = runBlocking {
        val ab = enginePair(seed = 31L); ab.up()
        val bc = enginePair(seed = 32L); bc.up()

        val a = node()
        val b = node()
        val c = node()
        a.addEdge(ab.host); b.addEdge(ab.device); b.addEdge(bc.host); c.addEdge(bc.device)
        a.awaitEdgeReady(ab.host); b.awaitEdgeReady(ab.device)
        b.awaitEdgeReady(bc.host); c.awaitEdgeReady(bc.device)

        val aId = a.getDeviceId()
        val bId = b.getDeviceId()
        val cId = c.getDeviceId()

        withTimeout(30_000) { while (b.neighbors.value.neighborCount < 2) delay(20) }
        withTimeout(30_000) { while (c.neighbors.value.twoHop.none { it.deviceId == aId }) delay(20) }
        delay(3_000) // let the topology settle before asserting the absence of a false link

        // B sits in the middle, so it is the only node with two direct links.
        assertEquals(
            "only the relay has two direct neighbors",
            listOf(aId, cId).sorted(),
            b.neighbors.value.direct.map { it.deviceId }.sorted()
        )
        assertTrue(c.neighbors.value.direct.any { it.deviceId == bId })
        assertFalse(
            "C is two hops from A, so A must not be a direct link",
            c.neighbors.value.direct.any { it.deviceId == aId }
        )
        assertFalse(
            "A is two hops from C, so C must not be a direct link",
            a.neighbors.value.direct.any { it.deviceId == cId }
        )
    }

    @Test
    fun `a node hears itself advertised and is not counted twice`() = runBlocking {
        val ab = enginePair(seed = 21L); ab.up()
        val bc = enginePair(seed = 22L); bc.up()

        val a = node()
        val b = node()
        val c = node()
        a.addEdge(ab.host); b.addEdge(ab.device); b.addEdge(bc.host); c.addEdge(bc.device)
        a.awaitEdgeReady(ab.host); b.awaitEdgeReady(ab.device)
        b.awaitEdgeReady(bc.host); c.awaitEdgeReady(bc.device)

        withTimeout(30_000) { while (b.neighbors.value.neighborCount < 2) delay(20) }

        val snapshot = b.neighbors.value
        val ids = snapshot.direct.map { it.deviceId } + snapshot.twoHop.map { it.deviceId }
        assertEquals("a node must appear at most once", ids.size, ids.toSet().size)
        assertFalse("self must never be a neighbor", ids.contains(b.getDeviceId()))
    }

    @Test
    fun `beacons are presence data and never surface as application payloads`() = runBlocking {
        val ab = enginePair(seed = 31L); ab.up()

        val a = node(beaconIntervalMs = 500)
        val b = node(beaconIntervalMs = 500)
        a.addEdge(ab.host)
        b.addEdge(ab.device)
        a.awaitEdgeReady(ab.host)
        b.awaitEdgeReady(ab.device)

        val (aJob, aReceived) = collectPayloads(a, this)
        val (bJob, bReceived) = collectPayloads(b, this)

        // Let several beacon rounds go by.
        withTimeout(30_000) { while (b.neighbors.value.beaconsHeard < 2) delay(20) }
        assertTrue(a.neighbors.value.beaconsSent >= 1)

        // Beacons must be invisible to the app payload stream on both ends.
        assertTrue("A must not receive beacon payloads", aReceived.isEmpty())
        assertTrue("B must not receive beacon payloads", bReceived.isEmpty())

        // A real message still flows normally alongside the beacons.
        val target = "real traffic alongside presence".encodeToByteArray()
        val result = a.send(target, Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)
        withTimeout(15_000) { while (bReceived.isEmpty()) delay(10) }
        assertArrayEquals(target, bReceived.single().payload)
        assertEquals("exactly one application message", 1, bReceived.size)

        aJob.cancel(); bJob.cancel()
    }

    @Test
    fun `disabling discovery keeps the mesh silent and honest`() = runBlocking {
        val ab = enginePair(seed = 41L); ab.up()

        val a = MeshTransportEngine(neighborDiscoveryEnabled = false)
        val b = MeshTransportEngine(neighborDiscoveryEnabled = false)
        a.addEdge(ab.host)
        b.addEdge(ab.device)
        a.awaitEdgeReady(ab.host)
        b.awaitEdgeReady(ab.device)

        val (aJob, aReceived) = collectPayloads(a, this)
        val (bJob, bReceived) = collectPayloads(b, this)

        // Several beacon intervals with no discovery configured.
        delay(3_000)
        assertTrue("no neighbors without discovery", a.neighbors.value.isAlone)
        assertEquals(0, a.neighbors.value.beaconsSent)
        assertEquals(0, a.neighbors.value.beaconsHeard)
        assertTrue("no traffic without discovery", aReceived.isEmpty() && bReceived.isEmpty())

        // The mesh itself still works.
        val target = "discovery off, mesh on".encodeToByteArray()
        assertFalse(a.send(target, Language.ENGLISH, isEmergency = false, priority = 2)!!.failed)
        withTimeout(15_000) { while (bReceived.isEmpty()) delay(10) }
        assertArrayEquals(target, bReceived.single().payload)

        aJob.cancel(); bJob.cancel()
    }

    @Test
    fun `a message still arrives exactly once while beacons are flooding`() = runBlocking {
        val ab = enginePair(seed = 51L); ab.up()
        val bc = enginePair(seed = 52L); bc.up()

        val a = node(beaconIntervalMs = 400)
        val b = node(beaconIntervalMs = 400)
        val c = node(beaconIntervalMs = 400)
        a.addEdge(ab.host); b.addEdge(ab.device); b.addEdge(bc.host); c.addEdge(bc.device)
        a.awaitEdgeReady(ab.host); b.awaitEdgeReady(ab.device)
        b.awaitEdgeReady(bc.host); c.awaitEdgeReady(bc.device)

        val (cJob, cReceived) = collectPayloads(c, this)
        withTimeout(30_000) { while (b.neighbors.value.neighborCount < 2) delay(20) }

        val target = "presence must not duplicate a message".encodeToByteArray()
        val result = a.send(target, Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)

        withTimeout(20_000) { while (cReceived.isEmpty()) delay(10) }
        // Let in-flight relay/retry plus any concurrent beacon settle.
        delay(2_000)
        assertArrayEquals(target, cReceived.single().payload)
        assertEquals("beacon flooding must not duplicate the payload", 1, cReceived.size)
        assertTrue("relay still engaged", b.neighbors.value.neighborCount >= 2)

        cJob.cancel()
    }
}
