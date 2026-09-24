package com.example.itantra.codec

/**
 * Encodes speech text into the compact lossless RETRO binary frame.
 *
 * Pipeline:
 * ```
 * text -> TextNormalizer -> importance scoring -> dictionary lookup ->
 * prediction -> EncodedTokenSpec -> BinaryCodec (binary frame + CRC)
 * ```
 *
 * The encoding is a real binary format (see [BinaryCodec]) and never a JSON
 * dump of token ids. Original text is recoverable exactly:
 * - dictionary words keep an all-lower / title / all-upper case hint,
 * - unknown or mixed-case words use the ESCAPE mechanism (exact UTF-8),
 * - punctuation is captured via a small catalog,
 * - whitespace is normalized to single spaces (canonical form).
 */
class RetroSpeechEncoder(
    private val dictionary: TokenDictionary = TokenDictionary(),
    private val predictor: ContextPredictor = ContextPredictor()
) {

    private val binaryCodec = BinaryCodec()
    private val importanceScorer = ImportanceScorer()

    private var messageIdCounter = 0L

    /** Standalone encode used by pipelines. See [encodeExpanded] for details. */
    fun encode(text: String, language: Language): EncodedPayload =
        encodeExpanded(text, language).payload

    /**
     * Full encode with normalization, token stats and honest per-section
     * byte counts. Does not perform a decode (use RetroSpeechDecoder +
     * [markPayloadDecoded] for the round trip).
     */
    fun encodeExpanded(text: String, language: Language): EncodeReport {
        val start = System.nanoTime()
        val normalized = TextNormalizer.normalize(text)
        val originalUtf8 = text.toByteArray(Charsets.UTF_8).size

        predictor.beginMessage()
        val specs = mutableListOf<EncodedTokenSpec>()
        val tokens = mutableListOf<Token>()
        val sentenceEnds = mutableListOf<Int>()
        var dictCount = 0
        var escCount = 0
        var predCount = 0
        var punctCount = 0
        var maxImportance = Importance.LOW

        normalized.tokens.forEachIndexed { index, nt ->
            if (nt.isPunctuation) {
                specs.add(EncodedTokenSpec(BinaryCodec.Kind.PUNCT, nt.text, spaceBefore = nt.spaceBefore))
                predictor.note(ContextPredictor.OOV_ID)
                punctCount++
                tokens.add(Token(nt.text, -1, Importance.LOW, isPredicted = false))
                if (nt.text.any { it in ".!?\u0964" }) sentenceEnds.add(index)
                return@forEachIndexed
            }

            val id = dictionary.encode(nt.text)
            val importance = importanceScorer.score(nt.text)
            if (importance.level > maxImportance.level) maxImportance = importance

            if (id >= 0) {
                val canonical = dictionary.decode(id)
                val caseCode = computeCaseCode(nt.text, canonical ?: nt.text, language)
                if (caseCode == BinaryCodec.CaseCode.OTHER) {
                    // Mixed case that the dictionary spelling cannot reproduce -> escape
                    specs.add(EncodedTokenSpec(BinaryCodec.Kind.ESCAPE, nt.text, importance = importance, spaceBefore = nt.spaceBefore))
                    predictor.note(ContextPredictor.OOV_ID)
                    escCount++
                    tokens.add(Token(nt.text, -1, importance, isPredicted = false))
                } else {
                    val predicted = predictor.nextPrediction() == id
                    if (predicted) predCount++
                    specs.add(EncodedTokenSpec(BinaryCodec.Kind.WORD, nt.text, id, caseCode, importance, predicted, nt.spaceBefore))
                    predictor.note(id)
                    dictCount++
                    tokens.add(Token(nt.text, id, importance, isPredicted = predicted))
                }
            } else {
                specs.add(EncodedTokenSpec(BinaryCodec.Kind.ESCAPE, nt.text, importance = importance, spaceBefore = nt.spaceBefore))
                predictor.note(ContextPredictor.OOV_ID)
                escCount++
                tokens.add(Token(nt.text, -1, importance, isPredicted = false))
            }
        }

        val messageId = ++messageIdCounter
        val isEmergency = maxImportance == Importance.CRITICAL
        val payload = binaryCodec.encode(
            language = language,
            messageId = messageId,
            sequenceId = 0,
            tokens = specs,
            includePhonemes = false,
            isEmergency = isEmergency,
            priority = maxImportance.level
        )

        // Honest byte accounting: fixed overhead (magic/version/lang/ids/flags/
        // priority/token+phoneme counts/CRC) vs the token entries themselves.
        val overheadBytes = 4 + 1 + 1 +
            varintLen(messageId) + varintLen(0) + 1 + 1 +
            varintLen(specs.size.toLong()) +
            varintLen(0) + // phoneme count = 0
            BinaryCodec.CRC_BYTES
        val tokenEncodedSize = (payload.size - overheadBytes).coerceAtLeast(0)

        val encodeMs = (System.nanoTime() - start) / 1_000_000L

        return EncodeReport(
            payload = EncodedPayload(
                data = payload,
                originalUtf8Size = originalUtf8,
                tokenEncodedSize = tokenEncodedSize,
                phonemeEncodedSize = 0,
                finalEncodedSize = payload.size,
                dictionaryTokens = dictCount,
                escapedTokens = escCount,
                predictedTokens = predCount,
                punctTokens = punctCount,
                messageId = messageId,
                importance = maxImportance
            ),
            normalizedText = normalized.canonical,
            sentenceBoundaries = sentenceEnds,
            tokenSpecs = specs,
            encodeMs = encodeMs
        )
    }

    fun encodeWithComparison(text: String, language: Language): EncodingComparison {
        val report = encodeExpanded(text, language)
        return EncodingComparison(
            originalText = text,
            originalUtf8Bytes = report.payload.originalUtf8Size,
            retroEncodedBytes = report.payload.finalEncodedSize,
            tokenEncodedBytes = report.payload.tokenEncodedSize,
            phonemeEncodedBytes = report.payload.phonemeEncodedSize,
            compressionPercentage = report.payload.compressionPercentage,
            packetCount = estimatePacketCount(report.payload.finalEncodedSize),
            predictedTokens = report.payload.predictedTokens,
            escapedTokens = report.payload.escapedTokens,
            dictionaryTokens = report.payload.dictionaryTokens,
            punctTokens = report.payload.punctTokens
        )
    }

    private fun computeCaseCode(original: String, canonical: String, language: Language): Int {
        if (language != Language.ENGLISH) {
            // Script languages restore the dictionary spelling verbatim, so a
            // dictionary hit is only lossless when it matches exactly. Mixed
            // Latin words (e.g. "am" inside a Hindi message) must go ESCAPE.
            return if (original == canonical) BinaryCodec.CaseCode.UPPER else BinaryCodec.CaseCode.OTHER
        }
        if (original == canonical) return BinaryCodec.CaseCode.UPPER
        if (original == canonical.lowercase()) return BinaryCodec.CaseCode.LOWER
        val title = canonical.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        if (original == title) return BinaryCodec.CaseCode.TITLE
        return BinaryCodec.CaseCode.OTHER
    }

    private fun varintLen(value: Long): Int {
        var v = value
        var len = 1
        while (v and 0x7F.inv().toLong() != 0L) {
            len++
            v = v ushr 7
        }
        return len
    }

    private fun estimatePacketCount(encodedSize: Int): Int {
        val maxPayloadSize = 1024
        return (encodedSize + maxPayloadSize - 1) / maxPayloadSize
    }

    fun getDictionary(): TokenDictionary = dictionary
    fun getPredictor(): ContextPredictor = predictor
    fun getImportanceScorer(): ImportanceScorer = importanceScorer
}

/**
 * Everything produced by a single encode: the binary payload, the canonical
 * normalized text, token entry stats and real encode timing.
 */
data class EncodeReport(
    val payload: EncodedPayload,
    val normalizedText: String,
    val sentenceBoundaries: List<Int>,
    val tokenSpecs: List<EncodedTokenSpec>,
    val encodeMs: Long
)

data class EncodingComparison(
    val originalText: String,
    val originalUtf8Bytes: Int,
    val retroEncodedBytes: Int,
    val tokenEncodedBytes: Int,
    val phonemeEncodedBytes: Int,
    val compressionPercentage: Double,
    val packetCount: Int,
    val dictionaryTokens: Int = 0,
    val escapedTokens: Int = 0,
    val predictedTokens: Int = 0,
    val punctTokens: Int = 0
)