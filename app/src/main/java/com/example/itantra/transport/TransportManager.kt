package com.example.itantra.transport

import com.example.itantra.codec.Language
import com.example.itantra.protocol.IdGenerator
import com.example.itantra.protocol.SetuPacket
import com.example.itantra.security.CryptoEngine
import com.example.itantra.security.ReplayDetector
import com.example.itantra.telemetry.CommunicationEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * Unified Transport Manager for iTantra: Setu.
 *
 * Implements a single transport abstraction over Bluetooth and Wi-Fi:
 *
 *        TransportManager
 *              │
 *        ┌─────┴────────┐
 *        ▼              ▼
 *  BluetoothTransport  WiFiTransport
 *
 * Transmits structured [SetuPacket] instances across either medium without altering
 * the application-layer protocol or cryptographic envelope.
 */
class TransportManager(
    val bluetoothEngine: BleTransportEngine,
    val wifiEngine: WifiTransportEngine
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val replayDetector = ReplayDetector()

    private val _selectedTransport = MutableStateFlow(TransportType.BLUETOOTH)
    val selectedTransport: StateFlow<TransportType> = _selectedTransport.asStateFlow()

    private val _lastTransmissionReport = MutableStateFlow<TransmissionReport?>(null)
    val lastTransmissionReport: StateFlow<TransmissionReport?> = _lastTransmissionReport.asStateFlow()

    private val _incomingDecryptedPackets = MutableSharedFlow<DecryptedPacketResult>(extraBufferCapacity = 64)
    val incomingDecryptedPackets: SharedFlow<DecryptedPacketResult> = _incomingDecryptedPackets.asSharedFlow()

    private var btJob: Job? = null
    private var wifiJob: Job? = null

    data class TransmissionReport(
        val transmissionId: String,
        val messageId: String,
        val packetId: String,
        val transport: TransportType,
        val senderId: String,
        val receiverId: String,
        val totalBytes: Int,
        val roundTripTimeMs: Long,
        val success: Boolean,
        val statusText: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class DecryptedPacketResult(
        val packet: SetuPacket,
        val decryptedText: String,
        val decryptedBytes: ByteArray,
        val integrityVerified: Boolean,
        val isDuplicate: Boolean,
        val transportReceived: TransportType,
        val receivedAt: Long = System.currentTimeMillis()
    )

    init {
        startInboundObservation()
    }

    fun selectTransport(transport: TransportType) {
        if (transport == TransportType.BLUETOOTH || transport == TransportType.WIFI) {
            _selectedTransport.value = transport
        }
    }

    fun getActiveEngine(): TransportEngine {
        return when (_selectedTransport.value) {
            TransportType.WIFI -> wifiEngine
            else -> bluetoothEngine
        }
    }

    private fun startInboundObservation() {
        btJob?.cancel()
        wifiJob?.cancel()

        btJob = scope.launch {
            bluetoothEngine.incomingPayloads.collect { payload ->
                handleInboundWireBytes(payload.payload, TransportType.BLUETOOTH)
            }
        }

        wifiJob = scope.launch {
            wifiEngine.incomingPayloads.collect { payload ->
                handleInboundWireBytes(payload.payload, TransportType.WIFI)
            }
        }
    }

    /**
     * Sends a structured [SetuPacket] over the chosen [targetTransport].
     */
    suspend fun sendPacket(
        packet: SetuPacket,
        targetTransport: TransportType = _selectedTransport.value
    ): TransmissionReport {
        val engine: TransportEngine = when (targetTransport) {
            TransportType.WIFI -> wifiEngine
            else -> bluetoothEngine
        }

        val wireBytes = packet.toWireBytes()
        val startTime = System.currentTimeMillis()

        CommunicationEventLog.logEvent(
            device = packet.senderId,
            destinationDevice = packet.receiverId,
            eventType = CommunicationEventLog.EventType.PACKET_SENT,
            transport = targetTransport.name,
            transmissionId = packet.transmissionId,
            messageId = packet.messageId,
            packetId = packet.packetId,
            detail = "Transmitting ${wireBytes.size}B AES-256-GCM packet via ${targetTransport.name}"
        )

        val sendResult = engine.send(
            data = wireBytes,
            language = Language.fromBcp47(packet.language),
            isEmergency = packet.priority == "CRITICAL",
            priority = if (packet.priority == "CRITICAL") 1 else 2
        )

        val rtt = sendResult?.roundTripTimeMs ?: (System.currentTimeMillis() - startTime)
        val success = sendResult != null && !sendResult.failed

        val report = TransmissionReport(
            transmissionId = packet.transmissionId,
            messageId = packet.messageId,
            packetId = packet.packetId,
            transport = targetTransport,
            senderId = packet.senderId,
            receiverId = packet.receiverId,
            totalBytes = wireBytes.size,
            roundTripTimeMs = rtt,
            success = success,
            statusText = if (success) "DELIVERED ✓" else "FAILED: ${sendResult?.detail ?: "no link"}"
        )

        _lastTransmissionReport.value = report

        if (success) {
            CommunicationEventLog.logEvent(
                device = packet.senderId,
                destinationDevice = packet.receiverId,
                eventType = CommunicationEventLog.EventType.MESSAGE_DELIVERED,
                transport = targetTransport.name,
                transmissionId = packet.transmissionId,
                messageId = packet.messageId,
                packetId = packet.packetId,
                detail = "Transmission acknowledged (${rtt}ms RTT, ${wireBytes.size} bytes wire)"
            )
        }

        return report
    }

    /**
     * Ingestion handler for received wire frames from Bluetooth or Wi-Fi.
     */
    private suspend fun handleInboundWireBytes(bytes: ByteArray, transport: TransportType) {
        val packet = SetuPacket.fromWireBytes(bytes)
        if (packet == null) {
            // Not a SetuPacket or corrupted JSON; could be raw legacy frame
            return
        }

        CommunicationEventLog.logEvent(
            device = packet.receiverId,
            destinationDevice = packet.receiverId,
            eventType = CommunicationEventLog.EventType.PACKET_RECEIVED,
            transport = transport.name,
            transmissionId = packet.transmissionId,
            messageId = packet.messageId,
            packetId = packet.packetId,
            detail = "Packet arrived via ${transport.name} (${bytes.size}B)"
        )

        // Duplicate / replay detection
        if (replayDetector.isDuplicate(packet)) {
            CommunicationEventLog.logEvent(
                device = packet.receiverId,
                eventType = CommunicationEventLog.EventType.DUPLICATE_IGNORED,
                transport = transport.name,
                transmissionId = packet.transmissionId,
                messageId = packet.messageId,
                packetId = packet.packetId,
                detail = "Duplicate packet ignored (${packet.packetId})"
            )
            return
        }

        // AES-256-GCM Decryption & Authentication Tag Verification
        try {
            val decryptedBytes = packet.decryptPayload(CryptoEngine.getSessionKey())
            val decryptedText = String(decryptedBytes, StandardCharsets.UTF_8)

            CommunicationEventLog.logEvent(
                device = packet.receiverId,
                eventType = CommunicationEventLog.EventType.AES_GCM_VERIFIED,
                transport = transport.name,
                transmissionId = packet.transmissionId,
                messageId = packet.messageId,
                packetId = packet.packetId,
                detail = "AES-256-GCM auth tag & AAD verified ✓ (${decryptedBytes.size}B plaintext)"
            )

            val result = DecryptedPacketResult(
                packet = packet,
                decryptedText = decryptedText,
                decryptedBytes = decryptedBytes,
                integrityVerified = true,
                isDuplicate = false,
                transportReceived = transport
            )

            _incomingDecryptedPackets.emit(result)
        } catch (e: AEADBadTagException) {
            CommunicationEventLog.logEvent(
                device = packet.receiverId,
                eventType = CommunicationEventLog.EventType.INTEGRITY_FAILURE,
                transport = transport.name,
                transmissionId = packet.transmissionId,
                messageId = packet.messageId,
                packetId = packet.packetId,
                detail = "AUTHENTICATION FAILURE: Ciphertext/tag tampered or bad key"
            )
        } catch (e: Exception) {
            CommunicationEventLog.logEvent(
                device = packet.receiverId,
                eventType = CommunicationEventLog.EventType.INTEGRITY_FAILURE,
                transport = transport.name,
                transmissionId = packet.transmissionId,
                messageId = packet.messageId,
                packetId = packet.packetId,
                detail = "Decryption error: ${e.message}"
            )
        }
    }
}
