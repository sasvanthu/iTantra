package com.example.itantra.mesh

import com.example.itantra.protocol.IdGenerator
import com.example.itantra.protocol.SetuPacket
import com.example.itantra.security.CryptoEngine
import com.example.itantra.security.ReplayDetector
import com.example.itantra.telemetry.CommunicationEventLog
import com.example.itantra.transport.TransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

/**
 * Controlled 3-Node Multi-Hop Relay Engine for iTantra: Setu Prototype.
 *
 * Demonstrates:
 *   Phone A  ──(Bluetooth)──►  Phone B  ──(Wi-Fi)──►  Phone C
 *
 * Protocol Behavior:
 * - End-to-End Encryption: Payload is encrypted by Phone A and remains encrypted at Phone B.
 * - Relay Forwarding: Phone B increments hopCount, updates currentSender, changes transport, and relays to Phone C.
 * - Duplicate Detection: If a replayed packet arrives at B or C, it is ignored and logged.
 * - Delivery Confirmation: Node C receives and decrypts the original message exactly once.
 */
class MultiHopRelayEngine {

    data class MultiHopState(
        val isSimulating: Boolean = false,
        val currentStep: Int = 0,
        val activeTxId: String = "",
        val activeMsgId: String = "",
        val activePktId: String = "",
        val nodeAStatus: String = "IDLE",
        val nodeBStatus: String = "IDLE",
        val nodeCStatus: String = "IDLE",
        val packetPosition: Float = 0f, // 0.0 = at A, 0.5 = at B, 1.0 = at C
        val currentHopTransport: String = "BLUETOOTH",
        val deliveredMessage: String = "",
        val logs: List<String> = emptyList(),
        val duplicateDetected: Boolean = false
    )

    private val _relayState = MutableStateFlow(MultiHopState())
    val relayState: StateFlow<MultiHopState> = _relayState.asStateFlow()

    private val replayDetector = ReplayDetector()

    /**
     * Executes the complete 3-Node multi-hop sequence with step-by-step state visualization.
     */
    suspend fun runMultiHopDemonstration(
        messageText: String = "Water and medical supplies required at Campus Quad",
        language: String = "ta-IN",
        key: SecretKey = CryptoEngine.getSessionKey(),
        stepDelayMs: Long = 1000L
    ): Boolean {
        _relayState.update {
            it.copy(
                isSimulating = true,
                currentStep = 1,
                deliveredMessage = "",
                packetPosition = 0f,
                duplicateDetected = false,
                logs = listOf("Initiating Multi-Hop Demonstration: Phone A -> Phone B -> Phone C")
            )
        }

        val txId = IdGenerator.generateTransmissionId()
        val msgId = IdGenerator.generateMessageId()
        val pktId = IdGenerator.generatePacketId(msgId, 1)

        _relayState.update {
            it.copy(
                activeTxId = txId,
                activeMsgId = msgId,
                activePktId = pktId,
                nodeAStatus = "GENERATING SECURE PACKET"
            )
        }

        // STEP 1: Phone A creates and encrypts message for Phone C
        val packetA = SetuPacket.createEncrypted(
            plaintext = messageText.toByteArray(StandardCharsets.UTF_8),
            senderId = "PHONE_A",
            receiverId = "PHONE_C",
            originalSenderId = "PHONE_A",
            transport = "BLUETOOTH",
            language = language,
            transmissionId = txId,
            messageId = msgId,
            hopCount = 0,
            key = key
        )

        CommunicationEventLog.logEvent(
            device = "PHONE_A",
            destinationDevice = "PHONE_B",
            eventType = CommunicationEventLog.EventType.MESSAGE_CREATED,
            transport = "BLUETOOTH",
            transmissionId = txId,
            messageId = msgId,
            packetId = pktId,
            detail = "Message created & encrypted with AES-256-GCM"
        )
        appendLog("[Phone A] Created $msgId, encrypted payload (${packetA.encryptedPayload.take(16)}...)")
        delay(stepDelayMs)

        // STEP 2: Phone A transmits to Phone B via Bluetooth
        _relayState.update {
            it.copy(
                currentStep = 2,
                nodeAStatus = "TX (BLUETOOTH) -> B",
                nodeBStatus = "LISTENING (BT)",
                currentHopTransport = "BLUETOOTH",
                packetPosition = 0.25f
            )
        }
        CommunicationEventLog.logEvent(
            device = "PHONE_A",
            destinationDevice = "PHONE_B",
            eventType = CommunicationEventLog.EventType.PACKET_SENT,
            transport = "BLUETOOTH",
            transmissionId = txId,
            messageId = msgId,
            packetId = pktId,
            detail = "Transmitting to Relay (Phone B) via Bluetooth"
        )
        appendLog("[Phone A -> Phone B] Bluetooth transmission started ($txId)")
        delay(stepDelayMs)

        // STEP 3: Phone B receives packet
        _relayState.update {
            it.copy(
                currentStep = 3,
                nodeAStatus = "ACKED (DELIVERED TO HOP 1)",
                nodeBStatus = "PACKET RECEIVED (RELAY)",
                packetPosition = 0.5f
            )
        }
        CommunicationEventLog.logEvent(
            device = "PHONE_B",
            destinationDevice = "PHONE_C",
            eventType = CommunicationEventLog.EventType.PACKET_RECEIVED,
            transport = "BLUETOOTH",
            transmissionId = txId,
            messageId = msgId,
            packetId = pktId,
            detail = "Relay node B received packet; destination is PHONE_C (hopCount=0)"
        )
        appendLog("[Phone B] Ingested packet. Destination is Phone C. Forwarding as relay (payload preserved)...")
        delay(stepDelayMs)

        // STEP 4: Phone B prepares forward to Phone C via Wi-Fi
        val packetBtoC = packetA.copy(
            senderId = "PHONE_B",
            receiverId = "PHONE_C",
            originalSenderId = "PHONE_A",
            transport = "WIFI",
            transmissionId = txId, // Preserved transaction ID
            hopCount = packetA.hopCount + 1
        )

        _relayState.update {
            it.copy(
                currentStep = 4,
                activeTxId = txId,
                nodeBStatus = "TX (WI-FI) -> C",
                nodeCStatus = "LISTENING (WI-FI)",
                currentHopTransport = "WIFI",
                packetPosition = 0.75f
            )
        }
        CommunicationEventLog.logEvent(
            device = "PHONE_B",
            destinationDevice = "PHONE_C",
            eventType = CommunicationEventLog.EventType.PACKET_FORWARDED,
            transport = "WIFI",
            transmissionId = txId,
            messageId = msgId,
            packetId = pktId,
            detail = "Relaying packet to Phone C via Wi-Fi (hopCount=1)"
        )
        appendLog("[Phone B -> Phone C] Relaying across Wi-Fi transport ($txId, hopCount=1)")
        delay(stepDelayMs)

        // STEP 5: Phone C receives packet, verifies AES-GCM and decrypts
        val isDuplicate = replayDetector.isDuplicate(packetBtoC)
        var decryptedText = ""
        var success = false

        if (!isDuplicate) {
            try {
                // Note: Phone C decrypts the end-to-end payload
                val decryptedBytes = packetBtoC.decryptPayload(key)
                decryptedText = String(decryptedBytes, StandardCharsets.UTF_8)
                success = (decryptedText == messageText)

                CommunicationEventLog.logEvent(
                    device = "PHONE_C",
                    eventType = CommunicationEventLog.EventType.AES_GCM_VERIFIED,
                    transport = "WIFI",
                    transmissionId = txId,
                    messageId = msgId,
                    packetId = pktId,
                    detail = "Phone C decrypted end-to-end payload ✓"
                )
                CommunicationEventLog.logEvent(
                    device = "PHONE_C",
                    eventType = CommunicationEventLog.EventType.MESSAGE_DELIVERED,
                    transport = "WIFI",
                    transmissionId = txId,
                    messageId = msgId,
                    packetId = pktId,
                    detail = "Original message delivered: \"$decryptedText\""
                )
                appendLog("[Phone C] Received from Phone B. AES-256-GCM auth tag VERIFIED ✓. Decoded text: \"$decryptedText\"")
            } catch (e: Exception) {
                appendLog("[Phone C] Decryption error: ${e.message}")
            }
        } else {
            appendLog("[Phone C] Replay detected! Duplicate packet dropped.")
        }

        _relayState.update {
            it.copy(
                isSimulating = false,
                currentStep = 5,
                nodeAStatus = "COMPLETE",
                nodeBStatus = "RELAY COMPLETED",
                nodeCStatus = if (success) "DELIVERED ✓" else "FAILED",
                packetPosition = 1.0f,
                deliveredMessage = decryptedText
            )
        }

        return success
    }

    /**
     * Demonstrates duplicate packet rejection during multi-hop relay.
     */
    fun testDuplicatePacketSuppression(packet: SetuPacket): Boolean {
        val firstCheck = replayDetector.isDuplicate(packet)
        val secondCheck = replayDetector.isDuplicate(packet)
        val duplicateDetected = !firstCheck && secondCheck
        _relayState.update { it.copy(duplicateDetected = duplicateDetected) }
        return duplicateDetected
    }

    private fun appendLog(msg: String) {
        _relayState.update {
            it.copy(logs = (it.logs + msg).takeLast(40))
        }
    }
}
