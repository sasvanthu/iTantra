package com.example.itantra.codec

import java.io.ByteArrayInputStream
import java.io.DataInputStream

class RetroSpeechDecoder {

    private val phonemeEncoder = PhonemeEncoder()
    private var dictionary: TokenDictionary? = null

    fun setDictionary(dictionary: TokenDictionary) {
        this.dictionary = dictionary
    }

    fun decode(payload: ByteArray): DecodeResult {
        val startTime = System.currentTimeMillis()

        try {
            val bais = ByteArrayInputStream(payload)
            val dis = DataInputStream(bais)

            // Read header
            val magic = ByteArray(4)
            dis.readFully(magic)
            if (magic[0] != 0x52.toByte() || magic[1] != 0x45.toByte() ||
                magic[2] != 0x54.toByte() || magic[3] != 0x52.toByte()) {
                return DecodeResult.Error("Invalid magic bytes")
            }

            val version = dis.readByte()
            val messageId = dis.readLong()
            val sequenceId = dis.readInt()
            val langByte = dis.readByte()
            val language = Language.fromByte(langByte)

            // Read tokens
            val tokenCount = dis.readInt()
            val tokens = mutableListOf<Token>()

            repeat(tokenCount) {
                val hasDictId = dis.readBoolean()
                val token: Token = if (hasDictId) {
                    val dictId = dis.readShort().toInt()
                    val importance = Importance.fromLevel(dis.readByte().toInt())
                    val flags = dis.readByte().toInt()
                    val text = dictionary?.decode(dictId) ?: "[$dictId]"
                    Token(
                        text = text,
                        id = dictId,
                        importance = importance,
                        isPredicted = (flags and 0x01) != 0
                    )
                } else {
                    val tokenLen = dis.readShort().toInt()
                    val tokenBytes = ByteArray(tokenLen)
                    dis.readFully(tokenBytes)
                    val text = String(tokenBytes, Charsets.UTF_8)
                    val importance = Importance.fromLevel(dis.readByte().toInt())
                    val flags = dis.readByte().toInt()
                    Token(
                        text = text,
                        id = -1,
                        importance = importance,
                        isPredicted = (flags and 0x01) != 0
                    )
                }
                tokens.add(token)
            }

            // Read phonemes
            val phonemeCount = dis.readInt()
            val phonemes = mutableListOf<Int>()
            repeat(phonemeCount) {
                phonemes.add(dis.readShort().toInt())
            }

            // Read CRC
            val receivedCRC = dis.readInt()

            // Reconstruct text
            val reconstructedText = tokens.joinToString(" ") { it.text }

            val decodeTime = System.currentTimeMillis() - startTime

            return DecodeResult.Success(
                representation = CommonSpeechRepresentation(
                    language = language,
                    tokens = tokens,
                    phonemes = phonemes,
                    messageId = messageId,
                    sequenceId = sequenceId,
                    rawText = reconstructedText
                ),
                reconstructedText = reconstructedText,
                decodeLatencyMs = decodeTime
            )
        } catch (e: Exception) {
            return DecodeResult.Error("Decode error: ${e.message}")
        }
    }

    fun decodeFromRepresentation(representation: CommonSpeechRepresentation): String {
        return representation.tokens.joinToString(" ") { it.text }
    }
}

sealed class DecodeResult {
    data class Success(
        val representation: CommonSpeechRepresentation,
        val reconstructedText: String,
        val decodeLatencyMs: Long
    ) : DecodeResult()

    data class Error(val message: String) : DecodeResult()
}
