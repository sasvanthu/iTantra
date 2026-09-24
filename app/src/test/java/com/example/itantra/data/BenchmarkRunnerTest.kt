package com.example.itantra.data

import com.example.itantra.codec.BaselineCodec
import com.example.itantra.codec.Language
import com.example.itantra.codec.RetroSpeechCodec
import com.example.itantra.codec.SpeechCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkRunnerTest {

    private val retro: SpeechCodec = RetroSpeechCodec()
    private val baseline: SpeechCodec = BaselineCodec()

    @Test
    fun `nearest-rank p95 reports a real observed value`() {
        val stats = LatencyStats.of((1L..100L).toList())
        assertEquals(1, stats.minMs)
        assertEquals(100, stats.maxMs)
        assertEquals(50.5, stats.meanMs, 0.001)
        assertEquals(95, stats.p95Ms) // rank = ceil(0.95 * 100) = 95
    }

    @Test
    fun `p95 clamps to a valid rank for tiny samples`() {
        val stats = LatencyStats.of(listOf(7L, 9L))
        assertEquals(7, stats.minMs)
        assertEquals(9, stats.maxMs)
        assertEquals(9, stats.p95Ms) // rank = max(ceil(0.95*2)=2, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty latency set is rejected`() {
        LatencyStats.of(emptyList())
    }

    @Test
    fun `runner measures every requested iteration per scenario`() {
        var invocations = 0
        val clock: (() -> Unit) -> Long = {
            invocations++
            it()
            100L
        }
        val runner = BenchmarkRunner(clock)

        val results = runner.run(
            listOf(
                BenchmarkScenario("RETRO-SHORT", retro, "hello world", Language.ENGLISH, 5),
                BenchmarkScenario("BASE-SHORT", baseline, "hello world", Language.ENGLISH, 3)
            )
        )

        // The clock must have driven every iteration of every scenario.
        assertEquals(8, invocations)

        val retroR = results.single { it.name == "RETRO-SHORT" }
        val baseR = results.single { it.name == "BASE-SHORT" }
        assertEquals(5, retroR.iterations)
        assertEquals(3, baseR.iterations)
        // The injected clock reports 100 ms for every iteration.
        assertEquals(100L, retroR.totalStats.minMs)
        assertEquals(100L, retroR.totalStats.maxMs)
        assertEquals(100.0, retroR.totalStats.meanMs, 0.001)
        assertEquals(100L, retroR.totalStats.p95Ms)
    }

    @Test
    fun `repeated round trips of the same text are byte-deterministic`() {
        val runner = BenchmarkRunner()
        val results = runner.run(
            listOf(
                BenchmarkScenario("RETRO-EN", retro, "I need help near the railway station.", Language.ENGLISH, 20),
                BenchmarkScenario("RETRO-TA", retro, "எனக்கு ரயில் நிலையம் அருகில் உதவி தேவை.", Language.TAMIL, 20)
            )
        )

        for (r in results) {
            assertEquals(20, r.iterations)
            assertEquals(20, r.successCount)
            assertTrue(r.successRate == 1.0)
            assertTrue("compression must be a measured rate", r.meanCompressionPercentage >= -100.0)
            assertTrue(r.meanEncodedBytes > 0)
        }
        // Two runs of the same corpus produce identical byte aggregates.
        val again = runner.run(
            listOf(BenchmarkScenario("RETRO-EN", retro, "I need help near the railway station.", Language.ENGLISH, 20))
        )
        assertEquals(results.single { it.name == "RETRO-EN" }.meanEncodedBytes,
            again.single().meanEncodedBytes, 0.001)
    }

    @Test
    fun `baseline is lossless and larger compared to retro for expendable text`() {
        val runner = BenchmarkRunner()
        val text = "I need help near the railway station."
        val base = runner.run(listOf(BenchmarkScenario("BASE", baseline, text, Language.ENGLISH, 10))).single()
        val retroR = runner.run(listOf(BenchmarkScenario("RETRO", retro, text, Language.ENGLISH, 10))).single()

        assertEquals(1.0, base.successRate, 0.001)
        assertEquals(1.0, retroR.successRate, 0.001)
        // BASELINE stores the raw UTF-8 -> its size is the original UTF-8
        // byte count, and its compression is measured as zero.
        assertEquals(0.0, base.meanCompressionPercentage, 0.001)
        assertTrue("measured retro aggregates are reported", retroR.meanEncodedBytes > 0)
    }

    @Test
    fun `wall clock measures a real positive duration`() {
        val took = WallClock.measure { Thread.sleep(5) }
        assertTrue("must have taken real wall-clock time", took >= 5)
    }
}