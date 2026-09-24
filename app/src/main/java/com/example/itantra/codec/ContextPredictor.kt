package com.example.itantra.codec

/**
 * Lightweight frequency-based next-token predictor.
 *
 * Prediction is computed ONLY from the current message's token stream
 * (previous-token and previous-two-token frequency tables, plus a unigram
 * fallback). Because both the encoder and the decoder rebuild the exact same
 * tables while processing the same message, predictions are deterministic on
 * both sides and can never corrupt the text: a token is marked PREDICTED
 * only when the predictor's top choice equals the actual dictionary id, so
 * the decoder can reconstruct it from its own predictor state.
 *
 * Cross-message learned models are intentionally NOT used yet: they require
 * synchronized session state between sender and receiver, which arrives with
 * the two-phone transport phase. This guarantees correctness first.
 */
class ContextPredictor(
    private val threshold: Float = 0.05f
) {

    companion object {
        const val OOV_ID: Int = -1
        private const val MAX_CONTEXT = 8

        private const val TRIGRAM_WEIGHT = 0.6f
        private const val BIGRAM_WEIGHT = 0.3f
        private const val UNIGRAM_WEIGHT = 0.1f
    }

    private val window = mutableListOf<Int>()
    private val unigramCounts = mutableMapOf<Int, Int>()
    private val bigramCounts = mutableMapOf<Pair<Int, Int>, Int>()
    private val trigramCounts = mutableMapOf<Triple<Int, Int, Int>, Int>()

    /** Resets window + frequency tables. Call at the start of every message. */
    fun beginMessage() {
        window.clear()
        unigramCounts.clear()
        bigramCounts.clear()
        trigramCounts.clear()
    }

    /**
     * Records a token id in context. Use [OOV_ID] for escape / punctuation
     * tokens so both sides keep identical state.
     */
    fun note(id: Int) {
        window.add(id)
        if (window.size > MAX_CONTEXT) window.removeAt(0)

        unigramCounts[id] = (unigramCounts[id] ?: 0) + 1

        if (window.size >= 2) {
            val b = Pair(window[window.size - 2], window.last())
            bigramCounts[b] = (bigramCounts[b] ?: 0) + 1
        }
        if (window.size >= 3) {
            val t = Triple(window[window.size - 3], window[window.size - 2], window.last())
            trigramCounts[t] = (trigramCounts[t] ?: 0) + 1
        }
    }

    /**
     * Returns the most probable next word id, or [OOV_ID] when there is no
     * confident prediction. Real dictionary ids are >= 0, so callers can
     * safely compare against [OOV_ID].
     */
    fun nextPrediction(): Int {
        if (window.isEmpty()) return OOV_ID

        val scores = mutableMapOf<Int, Float>()

        if (window.size >= 2) {
            val a = window[window.size - 2]
            val b = window.last()
            val total = trigramCounts.filter { it.key.first == a && it.key.second == b }.values.sum()
            if (total > 0) {
                for ((k, v) in trigramCounts) {
                    if (k.first == a && k.second == b) {
                        scores[k.third] = (scores[k.third] ?: 0f) + (v.toFloat() / total) * TRIGRAM_WEIGHT
                    }
                }
            }
        }

        if (window.isNotEmpty()) {
            val last = window.last()
            val total = bigramCounts.filter { it.key.first == last }.values.sum()
            if (total > 0) {
                for ((k, v) in bigramCounts) {
                    if (k.first == last) {
                        scores[k.second] = (scores[k.second] ?: 0f) + (v.toFloat() / total) * BIGRAM_WEIGHT
                    }
                }
            }
        }

        val unigramTotal = unigramCounts.values.sum()
        if (unigramTotal > 0) {
            for ((k, v) in unigramCounts) {
                scores[k] = (scores[k] ?: 0f) + (v.toFloat() / unigramTotal) * UNIGRAM_WEIGHT
            }
        }

        val best = scores.maxByOrNull { it.value } ?: return OOV_ID
        return if (best.value >= threshold) best.key else OOV_ID
    }

    fun confidenceFor(id: Int): Float {
        val windowState = window.toList()
        val prediction = nextPrediction()
        return if (prediction == id) {
            val scores = scoreBoard()
            scores[id] ?: 0f
        } else 0f
    }

    /** True when the predictor is confident the next token is [id]. */
    fun wouldPredict(id: Int): Boolean = nextPrediction() == id

    private fun scoreBoard(): Map<Int, Float> {
        // Re-compute scores for the current window (mirrors nextPrediction).
        val scores = mutableMapOf<Int, Float>()
        if (window.isNotEmpty()) {
            if (window.size >= 2) {
                val a = window[window.size - 2]
                val b = window.last()
                val total = trigramCounts.filter { it.key.first == a && it.key.second == b }.values.sum()
                if (total > 0) {
                    for ((k, v) in trigramCounts) {
                        if (k.first == a && k.second == b) {
                            scores[k.third] = (scores[k.third] ?: 0f) + (v.toFloat() / total) * TRIGRAM_WEIGHT
                        }
                    }
                }
            }
            val last = window.last()
            val total = bigramCounts.filter { it.key.first == last }.values.sum()
            if (total > 0) {
                for ((k, v) in bigramCounts) {
                    if (k.first == last) {
                        scores[k.second] = (scores[k.second] ?: 0f) + (v.toFloat() / total) * BIGRAM_WEIGHT
                    }
                }
            }
            val unigramTotal = unigramCounts.values.sum()
            if (unigramTotal > 0) {
                for ((k, v) in unigramCounts) {
                    scores[k] = (scores[k] ?: 0f) + (v.toFloat() / unigramTotal) * UNIGRAM_WEIGHT
                }
            }
        }
        return scores
    }

    fun getContextWindow(): List<Int> = window.toList()

    fun clear() = beginMessage()
}