package com.example.itantra.codec

/**
 * A single honest comparison of BASELINE (UTF-8) vs RETRO for one message.
 *
 * All byte counts are measured from the actual implementation. When the
 * encoded packet is larger than UTF-8 (packet headers, CRC, ids, dictionary
 * metadata for short messages) [modeLabel] reads "OVERHEAD"; no artificial
 * compression is ever claimed.
 */
data class CodecLabResult(
    val originalText: String = "",
    val normalizedText: String = "",
    val language: Language = Language.UNKNOWN,

    val originalUtf8Bytes: Int = 0,
    val tokenEncodedBytes: Int = 0,
    val phonemeEncodedBytes: Int = 0,
    // Full encoded frame (header + payload + phoneme cluster + CRC)
    val encodedBytes: Int = 0,

    val packetCount: Int = 0,
    val packetBytes: Int = 0,

    val dictionaryTokens: Int = 0,
    val escapedTokens: Int = 0,
    val predictedTokens: Int = 0,
    val punctTokens: Int = 0,
    val messageImportance: Importance = Importance.NORMAL,

    val decodedText: String = "",
    val exactMatch: Boolean = false,

    val encodeMs: Long = -1,
    val packetizeMs: Long = 0,
    val decodeMs: Long = -1,
    val totalMs: Long = 0
) {

    val compressionPercent: Double
        get() = if (originalUtf8Bytes > 0) {
            100.0 * (1.0 - encodedBytes.toDouble() / originalUtf8Bytes.toDouble())
        } else 0.0

    val modeLabel: String
        get() = when {
            encodedBytes > originalUtf8Bytes -> "OVERHEAD"
            encodedBytes < originalUtf8Bytes -> "REDUCTION"
            else -> "EQUAL"
        }

    val integrityLabel: String
        get() = if (exactMatch) "SUCCESS" else "FAILED"

    fun withDecoded(decoded: String, decodeMs: Long, totalMs: Long, exact: Boolean): CodecLabResult =
        copy(decodedText = decoded, decodeMs = decodeMs, totalMs = totalMs, exactMatch = exact)
}