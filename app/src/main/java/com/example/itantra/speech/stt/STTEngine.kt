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
        val unpackDir = File(context.filesDir, "models/${modelCode(language)}")
        if (looksLikeVoskModel(unpackDir)) return unpackDir.absolutePath

        val operatorZip = File(context.filesDir, "stt-${language.name}.zip")
        if (operatorZip.isFile) {
            try {
                unpackZip(operatorZip, unpackDir)
            } catch (e: IOException) {
                Log.e(TAG, "Error unpacking installed model zip", e)
            }
            if (looksLikeVoskModel(unpackDir)) return unpackDir.absolutePath
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
            Language.TAMIL -> "model-ta"
            else -> null
        }

    private fun modelCode(language: Language): String =
        when (language) {
            Language.ENGLISH -> "en"
            Language.HINDI -> "hi"
            Language.TAMIL -> "ta"
            Language.BENGALI -> "bn"
            Language.TELUGU -> "te"
            Language.MARATHI -> "mr"
            Language.GUJARATI -> "gu"
            Language.KANNADA -> "kn"
            Language.MALAYALAM -> "ml"
            Language.ODIA -> "or"
            Language.UNKNOWN -> "xx"
        }

    /** A valid Vosk model directory contains graph + am + conf metadata. */
    private fun looksLikeVoskModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "conf/mfcc.conf").isFile ||
            File(dir, "conf").isDirectory ||
            File(dir, "graph").isDirectory
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
