package com.example.itantra.mesh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MultiHopRelayEngineTest {

    @Test
    fun testMultiHopEndToEndDelivery() = runBlocking {
        val engine = MultiHopRelayEngine()
        val originalMessage = "Emergency alert: Team needed at Gate 3"
        val success = engine.runMultiHopDemonstration(
            messageText = originalMessage,
            language = "ta-IN",
            stepDelayMs = 10L // fast for unit tests
        )

        assertTrue("Multi-hop transmission should succeed end-to-end", success)
        val state = engine.relayState.value
        assertEquals("DELIVERED ✓", state.nodeCStatus)
        assertEquals(originalMessage, state.deliveredMessage)
        assertEquals(1.0f, state.packetPosition, 0.01f)
    }

    @Test
    fun testMultiHopDuplicatePacketSuppression() = runBlocking {
        val engine = MultiHopRelayEngine()
        val packet = com.example.itantra.protocol.SetuPacket.createEncrypted(
            plaintext = "Duplicate check".toByteArray(),
            senderId = "PHONE_A",
            receiverId = "PHONE_C"
        )

        val duplicateDetected = engine.testDuplicatePacketSuppression(packet)
        assertTrue("Duplicate packet must be detected and suppressed", duplicateDetected)
    }
}
