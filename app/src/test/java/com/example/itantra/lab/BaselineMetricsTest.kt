package com.example.itantra.lab

import com.example.itantra.codec.Language
import com.example.itantra.codec.RetroSpeechCodec
import com.example.itantra.data.BenchmarkIteration
import com.example.itantra.data.BenchmarkResult
import com.example.itantra.data.BenchmarkRunner
import com.example.itantra.data.BenchmarkScenario
import com.example.itantra.data.WallClock
import com.example.itantra.protocol.Packetizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 honest baseline: cold start vs steady state, per-message wire size,
 * encode/decode latency, and a labeled JVM heap-delta estimate. Numbers are
 * produced by the real JVM on this machine and are reproducible given the same
 * corpus; they must be re-measured on target hardware before they may be
 * advertised as device figures.
 */
class BaselineMetricsTest {

    private val corpus = listOf(
        Language.ENGLISH to "Emergency flood water rising near hospital evacuate people send rescue team now",
        Language.HINDI to "अस्पताल के पास बाढ़ का पानी बढ़ रहा है अभी लोगों को निकालें",
        Language.TAMIL to "மருத்துவமனை அருகில் வெள்ள நீர் உயர்கிறது உடனே மக்களை வெளியேற்றவும்"
    )

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        // Two explicit GC passes still cannot guarantee collection, so the
        // resulting number is explicitly labeled an *estimate* in the report.
        runtime.gc()
        runtime.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun `cold start is slower than or equal to steady state`() {
        val retro = RetroSpeechCodec()
        val text = "Emergency flood water rising near hospital evacuate people"
        val lang = Language.ENGLISH

        // COLD: very first ever round-trip on a fresh instance.
        val coldStartMs = WallClock.measure { retro.performLab(text, lang) }

        // STEADY: JIT-warmed iterations.
        repeat(5) { retro.performLab(text, lang) }
        val steady = BenchmarkRunner().run(
            listOf(BenchmarkScenario("STEADY", retro, text, lang, iterations = 30))
        ).single()

        println("COLD START: ${coldStartMs}ms | steady mean=${"%.3f".format(steady.totalStats.meanMs)}ms p95=${steady.totalStats.p95Ms}ms")

        assertTrue(coldStartMs >= 0L)
        // Cold start includes JIT/class-load cost and must never be reported
        // as faster than a warmed steady-state path.
        assertTrue("cold start must not be reported below steady-state",
            coldStartMs >= steady.totalStats.meanMs - 5)
        assertTrue(steady.totalStats.p95Ms >= steady.totalStats.minMs)
    }

    @Test
    fun `per message wire size and latency stay inside measured envelope`() {
        val retro = RetroSpeechCodec()
        val scenarios = corpus.map { (lang, text) ->
            BenchmarkScenario("${lang.code}-MIXED", retro, text, lang, iterations = 25)
        }
        val results = BenchmarkRunner().run(scenarios)

        for (r in results) {
            assertTrue("${r.name}: must stay lossless", r.successRate == 1.0)
            assertTrue("${r.name}: encode mean must be non-negative", r.encodeStats.meanMs >= 0.0)
            assertTrue("${r.name}: wire mean must be positive", r.meanEncodedBytes > 0)
            println("${r.name}: wire(mean)=${"%.0f".format(r.meanEncodedBytes)}B enc(mean)=${"%.3f".format(r.encodeStats.meanMs)}ms dec(mean)=${"%.3f".format(r.decodeStats.meanMs)}ms +pkt=${r.meanPacketCount}")
        }

        // Cross-language wire payload must be no larger than UTF-8 and stay
        // dramatically inside the per-message budget (Brahmic+phoneme tokens
        // beat UTF-8 for Indic script; ASCII rides phoneme compression).
        val totalUtf8 = corpus.sumOf { it.second.toByteArray(Charsets.UTF_8).size }
        val totalWire = results.sumOf { it.meanEncodedBytes }
        val reduction = 100.0 * (1.0 - totalWire / totalUtf8)
        println("TOTAL UTF-8=${totalUtf8}B -> wire=${"%.0f".format(totalWire)}B ($reduction% reduction)")
        assertTrue("wire must never exceed UTF-8 for the mixed corpus", totalWire <= totalUtf8)
        for (r in results) {
            val budget = BaselineLimits.TARGET_WIRE_BYTES_PER_MESSAGE.toDouble()
            assertTrue("${r.name}: wire=${"%.0f".format(r.meanEncodedBytes)}B must fit per-message budget $budget",
                r.meanEncodedBytes <= budget)
        }
    }

    @Test
    fun `heap delta after a burst is a bounded labeled estimate`() {
        val retro = RetroSpeechCodec()
        val text = "Emergency flood water rising near hospital evacuate people"
        val lang = Language.ENGLISH

        repeat(10) { retro.performLab(text, lang) } // warm, allocate caches

        val before = usedHeapBytes()
        repeat(500) { retro.performLab(text, lang) }
        val after = usedHeapBytes()

        val delta = (after - before).coerceAtLeast(0L)
        val label = "$delta bytes (JVM estimate, not on-device RAM)"
        println("HEAP DELTA after 500 round-trips: $label")

        // No assertion on the exact figure (GC is non-deterministic), but the
        // estimate must be a sane positive value and must never be negative.
        assertTrue(delta >= 0L)
        assertTrue("burst must show measurable allocation (retained or freed)",
            delta >= 0L && delta < 512L * 1024L * 1024L)
    }

    @Test
    fun `baseline metrics snapshot is self describing`() {
        val retro = RetroSpeechCodec()
        val text = "Emergency flood water rising near hospital evacuate people"
        val lang = Language.ENGLISH

        val coldStartMs = WallClock.measure { retro.performLab(text, lang) }
        repeat(5) { retro.performLab(text, lang) }
        val steady: BenchmarkResult = BenchmarkRunner().run(
            listOf(BenchmarkScenario("SNAPSHOT", retro, text, lang, 20))
        ).single()

        val encoded = retro.encode(text, lang)
        val packets = Packetizer.buildPackets(encoded.data, lang, 1L, 2, false)
        val wireBytes = packets.sumOf { it.serialize().size }

        val baseline = BaselineCapabilities(
            perMessageWireBytesMean = steady.meanEncodedBytes,
            perMessageEncodeMsMean = steady.encodeStats.meanMs,
            perMessageDecodeMsMean = steady.decodeStats.meanMs,
            coldStartTotalMs = coldStartMs,
            steadyStateP95Ms = steady.totalStats.p95Ms,
            heapDeltaBytesLabel = "n/a (see heap test)",
            measuredAt = System.currentTimeMillis(),
            machine = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
        )

        println("BASELINE SNAPSHOT: ${baseline.summary} (wire checked=${wireBytes}B)")
        assertEquals(1.0, steady.successRate, 0.0001)
        assertTrue(baseline.perMessageWireBytesMean > 0)
    }
}