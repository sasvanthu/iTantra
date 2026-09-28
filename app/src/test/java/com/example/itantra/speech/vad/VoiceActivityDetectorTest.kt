package com.example.itantra.speech.vad

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceActivityDetectorTest {

    private fun generateSineFrame(frequencyHz: Float, amplitude: Short, numSamples: Int = 320): ShortArray {
        val frame = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / 16000.0
            frame[i] = (sin(2.0 * PI * frequencyHz * t) * amplitude).toInt().coerceIn(-32767, 32767).toShort()
        }
        return frame
    }

    private fun generateSilenceFrame(numSamples: Int = 320): ShortArray {
        return ShortArray(numSamples) // All zeros
    }

    private fun generateNoiseFrame(amplitude: Short = 80, numSamples: Int = 320): ShortArray {
        val frame = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            frame[i] = ((Math.random() * 2.0 - 1.0) * amplitude).toInt().toShort()
        }
        return frame
    }

    @Test
    fun `pure silence produces SILENCE state`() {
        val vad = AdaptiveEnergyVad()
        val silence = generateSilenceFrame()

        val result = vad.process(silence)
        assertEquals(VadState.SILENCE, result.state)
        assertEquals(0f, result.energyRms, 0.001f)
        assertFalse(result.isEndpointDetected)
    }

    @Test
    fun `speech tone transitions from MAYBE_SPEECH to SPEECH`() {
        val vad = AdaptiveEnergyVad()
        val speechFrame = generateSineFrame(frequencyHz = 350f, amplitude = 4000)

        // First frame: onset detection
        val r1 = vad.process(speechFrame)
        assertTrue(r1.state == VadState.MAYBE_SPEECH || r1.state == VadState.SPEECH)

        // Sustained speech frames must transition firmly to SPEECH
        var confirmedSpeech = false
        repeat(5) {
            val r = vad.process(speechFrame)
            if (r.state == VadState.SPEECH) confirmedSpeech = true
        }
        assertTrue("Sustained voice frame must produce SPEECH state", confirmedSpeech)
        assertTrue(vad.isSpeaking())
    }

    @Test
    fun `speech to silence transitions and detects endpoint with measured latency`() {
        val vad = AdaptiveEnergyVad(aggressiveness = AdaptiveEnergyVad.Aggressiveness.BALANCED)
        val speechFrame = generateSineFrame(frequencyHz = 300f, amplitude = 5000) // 20 ms @ 16kHz
        val silenceFrame = generateNoiseFrame(amplitude = 60)

        // 1. Speak for 500 ms (25 frames of 20 ms each)
        repeat(25) {
            vad.process(speechFrame)
        }
        assertTrue("VAD must be in speaking state during speech", vad.isSpeaking())

        // 2. User stops speaking. Feed silence until endpoint is triggered.
        var endpointFrameIndex = -1
        for (frameIdx in 1..40) {
            val res = vad.process(silenceFrame)
            if (res.isEndpointDetected) {
                endpointFrameIndex = frameIdx
                break
            }
        }

        assertTrue("VAD must detect endpoint after user stops speaking", endpointFrameIndex > 0)
        assertFalse("VAD must reset speaking flag after endpoint", vad.isSpeaking())

        // Measured endpoint latency
        val endpointLatencyMs = endpointFrameIndex * 20L
        println("=== VAD ENDPOINT MEASUREMENT ===")
        println("Measured Endpoint Hangover Latency: ${endpointLatencyMs} ms (${endpointFrameIndex} frames)")
        println("================================")

        assertTrue("Endpoint latency must be between 200 ms and 500 ms", endpointLatencyMs in 200..500)
    }

    @Test
    fun `ambient background noise adapts noise floor without false triggering speech`() {
        val vad = AdaptiveEnergyVad()
        val lowNoise = generateNoiseFrame(amplitude = 100)

        // Process 30 frames of quiet ambient noise
        repeat(30) {
            val res = vad.process(lowNoise)
            assertEquals("Steady background noise must remain SILENCE", VadState.SILENCE, res.state)
            assertFalse(res.isEndpointDetected)
        }
        assertFalse(vad.isSpeaking())
    }

    @Test
    fun `soft speech is detected with GENTLE aggressiveness`() {
        val gentleVad = AdaptiveEnergyVad(aggressiveness = AdaptiveEnergyVad.Aggressiveness.GENTLE)
        val softSpeech = generateSineFrame(frequencyHz = 280f, amplitude = 600) // Low amplitude

        var detected = false
        repeat(10) {
            val res = gentleVad.process(softSpeech)
            if (res.state == VadState.SPEECH || res.state == VadState.MAYBE_SPEECH) {
                detected = true
            }
        }
        assertTrue("Soft speech must be detected under GENTLE aggressiveness", detected)
    }

    @Test
    fun `short utterance detects speech and endpoint cleanly`() {
        val vad = AdaptiveEnergyVad()
        val speech = generateSineFrame(frequencyHz = 400f, amplitude = 6000)
        val silence = generateSilenceFrame()

        // Short command (100 ms = 5 frames)
        repeat(5) { vad.process(speech) }
        assertTrue(vad.isSpeaking())

        // Trailing silence
        var endpointFound = false
        repeat(30) {
            if (vad.process(silence).isEndpointDetected) endpointFound = true
        }
        assertTrue("Short utterance must trigger endpoint cleanly", endpointFound)
    }

    @Test
    fun `long continuous speech stays in SPEECH state without premature endpoint`() {
        val vad = AdaptiveEnergyVad()
        val speech = generateSineFrame(frequencyHz = 320f, amplitude = 4500)

        // 300 frames of speech (6 seconds)
        for (i in 0 until 300) {
            val res = vad.process(speech)
            assertFalse("Continuous speech must not trigger premature endpoint at frame $i", res.isEndpointDetected)
        }
        assertTrue(vad.isSpeaking())
    }

    @Test
    fun `reset flushes VAD state cleanly`() {
        val vad = AdaptiveEnergyVad()
        val speech = generateSineFrame(frequencyHz = 350f, amplitude = 5000)

        repeat(10) { vad.process(speech) }
        assertTrue(vad.isSpeaking())

        vad.reset()
        assertFalse(vad.isSpeaking())

        val res = vad.process(generateSilenceFrame())
        assertEquals(VadState.SILENCE, res.state)
    }
}
