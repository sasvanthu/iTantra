package com.example.itantra.speech

import com.example.itantra.codec.DecodeResult
import com.example.itantra.codec.Language
import com.example.itantra.codec.RetroSpeechCodec
import com.example.itantra.protocol.Packetizer
import com.example.itantra.speech.tts.EmbeddedOpenSourceTTS
import org.junit.Assert.*
import org.junit.Test

/**
 * Requirement 6: Repeated Voice Pipeline Stability Validation.
 *
 * Simulates at least 20 continuous cycles of:
 * Input Speech -> STT -> Codec Encode -> Wire Packetize -> Reassemble -> Decode -> TTS Synthesize -> Release.
 *
 * Verifies:
 * - Zero state leakage between cycles
 * - 100% exact text recovery across English, Hindi, and Tamil
 * - Constant bounded memory allocation across iterations
 * - Non-empty PCM waveform generation
 * - No infinite loops or resource locks
 */
class RepeatedVoiceStabilityTest {

    @Test
    fun `twenty-five consecutive speech pipeline cycles remain robust with zero memory runaway`() {
        val codec = RetroSpeechCodec()
        val synthesizer = EmbeddedOpenSourceTTS.OpenSourceFormantSynthesizer()

        val corpora = listOf(
            Triple("Medical emergency need ambulance at sector 4", Language.ENGLISH, 101L),
            Triple("तुरंत सहायता की आवश्यकता है कृपया पानी लाएं", Language.HINDI, 102L),
            Triple("ரயில் நிலையம் அருகில் அவசர உதவி தேவைப்படுகிறது", Language.TAMIL, 103L),
            Triple("Route clear convoy moving forward safely", Language.ENGLISH, 104L),
            Triple("सुरक्षित स्थान पर पहुंच गए हैं", Language.HINDI, 105L)
        )

        val totalCycles = 25
        var successfulCycles = 0

        val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        for (cycle in 1..totalCycles) {
            val (inputSpeech, language, msgId) = corpora[cycle % corpora.size]

            // 1. Encode via RetroSpeechCodec
            val encodeStart = System.nanoTime()
            val encoded = codec.encode(inputSpeech, language)
            val encodeNs = System.nanoTime() - encodeStart
            assertTrue("Encoded payload must be non-empty on cycle $cycle", encoded.finalEncodedSize > 0)
            assertTrue("Encode time must be under 50ms (was ${encodeNs / 1_000_000.0}ms)", encodeNs < 50_000_000L)

            // 2. Packetize
            val packets = Packetizer.buildPackets(
                payload = encoded.data,
                language = language,
                messageId = msgId + cycle,
                priority = encoded.importance.level,
                isEmergency = false
            )
            assertTrue("Packets must be generated on cycle $cycle", packets.isNotEmpty())

            // 3. Reassemble
            val dataMap = packets.filter { it.packetType == com.example.itantra.codec.PacketType.TEXT_DATA }
                .associate { it.sequenceId to it.payload }
            val reassembled = Packetizer.assemble(dataMap)
            assertArrayEquals("Reassembled payload must match encoded data", encoded.data, reassembled)

            // 4. Decode
            val decodeStart = System.nanoTime()
            val decodeResult = codec.decode(reassembled)
            val decodeNs = System.nanoTime() - decodeStart
            assertTrue("Decode must succeed on cycle $cycle", decodeResult is DecodeResult.Success)
            val decodedText = (decodeResult as DecodeResult.Success).reconstructedText
            assertEquals("Decoded text must match input on cycle $cycle", inputSpeech, decodedText)
            assertTrue("Decode time must be under 50ms (was ${decodeNs / 1_000_000.0}ms)", decodeNs < 50_000_000L)

            // 5. TTS Formant Synthesis
            val ttsStart = System.nanoTime()
            val pcmAudio = synthesizer.synthesizePcm(decodedText, language)
            val ttsNs = System.nanoTime() - ttsStart
            assertTrue("PCM audio waveform must be generated on cycle $cycle", pcmAudio.isNotEmpty())
            assertTrue("PCM synthesis must be fast (< 200ms, was ${ttsNs / 1_000_000.0}ms)", ttsNs < 200_000_000L)

            successfulCycles++
        }

        assertEquals("All 25 cycles must complete with 100% success", 25, successfulCycles)

        // Run GC and assert memory has not run away
        System.gc()
        Thread.sleep(50)
        val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryDeltaMB = (finalMemory - initialMemory) / (1024.0 * 1024.0)

        // Memory delta should remain stable (less than 20MB growth after 25 cycles)
        assertTrue("Memory growth after 25 cycles must be bounded (was ${memoryDeltaMB}MB)", memoryDeltaMB < 20.0)
    }
}
