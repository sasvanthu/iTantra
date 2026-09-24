package com.example.itantra.transport

import com.example.itantra.codec.Language

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    /** Server (host) socket is open, waiting for a peer to connect. */
    WAITING,
    CONNECTED,
    ERROR
}

enum class TransportType {
    WIFI,
    SIMULATED,
    BLUETOOTH,
    MESH
}

/**
 * Immutable description of an established RETRO link session. Reset to null on
 * every disconnect; never carries state across reconnects so stale packets from
 * an old session cannot corrupt the new one.
 */
data class ConnectionSession(
    val sessionId: String,
    val localDeviceId: String,
    val remoteDeviceId: String,
    val protocolVersion: Int,
    val codecVersion: Int,
    val supportedLanguages: List<Language>,
    val connectionStartTime: Long,
    val lastReceivedTime: Long,
    val epoch: Int,
    val isHost: Boolean
)

/** Live counters for the current session (reset on disconnect). */
data class LinkMetrics(
    var transmittedBytes: Long = 0,
    var receivedBytes: Long = 0,
    var packetsSent: Int = 0,
    var packetsReceived: Int = 0,
    var controlPacketsSent: Int = 0,
    var retransmissions: Int = 0,
    var packetLoss: Int = 0,
    var corruptedFrames: Int = 0,
    var duplicatePackets: Int = 0,
    var roundTripTimeMs: Long = 0,
    var ackLatencyMs: Long = 0,
    var lastMessageLatencyMs: Long = 0
)

enum class MessageState {
    IDLE,
    LISTENING,
    PROCESSING,
    ENCODING,
    TRANSMITTING,
    WAITING_ACK,
    RECEIVING,
    DECODING,
    COMPLETE,
    FAILED
}

/**
 * Sender/Receiver-side state of the most recent message, surfaced to the UI.
 */
data class MessageInfo(
    val state: MessageState = MessageState.IDLE,
    val detail: String = "",
    val originalBytes: Int = 0,
    val encodedBytes: Int = 0,
    val transmittedBytes: Int = 0,
    val packetCount: Int = 0,
    val transmittedPackets: Int = 0,
    val acknowledgedPackets: Int = 0,
    val retransmissions: Int = 0,
    val roundTripTimeMs: Long = 0,
    val totalLatencyMs: Long = 0,
    val failed: Boolean = false,
    val isEmergency: Boolean = false
)

/** Per-message metadata for a payload that arrived on the wire. */
data class ReassembledPayload(
    val messageId: Long,
    val payload: ByteArray,
    val language: Language,
    val isEmergency: Boolean,
    val receivedAt: Long,
    val dataPackets: Int,
    val acksSent: Int,
    val retransmissionRequestsSent: Int,
    val sessionId: String
)

sealed class LinkMessageEvent {
    data class Connected(val session: ConnectionSession) : LinkMessageEvent()
    data class Disconnected(val reason: String) : LinkMessageEvent()
    data class StatusChange(val status: ConnectionStatus) : LinkMessageEvent()
    data class Received(
        val messageId: Long,
        val payloadBytes: Int,
        val dataPackets: Int,
        val isEmergency: Boolean,
        val receivedAt: Long
    ) : LinkMessageEvent()
    data class Transmitted(
        val messageId: Long,
        val transmittedBytes: Int,
        val packetCount: Int,
        val retransmissions: Int,
        val roundTripTimeMs: Long,
        val failed: Boolean
    ) : LinkMessageEvent()
}

/** Outcome of [TransportEngine.send], measuring actual bytes/latency. */
data class SendResult(
    val messageId: Long,
    val transmittedBytes: Int,
    val packetCount: Int,
    val retransmissions: Int,
    val roundTripTimeMs: Long,
    val failed: Boolean,
    val detail: String = ""
)