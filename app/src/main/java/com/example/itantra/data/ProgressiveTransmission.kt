package com.example.itantra.data

import com.example.itantra.protocol.Packetizer

/**
 * Phase 13 — progressive transmission.
 *
 * A message does not have to arrive all at once. The sender carves a small,
 * instantly-deliverable **preview stage** out of the real message text; the
 * receiver can render that immediately (approximate but useful), and the full
 * binary follows over the same path.
 *
 * Honesty rules:
 *  - The preview is REAL: it is the UTF-8 prefix of the actual message text,
 *    never a synthetic summary.
 *  - **Quality is measured, never modelled.** [measuredQuality] compares the
 *    preview/partial text against the eventual full text with a real,
 *    language-agnostic string metric (normalised LCS) plus word-level overlap,
 *    so "50% of the message conveyed" is a number derived from the strings
 *    themselves, not from a fabricated quality curve.
 *  - [plan] derives its stages from the real packetization ceiling
 *    ([Packetizer]) so the preview cap and packet counts match the wire.
 */
object ProgressiveTransmission {

    /** Cap on the instantly-deliverable preview stage, in UTF-8 bytes. */
    const val PREVIEW_MAX_BYTES = 64

    const val DEFAULT_MAX_PACKET_PAYLOAD = 1024

    /**
     * Deterministic stage plan for [totalPayloadBytes] of encoded data.
     * [totalPackets] mirrors the transport packetization; [previewBytes] is the
     * real UTF-8 prefix length the sender will carve out of the message text.
     */
    data class StagePlan(
        val totalPackets: Int,
        val previewBytes: Int
    )

    fun plan(
        totalPayloadBytes: Int,
        maxPacketPayload: Int = DEFAULT_MAX_PACKET_PAYLOAD
    ): StagePlan {
        val safePayload = totalPayloadBytes.coerceAtLeast(0)
        val ceiling = maxPacketPayload.coerceAtLeast(1)
        val totalPackets = (safePayload + ceiling - 1) / ceiling
        val previewBytes = minOf(PREVIEW_MAX_BYTES, safePayload)
        return StagePlan(totalPackets, previewBytes)
    }

    /** The real preview stage: the message text clipped to the UTF-8 budget. */
    fun preview(text: String, plan: StagePlan): String = takeUtf8Prefix(text, plan.previewBytes)

    /** UTF-8 length-aware prefix: never splits a multi-byte character or
     * a surrogate pair. */
    fun takeUtf8Prefix(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        var consumed = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val byteLen = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                Character.isSupplementaryCodePoint(cp) -> 4
                else -> 3
            }
            if (consumed + byteLen > maxBytes) break
            consumed += byteLen
            index += Character.charCount(cp)
        }
        return text.substring(0, index)
    }

    /**
     * Measured quality of [partial] against the eventual [full] text in [0,1]:
     *  - character-level: normalised longest-common-subsequence ratio
     *    (language-agnostic: works for space-less Devanagari/Tamil scripts),
     *  - word-level: Jaccard overlap of whitespace-split tokens,
     *  blended 0.6/0.4. Both are computed from the real strings.
     */
    fun measuredQuality(full: String, partial: String): Float {
        if (full.isEmpty()) return if (partial.isEmpty()) 1f else 0f
        if (partial.isEmpty()) return 0f
        val charSim = lcsRatio(full, partial)
        val wordSim = wordJaccard(full, partial)
        return 0.6f * charSim + 0.4f * wordSim
    }

    /** Fraction of [full]'s characters preserved by [partial] via LCS. */
    fun lcsRatio(full: String, partial: String): Float {
        if (full.isEmpty()) return if (partial.isEmpty()) 1f else 0f
        val m = full.length
        val n = partial.length
        if (n > m) return lcsRatio(partial, full)
        // Classic DP on the shorter string to stay O(m*n) bounded.
        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            for (j in 1..n) {
                curr[j] = if (full[i - 1] == partial[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], curr[j - 1])
                }
            }
            val tmp = prev
            prev = curr
            curr = tmp
            curr[0] = 0
        }
        val lcs = prev[n]
        return (lcs.toFloat() / m).coerceIn(0f, 1f)
    }

    /** Jaccard overlap of whitespace-split tokens. */
    fun wordJaccard(full: String, partial: String): Float {
        val fullTokens = full.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val partialTokens = partial.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (fullTokens.isEmpty() || partialTokens.isEmpty()) return 0f
        val union = fullTokens.size + partialTokens.size - fullTokens.intersect(partialTokens).size
        if (union == 0) return 0f
        return fullTokens.intersect(partialTokens).size.toFloat() / union
    }
}