package com.example.itantra.speech.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.itantra.codec.Language
import java.util.Locale

interface TTSEngine {
    fun initialize(context: Context, language: Language, onReady: () -> Unit = {})
    fun speak(text: String, utteranceId: String = System.currentTimeMillis().toString())
    fun stop()
    fun isInitialized(): Boolean
    fun shutdown()
}

class AndroidTTSEngine : TTSEngine {

    companion object {
        private const val TAG = "AndroidTTS"
    }

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var currentLanguage: Language = Language.ENGLISH

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
            else -> Locale.US
        }
    }

    override fun speak(text: String, utteranceId: String) {
        if (!initialized) {
            Log.w(TAG, "TTS not initialized")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun isInitialized(): Boolean = initialized

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }
}
