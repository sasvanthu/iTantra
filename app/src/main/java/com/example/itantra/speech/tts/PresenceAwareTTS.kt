package com.example.itantra.speech.tts

import android.content.Context
import com.example.itantra.codec.Language

/**
 * Phase 18 — presence-driven TTS.
 *
 * The device only speaks when a human listener is actually present. [knowsOfPresence]
 * is the single hook the host app supplies (screen on, speaker enabled, a peer
 * session in progress, motion sensor — the host decides, honestly). When the
 * hook reports no listener, incoming utterances are NOT synthesized into sound;
 * they are counted in [suppressedSpeeches] so the operator can see what was
 * withheld rather than the device pretending to have spoken.
 *
 * Implements [TTSEngine] so the receive path in
 * [com.example.itantra.SpeechPipeline] needs no change: swap this in where the
 * raw engine is injected.
 */
class PresenceAwareTTS(
    private val delegate: TTSEngine,
    private val knowsOfPresence: () -> Boolean
) : TTSEngine {

    var suppressedSpeeches: Int = 0
        private set

    var lastSuppressionReason: String = ""
        private set

    override fun initialize(context: Context, language: Language, onReady: () -> Unit) {
        delegate.initialize(context, language, onReady)
    }

    override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
        if (!knowsOfPresence()) {
            suppressedSpeeches++
            lastSuppressionReason = "no listener present"
            // Nothing reached the speaker; complete immediately so the caller's
            // latency bookkeeping does not block on a speech that never ran.
            onDone?.invoke()
            return
        }
        delegate.speak(text, utteranceId, onDone)
    }

    override fun stop() {
        delegate.stop()
    }

    override fun isInitialized(): Boolean = delegate.isInitialized()

    override fun shutdown() {
        delegate.shutdown()
    }
}