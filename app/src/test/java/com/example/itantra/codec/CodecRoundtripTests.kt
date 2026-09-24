package com.example.itantra.codec

import com.example.itantra.protocol.Packet
import com.example.itantra.protocol.Packetizer
import com.example.itantra.protocol.Packetizer.DEFAULT_MAX_PAYLOAD
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * End-to-end lossless round-trip tests for the RETRO codec.
 *
 * Every valid message must decode back to its exact normalized text. Any
 * failure here means the binary representation corrupted the message.
 */
class CodecRoundtripTests {

    private lateinit var codec: RetroSpeechCodec

    @Before
    fun setup() {
        codec = RetroSpeechCodec()
    }

    private fun assertRoundtrip(text: String, language: Language): CodecLabResult {
        val result = codec.performLab(text, language)
        assertTrue(
            "Round trip failed for [$text]: decoded=[${result.decodedText}] (${result.integrityLabel}, enc=${result.encodedBytes}B utf8=${result.originalUtf8Bytes}B)",
            result.exactMatch
        )
        assertEquals(result.normalizedText, result.decodedText)
        return result
    }

    // 1. English roundtrip
    @Test
    fun `English sentence roundtrips exactly`() {
        val r = assertRoundtrip("I need help.", Language.ENGLISH)
        assertTrue(r.encodedBytes > 0)
        assertEquals("I need help.", r.decodedText)
    }

    // 2. Tamil roundtrip
    @Test
    fun `Tamil sentence roundtrips exactly`() {
        val r = assertRoundtrip("எனக்கு உதவி தேவை.", Language.TAMIL)
        assertEquals("எனக்கு உதவி தேவை.", r.decodedText)
    }

    // 3. Hindi roundtrip
    @Test
    fun `Hindi sentence roundtrips exactly`() {
        val r = assertRoundtrip("मुझे मदद चाहिए।", Language.HINDI)
        assertEquals("मुझे मदद चाहिए।", r.decodedText)
    }

    // 4. Unknown / out-of-vocabulary words are escaped with exact UTF-8
    @Test
    fun `unknown words roundtrip exactly via escape`() {
        val r = assertRoundtrip("Zorkfblañ kaleidoscope help", Language.ENGLISH)
        assertTrue(r.escapedTokens > 0)
        assertTrue(r.decodedText.contains("Zorkfblañ"))
    }

    // 5. Empty input still produces a valid (header-only) frame
    @Test
    fun `empty input roundtrips`() {
        val r = codec.performLab("", Language.ENGLISH)
        assertEquals("", r.decodedText)
        assertTrue(r.exactMatch)
        assertTrue(r.encodedBytes > 0)
    }

    // 6. Punctuation-only message
    @Test
    fun `punctuation roundtrips exactly`() {
        val r = assertRoundtrip("Stop! Wait... Really?", Language.ENGLISH)
        assertTrue(r.punctTokens > 0)
        assertEquals("Stop! Wait... Really?", r.decodedText)
    }

    // 7. Unicode escapes (including astral-plane emoji)
    @Test
    fun `unicode and emoji roundtrip exactly`() {
        val r = assertRoundtrip("नमस्ते! I am ok 🙂", Language.HINDI)
        assertTrue(r.decodedText.contains("🙂"))
    }

    // 8. Corrupted payload is rejected by CRC, never decoded as garbage
    @Test
    fun `corrupted frame is rejected on CRC mismatch`() {
        val payload = codec.getEncoder().encode("I need help", Language.ENGLISH)
        val corrupted = java.util.Arrays.copyOf(payload.data, payload.data.size)
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2] + 1).toByte()

        val result = codec.decode(corrupted)
        assertTrue(result is DecodeResult.Error)
        assertTrue((result as DecodeResult.Error).message.contains("CRC"))
    }

    // 9. Invalid magic is rejected
    @Test
    fun `invalid magic is rejected`() {
        val payload = codec.getEncoder().encode("help", Language.ENGLISH)
        val bad = java.util.Arrays.copyOf(payload.data, payload.data.size)
        bad[0] = 'X'.code.toByte()

        val result = codec.decode(bad)
        assertTrue(result is DecodeResult.Error)
    }

    // 10. Truncated frame is rejected
    @Test
    fun `truncated frame is rejected`() {
        val payload = codec.getEncoder().encode("I need help right now", Language.ENGLISH)
        val truncated = java.util.Arrays.copyOf(payload.data, payload.data.size - 3)

        val result = codec.decode(truncated)
        assertTrue(result is DecodeResult.Error)
    }

    // 11. Prediction kicks in and still decodes exactly
    @Test
    fun `prediction succeeds without corrupting text`() {
        val r = assertRoundtrip("HELP FIRE HELP FIRE HELP", Language.ENGLISH)
        assertTrue("expected at least one predicted token", r.predictedTokens > 0)
    }

    // 12. Emergency message carries CRITICAL importance + emergency flag
    @Test
    fun `emergency message is marked critical`() {
        val payload = codec.getEncoder().encode("FIRE HELP DANGER", Language.ENGLISH)
        assertEquals(Importance.CRITICAL, payload.importance)
        assertTrue(payload.compressionPercentage <= 100.0)
    }

    // 13. Multi-packet payload reassembles and decodes exactly
    @Test
    fun `multi-packet payload reassembles and decodes exactly`() {
        // Grow the message until the frame actually spans multiple packets.
        val sentence = "help fire danger station road main railway shelter hospital need water food urgent"
        var report = codec.getEncoder().encodeExpanded(sentence, Language.ENGLISH)
        var repetitions = 1
        while (report.payload.finalEncodedSize <= DEFAULT_MAX_PAYLOAD) {
            repetitions *= 2
            report = codec.getEncoder().encodeExpanded(
                List(repetitions) { sentence }.joinToString(" "), Language.ENGLISH
            )
        }
        assertTrue(report.payload.finalEncodedSize > DEFAULT_MAX_PAYLOAD)

        val packets = Packetizer.buildPackets(
            payload = report.payload.data,
            language = Language.ENGLISH,
            messageId = report.payload.messageId,
            priority = report.payload.importance.level,
            isEmergency = report.payload.importance == Importance.CRITICAL
        )
        val dataPackets = packets.filter { it.sequenceId > 0 }
        assertTrue("expected multiple DATA packets", dataPackets.size > 1)

        val parts = dataPackets.associate { it.sequenceId to it.payload }
        val assembled = Packetizer.assemble(parts)
        assertArrayEquals(report.payload.data, assembled)

        val decoded = codec.decode(assembled)
        assertTrue(decoded is DecodeResult.Success)
        assertEquals(report.normalizedText, (decoded as DecodeResult.Success).reconstructedText)
    }

    // 14. Out-of-order DATA packets still reassemble by sequence id
    @Test
    fun `out-of-order packets reassemble by sequence id`() {
        val text = "we need supplies water food medical help at village shelter now"
        val report = codec.getEncoder().encodeExpanded(text, Language.ENGLISH)
        val packets = Packetizer.buildPackets(
            payload = report.payload.data,
            language = Language.ENGLISH,
            messageId = report.payload.messageId,
            priority = 1,
            isEmergency = false
        )
        val dataPackets = packets.filter { it.sequenceId > 0 }
        val shuffled = dataPackets.sortedByDescending { it.sequenceId }

        val parts = shuffled.associate { it.sequenceId to it.payload }
        val assembled = Packetizer.assemble(parts)
        assertArrayEquals(report.payload.data, assembled)

        val decoded = codec.decode(assembled)
        assertEquals(report.normalizedText, (decoded as DecodeResult.Success).reconstructedText)
    }

    // Honest compression: a long dictionary-heavy message actually reduces
    // payload vs UTF-8; nobody fakes numbers.
    @Test
    fun `long dictionary heavy message reduces bytes versus utf8`() {
        val longText = arrayOf(
            "HELP","FIRE","DANGER","HELPLESS","RAIN","ROAD","MAIN","STATION",
            "RAILWAY","NEAR","VILLAGE","SHELTER","HOSPITAL","HOUSE","AREA"
        ).distinct().joinToString(" ")
        val r = codec.performLab(longText, Language.ENGLISH)
        assertTrue("expected reduction, got ${r.modeLabel} (${r.encodedBytes}B vs ${r.originalUtf8Bytes}B)", r.compressionPercent > 0)
        assertTrue(r.exactMatch)
    }
}