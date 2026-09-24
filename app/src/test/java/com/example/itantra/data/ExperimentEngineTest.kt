package com.example.itantra.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentEngineTest {

    private fun outcome(
        unitId: Long, arm: String,
        success: Boolean = true,
        encoded: Int = 100,
        encodeMs: Long = 5,
        decodeMs: Long = 3,
        totalMs: Long = 10,
        compression: Double = 0.5,
        relevance: Float? = null
    ) = ExperimentOutcome(
        unitId = unitId, armId = arm, success = success,
        originalBytes = 200, encodedBytes = encoded, packetCount = 4,
        encodeMs = encodeMs, decodeMs = decodeMs, totalMs = totalMs,
        compressionPercentage = compression, relevanceScore = relevance
    )

    @Test
    fun `balanced block assignment gives each arm equal counts`() {
        val engine = ExperimentEngine(seed = 1234L)
        val counts = mutableMapOf<String, Int>()
        repeat(200) {
            val arm = engine.nextArm()
            counts[arm.id] = (counts[arm.id] ?: 0) + 1
        }
        assertEquals(200, engine.assigned)
        assertEquals(100, counts["BASELINE"])
        assertEquals(100, counts["RETRO"])
    }

    @Test
    fun `same seed reproduces the identical assignment sequence`() {
        val a = ExperimentEngine(seed = 42L)
        val b = ExperimentEngine(seed = 42L)
        val seqA = (1..50).map { a.nextArm().id }
        val seqB = (1..50).map { b.nextArm().id }
        assertEquals(seqA, seqB)
    }

    @Test
    fun `different seeds produce different sequences for the same units`() {
        val a = ExperimentEngine(seed = 7L)
        val b = ExperimentEngine(seed = 99L)
        val seqA = (1..40).map { a.nextArm().id }
        val seqB = (1..40).map { b.nextArm().id }
        assertNotEquals(seqA, seqB)
    }

    @Test
    fun `assignment is balanced but not a predictable alternation`() {
        val engine = ExperimentEngine(seed = 5L)
        val seq = (1..40).map { engine.nextArm().id }
        // Strict alternation (BASELINE, RETRO, BASELINE, ...) is NOT what a
        // randomized trial does; within any block both counts still balance.
        assertFalse("block randomization must not be strictly alternating",
            seq.filterIndexed { i, v -> i % 2 == 0 }.all { it == "BASELINE" })
        assertEquals(20, seq.count { it == "BASELINE" })
        assertEquals(20, seq.count { it == "RETRO" })
    }

    @Test
    fun `summary aggregates only recorded trials over each arm`() {
        val engine = ExperimentEngine(seed = 1L)
        repeat(10) { engine.record(outcome(it.toLong(), "BASELINE", encoded = 100)) }
        repeat(10) { engine.record(outcome(100L + it, "RETRO", encoded = 40)) }

        val byArm = engine.summarize().associateBy { it.armId }
        assertEquals(10, byArm["BASELINE"]!!.sampleCount)
        assertEquals(10, byArm["RETRO"]!!.sampleCount)
        assertEquals(100.0, byArm["BASELINE"]!!.meanEncodedBytes, 0.001)
        assertEquals(40.0, byArm["RETRO"]!!.meanEncodedBytes, 0.001)
        assertEquals(1.0, byArm["BASELINE"]!!.successRate, 0.001)
    }

    @Test
    fun `failed trials lower the success rate but stay counted`() {
        val engine = ExperimentEngine(seed = 2L)
        repeat(4) { engine.record(outcome(it.toLong(), "RETRO", success = true)) }
        repeat(1) { engine.record(outcome(10L + it, "RETRO", success = false)) }

        val retro = engine.summarize().single { it.armId == "RETRO" }
        assertEquals(5, retro.sampleCount)
        assertEquals(4, retro.successCount)
        assertEquals(0.8, retro.successRate, 0.001)
    }

    @Test
    fun `relevance mean stays null until a human rating is recorded`() {
        val engine = ExperimentEngine(seed = 3L)
        engine.record(outcome(1L, "RETRO"))
        assertNull("no relevance ratings yet", engine.summarize().single { it.armId == "RETRO" }.relevanceMean)

        engine.record(outcome(2L, "RETRO", relevance = 4.0f))
        engine.record(outcome(3L, "RETRO", relevance = 5.0f))
        val retro = engine.summarize().single { it.armId == "RETRO" }
        assertEquals(2, retro.relevanceCount)
        assertEquals(4.5f, retro.relevanceMean!!, 0.001f)
    }

    @Test
    fun `impossible timing or negative bytes is rejected and counted`() {
        val engine = ExperimentEngine(seed = 9L)
        engine.record(outcome(1L, "BASELINE"))
        assertFalse(engine.record(outcome(2L, "BASELINE", encodeMs = -4)))
        assertFalse(engine.record(outcome(3L, "BASELINE", encoded = -1)))

        assertEquals(1, engine.recordedCount)
        assertEquals(2, engine.rejectedCount)
        assertTrue(engine.rejectedUnitIds().containsAll(listOf(2L, 3L)))
        // The rejected trials must never pollute the averages.
        assertEquals(1, engine.summarize().single { it.armId == "BASELINE" }.sampleCount)
    }

    @Test
    fun `empty summary is zero-count per arm with nulls not zeros`() {
        val engine = ExperimentEngine(seed = 0L)
        val retro = engine.summarize().single { it.armId == "RETRO" }
        assertEquals(0, retro.sampleCount)
        assertEquals(0.0, retro.successRate, 0.001)
        assertNull(retro.relevanceMean)
    }
}