package com.example.itantra.transport

import android.util.Log
import com.example.itantra.protocol.Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentLinkedQueue

class SimulatedTransport : TransportEngine {

    companion object {
        private const val TAG = "SimulatedTransport"
    }

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _receivedPackets = Channel<Packet>(Channel.BUFFERED)
    override val receivedPackets: Flow<Packet> = _receivedPackets.receiveAsFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var packetLossRate = 0.0f
    private var latencyMs = 0L

    private var remoteTransport: SimulatedTransport? = null

    fun setPacketLoss(rate: Float) {
        packetLossRate = rate.coerceIn(0f, 1f)
    }

    fun setLatency(ms: Long) {
        latencyMs = ms
    }

    fun connectToRemote(remote: SimulatedTransport) {
        remoteTransport = remote
        _connectionStatus.value = ConnectionStatus.CONNECTED
        remote._connectionStatus.value = ConnectionStatus.CONNECTED
    }

    override suspend fun startServer(port: Int) {
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    override suspend fun connect(host: String, port: Int) {
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    override suspend fun disconnect() {
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        remoteTransport?._connectionStatus?.value = ConnectionStatus.DISCONNECTED
        remoteTransport = null
    }

    override suspend fun send(packet: Packet) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) return
        if (remoteTransport == null) return

        // Simulate packet loss
        if (Math.random() < packetLossRate) {
            Log.d(TAG, "Packet lost: msg=${packet.messageId} seq=${packet.sequenceId}")
            return
        }

        // Simulate latency
        if (latencyMs > 0) {
            delay(latencyMs)
        }

        remoteTransport!!._receivedPackets.send(packet)
    }

    override fun getConnectionStatus(): ConnectionStatus = _connectionStatus.value

    override fun getTransportType(): TransportType = TransportType.SIMULATED

    fun getStats(): SimulatedTransportStats {
        return SimulatedTransportStats(
            packetLossRate = packetLossRate,
            latencyMs = latencyMs,
            connected = _connectionStatus.value == ConnectionStatus.CONNECTED
        )
    }
}

data class SimulatedTransportStats(
    val packetLossRate: Float,
    val latencyMs: Long,
    val connected: Boolean
)
