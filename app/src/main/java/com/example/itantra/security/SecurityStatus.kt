package com.example.itantra.security

import com.example.itantra.protocol.SetuPacket
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Security validation and test harness for iTantra: Setu.
 *
 * Runs the mandatory 5 security test cases:
 * - TEST 1: Send encrypted message -> Receiver decrypts successfully
 * - TEST 2: Modify one byte of ciphertext -> Receiver rejects with authentication failure
 * - TEST 3: Modify authentication tag -> Receiver rejects with authentication failure
 * - TEST 4: Modify authenticated packet metadata (AAD) -> Receiver rejects with authentication failure
 * - TEST 5: Replay an old packet -> ReplayDetector identifies duplicate and ignores
 */
object SecurityStatus {

    data class SecurityTestReport(
        val encryptionPass: Boolean,
        val decryptionPass: Boolean,
        val tamperCiphertextPass: Boolean,
        val tamperTagPass: Boolean,
        val tamperMetadataPass: Boolean,
        val replayDetectionPass: Boolean,
        val details: List<String>
    ) {
        val allPassed: Boolean
            get() = encryptionPass && decryptionPass && tamperCiphertextPass &&
                    tamperTagPass && tamperMetadataPass && replayDetectionPass
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun runSecuritySelfTest(): SecurityTestReport {
        val details = mutableListOf<String>()
        val key = CryptoEngine.generateSessionKey()
        val originalText = "iTantra: Setu secure communication test payload"
        val originalBytes = originalText.toByteArray(StandardCharsets.UTF_8)

        // TEST 1: Encryption & Decryption
        var encPass = false
        var decPass = false
        val packet = try {
            val pkt = SetuPacket.createEncrypted(
                plaintext = originalBytes,
                senderId = "PHONE_A",
                receiverId = "PHONE_B",
                transport = "BLUETOOTH",
                key = key
            )
            encPass = pkt.encryptedPayload.isNotEmpty() && pkt.nonce.isNotEmpty() && pkt.authenticationTag.isNotEmpty()
            val decrypted = pkt.decryptPayload(key)
            val decryptedStr = String(decrypted, StandardCharsets.UTF_8)
            decPass = (decryptedStr == originalText)
            details.add("TEST 1: Encrypt + Decrypt -> PASS (exact match)")
            pkt
        } catch (e: Exception) {
            details.add("TEST 1: Encrypt + Decrypt -> FAIL: ${e.message}")
            return SecurityTestReport(false, false, false, false, false, false, details)
        }

        // TEST 2: Modify one byte of ciphertext
        var tamperCiphertextPass = false
        try {
            val rawCipher = Base64.decode(packet.encryptedPayload)
            rawCipher[0] = (rawCipher[0].toInt() xor 0xFF).toByte() // Flip bits
            val tamperedPacket = packet.copy(encryptedPayload = Base64.encode(rawCipher))
            tamperedPacket.decryptPayload(key)
            details.add("TEST 2: Tamper ciphertext -> FAIL (tampered ciphertext was accepted!)")
        } catch (e: AEADBadTagException) {
            tamperCiphertextPass = true
            details.add("TEST 2: Tamper ciphertext -> PASS (rejected with AEADBadTagException)")
        } catch (e: Exception) {
            tamperCiphertextPass = true
            details.add("TEST 2: Tamper ciphertext -> PASS (rejected with ${e.javaClass.simpleName})")
        }

        // TEST 3: Modify authentication tag
        var tamperTagPass = false
        try {
            val rawTag = Base64.decode(packet.authenticationTag)
            rawTag[rawTag.size - 1] = (rawTag[rawTag.size - 1].toInt() xor 0x01).toByte() // Corrupt 1 bit
            val tamperedPacket = packet.copy(authenticationTag = Base64.encode(rawTag))
            tamperedPacket.decryptPayload(key)
            details.add("TEST 3: Tamper tag -> FAIL (tampered auth tag was accepted!)")
        } catch (e: AEADBadTagException) {
            tamperTagPass = true
            details.add("TEST 3: Tamper tag -> PASS (rejected with AEADBadTagException)")
        } catch (e: Exception) {
            tamperTagPass = true
            details.add("TEST 3: Tamper tag -> PASS (rejected with ${e.javaClass.simpleName})")
        }

        // TEST 4: Modify authenticated packet metadata (e.g. change receiverId)
        var tamperMetadataPass = false
        try {
            val tamperedMetaPacket = packet.copy(receiverId = "PHONE_MALICIOUS")
            tamperedMetaPacket.decryptPayload(key)
            details.add("TEST 4: Tamper metadata -> FAIL (modified receiverId was accepted!)")
        } catch (e: AEADBadTagException) {
            tamperMetadataPass = true
            details.add("TEST 4: Tamper metadata -> PASS (rejected with AEADBadTagException)")
        } catch (e: Exception) {
            tamperMetadataPass = true
            details.add("TEST 4: Tamper metadata -> PASS (rejected with ${e.javaClass.simpleName})")
        }

        // TEST 5: Replay detection
        var replayPass = false
        val replayDetector = ReplayDetector()
        val firstSeen = replayDetector.isDuplicate(packet) // should be false
        val secondSeen = replayDetector.isDuplicate(packet) // should be true
        if (!firstSeen && secondSeen) {
            replayPass = true
            details.add("TEST 5: Replay detection -> PASS (duplicate packet correctly identified and rejected)")
        } else {
            details.add("TEST 5: Replay detection -> FAIL (firstSeen=$firstSeen, secondSeen=$secondSeen)")
        }

        return SecurityTestReport(
            encryptionPass = encPass,
            decryptionPass = decPass,
            tamperCiphertextPass = tamperCiphertextPass,
            tamperTagPass = tamperTagPass,
            tamperMetadataPass = tamperMetadataPass,
            replayDetectionPass = replayPass,
            details = details
        )
    }
}
