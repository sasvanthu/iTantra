package com.example.itantra.security

import com.example.itantra.protocol.SetuPacket
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Verifies the out-of-band pairing code used to share the AES-256 session key
 * between two paired demo devices, plus the BCP-47 language bridge that keeps
 * Setu packet metadata aligned with the codec wire format.
 */
class PairingCodeTest {

    private val random = SecureRandom()

    @Test
    fun testRoundTripPreservesKeyMaterial() {
        repeat(200) {
            val key = ByteArray(32).also { random.nextBytes(it) }
            val decoded = PairingCode.decode(PairingCode.encode(key))
            assertArrayEquals("Key must survive a full encode/decode round trip", key, decoded)
        }
    }

    @Test
    fun testGeneratedCodeIsWellFormed() {
        val code = PairingCode.encode(ByteArray(32) { it.toByte() })
        assertTrue("Code must carry the SETU prefix", code.startsWith("SETU-"))
        // 56 symbols in groups of 4 => "SETU-" + 14 groups of 4 joined by 13 dashes.
        assertEquals("SETU-".length + 14 * 4 + 13, code.length)
        assertTrue(code.split("-").all { it.length == 4 })
    }

    @Test
    fun testAllZeroAndAllOnesKeysRoundTrip() {
        for (fill in listOf(0x00, 0xFF)) {
            val key = ByteArray(32) { fill.toByte() }
            assertArrayEquals(key, PairingCode.decode(PairingCode.encode(key)))
        }
    }

    @Test
    fun testDecodeIsCaseAndSeparatorInsensitive() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val canonical = PairingCode.encode(key)

        assertArrayEquals(key, PairingCode.decode(canonical.lowercase()))
        assertArrayEquals(key, PairingCode.decode(canonical.replace("-", "")))
        assertArrayEquals(key, PairingCode.decode("  ${canonical.replace("-", " ")}  "))
        assertArrayEquals(key, PairingCode.decode(canonical.removePrefix("SETU-")))
    }

    @Test
    fun testCrockfordAliasesAreAccepted() {
        val key = ByteArray(32) { 0 }
        val symbols = PairingCode.encode(key).removePrefix("SETU-").replace("-", "")
        // O->0, I->1, L->1, U->V must all fold into the canonical alphabet.
        val aliased = "SETU-" + symbols
            .replace('O', '0').replace('I', '1')
            .replace('L', '1').replace('U', 'V')
        assertArrayEquals(key, PairingCode.decode(aliased))
    }

    /**
     * The prefix is matched before alias folding, so a code whose prefix was
     * itself aliased ("SETV") is not a valid code and must be rejected rather
     * than silently parsed as payload.
     */
    @Test
    fun testMangledPrefixIsRejected() {
        val key = ByteArray(32) { 0 }
        val mangled = PairingCode.encode(key).replace("SETU-", "SETV-")
        assertNull(PairingCode.decode(mangled))
    }

    @Test
    fun testSingleCharacterTypoIsRejected() {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val code = PairingCode.encode(ByteArray(32).also { random.nextBytes(it) })

        var rejected = 0
        var tested = 0
        for (position in code.indices) {
            if (code[position] == '-') continue
            for (replacement in alphabet) {
                if (replacement == code[position]) continue
                val mutated = code.toCharArray().also { it[position] = replacement }
                tested++
                if (PairingCode.decode(String(mutated)) == null) rejected++
            }
        }
        // CRC-16 catches every single-character substitution here.
        assertEquals("All $tested single-character typos must be rejected", tested, rejected)
    }

    @Test
    fun testTruncatedAndOverlongCodesAreRejected() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val symbols = PairingCode.encode(key).removePrefix("SETU-").replace("-", "")

        assertNull("Short code must be rejected", PairingCode.decode(symbols.dropLast(1)))
        assertNull("Long code must be rejected", PairingCode.decode(symbols + "0"))
        assertNull("Empty code must be rejected", PairingCode.decode(""))
        assertNull("Garbage must be rejected", PairingCode.decode("not-a-valid-code"))
    }

    @Test
    fun testInvalidCharactersAreRejected() {
        val symbols = PairingCode.encode(ByteArray(32)).removePrefix("SETU-").replace("-", "")
        // '#' and '!' are outside the alphabet and must not be silently dropped.
        assertNull(PairingCode.decode(symbols.drop(1) + "#" + symbols.drop(2)))
        assertNull(PairingCode.decode(symbols.drop(1) + "!" + symbols.drop(2)))
    }

    @Test
    fun testEncodingIsCanonicalAndUnique() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        assertEquals(
            "Encoding must be deterministic for a given key",
            PairingCode.encode(key),
            PairingCode.encode(key)
        )
    }

    @Test
    fun testEncodeRejectsWrongKeyLength() {
        for (length in listOf(0, 16, 24, 31, 33, 64)) {
            try {
                PairingCode.encode(ByteArray(length))
                fail("Expected rejection for $length-byte key")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun testFingerprintIsStableAndDistinct() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val fingerprint = PairingCode.fingerprint(key)

        assertEquals("Fingerprint must be stable", fingerprint, PairingCode.fingerprint(key))
        assertEquals("Fingerprint is 8 bytes of hex", 16, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789ABCDEF" })

        val other = ByteArray(32).also { random.nextBytes(it) }
        assertNotEquals(fingerprint, PairingCode.fingerprint(other))
    }

    /**
     * The two devices in a demo must end up with byte-identical keys, which is
     * the whole reason this code exists.
     */
    @Test
    fun testTwoDevicesEndUpWithIdenticalKeys() {
        val phoneAKey = CryptoEngine.generateSessionKey().encoded
        val code = PairingCode.encode(phoneAKey)
        val phoneBKey = PairingCode.decode(code)!!

        assertArrayEquals(
            "Phone B must derive the same key Phone A generated",
            phoneAKey,
            phoneBKey
        )
        assertEquals(
            PairingCode.fingerprint(phoneAKey),
            PairingCode.fingerprint(phoneBKey)
        )

        // And a packet encrypted by A must decrypt with B's copy of the key.
        val packet = SetuPacket.createEncrypted(
            plaintext = "cross-device key agreement check".toByteArray(Charsets.UTF_8),
            senderId = "PHONE_A",
            receiverId = "PHONE_B",
            key = CryptoEngine.importKey(phoneAKey)
        )
        val decrypted = String(packet.decryptPayload(CryptoEngine.importKey(phoneBKey)), Charsets.UTF_8)
        assertEquals("cross-device key agreement check", decrypted)
    }
}
