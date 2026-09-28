package com.example.itantra.speech.stt

import com.example.itantra.codec.Language

/**
 * Individual token with confidence and stability flag.
 */
data class AsrToken(
    val text: String,
    val confidence: Float,
    val isStable: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Result produced by a [StreamingAsrEngine].
 */
sealed class AsrResult {
    /**
     * Interim result while speech is ongoing.
     * [stablePrefix] contains only tokens verified as stable across multiple acoustic windows.
     */
    data class Partial(
        val hypothesis: String,
        val stablePrefix: String,
        val tokens: List<AsrToken>,
        val language: Language
    ) : AsrResult()

    /**
     * Final committed transcript for a completed utterance.
     */
    data class Final(
        val text: String,
        val tokens: List<AsrToken>,
        val confidence: Float,
        val language: Language
    ) : AsrResult()

    /**
     * Error condition during recognition.
     */
    data class Error(val message: String, val throwable: Throwable? = null) : AsrResult()
}

/**
 * Streaming speech-to-text recognition interface.
 * Consumes raw PCM frames incrementally and emits partial and final hypotheses with confidence metrics.
 */
interface StreamingAsrEngine {
    fun start()
    fun acceptAudio(frame: ShortArray, length: Int = frame.size)
    fun pollResult(): AsrResult?
    fun stop()
    fun release()
}

/**
 * Tracks stability of hypothesis prefixes across consecutive partial ASR updates.
 *
 * Prevents transmitting unstable, transient tokens across the wireless mesh:
 * A token is committed to the [stablePrefix] only when it has remained identical
 * for [stabilityThreshold] consecutive partial updates.
 */
class StablePrefixTracker(
    private val stabilityThreshold: Int = 2
) {
    private val tokenHistory = mutableMapOf<Int, String>()
    private val tokenStabilityCounts = mutableMapOf<Int, Int>()
    private var committedStableTokens = mutableListOf<String>()

    fun update(hypothesis: String): Pair<String, List<AsrToken>> {
        val rawTokens = hypothesis.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val asrTokens = mutableListOf<AsrToken>()
        val currentStable = mutableListOf<String>()

        rawTokens.forEachIndexed { index, token ->
            val prevToken = tokenHistory[index]
            if (prevToken == token) {
                val currentCount = (tokenStabilityCounts[index] ?: 0) + 1
                tokenStabilityCounts[index] = currentCount

                val isStable = currentCount >= stabilityThreshold
                if (isStable) {
                    currentStable.add(token)
                }
                asrTokens.add(AsrToken(token, confidence = if (isStable) 0.95f else 0.65f, isStable = isStable))
            } else {
                tokenHistory[index] = token
                tokenStabilityCounts[index] = 1
                asrTokens.add(AsrToken(token, confidence = 0.50f, isStable = false))
            }
        }

        // Clean up history for indexes beyond current hypothesis length
        val currentSize = rawTokens.size
        tokenHistory.keys.filter { it >= currentSize }.forEach {
            tokenHistory.remove(it)
            tokenStabilityCounts.remove(it)
        }

        committedStableTokens = currentStable
        return currentStable.joinToString(" ") to asrTokens
    }

    fun getCommittedPrefix(): String = committedStableTokens.joinToString(" ")

    fun reset() {
        tokenHistory.clear()
        tokenStabilityCounts.clear()
        committedStableTokens.clear()
    }
}
