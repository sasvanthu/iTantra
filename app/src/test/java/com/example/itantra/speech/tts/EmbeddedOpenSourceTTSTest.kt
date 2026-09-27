package com.example.itantra.speech.tts

import com.example.itantra.codec.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EmbeddedOpenSourceTTSTest {

    @Test
    fun `formant synthesizer generates valid 16-bit 16kHz PCM audio for English`() {
        val synthesizer = EmbeddedOpenSourceTTS.OpenSourceFormantSynthesizer()
        val pcm = synthesizer.synthesizePcm("Hello world this is iTantra emergency broadcast", Language.ENGLISH)
        assertNotNull(pcm)
        assertTrue("PCM output must not be empty", pcm.isNotEmpty())
        assertEquals("PCM 16-bit audio must have even byte length", 0, pcm.size % 2)
        val numSamples = pcm.size / 2
        assertTrue("Should have synthesized at least 1000 audio samples", numSamples > 1000)
    }

    @Test
    fun `formant synthesizer generates valid PCM audio for Indic languages`() {
        val synthesizer = EmbeddedOpenSourceTTS.OpenSourceFormantSynthesizer()
        val hindiPcm = synthesizer.synthesizePcm("namaste madad chahiye", Language.HINDI)
        assertTrue(hindiPcm.isNotEmpty())
        assertEquals(0, hindiPcm.size % 2)

        val tamilPcm = synthesizer.synthesizePcm("vanakkam udhavi thevai", Language.TAMIL)
        assertTrue(tamilPcm.isNotEmpty())
        assertEquals(0, tamilPcm.size % 2)
    }

    @Test
    fun `empty text yields empty PCM without crashing`() {
        val synthesizer = EmbeddedOpenSourceTTS.OpenSourceFormantSynthesizer()
        val emptyPcm = synthesizer.synthesizePcm("   ", Language.ENGLISH)
        assertEquals(0, emptyPcm.size)
    }

    @Test
    fun `embedded engine initializes, tracks language and reports initialized state`() {
        val engine = EmbeddedOpenSourceTTS()
        assertFalse(engine.isInitialized())

        var readyCalled = false
        engine.initialize(Language.HINDI) {
            readyCalled = true
        }

        assertTrue(engine.isInitialized())
        assertTrue(readyCalled)
    }

    @Test
    fun `speak triggers completion callback even in headless test environment`() {
        val engine = EmbeddedOpenSourceTTS()
        engine.initialize(Language.ENGLISH)

        val latch = CountDownLatch(1)
        engine.speak("short alert", "test-1") {
            latch.countDown()
        }

        val completed = latch.await(2, TimeUnit.SECONDS)
        assertTrue("onDone callback should be triggered", completed)
    }

    @Test
    fun `stop and shutdown clean up state gracefully`() {
        val engine = EmbeddedOpenSourceTTS()
        engine.initialize(Language.TAMIL)
        assertTrue(engine.isInitialized())

        engine.stop()
        assertTrue(engine.isInitialized())

        engine.shutdown()
        assertFalse(engine.isInitialized())
    }

    @Test
    fun `tts registry provides embedded open source engine marked bundled`() {
        val engine = TTSRegistry.embeddedEngine
        assertEquals(TTSRegistry.EngineKind.OPEN_SOURCE_EMBEDDED, engine.kind)
        assertEquals(TTSRegistry.VoiceCapability.BUNDLED, engine.capability(Language.ENGLISH))
        assertEquals(TTSRegistry.VoiceCapability.BUNDLED, engine.capability(Language.HINDI))
        assertEquals(TTSRegistry.VoiceCapability.BUNDLED, engine.capability(Language.TAMIL))

        val created = TTSRegistry.createEngine(preferOpenSource = true)
        assertTrue(created is EmbeddedOpenSourceTTS)
    }
}
