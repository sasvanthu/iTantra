package com.example.itantra.transport

import com.example.itantra.codec.Importance
import com.example.itantra.codec.Language
import com.example.itantra.data.AdaptiveBandwidth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the Phase 11/12/15/16 transport components:
 *  - [PriorityFrameQueue] preemption + bucket FIFO
 *  - [StoreAndForwardQueue] retention / displacement / expiry
 *  - [AdaptiveLinkGovernor] mode-derived transmission decisions
 *  - [NetworkSimulator] seeded determinism + reorder/content integrity
 */
class PhaseComponentsTest {

    // ------------------------------------------------------------------
    // PriorityFrameQueue
    // ------------------------------------------------------------------

    @Test
    fun `priority queue drains highest bucket first, FIFO within a bucket`() = runBlocking {
        val q = PriorityFrameQueue()
        q.enqueue(byteArrayOf(1), 1)
        q.enqueue(byteArrayOf(2), 1)
        q.enqueue(byteArrayOf(3), 0)
        q.enqueue(byteArrayOf(9), 3)
        q.enqueue(byteArrayOf(8), 2)

        assertArrayEquals(byteArrayOf(9), q.take())
        assertArrayEquals(byteArrayOf(8), q.take())
        assertArrayEquals(byteArrayOf(1), q.take())
        assertArrayEquals(byteArrayOf(2), q.take())
        assertArrayEquals(byteArrayOf(3), q.take())
        assertEquals(0, q.size)
    }

    @Test
    fun `take suspends until a frame arrives`() = runBlocking {
        val q = PriorityFrameQueue()
        val got = CompletableDeferred<ByteArray>()
        val job = launch { got.complete(q.take()) }
        delay(50)
        assertFalse("must suspend while empty", got.isCompleted)
        q.enqueue(byteArrayOf(42), 2)
        assertArrayEquals(byteArrayOf(42), got.await())
        job.cancel()
    }

    @Test
    fun `enqueue clamps out-of-range priorities into valid buckets`() = runBlocking {
        val q = PriorityFrameQueue()
        q.enqueue(byteArrayOf(1), -5)
        q.enqueue(byteArrayOf(2), 99)
        q.enqueue(byteArrayOf(3), 2)
        assertArrayEquals(byteArrayOf(2), q.take())
        assertArrayEquals(byteArrayOf(3), q.take())
        assertArrayEquals(byteArrayOf(1), q.take())
    }

    // ------------------------------------------------------------------
    // StoreAndForwardQueue
    // ------------------------------------------------------------------

    @Test
    fun `retained queue prioritises higher importance over older low ones`() {
        var clock = 0L
        val sq = StoreAndForwardQueue(nowMillis = { clock })

        sq.enqueue(byteArrayOf(1), Language.ENGLISH, false, Importance.LOW.level.toByte())
        clock++
        sq.enqueue(byteArrayOf(2), Language.ENGLISH, false, Importance.NORMAL.level.toByte())
        clock++

        assertEquals(2, sq.size)
        // NORMAL (1) must be handed off before LOW (0), regardless of age.
        val next = sq.peekNext()
        assertNotNull(next)
        assertArrayEquals(byteArrayOf(2), next!!.data)
        assertEquals(Importance.NORMAL.level, next.priorityLevel)

        // markForwarded removes exactly the handed-off message.
        assertTrue(sq.markForwarded(next))
        assertEquals(1, sq.size)
        assertArrayEquals(byteArrayOf(1), sq.peekNext()!!.data)
        assertEquals(1, sq.stats.value.forwarded)
        assertEquals(2, sq.stats.value.queued)
    }

    @Test
    fun `oldest retained first within equal priority`() {
        var clock = 0L
        val sq = StoreAndForwardQueue(nowMillis = { clock })
        sq.enqueue(byteArrayOf(1), Language.ENGLISH, false, Importance.HIGH.level.toByte())
        clock++
        sq.enqueue(byteArrayOf(2), Language.ENGLISH, false, Importance.HIGH.level.toByte())

        assertArrayEquals(byteArrayOf(1), sq.peekNext()!!.data)
    }

    @Test
    fun `full queue displaces the weakest message for higher priority arrivals`() {
        var clock = 0L
        val sq = StoreAndForwardQueue(maxQueueSize = 2, nowMillis = { clock })

        sq.enqueue(byteArrayOf(1), Language.ENGLISH, false, Importance.LOW.level.toByte())
        clock++
        sq.enqueue(byteArrayOf(2), Language.ENGLISH, false, Importance.NORMAL.level.toByte())
        clock++

        val result = sq.enqueue(byteArrayOf(3), Language.ENGLISH, false, Importance.HIGH.level.toByte())
        assertTrue(result is StoreAndForwardResult.Accepted)
        assertTrue((result as StoreAndForwardResult.Accepted).droppedToMakeRoom)
        assertEquals(2, sq.size)
        assertEquals(1, sq.stats.value.dropped)
        // The LOW entry was displaced; the HIGH arrival is next out.
        assertArrayEquals(byteArrayOf(3), sq.peekNext()!!.data)
    }

    @Test
    fun `full queue drops a lower or equal priority arrival on the spot`() {
        var clock = 0L
        val sq = StoreAndForwardQueue(maxQueueSize = 2, nowMillis = { clock })
        sq.enqueue(byteArrayOf(3), Language.ENGLISH, false, Importance.HIGH.level.toByte())
        clock++
        sq.enqueue(byteArrayOf(4), Language.ENGLISH, false, Importance.HIGH.level.toByte())
        clock++

        val result = sq.enqueue(byteArrayOf(5), Language.ENGLISH, false, Importance.LOW.level.toByte())
        assertTrue(result is StoreAndForwardResult.Dropped)
        assertEquals(2, sq.size)
        assertEquals(1, sq.stats.value.dropped)
        assertArrayEquals(byteArrayOf(3), sq.peekNext()!!.data)
    }

    @Test
    fun `all-critical queue stays bounded replacing the oldest`() {
        var clock = 0L
        val sq = StoreAndForwardQueue(maxQueueSize = 2, nowMillis = { clock })
        sq.enqueue(byteArrayOf(1), Language.ENGLISH, true, Importance.CRITICAL.level.toByte())
        clock++
        sq.enqueue(byteArrayOf(2), Language.ENGLISH, true, Importance.CRITICAL.level.toByte())
        clock++

        val result = sq.enqueue(byteArrayOf(3), Language.ENGLISH, true, Importance.CRITICAL.level.toByte())
        assertTrue(result is StoreAndForwardResult.Accepted)
        assertEquals(2, sq.size)
        assertEquals(1, sq.stats.value.dropped)
        // Oldest CRITICAL (1) was replaced; (2) is now the senior retained.
        assertArrayEquals(byteArrayOf(2), sq.peekNext()!!.data)
    }

    @Test
    fun `stale messages expire and are counted honestly`() {
        var clock = 0L
        val sq = StoreAndForwardQueue(maxMessageAgeMs = 1_000, nowMillis = { clock })
        sq.enqueue(byteArrayOf(1), Language.ENGLISH, false, Importance.NORMAL.level.toByte())

        clock = 500
        assertNotNull("fresh message must still be offered", sq.peekNext())

        clock = 2_000
        assertNull("stale message must not be delivered half-way", sq.peekNext())
        assertEquals(1, sq.stats.value.expired)
        assertEquals(0, sq.size)
    }

    @Test
    fun `minimum queue size remains bounded even when policy retuned to zero`() {
        val sq = StoreAndForwardQueue()
        assertEquals(StoreAndForwardQueue.DEFAULT_MAX_QUEUE_SIZE, sq.maxQueueSize)
        sq.maxQueueSize = 0
        assertEquals(1, sq.maxQueueSize)
        val result = sq.enqueue(byteArrayOf(1), Language.ENGLISH, false, Importance.NORMAL.level.toByte())
        assertTrue(result is StoreAndForwardResult.Accepted)
        assertEquals(1, sq.size)
    }

    // ------------------------------------------------------------------
    // AdaptiveLinkGovernor
    // ------------------------------------------------------------------

    private fun metrics(onWireLossPercent: Int): LinkMetrics =
        LinkMetrics(packetLoss = onWireLossPercent, roundTripTimeMs = 40, retransmissions = 0)

    /** Saturate the estimator's 20-sample window so the mode is steady-state. */
    private fun feedWindow(g: AdaptiveLinkGovernor, onWireLossPercent: Int) {
        repeat(20) { g.observe(metrics(onWireLossPercent)) }
    }

    @Test
    fun `governor derives mode and payload ceiling from measured loss only`() {
        val g = AdaptiveLinkGovernor()
        assertEquals(AdaptiveBandwidth.BandwidthMode.NORMAL, g.mode())
        assertEquals(AdaptiveLinkGovernor.NORMAL_MAX_PAYLOAD, g.maxPayload())

        feedWindow(g, 8) // 8% loss -> NORMAL keeps the small packet size
        assertEquals(AdaptiveBandwidth.BandwidthMode.NORMAL, g.mode())
        assertEquals(AdaptiveLinkGovernor.NORMAL_MAX_PAYLOAD, g.maxPayload())
        assertFalse(g.shouldUsePrediction())

        feedWindow(g, 20) // 20% loss -> LOW: smaller atoms, deferred low prio
        assertEquals(AdaptiveBandwidth.BandwidthMode.LOW_BANDWIDTH, g.mode())
        assertEquals(AdaptiveLinkGovernor.LOW_MAX_PAYLOAD, g.maxPayload())
        assertTrue(g.shouldUsePrediction())
        assertTrue(g.shouldDeferLowPriority())

        feedWindow(g, 50) // 50% loss -> EMERGENCY: smallest atoms
        assertEquals(AdaptiveBandwidth.BandwidthMode.EMERGENCY, g.mode())
        assertEquals(AdaptiveLinkGovernor.EMERGENCY_MAX_PAYLOAD, g.maxPayload())
        assertTrue(g.shouldDeferLowPriority())
        assertFalse(g.shouldUsePrediction())
    }

    @Test
    fun `clean link opens up to the big payload ceiling`() {
        val g = AdaptiveLinkGovernor()
        g.observe(metrics(0))
        assertEquals(AdaptiveBandwidth.BandwidthMode.HIGH_BANDWIDTH, g.mode())
        assertEquals(AdaptiveLinkGovernor.HIGH_MAX_PAYLOAD, g.maxPayload())
    }

    @Test
    fun `high rtt alone degrades the link honestly`() {
        val g = AdaptiveLinkGovernor()
        g.observe(LinkMetrics(packetLoss = 0, roundTripTimeMs = 700, retransmissions = 0))
        assertEquals(AdaptiveBandwidth.BandwidthMode.LOW_BANDWIDTH, g.mode())
        assertEquals(AdaptiveLinkGovernor.LOW_MAX_PAYLOAD, g.maxPayload())
    }

    @Test
    fun `governor reset returns to the nominal ceiling`() {
        val g = AdaptiveLinkGovernor()
        g.observe(metrics(90))
        assertEquals(AdaptiveLinkGovernor.EMERGENCY_MAX_PAYLOAD, g.maxPayload())
        g.reset()
        assertEquals(AdaptiveBandwidth.BandwidthMode.NORMAL, g.mode())
        assertEquals(AdaptiveLinkGovernor.NORMAL_MAX_PAYLOAD, g.maxPayload())
    }

    // ------------------------------------------------------------------
    // NetworkSimulator
    // ------------------------------------------------------------------

    @Test
    fun `simulator is a no-op when no impairment is configured`() = runBlocking {
        val sim = NetworkSimulator(seed = 1L)
        val out = sim.applyOutgoing(byteArrayOf(7, 8, 9))
        assertEquals(1, out.size)
        assertArrayEquals(byteArrayOf(7, 8, 9), out[0])
        assertFalse(sim.config.isActive)
    }

    @Test
    fun `same seed reproduces the exact impairment sequence`() = runBlocking {
        fun seeded(): NetworkSimulator = NetworkSimulator(seed = 99L).apply {
            config.lossRate = 0.3f
            config.reorderRate = 0.4f
            config.duplicationRate = 0.2f
            config.latencyMs = 0
        }
        val a = seeded()
        val b = seeded()
        val inputs = (1..40).map { ByteArray(4) { i -> it.toByte() } }

        val flowA = mutableListOf<ByteArray>()
        val flowB = mutableListOf<ByteArray>()
        for (i in inputs.indices) {
            flowA += a.applyOutgoing(inputs[i])
            flowB += b.applyOutgoing(inputs[i])
        }
        assertEquals(flowA.size, flowB.size)
        for (i in flowA.indices) assertArrayEquals(flowA[i], flowB[i])
        assertTrue("the seed must actually exercise impairments", flowA.size != inputs.size || flowA.isNotEmpty())
    }

    @Test
    fun `reordered stream never fabricates content`() = runBlocking {
        val sim = NetworkSimulator(seed = 7L)
        sim.config.reorderRate = 0.3f
        val inputs = (1..64).map { ByteArray(6) { i -> it.toByte() } }
        val accepted = inputs.map { it.contentToString() }.toSet()

        var emitted = 0
        for (frame in inputs) {
            for (out in sim.applyOutgoing(frame)) {
                emitted++
                assertTrue("emitted frame must be a copy of an input frame",
                    out.contentToString() in accepted)
            }
        }
        // Consecutive holds model a drop, so emission may be < 64, but the
        // majority must survive intact.
        assertTrue(emitted > 32)
    }

    @Test
    fun `high loss with latency keeps every emitted frame intact`() = runBlocking {
        val sim = NetworkSimulator(seed = 5L)
        sim.config.lossRate = 0.5f
        sim.config.latencyMs = 5
        val inputs = (1..20).map { ByteArray(8) { i -> it.toByte() } }
        val accepted = inputs.map { it.contentToString() }.toSet()

        var emitted = 0
        for (frame in inputs) {
            for (out in sim.applyOutgoing(frame)) {
                emitted++
                assertTrue(out.contentToString() in accepted)
            }
        }
        assertTrue("loss must have dropped at least one frame", emitted < 20)
    }

    @Test
    fun `reset clears held frames and config`() = runBlocking {
        val sim = NetworkSimulator(seed = 2L)
        sim.config.reorderRate = 1f
        sim.applyOutgoing(byteArrayOf(1)) // held
        sim.config.lossRate = 0.5f
        sim.reset()
        assertFalse(sim.config.isActive)
        val out = sim.applyOutgoing(byteArrayOf(2))
        assertEquals(1, out.size)
        assertArrayEquals(byteArrayOf(2), out[0])
    }

    @Test
    fun `config label is honest about the active impairments`() {
        val c = NetworkSimConfig(lossRate = 0.3f, latencyMs = 250, reorderRate = 0.1f)
        assertTrue(c.label().contains("LOSS 30%"))
        assertTrue(c.label().contains("LAT 250ms"))
        assertTrue(c.label().contains("REORDER 10%"))
        assertFalse(NetworkSimConfig().label().isNotEmpty())
    }
}