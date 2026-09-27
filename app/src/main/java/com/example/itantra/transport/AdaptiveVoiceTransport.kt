package com.example.itantra.transport

import com.example.itantra.codec.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Intelligent transport delegator for real-time walkie-talkie audio.
 *
 * Problem:
 * Physical BLE GATT transfers operate with higher latency (~150-300ms) due to connection
 * intervals, chunking, and physical bitrates. Wi-Fi TCP/UDP sockets operate with ~20ms latency.
 *
 * Solution:
 * [AdaptiveVoiceTransport] prefers Wi-Fi sockets whenever available/connected for real-time
 * walkie-talkie audio (PTT), cutting latency down from ~150-300ms to ~20ms. When Wi-Fi is
 * not connected, it falls back to high-throughput BLE (with 2M PHY, high priority, and 512 MTU).
 */
class AdaptiveVoiceTransport(
    private val primaryEngineProvider: () -> TransportEngine,
    val wifiTransport: TransportEngine,
    private val isPTTActiveProvider: () -> Boolean = { true }
) : TransportEngine {

    companion object {
        private const val TAG = "AdaptiveVoiceTransport"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _incomingPayloads = MutableSharedFlow<ReassembledPayload>(extraBufferCapacity = 64)
    override val incomingPayloads: SharedFlow<ReassembledPayload> = _incomingPayloads.asSharedFlow()

    private val _linkEvents = MutableSharedFlow<LinkMessageEvent>(extraBufferCapacity = 64)
    override val linkEvents: SharedFlow<LinkMessageEvent> = _linkEvents.asSharedFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _session = MutableStateFlow<ConnectionSession?>(null)
    override val session: StateFlow<ConnectionSession?> = _session.asStateFlow()

    private val _linkMetrics = MutableStateFlow(LinkMetrics())
    override val linkMetrics: StateFlow<LinkMetrics> = _linkMetrics.asStateFlow()

    private val _messageInfo = MutableStateFlow(MessageInfo())
    override val messageInfo: StateFlow<MessageInfo> = _messageInfo.asStateFlow()

    init {
        // Collect incoming payloads from Wi-Fi transport (fast path)
        scope.launch {
            wifiTransport.incomingPayloads.collect { payload ->
                _incomingPayloads.emit(payload)
            }
        }
        // Collect link events from Wi-Fi transport
        scope.launch {
            wifiTransport.linkEvents.collect { event ->
                _linkEvents.emit(event)
            }
        }
    }

    fun startListeningTo(engine: TransportEngine) {
        scope.launch {
            engine.incomingPayloads.collect { payload ->
                _incomingPayloads.emit(payload)
            }
        }
        scope.launch {
            engine.linkEvents.collect { event ->
                _linkEvents.emit(event)
            }
        }
        scope.launch {
            engine.connectionStatus.collect { status ->
                if (!wifiTransport.isConnected()) {
                    _connectionStatus.value = status
                }
            }
        }
        scope.launch {
            engine.session.collect { s ->
                if (!wifiTransport.isConnected() || s != null) {
                    _session.value = s
                }
            }
        }
        scope.launch {
            engine.linkMetrics.collect { m ->
                if (!wifiTransport.isConnected()) {
                    _linkMetrics.value = m
                }
            }
        }
        scope.launch {
            engine.messageInfo.collect { info ->
                _messageInfo.value = info
            }
        }
    }

    /**
     * Resolves the target transport engine:
     * Wi-Fi (~20ms latency) is strictly preferred for real-time walkie-talkie audio
     * whenever Wi-Fi is connected.
     */
    fun resolveActiveTransport(): TransportEngine {
        val primary = primaryEngineProvider()
        // If Wi-Fi is connected, prefer Wi-Fi for real-time walkie-talkie audio!
        if (wifiTransport.isConnected()) {
            return wifiTransport
        }
        return primary
    }

    override val transportType: TransportType
        get() = resolveActiveTransport().transportType

    override fun observeIncoming(): Flow<ByteArray> = resolveActiveTransport().observeIncoming()

    override suspend fun startHost(port: Int): Boolean = primaryEngineProvider().startHost(port)

    override suspend fun connect(host: String, port: Int): Boolean = primaryEngineProvider().connect(host, port)

    override suspend fun disconnect() {
        primaryEngineProvider().disconnect()
        if (wifiTransport !== primaryEngineProvider()) {
            wifiTransport.disconnect()
        }
    }

    override suspend fun send(
        data: ByteArray,
        language: Language,
        isEmergency: Boolean,
        priority: Byte
    ): SendResult? {
        val engine = resolveActiveTransport()
        try {
            android.util.Log.i(
                TAG,
                "[VOICE-ROUTING] Routing transmission over ${engine.transportType.name} (Wi-Fi preferred: ${wifiTransport.isConnected()})"
            )
        } catch (_: Throwable) {}
        return engine.send(data, language, isEmergency, priority)
    }

    override fun isConnected(): Boolean {
        return resolveActiveTransport().isConnected() || primaryEngineProvider().isConnected()
    }

    override fun getLocalAddress(): String = resolveActiveTransport().getLocalAddress()

    override fun getDeviceId(): String = resolveActiveTransport().getDeviceId()

    override fun getRoleLabel(): String = resolveActiveTransport().getRoleLabel()

    override fun getLinkName(): String {
        val engine = resolveActiveTransport()
        return if (engine.transportType == TransportType.WIFI) {
            "WIFI (PREFERRED) // ${engine.getLocalAddress()}"
        } else {
            "${engine.getLinkName()} (BLE HIGH-THROUGHPUT)"
        }
    }

    override fun getLastError(): String? = resolveActiveTransport().getLastError()

    override fun getLastPacketSummary(): String = resolveActiveTransport().getLastPacketSummary()

    override fun getLastCrcStatus(): Boolean? = resolveActiveTransport().getLastCrcStatus()
}
