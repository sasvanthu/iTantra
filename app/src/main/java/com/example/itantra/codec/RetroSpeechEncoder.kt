package com.example.itantra.codec

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class RetroSpeechEncoder {

    private val dictionary = TokenDictionary()
    private val phonemeEncoder = PhonemeEncoder()
    private val importanceScorer = ImportanceScorer()
    private val predictor = Predictor()

    private var messageIdCounter = 0L

    fun encode(text: String, language: Language): EncodedPayload {
        val startTime = System.currentTimeMillis()
        val originalUtf8Size = text.toByteArray(Charsets.UTF_8).size

        // Step 1: Normalize text
        val normalized = normalizeText(text, language)

        // Step 2: Tokenize
        val rawTokens = tokenize(normalized, language)

        // Step 3: Score importance
        val tokens = rawTokens.map { rawToken ->
            val dictId = dictionary.encode(rawToken)
            val importance = importanceScorer.score(rawToken)
            val phonemes = phonemeEncoder.encode(rawToken)
            val predicted = predictor.isPredictable(dictId)
            val confidence = predictor.getPredictionConfidence(dictId)

            Token(
                text = rawToken,
                id = dictId,
                importance = importance,
                phonemeIds = phonemes,
                isPredicted = predicted,
                confidence = confidence
            )
        }

        // Step 4: Build CommonSpeechRepresentation
        val representation = CommonSpeechRepresentation(
            language = language,
            tokens = tokens,
            phonemes = tokens.flatMap { it.phonemeIds },
            wordBoundaries = computeWordBoundaries(tokens),
            sentenceBoundaries = listOf(0, tokens.size),
            importance = tokens.map { it.importance },
            messageId = ++messageIdCounter,
            sequenceId = 0,
            rawText = text
        )

        // Step 5: Encode to compact binary
        val tokenEncodedSize = encodeTokenCount(tokens)
        val phonemeEncodedSize = phonemeEncoder.estimateSize(representation.phonemes)
        val binaryData = encodeToBinary(representation)

        // Step 6: Update predictor context
        tokens.forEach { predictor.updateContext(it.id) }

        return EncodedPayload(
            data = binaryData,
            originalUtf8Size = originalUtf8Size,
            tokenEncodedSize = tokenEncodedSize,
            phonemeEncodedSize = phonemeEncodedSize,
            finalEncodedSize = binaryData.size
        )
    }

    fun encodeWithComparison(text: String, language: Language): EncodingComparison {
        val baselineUtf8 = text.toByteArray(Charsets.UTF_8)
        val retroPayload = encode(text, language)

        return EncodingComparison(
            originalText = text,
            originalUtf8Bytes = baselineUtf8.size,
            retroEncodedBytes = retroPayload.finalEncodedSize,
            tokenEncodedBytes = retroPayload.tokenEncodedSize,
            phonemeEncodedBytes = retroPayload.phonemeEncodedSize,
            compressionPercentage = retroPayload.compressionPercentage,
            packetCount = estimatePacketCount(retroPayload.finalEncodedSize)
        )
    }

    private fun normalizeText(text: String, language: Language): String {
        return text.trim()
            .replace(Regex("\\s+"), " ")
            .let { normalized ->
                when (language) {
                    Language.ENGLISH -> normalized.uppercase()
                    Language.HINDI -> normalized
                    Language.TAMIL -> normalized
                    else -> normalized.uppercase()
                }
            }
    }

    private fun tokenize(text: String, language: Language): List<String> {
        return when (language) {
            Language.ENGLISH -> text.split(Regex("\\s+")).filter { it.isNotBlank() }
            Language.HINDI -> text.split(Regex("[\\s।]+")).filter { it.isNotBlank() }
            Language.TAMIL -> text.split(Regex("[\\s.]+")).filter { it.isNotBlank() }
            else -> text.split(Regex("\\s+")).filter { it.isNotBlank() }
        }
    }

    private fun computeWordBoundaries(tokens: List<Token>): List<Int> {
        val boundaries = mutableListOf<Int>()
        var pos = 0
        for (token in tokens) {
            boundaries.add(pos)
            pos += token.text.length + 1
        }
        return boundaries
    }

    private fun encodeTokenCount(tokens: List<Token>): Int {
        var size = 0
        for (token in tokens) {
            if (token.id >= 0) {
                size += 2 // 2 bytes for dictionary ID
            } else {
                size += 2 + token.text.toByteArray(Charsets.UTF_8).size // escape + text
            }
        }
        return size
    }

    private fun encodeToBinary(representation: CommonSpeechRepresentation): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Header
        dos.writeByte(0x52) // 'R' magic
        dos.writeByte(0x45) // 'E'
        dos.writeByte(0x54) // 'T'
        dos.writeByte(0x52) // 'R'
        dos.writeByte(1)    // version
        dos.writeLong(representation.messageId)
        dos.writeInt(representation.sequenceId)
        dos.writeByte(Language.toByte(representation.language).toInt())

        // Token count
        dos.writeInt(representation.tokens.size)

        // Tokens (compact encoding)
        for (token in representation.tokens) {
            val importanceByte = token.importance.level.toByte()
            val flags = (if (token.isPredicted) 0x01 else 0x00).toByte()

            if (token.id >= 0) {
                dos.writeBoolean(true) // has dictionary ID
                dos.writeShort(token.id)
                dos.writeByte(importanceByte.toInt())
                dos.writeByte(flags.toInt())
            } else {
                dos.writeBoolean(false) // no dictionary ID
                val tokenBytes = token.text.toByteArray(Charsets.UTF_8)
                dos.writeShort(tokenBytes.size)
                dos.write(tokenBytes)
                dos.writeByte(importanceByte.toInt())
                dos.writeByte(flags.toInt())
            }
        }

        // Phoneme data (compact)
        dos.writeInt(representation.phonemes.size)
        for (phonemeId in representation.phonemes) {
            dos.writeShort(phonemeId)
        }

        // CRC
        val payload = baos.toByteArray()
        val crc = computeCRC(payload)
        dos.writeInt(crc)

        return baos.toByteArray()
    }

    private fun computeCRC(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor 0xEDB88320.toInt()
                } else {
                    crc ushr 1
                }
            }
        }
        return crc xor 0xFFFFFFFF.toInt()
    }

    private fun estimatePacketCount(encodedSize: Int): Int {
        val maxPayloadSize = 1024
        return (encodedSize + maxPayloadSize - 1) / maxPayloadSize
    }

    fun getDictionary(): TokenDictionary = dictionary
    fun getPhonemeEncoder(): PhonemeEncoder = phonemeEncoder
    fun getImportanceScorer(): ImportanceScorer = importanceScorer
    fun getPredictor(): Predictor = predictor
}

data class EncodingComparison(
    val originalText: String,
    val originalUtf8Bytes: Int,
    val retroEncodedBytes: Int,
    val tokenEncodedBytes: Int,
    val phonemeEncodedBytes: Int,
    val compressionPercentage: Double,
    val packetCount: Int
)
