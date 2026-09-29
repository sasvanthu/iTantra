package com.example.itantra.mesh

import com.example.itantra.codec.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-level guarantees for the neighbor beacon. */
class MeshBeaconTest {

    @Test
    fun `beacon round trips every field exactly`() {
        val beacon = MeshBeacon(
            origin = "D1A2B3C4",
            bootId = 0x0123456789ABCDEFL,
            peers = listOf("DAAAAAAAA", "DBBBBBBB"),
            languageMask = MeshBeacon.languageMaskOf(listOf(Language.ENGLISH, Language.TAMIL)),
            flags = 0
        )
        val decoded = MeshBeacon.deserialize(beacon.serialize())
        assertNotNull(decoded)
        assertEquals("D1A2B3C4", decoded!!.origin)
        assertEquals(0x0123456789ABCDEFL, decoded.bootId)
        assertEquals(listOf("DAAAAAAAA", "DBBBBBBB"), decoded.peers)
        assertEquals(setOf(Language.ENGLISH, Language.TAMIL), decoded.advertisedLanguages())
    }

    @Test
    fun `empty beacon and a beacon with no peers are valid`() {
        val bare = MeshBeacon("D0", 1L, emptyList(), 0)
        val decoded = MeshBeacon.deserialize(bare.serialize())
        assertNotNull(decoded)
        assertEquals("D0", decoded!!.origin)
        assertTrue(decoded.peers.isEmpty())
        assertTrue(decoded.advertisedLanguages().isEmpty())
    }

    @Test
    fun `every supported language fits the advertisement mask`() {
        val all = Language.values().filterNot { it == Language.UNKNOWN }
        val mask = MeshBeacon.languageMaskOf(all)
        assertEquals(all.toSet(), MeshBeacon.decodeLanguageMask(mask))
    }

    @Test
    fun `corrupted crc is rejected rather than trusted`() {
        val bytes = MeshBeacon("D1A2B3C4", 42L, listOf("DPEER0001"), 0x0003).serialize()
        val tampered = bytes.copyOf()
        // Flip a payload bit; the CRC must no longer match.
        tampered[6] = (tampered[6].toInt() xor 0x20).toByte()
        assertNull(MeshBeacon.deserialize(tampered))
    }

    @Test
    fun `truncated and foreign buffers are rejected`() {
        val bytes = MeshBeacon("D1A2B3C4", 7L, listOf("DPEER0001"), 0x00FF).serialize()
        assertNull("truncated beacon must not decode", MeshBeacon.deserialize(bytes.copyOf(bytes.size - 3)))
        assertNull("empty buffer must not decode", MeshBeacon.deserialize(ByteArray(0)))

        val notABeacon = "I am an application message".toByteArray()
        assertFalse(MeshBeacon.hasMagic(notABeacon))
        assertNull(MeshBeacon.deserialize(notABeacon))
    }

    @Test
    fun `unsupported version is rejected instead of misread`() {
        val bytes = MeshBeacon("D1A2B3C4", 9L, emptyList(), 0).serialize()
        val bumped = bytes.copyOf()
        bumped[4] = 99
        assertNull(MeshBeacon.deserialize(bumped))
    }

    @Test
    fun `peer list is bounded so a beacon can never grow unbounded`() {
        val huge = (1 until 40).map { "P$it" }
        val serialized = MeshBeacon("D0", 1L, huge, 0).serialize()
        val decoded = MeshBeacon.deserialize(serialized)
        assertNotNull(decoded)
        assertEquals(MeshBeacon.MAX_PEERS, decoded!!.peers.size)
        assertTrue("beacon must stay small", serialized.size <= MeshBeacon.MAX_BYTES)
    }

    @Test
    fun `peer id is truncated to the wire width without breaking framing`() {
        val long = "A-VERY-LONG-DEVICE-ID-THAT-EXCEEDS-THE-WIRE"
        val bytes = MeshBeacon("D0", 1L, listOf(long), 0).serialize()
        val decoded = MeshBeacon.deserialize(bytes)
        assertNotNull(decoded)
        assertEquals(MeshBeacon.PEER_ID_BYTES, decoded!!.peers.single().length)
    }

    @Test
    fun `origin is zero padded on the wire and trimmed on decode`() {
        val bytes = MeshBeacon("SHORT", 5L, emptyList(), 0).serialize()
        val decoded = MeshBeacon.deserialize(bytes)
        assertEquals("SHORT", decoded!!.origin)
    }

    @Test
    fun `header bytes are crc protected and trailer flags are not`() {
        // The CRC covers everything up to (not including) the trailing flag
        // byte, so a corrupt presence bit is dropped but a corrupt identity is
        // never trusted.
        val bytes = MeshBeacon("D1A2B3C4", 11L, listOf("DPEER0001"), 0x0007, flags = 0x02).serialize()
        assertEquals(0x02, MeshBeacon.deserialize(bytes)!!.flags)

        val flagFlipped = bytes.copyOf()
        flagFlipped[flagFlipped.size - 1] = 0x03
        assertEquals("flags sit outside the checksum", 0x03, MeshBeacon.deserialize(flagFlipped)!!.flags)

        val identityFlipped = bytes.copyOf()
        identityFlipped[6] = (identityFlipped[6].toInt() xor 0x08).toByte()
        assertNull("a corrupt origin must never be trusted", MeshBeacon.deserialize(identityFlipped))
    }

    @Test
    fun `a real nine character mesh device id survives a beacon round trip`() {
        // Mesh node ids are "D" + 8 hex chars; a narrower peer field would
        // silently corrupt the very id we are trying to learn.
        val deviceId = "D1A2B3C4E"
        val decoded = MeshBeacon.deserialize(
            MeshBeacon("D0", 1L, listOf(deviceId), 0).serialize()
        )
        assertEquals(deviceId, decoded!!.peers.single())
    }
}
