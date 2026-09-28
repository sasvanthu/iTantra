package com.example.itantra.protocol

import com.example.itantra.security.CryptoEngine
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class SetuPacketBinaryCodecTest {

    private val sampleKey = CryptoEngine.generateSessionKey()

    private fun createSamplePacket(
        text: String = "Test communication payload",
        language: String = "ta-IN",
        priority: String = "NORMAL",
        transport: String = "BLUETOOTH",
        messageType: String = "TEXT",
        senderId: String = "PHONE_A",
        receiverId: String = "PHONE_B",
        originalSenderId: String = senderId
    ): SetuPacket {
        return SetuPacket.createEncrypted(
            plaintext = text.toByteArray(StandardCharsets.UTF_8),
            senderId = senderId,
            receiverId = receiverId,
            originalSenderId = originalSenderId,
            language = language,
            priority = priority,
            transport = transport,
            messageType = messageType,
            key = sampleKey
        )
    }

    @Test
    fun `normal packet encodes and decodes losslessly with exact decryption`() {
        val original = createSamplePacket("Hello from Setu emergency network")
        val binary = original.encodeBinary()
        val decoded = SetuPacket.decodeBinary(binary)

        assertNotNull(decoded)
        assertEquals(original.transmissionId, decoded!!.transmissionId)
        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.receiverId, decoded.receiverId)
        assertEquals(original.originalSenderId, decoded.originalSenderId)
        assertEquals(original.transport, decoded.transport)
        assertEquals(original.sequenceNumber, decoded.sequenceNumber)
        assertEquals(original.totalPackets, decoded.totalPackets)
        assertEquals(original.language, decoded.language)
        assertEquals(original.messageType, decoded.messageType)
        assertEquals(original.priority, decoded.priority)
        assertEquals(original.hopCount, decoded.hopCount)

        // Verify cryptographic verification and decryption works identically
        val decrypted = decoded.decryptPayload(sampleKey)
        assertEquals("Hello from Setu emergency network", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun `empty payload packet encodes and decodes correctly`() {
        val emptyPacket = createSamplePacket("")
        val binary = emptyPacket.encodeBinary()
        val decoded = SetuPacket.decodeBinary(binary)

        assertNotNull(decoded)
        val decrypted = decoded!!.decryptPayload(sampleKey)
        assertEquals(0, decrypted.size)
    }

    @Test
    fun `one byte payload packet encodes and decodes correctly`() {
        val singleBytePacket = createSamplePacket("X")
        val binary = singleBytePacket.encodeBinary()
        val decoded = SetuPacket.decodeBinary(binary)

        assertNotNull(decoded)
        val decrypted = decoded!!.decryptPayload(sampleKey)
        assertEquals("X", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun `multilingual Indic unicode payloads encode and decode losslessly`() {
        val hindi = "मुझे अस्पताल के लिए तत्काल सहायता चाहिए।"
        val tamil = "அவசர உதவி தேவை, மருத்துவமனை எங்கே உள்ளது?"
        val telugu = "తక్షణ సహాయం కావాలి, రవాణా లేదు."

        listOf(
            hindi to "hi-IN",
            tamil to "ta-IN",
            telugu to "te-IN"
        ).forEach { (text, lang) ->
            val packet = createSamplePacket(text, language = lang)
            val binary = packet.encodeBinary()
            val decoded = SetuPacket.decodeBinary(binary)

            assertNotNull("Failed for $lang", decoded)
            val decrypted = decoded!!.decryptPayload(sampleKey)
            assertEquals("Decrypted text must match original for $lang", text, String(decrypted, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `distinct originalSenderId in multi-hop relay encodes and decodes correctly`() {
        val relayPacket = createSamplePacket(
            text = "Relayed through intermediary",
            senderId = "RELAY_NODE_2",
            receiverId = "DESTINATION_NODE",
            originalSenderId = "ORIGIN_NODE_0"
        )
        val binary = relayPacket.encodeBinary()
        val decoded = SetuPacket.decodeBinary(binary)

        assertNotNull(decoded)
        assertEquals("RELAY_NODE_2", decoded!!.senderId)
        assertEquals("ORIGIN_NODE_0", decoded.originalSenderId)
        assertEquals("DESTINATION_NODE", decoded.receiverId)

        val decrypted = decoded.decryptPayload(sampleKey)
        assertEquals("Relayed through intermediary", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun `corrupted authentication tag is rejected by GCM`() {
        val packet = createSamplePacket("Security sensitive payload")
        val binary = packet.encodeBinary()

        // Flip a byte in the authentication tag region (offsets 32 to 47)
        binary[35] = (binary[35].toInt() xor 0xFF).toByte()

        val decoded = SetuPacket.decodeBinary(binary)
        assertNotNull(decoded)

        // Decryption MUST fail with AEADBadTagException
        assertThrows(AEADBadTagException::class.java) {
            decoded!!.decryptPayload(sampleKey)
        }
    }

    @Test
    fun `invalid magic returns null on decode`() {
        val packet = createSamplePacket("Payload")
        val binary = packet.encodeBinary()

        // Corrupt magic
        binary[0] = 0x00
        assertNull(SetuPacket.decodeBinary(binary))
        // fromWireBytes should fall back to JSON or return null
        assertNull(SetuPacket.fromWireBytes(binary))
    }

    @Test
    fun `invalid wire version returns null`() {
        val packet = createSamplePacket("Payload")
        val binary = packet.encodeBinary()

        binary[4] = 99 // Unsupported wire version
        assertNull(SetuPacket.decodeBinary(binary))
    }

    @Test
    fun `truncated packet returns null safely without throwing crash`() {
        val packet = createSamplePacket("Payload")
        val binary = packet.encodeBinary()

        // Truncate at various arbitrary lengths
        for (cut in listOf(1, 10, 25, 45, binary.size - 5, binary.size - 1)) {
            val truncated = binary.copyOf(cut)
            assertNull("Truncated at $cut must return null", SetuPacket.decodeBinary(truncated))
        }
    }

    @Test
    fun `corrupted payload length returns null`() {
        val packet = createSamplePacket("Payload with length to corrupt")
        val binary = packet.encodeBinary()

        // The payload length is the 2 bytes immediately preceding the raw payload
        val lenOffset = binary.size - packet.encryptedPayload.let { Base64.decode(it).size } - 2
        binary[lenOffset] = 0x7F // Claim huge payload
        binary[lenOffset + 1] = 0xFF.toByte()

        assertNull("Mismatched payload length must return null", SetuPacket.decodeBinary(binary))
    }

    @Test
    fun `backward compatibility - fromWireBytes seamlessly accepts legacy JSON format`() {
        val packet = createSamplePacket("Legacy JSON test")
        val jsonWire = packet.toJson().toByteArray(StandardCharsets.UTF_8)

        // fromWireBytes should parse legacy JSON seamlessly
        val parsed = SetuPacket.fromWireBytes(jsonWire)
        assertNotNull(parsed)
        assertEquals(packet.packetId, parsed!!.packetId)
        val decrypted = parsed.decryptPayload(sampleKey)
        assertEquals("Legacy JSON test", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun `fromWireBytes automatically parses new binary wire format`() {
        val packet = createSamplePacket("Automatic binary test")
        val wire = packet.toWireBytes()

        // Ensure wire bytes start with SETU magic
        assertEquals('S'.code.toByte(), wire[0])
        assertEquals('E'.code.toByte(), wire[1])
        assertEquals('T'.code.toByte(), wire[2])
        assertEquals('U'.code.toByte(), wire[3])

        val parsed = SetuPacket.fromWireBytes(wire)
        assertNotNull(parsed)
        assertEquals(packet.packetId, parsed!!.packetId)
        val decrypted = parsed.decryptPayload(sampleKey)
        assertEquals("Automatic binary test", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun `binary wire size benchmark vs legacy JSON format`() {
        val cases = listOf(
            "SOS" to "Short Emergency",
            "Need immediate medical evacuation at Sector 4" to "Medium Emergency",
            "Severe flooding in southern campus. 15 people trapped on second floor. Send rescue boats immediately. Medical team required." to "Long Emergency"
        )

        println("\n=== Z-BWE BINARY vs LEGACY JSON WIRE BENCHMARK ===")
        println(String.format("%-18s | %-12s | %-12s | %-12s", "MESSAGE TYPE", "JSON (bytes)", "BINARY (bytes)", "REDUCTION (%)"))
        println("------------------------------------------------------------------")

        for ((text, label) in cases) {
            val pkt = createSamplePacket(text)
            val jsonSize = pkt.toJson().toByteArray(StandardCharsets.UTF_8).size
            val binarySize = pkt.encodeBinary().size
            val reduction = (1.0 - (binarySize.toDouble() / jsonSize.toDouble())) * 100.0

            println(String.format("%-18s | %-12d | %-12d | %-10.2f%%", label, jsonSize, binarySize, reduction))

            assertTrue("Binary size must be significantly smaller than JSON", binarySize < jsonSize)
            assertTrue("Binary format must achieve at least 50% wire reduction", reduction > 50.0)
        }
        println("------------------------------------------------------------------\n")
    }
}
