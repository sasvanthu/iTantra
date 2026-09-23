package com.example.itantra.codec

class Predictor {

    private val contextWindow = mutableListOf<Int>()
    private val bigramCounts = mutableMapOf<Pair<Int, Int>, Int>()
    private val trigramCounts = mutableMapOf<Triple<Int, Int, Int>, Int>()
    private val unigramCounts = mutableMapOf<Int, Int>()
    private val maxContextSize = 3

    fun updateContext(tokenId: Int) {
        contextWindow.add(tokenId)
        if (contextWindow.size > maxContextSize) {
            contextWindow.removeAt(0)
        }
        updateCounts()
    }

    private fun updateCounts() {
        val tokens = contextWindow
        if (tokens.size >= 1) {
            val unigram = tokens.last()
            unigramCounts[unigram] = (unigramCounts[unigram] ?: 0) + 1
        }
        if (tokens.size >= 2) {
            val bigram = Pair(tokens[tokens.size - 2], tokens.last())
            bigramCounts[bigram] = (bigramCounts[bigram] ?: 0) + 1
        }
        if (tokens.size >= 3) {
            val trigram = Triple(tokens[tokens.size - 3], tokens[tokens.size - 2], tokens.last())
            trigramCounts[trigram] = (trigramCounts[trigram] ?: 0) + 1
        }
    }

    fun predict(): List<Pair<Int, Float>> {
        if (contextWindow.isEmpty()) return emptyList()

        val predictions = mutableMapOf<Int, Float>()

        // Trigram prediction (highest priority)
        if (contextWindow.size >= 2) {
            val context = Triple(
                contextWindow[contextWindow.size - 2],
                contextWindow.last(),
                0
            )
            val matchingTrigrams = trigramCounts.filter { it.key.first == context.first && it.key.second == context.second }
            val total = matchingTrigrams.values.sum().toFloat()
            if (total > 0) {
                matchingTrigrams.forEach { (key, count) ->
                    predictions[key.third] = (predictions[key.third] ?: 0f) + (count / total) * 0.6f
                }
            }
        }

        // Bigram prediction
        if (contextWindow.isNotEmpty()) {
            val lastToken = contextWindow.last()
            val matchingBigrams = bigramCounts.filter { it.key.first == lastToken }
            val total = matchingBigrams.values.sum().toFloat()
            if (total > 0) {
                matchingBigrams.forEach { (key, count) ->
                    predictions[key.second] = (predictions[key.second] ?: 0f) + (count / total) * 0.3f
                }
            }
        }

        // Unigram fallback
        val totalUnigrams = unigramCounts.values.sum().toFloat()
        if (totalUnigrams > 0) {
            unigramCounts.forEach { (token, count) ->
                predictions[token] = (predictions[token] ?: 0f) + (count / totalUnigrams) * 0.1f
            }
        }

        return predictions.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }
    }

    fun getContext(): List<Int> = contextWindow.toList()

    fun clear() {
        contextWindow.clear()
    }

    fun getPredictionConfidence(tokenId: Int): Float {
        val predictions = predict()
        return predictions.find { it.first == tokenId }?.second ?: 0f
    }

    fun isPredictable(tokenId: Int): Boolean {
        return getPredictionConfidence(tokenId) > 0.3f
    }

    fun getCompressionSavings(tokenCount: Int): Float {
        if (tokenCount == 0) return 0f
        var predictedCount = 0
        for (tokenId in contextWindow) {
            if (isPredictable(tokenId)) predictedCount++
        }
        return predictedCount.toFloat() / tokenCount
    }
}
