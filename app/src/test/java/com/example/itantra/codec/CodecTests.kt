package com.example.itantra.codec

import com.example.itantra.protocol.Packet
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TokenDictionaryTest {

    private lateinit var dictionary: TokenDictionary

    @Before
    fun setup() {
        dictionary = TokenDictionary()
    }

    @Test
    fun `encode returns valid ID for known token`() {
        val id = dictionary.encode("HELP")
        assertTrue(id >= 0)
    }

    @Test
    fun `encode returns -1 for unknown token`() {
        val id = dictionary.encode("XYZUNKNOWN")
        assertEquals(-1, id)
    }

    @Test
    fun `decode returns correct token for known ID`() {
        val id = dictionary.encode("HELP")
        val token = dictionary.decode(id)
        assertEquals("HELP", token)
    }

    @Test
    fun `decode returns null for invalid ID`() {
        val token = dictionary.decode(99999)
        assertNull(token)
    }

    @Test
    fun `getImportance returns CRITICAL for emergency words`() {
        val importance = dictionary.getImportance("HELP")
        assertEquals(Importance.CRITICAL, importance)
    }

    @Test
    fun `getImportance returns NORMAL for regular words`() {
        val importance = dictionary.getImportance("CAT")
        assertEquals(Importance.NORMAL, importance)
    }

    @Test
    fun `getTokenCount returns positive number`() {
        assertTrue(dictionary.getTokenCount() > 0)
    }

    @Test
    fun `serialize and deserialize maintains integrity`() {
        val data = dictionary.serialize()
        val newDict = TokenDictionary()
        newDict.deserialize(data)

        assertEquals(dictionary.getTokenCount(), newDict.getTokenCount())
        assertEquals(dictionary.encode("HELP"), newDict.encode("HELP"))
    }
}

class PhonemeEncoderTest {

    private lateinit var encoder: PhonemeEncoder

    @Before
    fun setup() {
        encoder = PhonemeEncoder()
    }

    @Test
    fun `encode produces non-empty list for English text`() {
        val phonemes = encoder.encode("HELP")
        assertTrue(phonemes.isNotEmpty())
    }

    @Test
    fun `encode handles empty string`() {
        val phonemes = encoder.encode("")
        assertTrue(phonemes.isEmpty())
    }

    @Test
    fun `encode produces different IDs for different characters`() {
        val phonemesA = encoder.encode("A")
        val phonemesB = encoder.encode("B")
        assertNotEquals(phonemesA, phonemesB)
    }

    @Test
    fun `estimateSize returns positive value`() {
        val phonemes = encoder.encode("HELP")
        assertTrue(encoder.estimateSize(phonemes) > 0)
    }
}

class ImportanceScorerTest {

    private lateinit var scorer: ImportanceScorer

    @Before
    fun setup() {
        scorer = ImportanceScorer()
    }

    @Test
    fun `score returns CRITICAL for HELP`() {
        assertEquals(Importance.CRITICAL, scorer.score("HELP"))
    }

    @Test
    fun `score returns CRITICAL for FIRE`() {
        assertEquals(Importance.CRITICAL, scorer.score("FIRE"))
    }

    @Test
    fun `score returns HIGH for NEED`() {
        assertEquals(Importance.HIGH, scorer.score("NEED"))
    }

    @Test
    fun `score returns LOW for THE`() {
        assertEquals(Importance.LOW, scorer.score("THE"))
    }

    @Test
    fun `score returns NORMAL for unknown token`() {
        assertEquals(Importance.NORMAL, scorer.score("XYZ"))
    }

    @Test
    fun `scoreTokens returns list of same size`() {
        val tokens = listOf("HELP", "FIRE", "THE", "CAT")
        val scores = scorer.scoreTokens(tokens)
        assertEquals(tokens.size, scores.size)
    }

    @Test
    fun `filterByImportance filters correctly`() {
        val tokens = listOf(
            Token("HELP", 1, Importance.CRITICAL),
            Token("THE", 2, Importance.LOW),
            Token("FIRE", 3, Importance.CRITICAL)
        )
        val filtered = scorer.filterByImportance(tokens, Importance.HIGH)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.importance.level >= Importance.HIGH.level })
    }
}

class RetroSpeechCodecTest {

    private lateinit var codec: RetroSpeechCodec

    @Before
    fun setup() {
        codec = RetroSpeechCodec()
    }

    @Test
    fun `encode produces non-empty payload`() {
        val payload = codec.encode("HELP FIRE", Language.ENGLISH)
        assertTrue(payload.data.isNotEmpty())
    }

    @Test
    fun `encode records original UTF8 size`() {
        val payload = codec.encode("HELP", Language.ENGLISH)
        assertTrue(payload.originalUtf8Size > 0)
    }

    @Test
    fun `encode records final encoded size`() {
        val payload = codec.encode("HELP", Language.ENGLISH)
        assertTrue(payload.finalEncodedSize > 0)
    }

    @Test
    fun `encodeWithComparison provides comparison data`() {
        val comparison = codec.encodeWithComparison("I need help", Language.ENGLISH)
        assertTrue(comparison.originalUtf8Bytes > 0)
        assertTrue(comparison.retroEncodedBytes > 0)
        assertTrue(comparison.packetCount > 0)
    }

    @Test
    fun `encode handles Hindi text`() {
        val payload = codec.encode("मदद चाहिए", Language.HINDI)
        assertTrue(payload.data.isNotEmpty())
    }

    @Test
    fun `encode handles Tamil text`() {
        val payload = codec.encode("உதவி தேவை", Language.TAMIL)
        assertTrue(payload.data.isNotEmpty())
    }

    @Test
    fun `compression percentage is calculated`() {
        val comparison = codec.encodeWithComparison("HELP FIRE DANGER", Language.ENGLISH)
        // Compression may be positive or negative depending on text length vs overhead
        assertTrue(comparison.compressionPercentage > -500.0)
        assertTrue(comparison.compressionPercentage <= 100.0)
    }
}

class BaselineCodecTest {

    private lateinit var codec: BaselineCodec

    @Before
    fun setup() {
        codec = BaselineCodec()
    }

    @Test
    fun `encode produces UTF-8 bytes`() {
        val payload = codec.encode("HELP", Language.ENGLISH)
        assertEquals("HELP".toByteArray(Charsets.UTF_8).size, payload.finalEncodedSize)
    }

    @Test
    fun `decode restores original text`() {
        val payload = codec.encode("HELP FIRE", Language.ENGLISH)
        val result = codec.decode(payload.data)
        assertTrue(result is DecodeResult.Success)
        assertEquals("HELP FIRE", (result as DecodeResult.Success).reconstructedText)
    }

    @Test
    fun `baseline has zero compression`() {
        val comparison = codec.encodeWithComparison("HELP", Language.ENGLISH)
        assertEquals(0.0, comparison.compressionPercentage, 0.001)
    }
}

class ContextPredictorTest {

    private lateinit var predictor: ContextPredictor

    @Before
    fun setup() {
        predictor = ContextPredictor()
    }

    @Test
    fun `nextPrediction returns OOV when no context`() {
        assertEquals(ContextPredictor.OOV_ID, predictor.nextPrediction())
    }

    @Test
    fun `note adds tokens to context window`() {
        predictor.note(1)
        predictor.note(2)
        assertEquals(listOf(1, 2), predictor.getContextWindow())
    }

    @Test
    fun `context window respects max size`() {
        repeat(20) { predictor.note(it) }
        assertTrue(predictor.getContextWindow().size <= 8)
    }

    @Test
    fun `beginMessage resets everything`() {
        predictor.note(1)
        predictor.note(2)
        predictor.beginMessage()
        assertTrue(predictor.getContextWindow().isEmpty())
        assertEquals(ContextPredictor.OOV_ID, predictor.nextPrediction())
    }

    @Test
    fun `predicts a repeated token`() {
        predictor.note(7)
        predictor.note(7)
        assertEquals(7, predictor.nextPrediction())
    }

    @Test
    fun `wouldPredict is true for the next token of a repeated sequence`() {
        predictor.note(3)
        predictor.note(3)
        assertTrue(predictor.wouldPredict(3))
    }

    @Test
    fun `clear resets context`() {
        predictor.note(1)
        predictor.clear()
        assertTrue(predictor.getContextWindow().isEmpty())
    }
}

class PacketTest {

    @Test
    fun `packet serialization and deserialization roundtrip`() {
        val original = Packet.createTextPacket(
            messageId = 12345L,
            sequenceId = 1,
            language = Language.ENGLISH,
            payload = "TEST".toByteArray(),
            priority = 2
        )
        val serialized = original.serialize()
        val deserialized = Packet.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(original.messageId, deserialized!!.messageId)
        assertEquals(original.sequenceId, deserialized.sequenceId)
        assertEquals(original.language, deserialized.language)
        assertEquals(original.packetType, deserialized.packetType)
    }

    @Test
    fun `CRC verification works`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val crc = Packet.computeCRC(data)
        assertTrue(crc != 0)
    }

    @Test
    fun `ACK packet has correct type`() {
        val ack = Packet.createAckPacket(1L, 1)
        assertEquals(PacketType.ACK, ack.packetType)
    }

    @Test
    fun `NACK packet has correct type`() {
        val nack = Packet.createNackPacket(1L, 1)
        assertEquals(PacketType.NACK, nack.packetType)
    }
}
