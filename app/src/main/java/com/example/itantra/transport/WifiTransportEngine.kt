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

    override suspend fun openLinkAsHost(port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                serverSocket = server
                val accepted = server.accept()
                accepted.tcpNoDelay = true
                setUpSocket(accepted)
                true
            } catch (e: Exception) {
                closeSocket()
                false
            }
        }
    }

    override suspend fun openLinkAsClient(host: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                setUpSocket(s)
                true
            } catch (e: Exception) {
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
                if (a is Inet4Address && !a.isAnyLocalAddress) return a.hostAddress ?: ""
            }
        }
        return detectWifiAddress()
    }

    /** Prefer the site-local (wifi/hotspot) IPv4, falling back to any IPv4. */
    private fun detectWifiAddress(): String {
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            for (net in all) {
                if (!net.isUp || net.isLoopback || net.isVirtual) continue
                for (addr in net.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            val allAgain = NetworkInterface.getNetworkInterfaces()
            for (net in allAgain) {
                if (!net.isUp || net.isLoopback || net.isVirtual) continue
                for (addr in net.inetAddresses) {
                    if (addr is Inet4Address && !addr.isAnyLocalAddress) {
                        return addr.hostAddress ?: ""
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