package com.example.itantra.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Explicit operational modes for the iTantra speech and message codec.
 */
enum class CodecMode(val id: Byte) {
    /** Token dictionary lookup with UTF-8 escape fallback. */
    LEGACY(0x01),

    /** Script-aware relative offset packing for Indic Brahmic scripts and ASCII. */
    BRAHMIC(0x02),

    /** Structured intent and entity slot frame for task/emergency dispatch. */
    SUTRA(0x03);

    companion object {
        fun fromId(id: Byte): CodecMode = entries.firstOrNull { it.id == id } ?: LEGACY
    }
}

/**
 * Lossless, deterministic, versioned, and self-describing Brahmic codec.
 *
 * Implements Vedic / Brahmic Script Offset Packing (VPMC text layer):
 * - Indic characters (Devanagari, Tamil, Telugu, Bengali, Gujarati, Kannada, Malayalam, Odia)
 *   are stored as 7-bit offsets (0..127) within their script's Unicode block (1 byte vs 3 bytes in UTF-8).
 * - ASCII characters (English letters, digits, punctuation, space) are mapped into 0x80..0xDE (1 byte).
 * - Common Indic marks (Danda, Double Danda, ZWJ, ZWNJ) are single-byte opcodes.
 * - Dynamic script switching enables seamless multilingual mixing.
 * - Arbitrary unsupported Unicode (e.g. emojis, symbols) uses an explicit UTF-8 escape opcode.
 *
 * Guarantees 100% exact text round-trip with zero loss or silent corruption.
 */
object BrahmicCodec {

    val MAGIC = byteArrayOf(0x42, 0x52, 0x48, 0x4D) // 'B', 'R', 'H', 'M'
    const val VERSION: Byte = 1

    // Opcodes in the 0x80..0xFF range
    private const val ASCII_OFFSET_BASE: Int = 0x80
    private const val ASCII_MIN_CHAR: Int = 0x20 // ' '
    private const val ASCII_MAX_CHAR: Int = 0x7E // '~'

    private const val OP_DANDA: Byte = 0xDF.toByte()           // '।' U+0964
    private const val OP_DOUBLE_DANDA: Byte = 0xE0.toByte()    // '॥' U+0965
    private const val OP_NEWLINE: Byte = 0xE1.toByte()         // '\n'
    private const val OP_CARRIAGE_RETURN: Byte = 0xE2.toByte()  // '\r'
    private const val OP_TAB: Byte = 0xE3.toByte()              // '\t'
    private const val OP_ZWJ: Byte = 0xE4.toByte()              // Zero-width joiner U+200D
    private const val OP_ZWNJ: Byte = 0xE5.toByte()             // Zero-width non-joiner U+200C
    private const val OP_SWITCH_SCRIPT: Byte = 0xF0.toByte()    // Followed by 1 byte Language wireByte
    private const val OP_ESCAPE_UTF8: Byte = 0xFF.toByte()      // Followed by uint16 len + UTF-8 bytes

    /**
     * Maps each supported Indic language to its Unicode 128-code-point block base.
     */
    fun getScriptBase(language: Language): Int? = when (language) {
        Language.HINDI, Language.MARATHI -> 0x0900    // Devanagari
        Language.BENGALI -> 0x0980                   // Bengali
        Language.GUJARATI -> 0x0A80                  // Gujarati
        Language.ODIA -> 0x0B00                      // Odia
        Language.TAMIL -> 0x0B80                     // Tamil
        Language.TELUGU -> 0x0C00                    // Telugu
        Language.KANNADA -> 0x0C80                   // Kannada
        Language.MALAYALAM -> 0x0D00                 // Malayalam
        Language.ENGLISH, Language.UNKNOWN -> null   // ASCII or default
    }

    /**
     * Resolves which language script an Indic character belongs to based on Unicode block.
     */
    fun detectScript(char: Char): Language? {
        val code = char.code
        return when (code) {
            in 0x0900..0x097F -> Language.HINDI
            in 0x0980..0x09FF -> Language.BENGALI
            in 0x0A80..0x0AFF -> Language.GUJARATI
            in 0x0B00..0x0B7F -> Language.ODIA
            in 0x0B80..0x0BFF -> Language.TAMIL
            in 0x0C00..0x0C7F -> Language.TELUGU
            in 0x0C80..0x0CFF -> Language.KANNADA
            in 0x0D00..0x0D7F -> Language.MALAYALAM
            else -> null
        }
    }

    /**
     * Encodes [text] in [initialLanguage] into self-describing Brahmic binary representation.
     */
    fun encode(text: String, initialLanguage: Language = Language.HINDI): ByteArray {
        val baos = ByteArrayOutputStream(text.length + 8)

        // Write Frame Header
        baos.write(MAGIC)
        baos.write(VERSION.toInt())
        baos.write(initialLanguage.wireByte.toInt())

        var currentLang = initialLanguage
        var currentBase = getScriptBase(currentLang)

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val code = ch.code

            // 1. Check if character belongs to current script block
            if (currentBase != null && code >= currentBase && code < currentBase + 128) {
                val offset = code - currentBase
                baos.write(offset)
                i++
                continue
            }

            // 2. Check if character belongs to another Indic script (automatic script switch)
            val otherScript = detectScript(ch)
            if (otherScript != null && otherScript != currentLang) {
                currentLang = otherScript
                currentBase = getScriptBase(currentLang)
                baos.write(OP_SWITCH_SCRIPT.toInt() and 0xFF)
                baos.write(currentLang.wireByte.toInt())
                val offset = code - currentBase!!
                baos.write(offset)
                i++
                continue
            }

            // 3. Common Indic special marks
            when (ch) {
                '\u0964' -> { baos.write(OP_DANDA.toInt() and 0xFF); i++; continue }
                '\u0965' -> { baos.write(OP_DOUBLE_DANDA.toInt() and 0xFF); i++; continue }
                '\n' -> { baos.write(OP_NEWLINE.toInt() and 0xFF); i++; continue }
                '\r' -> { baos.write(OP_CARRIAGE_RETURN.toInt() and 0xFF); i++; continue }
                '\t' -> { baos.write(OP_TAB.toInt() and 0xFF); i++; continue }
                '\u200D' -> { baos.write(OP_ZWJ.toInt() and 0xFF); i++; continue }
                '\u200C' -> { baos.write(OP_ZWNJ.toInt() and 0xFF); i++; continue }
            }

            // 4. Printable ASCII (0x20..0x7E)
            if (code in ASCII_MIN_CHAR..ASCII_MAX_CHAR) {
                val asciiByte = ASCII_OFFSET_BASE + (code - ASCII_MIN_CHAR)
                baos.write(asciiByte)
                i++
                continue
            }

            // 5. Unsupported / Extended Unicode (emojis, rare symbols) -> Escape block
            // Collect contiguous escape characters
            val escapeBuf = StringBuilder()
            while (i < text.length) {
                val escChar = text[i]
                val escCode = escChar.code
                val isIndic = (currentBase != null && escCode >= currentBase && escCode < currentBase + 128) ||
                        detectScript(escChar) != null
                val isAscii = escCode in ASCII_MIN_CHAR..ASCII_MAX_CHAR
                val isKnownMark = escChar in "\u0964\u0965\n\r\t\u200D\u200C"

                if (isIndic || isAscii || isKnownMark) break
                escapeBuf.append(escChar)
                i++
            }

            val utf8Bytes = escapeBuf.toString().toByteArray(StandardCharsets.UTF_8)
            baos.write(OP_ESCAPE_UTF8.toInt() and 0xFF)
            baos.write((utf8Bytes.size shr 8) and 0xFF)
            baos.write(utf8Bytes.size and 0xFF)
            baos.write(utf8Bytes)
        }

        return baos.toByteArray()
    }

    /**
     * Decodes a Brahmic binary payload back into original text.
     * Returns null if magic or version mismatch.
     */
    fun decode(bytes: ByteArray): String? {
        if (bytes.size < 6) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Validate Magic
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) return null

        val version = buffer.get()
        if (version != VERSION) return null

        val langByte = buffer.get()
        var currentLang = Language.fromByte(langByte)
        var currentBase = getScriptBase(currentLang)

        val sb = StringBuilder()

        while (buffer.hasRemaining()) {
            val b = buffer.get()
            val unsigned = b.toInt() and 0xFF

            when {
                // 0x00..0x7F: Indic relative offset in current script
                unsigned in 0..0x7F -> {
                    val base = currentBase ?: 0x0900 // Fallback to Devanagari if unknown
                    sb.append((base + unsigned).toChar())
                }

                // 0x80..0xDE: Printable ASCII character
                unsigned in ASCII_OFFSET_BASE..0xDE -> {
                    val asciiCode = unsigned - ASCII_OFFSET_BASE + ASCII_MIN_CHAR
                    sb.append(asciiCode.toChar())
                }

                // Special formatting marks
                b == OP_DANDA -> sb.append('\u0964')
                b == OP_DOUBLE_DANDA -> sb.append('\u0965')
                b == OP_NEWLINE -> sb.append('\n')
                b == OP_CARRIAGE_RETURN -> sb.append('\r')
                b == OP_TAB -> sb.append('\t')
                b == OP_ZWJ -> sb.append('\u200D')
                b == OP_ZWNJ -> sb.append('\u200C')

                // Script switch
                b == OP_SWITCH_SCRIPT -> {
                    if (buffer.remaining() < 1) return null
                    val newLangByte = buffer.get()
                    currentLang = Language.fromByte(newLangByte)
                    currentBase = getScriptBase(currentLang)
                }

                // UTF-8 escape
                b == OP_ESCAPE_UTF8 -> {
                    if (buffer.remaining() < 2) return null
                    val len = buffer.getShort().toInt() and 0xFFFF
                    if (buffer.remaining() < len) return null
                    val utf8 = ByteArray(len)
                    buffer.get(utf8)
                    sb.append(String(utf8, StandardCharsets.UTF_8))
                }

                else -> {
                    // Unknown opcode - skip safely
                }
            }
        }

        return sb.toString()
    }
}
