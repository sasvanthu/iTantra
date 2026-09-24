package com.example.itantra.codec

enum class Language(val code: String, val displayName: String, val wireByte: Byte) {
    ENGLISH("en", "English", 0x01),
    HINDI("hi", "Hindi", 0x02),
    TAMIL("ta", "Tamil", 0x03),
    BENGALI("bn", "Bengali", 0x04),
    TELUGU("te", "Telugu", 0x05),
    MARATHI("mr", "Marathi", 0x06),
    GUJARATI("gu", "Gujarati", 0x07),
    KANNADA("kn", "Kannada", 0x08),
    MALAYALAM("ml", "Malayalam", 0x09),
    ODIA("or", "Odia", 0x0A),
    UNKNOWN("xx", "Unknown", 0x00);

    companion object {
        private val codeMap = entries.associateBy { it.code }

        fun fromCode(code: String): Language =
            codeMap[code] ?: UNKNOWN

        /** Wire bytes are append-only: existing builds on the air keep their
         * codes while newer ten-language builds add 0x04..0x0A. */
        fun fromByte(b: Byte): Language =
            entries.find { it.wireByte == b } ?: UNKNOWN

        fun toByte(lang: Language): Byte = lang.wireByte
    }
}

enum class Importance(val level: Int) {
    CRITICAL(3),
    HIGH(2),
    NORMAL(1),
    LOW(0);

    companion object {
        fun fromLevel(level: Int): Importance =
            entries.find { it.level == level } ?: NORMAL
    }
}

enum class PacketType(val id: Byte) {
    TEXT_DATA(0x01),
    START(0x02),
    END(0x03),
    ACK(0x04),
    NACK(0x05),
    RETRANSMIT(0x06),
    EMERGENCY(0x07),
    HEARTBEAT(0x08),
    CAPABILITY(0x09),
    LANGUAGE_INFO(0x0A),
    CAPABILITY_ACK(0x0B);

    companion object {
        fun fromId(id: Byte): PacketType =
            entries.find { it.id == id } ?: TEXT_DATA

        /** Strict lookup used by the wire decoder to reject unknown types. */
        fun fromIdOrNull(id: Byte): PacketType? = entries.find { it.id == id }
    }
}

enum class BandwidthMode {
    HIGH_BANDWIDTH,
    NORMAL,
    LOW_BANDWIDTH,
    EMERGENCY
}

data class Token(
    val text: String,
    val id: Int = -1,
    val importance: Importance = Importance.NORMAL,
    val phonemeIds: List<Int> = emptyList(),
    val isPredicted: Boolean = false,
    val confidence: Float = 1.0f
)

data class CommonSpeechRepresentation(
    val language: Language,
    val tokens: List<Token>,
    val phonemes: List<Int> = emptyList(),
    val wordBoundaries: List<Int> = emptyList(),
    val sentenceBoundaries: List<Int> = emptyList(),
    val importance: List<Importance> = emptyList(),
    val messageId: Long = 0,
    val sequenceId: Int = 0,
    val rawText: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val originalText: String get() = rawText

    fun estimatedUtf8Size(): Int = rawText.toByteArray(Charsets.UTF_8).size
}

data class EncodedPayload(
    val data: ByteArray,
    val originalUtf8Size: Int,
    val tokenEncodedSize: Int = 0,
    val phonemeEncodedSize: Int = 0,
    val finalEncodedSize: Int = 0,
    val dictionaryTokens: Int = 0,
    val escapedTokens: Int = 0,
    val predictedTokens: Int = 0,
    val punctTokens: Int = 0,
    val messageId: Long = 0,
    val importance: Importance = Importance.NORMAL
) {
    val compressionPercentage: Double
        get() = if (originalUtf8Size > 0)
            100.0 * (1.0 - finalEncodedSize.toDouble() / originalUtf8Size.toDouble())
        else 0.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedPayload) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}
