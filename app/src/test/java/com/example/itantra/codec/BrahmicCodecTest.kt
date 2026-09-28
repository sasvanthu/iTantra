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

    // ------------------------------------------------------------------
    // Exhaustive lossless verification across every code point we claim
    // ------------------------------------------------------------------

    @Test
    fun `every code point in each of the 9 Indic script blocks round trips losslessly`() {
        val scriptBlocks = listOf(
            Language.HINDI to 0x0900,      // Devanagari
            Language.BENGALI to 0x0980,    // Bengali
            Language.GUJARATI to 0x0A80,   // Gujarati
            Language.ODIA to 0x0B00,       // Odia
            Language.TAMIL to 0x0B80,      // Tamil
            Language.TELUGU to 0x0C00,     // Telugu
            Language.KANNADA to 0x0C80,    // Kannada
            Language.MALAYALAM to 0x0D00   // Malayalam
        )

        for ((lang, base) in scriptBlocks) {
            for (offset in 0..127) {
                val codepoint = base + offset
                val text = String(Character.toChars(codepoint))
                val encoded = BrahmicCodec.encode(text, lang)
                val decoded = BrahmicCodec.decode(encoded)
                assertEquals(
                    "U+${codepoint.toString(16).padStart(4, '0')} via $lang must decode losslessly",
                    text, decoded
                )
            }
        }

        // Marathi reuses the Devanagari block and must not be lost.
        for (offset in 0..127) {
            val codepoint = 0x0900 + offset
            val text = String(Character.toChars(codepoint))
            val decoded = BrahmicCodec.decode(BrahmicCodec.encode(text, Language.MARATHI))
            assertEquals("Marathi (Devanagari) U+${codepoint.toString(16)} round trip", text, decoded)
        }
    }

    @Test
    fun `every printable ASCII code point round trips losslessly`() {
        for (code in 0x20..0x7E) {
            val text = code.toChar().toString()
            val encoded = BrahmicCodec.encode(text, Language.ENGLISH)
            val decoded = BrahmicCodec.decode(encoded)
            assertEquals("ASCII 0x${code.toString(16)} must round trip", text, decoded)
        }
    }

    @Test
    fun `Indic digits in every script round trip losslessly`() {
        // Devanagari, Bengali, Gujarati, Odia, Tamil, Telugu, Kannada, Malayalam
        val digitBlocks = listOf(0x0966, 0x09E6, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6, 0x0D66)
        val languages = listOf(
            Language.HINDI, Language.BENGALI, Language.GUJARATI, Language.ODIA,
            Language.TAMIL, Language.TELUGU, Language.KANNADA, Language.MALAYALAM
        )
        for ((lang, base) in languages.zip(digitBlocks)) {
            for (d in 0..9) {
                val text = (base + d).toChar().toString()
                val decoded = BrahmicCodec.decode(BrahmicCodec.encode(text, lang))
                assertEquals("$lang digit $d must round trip", text, decoded)
            }
        }
    }

    @Test
    fun `rapid script switching across all 10 languages stays lossless`() {
        // Every adjacent pair forces an actual script switch so the opcode path
        // is exercised across the whole wire alphabet.
        val segments = listOf(
            Language.HINDI to "अ",
            Language.MARATHI to "म",
            Language.BENGALI to "ক",
            Language.GUJARATI to "ક",
            Language.ODIA to "କ",
            Language.TAMIL to "க",
            Language.TELUGU to "క",
            Language.KANNADA to "ಕ",
            Language.MALAYALAM to "ക",
            Language.ENGLISH to "A"
        )
        for (i in segments.indices) {
            for (j in segments.indices) {
                if (i == j) continue
                val text = segments[i].second + segments[j].second
                val decoded = BrahmicCodec.decode(BrahmicCodec.encode(text, segments[i].first))
                assertEquals("switch ${segments[i].first}->${segments[j].first}", text, decoded)
            }
        }
    }

    @Test
    fun `seeded fuzz across scripts keeps the codec lossless`() {
        // Whole-code-point items (emojis are full surrogate pairs) so the pool
        // can never manufacture a lone high surrogate.
        val pool = listOf(
            "अ", "आ", "इ", "ई", "उ", "क", "ख", "ग", "म", "न", "स", "ह",
            "ा", "ि", "ी", "ु", "ू", "े", "ै", "ो", "ौ", "ं", "ः", "्", "।",
            "অ", "আ", "ই", "ঈ", "উ", "ক", "খ", "গ", "ম", "ন", "ঃ", "্",
            "અ", "આ", "ઇ", "ઈ", "ઉ", "ક", "ખ", "ગ", "મ", "ન", "ા",
            "ଅ", "ଆ", "ଇ", "ଈ", "ଉ", "କ", "ଖ", "ଗ", "ମ", "ନ", "ା",
            "அ", "ஆ", "இ", "ஈ", "உ", "க", "ங", "ந", "ப", "ம", "ா", "்",
            "అ", "ఆ", "ఇ", "ఈ", "ఉ", "క", "ఖ", "గ", "మ", "న", "ా",
            "ಅ", "ಆ", "ಇ", "ಈ", "ಉ", "ಕ", "ಖ", "ಗ", "ಮ", "ನ", "ಾ",
            "അ", "ആ", "ഇ", "ഈ", "ഉ", "ക", "ഖ", "ഗ", "മ", "ന", "ാ",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "!", "?", ",", ";", ":", ".", "-", "%", "/", "(", ")", " ", "A", "z", "~",
            "🚨", "🔥", "🚑"
        )
        val rng = kotlin.random.Random(0x5EED)
        repeat(600) {
            val sb = StringBuilder()
            val len = 1 + rng.nextInt(48)
            repeat(len) { sb.append(pool[rng.nextInt(pool.size)]) }
            val text = sb.toString()
            val lang = listOf(
                Language.HINDI, Language.MARATHI, Language.TAMIL, Language.BENGALI,
                Language.TELUGU, Language.GUJARATI, Language.KANNADA, Language.MALAYALAM,
                Language.ODIA, Language.ENGLISH
            )[rng.nextInt(10)]
            val encoded = BrahmicCodec.encode(text, lang)
            val decoded = BrahmicCodec.decode(encoded)
            assertEquals("fuzz iteration $lang: $text", text, decoded)
        }
    }

    @Test
    fun `truncating a valid payload either returns null or a true prefix`() {
        val valid = BrahmicCodec.encode("आपातकालीन स्थिति 🚨 Hospital Sector 4", Language.HINDI)
        val full = BrahmicCodec.decode(valid)!!
        for (cut in 0 until valid.size) {
            val truncated = valid.copyOf(cut)
            // decode must be total (never throw) for any truncation, and any
            // partial output must be a strict prefix of the full message.
            val result = BrahmicCodec.decode(truncated)
            if (result != null) {
                assertTrue(
                    "cut=$cut must yield a prefix of the message, not fabricated content",
                    full.startsWith(result)
                )
            }
        }
        assertEquals(null, BrahmicCodec.decode(valid.copyOf(5)))
        assertEquals(null, BrahmicCodec.decode(valid.copyOf(0)))
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
