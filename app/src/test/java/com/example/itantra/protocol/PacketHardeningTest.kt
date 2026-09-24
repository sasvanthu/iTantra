package com.example.itantra.protocol

import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Wire-hardening coverage for Packet.deserialize (Phase 28) plus a regression
 * test for MeshReassembler's "unresolved loss only" gap accounting (healed
 * reordering must net back to zero, keeping the hop-reliability mesh honest).
 */
class PacketHardeningTest {

    private fun headerOffset(typeByte: Byte, payloadLengthInt: Int): ByteArray {
        val p = Packet.createTextPacket(1L, 1, Language.ENGLISH, ByteArray(10) { 1 })
        val bytes = p.serialize()
        bytes[18] = typeByte              // packetType
        bytes[21] = (payloadLengthInt ushr 24).toByte()
        bytes[22] = (payloadLengthInt ushr 16).toByte()
        bytes[23] = (payloadLengthInt ushr 8).toByte()
        bytes[24] = payloadLengthInt.toByte()
        return bytes
    }

    @Test
    fun `valid roundtrip preserves identity, payload and crc`() {
        val p = Packet.createTextPacket(42L, 7, Language.HINDI, "hello".encodeToByteArray(), priority = 1)
        val back = Packet.deserialize(p.serialize())
        assertNotNull(back)
        assertEquals(42L, back!!.messageId)
        assertEquals(7, back.sequenceId)
        assertEquals(Language.HINDI, back.language)
        assertEquals(PacketType.TEXT_DATA, back.packetType)
        assertEquals(1, back.priority.toInt())
        assertArrayEquals("hello".encodeToByteArray(), back.payload)
        assertEquals(p.crc, back.crc)
    }

    @Test
    fun `control packet with zero crc is accepted verbatim`() {
        val ack = Packet.createAckPacket(9L, 3)
        assertEquals(0, ack.crc)
        val back = Packet.deserialize(ack.serialize())
        assertNotNull(back)
        assertEquals(PacketType.ACK, back!!.packetType)
    }

    @Test
    fun `oversized claimed payload is rejected before allocation`() {
        val patched = headerOffset(PacketType.TEXT_DATA.id, 70_000)
        assertNull(Packet.deserialize(patched))
    }

    @Test
    fun `negative payload length is rejected`() {
        val patched = headerOffset(PacketType.TEXT_DATA.id, -1)
        assertNull(Packet.deserialize(patched))
    }

    @Test
    fun `truncated payload trailer is rejected`() {
        val p = Packet.createTextPacket(1L, 1, Language.ENGLISH, ByteArray(10) { 1 })
        val bytes = p.serialize()
        assertNull(Packet.deserialize(bytes.copyOf(bytes.size - 2)))
        assertNull(Packet.deserialize(bytes.copyOf(21)))
    }

    @Test
    fun `bad magic is rejected`() {
        assertNull(Packet.deserialize(ByteArray(40)))
    }

    @Test
    fun `unknown packet type id is rejected`() {
        assertNull(Packet.deserialize(headerOffset(0x7F, 10)))
    }

    @Test
    fun `wrong protocol version is rejected`() {
        val bytes = Packet.createTextPacket(1L, 1, Language.ENGLISH, ByteArray(10) { 1 }).serialize()
        bytes[4] = 2
        assertNull(Packet.deserialize(bytes))
    }

    @Test
    fun `corrupted payload fails the stored crc`() {
        val p = Packet.createTextPacket(1L, 1, Language.ENGLISH, ByteArray(10) { 1 })
        val bytes = p.serialize()
        bytes[25] = (bytes[25].toInt() xor 0xFF).toByte() // first payload byte
        assertNull(Packet.deserialize(bytes))
    }

    @Test
    fun `fuzzing never throws and roundtrips any accepted input`() {
        val rnd = Random(1234L)
        repeat(2_000) {
            val size = rnd.nextInt(300)
            val bytes = ByteArray(size) { rnd.nextInt(256).toByte() }
            val decoded = Packet.deserialize(bytes)
            if (decoded != null) {
                assertTrue("payload may not exceed the decoded length", decoded.payload.size <= bytes.size)
                assertArrayEquals("an accepted frame must serialize back identically", bytes, decoded.serialize())
            }
        }
    }

    @Test
    fun `reordered frames that heal report zero unresolved gaps`() {
        val r = MeshReassembler()
        val packets = Packetizer.buildPackets(
            ByteArray(4_000) { 1 }, Language.ENGLISH, messageId = 4L, priority = 2, isEmergency = false
        )
        val start = packets[0]
        val end = packets.last()
        val data = packets.drop(1).dropLast(1)

        r.onPacket(start)
        r.onPacket(data[2]) // seq 3 arrives before 1 and 2 -> 2 unresolved gaps
        assertEquals(2, r.gapsDetected)

        r.onPacket(data[0]) // seq 1 heals one hole
        assertEquals(1, r.gapsDetected)

        r.onPacket(data[1]) // seq 2 heals the other
        assertEquals(0, r.gapsDetected)

        r.onPacket(data[3]) // seq 4 is contiguous -> still zero unresolved
        assertEquals(0, r.gapsDetected)

        val message = r.onPacket(end)
        assertNotNull(message)
        assertEquals(0, r.gapsDetected)
        assertEquals(4, message!!.dataPackets)
    }
}