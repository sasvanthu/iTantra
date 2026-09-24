package com.example.itantra.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveTransmissionTest {

    @Test
    fun `stage plan counts packets like the transport ceiling`() {
        assertEquals(1, ProgressiveTransmission.plan(100).totalPackets)
        assertEquals(2, ProgressiveTransmission.plan(1025, 1024).totalPackets)
        assertEquals(4, ProgressiveTransmission.plan(4096, 1024).totalPackets)
        assertEquals(5, ProgressiveTransmission.plan(4097, 1024).totalPackets)
        assertEquals(0, ProgressiveTransmission.plan(0).totalPackets)
        assertEquals(0, ProgressiveTransmission.plan(0).previewBytes)
        assertEquals(64, ProgressiveTransmission.plan(200).previewBytes)
        assertEquals(50, ProgressiveTransmission.plan(50).previewBytes)
    }

    @Test
    fun `preview is capped and is a real prefix of the message`() {
        val text = "I need help near the railway station please come immediately."
        val plan = ProgressiveTransmission.plan(text.toByteArray().size)
        val p = ProgressiveTransmission.preview(text, plan)
        assertTrue(text.startsWith(p))
        assertTrue("preview must respect the byte cap", p.toByteArray(Charsets.UTF_8).size <= plan.previewBytes)
        assertTrue(p.isNotEmpty())
    }

    @Test
    fun `utf8 prefix never splits a multi-byte character`() {
        val text = "எனக்கு உதவி தேவை" // Tamil, 3-byte UTF-8 chars
        // 4-byte entries from split points still produce a valid string.
        for (bytes in listOf(1, 2, 3, 4, 5, 9, 13)) {
            val taken = ProgressiveTransmission.takeUtf8Prefix(text, bytes)
            assertTrue("byte budget must be respected", taken.toByteArray(Charsets.UTF_8).size <= bytes)
            assertEquals("no replacement chars from split codepoints", '\uFFFD' !in taken, true)
        }
        // A surrogate pair (one code point -> 4 UTF-8 bytes) is kept whole.
        val emoji = "help \uD83D\uDE00 now" // (code point survives as a pair)
        assertEquals("help ", ProgressiveTransmission.takeUtf8Prefix(emoji, 6))
        assertTrue("emoji kept intact when budget allows",
            ProgressiveTransmission.takeUtf8Prefix(emoji, 16).contains("\uD83D\uDE00"))
    }

    @Test
    fun `quality is identical for identical text and zero for empty`() {
        assertEquals(1f, ProgressiveTransmission.measuredQuality("help me now", "help me now"))
        assertEquals(0f, ProgressiveTransmission.measuredQuality("help me now", ""))
        assertEquals(1f, ProgressiveTransmission.measuredQuality("", ""))
    }

    @Test
    fun `longer previews give monotonically non-decreasing measured quality`() {
        val full = "I need help near the railway station please come immediately."
        val plan = ProgressiveTransmission.plan(full.toByteArray().size)
        var previous = -1f
        // Longer preview bytes -> bigger real preview prefix -> quality grows.
        for (bytes in listOf(8, 16, 32, plan.previewBytes)) {
            val preview = ProgressiveTransmission.takeUtf8Prefix(full, bytes)
            val q = ProgressiveTransmission.measuredQuality(full, preview)
            assertTrue("quality must not regress as the preview grows (prev=$previous q=$q)",
                q >= previous)
            assertTrue(q in 0f..1f)
            previous = q
        }
    }

    @Test
    fun `measured quality is derived from the real strings - word overlap`() {
        // Same words, different order: character match drops but word Jaccard keeps it high.
        val q = ProgressiveTransmission.measuredQuality("need help I", "I need help")
        assertTrue(q >= 0.8f)
        // Unrelated text: near zero.
        val low = ProgressiveTransmission.measuredQuality("railway station please come", "battery low")
        assertTrue(low < 0.6f)
    }

    @Test
    fun `script text quality still measures across missing tail`() {
        val full = "எனக்கு ரயில் நிலையம் அருகில் உதவி தேவை"
        val plan = ProgressiveTransmission.plan(full.toByteArray().size)
        val partial = ProgressiveTransmission.preview(full, plan)
        val q = ProgressiveTransmission.measuredQuality(full, partial)
        assertTrue("Tamil preview must carry measurable meaning", q >= 0.3f)
        assertTrue(q <= 1f)
    }
}