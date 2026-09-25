package com.example.itantra.speech.tts

import android.content.Context
import com.example.itantra.codec.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSRegistryTest {

    @Test
    fun `catalog covers all ten languages for every engine`() {
        assertEquals(10, TTSRegistry.catalogedLanguages().size)
        TTSRegistry.catalog.forEach { engine ->
            assertEquals(10, engine.languages.size)
        }
    }

    @Test
    fun `no voice data is bundled - everything is honest`() {
        TTSRegistry.catalog.forEach { engine ->
            engine.languages.values.forEach { capability ->
                // Android's depends on the device, open-source voices are missing from this APK.
                assertTrue(
                    "capability for ${engine.name} must not claim a shipped voice",
                    capability == TTSRegistry.VoiceCapability.DEVICE_PROVIDED ||
                        capability == TTSRegistry.VoiceCapability.NOT_BUNDLED
                )
            }
        }
    }

    @Test
    fun `best available engine for any language is the dev fallback, flagged`() {
        TTSRegistry.catalogedLanguages().forEach { language ->
            val (engine, fallback) = TTSRegistry.bestAvailableEngine(language)
            assertEquals(TTSRegistry.EngineKind.DEVICE_FALLBACK, engine.kind)
            assertTrue("no open-source voices are bundled yet, so fallback must be true", fallback)
        }
    }

    @Test
    fun `only the dev-fallback engine can speak today`() {
        val spoken = TTSRegistry.enginesThatCanSpeak(Language.HINDI)
        assertEquals(1, spoken.size)
        assertEquals(TTSRegistry.EngineKind.DEVICE_FALLBACK, spoken[0].kind)
        assertTrue(TTSRegistry.enginesThatCanSpeak(Language.MALAYALAM).isEmpty().not())
    }

    @Test
    fun `unknown language resolves like unsupported - no crash`() {
        val (engine, fallback) = TTSRegistry.bestAvailableEngine(Language.UNKNOWN)
        assertEquals(TTSRegistry.EngineKind.DEVICE_FALLBACK, engine.kind)
        assertTrue(fallback)
    }
}

/** In-memory fake [TTSEngine] for JVM tests; records every [speak] call. */
class RecordingTTSEngine : TTSEngine {
    val spoken = mutableListOf<Pair<String, String>>()
    var initialized = false

    override fun initialize(context: Context, language: Language, onReady: () -> Unit) {
        initialized = true
        onReady()
    }

    override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
        spoken.add(text to utteranceId)
    }

    override fun stop() {
        // no-op
    }

    override fun isInitialized(): Boolean = initialized

    override fun shutdown() {
        initialized = false
    }
}

class PresenceAwareTTSTest {

    @Test
    fun `speaking is forwarded when a listener is present`() {
        val delegate = RecordingTTSEngine()
        val aware = PresenceAwareTTS(delegate) { true }
        aware.speak("help")
        assertEquals(1, delegate.spoken.size)
        assertEquals("help", delegate.spoken[0].first)
        assertEquals(0, aware.suppressedSpeeches)
    }

    @Test
    fun `speaking is suppressed and accounted when nobody is present`() {
        val delegate = RecordingTTSEngine()
        val aware = PresenceAwareTTS(delegate) { false }
        aware.speak("help")
        aware.speak("again")
        assertEquals("no sound must reach the raw engine", 0, delegate.spoken.size)
        assertEquals(2, aware.suppressedSpeeches)
        assertEquals("no listener present", aware.lastSuppressionReason)
    }

    @Test
    fun `suppression and forwarding follow the live presence state`() {
        var present = false
        val delegate = RecordingTTSEngine()
        val aware = PresenceAwareTTS(delegate) { present }

        aware.speak("muted")
        assertEquals(1, aware.suppressedSpeeches)

        present = true
        aware.speak("now speaking")
        assertEquals(1, delegate.spoken.size)
        assertEquals("now speaking", delegate.spoken[0].first)
        assertEquals("previous suppression is not recounted", 1, aware.suppressedSpeeches)

        present = false
        aware.speak("muted again")
        assertEquals(2, aware.suppressedSpeeches)
    }

    @Test
    fun `lifecycle and state delegate through to the raw engine`() {
        val delegate = RecordingTTSEngine()
        val aware = PresenceAwareTTS(delegate) { true }
        aware.initialize(android.app.Application(), Language.TAMIL)
        assertTrue(aware.isInitialized())
        assertTrue(delegate.isInitialized())
        aware.stop()
        aware.shutdown()
        assertFalse(aware.isInitialized())
        assertFalse(delegate.isInitialized())
    }
}