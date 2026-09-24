package com.example.itantra.transport

import com.example.itantra.codec.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Codec-independent RETRO link transport.
 *
 * A [TransportEngine] knows nothing about the RETRO codec internals: it moves
 * opaque [ByteArray] payloads between two endpoints over whatever medium
 * [transportType] describes (TCP sockets today, BLE/mesh later). Framing,
 * handshake, ACK/NACK reliability, duplicate detection, reassembly and
 * retransmission live *inside* the transport so the speech codec stays a
 * pure representation layer.
 *
 * ```
 * Phone A: speech -> RetroSpeechCodec -> engine.send(bytes)
 * Phone B: engine.incomingPayloads -> RetroSpeechDecoder -> text
 * ```
 */
interface TransportEngine {

    val transportType: TransportType

    /** Current link status (DISCONNECTED / WAITING / CONNECTING / CONNECTED / ERROR). */
    val connectionStatus: StateFlow<ConnectionStatus>

    /** Present while a link is established; null on any disconnect. */
    val session: StateFlow<ConnectionSession?>

    /** Running totals for the current session. */
    val linkMetrics: StateFlow<LinkMetrics>

    /** State machine of the most recent transmitted/received message. */
    val messageInfo: StateFlow<MessageInfo>

    /** Lifecycle + message events (connected, disconnected, transmitted, received). */
    val linkEvents: SharedFlow<LinkMessageEvent>

    /** Fully reassembled, integrity-checked payloads (cryptographic session order). */
    val incomingPayloads: SharedFlow<ReassembledPayload>

    /** Convenience alias to [incomingPayloads] payloads (raw bytes). */
    fun observeIncoming(): Flow<ByteArray>

    /** Bind/accept incoming connection as the HOST on [port]. Suspends until accepted & connected. */
    suspend fun startHost(port: Int): Boolean

    /** Connect to a HOST at [host]:[port] and perform the capability handshake. */
    suspend fun connect(host: String, port: Int): Boolean

    /** Tear down the link, reset the session and metrics, cancel pending work. */
    suspend fun disconnect()

    /**
     * Reliably send one message [data] (encoded codec payload). Suspends until
     * every packet is ACKed (or retries are exhausted). Returns null when the
     * link is not usable.
     */
    suspend fun send(
        data: ByteArray,
        language: Language = Language.ENGLISH,
        isEmergency: Boolean = false,
        priority: Byte = 2
    ): SendResult?

    fun isConnected(): Boolean

    /** Best-effort display address of this device over the active link. */
    fun getLocalAddress(): String

    /** Stable per-process device identifier, readable even while disconnected. */
    fun getDeviceId(): String

    /** "HOST" or "DEVICE". */
    fun getRoleLabel(): String

    /** Human label like "WIFI // 192.168.1.5:9876". */
    fun getLinkName(): String
}