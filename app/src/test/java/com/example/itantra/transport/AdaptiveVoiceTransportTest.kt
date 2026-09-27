package com.example.itantra.transport

import com.example.itantra.codec.Language
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveVoiceTransportTest {

    private class FakeTransport(
        override val transportType: TransportType,
        private var connected: Boolean = false
    ) : TransportEngine {
        private val _status = MutableStateFlow(if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED)
        override val connectionStatus: StateFlow<ConnectionStatus> = _status.asStateFlow()

        private val _session = MutableStateFlow<ConnectionSession?>(null)
        override val session: StateFlow<ConnectionSession?> = _session.asStateFlow()

        override val linkMetrics: StateFlow<LinkMetrics> = MutableStateFlow(LinkMetrics()).asStateFlow()
        override val messageInfo: StateFlow<MessageInfo> = MutableStateFlow(MessageInfo()).asStateFlow()

        private val _linkEvents = MutableSharedFlow<LinkMessageEvent>(extraBufferCapacity = 16)
        override val linkEvents: SharedFlow<LinkMessageEvent> = _linkEvents.asSharedFlow()

        private val _incomingPayloads = MutableSharedFlow<ReassembledPayload>(extraBufferCapacity = 16)
        override val incomingPayloads: SharedFlow<ReassembledPayload> = _incomingPayloads.asSharedFlow()

        var sendCount = 0
        var lastSentPriority: Byte = 0

        override fun observeIncoming() = flowOf(ByteArray(0))
        override suspend fun startHost(port: Int): Boolean = true
        override suspend fun connect(host: String, port: Int): Boolean = true
        override suspend fun disconnect() {
            connected = false
            _status.value = ConnectionStatus.DISCONNECTED
        }

        override suspend fun send(data: ByteArray, language: Language, isEmergency: Boolean, priority: Byte): SendResult {
            sendCount++
            lastSentPriority = priority
            return SendResult(
                messageId = 1L,
                transmittedBytes = data.size,
                packetCount = 1,
                retransmissions = 0,
                roundTripTimeMs = if (transportType == TransportType.WIFI) 20L else 180L,
                failed = false,
                detail = "${transportType.name} delivery"
            )
        }

        override fun isConnected(): Boolean = connected
        fun setConnected(value: Boolean) {
            connected = value
            _status.value = if (value) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
        }

        override fun getLocalAddress(): String = "192.168.1.100"
        override fun getDeviceId(): String = "dev-${transportType.name}"
        override fun getRoleLabel(): String = "PEER"
        override fun getLinkName(): String = "${transportType.name} // 192.168.1.100"
    }

    @Test
    fun `prefers wifi sockets for real-time walkie-talkie audio when wifi is connected`() = runBlocking {
        val simEngine = FakeTransport(TransportType.SIMULATED, connected = true)
        val wifiEngine = FakeTransport(TransportType.WIFI, connected = false)

        val adaptive = AdaptiveVoiceTransport(
            primaryEngineProvider = { simEngine },
            wifiTransport = wifiEngine,
            isPTTActiveProvider = { true }
        )

        // When Wi-Fi is not connected, routes via primary engine
        assertFalse(wifiEngine.isConnected())
        assertEquals(TransportType.SIMULATED, adaptive.resolveActiveTransport().transportType)

        // When Wi-Fi is connected, Wi-Fi is strictly preferred (~20ms latency socket)
        wifiEngine.setConnected(true)
        assertTrue(wifiEngine.isConnected())
        assertEquals(TransportType.WIFI, adaptive.resolveActiveTransport().transportType)
    }

    @Test
    fun `send routes through active engine correctly`() = runBlocking {
        val primary = FakeTransport(TransportType.BLUETOOTH, connected = true)
        val wifi = FakeTransport(TransportType.WIFI, connected = false)

        val adaptive = AdaptiveVoiceTransport(
            primaryEngineProvider = { primary },
            wifiTransport = wifi,
            isPTTActiveProvider = { true }
        )

        val result = adaptive.send("test audio".toByteArray(), Language.ENGLISH, false, 2)
        assertEquals(1, primary.sendCount)
        assertEquals(180L, result?.roundTripTimeMs)

        // Now activate Wi-Fi: send routes through Wi-Fi (~20ms latency)
        wifi.setConnected(true)
        val wifiResult = adaptive.send("fast audio".toByteArray(), Language.ENGLISH, false, 2)
        assertEquals(1, wifi.sendCount)
        assertEquals(20L, wifiResult?.roundTripTimeMs)
    }
}
