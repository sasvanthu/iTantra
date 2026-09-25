package com.example.itantra.transport

import com.example.itantra.codec.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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
 * The mesh overlay composed over real transport engines.
 *
 * Nodes are MeshTransportEngines whose edges are SimulatedTransport pairs, so
 * this exercises the real path the app would take on Wi-Fi/BLE edges: per-hop
 * reliability + hop-envelope flooding + per-node dedup + TTL relay + broadcast
 * reassembly. A three-node line (A -> B -> C) and a two-edge fan-in (two
 * redundant links into one node) cover the key behaviours.
 */
class MeshTransportIntegrationTest {

    private class SimPair(
        val host: SimulatedTransport,
        val device: SimulatedTransport,
        val hostSim: NetworkSimulator
    )

    private fun enginePair(ackTimeout: Long = 150, maxRetries: Int = 6): SimPair {
        val hostSim = NetworkSimulator()
        val host = SimulatedTransport(hostSim, ackTimeoutMs = ackTimeout, maxRetries = maxRetries)
        val device = SimulatedTransport(NetworkSimulator(), ackTimeoutMs = ackTimeout, maxRetries = maxRetries)
        host.bindPeer(device)
        return SimPair(host, device, hostSim)
    }

    private suspend fun SimPair.up() = coroutineScope {
        val results = listOf(
            async { host.startHost(0) },
            async { device.connect("peer", 0) }
        ).awaitAll()
        assertTrue("host handshake", results[0])
        assertTrue("device handshake", results[1])
    }

    private suspend fun awaitSize(list: List<*>, size: Int, timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            while (list.size < size) delay(5)
        }
    }

    private suspend fun collectPayloads(
        engine: TransportEngine,
        scope: CoroutineScope,
        predicate: (ReassembledPayload) -> Boolean = { true }
    ): Pair<Job, MutableList<ReassembledPayload>> {
        val received = mutableListOf<ReassembledPayload>()
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            ready.complete(Unit)
            engine.incomingPayloads.collect { if (predicate(it)) received.add(it) }
        }
        ready.await()
        return job to received
    }

    @Test
    fun `three node line relays a message end to end via store and forward`() = runBlocking {
        // Generous ack budget: under parallel CI load a hop may retransmit; the
        // mesh layer's dedup must still delivery exactly once.
        val ab = enginePair(ackTimeout = 250, maxRetries = 12); ab.up()
        val bc = enginePair(ackTimeout = 250, maxRetries = 12); bc.up()

        val a = MeshTransportEngine()
        val b = MeshTransportEngine()
        val c = MeshTransportEngine()

        a.addEdge(ab.host)
        b.addEdge(ab.device)
        b.addEdge(bc.host)
        c.addEdge(bc.device)

        a.awaitEdgeReady(ab.host)
        b.awaitEdgeReady(ab.device)
        b.awaitEdgeReady(bc.host)
        c.awaitEdgeReady(bc.device)

        assertEquals(ConnectionStatus.CONNECTED, a.connectionStatus.value)
        assertEquals(ConnectionStatus.CONNECTED, b.connectionStatus.value)
        assertEquals(ConnectionStatus.CONNECTED, c.connectionStatus.value)

        val (bJob, bReceived) = collectPayloads(b, this)
        val (cJob, cReceived) = collectPayloads(c, this)

        val target = "relay this far down the chain".encodeToByteArray()
        val result = a.send(target, Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)

        awaitSize(bReceived, 1)
        awaitSize(cReceived, 1)
        assertArrayEquals(target, bReceived[0].payload)
        assertArrayEquals(target, cReceived[0].payload)
        assertEquals(Language.ENGLISH, cReceived[0].language)

        // The relay node sees both hops: A's device-id and C's device-id.
        val bNeighbors = b.session.value!!.remoteDeviceId
        assertTrue(bNeighbors.contains(ab.host.getDeviceId()))
        assertTrue(bNeighbors.contains(bc.device.getDeviceId()))

        // Exactly-once delivery per node: let any in-flight relay/retry settle,
        // then confirm each node still received just one copy. Legitimate hop
        // retransmissions become deduped drops at the mesh layer, never
        // duplicate deliveries.
        delay(1_000)
        assertEquals("exactly-once delivery at B", 1, bReceived.size)
        assertEquals("exactly-once delivery at C", 1, cReceived.size)

        bJob.cancel(); cJob.cancel()
        a.disconnect(); b.disconnect(); c.disconnect()
    }

    @Test
    fun `redundant edges fan a message into one node exactly once`() = runBlocking {
        val up1 = enginePair(); up1.up()
        val up2 = enginePair(); up2.up()

        val upstream = MeshTransportEngine()
        val node = MeshTransportEngine()

        upstream.addEdge(up1.host)
        upstream.addEdge(up2.host)
        node.addEdge(up1.device)
        node.addEdge(up2.device)

        upstream.awaitEdgeReady(up1.host)
        upstream.awaitEdgeReady(up2.host)
        node.awaitEdgeReady(up1.device)
        node.awaitEdgeReady(up2.device)

        val (job, received) = collectPayloads(node, this)

        val target = "duplicate fan-in must deliver once".encodeToByteArray()
        val result = upstream.send(target, Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)

        awaitSize(received, 1)
        // Parity in a warm system is quick, but the full suite runs in parallel
        // on shared CPUs; allow generous reconcile time before asserting dedup.
        withTimeout(15_000) { while (node.linkMetrics.value.duplicatePackets < 1) delay(10) }
        // Settle so a late duplicate copy can reveal itself, then assert the
        // redundant edge's copy was deduped to exactly one delivery.
        delay(500)
        assertArrayEquals(target, received[0].payload)
        assertEquals(1, received.size)
        assertTrue("the second copy must be dropped", node.linkMetrics.value.duplicatePackets >= 1)

        job.cancel()
        upstream.disconnect(); node.disconnect()
    }

    @Test
    fun `hop edge loss is healed by the link reliability and mesh still delivers`() = runBlocking {
        val up = enginePair(ackTimeout = 150, maxRetries = 12); up.up()
        // SIMULATION on the hop, after handshake. 50% loss over ~30 frames makes
        // the draw effectively deterministic (P(no drop) ~= 1e-9), so the
        // healing assertions never depend on luck.
        up.hostSim.config.lossRate = 0.5f

        val sender = MeshTransportEngine()
        val node = MeshTransportEngine()

        sender.addEdge(up.host)
        node.addEdge(up.device)
        sender.awaitEdgeReady(up.host)
        node.awaitEdgeReady(up.device)

        val (job, received) = collectPayloads(node, this)

        val big = ByteArray(30 * 1024) { (it % 251).toByte() }
        val result = sender.send(big, Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)

        awaitSize(received, 1, 60_000)
        assertArrayEquals(big, received[0].payload)

        // The simulated hop drops frames; per-hop retransmission (RETRO) is what
        // gets every envelope across. That engagement is the healing signal we
        // assert — NOT the mesh node's counter, which legitimately stays 0 when
        // the edge healed everything (mesh loss only counts what a hop lost
        // permanently). LinkMetrics counters are cumulative and never unwind.
        withTimeout(20_000) { while (up.host.linkMetrics.value.retransmissions <= 0) delay(10) }
        assertTrue("the hop must have retransmitted after 50% loss",
            up.host.linkMetrics.value.retransmissions > 0)

        // Exactly-once delivery: let any in-flight relay/retry settle, then
        // confirm the mesh dedup delivered just one copy of the message.
        delay(1_000)
        assertEquals("mesh layer delivers exactly once", 1, received.size)

        job.cancel()
        sender.disconnect(); node.disconnect()
    }
}