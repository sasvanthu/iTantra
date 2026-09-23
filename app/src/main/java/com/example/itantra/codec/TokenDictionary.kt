package com.example.itantra.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class TokenDictionary {

    data class DictionaryEntry(
        val token: String,
        val id: Int,
        val frequency: Int,
        val language: Language
    )

    private val tokenToId = mutableMapOf<String, Int>()
    private val idToToken = mutableMapOf<Int, String>()
    private var nextId = 0
    private val entries = mutableListOf<DictionaryEntry>()

    init {
        initializeCommonTokens()
    }

    private fun initializeCommonTokens() {
        val emergencyTokens = listOf(
            "HELP", "FIRE", "SOS", "DANGER", "ACCIDENT", "MEDICAL",
            "EMERGENCY", "RESCUE", "POLICE", "HOSPITAL", "AMBULANCE",
            "EVACUATE", "ALERT", "WARNING", "URGENT", "INJURY", "BLOOD",
            "WATER", "FOOD", "SHELTER", "COLD", "HEAT", "TRAPPED",
            "LOST", "FOUND", "SAFE", "DAMAGE", "FLOOD", "EARTHQUAKE"
        )

        val highFreqEnglish = listOf(
            "I", "YOU", "WE", "THEY", "HE", "SHE", "IT",
            "IS", "AM", "ARE", "WAS", "WERE", "BE", "BEEN",
            "HAVE", "HAS", "HAD", "DO", "DOES", "DID",
            "WILL", "WOULD", "CAN", "COULD", "SHALL", "SHOULD",
            "MAY", "MIGHT", "MUST",
            "THE", "A", "AN", "THIS", "THAT", "THESE", "THOSE",
            "MY", "YOUR", "HIS", "HER", "OUR", "THEIR",
            "NOT", "NO", "YES", "AND", "OR", "BUT",
            "IN", "ON", "AT", "TO", "FOR", "WITH", "FROM",
            "NEED", "WANT", "GO", "COME", "HELP", "FIND",
            "WHERE", "WHAT", "WHEN", "WHO", "HOW", "WHY",
            "HERE", "THERE", "NOW", "THEN", "ALWAYS", "NEVER",
            "GOOD", "BAD", "BIG", "SMALL", "FAST", "SLOW",
            "ALL", "SOME", "NONE", "MANY", "FEW", "MORE",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "FIRST", "SECOND", "THIRD", "NEXT", "LAST"
        )

        val highFreqHindi = listOf(
            "मैं", "तुम", "वह", "हम", "वे", "यह",
            "है", "हैं", "था", "थे", "होगा", "हो",
            "को", "की", "के", "से", "में", "पर",
            "नहीं", "हाँ", "और", "परंतु", "या",
            "क्या", "कहाँ", "कब", "कैसे", "क्यों",
            "मदद", "चाहिए", "जरूरत", "खतरा", "आग",
            "पुलिस", "अस्पताल", "स्कूल", "घर", "पानी"
        )

        val highFreqTamil = listOf(
            "நான்", "நீ", "அவன்", "அவள்", "நாங்கள்", "அவர்கள்",
            "இருக்கிறது", "இருக்கிறேன்", "இருந்தது", "ஆகும்",
            "க்கு", "இன்", "இல்", "உடன்", "மற்றும்", "ஆனால்",
            "என்ன", "எங்கே", "எப்போது", "எப்படி", "ஏன்",
            "உதவி", "தேவை", "ஆபத்து", "தீ", "நீர்",
            "காவல்துறை", "மருத்துவமனை", "பள்ளி", "வீடு"
        )

        var id = 0
        emergencyTokens.forEach { registerToken(it, id++, Importance.CRITICAL) }
        highFreqEnglish.forEach { registerToken(it, id++, Importance.NORMAL) }
        highFreqHindi.forEach { registerToken(it, id++, Importance.NORMAL) }
        highFreqTamil.forEach { registerToken(it, id++, Importance.NORMAL) }
        nextId = id
    }

    private fun registerToken(token: String, id: Int, importance: Importance) {
        val upper = token.uppercase()
        tokenToId[upper] = id
        idToToken[id] = upper
        entries.add(DictionaryEntry(upper, id, 0, Language.UNKNOWN))
    }

    fun encode(token: String): Int {
        val upper = token.uppercase()
        return tokenToId[upper] ?: -1
    }

    fun decode(id: Int): String? = idToToken[id]

    fun getImportance(token: String): Importance {
        val id = encode(token)
        return if (id >= 0 && id < 30) Importance.CRITICAL
        else Importance.NORMAL
    }

    fun getTokenCount(): Int = tokenToId.size

    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(tokenToId.size)
        tokenToId.forEach { (token, id) ->
            dos.writeUTF(token)
            dos.writeInt(id)
        }
        return baos.toByteArray()
    }

    fun deserialize(data: ByteArray) {
        tokenToId.clear()
        idToToken.clear()
        val bais = ByteArrayInputStream(data)
        val dis = DataInputStream(bais)
        val count = dis.readInt()
        repeat(count) {
            val token = dis.readUTF()
            val id = dis.readInt()
            tokenToId[token] = id
            idToToken[id] = token
        }
    }
}
