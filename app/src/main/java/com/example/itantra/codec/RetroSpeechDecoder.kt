package com.example.itantra.codec

/**
 * Decodes a RETRO binary frame back into the exact normalized text.
 *
 * - Rejects frames whose CRC does not match.
 * - Reconstructed PREDICTED tokens come from the decoder's own
 *   [ContextPredictor], which is kept in lock-step with the encoder because
 *   both rebuild identical per-message frequency tables.
 * - Dictionary words are re-rendered to the original case via the stored
 *   LOWER / TITLE / UPPER hint; ESCAPE tokens carry their exact text; the
 *   punctuation catalog restores punctuation marks.
 */
class RetroSpeechDecoder(
    private val predictor: ContextPredictor = ContextPredictor(),
    private val binaryCodec: BinaryCodec = BinaryCodec()
) {

    private var dictionary: TokenDictionary? = null

    fun setDictionary(dictionary: TokenDictionary) {
        this.dictionary = dictionary
    }

    fun decode(payload: ByteArray): DecodeResult {
        val start = System.nanoTime()
        return try {
            val frame = binaryCodec.decode(payload)
            if (!frame.crcValid) {
                return DecodeResult.Error(
                    "CRC mismatch: stored=${frame.crcStored} computed=${frame.crcComputed}. Packet rejected."
                )
            }
            if (frame.version > BinaryCodec.VERSION) {
                return DecodeResult.Error("Unsupported frame version=${frame.version}")
            }

            predictor.beginMessage()
            val sb = StringBuilder()
            val tokens = mutableListOf<Token>()

            for (spec in frame.tokens) {
                val text: String
                when (spec.kind) {
                    BinaryCodec.Kind.WORD -> {
                        val id = if (spec.predicted) {
                            predictor.nextPrediction()
                        } else {
                            spec.dictId
                        }
                        val base = dictionary?.decode(id) ?: "[$id]"
                        text = if (frame.language == Language.ENGLISH) {
                            applyCase(base, spec.caseCode)
                        } else {
                            base
                        }
                        predictor.note(id)
                    }
                    BinaryCodec.Kind.ESCAPE -> {
                        text = spec.text
                        predictor.note(ContextPredictor.OOV_ID)
                    }
                    BinaryCodec.Kind.PUNCT -> {
                        text = spec.text
                        predictor.note(ContextPredictor.OOV_ID)
                    }
                    else -> {
                        text = ""
                        predictor.note(ContextPredictor.OOV_ID)
                    }
                }

                if (spec.spaceBefore && sb.isNotEmpty()) sb.append(' ')
                sb.append(text)
                tokens.add(
                    Token(
                        text = text,
                        id = if (spec.kind == BinaryCodec.Kind.WORD) spec.dictId else -1,
                        importance = spec.importance,
                        isPredicted = spec.predicted
                    )
                )
            }

            val reconstructedText = sb.toString()
            val decodeMs = (System.nanoTime() - start) / 1_000_000L

            DecodeResult.Success(
                representation = CommonSpeechRepresentation(
                    language = frame.language,
                    tokens = tokens,
                    phonemes = frame.phonemes,
                    sentenceBoundaries = computeSentenceEnds(tokens),
                    importance = tokens.map { it.importance },
                    messageId = frame.messageId,
                    sequenceId = frame.sequenceId,
                    rawText = reconstructedText
                ),
                reconstructedText = reconstructedText,
                decodeLatencyMs = decodeMs
            )
        } catch (e: CodecException) {
            DecodeResult.Error("Decode error: ${e.message}")
        }
    }

    private fun computeSentenceEnds(tokens: List<Token>): List<Int> {
        val ends = mutableListOf<Int>()
        tokens.forEachIndexed { i, t ->
            if (t.text.any { it in ".!?\u0964" }) ends.add(i)
        }
        return ends
    }

    private fun applyCase(word: String, caseCode: Int): String = when (caseCode) {
        BinaryCodec.CaseCode.LOWER -> word.lowercase()
        BinaryCodec.CaseCode.TITLE ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        BinaryCodec.CaseCode.UPPER -> word.uppercase()
        else -> word
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