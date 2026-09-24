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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay panel counters (Phase 5 #19): PACKETS RELAYED, DUPLICATES DROPPED,
 * TTL EXPIRED and LAST HOP must reflect real mesh traffic. Exercises the
 * same [MeshTransportEngine.relayStats] the Hardware Test screen renders.
 * maxTtl = 2 so TTL exhaustion is observable inside a 3-node chain.
 */
class MeshRelayStatsTest {

    private class SimPair(val host: SimulatedTransport, val device: SimulatedTransport)

    private fun simPair(): SimPair {
        val host = SimulatedTransport(NetworkSimulator(), ackTimeoutMs = 150, maxRetries = 6)
        val device = SimulatedTransport(NetworkSimulator(), ackTimeoutMs = 150, maxRetries = 6)
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

    private suspend fun collect(scope: CoroutineScope, engine: TransportEngine): Pair<Job, MutableList<ReassembledPayload>> {
        val list = mutableListOf<ReassembledPayload>()
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            ready.complete(Unit)
            engine.incomingPayloads.collect { list.add(it) }
        }
        ready.await()
        return job to list
    }

    @Test
    fun `relay counts forwarded packets, last hop, and ttl exhaustion`() = runBlocking {
        val ab = simPair(); ab.up()
        val bc = simPair(); bc.up()

        val a = MeshTransportEngine(maxTtl = 2)
        val b = MeshTransportEngine(maxTtl = 2)
        val c = MeshTransportEngine(maxTtl = 2)

        a.addEdge(ab.host)
        b.addEdge(ab.device)
        b.addEdge(bc.host)
        c.addEdge(bc.device)

        a.awaitEdgeReady(ab.host)
        b.awaitEdgeReady(ab.device)
        b.awaitEdgeReady(bc.host)
        c.awaitEdgeReady(bc.device)

        val (bJob, _) = collect(this, b)
        val (cJob, cRecv) = collect(this, c)

        val msg = "HELP RELAY 1"
        val sent = a.send(msg.toByteArray(), Language.ENGLISH, isEmergency = false, priority = 1)
        assertTrue("source sends", sent?.failed == false)

        withTimeout(10_000) { while (cRecv.isEmpty()) delay(10) }
        withTimeout(10_000) { while (b.relayStats.value.packetsRelayed < 1) delay(10) }
        withTimeout(10_000) { while (c.relayStats.value.ttlExpired < 1) delay(10) }

        // B must have relayed the envelope onward (A -> B -> C).
        assertTrue("B relays traffic", b.relayStats.value.packetsRelayed >= 1)
        assertTrue("B records a last hop", b.relayStats.value.lastHop.isNotBlank())

        // Origin TTL was 2; C is the last receiver, so its budget is exhausted.
        assertTrue("C sees ttl exhausted", c.relayStats.value.ttlExpired >= 1)

        assertEquals("uncorrupted over two hops", msg.toByteArray().toList(), cRecv[0].payload.toList())

        bJob.cancel()
        cJob.cancel()
        a.disconnect(); b.disconnect(); c.disconnect()
        ab.host.disconnect(); ab.device.disconnect()
        bc.host.disconnect(); bc.device.disconnect()
    }

    @Test
    fun `fan-in duplicates are relayed once and counted as dropped`() = runBlocking {
        val up1 = simPair(); up1.up()
        val up2 = simPair(); up2.up()

        val upstream = MeshTransportEngine(maxTtl = 3)
        val node = MeshTransportEngine(maxTtl = 3)

        upstream.addEdge(up1.host)
        upstream.addEdge(up2.host)
        node.addEdge(up1.device)
        node.addEdge(up2.device)

        upstream.awaitEdgeReady(up1.host)
        upstream.awaitEdgeReady(up2.host)
        node.awaitEdgeReady(up1.device)
        node.awaitEdgeReady(up2.device)

        val (job, received) = collect(this, node)

        val result = upstream.send("FAN IN".toByteArray(), Language.ENGLISH, isEmergency = false, priority = 1)
        assertNotNull(result)
        assertFalse(result!!.failed)
        withTimeout(5_000) { while (received.isEmpty()) delay(10) }
        withTimeout(5_000) { while (node.relayStats.value.duplicatesDropped < 1) delay(10) }

        assertEquals("exactly one application delivery", 1, received.size)
        assertTrue("node dropped the copied frame", node.relayStats.value.duplicatesDropped >= 1)

        job.cancel()
        upstream.disconnect(); node.disconnect()
        up1.host.disconnect(); up1.device.disconnect()
        up2.host.disconnect(); up2.device.disconnect()
    }
}