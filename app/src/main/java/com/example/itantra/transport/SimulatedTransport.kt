package com.example.itantra.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

/**
 * In-process RETRO link.
 *
 * Two modes:
 *  - **Self loopback** (default, single device): every frame the engine sends
 *    is fed straight back through its own receive path, so a single phone can
 *    exercise the full stack (capability handshake, framing, ACK/NACK,
 *    reassembly, retransmission under simulation) without a peer.
 *  - **Bound pair** (integration tests): [bindPeer] connects two engine
 *    instances through [Channel]s so both host/device sides are real.
 *
 * Physical details are irrelevant here: [openLinkAsHost]/[openLinkAsClient]
 * return immediately and the negotiated handshake happens over the loop.
 */
class SimulatedTransport(
    networkSimulator: NetworkSimulator,
    ackTimeoutMs: Long = 2000L,
    maxRetries: Int = 3
) : BaseTransportEngine(
    transportType = TransportType.SIMULATED,
    engineName = "SIMULATED",
    networkSimulator = networkSimulator,
    ackTimeoutMs = ackTimeoutMs,
    maxRetries = maxRetries
) {

    private val rxPipe = Channel<ByteArray>(Channel.UNLIMITED)
    private var peerRx: Channel<ByteArray>? = null

    private var pumpStarted = false

    private fun startPump() {
        if (pumpStarted) return
        pumpStarted = true
        baseScope.launch {
            for (chunk in rxPipe) {
                deliverIncoming(chunk)
            }
        }
    }

    init {
        startPump()
    }

    /** Wire this engine to another instance so they act as true peers. */
    fun bindPeer(other: SimulatedTransport) {
        other.startPump()
        this.peerRx = other.rxPipe
        other.peerRx = this.rxPipe
    }

    fun isLoopback(): Boolean = peerRx == null

    override suspend fun openLinkAsHost(port: Int): Boolean = true

    override suspend fun openLinkAsClient(host: String, port: Int): Boolean = true

    override suspend fun writeRawFrame(frame: ByteArray) {
        val peer = peerRx
        if (peer != null) {
            peer.send(frame)
        } else {
            // Strict FIFO, same coroutine: write path == read path, in order.
            deliverIncoming(frame)
        }
    }

    override suspend fun tearDown() {
        // nothing physical to close
    }

    override fun pollLocalAddress(): String =
        if (peerRx == null) "loopback" else "simulated://virtual"
}