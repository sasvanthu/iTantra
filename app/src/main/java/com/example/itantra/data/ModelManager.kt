package com.example.itantra.data

import com.example.itantra.codec.Language

/**
 * Phase 22 — model manager.
 *
 * Tracks exactly which ML/model assets this build actually ships versus which
 * the device would like to run (STT recognition models per language, TTS voices
 * per language). Honesty contract:
 *
 *  - Presence is decided by an injected probe [ModelProbe] that consults the
 *    real storage/asset layer. In production that is the APK's asset folder and
 *    the device's model dir; in tests it is whatever the test injects. The app
 *    NEVER assumes a model exists because it was declared.
 *  - This repository ships no offline STT/TTS model data, so a truthful probe
 *    reports [Status.MISSING] for every desired entry. The [wanted] catalog
 *    below is the deployable spec; [resolve] maps each entry to reality.
 *  - No fake download progress, no fabricated file sizes.
 */
object ModelManager {

    enum class ModelKind { STT, TTS }

    enum class Status {
        /** Present on device; genuinely usable. */
        PRESENT,

        /** In the catalog but not shipped/installed. */
        MISSING
    }

    data class ModelEntry(
        val id: String,
        val kind: ModelKind,
        val language: Language,
        /** Expected size if installed; informational only, never guessed as shipped. */
        val sizeHintBytes: Int
    )

    data class Resolution(
        val entry: ModelEntry,
        val status: Status
    ) {
        val isUsable: Boolean get() = status == Status.PRESENT
    }

    /** Function that answers "is this model real on this device right now?". */
    fun interface ModelProbe {
        fun present(entry: ModelEntry): Boolean
    }

    private val STT_LANGUAGES = listOf(
        Language.ENGLISH, Language.HINDI, Language.TAMIL
    )

    private val TTS_LANGUAGES = listOf(
        Language.ENGLISH, Language.HINDI, Language.TAMIL,
        Language.BENGALI, Language.TELUGU, Language.MARATHI,
        Language.GUJARATI, Language.KANNADA, Language.MALAYALAM, Language.ODIA
    )

    /** Every model the product wants to run, regardless of whether it is shipped. */
    val wanted: List<ModelEntry> =
        buildList {
            for (lang in STT_LANGUAGES) {
                add(ModelEntry("stt-$lang", ModelKind.STT, lang, sizeHintForStt(lang)))
            }
            for (lang in TTS_LANGUAGES) {
                add(ModelEntry("tts-$lang", ModelKind.TTS, lang, sizeHintForTts(lang)))
            }
        }

    // Reasonable order-of-magnitude sizes (MB -> bytes) for the *catalog*.
    // These are aspirational install sizes IF the model were bundled; the probe
    // still has to find real bytes for the entry to count as PRESENT.
    private fun sizeHintForStt(language: Language): Int =
        when (language) {
            Language.ENGLISH -> 40
            Language.HINDI -> 26
            Language.TAMIL -> 24
            else -> 0
        } * 1024 * 1024

    private fun sizeHintForTts(language: Language): Int =
        64 * 1024 * 1024

    /** Map each wanted model to its real on-device status. */
    fun resolve(probe: ModelProbe): List<Resolution> =
        wanted.map { Resolution(it, if (probe.present(it)) Status.PRESENT else Status.MISSING) }

    /** Honest report: how much of the wanted catalog is actually usable. */
    fun missing(probe: ModelProbe): List<Resolution> = resolve(probe).filterNot { it.isUsable }

    fun countByKind(probe: ModelProbe): Map<ModelKind, Map<Status, Int>> =
        resolve(probe).groupingBy { it.entry.kind }
            .aggregate { kind, acc: MutableMap<Status, Int>?, element, first ->
                val map = acc ?: mutableMapOf()
                map[element.status] = (map[element.status] ?: 0) + 1
                map
            }
            .mapValues { it.value.toMap() }
}