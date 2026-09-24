package com.example.itantra.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationModeControllerTest {

    @Test
    fun `starts in NORMAL with full audio and free transmission`() {
        val c = OperationModeController()
        assertEquals(OperationMode.NORMAL, c.mode)
        assertTrue(c.isCaptureAllowed)
        assertTrue(c.isPlaybackAllowed)
        assertTrue(c.isTransmissionFree)
        assertFalse(c.isEmergency)
    }

    @Test
    fun `silent mode strands both capture and playback`() {
        val c = OperationModeController()
        c.switchTo(OperationMode.SILENT, "user toggled")
        assertFalse(c.isCaptureAllowed)
        assertFalse(c.isPlaybackAllowed)
        assertFalse(c.isTransmissionFree)
        assertEquals(OperationMode.SILENT, c.mode)
    }

    @Test
    fun `every lawful transition is logged with cause and order`() {
        var t = 0L
        val c = OperationModeController { ++t }
        c.switchTo(OperationMode.PTT, "hold to talk")
        c.switchTo(OperationMode.NORMAL, "release")
        assertEquals(
            listOf("hold to talk", "release"),
            c.history.map { it.cause }
        )
        assertEquals(OperationMode.NORMAL, c.mode)
    }

    @Test
    fun `emergency latches and blocks silent escape attempts`() {
        var t = 0L
        val c = OperationModeController { ++t }
        c.switchTo(OperationMode.EMERGENCY, "alert raised")
        assertTrue(c.isEmergency)

        val attempted = c.switchTo(OperationMode.SILENT, "operator wants silence")
        assertEquals("EMERGENCY is latched; SILENT is refused", OperationMode.EMERGENCY, attempted)
        val attemptedPtt = c.switchTo(OperationMode.PTT, "operator wants PTT")
        assertEquals(OperationMode.EMERGENCY, attemptedPtt)
        assertTrue(c.isEmergency)

        // The refusal is part of the audit history too.
        assertTrue(c.history.any { it.cause.contains("REJECTED") })
    }

    @Test
    fun `only explicit acknowledgement releases the emergency`() {
        var t = 0L
        val c = OperationModeController { ++t }
        assertEquals(OperationMode.EMERGENCY, c.switchTo(OperationMode.EMERGENCY, "alert"))
        val released = c.acknowledgeEmergency("operator")
        assertEquals(OperationMode.NORMAL, released)
        assertFalse(c.isEmergency)
        assertEquals("acknowledged by operator", c.history.last().cause)
    }

    @Test
    fun `re-affirming emergency over an active alert is still the same mode`() {
        val c = OperationModeController()
        c.switchTo(OperationMode.EMERGENCY, "first")
        c.switchTo(OperationMode.EMERGENCY, "re-invoked")
        assertEquals(OperationMode.EMERGENCY, c.mode)
        assertEquals(2, c.history.size)
    }

    @Test
    fun `acknowledging with no active emergency is a no-op`() {
        val c = OperationModeController()
        assertEquals(OperationMode.NORMAL, c.acknowledgeEmergency("nobody"))
        assertEquals(OperationMode.NORMAL, c.mode)
    }
}

class EmergencyControllerTest {

    @Test
    fun `raising opens an active unacknowledged emergency`() {
        var t = 0L
        val c = EmergencyController { ++t }
        c.raise("battery is dead")
        assertTrue(c.isActive)
        assertEquals("battery is dead", c.currentReason)
        assertEquals(1, c.log.size)
        assertEquals(0, c.countClosed())
    }

    @Test
    fun `re-raising while active increments the counter and updates reason`() {
        var t = 0L
        val c = EmergencyController { ++t }
        c.raise("first")
        c.raise("still urgent")
        assertTrue(c.isActive)
        assertEquals(1, c.log.size)
        assertEquals(1, c.log[0].reRaises)
        assertEquals("still urgent", c.log[0].reason)
    }

    @Test
    fun `acknowledgement requires a named operator`() {
        var t = 0L
        val c = EmergencyController { ++t }
        c.raise("help")
        assertFalse("blank acknowledger must be refused", c.acknowledge("   "))
        assertTrue(c.isActive)

        assertTrue(c.acknowledge("operator-1"))
        assertFalse(c.isActive)
        assertEquals(1, c.countClosed())
        val closed = c.log[0]
        assertEquals("operator-1", closed.acknowledgedBy)
        assertEquals(2L, closed.acknowledgedAt)
    }

    @Test
    fun `acknowledging with nothing active returns false`() {
        val c = EmergencyController()
        assertFalse(c.acknowledge("operator"))
        assertEquals(0, c.log.size)
    }

    @Test
    fun `a closed emergency stays in the logbook for audit`() {
        var t = 0L
        val c = EmergencyController { ++t }
        c.raise("fire")
        c.acknowledge("operator")
        assertEquals(0, c.log.filter { it.acknowledgedAt == null }.size)
        assertEquals(1, c.log.size)
        assertEquals(1, c.countClosed())
    }
}