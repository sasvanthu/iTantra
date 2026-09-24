package com.example.itantra.speech.stt

import android.content.Context
import android.util.Log
import com.example.itantra.codec.Language
import com.example.itantra.metrics.MetricsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

interface STTEngine {
    fun initialize(context: Context, language: Language)
    fun startListening(listener: (String) -> Unit)
    fun stopListening()
    fun reset()
    fun isInitialized(): Boolean
    fun shutdown()
}

class VoskSTTEngine : STTEngine {

    companion object {
        private const val TAG = "VoskSTT"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var initialized = false
    private var currentLanguage: Language = Language.ENGLISH

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    override fun initialize(context: Context, language: Language) {
        currentLanguage = language
        val modelDir = getModelDir(context, language)
        if (modelDir == null) {
            Log.e(TAG, "Model directory not found for $language")
            return
        }
        try {
            StorageService.unpack(context, modelDir, "model",
                { model ->
                    this.model = model
                    initialized = true
                    Log.i(TAG, "Model loaded for $language")
                },
                { exception ->
                    Log.e(TAG, "Error unpacking model", exception)
                }
            )
        } catch (e: IOException) {
            Log.e(TAG, "Error initializing model", e)
        }
    }

    private fun getModelDir(context: Context, language: Language): String? {
        // Only these three have known offline Vosk bundles. The P21 languages
        // (bn/te/mr/gu/kn/ml/or) are recognised by the enum but NO model asset
        // is shipped — initialize() honestly reports the model as missing.
        return when (language) {
            Language.ENGLISH -> "model-en"
            Language.HINDI -> "model-hi"
            Language.TAMIL -> "model-ta"
            else -> null
        }
    }

    override fun startListening(listener: (String) -> Unit) {
        if (!initialized || model == null) {
            Log.w(TAG, "Model not initialized")
            return
        }
        try {
            recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService!!.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    hypothesis?.let { parseResult(it, listener, isFinal = false) }
                }

                override fun onResult(hypothesis: String?) {
                    hypothesis?.let { parseResult(it, listener, isFinal = true) }
                }

                override fun onFinalResult(hypothesis: String?) {
                    hypothesis?.let { parseResult(it, listener, isFinal = true) }
                }

                override fun onError(exception: Exception?) {
                    Log.e(TAG, "Recognition error", exception)
                }

                override fun onTimeout() {
                    stopListening()
                }
            })
            _isListening.value = true
        } catch (e: IOException) {
            Log.e(TAG, "Error starting recognition", e)
        }
    }

    private fun parseResult(json: String, listener: (String) -> Unit, isFinal: Boolean) {
        try {
            val text = extractTextFromJson(json)
            if (text.isNotBlank() && isFinal) {
                listener(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing result: $json", e)
        }
    }

    private fun extractTextFromJson(json: String): String {
        val textMatch = Regex("\"text\"\\s*:\\s*\"([^\"]*?)\"").find(json)
        return textMatch?.groupValues?.get(1) ?: ""
    }

    override fun stopListening() {
        speechService?.stop()
        _isListening.value = false
    }

    override fun reset() {
        stopListening()
        recognizer?.close()
        recognizer = null
    }

    override fun isInitialized(): Boolean = initialized

    override fun shutdown() {
        stopListening()
        speechService?.shutdown()
        recognizer?.close()
        model?.close()
        initialized = false
    }
}

class SimulatedSTTEngine : STTEngine {

    private var initialized = false
    private var currentLanguage: Language = Language.ENGLISH
    private var callback: ((String) -> Unit)? = null

    override fun initialize(context: Context, language: Language) {
        currentLanguage = language
        initialized = true
    }

    override fun startListening(listener: (String) -> Unit) {
        callback = listener
    }

    override fun stopListening() {
        callback = null
    }

    fun emitText(text: String) {
        callback?.invoke(text)
    }

    override fun reset() {}
    override fun isInitialized(): Boolean = initialized
    override fun shutdown() { initialized = false }
}
