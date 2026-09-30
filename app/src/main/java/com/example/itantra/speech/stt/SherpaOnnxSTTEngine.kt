package com.example.itantra.speech.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.itantra.codec.Language
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * On-device neural speech recognizer using Sherpa-ONNX.
 * Executes AI4Bharat's IndicConformer models for Tamil (ta) and Kannada (kn)
 * fully offline with zero internet and zero cloud reliance.
 *
 * Thread-safe with robust concurrency guards to prevent native NULL-pointer crashes
 * during rapid state transitions or engine reconfigurations.
 */
class SherpaOnnxSTTEngine : STTEngine {

    companion object {
        private const val TAG = "SherpaOnnxSTT"
        private const val SAMPLE_RATE = 16000
    }

    private val engineLock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var currentLanguage: Language = Language.TAMIL
    private var initialized = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private var recordingJob: Job? = null
    private var decodingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val audioBuffer = ByteArrayOutputStream()
    private var speechCallback: ((String) -> Unit)? = null

    override fun initialize(context: Context, language: Language) {
        synchronized(engineLock) {
            if (initialized && currentLanguage == language && recognizer != null) {
                Log.i(TAG, "SherpaOnnxSTTEngine already initialized for $language, skipping redundant reload")
                return
            }

            stopListeningInternal()
            try {
                recognizer?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing old recognizer: ${t.message}")
            }
            recognizer = null
            initialized = false
            currentLanguage = language

            val modelDir = LanguagePackManager.getModelDirectory(context, language)
            val resolved = LanguagePackManager.resolveVoskRoot(modelDir)

            val modelFile = listOf("model.int8.onnx", "model.onnx")
                .map { File(resolved, it) }
                .firstOrNull { it.isFile && it.length() > 1000000L }

            val tokensFile = File(resolved, "tokens.txt")

            if (modelFile == null || !tokensFile.isFile) {
                Log.w(TAG, "No valid neural model / tokens found for $language in ${resolved.absolutePath}")
                initialized = false
                return
            }

            try {
                val modelConfig = OfflineModelConfig().apply {
                    nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath)
                    tokens = tokensFile.absolutePath
                    numThreads = 2
                    debug = false
                    modelType = "nemo_ctc"
                }

                val config = OfflineRecognizerConfig().apply {
                    featConfig = FeatureConfig().apply {
                        sampleRate = SAMPLE_RATE
                        featureDim = 80
                    }
                    this.modelConfig = modelConfig
                }

                recognizer = OfflineRecognizer(assetManager = null, config = config)
                initialized = true
                Log.i(TAG, "SherpaOnnxSTTEngine initialized successfully for $language using ${modelFile.name}")
            } catch (e: Throwable) {
                Log.e(TAG, "Error initializing SherpaOnnxSTTEngine for $language", e)
                recognizer = null
                initialized = false
            }
        }
    }

    override fun startListening(listener: (String) -> Unit) {
        synchronized(engineLock) {
            if (!initialized || recognizer == null) {
                Log.e(TAG, "[STT] Cannot start listening: SherpaOnnxSTTEngine not initialized for $currentLanguage")
                return
            }
        }

        stopListeningInternal()
        speechCallback = listener
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        _isListening.value = true

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        try {
            var record: AudioRecord? = null
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            for (src in sources) {
                try {
                    val r = AudioRecord(
                        src,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        record = r
                        break
                    } else {
                        r.release()
                    }
                } catch (_: Exception) {}
            }

            if (record == null) {
                Log.e(TAG, "[STT] AudioRecord initialization failed on all audio sources")
                _isListening.value = false
                return
            }

            audioRecord = record
            record.startRecording()
            Log.i(TAG, "[VOICE] Starting microphone capture for Sherpa-ONNX (16kHz PCM, lang=$currentLanguage)")

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val tempBuffer = ByteArray(bufferSize)
                while (_isListening.value && isActive) {
                    val read = record.read(tempBuffer, 0, tempBuffer.size)
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            audioBuffer.write(tempBuffer, 0, read)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STT] Error starting audio capture for Sherpa-ONNX", e)
            _isListening.value = false
        }
    }

    private fun stopListeningInternal() {
        _isListening.value = false
        recordingJob?.cancel()
        recordingJob = null

        val r = audioRecord
        audioRecord = null
        if (r != null) {
            try {
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    r.stop()
                }
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord", e)
            }
        }
    }

    override fun stopListening() {
        if (!_isListening.value) return
        stopListeningInternal()

        val pcmBytes: ByteArray
        synchronized(audioBuffer) {
            pcmBytes = audioBuffer.toByteArray()
            audioBuffer.reset()
        }

        if (pcmBytes.size < 3200) { // Less than 100ms
            Log.w(TAG, "Captured audio too short for recognition (${pcmBytes.size} bytes)")
            return
        }

        decodingJob?.cancel()
        decodingJob = CoroutineScope(Dispatchers.Default).launch {
            decodeAudio(pcmBytes)
        }
    }

    private fun decodeAudio(pcmBytes: ByteArray) {
        synchronized(engineLock) {
            val rec = recognizer
            if (rec == null || !initialized) {
                Log.w(TAG, "Cannot decode audio: SherpaOnnxSTTEngine not initialized or already released")
                return
            }

            try {
                val sampleCount = pcmBytes.size / 2
                val floatSamples = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val low = pcmBytes[i * 2].toInt() and 0xFF
                    val high = pcmBytes[i * 2 + 1].toInt()
                    val sample = (high shl 8) or low
                    floatSamples[i] = sample / 32768.0f
                }

                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(floatSamples, SAMPLE_RATE)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val text = result.text.trim()
                    Log.i(TAG, "[STT] Sherpa-ONNX decoded sentence: \"$text\" (lang=$currentLanguage)")
                    if (text.isNotBlank()) {
                        speechCallback?.invoke(text)
                    }
                } finally {
                    try {
                        stream.release()
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error during Sherpa-ONNX audio decoding", e)
            }
        }
    }

    /** Decodes an external WAV or PCM file for automated offline testing */
    fun decodeWav(wavFile: File): String {
        synchronized(engineLock) {
            val rec = recognizer ?: return ""
            if (!initialized) return ""
            return try {
                val bytes = wavFile.readBytes()
                val dataOffset = if (bytes.size > 44 && String(bytes.sliceArray(0..3)) == "RIFF") 44 else 0
                val pcmBytes = bytes.sliceArray(dataOffset until bytes.size)

                val sampleCount = pcmBytes.size / 2
                val floatSamples = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val low = pcmBytes[i * 2].toInt() and 0xFF
                    val high = pcmBytes[i * 2 + 1].toInt()
                    val sample = (high shl 8) or low
                    floatSamples[i] = sample / 32768.0f
                }

                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(floatSamples, SAMPLE_RATE)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    result.text.trim()
                } finally {
                    try {
                        stream.release()
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error decoding WAV file with Sherpa-ONNX", e)
                ""
            }
        }
    }

    override fun reset() {
        stopListening()
    }

    override fun isInitialized(): Boolean = synchronized(engineLock) { initialized && recognizer != null }

    override fun shutdown() {
        stopListeningInternal()
        decodingJob?.cancel()
        decodingJob = null

        synchronized(engineLock) {
            try {
                recognizer?.release()
            } catch (_: Throwable) {}
            recognizer = null
            initialized = false
        }
    }
}
