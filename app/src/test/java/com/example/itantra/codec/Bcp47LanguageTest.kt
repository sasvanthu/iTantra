package com.example.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Setu packets carry BCP-47 tags (`ta-IN`) while the codec wire format uses the
 * bare ISO-639-1 code (`ta`). Before this bridge existed every packet resolved to
 * [Language.UNKNOWN] on the transport layer.
 */
class Bcp47LanguageTest {

    @Test
    fun testFullTagsResolveToCorrectLanguage() {
        assertEquals(Language.TAMIL, Language.fromBcp47("ta-IN"))
        assertEquals(Language.HINDI, Language.fromBcp47("hi-IN"))
        assertEquals(Language.ENGLISH, Language.fromBcp47("en-US"))
        assertEquals(Language.ENGLISH, Language.fromBcp47("en-IN"))
    }

    @Test
    fun testBareIsoCodesStillResolve() {
        assertEquals(Language.TAMIL, Language.fromBcp47("ta"))
        assertEquals(Language.KANNADA, Language.fromBcp47("kn"))
        assertEquals(Language.ODIA, Language.fromBcp47("or"))
    }

    @Test
    fun testResolutionIsCaseAndSeparatorInsensitive() {
        assertEquals(Language.BENGALI, Language.fromBcp47("BN-in"))
        assertEquals(Language.MALAYALAM, Language.fromBcp47("ML_IN"))
        assertEquals(Language.GUJARATI, Language.fromBcp47("gu"))
    }

    @Test
    fun testUnknownAndEmptyTagsDegradeToUnknown() {
        assertEquals(Language.UNKNOWN, Language.fromBcp47("fr-FR"))
        assertEquals(Language.UNKNOWN, Language.fromBcp47(""))
        assertEquals(Language.UNKNOWN, Language.fromBcp47("   "))
        assertEquals(Language.UNKNOWN, Language.fromBcp47("-IN"))
    }

    /**
     * The inverse must be stable, otherwise the UI and the packet metadata would
     * disagree about which language is active.
     */
    @Test
    fun testToBcp47RoundTripsThroughFromBcp47() {
        for (language in Language.entries) {
            assertEquals(
                "Round trip failed for $language",
                language,
                Language.fromBcp47(Language.toBcp47(language))
            )
        }
    }

    @Test
    fun testToBcp47ProducesRegionTaggedStrings() {
        assertEquals("en-IN", Language.toBcp47(Language.ENGLISH))
        assertEquals("ta-IN", Language.toBcp47(Language.TAMIL))
        assertEquals("hi-IN", Language.toBcp47(Language.HINDI))
    }

    /**
     * Guards the original defect: a packet tagged with a full BCP-47 tag must
     * never collapse to UNKNOWN when handed to the transport layer.
     */
    @Test
    fun testPacketLanguageTagsNeverDegradeToUnknown() {
        for (language in Language.entries.filter { it != Language.UNKNOWN }) {
            val tag = Language.toBcp47(language)
            assertEquals(
                "Tag $tag degraded to UNKNOWN",
                language,
                Language.fromBcp47(tag)
            )
        }
    }

    @Test
    fun testLegacyFromCodeBehaviourIsUnchanged() {
        assertEquals(Language.TAMIL, Language.fromCode("ta"))
        assertEquals(Language.UNKNOWN, Language.fromCode("ta-IN"))
    }
}
