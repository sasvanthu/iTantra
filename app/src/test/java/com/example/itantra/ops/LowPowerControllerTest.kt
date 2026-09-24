package com.example.itantra.ops

import com.example.itantra.codec.Importance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowPowerControllerTest {

    @Test
    fun `battery above low threshold is healthy and allows everything`() {
        val c = LowPowerController { 80 }
        assertEquals(LowPowerController.PowerProfile.HEALTHY, c.profile)
        assertTrue(c.canTransmit(Importance.LOW))
        assertTrue(c.canTransmit(Importance.CRITICAL))
        assertFalse(c.deferFullQualityExtras())
    }

    @Test
    fun `low battery only lets serious traffic through and defers extras`() {
        val c = LowPowerController { 17 }
        assertEquals(LowPowerController.PowerProfile.LOW, c.profile)
        assertTrue("high is urgent enough at LOW", c.canTransmit(Importance.HIGH))
        assertTrue(c.canTransmit(Importance.CRITICAL))
        assertFalse("plain chatter waits at LOW", c.canTransmit(Importance.LOW))
        assertTrue(c.deferFullQualityExtras())
    }

    @Test
    fun `critical battery reserves the channel for emergencies only`() {
        val c = LowPowerController { 5 }
        assertEquals(LowPowerController.PowerProfile.CRITICAL, c.profile)
        assertTrue("emergency always goes out", c.canTransmit(Importance.CRITICAL))
        assertFalse("even HIGH is withheld at CRITICAL", c.canTransmit(Importance.HIGH))
        assertTrue(c.deferFullQualityExtras())
    }

    @Test
    fun `thresholds are boundary exact`() {
        var battery = 20
        val c = LowPowerController { battery }
        assertEquals(LowPowerController.PowerProfile.LOW, c.profile)
        battery = 21
        assertEquals(LowPowerController.PowerProfile.HEALTHY, c.profile)
        battery = 10
        assertEquals(LowPowerController.PowerProfile.CRITICAL, c.profile)
    }

    @Test
    fun `clamps out-of-range battery readings honestly`() {
        val neg = LowPowerController { -5 }
        assertEquals(LowPowerController.PowerProfile.CRITICAL, neg.profile)
        val huge = LowPowerController { 170 }
        assertEquals(LowPowerController.PowerProfile.HEALTHY, huge.profile)
    }

    @Test
    fun `profile changes are logged and observations recorded`() {
        var battery = 60
        val c = LowPowerController { battery }
        assertEquals(0, c.changes.size)
        assertEquals(LowPowerController.PowerProfile.HEALTHY, c.profile)
        battery = 12
        assertEquals(LowPowerController.PowerProfile.LOW, c.profile)
        assertEquals(1, c.changes.size)
        assertEquals(LowPowerController.PowerProfile.HEALTHY, c.changes[0].from)
        assertEquals(LowPowerController.PowerProfile.LOW, c.changes[0].to)
        assertEquals(listOf(60, 12), c.observations)
    }
}