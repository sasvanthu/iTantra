package com.example.itantra.transport

import com.example.itantra.protocol.FrameReadResult
import com.example.itantra.protocol.FrameReader
import com.example.itantra.protocol.FrameWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `single frame roundtrip preserves epoch and payload`() {
        val data = payload(37)
        val frame = FrameWriter.write(epoch = 5, payload = data)

        val reader = FrameReader()
        val results = reader.feed(frame)

        assertEquals(1, results.size)
        val result = results.first() as? FrameReadResult.Complete
        assertTrue(result != null)
        assertEquals(5, result!!.frame.epoch)
        assertArrayEquals(data, result.frame.bytes)
    }

    @Test
    fun `partial reads assemble into one frame`() {
        val data = payload(64)
        val frame = FrameWriter.write(epoch = 2, payload = data)

        val reader = FrameReader()
        val half = frame.size / 2 - 1
        val first = reader.feed(frame.copyOfRange(0, half))
        // Not enough bytes for a frame boundary: nothing is reported yet.
        assertEquals(0, first.size)
        assertTrue(reader.snapshotSize() > 0)

        val second = reader.feed(frame.copyOfRange(half, frame.size))
        val complete = second.filterIsInstance<FrameReadResult.Complete>()
        assertEquals(1, complete.size)
        assertArrayEquals(data, complete.first().frame.bytes)
        assertEquals(0, reader.snapshotSize())
    }

    @Test
    fun `multiple frames in one chunk are all parsed`() {
        val a = FrameWriter.write(1, payload(10))
        val b = FrameWriter.write(1, payload(20))
        val c = FrameWriter.write(1, payload(30))

        val reader = FrameReader()
        val chunk = a + b + c
        val results = reader.feed(chunk)

        val completes = results.filterIsInstance<FrameReadResult.Complete>()
        assertEquals(3, completes.size)
        assertEquals(10, completes[0].frame.bytes.size)
        assertEquals(20, completes[1].frame.bytes.size)
        assertEquals(30, completes[2].frame.bytes.size)
    }

    @Test
    fun `crc corruption reports Corrupted and resyncs on next frame`() {
        val good = FrameWriter.write(1, payload(16))
        val corrupted = good.copyOf()
        corrupted[corrupted.size - 5] = (corrupted[corrupted.size - 5].toInt() xor 0x6A).toByte()

        val reader = FrameReader()
        val results = reader.feed(corrupted)
        assertTrue(results.first() is FrameReadResult.Corrupted)

        // A valid frame after the corruption must still parse.
        val reader2 = FrameReader()
        val mixed = corrupted + good
        val mixedResults = reader2.feed(mixed)
        val completes = mixedResults.filterIsInstance<FrameReadResult.Complete>()
        assertEquals(1, completes.size)
        assertEquals(payload(16).size, completes.first().frame.bytes.size)
    }

    @Test
    fun `non-magic garbage resyncs to the next valid frame`() {
        val garbage = ByteArray(5)
        garbage[3] = 0x11

        val reader = FrameReader()
        val results = reader.feed(garbage + FrameWriter.write(1, payload(8)))
        val completes = results.filterIsInstance<FrameReadResult.Complete>()
        assertEquals(1, completes.size)
        assertEquals(8, completes.first().frame.bytes.size)
    }

    @Test
    fun `large payload survives roundtrip`() {
        // At the frame ceiling (64 KiB) minus serialization margin.
        val data = payload(60000)
        val frame = FrameWriter.write(7, data)

        val reader = FrameReader()
        val results = reader.feed(frame)
        val complete = results.first() as FrameReadResult.Complete
        assertArrayEquals(data, complete.frame.bytes)
        assertEquals(7, complete.frame.epoch)
    }

    @Test
    fun `different epochs are preserved per frame`() {
        val frameA = FrameWriter.write(1, payload(2))
        val frameB = FrameWriter.write(9, payload(2))

        val reader = FrameReader()
        val results = reader.feed(frameA + frameB)
        val epochs = results.filterIsInstance<FrameReadResult.Complete>().map { it.frame.epoch }
        assertEquals(listOf(1, 9), epochs)
        assertNotEquals(frameA.contentEquals(frameB), true)
    }
}