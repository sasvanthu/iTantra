package com.example.itantra.codec

import com.example.itantra.data.AdaptiveBandwidth
import com.example.itantra.transport.AdaptiveLinkGovernor
import com.example.itantra.transport.LinkMetrics
import com.example.itantra.transport.PriorityFrameQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveLayeredPipelineTest {

    @Test
    fun `layered packet serialization roundtrips accurately`() {
        val original = LayeredPacket(
            messageId = 12345L,
            layerId = 0,
            totalLayers = 2,
            payload = byteArrayOf(0x53, 0x01, 0x01, 0x01, 0x50, 0x00),
            priority = 3
        )

        val bytes = LayeredPacket.serialize(original)
        assertTrue(bytes.size >= 16)

        val decoded = LayeredPacket.deserialize(bytes)
        assertNotNull(decoded)
        assertEquals(original.messageId, decoded!!.messageId)
        assertEquals(original.layerId, decoded.layerId)
        assertEquals(original.totalLayers, decoded.totalLayers)
        assertEquals(original.priority, decoded.priority)
        assertTrue(original.payload.contentEquals(decoded.payload))
    }

    @Test
    fun `emergency message produces high priority layer 0 SUTRA and layer 1 Brahmic`() {
        val text = "Trapped in room 4 people emergency"
        val layers = ProgressiveLayeredPipeline.createLayers(
            messageId = 1001L,
            text = text,
            language = Language.ENGLISH,
            isEmergency = true
        )

        assertEquals(2, layers.size)
        val layer0 = layers[0]
        val layer1 = layers[1]

        assertEquals(0, layer0.layerId)
        assertEquals(3, layer0.priority) // CRITICAL priority
        assertTrue("Layer 0 SUTRA frame must be compact (<20 bytes)", layer0.payload.size < 20)

        assertEquals(1, layer1.layerId)
        assertEquals(1, layer1.priority) // Normal transcript priority
        assertTrue("Layer 1 Brahmic must contain payload", layer1.payload.isNotEmpty())
    }

    @Test
    fun `layer 0 pre-empts lower priority frames in priority frame queue`() = runBlocking {
        val queue = PriorityFrameQueue()

        // 1. Enqueue bulk lower-priority traffic (Priority 1)
        val bulkPacket = byteArrayOf(0x01, 0x02, 0x03)
        queue.enqueue(bulkPacket, priority = 1)

        // 2. Enqueue urgent Layer 0 emergency packet (Priority 3)
        val emergencyLayer0 = byteArrayOf(0x50, 0x99.toByte())
        queue.enqueue(emergencyLayer0, priority = 3)

        // 3. Dequeue: Priority 3 MUST be delivered before Priority 1!
        val firstOut = queue.take()
        assertTrue("Emergency Layer 0 frame must jump queue", firstOut.contentEquals(emergencyLayer0))

        val secondOut = queue.take()
        assertTrue("Bulk frame delivered after emergency", secondOut.contentEquals(bulkPacket))
    }

    @Test
    fun `adaptive link governor adjusts layer 1 priority under congestion`() {
        val governor = AdaptiveLinkGovernor()

        // Simulate severe packet loss (40% loss, 500ms RTT)
        val degradedMetrics = LinkMetrics(
            packetsSent = 100,
            packetLoss = 40,
            roundTripTimeMs = 500,
            retransmissions = 35
        )
        repeat(AdaptiveLinkGovernor.DEFAULT_HYSTERESIS_SAMPLES) {
            governor.observe(degradedMetrics)
        }

        val layers = ProgressiveLayeredPipeline.createLayers(
            messageId = 2002L,
            text = "Need water urgently",
            language = Language.ENGLISH,
            isEmergency = true,
            linkGovernor = governor
        )

        val layer0 = layers[0]
        val layer1 = layers[1]

        assertEquals(3, layer0.priority) // Layer 0 remains P0 emergency
        assertEquals(0, layer1.priority) // Layer 1 is deferred (P0 background) under congestion
    }

    @Test
    fun `progressive reassembler delivers instant semantic preview then full text`() {
        val reassembler = ProgressiveReassembler()
        val text = "Trapped in room 3 people emergency"

        val layers = ProgressiveLayeredPipeline.createLayers(
            messageId = 3003L,
            text = text,
            language = Language.ENGLISH,
            isEmergency = true
        )

        // 1. Ingest Layer 0
        val state1 = reassembler.feed(layers[0])
        assertTrue("Layer 0 must be marked received", state1.layer0Received)
        assertFalse("Message is not complete yet", state1.isComplete)
        assertNotNull(state1.sutraFrame)
        assertEquals(SutraIntent.TRAPPED, state1.sutraFrame!!.intent)
        assertTrue(state1.summaryText.contains("TRAPPED"))

        // 2. Ingest Layer 1
        val state2 = reassembler.feed(layers[1])
        assertTrue("Layer 1 must be marked received", state2.layer1Received)
        assertTrue("Message must now be complete", state2.isComplete)
        assertEquals(text, state2.transcript)
        assertEquals(1.0f, state2.estimatedQuality, 0.01f)
    }

    @Test
    fun `out of order layer arrival completes gracefully`() {
        val reassembler = ProgressiveReassembler()
        val text = "Need food supplies 10 units"

        val layers = ProgressiveLayeredPipeline.createLayers(
            messageId = 4004L,
            text = text,
            language = Language.ENGLISH,
            isEmergency = false
        )

        // Ingest Layer 1 first
        val s1 = reassembler.feed(layers[1])
        assertTrue(s1.layer1Received)
        assertTrue(s1.isComplete)
        assertEquals(text, s1.transcript)

        // Then Layer 0 arrives late
        val s2 = reassembler.feed(layers[0])
        assertTrue(s2.layer0Received)
        assertTrue(s2.isComplete)
    }

    @Test
    fun `layer 2 enhancement is opt-in and rides the background band`() {
        val context = BrahmicCodec.encode("whispered, wind noise high", Language.ENGLISH)
        val withEnhancement = ProgressiveLayeredPipeline.createLayers(
            messageId = 5005L,
            text = "Need water",
            language = Language.HINDI,
            isEmergency = false,
            contextPayload = context
        )
        assertEquals(3, withEnhancement.size)
        val layer2 = withEnhancement[2]
        assertEquals(2, layer2.layerId)
        assertEquals(3, layer2.totalLayers)
        assertEquals(0, layer2.priority) // background, never required

        // Without enhancement the default two-layer wire form is unchanged.
        val plain = ProgressiveLayeredPipeline.createLayers(
            messageId = 5006L,
            text = "Need water",
            language = Language.HINDI,
            isEmergency = false
        )
        assertEquals(2, plain.size)
        assertEquals(2, plain[0].totalLayers)
        assertEquals(2, plain[1].totalLayers)
    }

    @Test
    fun `losing layer 2 never corrupts or blocks the base message`() {
        val reassembler = ProgressiveReassembler()
        val context = BrahmicCodec.encode("noisy rooftop", Language.ENGLISH)

        // Layer 2 arrives FIRST and alone: must not force completeness.
        val ctxOnly = reassembler.feed(
            LayeredPacket(
                messageId = 6006L,
                layerId = 2,
                totalLayers = 3,
                payload = context,
                priority = 0
            )
        )
        assertTrue(ctxOnly.layer2Received)
        assertFalse(ctxOnly.isComplete)

        // Layer 1 arrives: message completes WITHOUT any layer 2.
        val layers = ProgressiveLayeredPipeline.createLayers(
            messageId = 6006L,
            text = "Trapped outside building",
            language = Language.ENGLISH,
            isEmergency = true,
            contextPayload = context
        )
        val justCore = reassembler.feed(layers[1])
        assertTrue(justCore.isComplete)
        assertEquals("Trapped outside building", justCore.transcript)
        assertTrue(justCore.layer1Received)
    }

    @Test
    fun `out of order layer 2 attaches context without downgrading completeness`() {
        val reassembler = ProgressiveReassembler()
        val layers = ProgressiveLayeredPipeline.createLayers(
            messageId = 7007L,
            text = "Medical help needed",
            language = Language.ENGLISH,
            isEmergency = false,
            contextPayload = BrahmicCodec.encode("doctor on call", Language.ENGLISH)
        )

        val full = reassembler.feed(layers[1])
        assertTrue(full.isComplete)
        assertEquals("Medical help needed", full.transcript)

        val withContext = reassembler.feed(layers[2])
        assertTrue(withContext.isComplete)
        assertEquals("doctor on call", withContext.context)
        assertEquals(1.0f, withContext.estimatedQuality, 0.01f)
        assertEquals("Medical help needed", withContext.transcript)
    }
}
