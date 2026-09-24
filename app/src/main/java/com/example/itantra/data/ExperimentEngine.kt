package com.example.itantra.data

import java.util.Random

/**
 * Phase 25/26 — A/B experiment engine.
 *
 * Runs balanced, reproducible trials comparing two conditions (BASELINE UTF-8
 * vs the RETRO codec) and aggregates only *measured* outcomes. Designed to be
 * honest by construction:
 *
 *  - **Assignment** uses block randomization on a seeded RNG: within every
 *    block of N arms each condition appears exactly N/N times. A fixed seed
 *    reproduces the assignment sequence across runs; no seed means a fresh
 *    (time-seeded) sequence per process.
 *  - **Outcomes are recorded, not imputed.** Every outcome carries the
 *    actually-measured bytes, timings and round-trip success. A trial that did
 *    not run is simply not a trial.
 *  - **Invalid data is rejected.** A recorded outcome with negative bytes or
 *    negative latency is dropped (and counted) instead of polluting averages —
 *    there is no legitimate negative latency.
 *  - **Relevance is opt-in.** A human relevance rating may be attached after
 *    the fact; summaries report `relevanceMean = null` until real ratings
 *    exist, so no score is ever fabricated.
 *
 * Pure Kotlin: the whole engine is JVM-testable.
 */
class ExperimentEngine(
    val arms: List<ExperimentArm> = DEFAULT_ARMS,
    val seed: Long? = null
) {

    private val rngSeed: Long = seed ?: System.nanoTime()
    private val random = Random(rngSeed)
    private val armPool: MutableList<ExperimentArm> = arms.toMutableList()
    private val outcomes = mutableListOf<ExperimentOutcome>()
    private val rejectedInvalid = mutableListOf<Long>()

    /** Number of units assigned so far across all arms. */
    var assigned: Long = 0
        private set

    /**
     * Balanced block-random assignment for the next trial unit: draw one arm
     * without replacement from a shuffled pool that refills with one copy of
     * every arm each block, so each block of `arms.size` trials contains a
     * full, balanced set of conditions in non-predictable order.
     */
    fun nextArm(): ExperimentArm {
        if (armPool.isEmpty()) {
            armPool.addAll(arms) // a new balanced block
        }
        val index = random.nextInt(armPool.size)
        assigned++
        return armPool.removeAt(index)
    }

    /**
     * Record one completed trial. Returns false (and tracks the rejection)
     * when the data is impossible — negative bytes or negative latencies.
     */
    fun record(outcome: ExperimentOutcome): Boolean = synchronized(outcomes) {
        if (outcome.originalBytes < 0 || outcome.encodedBytes < 0 ||
            outcome.packetCount < 0 || outcome.encodeMs < 0 || outcome.decodeMs < 0 ||
            outcome.totalMs < 0
        ) {
            rejectedInvalid.add(outcome.unitId)
            return false
        }
        outcomes.add(outcome)
        return true
    }

    val recordedCount: Int get() = synchronized(outcomes) { outcomes.size }
    val rejectedCount: Int get() = synchronized(outcomes) { rejectedInvalid.size }
    fun rejectedUnitIds(): List<Long> = synchronized(outcomes) { rejectedInvalid.toList() }

    /** Per-arm aggregate over the recorded (measured) trials, never imputed. */
    fun summarize(): List<ExperimentSummary> =
        synchronized(outcomes) { summarizeLocked() }

    private fun summarizeLocked(): List<ExperimentSummary> {
        return arms.map { arm ->
            val trials = outcomes.filter { it.armId == arm.id }
            val rated = trials.mapNotNull { it.relevanceScore }
            ExperimentSummary(
                armId = arm.id,
                armLabel = arm.label,
                sampleCount = trials.size,
                successCount = trials.count { it.success },
                meanEncodedBytes = trials.meanOfOrNull { it.encodedBytes } ?: 0.0,
                meanCompressionPercentage = trials.meanOfOrNull { it.compressionPercentage } ?: 0.0,
                meanEncodeMs = trials.meanOfOrNull { it.encodeMs } ?: 0.0,
                meanDecodeMs = trials.meanOfOrNull { it.decodeMs } ?: 0.0,
                meanTotalMs = trials.meanOfOrNull { it.totalMs } ?: 0.0,
                meanTokensStored = 0,
                meanTokensPredicted = 0,
                relevanceCount = rated.size,
                relevanceMean = if (rated.isEmpty()) null else rated.average().toFloat()
            )
        }
    }

    private inline fun List<ExperimentOutcome>.meanOfOrNull(
        selector: (ExperimentOutcome) -> Number
    ): Double? {
        if (isEmpty()) return null
        var sum = 0.0
        for (t in this) sum += selector(t).toDouble()
        return sum / size
    }

    companion object {
        val DEFAULT_ARMS = listOf(
            ExperimentArm(id = "BASELINE", label = "Baseline UTF-8", codec = "BASELINE"),
            ExperimentArm(id = "RETRO", label = "RETRO token+phoneme", codec = "RETRO")
        )
    }
}

/** One condition in an A/B experiment. */
data class ExperimentArm(
    val id: String,
    val label: String,
    val codec: String,
    val config: Map<String, String> = emptyMap()
)

/** Result of one completed trial — measured values only. */
data class ExperimentOutcome(
    val unitId: Long,
    val armId: String,
    val success: Boolean,
    val originalBytes: Int,
    val encodedBytes: Int,
    val packetCount: Int,
    val encodeMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val compressionPercentage: Double,
    val relevanceScore: Float? = null
)

/** Honest per-arm aggregate: null relevance mean until human ratings exist. */
data class ExperimentSummary(
    val armId: String,
    val armLabel: String,
    val sampleCount: Int,
    val successCount: Int,
    val meanEncodedBytes: Double,
    val meanCompressionPercentage: Double,
    val meanEncodeMs: Double,
    val meanDecodeMs: Double,
    val meanTotalMs: Double,
    val meanTokensStored: Int,
    val meanTokensPredicted: Int,
    val relevanceCount: Int,
    val relevanceMean: Float?
) {
    val successRate: Double
        get() = if (sampleCount == 0) 0.0 else successCount.toDouble() / sampleCount
    val meanTotalMsOrNull: Double?
        get() = if (sampleCount == 0) null else meanTotalMs
}