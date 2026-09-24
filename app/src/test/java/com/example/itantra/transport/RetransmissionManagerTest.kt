package com.example.itantra.transport

import com.example.itantra.codec.Language
import com.example.itantra.protocol.Packet
import com.example.itantra.protocol.RetransmissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetransmissionManagerTest {

    private fun dataPacket(msgId: Long, seq: Int): Packet =
        Packet.createTextPacket(msgId, seq, Language.ENGLISH, ByteArray(4) { seq.toByte() })

    private fun endPacket(msgId: Long): Packet =
        Packet.createEndPacket(msgId, Language.ENGLISH, dataCount = 1)

    private fun clockedManager(ackTimeout: Long = 1000, maxRetries: Int = 3): Pair<RetransmissionManager, () -> Unit> {
        val manager = RetransmissionManager(ackTimeout, maxRetries)
        var nowMs = 0L
        manager.setClock { nowMs }
        return manager to { nowMs += 100 }
    }

    @Test
    fun `acked packet is removed and rtt reported`() {
        val (manager, advance) = clockedManager()
        manager.track(dataPacket(1, 1))
        advance() // +100
        advance() // +200

        val rtt = manager.ack(1, 1)
        assertEquals(200L, rtt)
        assertEquals(0, manager.pendingCount)
    }

    @Test
    fun `unknown ack is ignored`() {
        val (manager, _) = clockedManager()
        assertNull(manager.ack(99, 1))
    }

    @Test
    fun `nack triggers retransmission and counts`() {
        val (manager, advance) = clockedManager()
        manager.track(dataPacket(1, 2))
        repeat(11) { advance() } // +1100 > ack timeout: stale

        val packet = manager.nack(1, 2)
        assertTrue(packet != null)
        assertEquals(2, packet!!.sequenceId)
        assertEquals(1, manager.retransmissionCount)
        assertEquals(1, manager.pendingCount)
    }

    @Test
    fun `duplicate nack within ack window is ignored`() {
        val (manager, advance) = clockedManager()
        manager.track(dataPacket(1, 2))
        repeat(11) { advance() } // make stale

        assertTrue(manager.nack(1, 2) != null) // resends, counts
        // Same gap re-reported immediately: single in-flight retry is enough.
        assertNull(manager.nack(1, 2))
        assertEquals(1, manager.retransmissionCount)
    }

    @Test
    fun `nack beyond retries marks packet failed`() {
        val (manager, advance) = clockedManager(maxRetries = 2)
        manager.track(dataPacket(1, 3))

        var failedKey: RetransmissionManager.PacketKey? = null
        manager.onMaxRetries = { failedKey = it }

        repeat(11) { advance() }
        assertTrue(manager.nack(1, 3) != null)   // retry 1
        repeat(11) { advance() }
        assertTrue(manager.nack(1, 3) != null)   // retry 2
        repeat(11) { advance() }
        assertNull(manager.nack(1, 3))           // retry 3 -> failed

        assertEquals(1, manager.failedCount)
        assertEquals(2, manager.retransmissionCount)
        assertEquals(0, manager.pendingCount)
        assertEquals(RetransmissionManager.PacketKey(1, 3), failedKey)
    }

    @Test
    fun `no timeout before ack timeout elapses`() {
        val (manager, advance) = clockedManager(ackTimeout = 1000, maxRetries = 2)
        manager.track(dataPacket(2, 1))

        repeat(9) { advance() } // +900 < 1000
        assertTrue(manager.checkTimeouts().isEmpty())
    }

    @Test
    fun `timeout resends overdue packet once per window`() {
        val (manager, advance) = clockedManager(ackTimeout = 1000, maxRetries = 5)
        manager.track(dataPacket(3, 1))
        repeat(11) { advance() } // +1100 > timeout

        val overdue = manager.checkTimeouts()
        assertEquals(1, overdue.size)
        assertEquals(1, manager.retransmissionCount)

        // Immediately after resending, age resets so no second resend happens.
        advance() // +100
        assertTrue(manager.checkTimeouts().isEmpty())
    }

    @Test
    fun `end packet is trackable (reliability for small messages)`() {
        val (manager, advance) = clockedManager()
        manager.track(endPacket(4))
        advance()
        assertTrue(manager.ack(4, -1) != null)
        assertEquals(0, manager.pendingCount)
    }

    @Test
    fun `dropMessage clears tracking for a message`() {
        val (manager, _) = clockedManager()
        manager.track(dataPacket(5, 1))
        manager.track(dataPacket(5, 2))
        manager.track(dataPacket(6, 1))

        manager.dropMessage(5)
        assertEquals(1, manager.pendingCount)
        assertNull(manager.ack(5, 1))
        assertNull(manager.ack(5, 2))
    }

    @Test
    fun `snapshot matches live counters`() {
        val (manager, advance) = clockedManager()
        manager.track(dataPacket(7, 1))
        repeat(11) { advance() }
        manager.nack(7, 1)
        val snap = manager.snapshot()
        assertEquals(1, snap.pendingCount)
        assertEquals(1, snap.retransmissions)
        assertEquals(0, snap.failed)
    }
}