package com.example.itantra.transport

import com.example.itantra.codec.Language
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end integration test: Laptop client communicates with the real Android phone host.
 */
class LaptopMobileLiveTest {

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun testLaptopToMobileCommunication() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Live physical device test requires phone to be running iTantra in HOST mode on port 8888",
            isPortOpen("127.0.0.1", 8888)
        )
        println("=== STARTING LAPTOP <-> MOBILE TEST ===")
        val client = WifiTransportEngine(NetworkSimulator(), ackTimeoutMs = 3000L)

        println("Connecting laptop client to phone at 127.0.0.1:8888...")
        val connected = client.connect("127.0.0.1", 8888)
        println("Connection established: $connected")
        println("Engine status: ${client.connectionStatus.value}")
        println("Last error: ${client.getLastError()}")

        assertTrue("Laptop should connect to mobile host", connected)
        assertTrue("Client should be in CONNECTED state", client.isConnected())

        val testMessage = "Hello from Laptop to Mobile! iTantra mesh link verified."
        println("Sending message: \"$testMessage\"")
        val sendResult = client.send(
            data = testMessage.toByteArray(Charsets.UTF_8),
            language = Language.ENGLISH,
            isEmergency = false,
            priority = 2
        )

        assertNotNull("SendResult should not be null", sendResult)
        println("SendResult: failed=${sendResult!!.failed}, transmittedBytes=${sendResult.transmittedBytes}, packets=${sendResult.packetCount}, rtt=${sendResult.roundTripTimeMs}ms, detail=${sendResult.detail}")

        assertFalse("Message delivery should not fail", sendResult.failed)
        assertTrue("At least one packet should be transmitted", sendResult.packetCount > 0)

        // Hold connection for a moment so mobile finishes updating UI
        delay(1500)

        println("Laptop <-> Mobile communication test SUCCESSFUL!")
        client.disconnect()
    }
}
