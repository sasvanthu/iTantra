package com.example.itantra.codec

import com.example.itantra.protocol.CapabilityMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * P21 wire-compatibility contract: the pre-expansion bytes are frozen
 * (EN=1, HI=2, TA=3, UNKNOWN=0) and the appended P21 languages occupy
 * 0x04..0x0A. Everything the wire touches — Packet, CapabilityMessage —
 * must round-trip every ten-language build and treat unknown bytes as UNKNOWN.
 */
class LanguageExpansionTest {

    @Test
    fun `legacy wire bytes are frozen`() {
        assertEquals(0x01, Language.toByte(Language.ENGLISH).toInt())
        assertEquals(0x02, Language.toByte(Language.HINDI).toInt())
        assertEquals(0x03, Language.toByte(Language.TAMIL).toInt())
        assertEquals(0x00, Language.toByte(Language.UNKNOWN).toInt())
    }

    @Test
    fun `p21 languages append unique bytes without colliding`() {
        val p21 = listOf(
            Language.BENGALI to 0x04,
            Language.TELUGU to 0x05,
            Language.MARATHI to 0x06,
            Language.GUJARATI to 0x07,
            Language.KANNADA to 0x08,
            Language.MALAYALAM to 0x09,
            Language.ODIA to 0x0A
        )
        for ((lang, byte) in p21) {
            assertEquals(byte, Language.toByte(lang).toInt())
            assertEquals(lang, Language.fromByte(byte.toByte()))
        }
        // All ten wire bytes are distinct.
        val codes = Language.entries.map { Language.toByte(it) }.toSet()
        assertEquals(Language.entries.size, codes.size)
    }

    @Test
    fun `every language roundtrips from byte to enum`() {
        for (lang in Language.entries) {
            assertEquals(lang, Language.fromByte(Language.toByte(lang)))
        }
    }

    @Test
    fun `unknown wire bytes decode to UNKNOWN`() {
        assertEquals(Language.UNKNOWN, Language.fromByte(0x0B))
        assertEquals(Language.UNKNOWN, Language.fromByte(0x7F.toByte()))
        assertEquals(Language.UNKNOWN, Language.fromByte(0xFF.toByte()))
    }

    @Test
    fun `iso codes resolve for the expanded set`() {
        assertEquals(Language.BENGALI, Language.fromCode("bn"))
        assertEquals(Language.TELUGU, Language.fromCode("te"))
        assertEquals(Language.MARATHI, Language.fromCode("mr"))
        assertEquals(Language.GUJARATI, Language.fromCode("gu"))
        assertEquals(Language.KANNADA, Language.fromCode("kn"))
        assertEquals(Language.MALAYALAM, Language.fromCode("ml"))
        assertEquals(Language.ODIA, Language.fromCode("or"))
        assertEquals(Language.UNKNOWN, Language.fromCode("zz"))
    }

    @Test
    fun `capability negotiation carries the expanded language set`() {
        val allLanguages = Language.entries.filter { it != Language.UNKNOWN }
        val message = CapabilityMessage(
            protocolVersion = 1,
            codecVersion = 2,
            deviceId = "LNGG10",
            languages = allLanguages
        )
        val decoded = requireNotNull(CapabilityMessage.deserialize(message.serialize()))
        assertEquals(allLanguages, decoded.languages)
        assertEquals(10, decoded.languages.size)
    }

    @Test
    fun `packet carries any p21 language over the wire`() {
        for (lang in Language.entries.filter { it != Language.UNKNOWN }) {
            val packet = com.example.itantra.protocol.Packet.createTextPacket(
                messageId = 1L, sequenceId = 1, language = lang,
                payload = "labelled".encodeToByteArray()
            )
            val back = com.example.itantra.protocol.Packet.deserialize(packet.serialize())
            assertNotNull(back)
            assertEquals(lang, back!!.language)
        }
    }
}