package com.example.itantra.transport

import com.example.itantra.codec.BinaryCodec
import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType
import com.example.itantra.protocol.BleChunkReader
import com.example.itantra.protocol.BleChunkResult
import com.example.itantra.protocol.BleChunkWriter
import com.example.itantra.protocol.BleLinkCodec
import com.example.itantra.protocol.CapabilityMessage
import com.example.itantra.protocol.Packet
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

/**
 * Regression and lifecycle tests verifying Wi-Fi and BLE connections,
 * handshake negotiation, disconnect cleanup, duplicate prevention, and
 * bidirectional packet exchange.
 */
class ConnectionRegressionTest {

    private fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    private suspend fun collectPayloads(
        engine: TransportEngine,
        scope: CoroutineScope
    ): Pair<Job, MutableList<ReassembledPayload>> {
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
    fun `wifi host and client connect, exchange packets bidirectionally, and disconnect`() = runBlocking {
        val port = findFreePort()
        val hostSim = NetworkSimulator()
        val clientSim = NetworkSimulator()
        val hostEngine = WifiTransportEngine(hostSim, ackTimeoutMs = 300, maxRetries = 3)
        val clientEngine = WifiTransportEngine(clientSim, ackTimeoutMs = 300, maxRetries = 3)

        try {
            // Launch host and client concurrently
            val (hostOk, clientOk) = coroutineScope {
                val h = async { hostEngine.startHost(port) }
                // Small delay to allow host server socket to bind
                delay(50)
                val c = async { clientEngine.connect("127.0.0.1", port) }
                h.await() to c.await()
            }

            assertTrue("Host should connect", hostOk)
            assertTrue("Client should connect", clientOk)
            assertTrue(hostEngine.isConnected())
            assertTrue(clientEngine.isConnected())
            assertEquals(ConnectionStatus.CONNECTED, hostEngine.connectionStatus.value)
            assertEquals(ConnectionStatus.CONNECTED, clientEngine.connectionStatus.value)

            // Setup collectors for incoming payloads
            val (hJob, hostRx) = collectPayloads(hostEngine, this)
            val (cJob, clientRx) = collectPayloads(clientEngine, this)

            // 1. Host sends "HELLO" -> Client receives "HELLO"
            val helloBytes = "HELLO".toByteArray(Charsets.UTF_8)
            val res1 = hostEngine.send(helloBytes, Language.ENGLISH)
            assertNotNull(res1)
            assertFalse(res1?.failed ?: true)

            withTimeout(3000) {
                while (clientRx.isEmpty()) delay(10)
            }
            assertArrayEquals(helloBytes, clientRx.first().payload)

            // 2. Client sends "REPLY" -> Host receives "REPLY"
            val replyBytes = "REPLY".toByteArray(Charsets.UTF_8)
            val res2 = clientEngine.send(replyBytes, Language.ENGLISH)
            assertNotNull(res2)
            assertFalse(res2?.failed ?: true)

            withTimeout(3000) {
                while (hostRx.isEmpty()) delay(10)
            }
            assertArrayEquals(replyBytes, hostRx.first().payload)

            // Verify protocol versions and CRC
            assertEquals(1, hostEngine.session.value?.protocolVersion)
            assertEquals(1, clientEngine.session.value?.protocolVersion)
            assertEquals(true, hostEngine.getLastCrcStatus())
            assertEquals(true, clientEngine.getLastCrcStatus())

            hJob.cancel()
            cJob.cancel()
        } finally {
            hostEngine.disconnect()
            clientEngine.disconnect()
            delay(50)
            assertFalse(hostEngine.isConnected())
            assertFalse(clientEngine.isConnected())
            assertEquals(ConnectionStatus.DISCONNECTED, hostEngine.connectionStatus.value)
            assertEquals(ConnectionStatus.DISCONNECTED, clientEngine.connectionStatus.value)
        }
    }

    @Test
    fun `ble chunk size never exceeds att mtu and reassembles correctly`() {
        // Test default MTU 23: ATT header = 3, BleLinkCodec header = 4 -> payload max = 16 bytes
        val mtu = 23
        val maxChunkPayload = maxOf(1, mtu - 3 - BleLinkCodec.HEADER_SIZE)
        assertEquals(16, maxChunkPayload)

        val writer = BleChunkWriter(maxChunkPayload)
        val sampleData = ByteArray(100) { (it and 0xFF).toByte() }
        val chunks = writer.split(sampleData)

        // Every serialized chunk must be <= 20 bytes (mtu - 3)
        for (chunk in chunks) {
            assertTrue("Chunk size ${chunk.size} must be <= ${mtu - 3}", chunk.size <= mtu - 3)
        }

        // Reader must reassemble back into the exact original 100 bytes
        val reader = BleChunkReader()
        val out = ByteArrayOutputStream()
        for (chunk in chunks) {
            when (val r = reader.feed(chunk)) {
                is BleChunkResult.Stream -> out.write(r.bytes)
                is BleChunkResult.Gap -> throw IllegalStateException("Unexpected missing chunk")
            }
        }
        val assembled = out.toByteArray()
        assertNotNull("Reassembled data should not be null", assembled)
        assertArrayEquals(sampleData, assembled)

        // Test high MTU 512: max chunk payload = 512 - 3 - 4 = 505 bytes
        val mtu512 = 512
        val maxPayload512 = maxOf(1, mtu512 - 3 - BleLinkCodec.HEADER_SIZE)
        assertEquals(505, maxPayload512)
        val writer512 = BleChunkWriter(maxPayload512)
        val largeData = ByteArray(2048) { (it % 250).toByte() }
        val chunks512 = writer512.split(largeData)
        for (chunk in chunks512) {
            assertTrue("Chunk size ${chunk.size} must be <= ${mtu512 - 3}", chunk.size <= mtu512 - 3)
        }
        val reader512 = BleChunkReader()
        val out512 = ByteArrayOutputStream()
        for (chunk in chunks512) {
            when (val r = reader512.feed(chunk)) {
                is BleChunkResult.Stream -> out512.write(r.bytes)
                is BleChunkResult.Gap -> throw IllegalStateException("Unexpected gap")
            }
        }
        assertArrayEquals(largeData, out512.toByteArray())
    }

    @Test
    fun `duplicate connection attempts are rejected when already connected`() = runBlocking {
        val simHost = SimulatedTransport(NetworkSimulator())
        val simClient = SimulatedTransport(NetworkSimulator())
        simHost.bindPeer(simClient)

        coroutineScope {
            val h = async { simHost.startHost(0) }
            val c = async { simClient.connect("peer", 0) }
            assertTrue(h.await())
            assertTrue(c.await())
        }

        assertTrue(simHost.isConnected())
        // Attempting duplicate connect or startHost should return false immediately
        val duplicateConnect = simHost.connect("another", 0)
        assertFalse("Duplicate connect should be rejected", duplicateConnect)

        val duplicateHost = simHost.startHost(0)
        assertFalse("Duplicate startHost should be rejected", duplicateHost)

        simHost.disconnect()
        simClient.disconnect()
    }

    @Test
    fun `wifi client fails with error on blank host address`() = runBlocking {
        val client = WifiTransportEngine(NetworkSimulator())
        val ok = client.connect("   ", 8888)
        assertFalse(ok)
        assertNotNull(client.getLastError())
        assertTrue(client.getLastError()!!.contains("Host IP address is required"))
    }

    @Test
    fun `wifi client times out and sets error on unreachable host`() = runBlocking {
        val client = WifiTransportEngine(NetworkSimulator())
        val unreachablePort = findFreePort()
        val ok = client.connect("127.0.0.1", unreachablePort)
        assertFalse("Connection to unused port should fail", ok)
        assertEquals(ConnectionStatus.ERROR, client.connectionStatus.value)
        assertNotNull(client.getLastError())
        assertTrue(client.getLastError()!!.contains("failed"))
    }

    @Test
    fun `reconnection generates fresh session epoch and clears old session`() = runBlocking {
        val simHost = SimulatedTransport(NetworkSimulator())
        val simClient = SimulatedTransport(NetworkSimulator())
        simHost.bindPeer(simClient)

        coroutineScope {
            val h = async { simHost.startHost(0) }
            val c = async { simClient.connect("peer", 0) }
            assertTrue(h.await())
            assertTrue(c.await())
        }

        val firstEpoch = simHost.session.value?.epoch
        val firstSessionId = simHost.session.value?.sessionId
        assertNotNull(firstEpoch)
        assertNotNull(firstSessionId)

        // Disconnect
        simHost.disconnect()
        simClient.disconnect()
        delay(20)
        assertEquals(ConnectionStatus.DISCONNECTED, simHost.connectionStatus.value)
        assertNull(simHost.session.value)

        // Reconnect
        coroutineScope {
            val h = async { simHost.startHost(0) }
            val c = async { simClient.connect("peer", 0) }
            assertTrue(h.await())
            assertTrue(c.await())
        }

        val secondEpoch = simHost.session.value?.epoch
        val secondSessionId = simHost.session.value?.sessionId
        assertNotNull(secondEpoch)
        assertNotNull(secondSessionId)

        assertNotEquals("Session ID must change on reconnect", firstSessionId, secondSessionId)
        assertNotEquals("Epoch must change on reconnect", firstEpoch, secondEpoch)

        simHost.disconnect()
        simClient.disconnect()
    }

    @Test
    fun `handshake timeout failure transitions to error`() = runBlocking {
        // Create an engine where peer does not answer handshake
        val host = SimulatedTransport(NetworkSimulator(), ackTimeoutMs = 100)
        val peer = SimulatedTransport(NetworkSimulator(), ackTimeoutMs = 100)
        host.bindPeer(peer)
        // Only start host, don't start client: host announces capability, but nobody replies
        val hostOk = withTimeout(10_000) {
            host.startHost(0)
        }
        assertFalse("Host handshake should fail without peer reply", hostOk)
        assertEquals(ConnectionStatus.ERROR, host.connectionStatus.value)
        assertNotNull(host.getLastError())
    }
}
