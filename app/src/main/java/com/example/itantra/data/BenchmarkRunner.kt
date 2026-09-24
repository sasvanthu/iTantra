package com.example.itantra.data

import com.example.itantra.codec.CodecLabResult
import com.example.itantra.codec.Language
import com.example.itantra.codec.SpeechCodec

/**
 * Wall-clock measurement helper (Phase 27/36).
 *
 * Durations come from the JVM nanosecond timer — the only honest source for a
 * JVM process. No warm-up is discarded and no synthetic latency is injected:
 * every number is what the code path actually took on this machine.
 */
object WallClock {
    /** Runs [block] and returns its wall-clock duration in milliseconds. */
    fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000L
    }
}

/**
 * Descriptive statistics over a set of measured latencies. The percentile is
 * computed with the nearest-rank method (ceil(p * n)), which reports back a
 * real observed value rather than an interpolation.
 */
data class LatencyStats(
    val minMs: Long,
    val maxMs: Long,
    val meanMs: Double,
    val p95Ms: Long
) {
    companion object {
        fun of(latenciesMs: List<Long>): LatencyStats {
            require(latenciesMs.isNotEmpty()) { "cannot summarise an empty latency set" }
            val sorted = latenciesMs.sorted()
            val min = sorted.first()
            val max = sorted.last()
            val mean = sorted.map { it.toDouble() }.average()
            val rank = ((0.95 * sorted.size).toDouble().let(::ceilInt)).coerceAtLeast(1)
            val p95 = sorted[rank - 1]
            return LatencyStats(minMs = min, maxMs = max, meanMs = mean, p95Ms = p95)
        }
    }
}

/** One repeatable encode+decode round-trip to benchmark. */
data class BenchmarkScenario(
    val name: String,
    val codec: SpeechCodec,
    val text: String,
    val language: Language,
    val iterations: Int
)

/** One measured round-trip: real timings and sizes, with its success flag. */
data class BenchmarkIteration(
    val success: Boolean,
    val encodeMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val encodedBytes: Int,
    val packetCount: Int,
    val compressionPercentage: Double,
    val tokenEncodedBytes: Int,
    val phonemeEncodedBytes: Int
)

/**
 * Per-scenario honest aggregate. Failed round-trips stay counted (they still
 * consumed real time) but are surfaced via [successCount]; nothing is imputed.
 */
data class BenchmarkResult(
    val name: String,
    val iterations: Int,
    val successCount: Int,
    val encodeStats: LatencyStats,
    val decodeStats: LatencyStats,
    val totalStats: LatencyStats,
    val meanEncodedBytes: Double,
    val meanCompressionPercentage: Double,
    val meanPacketCount: Double
) {
    val successRate: Double
        get() = if (iterations == 0) 0.0 else successCount.toDouble() / iterations
}

/**
 * Phase 27 benchmark harness over the real codec implementations.
 *
 * Every iteration is measured end-to-end with [WallClock]; failed iterations
 * are included (their time was real) and reported via [BenchmarkResult].
 * The harness is pure JVM and deterministic given the same corpus, so the
 * numbers it produces are reproducible on honest hardware.
 */
class BenchmarkRunner(
    private val clock: (() -> Unit) -> Long = { WallClock.measure(it) }
) {

    fun run(scenarios: List<BenchmarkScenario>): List<BenchmarkResult> {
        return scenarios.map { scenario ->
            val iterations = scenario.iterations.coerceAtLeast(1)
            val results = mutableListOf<BenchmarkIteration>()
            val encodeTimes = mutableListOf<Long>()
            val decodeTimes = mutableListOf<Long>()
            val totalTimes = mutableListOf<Long>()

            repeat(iterations) {
                var lab: CodecLabResult? = null
                val elapsed = clock { lab = scenario.codec.performLab(scenario.text, scenario.language) }
                val result = requireNotNull(lab) { "clock block must run the measured round-trip" }
                results.add(
                    BenchmarkIteration(
                        success = result.exactMatch,
                        encodeMs = result.encodeMs.coerceAtLeast(0),
                        decodeMs = result.decodeMs.coerceAtLeast(0),
                        totalMs = elapsed,
                        encodedBytes = result.encodedBytes,
                        packetCount = result.packetCount,
                        compressionPercentage = result.compressionPercent,
                        tokenEncodedBytes = result.tokenEncodedBytes,
                        phonemeEncodedBytes = result.phonemeEncodedBytes
                    )
                )
                encodeTimes.add(result.encodeMs.coerceAtLeast(0))
                decodeTimes.add(result.decodeMs.coerceAtLeast(0))
                totalTimes.add(elapsed)
            }

            BenchmarkResult(
                name = scenario.name,
                iterations = iterations,
                successCount = results.count { it.success },
                encodeStats = LatencyStats.of(encodeTimes),
                decodeStats = LatencyStats.of(decodeTimes),
                totalStats = LatencyStats.of(totalTimes),
                meanEncodedBytes = results.map { it.encodedBytes.toDouble() }.average(),
                meanCompressionPercentage = results.map { it.compressionPercentage }.average(),
                meanPacketCount = results.map { it.packetCount.toDouble() }.average()
            )
        }
    }
}

private fun ceilInt(value: Double): Int = kotlin.math.ceil(value).toInt()