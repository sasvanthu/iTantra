package com.example.itantra.dispatch

import com.example.itantra.codec.Importance
import com.example.itantra.codec.Language
import com.example.itantra.codec.ProgressiveLayeredPipeline
import com.example.itantra.codec.ProgressiveReassembler
import com.example.itantra.codec.SutraFrame
import com.example.itantra.codec.SutraParser
import java.util.concurrent.atomic.AtomicLong

/**
 * Live semantic dispatch chain for a text utterance:
 *
 *   SutraParser -> SutraFrame (5-14 byte semantic intent)
 *   ProgressiveLayeredPipeline -> SUTRA layer 0 + Brahmic transcript layer 1
 *   ProgressiveReassembler -> ordered semantic-first reconstruction
 *
 * This is the component that "wires" the Phase 4..9 modules into the runtime
 * send path. VAD and raw-PCM streaming ASR stay at the audio boundary (no
 * bundled acoustic model / microphone): those are exercised in their own
 * component tests, never faked here.
 */
class SemanticDispatchRouter {

    private val messageCounter = AtomicLong(0L)
    private val reassembler = ProgressiveReassembler()

    data class Route(
        val messageId: Long,
        val sutra: SutraFrame,
        val transcript: String,
        val layerCount: Int,
        val summary: String
    ) {
        val isCriticalIntent: Boolean get() = sutra.isCritical
        val importance: Byte
            get() = if (sutra.isCritical) Importance.CRITICAL.level.toByte()
            else Importance.NORMAL.level.toByte()
    }

    /** Next semantic frame delivered for a completed utterance. */
    fun route(text: String, language: Language): Route {
        val messageId = messageCounter.incrementAndGet()
        val sutra = SutraParser.parse(text, language)
        val layers = ProgressiveLayeredPipeline.createLayers(
            messageId = messageId,
            text = text,
            language = language,
            isEmergency = sutra.isCritical
        )
        reassembler.feed(layers[0])
        val completed: ProgressiveReassembler.ProgressiveMessageState = reassembler.feed(layers[1])
        return Route(
            messageId = messageId,
            sutra = sutra,
            transcript = completed.transcript ?: text,
            layerCount = layers.size,
            summary = completed.summaryText
        )
    }

    fun reset() {
        messageCounter.set(0L)
        reassembler.reset()
    }
}