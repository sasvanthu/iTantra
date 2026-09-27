package com.example.itantra.security

import com.example.itantra.protocol.IdGenerator
import com.example.itantra.protocol.SetuPacket
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SecurityProtocolTest {

    @Test
    fun testIdGeneratorFormats() {
        val txId = IdGenerator.generateTransmissionId()
        assertTrue("TX ID should start with TX-", txId.startsWith("TX-"))
        assertEquals("TX ID parts should match TX-YYYYMMDD-XXXXXX", 3, txId.split("-").size)

        val msgId = IdGenerator.generateMessageId()
        assertTrue("MSG ID should start with MSG-", msgId.startsWith("MSG-"))

        val pktId = IdGenerator.generatePacketId(msgId, 1)
        assertTrue("PKT ID should start with PKT-", pktId.startsWith("PKT-"))
        assertTrue("PKT ID should end with sequence -001", pktId.endsWith("-001"))
    }

    @Test
    fun testAes256GcmEncryptionAndDecryption() {
        val key = CryptoEngine.generateSessionKey()
        val plaintext = "Hello from Phone A to Phone B via iTantra: Setu".toByteArray(StandardCharsets.UTF_8)
        val aad = "routing-metadata".toByteArray(StandardCharsets.UTF_8)

        val enc = CryptoEngine.encrypt(plaintext, key, aad)
        assertEquals(CryptoEngine.GCM_NONCE_LENGTH_BYTES, enc.nonce.size)
        assertEquals(CryptoEngine.GCM_TAG_LENGTH_BYTES, enc.tag.size)
        assertEquals(plaintext.size, enc.ciphertext.size)

        val decrypted = CryptoEngine.decrypt(enc.ciphertext, enc.tag, enc.nonce, key, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun testCiphertextTamperingRejection() {
        val key = CryptoEngine.generateSessionKey()
        val plaintext = "Tamper check".toByteArray(StandardCharsets.UTF_8)
        val enc = CryptoEngine.encrypt(plaintext, key)

        // Modify 1 byte of ciphertext
        enc.ciphertext[0] = (enc.ciphertext[0].toInt() xor 0x01).toByte()

        // Must throw AEADBadTagException
        CryptoEngine.decrypt(enc.ciphertext, enc.tag, enc.nonce, key)
    }

    @Test(expected = AEADBadTagException::class)
    fun testAuthenticationTagTamperingRejection() {
        val key = CryptoEngine.generateSessionKey()
        val plaintext = "Tag tamper check".toByteArray(StandardCharsets.UTF_8)
        val enc = CryptoEngine.encrypt(plaintext, key)

        // Modify 1 byte of auth tag
        enc.tag[enc.tag.size - 1] = (enc.tag[enc.tag.size - 1].toInt() xor 0xFF).toByte()

        // Must throw AEADBadTagException
        CryptoEngine.decrypt(enc.ciphertext, enc.tag, enc.nonce, key)
    }

    @Test(expected = AEADBadTagException::class)
    fun testAadTamperingRejection() {
        val key = CryptoEngine.generateSessionKey()
        val plaintext = "AAD tamper check".toByteArray(StandardCharsets.UTF_8)
        val aad = "legitimate-sender".toByteArray(StandardCharsets.UTF_8)
        val enc = CryptoEngine.encrypt(plaintext, key, aad)

        val spoofedAad = "spoofed-sender".toByteArray(StandardCharsets.UTF_8)
        // Must throw AEADBadTagException because AAD does not match
        CryptoEngine.decrypt(enc.ciphertext, enc.tag, enc.nonce, key, spoofedAad)
    }

    @Test
    fun testFreshNoncePerEncryption() {
        val key = CryptoEngine.generateSessionKey()
        val plaintext = "Same message".toByteArray(StandardCharsets.UTF_8)

        val enc1 = CryptoEngine.encrypt(plaintext, key)
        val enc2 = CryptoEngine.encrypt(plaintext, key)

        // Nonces must NEVER be identical
        assertFalse("GCM Nonces must be unique", enc1.nonce.contentEquals(enc2.nonce))
        // Ciphertexts must differ due to distinct nonces
        assertFalse("Ciphertexts must differ due to unique nonces", enc1.ciphertext.contentEquals(enc2.ciphertext))
    }

    @Test
    fun testSetuPacketSerializationAndDecryption() {
        val key = CryptoEngine.generateSessionKey()
        val message = "Tamil emergency message: அங்கு தீ ஏற்பட்டுள்ளது."
        val packet = SetuPacket.createEncrypted(
            plaintext = message.toByteArray(StandardCharsets.UTF_8),
            senderId = "PHONE_A",
            receiverId = "PHONE_B",
            transport = "BLUETOOTH",
            language = "ta-IN",
            key = key
        )

        // Validate JSON serialization
        val json = packet.toJson()
        assertTrue(json.contains("transmissionId"))
        assertTrue(json.contains("messageId"))
        assertTrue(json.contains("packetId"))
        assertTrue(json.contains("encryptedPayload"))
        assertTrue(json.contains("nonce"))
        assertTrue(json.contains("authenticationTag"))
        assertFalse(json.contains(message)) // Plaintext must NOT appear in packet

        // Deserialize and decrypt
        val parsed = SetuPacket.fromJson(json)
        assertEquals(packet.packetId, parsed.packetId)
        assertEquals("BLUETOOTH", parsed.transport)
        assertEquals("ta-IN", parsed.language)

        val decryptedBytes = parsed.decryptPayload(key)
        val decryptedStr = String(decryptedBytes, StandardCharsets.UTF_8)
        assertEquals(message, decryptedStr)
    }

    @Test
    fun testSetuPacketMetadataTamperingRejection() {
        val key = CryptoEngine.generateSessionKey()
        val packet = SetuPacket.createEncrypted(
            plaintext = "Secure message".toByteArray(StandardCharsets.UTF_8),
            senderId = "PHONE_A",
            receiverId = "PHONE_B",
            key = key
        )

        // Maliciously modify receiverId in transit
        val tampered = packet.copy(receiverId = "PHONE_C")
        try {
            tampered.decryptPayload(key)
            fail("Decryption should fail when metadata (receiverId) is altered")
        } catch (e: AEADBadTagException) {
            // Expected
        }
    }

    @Test
    fun testReplayDetection() {
        val packet = SetuPacket.createEncrypted(
            plaintext = "Payload".toByteArray(StandardCharsets.UTF_8),
            senderId = "PHONE_A",
            receiverId = "PHONE_B"
        )
        val detector = ReplayDetector()

        assertFalse("First reception should not be duplicate", detector.isDuplicate(packet))
        assertTrue("Second reception should be flagged as duplicate", detector.isDuplicate(packet))
        assertTrue("Third reception should be flagged as duplicate", detector.isDuplicate(packet))
    }

    @Test
    fun testSecuritySelfTestHarness() {
        val report = SecurityStatus.runSecuritySelfTest()
        assertTrue("Encryption should pass", report.encryptionPass)
        assertTrue("Decryption should pass", report.decryptionPass)
        assertTrue("Ciphertext tampering rejection should pass", report.tamperCiphertextPass)
        assertTrue("Tag tampering rejection should pass", report.tamperTagPass)
        assertTrue("Metadata tampering rejection should pass", report.tamperMetadataPass)
        assertTrue("Replay detection should pass", report.replayDetectionPass)
        assertTrue("All security checks must pass", report.allPassed)
    }
}
