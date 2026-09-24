package com.example.itantra.codec

import com.example.itantra.protocol.Packetizer

interface SpeechCodec {
    fun encode(text: String, language: Language): EncodedPayload
    fun decode(payload: ByteArray): DecodeResult
    fun encodeWithComparison(text: String, language: Language): EncodingComparison
    fun getDictionary(): TokenDictionary

    /** Full encode + packetize + decode round trip with real timings and sizes. */
    fun performLab(text: String, language: Language): CodecLabResult

    /** Encode + packetize only. Returns (result, binary payload). */
    fun encodeLab(text: String, language: Language): Pair<CodecLabResult, ByteArray>

    /** Decode an already-encoded payload, completing a partial lab result. */
    fun decodeLab(payload: ByteArray, base: CodecLabResult): CodecLabResult
}

class RetroSpeechCodec : SpeechCodec {

    private val encoder = RetroSpeechEncoder()
    private val decoder = RetroSpeechDecoder(encoder.getPredictor())

    init {
        decoder.setDictionary(encoder.getDictionary())
    }

    override fun encode(text: String, language: Language): EncodedPayload {
        return encoder.encode(text, language)
    }

    override fun decode(payload: ByteArray): DecodeResult {
        return decoder.decode(payload)
    }

    override fun encodeWithComparison(text: String, language: Language): EncodingComparison {
        return encoder.encodeWithComparison(text, language)
    }

    override fun getDictionary(): TokenDictionary = encoder.getDictionary()

    override fun performLab(text: String, language: Language): CodecLabResult {
        val (base, payload) = encodeLab(text, language)
        return decodeLab(payload, base)
    }

    override fun encodeLab(text: String, language: Language): Pair<CodecLabResult, ByteArray> {
        val report = encoder.encodeExpanded(text, language)
        val payload = report.payload

        val packetizeStart = System.nanoTime()
        val packets = Packetizer.buildPackets(
            payload = payload.data,
            language = language,
            messageId = payload.messageId,
            priority = payload.importance.level,
            isEmergency = payload.importance == Importance.CRITICAL
        )
        val packetBytes = packets.sumOf { it.serialize().size }
        val packetizeMs = (System.nanoTime() - packetizeStart) / 1_000_000L

        val result = CodecLabResult(
            originalText = text,
            normalizedText = report.normalizedText,
            language = language,
            originalUtf8Bytes = payload.originalUtf8Size,
            tokenEncodedBytes = payload.tokenEncodedSize,
            phonemeEncodedBytes = payload.phonemeEncodedSize,
            encodedBytes = payload.finalEncodedSize,
            packetCount = packets.size,
            packetBytes = packetBytes,
            dictionaryTokens = payload.dictionaryTokens,
            escapedTokens = payload.escapedTokens,
            predictedTokens = payload.predictedTokens,
            punctTokens = payload.punctTokens,
            messageImportance = payload.importance,
            encodeMs = report.encodeMs,
            packetizeMs = packetizeMs
        )
        return result to payload.data
    }

    override fun decodeLab(payload: ByteArray, base: CodecLabResult): CodecLabResult {
        val decodeStart = System.nanoTime()
        val result = decoder.decode(payload)
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000L

        if (result is DecodeResult.Success) {
            val exact = result.reconstructedText == base.normalizedText
            return base.withDecoded(
                decoded = result.reconstructedText,
                decodeMs = decodeMs,
                totalMs = base.encodeMs + base.packetizeMs + decodeMs,
                exact = exact
            )
        }
        return base.withDecoded(
            decoded = "DECODE ERROR: ${(result as DecodeResult.Error).message}",
            decodeMs = decodeMs,
            totalMs = base.encodeMs + base.packetizeMs + decodeMs,
            exact = false
        )
    }

    fun getEncoder(): RetroSpeechEncoder = encoder
    fun getDecoder(): RetroSpeechDecoder = decoder
}

class BaselineCodec : SpeechCodec {

    override fun encode(text: String, language: Language): EncodedPayload {
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        return EncodedPayload(
            data = utf8Bytes,
            originalUtf8Size = utf8Bytes.size,
            tokenEncodedSize = utf8Bytes.size,
            phonemeEncodedSize = 0,
            finalEncodedSize = utf8Bytes.size
        )
    }

    override fun decode(payload: ByteArray): DecodeResult {
        val text = String(payload, Charsets.UTF_8)
        return DecodeResult.Success(
            representation = CommonSpeechRepresentation(
                language = Language.UNKNOWN,
                tokens = emptyList(),
                rawText = text
            ),
            reconstructedText = text,
            decodeLatencyMs = 0
        )
    }

    override fun encodeWithComparison(text: String, language: Language): EncodingComparison {
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        return EncodingComparison(
            originalText = text,
            originalUtf8Bytes = utf8Bytes.size,
            retroEncodedBytes = utf8Bytes.size,
            tokenEncodedBytes = utf8Bytes.size,
            phonemeEncodedBytes = 0,
            compressionPercentage = 0.0,
            packetCount = 1
        )
    }

    override fun getDictionary(): TokenDictionary = TokenDictionary()

    override fun performLab(text: String, language: Language): CodecLabResult {
        val (base, payload) = encodeLab(text, language)
        return decodeLab(payload, base)
    }

    override fun encodeLab(text: String, language: Language): Pair<CodecLabResult, ByteArray> {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val packets = Packetizer.buildPackets(
            payload = utf8,
            language = language,
            messageId = 1,
            priority = 1,
            isEmergency = false
        )
        val result = CodecLabResult(
            originalText = text,
            normalizedText = text,
            language = language,
            originalUtf8Bytes = utf8.size,
            tokenEncodedBytes = utf8.size,
            encodedBytes = utf8.size,
            packetCount = packets.size,
            packetBytes = packets.sumOf { it.serialize().size },
            messageImportance = Importance.NORMAL,
            encodeMs = 0,
            packetizeMs = 0
        )
        return result to utf8
    }

    override fun decodeLab(payload: ByteArray, base: CodecLabResult): CodecLabResult {
        val text = String(payload, Charsets.UTF_8)
        return base.withDecoded(text, decodeMs = 0, totalMs = base.encodeMs + base.packetizeMs, exact = text == base.normalizedText)
    }
}