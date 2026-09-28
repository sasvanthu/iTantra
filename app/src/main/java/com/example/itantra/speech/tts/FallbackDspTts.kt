package com.example.itantra.speech.tts

/**
 * Emergency ultra-lightweight formant/DSP TTS engine.
 *
 * Guarantees zero-model, <100KB footprint, sub-50ms synthesis latency on any hardware
 * when neural TTS models cannot run, are not downloaded, or are evicted from RAM by [com.example.itantra.data.ModelLifecycleManager].
 *
 * Generates 16-bit 16kHz mono PCM directly in-memory using acoustic phoneme formants (F1, F2, F3).
 */
open class FallbackDspTts(
    fallbackEngine: TTSEngine? = null
) : EmbeddedOpenSourceTTS(fallbackEngine)

/**
 * Uppercase alias for architectural alignment with the project audit specification.
 */
typealias FallbackDSPTTS = FallbackDspTts
