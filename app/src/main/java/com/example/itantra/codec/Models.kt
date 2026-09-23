package com.example.itantra.codec

enum class Language(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    HINDI("hi", "Hindi"),
    TAMIL("ta", "Tamil"),
    UNKNOWN("xx", "Unknown");

    companion object {
        private val codeMap = entries.associateBy { it.code }

        fun fromCode(code: String): Language =
            codeMap[code] ?: UNKNOWN

        fun fromByte(b: Byte): Language = when (b) {
            0x01.toByte() -> ENGLISH
            0x02.toByte() -> HINDI
            0x03.toByte() -> TAMIL
            else -> UNKNOWN
        }

        fun toByte(lang: Language): Byte = when (lang) {
            ENGLISH -> 0x01
            HINDI -> 0x02
            TAMIL -> 0x03
            UNKNOWN -> 0x00
        }
    }
}

enum class Importance(val level: Int) {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3);

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
    LANGUAGE_INFO(0x0A);

    companion object {
        fun fromId(id: Byte): PacketType =
            entries.find { it.id == id } ?: TEXT_DATA
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
    fun estimatedUtf8Size(): Int = rawText.toByteArray(Charsets.UTF_8).size
}

data class EncodedPayload(
    val data: ByteArray,
    val originalUtf8Size: Int,
    val tokenEncodedSize: Int,
    val phonemeEncodedSize: Int,
    val finalEncodedSize: Int
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
