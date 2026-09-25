package com.example.itantra.speech.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.itantra.codec.Language
import java.util.Locale

/**
 * TTS abstraction (Phase 5 spec #23 — audit outcome):
 *
 *  - The emergency pipeline only depends on this interface; swap the backend
 *    without touching [com.example.itantra.SpeechPipeline].
 *  - [AndroidTTSEngine] is the ONLY current implementation. Audit verdict:
 *    Android text-to-speech is a DEVICE-PROVIDED, PROPRIETARY engine (usually
 *    Google's TTS on Android/Google, or the OEM's, e.g. Samsung/MiUI). It is
 *    offline-capable once voices are downloaded, but it is NOT open-source and
 *    its voice data is not redistributable — it violates this project's
 *    open-source-only constraint for the final device.
 *  - So Android TTS stays ONLY as a DEVELOPMENT FALLBACK (on-device staging of
 *    the receive path). Enable a truly open-source offline engine (e.g.
 *    eSpeak-NG, Piper, RHVoice) behind this interface for the delivered device.
 *  - See TTS_AUDIT.md in the repo root for the full decision.
 */
interface TTSEngine {
    fun initialize(context: Context, language: Language, onReady: () -> Unit = {})
    fun speak(
        text: String,
        utteranceId: String = System.currentTimeMillis().toString(),
        onDone: (() -> Unit)? = null
    )
    fun stop()
    fun isInitialized(): Boolean
    fun shutdown()
}

/**
 * DEVELOPMENT FALLBACK ONLY (see file-level audit notes): bundles the device
 * vendor's proprietary TTS stack, not the open-source path the final build
 * requires. All current usage goes through [TTSEngine] so a Piper/RHVoice/
 * eSpeak-NG engine can drop in later.
 */
class AndroidTTSEngine : TTSEngine {

    companion object {
        private const val TAG = "AndroidTTS"
    }

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var currentLanguage: Language = Language.ENGLISH

    /** utteranceId -> completion callback, used to measure real synthesis time. */
    private val pendingDone = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    override fun initialize(context: Context, language: Language, onReady: () -> Unit) {
        currentLanguage = language
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = getLocaleForLanguage(language)
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Language $language not supported, falling back to English")
                    tts?.setLanguage(Locale.US)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { pendingDone.remove(it)?.invoke() }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { pendingDone.remove(it)?.invoke() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { pendingDone.remove(it)?.invoke() }
                    }
                })
                initialized = true
                Log.i(TAG, "TTS initialized for $language")
                onReady()
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    private fun getLocaleForLanguage(language: Language): Locale {
        return when (language) {
            Language.ENGLISH -> Locale.US
            Language.HINDI -> Locale("hi", "IN")
            Language.TAMIL -> Locale("ta", "IN")
            Language.BENGALI -> Locale("bn", "IN")
            Language.TELUGU -> Locale("te", "IN")
            Language.MARATHI -> Locale("mr", "IN")
            Language.GUJARATI -> Locale("gu", "IN")
            Language.KANNADA -> Locale("kn", "IN")
            Language.MALAYALAM -> Locale("ml", "IN")
            Language.ODIA -> Locale("or", "IN")
            else -> Locale.US
        }
    }

    override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
        if (!initialized) {
            Log.w(TAG, "TTS not initialized")
            onDone?.invoke()
            return
        }
        if (onDone != null) {
            pendingDone[utteranceId] = onDone
            // Safety net: synthesize() must eventually resolve even if the
            // engine never reports completion (e.g. focus lost mid-utterance).
            Thread {
                try {
                    Thread.sleep(20_000)
                    pendingDone.remove(utteranceId)?.invoke()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply { isDaemon = true }.start()
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        tts?.stop()
        pendingDone.values.forEach { it.invoke() }
        pendingDone.clear()
    }

    override fun isInitialized(): Boolean = initialized

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }
}
