package com.example.itantra.transport

import com.example.itantra.codec.BinaryCodec
import com.example.itantra.codec.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two real engine instances — one HOST, one DEVICE — talking over the same
 * framing/reliability stack the app uses on Wi-Fi. Handshake, capability
 * negotiation, ACK/NACK retransmission, duplicate detection and session
 * lifecycle are exercised end-to-end. Simulation configs are only enabled
 * AFTER the handshake so they cannot break it, and are explicitly labeled
 * SIMULATION in the UI.
 */
class TransportEngineIntegrationTest {

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

    /**
     * Start collecting the peer's payload stream on [scope]. Signals readiness
     * only after the subscriber is registered (inside the collector coroutine,
     * immediately before `collect` suspends), mirroring the UI lifecycle where
     * the message screen subscribes before any payload can arrive.
     */
    private suspend fun collectPayloads(
        device: TransportEngine,
        scope: CoroutineScope,
        predicate: (ReassembledPayload) -> Boolean = { true }
    ): Pair<Job, MutableList<ReassembledPayload>> {
        val received = mutableListOf<ReassembledPayload>()
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            ready.complete(Unit)
            device.incomingPayloads.collect { if (predicate(it)) received.add(it) }
        }
        ready.await()
        return job to received
    }

    // ------------------------------------------------------------------

    @Test
    fun `handshake exchanges capability and establishes session`() = runBlocking {
        val pair = enginePair()
        pair.up()

        assertEquals(ConnectionStatus.CONNECTED, pair.host.connectionStatus.value)
        assertEquals(ConnectionStatus.CONNECTED, pair.device.connectionStatus.value)
        assertTrue(pair.host.isConnected())
        assertTrue(pair.device.isConnected())

        val hSession = requireNotNull(pair.host.session.value)
        assertEquals(1, hSession.protocolVersion)
        assertEquals(BinaryCodec.VERSION, hSession.codecVersion)
        assertTrue(hSession.supportedLanguages.contains(Language.TAMIL))
        assertEquals(pair.device.getDeviceId(), hSession.remoteDeviceId)
        assertEquals(pair.host.getDeviceId(), pair.device.session.value!!.remoteDeviceId)
        assertNotEquals(hSession.sessionId, pair.device.session.value!!.sessionId)
    }

    @Test
    fun `small message is delivered packet-for-packet with exact bytes`() = runBlocking {
        val pair = enginePair()
        pair.up()

        val (job, received) = collectPayloads(pair.device, this)

        val target = "I need help near the railway station.".encodeToByteArray()
        val result = pair.host.send(target, Language.ENGLISH, isEmergency = false, priority = 2)
        assertNotNull(result)
        assertFalse(result!!.failed)

        awaitSize(received, 1)
        assertEquals(3, result.packetCount) // START + one DATA + END
        assertArrayEquals(target, received[0].payload)
        job.cancel()
    }

    @Test
    fun `large message reassembles from many packets`() = runBlocking {
        val pair = enginePair()
        pair.up()

        val (job, received) = collectPayloads(pair.device, this) { it.payload.isNotEmpty() }

        // Multi-packet payload spanning several 1024-byte data packets.
        val big = ByteArray(30 * 1024 + 7) { (it % 251).toByte() }
        val result = pair.host.send(big, Language.ENGLISH, isEmergency = false, priority = 2)
        assertNotNull(result)
        assertFalse(result!!.failed)
        assertTrue(result.packetCount > 32)

        awaitSize(received, 1)
        assertArrayEquals(big, received[0].payload)
        job.cancel()
    }

    @Test
    fun `unicode tamil and hindi messages arrive without reordering`() = runBlocking {
        val pair = enginePair()
        pair.up()

        val (job, received) = collectPayloads(pair.device, this)

        val tamil = "எனக்கு ரயில் நிலையம் அருகில் உதவி தேவை.".encodeToByteArray()
        val hindi = "मुझे रेलवे स्टेशन के पास मदद चाहिए।".encodeToByteArray()

        val r1 = pair.host.send(tamil, Language.TAMIL, isEmergency = false, priority = 2)
        val r2 = pair.host.send(hindi, Language.HINDI, isEmergency = false, priority = 2)
        assertFalse(r1!!.failed)
        assertFalse(r2!!.failed)

        awaitSize(received, 2)
        assertEquals(Language.TAMIL, received[0].language)
        assertEquals(Language.HINDI, received[1].language)
        assertArrayEquals(tamil, received[0].payload)
        assertArrayEquals(hindi, received[1].payload)
        job.cancel()
    }

    @Test
    fun `emergency priority marks the received transmission`() = runBlocking {
        val pair = enginePair()
        pair.up()

        val (job, received) = collectPayloads(pair.device, this)

        val target = "WE ARE TRAPPED SEND HELP".encodeToByteArray()
        val result = pair.host.send(target, Language.ENGLISH, isEmergency = true, priority = 3)
        assertFalse(result!!.failed)

        awaitSize(received, 1)
        assertArrayEquals(target, received[0].payload)
        assertTrue(received[0].isEmergency)
        job.cancel()
    }

    @Test
    fun `packet loss is recovered by retransmission and message still delivers`() = runBlocking {
        val pair = enginePair(ackTimeout = 60, maxRetries = 12)
        pair.up()
        pair.hostSim.config.lossRate = 0.35f // SIMULATION

        val (job, received) = collectPayloads(pair.device, this) { it.payload.isNotEmpty() }

        val big = ByteArray(30 * 1024) { (it % 251).toByte() }
        val result = pair.host.send(big, Language.ENGLISH, isEmergency = false, priority = 2)

        assertNotNull(result)
        awaitSize(received, 1, 15_000)
        assertArrayEquals(big, received[0].payload)
        assertFalse(result!!.failed)
        assertTrue("loss must force at least one retransmission", result.retransmissions > 0)
        job.cancel()
    }

    @Test
    fun `duplicate frames are re-acked but never re-processed`() = runBlocking {
        val pair = enginePair()
        pair.up()
        pair.hostSim.config.duplicationRate = 1f // SIMULATION, applied after handshake

        var count = 0
        val (job, _) = collectPayloads(pair.device, this) { count++; true }

        val result = pair.host.send("short duplicated delivery".encodeToByteArray(), Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)

        withTimeout(5_000) { while (count < 1) delay(10) }
        delay(400) // let duplicates complete their path
        assertEquals(1, count)
        assertTrue(pair.device.linkMetrics.value.duplicatePackets > 0)
        job.cancel()
    }

    @Test
    fun `corrupted frames exhaust retries and the message reports failure`() = runBlocking {
        val pair = enginePair(ackTimeout = 40)
        pair.up()
        pair.hostSim.config.corruptionRate = 1f // SIMULATION, applied after handshake

        val result = pair.host.send("corrupt me".encodeToByteArray(), Language.ENGLISH, isEmergency = false, priority = 2)
        assertNotNull(result)
        assertTrue(result!!.failed)
        assertTrue(result.retransmissions > 0)
        assertTrue(pair.device.linkMetrics.value.corruptedFrames > 0)
    }

    @Test
    fun `disconnect resets session and a fresh handshake sends again`() = runBlocking {
        val pair = enginePair()
        pair.up()
        val firstSession = pair.host.session.value!!.sessionId

        pair.host.disconnect()
        pair.device.disconnect()
        assertEquals(ConnectionStatus.DISCONNECTED, pair.host.connectionStatus.value)
        assertEquals(ConnectionStatus.DISCONNECTED, pair.device.connectionStatus.value)
        assertEquals(null, pair.host.session.value)

        val fresh = enginePair()
        fresh.up()
        assertEquals(ConnectionStatus.CONNECTED, fresh.host.connectionStatus.value)
        assertNotEquals(firstSession, fresh.host.session.value!!.sessionId)

        val (job, received) = collectPayloads(fresh.device, this)

        val target = "I need help.".encodeToByteArray()
        val result = fresh.host.send(target, Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)
        awaitSize(received, 1)
        assertArrayEquals(target, received[0].payload)
        job.cancel()
    }

    @Test
    fun `real tcp sockets carry a message end to end`() = runBlocking {
        val host = WifiTransportEngine(NetworkSimulator(), ackTimeoutMs = 500, maxRetries = 6)
        val device = WifiTransportEngine(NetworkSimulator(), ackTimeoutMs = 500, maxRetries = 6)

        val port = withContext(Dispatchers.IO) {
            java.net.ServerSocket(0).use { it.localPort }
        }

        val hostJob = async { host.startHost(port) }
        val deviceJob = async { device.connect("127.0.0.1", port) }
        assertTrue(hostJob.await())
        assertTrue(deviceJob.await())

        assertEquals(ConnectionStatus.CONNECTED, host.connectionStatus.value)
        assertEquals(ConnectionStatus.CONNECTED, device.connectionStatus.value)
        assertNotNull(host.session.value)
        assertEquals(device.getDeviceId(), host.session.value!!.remoteDeviceId)

        val (job, received) = collectPayloads(device, this)

        val target = "wifi socket roundtrip works".encodeToByteArray()
        val result = host.send(target, Language.ENGLISH, isEmergency = false, priority = 2)
        assertFalse(result!!.failed)
        awaitSize(received, 1)
        assertArrayEquals(target, received[0].payload)

        job.cancel()
        host.disconnect()
        device.disconnect()
    }
}