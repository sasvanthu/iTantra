package com.example.itantra.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BleLinkCodecTest {

    private fun randomBytes(size: Int, random: Random = Random(42)): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    /** Writer.split -> Reader.feed -> concatenated payloads. */
    private fun roundTrip(stream: ByteArray, chunkPayload: Int): ByteArray {
        val writer = BleChunkWriter(chunkPayload)
        val reader = BleChunkReader()
        val out = java.io.ByteArrayOutputStream()
        var gaps = 0
        for (chunk in writer.split(stream)) {
            when (val r = reader.feed(chunk)) {
                is BleChunkResult.Gap -> gaps++
                is BleChunkResult.Stream -> out.write(r.bytes)
            }
        }
        assertEquals(0, gaps)
        return out.toByteArray()
    }

    @Test
    fun `empty stream produces no chunks`() {
        assertEquals(0, BleChunkWriter(20).split(ByteArray(0)).size)
    }

    @Test
    fun `exact byte-for-byte roundtrip across sizes and mtus`() {
        val mtus = listOf(1, 20, 128, 500, 512, 2048)
        val sizes = listOf(1, 19, 20, 21, 127, 128, 129, 499, 500, 501, 4096, 65_536 + 12)
        val random = Random(7)
        for (mtu in mtus) {
            for (size in sizes) {
                val stream = randomBytes(size, random)
                val out = roundTrip(stream, mtu)
                assertArrayEquals("mtu=$mtu size=$size", stream, out)
            }
        }
    }

    @Test
    fun `chunk count follows payload ceiling`() {
        val writer = BleChunkWriter(20)
        assertEquals(0, writer.split(ByteArray(0)).size)
        assertEquals(1, writer.split(ByteArray(1)).size)
        assertEquals(1, writer.split(ByteArray(20)).size)
        assertEquals(2, writer.split(ByteArray(21)).size)
        assertEquals(5, writer.split(ByteArray(100)).size)
        assertEquals(6, writer.split(ByteArray(101)).size)
    }

    @Test
    fun `chunks stay within the payload cap and carry valid headers`() {
        val payloadCap = 128
        val writer = BleChunkWriter(payloadCap)
        val chunks = writer.split(randomBytes(10_000))
        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue(BleLinkCodec.hasMagic(chunk))
            assertTrue(chunk.size - BleLinkCodec.HEADER_SIZE <= payloadCap)
        }
        assertEquals((10_000 + payloadCap - 1) / payloadCap, chunks.size)
    }

    @Test
    fun `sequence counter wraps at 65536`() {
        val writer = BleChunkWriter(20)
        var lastSeq = -1
        // 4001 chunks * 20 bytes = 80 KB of stream -> seq wraps past 65535.
        for (chunk in writer.split(ByteArray(80_020))) {
            val s = BleLinkCodec.readSeq(chunk)
            assertEquals((lastSeq + 1) and 0xFFFF, s)
            lastSeq = s
        }
        assertTrue(lastSeq < 40_000) // we really wrapped around
    }

    @Test
    fun `first chunk accepts any sequence`() {
        val reader = BleChunkReader()
        val chunk = ByteArray(BleLinkCodec.HEADER_SIZE + 3)
        chunk[0] = BleLinkCodec.MAGIC[0]
        chunk[1] = BleLinkCodec.MAGIC[1]
        chunk[2] = 0xFF.toByte()
        chunk[3] = 0xFE.toByte()
        chunk[4] = 1; chunk[5] = 2; chunk[6] = 3
        val result = reader.feed(chunk)
        assertTrue(result is BleChunkResult.Stream)
        assertArrayEquals(byteArrayOf(1, 2, 3), (result as BleChunkResult.Stream).bytes)
    }

    @Test
    fun `repeated chunk reports a gap and recovers on the next in-order chunk`() {
        val reader = BleChunkReader()
        val writer = BleChunkWriter(20)
        val chunks = writer.split(ByteArray(20)) // seq 0
        assertTrue(reader.feed(chunks[0]) is BleChunkResult.Stream)
        // Duplicate delivery of seq 0 is out of order -> gap, but not fatal.
        assertTrue(reader.feed(chunks[0]) is BleChunkResult.Gap)
        // The next in-order chunk from the same writer continues the stream.
        val next = writer.split(ByteArray(20))[0] // seq 1
        val result = reader.feed(next)
        assertTrue(result is BleChunkResult.Stream)
        assertEquals(20, (result as BleChunkResult.Stream).bytes.size)
    }

    @Test
    fun `gap reports how many chunks were lost`() {
        val writer = BleChunkWriter(20)
        val reader = BleChunkReader()
        val chunks = writer.split(ByteArray(200)) // 10 chunks, seqs 0..9
        reader.feed(chunks[0])
        reader.feed(chunks[1])
        reader.feed(chunks[2])
        // Simulate chunks 3..7 lost, chunk 8 arrives.
        val gap = reader.feed(chunks[8]) as BleChunkResult.Gap
        assertEquals(5, gap.lostChunks)
        // Chunk 9 continues the stream normally.
        assertTrue(reader.feed(chunks[9]) is BleChunkResult.Stream)
    }

    @Test
    fun `reset clears continuity state`() {
        val writer = BleChunkWriter(20)
        val reader = BleChunkReader()
        writer.split(ByteArray(20)) // seq 0
        reader.feed(writer.split(ByteArray(20))[0]) // seq 1
        reader.reset()
        val after = BleChunkWriter(20)
        after.split(ByteArray(20)) // seq 0 again on a fresh writer
        assertTrue(reader.feed(after.split(ByteArray(20))[0]) is BleChunkResult.Stream)
    }

    @Test
    fun `bad magic is reported as loss`() {
        val reader = BleChunkReader()
        val result = reader.feed(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01))
        assertTrue(result is BleChunkResult.Gap)
        assertTrue((result as BleChunkResult.Gap).lostChunks >= 1)
    }

    @Test
    fun `chunk stream reassembles a real FrameCodec frame`() {
        val payload = ByteArray(31_000) { (it % 251).toByte() }
        val frame = FrameWriter.write(3, payload)
        val chunkPayload = 500

        val writer = BleChunkWriter(chunkPayload)
        val reader = BleChunkReader()
        val frameReader = FrameReader()

        val received = java.io.ByteArrayOutputStream()
        var complete: ReceivedFrame? = null
        for (chunk in writer.split(frame)) {
            val stream = when (val r = reader.feed(chunk)) {
                is BleChunkResult.Gap -> throw AssertionError("unexpected gap in ordered delivery")
                is BleChunkResult.Stream -> r.bytes
            }
            received.write(stream)
            for (result in frameReader.feed(stream)) {
                if (result is FrameReadResult.Complete) complete = result.frame
            }
        }

        assertArrayEquals(frame, received.toByteArray())
        assertEquals(3, complete!!.epoch)
        assertArrayEquals(payload, complete!!.bytes)
    }

    @Test
    fun `end to end via chunk codec preserves transport delivery order`() {
        val writer = BleChunkWriter(64)
        val reader = BleChunkReader()
        val frameReader = FrameReader()
        val frames = mutableListOf<ReceivedFrame>()

        val rawPackets = listOf(
            Packet.createTextPacket(1L, 1, com.example.itantra.codec.Language.ENGLISH, ByteArray(100) { 1 }).serialize(),
            Packet.createTextPacket(1L, 2, com.example.itantra.codec.Language.ENGLISH, ByteArray(100) { 2 }).serialize(),
            Packet.createEndPacket(1L, com.example.itantra.codec.Language.ENGLISH, dataCount = 2).serialize()
        )
        for (raw in rawPackets) {
            for (chunk in writer.split(FrameWriter.write(1, raw))) {
                val stream = (reader.feed(chunk) as BleChunkResult.Stream).bytes
                for (result in frameReader.feed(stream)) {
                    if (result is FrameReadResult.Complete) frames.add(result.frame)
                }
            }
        }

        assertEquals(3, frames.size)
        assertEquals(1, frames[0].epoch)
        assertArrayEquals(rawPackets[0], frames[0].bytes)
        assertArrayEquals(rawPackets[2], frames[2].bytes)
    }
}