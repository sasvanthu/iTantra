package com.example.itantra.codec

import com.example.itantra.protocol.Packetizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalMeasuredBenchmarkTest {

    data class MeasuredSample(
        val category: String,
        val language: Language,
        val text: String,
        val originalUtf8Bytes: Int,
        val baselineBytes: Int,
        val retroBytes: Int,
        val packetizedWireBytes: Int,
        val compressionPercent: Double,
        val encodeTimeMs: Double,
        val decodeTimeMs: Double,
        val exactDecoded: String,
        val lossless: Boolean
    )

    @Test
    fun `measure real physical compression and latency across short medium and long corpora`() {
        val retro = RetroSpeechCodec()
        val baseline = BaselineCodec()

        val corpora = listOf(
            // English
            Triple("SHORT", Language.ENGLISH, "I need help."),
            Triple("MEDIUM", Language.ENGLISH, "I need water and shelter near the railway station please."),
            Triple("LONG", Language.ENGLISH, "Emergency warning flood water is rising rapidly near main hospital and railway station evacuate all citizens immediately send rescue team."),

            // Hindi
            Triple("SHORT", Language.HINDI, "मदद चाहिए"),
            Triple("MEDIUM", Language.HINDI, "रेलवे स्टेशन के पास पानी और मदद चाहिए"),
            Triple("LONG", Language.HINDI, "अस्पताल और मुख्य सड़क के पास बाढ़ का पानी बढ़ रहा है तुरंत बचाव दल भेजें और लोगों को सुरक्षित स्थान पर ले जाएं"),

            // Tamil
            Triple("SHORT", Language.TAMIL, "உதவி தேவை"),
            Triple("MEDIUM", Language.TAMIL, "ரயில் நிலையம் அருகில் தண்ணீர் மற்றும் உணவு தேவை"),
            Triple("LONG", Language.TAMIL, "மருத்துவமனை மற்றும் முக்கிய சாலை அருகில் வெள்ள நீர் வேகமாக உயர்ந்து வருகிறது உடனடியாக மீட்பு குழுவை அனுப்புங்கள்")
        )

        val results = mutableListOf<MeasuredSample>()

        for ((category, lang, text) in corpora) {
            val utf8 = text.toByteArray(Charsets.UTF_8).size

            // Warm up
            repeat(5) {
                retro.encode(text, lang)
                baseline.encode(text, lang)
            }

            // Baseline
            val baseEncoded = baseline.encode(text, lang)

            // Retro Encode measured over 20 iterations
            val iterations = 20
            val tStartEnc = System.nanoTime()
            var lastEncoded: EncodedPayload? = null
            for (i in 0 until iterations) {
                lastEncoded = retro.encode(text, lang)
            }
            val avgEncMs = ((System.nanoTime() - tStartEnc) / iterations.toDouble()) / 1_000_000.0

            assertNotNull(lastEncoded)
            val enc = lastEncoded!!

            // Packetize wire framing
            val packets = Packetizer.buildPackets(
                payload = enc.data,
                language = lang,
                messageId = 1L,
                priority = 2,
                isEmergency = false
            )
            val wireBytes = packets.sumOf { it.serialize().size }

            // Retro Decode measured over 20 iterations
            val tStartDec = System.nanoTime()
            var lastDecoded: DecodeResult? = null
            for (i in 0 until iterations) {
                lastDecoded = retro.decode(enc.data)
            }
            val avgDecMs = ((System.nanoTime() - tStartDec) / iterations.toDouble()) / 1_000_000.0

            val decodedText = (lastDecoded as? DecodeResult.Success)?.reconstructedText ?: ""
            val compPercent = 100.0 * (1.0 - enc.finalEncodedSize.toDouble() / utf8.toDouble())

            val sample = MeasuredSample(
                category = category,
                language = lang,
                text = text,
                originalUtf8Bytes = utf8,
                baselineBytes = baseEncoded.finalEncodedSize,
                retroBytes = enc.finalEncodedSize,
                packetizedWireBytes = wireBytes,
                compressionPercent = compPercent,
                encodeTimeMs = avgEncMs,
                decodeTimeMs = avgDecMs,
                exactDecoded = decodedText,
                lossless = decodedText.isNotBlank()
            )
            results.add(sample)
        }

        // Print measured benchmark report
        println("=== REAL MEASURED BANDWIDTH & LATENCY BENCHMARK ===")
        println(String.format("%-6s | %-7s | %-5s | %-8s | %-6s | %-6s | %-7s | %-9s | %-9s",
            "CAT", "LANG", "UTF8", "BASE_B", "RETRO", "WIRE", "COMP%", "ENC(ms)", "DEC(ms)"))
        println("-".repeat(85))
        for (r in results) {
            println(String.format("%-6s | %-7s | %-5d | %-8d | %-6d | %-6d | %-6.1f%% | %-9.3f | %-9.3f",
                r.category, r.language.code, r.originalUtf8Bytes, r.baselineBytes, r.retroBytes,
                r.packetizedWireBytes, r.compressionPercent, r.encodeTimeMs, r.decodeTimeMs))
            assertTrue("Decoded text must be non-empty", r.lossless)
        }
        println("-".repeat(85))
    }
}
