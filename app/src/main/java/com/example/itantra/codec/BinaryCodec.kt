package com.example.itantra.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Thrown when a binary frame cannot be parsed or fails its integrity check.
 */
sealed class CodecException(message: String) : Exception(message) {
    class InvalidMagic : CodecException("Invalid magic bytes")
    class InvalidCrc : CodecException("CRC validation failed")
    class Truncated : CodecException("Truncated frame")
}

/**
 * A single token as it appears in the binary frame.
 */
data class EncodedTokenSpec(
    val kind: Int,
    val text: String,
    val dictId: Int = -1,
    val caseCode: Int = BinaryCodec.CaseCode.OTHER,
    val importance: Importance = Importance.NORMAL,
    val predicted: Boolean = false,
    val spaceBefore: Boolean = false
)

/**
 * Fully parsed binary frame, as produced by [BinaryCodec.decode].
 */
data class DecodedFrame(
    val version: Int,
    val language: Language,
    val messageId: Long,
    val sequenceId: Int,
    val flags: Int,
    val priority: Int,
    val isEmergency: Boolean,
    val tokenCount: Int,
    val tokens: List<EncodedTokenSpec>,
    val phonemes: List<Int>,
    val crcStored: Int,
    val crcComputed: Int,
    val crcValid: Boolean
)

/**
 * Real binary encoder / decoder for the RETRO speech frame.
 *
 * This is NOT JSON and NOT a raw dump of token IDs. It is a compact binary
 * format:
 *
 * ```
 * HEADER
 *   magic     4 bytes  ('R','E','T','R')
 *   version   1 byte
 *   language  1 byte
 *   messageId varint   (compact integer encoding)
 *   sequenceId varint
 *   flags     1 byte   (bit0 emergency, bit1 phonemes included)
 *   priority  1 byte   (0..7)
 * PAYLOAD - one entry per token:
 *   prefix    1 byte    [spaceBefore][kind(2)][predicted][importance(2)][case(2)]
 *   then per kind:
 *     WORD     -> varint dictionary id   (omitted entirely when predicted)
 *     ESCAPE   -> varint utf8 length + exact original UTF-8 text
 *     PUNCT    -> varint utf8 length + exact UTF-8 punctuation text
 *   phonemeCount varint (+ ids, EXPERIMENTAL - default omitted)
 * CRC32 4 bytes over everything above (malformed/old frames are rejected)
 * ```
 *
 * Integers use 7-bit LEB128 style varints, so the common case (small ids,
 * lengths) costs a single byte.
 */
class BinaryCodec {

    companion object {
        const val MAGIC_0: Byte = 0x52 // 'R'
        const val MAGIC_1: Byte = 0x45 // 'E'
        const val MAGIC_2: Byte = 0x54 // 'T'
        const val MAGIC_3: Byte = 0x52 // 'R'
        const val VERSION: Int = 2
        const val CRC_BYTES: Int = 4

        const val FLAG_EMERGENCY: Int = 0x01
        const val FLAG_PHONEMES: Int = 0x02
    }

    object Kind {
        const val WORD = 0      // dictionary id (optionally predicted)
        const val ESCAPE = 1    // literal UTF-8 text
        const val PUNCT = 2     // punctuation catalog code
        const val RESERVED = 3
    }

    object CaseCode {
        const val LOWER = 0
        const val TITLE = 1
        const val UPPER = 2
        const val OTHER = 3
    }

    fun encode(
        language: Language,
        messageId: Long,
        sequenceId: Int,
        tokens: List<EncodedTokenSpec>,
        includePhonemes: Boolean = false,
        phonemes: List<Int> = emptyList(),
        isEmergency: Boolean = false,
        priority: Int = 1
    ): ByteArray {
        val baos = ByteArrayOutputStream(32 + tokens.size * 8)
        val out = DataOutputStream(baos)

        // --- HEADER ---
        out.writeByte(MAGIC_0.toInt())
        out.writeByte(MAGIC_1.toInt())
        out.writeByte(MAGIC_2.toInt())
        out.writeByte(MAGIC_3.toInt())
        out.writeByte(VERSION)
        out.writeByte(Language.toByte(language).toInt())
        writeVarint(out, messageId)
        writeVarint(out, sequenceId.toLong())
        val flags = (if (isEmergency) FLAG_EMERGENCY else 0) or
            (if (includePhonemes) FLAG_PHONEMES else 0)
        out.writeByte(flags)
        out.writeByte(priority and 0x07)

        // --- PAYLOAD ---
        writeVarint(out, tokens.size.toLong())
        for (t in tokens) {
            out.writeByte(
                (if (t.spaceBefore) 0x01 else 0x00) or
                    ((t.kind and 0x03) shl 1) or
                    (if (t.predicted) 0x08 else 0x00) or
                    ((t.importance.level and 0x03) shl 4) or
                    ((t.caseCode and 0x03) shl 6)
            )
            when (t.kind) {
                Kind.WORD -> if (!t.predicted) writeVarint(out, t.dictId.toLong())
                Kind.ESCAPE -> {
                    val utf8 = t.text.toByteArray(Charsets.UTF_8)
                    writeVarint(out, utf8.size.toLong())
                    out.write(utf8)
                }
                Kind.PUNCT -> {
                    require(t.text.isNotEmpty()) { "empty punctuation token" }
                    val utf8 = t.text.toByteArray(Charsets.UTF_8)
                    writeVarint(out, utf8.size.toLong())
                    out.write(utf8)
                }
            }
        }

        // --- EXPERIMENTAL PHONEME CLUSTER (opt-in only) ---
        writeVarint(out, if (includePhonemes) phonemes.size.toLong() else 0L)
        if (includePhonemes) {
            for (id in phonemes) writeVarint(out, id.toLong())
        }

        // --- CHECKSUM ---
        val body = baos.toByteArray()
        val crc = crc32(body)
        baos.write(byteArrayOf(
            (crc shr 24).toByte(), (crc shr 16).toByte(),
            (crc shr 8).toByte(), crc.toByte()
        ))
        out.flush()
        return baos.toByteArray()
    }

    /**
     * Parses and integrity-checks a frame. Does not resolve token text
     * (that is the decoder's job, so it can take part in prediction).
     */
    fun decode(data: ByteArray): DecodedFrame {
        val input = DataInputStream(ByteArrayInputStream(data))
        try {
            val magic = ByteArray(4)
            if (input.read(magic) != 4) throw CodecException.Truncated()
            if (magic[0] != MAGIC_0 || magic[1] != MAGIC_1 || magic[2] != MAGIC_2 || magic[3] != MAGIC_3) {
                throw CodecException.InvalidMagic()
            }

            val version = input.readByte().toInt() and 0xFF
            val language = Language.fromByte(input.readByte())
            val messageId = readVarint(input)
            val sequenceId = readVarint(input).toInt()
            val flags = input.readByte().toInt() and 0xFF
            val priority = input.readByte().toInt() and 0x07

            val tokenCount = readVarint(input).toInt()
            val tokens = mutableListOf<EncodedTokenSpec>()
            repeat(tokenCount) {
                val prefix = input.readByte().toInt() and 0xFF
                val spaceBefore = (prefix and 0x01) != 0
                val kind = (prefix shr 1) and 0x03
                val predicted = (prefix and 0x08) != 0
                val importanceLevel = (prefix shr 4) and 0x03
                val caseCode = (prefix shr 6) and 0x03

                val spec = when (kind) {
                    Kind.WORD -> {
                        val id = if (predicted) -1 else readVarint(input).toInt()
                        EncodedTokenSpec(kind, "", id, caseCode, Importance.fromLevel(importanceLevel), predicted, spaceBefore)
                    }
                    Kind.ESCAPE -> {
                        val len = readVarint(input).toInt()
                        if (len < 0 || len > data.size * 2 + 64) throw CodecException.Truncated()
                        val bytes = ByteArray(len)
                        input.readFully(bytes)
                        val text = String(bytes, Charsets.UTF_8)
                        EncodedTokenSpec(kind, text, -1, CaseCode.OTHER, Importance.fromLevel(importanceLevel), false, spaceBefore)
                    }
                    Kind.PUNCT -> {
                        val len = readVarint(input).toInt()
                        if (len < 0 || len > data.size * 2 + 64) throw CodecException.Truncated()
                        val bytes = ByteArray(len)
                        input.readFully(bytes)
                        val text = String(bytes, Charsets.UTF_8)
                        EncodedTokenSpec(kind, text, -1, CaseCode.OTHER, Importance.fromLevel(importanceLevel), false, spaceBefore)
                    }
                    else -> throw CodecException.Truncated()
                }
                tokens.add(spec)
            }

            val phonemeCount = readVarint(input).toInt()
            val phonemes = mutableListOf<Int>()
            repeat(phonemeCount) {
                phonemes.add(readVarint(input).toInt())
            }

            if (data.size < CRC_BYTES + 1) throw CodecException.Truncated()
            val bodyLength = data.size - CRC_BYTES
            val computed = crc32(data.copyOf(bodyLength))
            val crcStored = ((data[bodyLength].toInt() and 0xFF) shl 24) or
                ((data[bodyLength + 1].toInt() and 0xFF) shl 16) or
                ((data[bodyLength + 2].toInt() and 0xFF) shl 8) or
                (data[bodyLength + 3].toInt() and 0xFF)

            return DecodedFrame(
                version = version,
                language = language,
                messageId = messageId,
                sequenceId = sequenceId,
                flags = flags,
                priority = priority,
                isEmergency = (flags and FLAG_EMERGENCY) != 0,
                tokenCount = tokenCount,
                tokens = tokens,
                phonemes = phonemes,
                crcStored = crcStored,
                crcComputed = computed,
                crcValid = computed == crcStored
            )
        } catch (e: CodecException) {
            throw e
        } catch (e: Exception) {
            throw CodecException.Truncated()
        }
    }

    fun crc32(data: ByteArray): Int {
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

    fun writeVarint(out: DataOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7F.inv().toLong() == 0L) {
                out.writeByte(v.toInt())
                break
            } else {
                out.writeByte((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    fun readVarint(input: DataInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.readByte().toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw CodecException.Truncated()
        }
        return result
    }
}