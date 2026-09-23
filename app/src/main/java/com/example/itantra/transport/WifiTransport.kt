package com.example.itantra.transport

import android.util.Log
import com.example.itantra.protocol.Packet
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

class WifiTransport : TransportEngine {

    companion object {
        private const val TAG = "WifiTransport"
        private const val BUFFER_SIZE = 8192
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _receivedPackets = Channel<Packet>(Channel.BUFFERED)
    override val receivedPackets: Flow<Packet> = _receivedPackets.receiveAsFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var receiveJob: Job? = null

    private val sendQueue = ConcurrentLinkedQueue<Packet>()
    private var sendJob: Job? = null

    override suspend fun startServer(port: Int) {
        withContext(Dispatchers.IO) {
            try {
                _connectionStatus.value = ConnectionStatus.CONNECTING
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Server started on port $port, waiting for connection...")

                serverJob = scope.launch {
                    try {
                        val socket = serverSocket!!.accept()
                        handleNewConnection(socket)
                    } catch (e: Exception) {
                        if (_connectionStatus.value != ConnectionStatus.DISCONNECTED) {
                            Log.e(TAG, "Server accept error", e)
                            _connectionStatus.value = ConnectionStatus.ERROR
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    override suspend fun connect(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                _connectionStatus.value = ConnectionStatus.CONNECTING
                val socket = Socket(host, port)
                handleNewConnection(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $host:$port", e)
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    private fun handleNewConnection(socket: Socket) {
        clientSocket = socket
        input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        _connectionStatus.value = ConnectionStatus.CONNECTED
        Log.i(TAG, "Connected to ${socket.remoteSocketAddress}")

        startReceiving()
        startSending()
    }

    private fun startReceiving() {
        receiveJob = scope.launch {
            try {
                while (isActive && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                    val input = input ?: break

                    // Read packet length
                    val length = input.readInt()
                    if (length <= 0 || length > BUFFER_SIZE * 10) {
                        Log.w(TAG, "Invalid packet length: $length")
                        continue
                    }

                    // Read packet data
                    val data = ByteArray(length)
                    input.readFully(data)

                    // Parse packet
                    val packet = Packet.deserialize(data)
                    if (packet != null) {
                        _receivedPackets.send(packet)
                    } else {
                        Log.w(TAG, "Failed to deserialize packet")
                    }
                }
            } catch (e: EOFException) {
                Log.i(TAG, "Connection closed by remote")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            } catch (e: Exception) {
                if (_connectionStatus.value != ConnectionStatus.DISCONNECTED) {
                    Log.e(TAG, "Receive error", e)
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            }
        }
    }

    private fun startSending() {
        sendJob = scope.launch {
            try {
                while (isActive && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                    val packet = sendQueue.poll()
                    if (packet != null) {
                        sendPacketInternal(packet)
                    } else {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                    Log.e(TAG, "Send error", e)
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            }
        }
    }

    override suspend fun send(packet: Packet) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
            Log.w(TAG, "Cannot send: not connected")
            return
        }
        sendQueue.add(packet)
    }

    private fun sendPacketInternal(packet: Packet) {
        try {
            val data = packet.serialize()
            val output = output ?: return

            output.writeInt(data.size)
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet", e)
        }
    }

    override suspend fun disconnect() {
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        receiveJob?.cancel()
        sendJob?.cancel()
        serverJob?.cancel()

        try {
            input?.close()
            output?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connections", e)
        }

        input = null
        output = null
        clientSocket = null
        serverSocket = null
    }

    override fun getConnectionStatus(): ConnectionStatus = _connectionStatus.value

    override fun getTransportType(): TransportType = TransportType.WIFI

    fun getLocalIpAddress(): String? {
        return try {
            val socket = java.net.Socket("8.8.8.8", 53)
            val localAddress = socket.localAddress.hostAddress
            socket.close()
            localAddress
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
