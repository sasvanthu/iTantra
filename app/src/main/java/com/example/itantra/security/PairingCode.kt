package com.example.itantra.security

/**
 * Human-transcribable pairing code used for OUT-OF-BAND sharing of the
 * AES-256 session key between two paired demo devices.
 *
 * Format
 * ------
 *     SETU-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX
 *
 *   * Crockford Base32 alphabet (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`) - the
 *     letters I, L, O and U are excluded so a spoken or typed code has no
 *     ambiguous glyphs.
 *   * 52 symbols carry the 256-bit key (32 bytes) in 5-bit groups.
 *   * 4 trailing symbols carry a CRC-16/CCITT-FALSE checksum.
 *   * Symbols are grouped in fours for readability and transcription accuracy.
 *
 * Security notes
 * --------------
 *   * This is an OUT-OF-BAND channel. Whoever reads the code aloud or scans it
 *     obtains the session key. It is a prototype affordance for a face-to-face
 *     demo, NOT a substitute for a key agreement protocol.
 *   * [decode] is strict: it rejects wrong lengths, unknown characters, a bad
 *     CRC, and non-canonical padding bits, so a key always has exactly one
 *     valid representation and typo'd codes fail loudly instead of silently
 *     producing a different key.
 *
 * Replace [decode] with an X25519/ECDH agreement step (see `SessionKeyManager`)
 * when authenticated key exchange is implemented; nothing else has to change.
 */
object PairingCode {

    /** AES-256 key length in bytes. */
    const val KEY_LENGTH_BYTES = 32

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val PREFIX = "SETU"
    private const val GROUP_SIZE = 4

    /** CRC-16/CCITT-FALSE occupies 16 bits, padded into 4 Base32 symbols. */
    private const val CHECK_SYMBOLS = 4

    /** 256 key bits / 5 = 52 symbols (the last symbol carries 1 padding bit). */
    private const val KEY_SYMBOLS = (KEY_LENGTH_BYTES * 8 + 4) / 5

    /** Total symbols in a well-formed code, excluding the prefix. */
    const val TOTAL_SYMBOLS = KEY_SYMBOLS + CHECK_SYMBOLS

    /**
     * Encodes 32 bytes of raw key material into a formatted pairing code.
     *
     * @throws IllegalArgumentException if [key] is not exactly 32 bytes
     */
    fun encode(key: ByteArray): String {
        require(key.size == KEY_LENGTH_BYTES) {
            "Pairing code requires exactly $KEY_LENGTH_BYTES bytes, got ${key.size}"
        }

        val payload = StringBuilder(KEY_SYMBOLS)
        var acc = 0L
        var bits = 0
        for (b in key) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                payload.append(ALPHABET[((acc shr bits) and 0x1FL).toInt()])
            }
        }
        if (bits > 0) {
            payload.append(ALPHABET[((acc shl (5 - bits)) and 0x1FL).toInt()])
        }

        val checksum = crc16(key)
        val check = StringBuilder(CHECK_SYMBOLS)
        for (shift in intArrayOf(15, 10, 5, 0)) {
            check.append(ALPHABET[(checksum shr shift) and 0x1F])
        }

        return format(payload.toString() + check)
    }

    /**
     * Decodes a pairing code back into 32 bytes of raw key material.
     *
     * Tolerates lowercase, missing/extra separators and the Crockford aliases
     * O->0, I/L->1, U->V. Returns null for any malformed, mistyped or
     * checksum-failing input.
     */
    fun decode(code: String): ByteArray? {
        val symbols = normalize(code) ?: return null
        if (symbols.size != TOTAL_SYMBOLS) return null

        var checksumFromCode = 0
        for (i in 0 until CHECK_SYMBOLS) {
            val symbol = ALPHABET.indexOf(symbols[KEY_SYMBOLS + i])
            if (symbol < 0) return null
            checksumFromCode = checksumFromCode or (symbol shl (5 * (CHECK_SYMBOLS - 1 - i)))
        }

        val out = ByteArray(KEY_LENGTH_BYTES)
        var acc = 0L
        var bits = 0
        var outIndex = 0

        for (i in 0 until KEY_SYMBOLS) {
            val symbol = ALPHABET.indexOf(symbols[i])
            if (symbol < 0) return null
            acc = (acc shl 5) or symbol.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (outIndex >= KEY_LENGTH_BYTES) return null
                out[outIndex++] = ((acc shr bits) and 0xFF).toByte()
            }
        }

        // The final symbol carries 4 unused bits. A canonical encoder always
        // emits zeros there; anything else is a non-canonical (ambiguous) code.
        if (bits > 0 && (acc and ((1L shl bits) - 1)) != 0L) return null
        if (outIndex != KEY_LENGTH_BYTES) return null

        if (crc16(out) != checksumFromCode) return null
        return out
    }

    /**
     * Short, non-reversible identifier safe to display in the UI so an operator
     * can confirm both devices hold the same key without ever seeing key bytes.
     */
    fun fingerprint(key: ByteArray): String {
        require(key.size == KEY_LENGTH_BYTES) { "Fingerprint requires exactly $KEY_LENGTH_BYTES bytes" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key)
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append("0123456789ABCDEF"[((digest[i].toInt() and 0xF0) shr 4)])
            sb.append("0123456789ABCDEF"[(digest[i].toInt() and 0x0F)])
        }
        return sb.toString()
    }

    /** Groups the symbol string into dash-separated blocks behind the prefix. */
    private fun format(symbols: String): String {
        val sb = StringBuilder(PREFIX.length + 1 + symbols.length + TOTAL_SYMBOLS / GROUP_SIZE)
        sb.append(PREFIX).append('-')
        for (i in symbols.indices) {
            if (i > 0 && i % GROUP_SIZE == 0) sb.append('-')
            sb.append(symbols[i])
        }
        return sb.toString()
    }

    /**
     * Strips the prefix, folds case, applies Crockford aliases and rejects any
     * character that is not part of the alphabet.
     */
    private fun normalize(code: String): CharArray? {
        var s = code.trim().uppercase()
        if (s.startsWith(PREFIX)) {
            s = s.substring(PREFIX.length).trimStart('-', ' ')
        }

        val out = CharArray(s.length)
        var n = 0
        for (c in s) {
            if (c == '-' || c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
            val mapped = when (c) {
                'O' -> '0'
                'I', 'L' -> '1'
                'U' -> 'V'
                else -> c
            }
            if (ALPHABET.indexOf(mapped) < 0) return null
            out[n++] = mapped
        }
        return if (n == s.length) out else out.copyOf(n)
    }

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no xor-out. */
    private fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
