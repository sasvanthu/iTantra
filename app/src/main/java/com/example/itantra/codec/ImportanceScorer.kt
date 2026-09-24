package com.example.itantra.codec

class ImportanceScorer {

    companion object {
        private val CRITICAL_WORDS = setOf(
            "HELP", "FIRE", "SOS", "DANGER", "ACCIDENT", "MEDICAL",
            "EMERGENCY", "RESCUE", "POLICE", "HOSPITAL", "AMBULANCE",
            "EVACUATE", "ALERT", "WARNING", "URGENT", "INJURY", "BLOOD",
            // Hindi
            "मदद", "आग", "खतरा", "पुलिस", "अस्पताल", "एम्बुलेंस",
            "बचाव", "आपातकाल", "चेतावनी", "घायल", "खून",
            // Tamil
            "உதவி", "தீ", "ஆபத்து", "காவல்துறை", "மருத்துவமனை",
            "ஆம்புலன்ஸ்", "மீட்பு", "அவசரம்", "எச்சரிக்கை", "காயம்"
        )

        private val HIGH_WORDS = setOf(
            "NEED", "IMPORTANT", "COME", "GO", "FIND", "LOOK",
            "STOP", "WAIT", "FAST", "SLOW", "OPEN", "CLOSE",
            "LEFT", "RIGHT", "NORTH", "SOUTH", "EAST", "WEST",
            // Hindi
            "जरूरत", "जरूरी", "आओ", "जाओ", "ढूंढो", "देखो",
            "रुको", "तेज़", "धीमे", "बंद", "खोल",
            // Tamil
            "தேவை", "முக்கியம்", "வா", "போ", "தேடு", "பார்",
            "நில்", "வேகமாக", "மெதுவாக", "மூடு", "திற"
        )

        private val LOW_WORDS = setOf(
            "THE", "A", "AN", "IS", "AM", "ARE", "WAS",
            "OF", "IN", "ON", "AT", "TO", "FOR", "WITH",
            "AND", "OR", "BUT", "IF", "THEN", "ELSE"
        )
    }

    fun score(token: String): Importance {
        val upper = token.uppercase()
        return when {
            CRITICAL_WORDS.contains(upper) -> Importance.CRITICAL
            HIGH_WORDS.contains(upper) -> Importance.HIGH
            LOW_WORDS.contains(upper) -> Importance.LOW
            else -> Importance.NORMAL
        }
    }

    fun scoreTokens(tokens: List<String>): List<Importance> {
        return tokens.map { score(it) }
    }

    fun filterByImportance(
        tokens: List<Token>,
        minImportance: Importance
    ): List<Token> {
        return tokens.filter { it.importance.level >= minImportance.level }
    }

    fun prioritizePacket(tokens: List<Token>, bandwidthMode: BandwidthMode): List<Token> {
        return when (bandwidthMode) {
            BandwidthMode.EMERGENCY -> filterByImportance(tokens, Importance.CRITICAL)
            BandwidthMode.LOW_BANDWIDTH -> filterByImportance(tokens, Importance.HIGH)
            BandwidthMode.NORMAL -> filterByImportance(tokens, Importance.NORMAL)
            BandwidthMode.HIGH_BANDWIDTH -> tokens
        }
    }
}
