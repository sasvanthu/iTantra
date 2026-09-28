package com.example.itantra.speech.stt

import com.example.itantra.codec.Language

/**
 * Token-level flags that downstream compression must never silently drop.
 * Negations, quantities, names, locations and safety-critical entities are
 * preserved even under aggressive compression.
 */
object AsrTokenFlags {
    const val FLAG_NONE: Int = 0x00
    /** Low-confidence token; must not be transmitted as final truth. */
    const val FLAG_UNCERTAIN: Int = 0x01
    /** Safety-critical (e.g. emergency content); never dropped by compression. */
    const val FLAG_CRITICAL: Int = 0x02
    /** Proper noun / named entity; never dropped by compression. */
    const val FLAG_ENTITY: Int = 0x04
    /** Semantic negation ("no", "not", "उसे", "இல்லை"); must never be dropped. */
    const val FLAG_NEGATION: Int = 0x08
    /** Quantity / numeral; must never be dropped by compression. */
    const val FLAG_NUMBER: Int = 0x10
    /** Location / place name; must never be dropped by compression. */
    const val FLAG_LOCATION: Int = 0x20

    fun fromConfidence(confidence: Float): Int =
        if (confidence < 0.70f) FLAG_UNCERTAIN else FLAG_NONE
}

/**
 * Individual token with confidence, stability and preservation flags.
 */
data class AsrToken(
    val text: String,
    val confidence: Float,
    val isStable: Boolean,
    val flags: Int = AsrTokenFlags.FLAG_NONE,
    val alternatives: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isUncertainFlagged: Boolean get() = (flags and AsrTokenFlags.FLAG_UNCERTAIN) != 0
    val isCriticalFlagged: Boolean get() = (flags and AsrTokenFlags.FLAG_CRITICAL) != 0
    val isEntityFlagged: Boolean get() = (flags and AsrTokenFlags.FLAG_ENTITY) != 0
    val isNegationFlagged: Boolean get() = (flags and AsrTokenFlags.FLAG_NEGATION) != 0
    val isNumberFlagged: Boolean get() = (flags and AsrTokenFlags.FLAG_NUMBER) != 0
    val isLocationFlagged: Boolean get() = (flags and AsrTokenFlags.FLAG_LOCATION) != 0
}

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
                val confidence = if (isStable) 0.95f else 0.65f
                asrTokens.add(
                    AsrToken(
                        token,
                        confidence = confidence,
                        isStable = isStable,
                        flags = AsrTokenFlags.fromConfidence(confidence)
                    )
                )
            } else {
                tokenHistory[index] = token
                tokenStabilityCounts[index] = 1
                asrTokens.add(
                    AsrToken(
                        token,
                        confidence = 0.50f,
                        isStable = false,
                        flags = AsrTokenFlags.FLAG_UNCERTAIN
                    )
                )
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
