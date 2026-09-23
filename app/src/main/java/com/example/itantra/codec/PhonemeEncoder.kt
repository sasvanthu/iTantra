package com.example.itantra.codec

class PhonemeEncoder {

    companion object {
        private val PHONEME_MAP = mapOf(
            // English phonemes (simplified IPA-based)
            'A' to 1, 'E' to 2, 'I' to 3, 'O' to 4, 'U' to 5,
            'B' to 10, 'C' to 11, 'D' to 12, 'F' to 13, 'G' to 14,
            'H' to 15, 'J' to 16, 'K' to 17, 'L' to 18, 'M' to 19,
            'N' to 20, 'P' to 21, 'Q' to 22, 'R' to 23, 'S' to 24,
            'T' to 25, 'V' to 26, 'W' to 27, 'X' to 28, 'Y' to 29, 'Z' to 30,

            // Hindi phonemes
            'ा' to 101, 'ि' to 102, 'ी' to 103, 'ु' to 104, 'ू' to 105,
            'े' to 106, 'ै' to 107, 'ो' to 108, 'ौ' to 109,
            'क' to 110, 'ख' to 111, 'ग' to 112, 'घ' to 113, 'ङ' to 114,
            'च' to 115, 'छ' to 116, 'ज' to 117, 'झ' to 118, 'ञ' to 119,
            'ट' to 120, 'ठ' to 121, 'ड' to 122, 'ढ' to 123, 'ण' to 124,
            'त' to 125, 'थ' to 126, 'द' to 127, 'ध' to 128, 'न' to 129,
            'प' to 130, 'फ' to 131, 'ब' to 132, 'भ' to 133, 'म' to 134,
            'य' to 135, 'र' to 136, 'ल' to 137, 'व' to 138, 'श' to 139,
            'ष' to 140, 'स' to 141, 'ह' to 142,

            // Tamil phonemes
            'அ' to 201, 'ஆ' to 202, 'இ' to 203, 'ஈ' to 204, 'உ' to 205,
            'ஊ' to 206, 'எ' to 207, 'ஏ' to 208, 'ஐ' to 209, 'ஒ' to 210,
            'ஓ' to 211, 'ஔ' to 212,
            'க' to 213, 'ங' to 214, 'ச' to 215, 'ஞ' to 216, 'ட' to 217,
            'ண' to 218, 'த' to 219, 'ந' to 220, 'ப' to 221, 'ம' to 222,
            'ய' to 223, 'ர' to 224, 'ல' to 225, 'வ' to 226, 'ழ' to 227,
            'ள' to 228, 'ற' to 229, 'ன' to 230
        )

        private val REVERSE_PHONEME_MAP = PHONEME_MAP.entries.associate { (k, v) -> v to k }
    }

    fun encode(text: String): List<Int> {
        val phonemes = mutableListOf<Int>()
        for (char in text) {
            PHONEME_MAP[char]?.let { phonemes.add(it) }
                ?: run {
                    val code = char.code
                    if (code in 0x0900..0x097F) {
                        phonemes.add(code - 0x0900 + 300) // Devanagari range
                    } else if (code in 0x0B80..0x0BFF) {
                        phonemes.add(code - 0x0B80 + 400) // Tamil range
                    } else if (code in 0x0041..0x005A || code in 0x0061..0x007A) {
                        phonemes.add(char.uppercaseChar().code - 0x0041 + 1)
                    }
                }
        }
        return phonemes
    }

    fun decode(phonemeIds: List<Int>): String {
        val sb = StringBuilder()
        for (id in phonemeIds) {
            REVERSE_PHONEME_MAP[id]?.let { sb.append(it) }
                ?: run {
                    when {
                        id in 300..399 -> sb.append((id - 300 + 0x0900).toChar())
                        id in 400..499 -> sb.append((id - 400 + 0x0B80).toChar())
                        id in 1..26 -> sb.append((id - 1 + 0x0041).toChar())
                    }
                }
        }
        return sb.toString()
    }

    fun estimateSize(phonemeIds: List<Int>): Int {
        return phonemeIds.size * 2 // 2 bytes per phoneme ID (variable-length encoding)
    }
}
