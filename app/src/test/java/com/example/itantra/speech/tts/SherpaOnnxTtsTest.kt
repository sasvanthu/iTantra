package com.example.itantra.speech.tts

import com.example.itantra.codec.Language
import com.example.itantra.data.ModelLifecycleManager
import com.example.itantra.data.ModelLifecycleState
import com.example.itantra.data.ModelManifest
import com.example.itantra.data.ModelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SherpaOnnxTtsTest {

    @Test
    fun `fallback DSP TTS generates 16kHz PCM audio without external model`() {
        val dspEngine = FallbackDspTts()
        dspEngine.initialize(Language.HINDI)

        val pcm = dspEngine.synthesizePcm("madad chahiye", Language.HINDI)
        assertNotNull(pcm)
        assertTrue("PCM should contain audio samples", pcm.isNotEmpty())
        assertEquals("PCM 16-bit audio must have even byte length", 0, pcm.size % 2)

        val uppercaseAlias: FallbackDSPTTS = dspEngine
        assertTrue(uppercaseAlias.isInitialized())
    }

    @Test
    fun `sherpa TTS gracefully falls back to DSP when neural model is missing`() {
        val lifecycleManager = ModelLifecycleManager()
        val sherpa = SherpaOnnxTtsEngine(
            modelLifecycleManager = lifecycleManager
        )
        sherpa.initialize(Language.ENGLISH)

        assertFalse("Neural model should not be reported ready without weights or synthesizer", sherpa.isNeuralReady())

        val pcm = sherpa.synthesizePcm("Emergency broadcast test", Language.ENGLISH)
        assertTrue("PCM should be generated via DSP fallback", pcm.isNotEmpty())
        assertEquals(0, pcm.size % 2)
        assertEquals(1, sherpa.fallbackUtteranceCount)
        assertEquals(0, sherpa.neuralUtteranceCount)
        assertEquals(SherpaOnnxTtsEngine.BACKEND_FALLBACK_DSP, sherpa.lastBackendUsed)
        assertTrue(sherpa.lastSynthesisDurationMs >= 0)
    }

    @Test
    fun `sherpa TTS uses neural synthesizer when active and tracks metrics`() {
        val lifecycleManager = ModelLifecycleManager()
        val sherpa = SherpaOnnxTtsEngine(
            modelLifecycleManager = lifecycleManager
        )

        // Mock neural synthesizer that generates synthetic 16kHz sine PCM
        sherpa.neuralSynthesizer = { text, _ ->
            ByteArray(3200) { 0x42.toByte() }
        }

        sherpa.initialize(Language.HINDI)
        assertTrue("Neural model should be ready", sherpa.isNeuralReady())

        val pcm = sherpa.synthesizePcm("namaste", Language.HINDI)
        assertEquals(3200, pcm.size)
        assertEquals(1, sherpa.neuralUtteranceCount)
        assertEquals(0, sherpa.fallbackUtteranceCount)
        assertEquals(SherpaOnnxTtsEngine.BACKEND_NEURAL, sherpa.lastBackendUsed)
    }

    @Test
    fun `sherpa TTS integrates with ModelLifecycleManager single active model rule`() {
        val lifecycleManager = ModelLifecycleManager()

        // 1. Initially activate STT
        val sttManifest = ModelManifest(
            modelId = "vosk-hi",
            language = Language.HINDI,
            kind = ModelManager.ModelKind.STT,
            version = "1.0",
            sha256 = "SKIP",
            expectedSizeBytes = 40_000_000L,
            runtime = "VOSK",
            quantization = "INT8",
            localFileName = "vosk-model-small-hi.zip"
        )
        lifecycleManager.acquireStt(sttManifest)
        assertEquals(ModelLifecycleState.READY, lifecycleManager.currentSttState)
        assertEquals(ModelLifecycleState.UNLOADED, lifecycleManager.currentTtsState)

        // 2. Initialize Sherpa TTS -> must automatically evict STT
        val sherpa = SherpaOnnxTtsEngine(
            modelLifecycleManager = lifecycleManager
        )
        sherpa.initialize(Language.HINDI)

        assertEquals(ModelLifecycleState.UNLOADED, lifecycleManager.currentSttState)
        assertEquals(ModelLifecycleState.READY, lifecycleManager.currentTtsState)

        // 3. Shutdown Sherpa -> must evict TTS
        sherpa.shutdown()
        assertEquals(ModelLifecycleState.UNLOADED, lifecycleManager.currentTtsState)
        assertFalse(sherpa.isInitialized())
    }

    @Test
    fun `sherpa TTS speak invokes onDone callback asynchronously`() {
        val sherpa = SherpaOnnxTtsEngine()
        sherpa.initialize(Language.ENGLISH)

        val latch = CountDownLatch(1)
        sherpa.speak("All systems functional", "msg-101") {
            latch.countDown()
        }

        val completed = latch.await(2, TimeUnit.SECONDS)
        assertTrue("onDone should be invoked after playback", completed)
    }

    @Test
    fun `registry factory methods instantiate proper engines`() {
        val dsp = TTSRegistry.createFallbackDspEngine()
        assertTrue(dsp is FallbackDspTts)

        val sherpa = TTSRegistry.createIntelligibleOfflineEngine()
        assertTrue(sherpa is SherpaOnnxTtsEngine)
    }
}
