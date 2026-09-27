package com.example.itantra.transport

import com.example.itantra.codec.*
import com.example.itantra.protocol.Packet
import com.example.itantra.protocol.Packetizer
import com.example.itantra.speech.tts.PresenceAwareTTS
import com.example.itantra.speech.tts.TTSEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end integration tests verifying the complete iTantra speech, packet,
 * codec, transport, emergency priority, and mesh relay pipeline.
 */
class EndToEndVoiceAndPriorityPipelineTest {

    private lateinit var codec: RetroSpeechCodec

    class TestTTSEngine : TTSEngine {
        val spoken = mutableListOf<Pair<String, String>>() // text, utteranceId
        var initialized = false

        override fun initialize(context: android.content.Context, language: Language, onReady: () -> Unit) {
            initialized = true
            onReady()
        }

        override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
            spoken.add(text to utteranceId)
            onDone?.invoke()
        }

        override fun stop() {}
        override fun isInitialized(): Boolean = initialized
        override fun shutdown() { initialized = false }
    }

    @Before
    fun setup() {
        codec = RetroSpeechCodec()
    }

    @Test
    fun `end to end voice pipeline - english, tamil, hindi encode packetize reassemble decode speak`() {
        val testCases = listOf(
            "Emergency near the highway need immediate medical assistance." to Language.ENGLISH,
            "எனக்கு அவசர உதவி தேவைப்படுகிறது உடனே வாருங்கள்." to Language.TAMIL,
            "मुझे तुरंत सहायता की आवश्यकता है कृपया पानी लाएं।" to Language.HINDI
        )

        val ttsDelegate = TestTTSEngine().apply { initialized = true }
        val presenceTts = PresenceAwareTTS(ttsDelegate) { true }

        for ((inputSpeech, language) in testCases) {
            // Stage 1: Text from STT
            val spokenText = inputSpeech

            // Stage 2: Codec Encoding
            val encoded = codec.encode(spokenText, language)
            assertTrue("Encoded size must be non-zero", encoded.finalEncodedSize > 0)

            // Stage 3: Packetization with START / DATA / END frames
            val packets = Packetizer.buildPackets(
                payload = encoded.data,
                language = language,
                messageId = 42L,
                priority = encoded.importance.level,
                isEmergency = false
            )
            assertTrue("Must produce at least START, DATA, and END packets", packets.size >= 3)

            // Stage 4: Wire Serialization and Deserialization (with CRC check)
            val receivedPackets = mutableListOf<Packet>()
            for (p in packets) {
                val wireBytes = p.serialize()
                val parsed = Packet.deserialize(wireBytes)
                assertNotNull("Packet CRC verification must succeed", parsed)
                receivedPackets.add(parsed!!)
            }

            // Stage 5: Packet Reassembly
            val dataParts = mutableMapOf<Int, ByteArray>()
            for (p in receivedPackets) {
                if (p.packetType == PacketType.TEXT_DATA) {
                    dataParts[p.sequenceId] = p.payload
                }
            }
            val reassembledPayload = Packetizer.assemble(dataParts)
            assertArrayEquals("Reassembled binary payload must match original encoded data", encoded.data, reassembledPayload)

            // Stage 6: Decoding
            val decodeResult = codec.decode(reassembledPayload)
            assertTrue("Decode must succeed", decodeResult is DecodeResult.Success)
            val reconstructed = (decodeResult as DecodeResult.Success).reconstructedText
            assertEquals("Reconstructed text must match original spoken text", spokenText, reconstructed)

            // Stage 7: TTS output
            val utteranceId = "recv-42-${System.nanoTime()}"
            presenceTts.speak(reconstructed, utteranceId)
            assertEquals("TTS must receive and speak reconstructed text", spokenText, ttsDelegate.spoken.last().first)
        }
    }

    @Test
    fun `emergency message has highest priority and bypasses silence or absence`() {
        val emergencyText = "CRITICAL: BRIDGE COLLAPSE REPORTED AT KM 14"
        val ttsDelegate = TestTTSEngine().apply { initialized = true }
        val presenceTts = PresenceAwareTTS(ttsDelegate) { false } // NO listener present!

        // Standard message is suppressed
        presenceTts.speak("Standard status update", "recv-100-1")
        assertEquals("Standard message must be suppressed when no presence", 0, ttsDelegate.spoken.size)
        assertEquals(1, presenceTts.suppressedSpeeches)

        // Emergency packet generated
        val encoded = codec.encode(emergencyText, Language.ENGLISH)
        val packets = Packetizer.buildPackets(
            payload = encoded.data,
            language = Language.ENGLISH,
            messageId = 999L,
            priority = Importance.CRITICAL.level,
            isEmergency = true
        )

        // Verify emergency packets carry critical priority
        val dataPackets = packets.filter { it.packetType == PacketType.TEXT_DATA && it.priority == 3.toByte() }
        assertTrue("Must contain critical priority packet", dataPackets.isNotEmpty())

        // Receiver decodes and speaks with emergency utteranceId
        val reassembled = Packetizer.assemble(dataPackets.associate { it.sequenceId to it.payload })
        val decoded = (codec.decode(reassembled) as DecodeResult.Success).reconstructedText

        val emergencyUtteranceId = "emerg-999-${System.nanoTime()}"
        presenceTts.speak(decoded, emergencyUtteranceId)

        assertEquals("Emergency utterance must bypass presence suppression", 1, ttsDelegate.spoken.size)
        assertEquals(emergencyText, ttsDelegate.spoken.first().first)
        assertEquals(emergencyUtteranceId, ttsDelegate.spoken.first().second)
        assertEquals("Suppression count must not increment for emergency", 1, presenceTts.suppressedSpeeches)
    }

    @Test
    fun `corrupted wire packet is rejected by CRC check`() {
        val payload = "Testing packet CRC integrity".toByteArray(Charsets.UTF_8)
        val packet = Packet.createTextPacket(
            messageId = 123L,
            sequenceId = 1,
            language = Language.ENGLISH,
            payload = payload,
            priority = 2
        )
        val wireBytes = packet.serialize()
        assertNotNull("Valid packet must deserialize successfully", Packet.deserialize(wireBytes))

        // Corrupt a single byte in the payload
        val corruptedBytes = wireBytes.copyOf()
        val payloadOffset = 25 // Header is 25 bytes
        corruptedBytes[payloadOffset] = (corruptedBytes[payloadOffset].toInt() xor 0xFF).toByte()

        val parsedCorrupted = Packet.deserialize(corruptedBytes)
        assertNull("Corrupted packet must fail CRC check and return null", parsedCorrupted)
    }

    @Test
    fun `ptt walkie talkie press and hold state lifecycle simulation`() {
        var isPTTMode = true
        var isRecording = false
        var sentUtterances = mutableListOf<String>()

        fun onPress(onStartMic: () -> Unit) {
            isRecording = true
            onStartMic()
        }

        fun onRelease(transcribedText: String, onSend: (String) -> Unit) {
            isRecording = false
            if (isPTTMode && transcribedText.isNotBlank()) {
                onSend(transcribedText)
            }
        }

        // Simulate press
        var micActive = false
        onPress { micActive = true }
        assertTrue("Microphone must be active during hold", micActive)
        assertTrue("Recording state must be active during hold", isRecording)

        // Simulate speech transcription while held
        val capturedSpeech = "Walkie talkie testing channel 1"

        // Simulate release
        onRelease(capturedSpeech) { text ->
            sentUtterances.add(text)
            micActive = false
        }

        assertFalse("Microphone must be released", micActive)
        assertFalse("Recording must stop on release", isRecording)
        assertEquals(1, sentUtterances.size)
        assertEquals(capturedSpeech, sentUtterances[0])
    }
}
