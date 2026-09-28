package com.example.itantra.speech.tts

import android.content.Context
import android.util.Log
import com.example.itantra.codec.Language
import com.example.itantra.data.ModelLifecycleManager
import com.example.itantra.data.ModelLifecycleState
import com.example.itantra.data.ModelManifest
import com.example.itantra.data.ModelManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-intelligibility offline neural TTS engine.
 *
 * Implements [TTSEngine] with:
 * 1. Sherpa-ONNX / Piper neural acoustic model abstraction (Matcha-TTS, VITS).
 * 2. Memory governance through [ModelLifecycleManager], enforcing single-active-model
 *    residency to fit within the 4 GB RAM edge budget.
 * 3. Graceful instant fallback to [FallbackDspTts] (<100KB, 0 model weights)
 *    when neural weights are missing, evicted, or unsupported on the current device.
 */
class SherpaOnnxTtsEngine(
    private val modelLifecycleManager: ModelLifecycleManager? = null,
    val modelManifest: ModelManifest? = null,
    val fallbackEngine: FallbackDspTts = FallbackDspTts()
) : TTSEngine {

    companion object {
        private const val TAG = "SherpaOnnxTTS"
        const val BACKEND_NEURAL = "NEURAL_SHERPA_ONNX"
        const val BACKEND_FALLBACK_DSP = "FALLBACK_DSP_FORMANT"
    }

    private var initialized = false
    private var currentLanguage: Language = Language.ENGLISH
    private val isStopped = AtomicBoolean(false)

    /** Pending utterance callbacks for completion signaling. */
    private val pendingDone = ConcurrentHashMap<String, () -> Unit>()

    /** Pluggable neural synthesizer lambda for testing or custom ONNX runtime injection. */
    var neuralSynthesizer: ((text: String, language: Language) -> ByteArray?)? = null

    // Telemetry & metrics
    var lastSynthesisDurationMs: Long = 0L
        private set
    var neuralUtteranceCount: Int = 0
        private set
    var fallbackUtteranceCount: Int = 0
        private set
    var lastBackendUsed: String = BACKEND_FALLBACK_DSP
        private set

    private fun safeLog(priority: Int, msg: String) {
        try {
            when (priority) {
                Log.DEBUG -> Log.d(TAG, msg)
                Log.INFO -> Log.i(TAG, msg)
                Log.WARN -> Log.w(TAG, msg)
                Log.ERROR -> Log.e(TAG, msg)
            }
        } catch (_: Throwable) {
            // Android Log is not mocked in local JVM unit tests
        }
    }

    override fun initialize(context: Context, language: Language, onReady: () -> Unit) {
        currentLanguage = language
        isStopped.set(false)
        fallbackEngine.initialize(context, language)
        acquireLifecycleResidency()
        initialized = true
        safeLog(Log.INFO, "[TTS] SherpaOnnxTtsEngine initialized for $language (Neural ready: ${isNeuralReady()})")
        onReady()
    }

    /** Context-free initialization for testing or standalone embedded environments. */
    fun initialize(language: Language, onReady: () -> Unit = {}) {
        currentLanguage = language
        isStopped.set(false)
        fallbackEngine.initialize(language)
        acquireLifecycleResidency()
        initialized = true
        onReady()
    }

    private fun acquireLifecycleResidency() {
        if (modelLifecycleManager == null) return

        val manifest = modelManifest ?: ModelManifest(
            modelId = "sherpa-onnx-tts-${currentLanguage.name.lowercase()}",
            language = currentLanguage,
            kind = ModelManager.ModelKind.TTS,
            version = "1.0",
            sha256 = "SKIP",
            expectedSizeBytes = 25_000_000L,
            runtime = "ONNX-Matcha",
            quantization = "INT8",
            localFileName = "sherpa_tts_${currentLanguage.name.lowercase()}.onnx"
        )
        val success = modelLifecycleManager.acquireTts(manifest)
        if (!success) {
            safeLog(Log.WARN, "Failed to acquire TTS model in ModelLifecycleManager (ASR active)")
        }
    }

    /**
     * Determines whether the neural inference pipeline is available and resident in RAM.
     */
    fun isNeuralReady(): Boolean {
        if (modelLifecycleManager != null) {
            val state = modelLifecycleManager.currentTtsState
            if (state != ModelLifecycleState.READY && state != ModelLifecycleState.ACTIVE) {
                return false
            }
        }
        return neuralSynthesizer != null
    }

    /**
     * Synthesizes 16-bit 16kHz PCM audio bytes directly.
     * Uses neural model if available; falls back to DSP formant synthesizer if absent or evicted.
     */
    fun synthesizePcm(text: String, language: Language = currentLanguage): ByteArray {
        val startNs = System.nanoTime()
        val normalized = text.trim()
        if (normalized.isEmpty()) return ByteArray(0)

        var pcm: ByteArray? = null
        if (isNeuralReady()) {
            val inferenceLock = modelLifecycleManager?.startTtsInference() ?: true
            try {
                pcm = neuralSynthesizer?.invoke(normalized, language)
            } catch (e: Throwable) {
                safeLog(Log.WARN, "Neural synthesis exception: ${e.message}, falling back to DSP")
            } finally {
                if (inferenceLock) {
                    modelLifecycleManager?.finishTtsInference()
                }
            }
        }

        val resultPcm: ByteArray
        if (pcm != null && pcm.isNotEmpty()) {
            neuralUtteranceCount++
            lastBackendUsed = BACKEND_NEURAL
            resultPcm = pcm
        } else {
            fallbackUtteranceCount++
            lastBackendUsed = BACKEND_FALLBACK_DSP
            resultPcm = fallbackEngine.synthesizePcm(normalized, language)
        }

        lastSynthesisDurationMs = (System.nanoTime() - startNs) / 1_000_000L
        return resultPcm
    }

    override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
        if (!initialized) {
            safeLog(Log.WARN, "SherpaOnnxTtsEngine not initialized, cannot speak")
            onDone?.invoke()
            return
        }
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        val isEmergency = utteranceId.contains("emerg", ignoreCase = true)
        if (onDone != null) {
            pendingDone[utteranceId] = onDone
        }

        Thread {
            try {
                if (isStopped.get()) {
                    pendingDone.remove(utteranceId)?.invoke()
                    return@Thread
                }

                val pcmAudio = synthesizePcm(text, currentLanguage)

                if (isStopped.get()) {
                    pendingDone.remove(utteranceId)?.invoke()
                    return@Thread
                }

                // Render through AudioTrack via fallbackEngine
                fallbackEngine.playPcmAudio(pcmAudio, isEmergency)

            } catch (e: Throwable) {
                safeLog(Log.WARN, "Playback error: ${e.message}")
            } finally {
                pendingDone.remove(utteranceId)?.invoke()
            }
        }.apply {
            isDaemon = true
            name = "SherpaTTS-$utteranceId"
        }.start()
    }

    override fun stop() {
        isStopped.set(true)
        fallbackEngine.stop()
        pendingDone.values.forEach { it.invoke() }
        pendingDone.clear()
    }

    override fun isInitialized(): Boolean = initialized

    override fun shutdown() {
        stop()
        modelLifecycleManager?.evictTts()
        fallbackEngine.shutdown()
        initialized = false
    }
}
