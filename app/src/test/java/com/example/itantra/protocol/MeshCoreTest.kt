package com.example.itantra.protocol

import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCoreTest {

    // ------------------------------------------------------------------
    // HopPacket codec
    // ------------------------------------------------------------------

    @Test
    fun `hop packet roundtrips fields and payload`() {
        val payload = byteArrayOf(0x52, 0x46, 0x4C, 0x4B, 1, 2, 3, 4)
        val hop = HopPacket(hopId = Long.MAX_VALUE, origin = "D123ABC", ttl = 8, hops = 2, payload = payload)

        val decoded = HopPacket.deserialize(hop.serialize())

        assertNotNull(decoded)
        assertEquals(Long.MAX_VALUE, decoded!!.hopId)
        assertEquals("D123ABC", decoded.origin) // origin field is case-preserving
        assertEquals(8, decoded.ttl)
        assertEquals(2, decoded.hops)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `origin is padded and truncated to sixteen ascii bytes`() {
        val padded = HopPacket(0L, "tiny", 1, 0, ByteArray(0)).serialize()
        val decoded = HopPacket.deserialize(padded)
        assertEquals("tiny", decoded!!.origin)

        val long = "D1234567890ABCDEFGHIJ"
        val encoded = HopPacket(0L, long, 1, 0, ByteArray(0)).serialize()
        assertEquals("D1234567890ABCDE", HopPacket.deserialize(encoded)!!.origin)
    }

    @Test
    fun `malformed hop packets are rejected`() {
        val good = HopPacket(99L, "D123ABC", 8, 0, byteArrayOf(9, 9)).serialize()

        val tooShort = good.copyOf(good.size - 1)
        assertNull(HopPacket.deserialize(tooShort))

        val badMagic = good.copyOf()
        badMagic[0] = 0x00
        assertFalse(HopPacket.hasMagic(badMagic))
        assertNull(HopPacket.deserialize(badMagic))

        val corrupted = good.copyOf()
        corrupted[HopPacket.HEADER_SIZE / 2] = (corrupted[HopPacket.HEADER_SIZE / 2] + 1).toByte()
        assertNull(HopPacket.deserialize(corrupted))

        val fatLength = good.copyOf()
        fatLength[HopPacket.CRC_OFFSET - 2] = 0x01.toByte()
        fatLength[HopPacket.CRC_OFFSET - 1] = 0x00.toByte()
        assertNull(HopPacket.deserialize(fatLength))
    }

    @Test
    fun `empty mesh payload is allowed`() {
        val decoded = HopPacket.deserialize(HopPacket(1L, "D123ABC", 5, 0, ByteArray(0)).serialize())
        assertNotNull(decoded)
        assertEquals(0, decoded!!.payload.size)
    }

    // ------------------------------------------------------------------
    // MeshRouter
    // ------------------------------------------------------------------

    @Test
    fun `new packets are accepted and relayable`() {
        val a = MeshRouter("DA", maxTtl = 8)
        val b = MeshRouter("DB", maxTtl = 8)

        val envelope = a.craft("hello".encodeToByteArray()).serialize()
        val packet = HopPacket.deserialize(envelope)!!

        val decision = b.receive(packet)
        assertTrue(decision is MeshRouting.Accept)
        assertEquals(7, (decision as MeshRouting.Accept).forwardTtl)
    }

    @Test
    fun `duplicates and self echoes are dropped`() {
        val a = MeshRouter("DA", maxTtl = 8)
        val b = MeshRouter("DB", maxTtl = 8)

        val packet = HopPacket.deserialize(a.craft("x".encodeToByteArray()).serialize())!!
        assertTrue(b.receive(packet) is MeshRouting.Accept)
        assertTrue(b.receive(packet) is MeshRouting.Drop) // duplicate

        val own = HopPacket.deserialize(b.craft("y".encodeToByteArray()).serialize())!!
        assertTrue(b.receive(own) is MeshRouting.Drop) // self echo
    }

    @Test
    fun `ttl caps relay after the budget is exhausted`() {
        val a = MeshRouter("DA", maxTtl = 8)
        val b = MeshRouter("DB", maxTtl = 8)
        val c = MeshRouter("DC", maxTtl = 8)

        // ttl=1: B delivers but must not relay further.
        val near = HopPacket.deserialize(a.craft("k".encodeToByteArray(), ttl = 1).serialize())!!
        val first = b.receive(near)
        assertTrue(first is MeshRouting.Accept)
        assertEquals(0, (first as MeshRouting.Accept).forwardTtl)

        // A full chain: A -> B -> C with default ttl works.
        val packet = HopPacket.deserialize(a.craft("far".encodeToByteArray()).serialize())!!
        val atB = b.receive(packet)
        assertTrue(atB is MeshRouting.Accept)
        val relayed = packet.copy(ttl = (atB as MeshRouting.Accept).forwardTtl, hops = packet.hops + 1)
        val atC = c.receive(relayed)
        assertTrue(atC is MeshRouting.Accept)
        assertEquals("hops must advance", 1, relayed.hops)
    }

    @Test
    fun `craft assigns unique hop ids and clamps ttl`() {
        val a = MeshRouter("DA", maxTtl = 8)
        val h1 = a.craft(ByteArray(0))
        val h2 = a.craft(ByteArray(0))
        assertTrue(h1.hopId != h2.hopId)
        assertEquals(8, h1.ttl)
        assertEquals(8, a.craft(ByteArray(0), ttl = 99).ttl)
        assertEquals(1, a.craft(ByteArray(0), ttl = -5).ttl)
        assertEquals(0, h1.hops)
    }

    // ------------------------------------------------------------------
    // MeshReassembler
    // ------------------------------------------------------------------

    @Test
    fun `ordered packets reassemble into the exact payload`() {
        val target = "mesh over the air".encodeToByteArray()
        val packets = Packetizer.buildPackets(target, Language.ENGLISH, messageId = 1L, priority = 2, isEmergency = false)
        val r = MeshReassembler()

        var result: MeshMessage? = null
        for (p in packets) {
            val m = r.onPacket(p)
            if (m != null) result = m
        }
        assertNotNull(result)
        val msg = requireNotNull(result)
        assertArrayEquals(target, msg.payload)
        assertEquals(1, msg.dataPackets) // 15 bytes fit in a single DATA packet
        assertFalse(msg.isEmergency)
        assertEquals(0, r.duplicatesSeen)
        assertEquals(0, r.gapsDetected)
    }

    @Test
    fun `out of order arrival reassembles anyway`() {
        val target = ByteArray(5 * 1024 + 3) { (it % 200).toByte() }
        val packets = Packetizer.buildPackets(target, Language.TAMIL, messageId = 7L, priority = 2, isEmergency = false)
        val r = MeshReassembler()

        var result: MeshMessage? = null
        for (p in packets.reversed()) {
            val m = r.onPacket(p)
            if (m != null) result = m
        }
        assertNotNull(result)
        val msg = requireNotNull(result)
        assertArrayEquals(target, msg.payload)
        assertEquals(Language.TAMIL, msg.language)
    }

    @Test
    fun `duplicate data packets are counted but never reprocessed`() {
        val packets = Packetizer.buildPackets("dedup me".encodeToByteArray(), Language.ENGLISH, messageId = 3L, priority = 2, isEmergency = false)
        val r = MeshReassembler()

        // START, DATA, duplicate DATA, END: the duplicate must be counted and
        // skipped, and the message must still complete exactly once.
        var count = 0
        var result: MeshMessage? = null
        for (p in listOf(packets[0], packets[1], packets[1], packets[2])) {
            val m = r.onPacket(p)
            if (m != null) {
                count++
                result = m
            }
        }
        assertNotNull(result)
        assertArrayEquals("dedup me".encodeToByteArray(), result!!.payload)
        assertEquals(1, count)
        assertEquals(1, r.duplicatesSeen)
    }

    @Test
    fun `lost tail is detected by the end dataCount and reclaimed by purge`() {
        var clock = 100L
        val r = MeshReassembler(nowMillis = { clock })
        val packets = Packetizer.buildPackets(ByteArray(3000) { 1 }, Language.ENGLISH, messageId = 9L, priority = 2, isEmergency = false)

        // Deliberately withhold the last two DATA packets; END still announces
        // the full expected count, so no half-message may ever be emitted.
        assertNull(r.onPacket(packets[0])) // START
        assertNull(r.onPacket(packets[1])) // DATA 1 of 3
        assertNull(r.onPacket(packets.last())) // END announces dataCount=3

        // Gap is reported at END but the buffer is kept: a flooded network may
        // still deliver the stragglers.
        assertEquals(2, r.gapsDetected)
        assertEquals(0, r.droppedMessages)

        // The missing packets never arrive: only the purge reclaims it.
        clock = 50_000
        r.purgeStale(retentionMillis = 30_000)
        assertEquals(1, r.droppedMessages)
    }

    @Test
    fun `emergency priority is carried through reassembly`() {
        val packets = Packetizer.buildPackets("EMERGENCY".encodeToByteArray(), Language.ENGLISH, messageId = 5L, priority = 3, isEmergency = true)
        val r = MeshReassembler()

        var result: MeshMessage? = null
        for (p in packets) {
            val m = r.onPacket(p)
            if (m != null) result = m
        }
        assertNotNull(result)
        assertTrue(result!!.isEmergency)
    }

    @Test
    fun `stale half messages are purged`() {
        var clock = 100L
        val r = MeshReassembler(nowMillis = { clock })
        val packets = Packetizer.buildPackets("stale".encodeToByteArray(), Language.ENGLISH, messageId = 11L, priority = 2, isEmergency = false)
        r.onPacket(packets[0]) // START at t=100, never finished
        assertNull(r.onPacket(packets[1])) // DATA arrives, buffer still open

        clock = 1000
        r.purgeStale(retentionMillis = 500) // firstReceivedAt (100) < cutoff (500)
        assertEquals(1, r.droppedMessages)
    }
}