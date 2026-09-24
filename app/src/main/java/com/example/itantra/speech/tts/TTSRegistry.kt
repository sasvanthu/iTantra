package com.example.itantra.speech.tts

import com.example.itantra.codec.Language

/**
 * Phase 17 — TTS registry.
 *
 * A single data-driven catalog of every TTS backend this project knows about,
 * and how much of it is actually shipped in the APK. Honesty contract (mirrors
 * TTS_AUDIT.md):
 *
 *  - [AndroidTTSEngine] is a DEVICE-PROVIDED, PROPRIETARY engine: it works on a
 *    dev phone, but its voice data is not redistributable and it is NOT the
 *    open-source path the final device requires. Capability [VoiceCapability] of
 *    every language is [VoiceCapability.DEVICE_PROVIDED].
 *  - The open-source candidates (eSpeak-NG, Piper, RHVoice) are catalogued so a
 *    deploy can pick one, but their voice/model data is NOT bundled in this
 *    repository's assets — their languages report [VoiceCapability.NOT_BUNDLED].
 *    No engine in the catalog claims to ship a voice it does not ship.
 *  - Nothing here fabricates speech support; [bestAvailableEngine] only returns
 *    an engine that can genuinely render [language], or [CatalogEngine] with the
 *    honest fallback-flag set to true.
 */
object TTSRegistry {

    enum class EngineKind {
        /** Android text-to-speech — device-proprietary, development fallback only. */
        DEVICE_FALLBACK,

        /** eSpeak-NG — open-source formant synthesis, tiny, no ML. */
        OPEN_SOURCE_ESPEAK,

        /** Piper — open-source neural TTS. */
        OPEN_SOURCE_PIPER,

        /** RHVoice — open-source TTS for Indian languages. */
        OPEN_SOURCE_RHVoice
    }

    enum class VoiceCapability {
        /** A voice/model ships inside the APK. Nothing is bundled today. */
        BUNDLED,

        /** Voices may exist on the device already, outside this project's control. */
        DEVICE_PROVIDED,

        /** Known target but the voice data is not part of this build. */
        NOT_BUNDLED
    }

    data class CatalogEngine(
        val name: String,
        val kind: EngineKind,
        val description: String,
        /** Languages this engine could handle once its voices are present. */
        val languages: Map<Language, VoiceCapability>
    ) {
        fun capability(language: Language): VoiceCapability =
            languages[language] ?: VoiceCapability.NOT_BUNDLED
    }

    private val RECOGNIZED_LANGUAGES = setOf(
        Language.ENGLISH, Language.HINDI, Language.TAMIL, Language.BENGALI,
        Language.TELUGU, Language.MARATHI, Language.GUJARATI, Language.KANNADA,
        Language.MALAYALAM, Language.ODIA
    )

    val catalog: List<CatalogEngine> = listOf(
        CatalogEngine(
            name = "Android TTS (dev fallback)",
            kind = EngineKind.DEVICE_FALLBACK,
            description = "Device vendor's text-to-speech. Offline only after the user downloads voices; proprietary, not redistributable.",
            languages = RECOGNIZED_LANGUAGES.associateWith { VoiceCapability.DEVICE_PROVIDED }
        ),
        CatalogEngine(
            name = "eSpeak-NG",
            kind = EngineKind.OPEN_SOURCE_ESPEAK,
            description = "Open-source formant synthesizer; minimal footprint, understandable output.",
            languages = RECOGNIZED_LANGUAGES.associateWith { VoiceCapability.NOT_BUNDLED }
        ),
        CatalogEngine(
            name = "Piper",
            kind = EngineKind.OPEN_SOURCE_PIPER,
            description = "Open-source neural TTS; high quality, needs a voice archive per language.",
            languages = RECOGNIZED_LANGUAGES.associateWith { VoiceCapability.NOT_BUNDLED }
        ),
        CatalogEngine(
            name = "RHVoice",
            kind = EngineKind.OPEN_SOURCE_RHVoice,
            description = "Open-source speech synthesizer with Indian-language voices.",
            languages = RECOGNIZED_LANGUAGES.associateWith { VoiceCapability.NOT_BUNDLED }
        )
    )

    fun catalogedLanguages(): Set<Language> = RECOGNIZED_LANGUAGES

    fun engine(name: String): CatalogEngine? = catalog.firstOrNull { it.name == name }

    fun enginesThatCanSpeak(language: Language): List<CatalogEngine> =
        catalog.filter { it.capability(language) != VoiceCapability.NOT_BUNDLED }

    /**
     * Honest resolution for [language]: returns the open-source engine if one
     * is BUNDLED for the language (none today), otherwise the dev-fallback
     * Android engine marked [fallback]=true so callers know it is not the
     * open-source path and that no voices are shipped with this APK.
     */
    fun bestAvailableEngine(language: Language): Pair<CatalogEngine, Boolean> {
        val bundled = catalog.firstOrNull {
            it.kind.isOpenSource() && it.capability(language) == VoiceCapability.BUNDLED
        }
        if (bundled != null) return bundled to false
        val dev = catalog.first { it.kind == EngineKind.DEVICE_FALLBACK }
        return dev to true
    }

    private fun EngineKind.isOpenSource(): Boolean =
        this == EngineKind.OPEN_SOURCE_ESPEAK ||
            this == EngineKind.OPEN_SOURCE_PIPER ||
            this == EngineKind.OPEN_SOURCE_RHVoice
}