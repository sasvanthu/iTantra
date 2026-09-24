package com.example.itantra.codec

/**
 * A single meaningful unit produced by [TextNormalizer].
 *
 * A token is either a word (letters/digits of any script, with internal
 * marks such as apostrophes/hyphens kept intact) or a punctuation run.
 * The original spelling is preserved verbatim so that the decoder can
 * reconstruct the exact normalized text.
 */
data class NormalizedToken(
    val text: String,
    val spaceBefore: Boolean,
    val isPunctuation: Boolean
)

/**
 * Result of normalization: the canonical single-space collapsed text plus
 * the token stream that reproduces it exactly via
 * `tokens.joinToString(separator = " ")` style reconstruction using the
 * [spaceBefore] flag.
 */
data class NormalizedText(
    val canonical: String,
    val tokens: List<NormalizedToken>,
    val sentenceEnds: List<Int>
) {
    fun reconstruct(): String {
        val sb = StringBuilder()
        for (t in tokens) {
            if (t.spaceBefore && sb.isNotEmpty()) sb.append(' ')
            sb.append(t.text)
        }
        return sb.toString()
    }
}

/**
 * Normalizes speech / typed text before it enters the codec.
 *
 * Responsibilities:
 * - collapse unnecessary whitespace to single spaces,
 * - trim leading/trailing whitespace,
 * - preserve Tamil/Hindi/English Unicode exactly (no transliteration),
 * - split text into meaningful word / punctuation tokens,
 * - preserve sentence boundaries (`.`, `!`, `?`, `।`).
 *
 * No characters inside a token are altered; the original script and case
 * remain recoverable from the emitted tokens.
 */
object TextNormalizer {

    private val PUNCT_CHARS = setOf(
        '.', ',', '!', '?', ';', ':', '\'', '"',
        '(', ')', '[', ']', '{', '}', '-', '_', '/', '\\',
        '@', '#', '$', '%', '^', '&', '*', '+', '=', '<', '>',
        '|', '~', '`', '\u0964' // Devanagari danda
    )

    fun isPunctuation(ch: Char): Boolean = ch in PUNCT_CHARS

    /** Collapses whitespace runs and trims, leaving a canonical sentence. */
    fun normalize(text: String): NormalizedText {
        val canonical = buildString {
            var lastWasSpace = false
            for (ch in text.trim()) {
                if (ch.isWhitespace()) {
                    if (!lastWasSpace) append(' ')
                    lastWasSpace = true
                } else {
                    append(ch)
                    lastWasSpace = false
                }
            }
        }
        val tokens = tokenize(canonical)
        val sentenceEnds = mutableListOf<Int>()
        tokens.forEachIndexed { index, token ->
            if (token.isPunctuation && token.text.any { it in ".!?\u0964" }) {
                sentenceEnds.add(index)
            }
        }
        return NormalizedText(canonical, tokens, sentenceEnds)
    }

    /** Splits normalized text into word and punctuation tokens. */
    fun tokenize(text: String): List<NormalizedToken> {
        val tokens = mutableListOf<NormalizedToken>()
        var i = 0
        var pendingSpace = false
        while (i < text.length) {
            val ch = text[i]
            if (ch.isWhitespace()) {
                pendingSpace = true
                i++
                continue
            }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val run = text.substring(start, i)
            decomposeRun(run, pendingSpace).forEach { tokens.add(it) }
            pendingSpace = false
        }
        return tokens
    }

    /**
     * Splits a whitespace-free run into leading punctuation, the word body
     * and trailing punctuation. Every emitted token carries the same
     * [spaceBefore] value, but only the first emitted token actually renders
     * the leading space (matching [NormaliizedToken.spaceBefore] semantics,
     * since reconstruction uses spaceBefore only on the first of a run).
     */
    private fun decomposeRun(run: String, spaceBefore: Boolean): List<NormalizedToken> {
        var wordStart = run.length
        for (j in run.indices) {
            if (!isPunctuation(run[j])) {
                wordStart = j
                break
            }
        }
        var wordEnd = wordStart
        while (wordEnd < run.length && !isPunctuation(run[wordEnd])) wordEnd++

        val parts = mutableListOf<NormalizedToken>()
        if (wordStart > 0) {
            parts.add(NormalizedToken(run.substring(0, wordStart), spaceBefore, true))
        }
        if (wordStart < wordEnd) {
            parts.add(NormalizedToken(run.substring(wordStart, wordEnd), spaceBefore && parts.isEmpty(), false))
        }
        if (wordEnd < run.length) {
            parts.add(NormalizedToken(run.substring(wordEnd), spaceBefore && parts.isEmpty(), true))
        }
        return parts
    }
}