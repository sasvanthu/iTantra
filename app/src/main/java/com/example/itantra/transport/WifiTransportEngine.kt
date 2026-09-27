package com.example.itantra.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TCP-socket RETRO link for real two-phone communication over the same
 * Wi-Fi / hotspot network. No internet required.
 *
 * Host binds a [ServerSocket] (status WAITING) and accepts exactly one peer;
 * the device connects out via [connect]. One active session at a time; a
 * reconnect always starts a fresh epoch so old-session frames are dropped.
 */
class WifiTransportEngine(
    networkSimulator: NetworkSimulator,
    ackTimeoutMs: Long = 2000L,
    maxRetries: Int = 3
) : BaseTransportEngine(
    transportType = TransportType.WIFI,
    engineName = "WIFI",
    networkSimulator = networkSimulator,
    ackTimeoutMs = ackTimeoutMs,
    maxRetries = maxRetries
) {

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    val isServerListening: Boolean
        get() = serverSocket?.let { it.isBound && !it.isClosed } ?: false

    override suspend fun openLinkAsHost(port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                closeSocket()
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                serverSocket = server
                if (!server.isBound || server.isClosed) {
                    setEngineError("Server socket failed to bind to port $port")
                    closeSocket()
                    return@withContext false
                }
                val accepted = server.accept()
                accepted.tcpNoDelay = true
                setUpSocket(accepted)
                true
            } catch (e: Exception) {
                setEngineError("Wi-Fi Host error: ${e.message ?: "port $port bind/accept failed"}")
                closeSocket()
                false
            }
        }
    }

    override suspend fun openLinkAsClient(host: String, port: Int): Boolean {
        val targetHost = host.trim()
        if (targetHost.isEmpty()) {
            setEngineError("Host IP address is required for Wi-Fi connection")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                closeSocket()
                val s = Socket()
                s.connect(InetSocketAddress(targetHost, port), 6000)
                s.tcpNoDelay = true
                setUpSocket(s)
                true
            } catch (e: Exception) {
                if (!disconnectRequested()) {
                    setEngineError("Wi-Fi connect to $targetHost:$port failed: ${e.message ?: "connection refused or timed out"}")
                }
                closeSocket()
                false
            }
        }
    }

    private fun setUpSocket(s: Socket) {
        socket = s
        output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 8192))

        baseScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) feedIncoming(buffer.copyOf(n))
                    }
                }
                finishSession("peer closed the link")
            } catch (e: Exception) {
                if (disconnectRequested()) return@launch
                finishSession("link error: ${e.message ?: "io"}")
            }
        }
    }

    private fun disconnectRequested(): Boolean = !isSessionActive()

    override suspend fun writeRawFrame(frame: ByteArray) {
        val out = output ?: return
        withContext(Dispatchers.IO) {
            try {
                synchronized(writeLock) {
                    out.write(frame)
                    out.flush()
                }
            } catch (e: Exception) {
                // link dying; write loop exits next iteration
            }
        }
    }

    override suspend fun tearDown() {
        withContext(Dispatchers.IO) {
            closeSocket()
        }
    }

    private fun closeSocket() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        output = null
        socket = null
        serverSocket = null
    }

    override fun pollLocalAddress(): String {
        socket?.let { s ->
            s.localAddress?.let { a ->
                if (a is Inet4Address && !a.isAnyLocalAddress && !a.isLoopbackAddress) {
                    return a.hostAddress ?: ""
                }
            }
        }
        return detectWifiAddress()
    }

    /**
     * Finds the real Wi-Fi / SoftAP (hotspot) IPv4 address.
     * Prioritizes wlan/ap/softap/eth interfaces and explicitly rejects cellular (rmnet/pdp).
     */
    private fun detectWifiAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return ""

            // Priority 1: Interfaces explicitly matching Wi-Fi or Hotspot names
            val wifiInterfaces = interfaces.filter { net ->
                val name = net.name.lowercase(java.util.Locale.ROOT)
                (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap") ||
                 name.startsWith("swlan") || name.startsWith("wigig") || name.startsWith("eth"))
            }

            for (net in wifiInterfaces) {
                for (addr in net.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.isNotEmpty() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }

            // Priority 2: Non-cellular interfaces with site-local IPv4
            val nonCellular = interfaces.filterNot { net ->
                val name = net.name.lowercase(java.util.Locale.ROOT)
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("tap") ||
                name.startsWith("sit") || name.startsWith("radio") || net.isLoopback
            }

            for (net in nonCellular) {
                for (addr in net.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.isNotEmpty() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }

            // Priority 3: Any non-cellular non-loopback IPv4
            for (net in nonCellular) {
                for (addr in net.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.isNotEmpty() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }

            // Priority 4: Fallback to any site-local address
            for (net in interfaces) {
                if (net.isLoopback) continue
                for (addr in net.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.isNotEmpty()) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // enumerating interfaces failed
        }
        return ""
    }

    override fun getLinkName(): String {
        val addr = pollLocalAddress()
        return if (addr.isNotEmpty()) "WIFI // $addr" else "WIFI"
    }
}