package com.example.itantra.speech.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.itantra.codec.Language
import com.example.itantra.metrics.MetricsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

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
        model?.close()
        model = null
        initialized = false
        val modelPath = resolveModel(context, language) ?: run {
            Log.e(TAG, "No offline STT model installed for $language — install it via MODEL CENTER")
            return
        }
        try {
            model = Model(modelPath)
            initialized = true
            Log.i(TAG, "Model loaded for $language from $modelPath")
        } catch (e: IOException) {
            Log.e(TAG, "Error loading model for $language", e)
        }
    }

    /**
     * Resolves a real on-device Vosk model directory, in priority order:
     *
     *  1. [MODEL CENTER unpack] — filesDir/models/<lang code>/ unzipped and ready.
     *  2. [MODEL CENTER zip]    — filesDir/stt-<LANG>.zip copied by the operator,
     *                             unpacked here on first use.
     *  3. [bundled asset]       — a model-<lang> asset shipped inside the APK.
     *
     * Returns null when none exists, which makes initialize() honestly report
     * the language as not-yet-usable instead of inventing a model.
     */
    private fun resolveModel(context: Context, language: Language): String? {
        val unpackDir = LanguagePackManager.getModelDirectory(context, language)
        if (unpackDir.isDirectory) {
            val root = LanguagePackManager.resolveVoskRoot(unpackDir)
            if (LanguagePackManager.isValidVoskModelDir(root)) {
                return root.absolutePath
            }
        }

        val operatorZip = File(context.filesDir, "stt-${language.name}.zip")
        if (operatorZip.isFile) {
            try {
                unpackZip(operatorZip, unpackDir)
            } catch (e: IOException) {
                Log.e(TAG, "Error unpacking installed model zip", e)
            }
            val root = LanguagePackManager.resolveVoskRoot(unpackDir)
            if (LanguagePackManager.isValidVoskModelDir(root)) {
                return root.absolutePath
            }
        }

        val bundledBase = bundledAssetBase(language) ?: return null
        val names = context.assets.list("") ?: emptyArray()
        val assetZip = names.firstOrNull { it == bundledBase || it == "$bundledBase.zip" }
        if (assetZip != null) {
            Log.w(TAG, "Bundled asset $assetZip exists but synchronous extraction is not supported; use MODEL CENTER")
        } else {
            Log.e(TAG, "No bundled STT model for $language")
        }
        return null
    }

    private fun bundledAssetBase(language: Language): String? =
        when (language) {
            Language.ENGLISH -> "model-en"
            Language.HINDI -> "model-hi"
            Language.TELUGU -> "model-te"
            Language.GUJARATI -> "model-gu"
            Language.TAMIL -> "model-ta"
            else -> null
        }

    private fun modelCode(language: Language): String =
        when (language) {
            Language.ENGLISH -> "en"
            Language.HINDI -> "hi"
            Language.TELUGU -> "te"
            Language.GUJARATI -> "gu"
            Language.TAMIL -> "ta"
            Language.BENGALI -> "bn"
            Language.MARATHI -> "mr"
            Language.KANNADA -> "kn"
            Language.MALAYALAM -> "ml"
            Language.ODIA -> "or"
            Language.UNKNOWN -> "xx"
        }

    /** A valid Vosk model directory contains graph + am + conf metadata. */
    private fun looksLikeVoskModel(dir: File): Boolean {
        return LanguagePackManager.isValidVoskModelDir(dir)
    }

    private fun unpackZip(zip: File, target: File) {
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                val safeName = entry.name.replace("\\", "/")
                if (safeName.endsWith("/")) continue
                val out = File(target, safeName)
                if (!out.canonicalPath.startsWith(target.canonicalPath)) continue
                out.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    out.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    override fun startListening(listener: (String) -> Unit) {
        if (!initialized || model == null) {
            Log.w(TAG, "[STT] [ERROR] Model not initialized for $currentLanguage")
            return
        }
        try {
            Log.i(TAG, "[VOICE] Starting microphone capture for Vosk STT (16kHz PCM, lang=$currentLanguage)")
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
                    Log.e(TAG, "[STT] [ERROR] Recognition error", exception)
                }

                override fun onTimeout() {
                    Log.i(TAG, "[STT] Recognition timeout / silence detected")
                    stopListening()
                }
            })
            _isListening.value = true
        } catch (e: IOException) {
            Log.e(TAG, "[STT] [ERROR] Error starting recognition", e)
        }
    }

    private var lastRecognizedText: String = ""

    private fun parseResult(json: String, listener: (String) -> Unit, isFinal: Boolean) {
        try {
            val text = extractTextFromJson(json)
            if (text.isNotBlank()) {
                lastRecognizedText = text
                if (isFinal) {
                    Log.i(TAG, "[STT] Final recognized sentence: \"$text\"")
                    listener(text)
                } else {
                    Log.d(TAG, "[STT] Partial result: \"$text\"")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STT] [ERROR] Error parsing result: $json", e)
        }
    }

    private fun extractTextFromJson(json: String): String {
        val textMatch = Regex("\"text\"\\s*:\\s*\"([^\"]*?)\"").find(json)
        if (textMatch != null && textMatch.groupValues[1].isNotBlank()) {
            return textMatch.groupValues[1].trim()
        }
        val partialMatch = Regex("\"partial\"\\s*:\\s*\"([^\"]*?)\"").find(json)
        return partialMatch?.groupValues?.get(1)?.trim() ?: ""
    }

    override fun stopListening() {
        Log.i(TAG, "[VOICE] Stopping microphone capture for Vosk STT")
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeechService", e)
        }
        speechService = null
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

/**
 * Speech recognition engine using Android's native [SpeechRecognizer].
 * Works on physical Android devices immediately without requiring external model downloads.
 * Supports English, Hindi, Tamil, and other Indian languages according to device language pack.
 */
class AndroidSTTEngine : STTEngine {

    companion object {
        private const val TAG = "AndroidSTT"
    }

    private var recognizer: SpeechRecognizer? = null
    private var currentLanguage: Language = Language.ENGLISH
    private var initialized = false
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechCallback: ((String) -> Unit)? = null
    private var appContext: Context? = null

    override fun initialize(context: Context, language: Language) {
        appContext = context.applicationContext
        currentLanguage = language
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        if (available) {
            initialized = true
            Log.i(TAG, "[STT] AndroidSTTEngine initialized for $language (${getLanguageTag(language)})")
        } else {
            initialized = false
            Log.e(TAG, "[STT] [ERROR] SpeechRecognizer not available on this device")
        }
    }

    private fun getLanguageTag(language: Language): String =
        when (language) {
            Language.ENGLISH -> "en-US"
            Language.HINDI -> "hi-IN"
            Language.TAMIL -> "ta-IN"
            Language.BENGALI -> "bn-IN"
            Language.TELUGU -> "te-IN"
            Language.MARATHI -> "mr-IN"
            Language.GUJARATI -> "gu-IN"
            Language.KANNADA -> "kn-IN"
            Language.MALAYALAM -> "ml-IN"
            Language.ODIA -> "or-IN"
            Language.UNKNOWN -> "en-US"
        }

    override fun startListening(listener: (String) -> Unit) {
        val ctx = appContext
        if (!initialized || ctx == null) {
            Log.e(TAG, "[STT] [ERROR] AndroidSTTEngine not initialized or context is null")
            return
        }
        speechCallback = listener
        mainHandler.post {
            try {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                    setRecognitionListener(createListener())
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLanguageTag(currentLanguage))
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, getLanguageTag(currentLanguage))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                }

                Log.i(TAG, "[VOICE] Starting microphone capture for Android STT (lang=${getLanguageTag(currentLanguage)})")
                recognizer?.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                Log.e(TAG, "[STT] [ERROR] Exception starting SpeechRecognizer: ${e.message}", e)
                _isListening.value = false
            }
        }
    }

    private fun createListener(): AndroidRecognitionListener {
        return object : AndroidRecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "[VOICE] Microphone ready for speech input")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "[VOICE] Beginning of speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "[VOICE] End of speech detected (pause/silence)")
                _isListening.value = false
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions (RECORD_AUDIO required)"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognition match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout (no speech input detected)"
                    else -> "Speech recognition error code: $error"
                }
                Log.w(TAG, "[STT] [ERROR] Recognition error: $errorMsg ($error)")
                _isListening.value = false
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                Log.i(TAG, "[STT] Final recognized sentence: \"$text\"")
                if (text.isNotBlank()) {
                    speechCallback?.invoke(text)
                }
                _isListening.value = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim() ?: ""
                if (partial.isNotBlank()) {
                    Log.d(TAG, "[STT] Partial result: \"$partial\"")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    override fun stopListening() {
        mainHandler.post {
            try {
                Log.i(TAG, "[VOICE] Stopping microphone capture for Android STT")
                recognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "[VOICE] Exception stopping SpeechRecognizer: ${e.message}")
            }
            _isListening.value = false
        }
    }

    override fun reset() {
        stopListening()
    }

    override fun isInitialized(): Boolean = initialized

    override fun shutdown() {
        mainHandler.post {
            try {
                recognizer?.destroy()
                recognizer = null
            } catch (e: Exception) {
                Log.w(TAG, "[VOICE] Exception destroying SpeechRecognizer: ${e.message}")
            }
            initialized = false
            _isListening.value = false
        }
    }
}

/**
 * Strict Offline STT Engine that enforces genuine offline Vosk recognition.
 * To guarantee zero-cloud and offline-native operation, this engine refuses
 * to fall back silently to Android's online SpeechRecognizer.
 * If an offline language pack is not installed, recognition is blocked until
 * the user installs it via the language pack setup or MODEL CENTER.
 */
class HybridSTTEngine : STTEngine {

    companion object {
        private const val TAG = "HybridSTT"
    }

    private val voskEngine = VoskSTTEngine()
    private val sherpaEngine = SherpaOnnxSTTEngine()
    private var activeEngine: STTEngine? = null

    private fun safeLog(priority: Int, msg: String) {
        try {
            when (priority) {
                Log.INFO -> Log.i(TAG, msg)
                Log.WARN -> Log.w(TAG, msg)
                Log.ERROR -> Log.e(TAG, msg)
                else -> Log.d(TAG, msg)
            }
        } catch (_: Throwable) {}
    }

    override fun initialize(context: Context, language: Language) {
        val modelDir = LanguagePackManager.getModelDirectory(context, language)
        val resolved = LanguagePackManager.resolveVoskRoot(modelDir)
        val hasOnnx = File(resolved, "model.int8.onnx").isFile || File(resolved, "model.onnx").isFile

        if (hasOnnx || language == Language.TAMIL || language == Language.KANNADA) {
            if (activeEngine !== sherpaEngine) {
                voskEngine.shutdown()
            }
            sherpaEngine.initialize(context, language)
            if (sherpaEngine.isInitialized()) {
                activeEngine = sherpaEngine
                safeLog(Log.INFO, "[STT] Pure offline Sherpa-ONNX model active for $language (AI4Bharat IndicConformer).")
                return
            }
        }

        if (activeEngine !== voskEngine) {
            sherpaEngine.shutdown()
        }
        voskEngine.initialize(context, language)
        if (voskEngine.isInitialized()) {
            activeEngine = voskEngine
            safeLog(Log.INFO, "[STT] Pure offline Vosk model active for $language.")
        } else {
            // Strictly enforce pure offline guarantee: No silent fallback to online recognition!
            activeEngine = null
            safeLog(Log.WARN, "[STT] Offline model NOT installed for $language. Install language pack in MODEL CENTER.")
        }
    }

    override fun startListening(listener: (String) -> Unit) {
        val engine = activeEngine
        if (engine != null && engine.isInitialized()) {
            engine.startListening(listener)
        } else {
            safeLog(Log.ERROR, "[STT] [ERROR] Cannot start listening: offline language model is not installed.")
        }
    }

    override fun stopListening() {
        activeEngine?.stopListening()
    }

    override fun reset() {
        activeEngine?.reset()
    }

    override fun isInitialized(): Boolean = activeEngine?.isInitialized() == true

    override fun shutdown() {
        voskEngine.shutdown()
        sherpaEngine.shutdown()
        activeEngine = null
    }

    fun isUsingVosk(): Boolean = activeEngine === voskEngine
    fun isUsingSherpa(): Boolean = activeEngine === sherpaEngine
    fun isOfflineReady(): Boolean = activeEngine != null && activeEngine!!.isInitialized()
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
