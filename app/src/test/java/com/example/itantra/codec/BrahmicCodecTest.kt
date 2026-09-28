package com.example.itantra.codec

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class BrahmicCodecTest {

    @Test
    fun `single character round trips for all 10 supported languages`() {
        val testChars = listOf(
            'अ' to Language.HINDI,
            'क' to Language.HINDI,
            'ा' to Language.HINDI,
            '्' to Language.HINDI,
            'ं' to Language.HINDI,
            'ः' to Language.HINDI,
            // Marathi
            'ळ' to Language.MARATHI,
            'झ' to Language.MARATHI,
            // Tamil
            'அ' to Language.TAMIL,
            'க' to Language.TAMIL,
            'ா' to Language.TAMIL,
            '்' to Language.TAMIL,
            'ழ' to Language.TAMIL,
            'ஃ' to Language.TAMIL,
            // Bengali
            'অ' to Language.BENGALI,
            'ক' to Language.BENGALI,
            'া' to Language.BENGALI,
            '্' to Language.BENGALI,
            // Telugu
            'అ' to Language.TELUGU,
            'క' to Language.TELUGU,
            'ా' to Language.TELUGU,
            '్' to Language.TELUGU,
            // Gujarati
            'અ' to Language.GUJARATI,
            'ક' to Language.GUJARATI,
            'ા' to Language.GUJARATI,
            // Kannada
            'ಅ' to Language.KANNADA,
            'ಕ' to Language.KANNADA,
            'ಾ' to Language.KANNADA,
            // Malayalam
            'അ' to Language.MALAYALAM,
            'ക' to Language.MALAYALAM,
            'ാ' to Language.MALAYALAM,
            // Odia
            'ଅ' to Language.ODIA,
            'କ' to Language.ODIA,
            'ା' to Language.ODIA,
            // English
            'A' to Language.ENGLISH,
            'z' to Language.ENGLISH,
            '7' to Language.ENGLISH,
            '!' to Language.ENGLISH
        )

        for ((ch, lang) in testChars) {
            val text = ch.toString()
            val encoded = BrahmicCodec.encode(text, lang)
            val decoded = BrahmicCodec.decode(encoded)
            assertEquals("Character '$ch' ($lang) must decode losslessly", text, decoded)
        }
    }

    @Test
    fun `full sentences in each of the 10 languages decode 100 percent losslessly`() {
        val sentences = listOf(
            Language.HINDI to "आपातकालीन स्थिति में तुरंत सहायता भेजें। रेलवे स्टेशन के पास आग लगी है।",
            Language.MARATHI to "आपत्कालीन परिस्थितीत ताबडतोब मदत पाठवा. रेल्वे स्थानकाजवळ आग लागली आहे.",
            Language.TAMIL to "அவசர உதவி தேவை, ரயில் நிலையம் அருகில் தீ விபத்து ஏற்பட்டுள்ளது.",
            Language.TELUGU to "అత్యవసర సహాయం కావాలి, రైల్వే స్టేషన్ సమీపంలో అగ్ని ప్రమాదం జరిగింది.",
            Language.BENGALI to "জরুরী সাহায্য প্রয়োজন, রেলওয়ে স্টেশনের কাছে আগুন লেগেছে।",
            Language.GUJARATI to "તાત્કાલિક સહાયની જરૂર છે, રેલ્વે સ્ટેશન પાસે આગ લાગી છે.",
            Language.KANNADA to "ತುರ್ತು ಸಹಾಯ ಬೇಕಾಗಿದೆ, ರೈಲ್ವೆ ನಿಲ್ದಾಣದ ಬಳಿ ಬೆಂಕಿ ಕಾಣಿಸಿಕೊಂಡಿದೆ.",
            Language.MALAYALAM to "അടിയന്തിര സഹായം ആവശ്യമാണ്, റെയിൽവേ സ്റ്റേഷന് സമീപം തീപിടുത്തമുണ്ടായി.",
            Language.ODIA to "ଜରୁରୀ ସାହାଯ୍ୟ ଆବଶ୍ୟକ, ରେଳ ଷ୍ଟେସନ ନିକଟରେ ଅଗ୍ନିକାଣ୍ଡ ଘଟିଛି।",
            Language.ENGLISH to "Immediate evacuation required. Fire reported near Sector 9 railway station!"
        )

        for ((lang, sentence) in sentences) {
            val encoded = BrahmicCodec.encode(sentence, lang)
            val decoded = BrahmicCodec.decode(encoded)
            assertEquals("Sentence in $lang must match exactly", sentence, decoded)
        }
    }

    @Test
    fun `mixed English and Indic text round trips losslessly`() {
        val mixed = "Hospital Sector 4 में 15 ICU beds उपलब्ध हैं. Call 108 immediately!"
        val encoded = BrahmicCodec.encode(mixed, Language.HINDI)
        val decoded = BrahmicCodec.decode(encoded)

        assertEquals("Mixed text must decode identically", mixed, decoded)
    }

    @Test
    fun `Indic punctuation danda and double danda round trip losslessly`() {
        val withDanda = "पहला वाक्य। दूसरा वाक्य॥"
        val encoded = BrahmicCodec.encode(withDanda, Language.HINDI)
        val decoded = BrahmicCodec.decode(encoded)

        assertEquals("Danda marks must be preserved", withDanda, decoded)
    }

    @Test
    fun `complex Indic ligatures with virama and ZWJ ZWNJ decode losslessly`() {
        // Hindi 'क्या', Tamil 'க்', Sanskrit/Hindi ZWJ and ZWNJ sequences
        val ligatures = "क्या परिस्थिति? क् + \u200D + य = क්‍ය. Virama \u200C split."
        val encoded = BrahmicCodec.encode(ligatures, Language.HINDI)
        val decoded = BrahmicCodec.decode(encoded)

        assertEquals("Ligatures with ZWJ/ZWNJ must match exactly", ligatures, decoded)
    }

    @Test
    fun `emojis and extended symbols escape and decode losslessly`() {
        val withEmojis = "🚨 SOS! आग लगी है 🔥! Ambulance 🚑 required at Gate 2."
        val encoded = BrahmicCodec.encode(withEmojis, Language.HINDI)
        val decoded = BrahmicCodec.decode(encoded)

        assertEquals("Emojis and text must decode without corruption", withEmojis, decoded)
    }

    @Test
    fun `invalid magic or truncated data returns null on decode`() {
        val valid = BrahmicCodec.encode("Sample", Language.ENGLISH)

        // Corrupt magic
        val badMagic = valid.copyOf()
        badMagic[0] = 0x00
        assertNull(BrahmicCodec.decode(badMagic))

        // Truncate
        assertNull(BrahmicCodec.decode(ByteArray(3)))
    }

    @Test
    fun `brahmic compression ratio benchmark vs UTF-8 across all 10 languages`() {
        val testCorpus = listOf(
            Language.HINDI to "आपातकालीन स्थिति में तुरंत सहायता भेजें। रेलवे स्टेशन के पास आग लगी है।",
            Language.MARATHI to "आपत्कालीन परिस्थितीत ताबडतोब मदत पाठवा. रेल्वे स्थानकाजवळ आग लागली आहे.",
            Language.TAMIL to "அவசர உதவி தேவை, ரயில் நிலையம் அருகில் தீ விபத்து ஏற்பட்டுள்ளது.",
            Language.TELUGU to "అత్యవసర సహాయం కావాలి, రైల్వే స్టేషన్ సమీపంలో అగ్ని ప్రమాదం జరిగింది.",
            Language.BENGALI to "জরুরী সাহায্য প্রয়োজন, রেলওয়ে স্টেশনের কাছে আগুন লেগেছে।",
            Language.GUJARATI to "તાત્કાલિક સહાયની જરૂર છે, રેલ્વે સ્ટેશન પાસે આગ લાગી છે.",
            Language.KANNADA to "ತುರ್ತು ಸಹಾಯ ಬೇಕಾಗಿದೆ, ರೈಲ್ವೆ ನಿಲ್ದಾಣದ ಬಳಿ ಬೆಂಕಿ ಕಾಣಿಸಿಕೊಂಡಿದೆ.",
            Language.MALAYALAM to "അടിയന്തിര സഹായം ആവശ്യമാണ്, റെയിൽവേ സ്റ്റേഷന് സമീപം തീപിടുത്തമുണ്ടായി.",
            Language.ODIA to "ଜରୁରୀ ସାହାଯ୍ୟ ଆବଶ୍ୟକ, ରେଳ ଷ୍ଟେସନ ନିକଟରେ ଅଗ୍ନିକାଣ୍ଡ ଘଟିଛି।"
        )

        println("\n=== BRAHMIC CODEC (VPMC) vs UTF-8 COMPRESSION BENCHMARK ===")
        println(String.format("%-12s | %-12s | %-12s | %-12s", "LANGUAGE", "UTF-8 (B)", "BRAHMIC (B)", "REDUCTION (%)"))
        println("------------------------------------------------------------")

        for ((lang, text) in testCorpus) {
            val utf8Size = text.toByteArray(StandardCharsets.UTF_8).size
            val brahmicSize = BrahmicCodec.encode(text, lang).size
            val reduction = (1.0 - (brahmicSize.toDouble() / utf8Size.toDouble())) * 100.0

            println(String.format("%-12s | %-12d | %-12d | %-10.2f%%", lang.name, utf8Size, brahmicSize, reduction))

            assertTrue("Brahmic encoding must be smaller than UTF-8 for Indic languages", brahmicSize < utf8Size)
            assertTrue("Brahmic encoding must achieve at least 50% compression on Indic text", reduction >= 50.0)
        }
        println("------------------------------------------------------------\n")
    }
}
