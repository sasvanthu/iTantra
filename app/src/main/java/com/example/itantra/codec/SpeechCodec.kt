package com.example.itantra.codec

interface SpeechCodec {
    fun encode(text: String, language: Language): EncodedPayload
    fun decode(payload: ByteArray): DecodeResult
    fun encodeWithComparison(text: String, language: Language): EncodingComparison
    fun getDictionary(): TokenDictionary
}

class RetroSpeechCodec : SpeechCodec {

    private val encoder = RetroSpeechEncoder()
    private val decoder = RetroSpeechDecoder()

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
}
