package com.example.itantra.transport

import com.example.itantra.codec.Language
import com.example.itantra.protocol.CapabilityMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMessageTest {

    private fun message(): CapabilityMessage = CapabilityMessage(
        protocolVersion = 1,
        codecVersion = 2,
        deviceId = "D12AB34C",
        languages = listOf(Language.ENGLISH, Language.TAMIL, Language.HINDI)
    )

    @Test
    fun `roundtrip preserves all fields`() {
        val original = message()
        val decoded = requireNotNull(CapabilityMessage.deserialize(original.serialize()))

        assertEquals(1, decoded.protocolVersion)
        assertEquals(2, decoded.codecVersion)
        assertEquals("D12AB34C", decoded.deviceId)
        assertEquals(listOf(Language.ENGLISH, Language.TAMIL, Language.HINDI), decoded.languages)
    }

    @Test
    fun `unicode-free ascii device id roundtrips exactly`() {
        val decoded = CapabilityMessage.deserialize(
            CapabilityMessage(1, 2, "DABC1234", listOf(Language.ENGLISH)).serialize()
        )
        assertEquals("DABC1234", decoded?.deviceId)
    }

    @Test
    fun `trailing marker mismatch is rejected`() {
        val bytes = message().serialize()
        val tampered = bytes.copyOf()
        tampered[tampered.size - 1] = 0
        assertNull(CapabilityMessage.deserialize(tampered))
    }

    @Test
    fun `oversized device id is rejected safely`() {
        val bytes = message().serialize()
        // Corrupt the device-id length byte (offset 2) to an impossible value.
        val tampered = bytes.copyOf()
        tampered[2] = 0x7F
        assertNull(CapabilityMessage.deserialize(tampered))
    }

    @Test
    fun `truncated payload is rejected safely`() {
        val bytes = message().serialize()
        assertNull(CapabilityMessage.deserialize(bytes.copyOfRange(0, 3)))
        assertFalse(bytes.isEmpty())
    }

    @Test
    fun `empty languages roundtrips`() {
        val decoded = requireNotNull(
            CapabilityMessage.deserialize(CapabilityMessage(1, 2, "DX", emptyList()).serialize())
        )
        assertTrue(decoded.languages.isEmpty())
    }
}