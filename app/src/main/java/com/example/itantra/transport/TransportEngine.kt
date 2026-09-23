package com.example.itantra.transport

import com.example.itantra.protocol.Packet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TransportEngine {
    val connectionStatus: StateFlow<ConnectionStatus>
    val receivedPackets: Flow<Packet>

    suspend fun startServer(port: Int = 9876)
    suspend fun connect(host: String, port: Int = 9876)
    suspend fun disconnect()
    suspend fun send(packet: Packet)
    fun getConnectionStatus(): ConnectionStatus
    fun getTransportType(): TransportType
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class TransportType {
    WIFI,
    BLUETOOTH,
    SIMULATED
}
