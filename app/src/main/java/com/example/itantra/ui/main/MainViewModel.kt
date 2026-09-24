package com.example.itantra.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.itantra.*
import com.example.itantra.codec.*
import com.example.itantra.data.ExperimentLogger
import com.example.itantra.metrics.MetricsEngine
import com.example.itantra.speech.stt.VoskSTTEngine
import com.example.itantra.speech.tts.AndroidTTSEngine
import com.example.itantra.transport.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as iTantraApp

    private val sttEngine = VoskSTTEngine()
    private val ttsEngine = AndroidTTSEngine()
    private val retroCodec = RetroSpeechCodec()
    private val baselineCodec = BaselineCodec()

    private val networkSimulator = NetworkSimulator()
    private val wifiTransport = WifiTransportEngine(networkSimulator)
    private val simulatedTransport = SimulatedTransport(networkSimulator)
    private val bleTransport = BleTransportEngine(application, networkSimulator)
    private val meshTransport = MeshTransportEngine()

    /** Cache so [setTransportMode] only attaches the point-to-point engines once. */
    private var meshEdgesAttached = false

    private var useRetroCodec = true
    private var speechPipeline: SpeechPipeline? = null

    private var transportObserverJob: Job? = null
    private var pipelineStateJob: Job? = null
    private var pipelineIncomingJob: Job? = null

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    private val _codecLab = MutableStateFlow(CodecLabState())
    val codecLab: StateFlow<CodecLabState> = _codecLab

    private val _manualText = MutableStateFlow("")
    val manualText: StateFlow<String> = _manualText

    data class ReceivedMessageUI(
        val text: String,
        val language: Language,
        val timestamp: Long,
        val isEmergency: Boolean
    )

    data class UIState(
        val isServer: Boolean = false,
        val isConnected: Boolean = false,
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val subStatusLabel: String = "DISCONNECTED",
        val ipAddress: String = "",
        val port: String = "9876",
        val remoteDeviceId: String = "",
        val sessionId: String = "",
        val protocolVersion: Int = 0,
        val codecVersion: Int = 0,
        val supportedLanguages: List<String> = emptyList(),
        val role: String = "HOST",
        val transportMode: TransportType = TransportType.WIFI,

        val isRecording: Boolean = false,
        val isProcessing: Boolean = false,
        val currentLanguage: Language = Language.ENGLISH,
        val mode: String = "RETRO",
        val lastSentText: String = "",
        val lastReceivedText: String = "",
        val lastSendReport: SpeechPipeline.SpeechSendReport? = null,
        val metrics: MetricsEngine.FullMetrics? = null,
        val codecComparison: EncodingComparison? = null,
        val speechLoopback: CodecLabResult? = null,
        val isPTTMode: Boolean = false,
        val activeTab: Int = 0,

        val simulationEnabled: Boolean = false,
        val simConfig: NetworkSimConfig = NetworkSimConfig(),
        val linkMetrics: LinkMetrics = LinkMetrics(),
        val messageInfo: MessageInfo = MessageInfo(),
        val receivedMessages: List<ReceivedMessageUI> = emptyList(),
        val linkError: String? = null,
        val linkStatusLine: String = "",
        val signalStrength: Int = 0,
        val packetCount: Pair<Int, Int> = Pair(0, 0),
        val compressionPercent: Double = 0.0,
        val latencyMs: Long = 0
    )

    data class CodecLabState(
        val input: String = "",
        val language: Language = Language.ENGLISH,
        val running: Boolean = false,
        val encodeResult: CodecLabResult? = null,
        val decodeResult: CodecLabResult? = null,
        val payload: ByteArray? = null
    )

    init {
        startObservation()
    }

    // ------------------------------------------------------------------
    // Engine + pipeline wiring
    // ------------------------------------------------------------------

    private fun activeEngine(): TransportEngine =
        when (_uiState.value.transportMode) {
            TransportType.WIFI -> wifiTransport
            TransportType.SIMULATED -> simulatedTransport
            TransportType.BLUETOOTH -> bleTransport
            TransportType.MESH -> meshTransport
        }

    private fun currentCodec(): SpeechCodec = if (useRetroCodec) retroCodec else baselineCodec

    private fun startObservation() {
        transportObserverJob?.cancel()
        pipelineStateJob?.cancel()
        pipelineIncomingJob?.cancel()

        val engine = activeEngine()

        speechPipeline = SpeechPipeline(
            context = getApplication(),
            sttEngine = sttEngine,
            ttsEngine = ttsEngine,
            speechCodec = currentCodec(),
            transport = engine,
            metricsEngine = app.metricsEngine
        )
        speechPipeline?.loopbackHandler = { text, lang -> currentCodec().performLab(text, lang) }
        speechPipeline?.setLanguage(_uiState.value.currentLanguage)
        speechPipeline?.setPTTMode(_uiState.value.isPTTMode)
        speechPipeline?.startReceiving()

        pipelineStateJob = viewModelScope.launch {
            speechPipeline?.pipelineState?.collect { ps ->
                _uiState.update {
                    it.copy(
                        isProcessing = ps.isProcessing,
                        lastSentText = ps.lastSentText,
                        lastReceivedText = if (ps.lastReceivedText.isNotEmpty()) ps.lastReceivedText else it.lastReceivedText,
                        speechLoopback = ps.lastLoopback,
                        lastSendReport = ps.lastSendReport,
                        metrics = ps.currentMetrics ?: it.metrics,
                        latencyMs = ps.currentMetrics?.latency?.totalLatencyMs ?: it.latencyMs,
                        compressionPercent = ps.currentMetrics?.size?.compressionPercentage ?: it.compressionPercent
                    )
                }
            }
        }

        pipelineIncomingJob = viewModelScope.launch {
            speechPipeline?.incomingMessages?.collect { msg ->
                _uiState.update {
                    it.copy(
                        lastReceivedText = msg.text,
                        receivedMessages = (it.receivedMessages + ReceivedMessageUI(
                            text = msg.text,
                            language = msg.language,
                            timestamp = msg.timestamp,
                            isEmergency = msg.isEmergency
                        )).takeLast(20)
                    )
                }
            }
        }

        transportObserverJob = viewModelScope.launch {
            supervisorScope {
                launch { observeStatus(engine) }
                launch { observeSession(engine) }
                launch { observeLinkMetrics(engine) }
                launch { observeMessageInfo(engine) }
                launch { observeEvents(engine) }
            }
        }
    }

    private suspend fun observeStatus(engine: TransportEngine) {
        engine.connectionStatus.collect { s ->
            _uiState.update {
                it.copy(
                    connectionStatus = s,
                    subStatusLabel = when (s) {
                        ConnectionStatus.DISCONNECTED -> "DISCONNECTED"
                        ConnectionStatus.CONNECTING -> "CONNECTING"
                        ConnectionStatus.WAITING -> "WAITING"
                        ConnectionStatus.CONNECTED -> "CONNECTED"
                        ConnectionStatus.ERROR -> "ERROR"
                    },
                    isConnected = s == ConnectionStatus.CONNECTED,
                    ipAddress = if (s != ConnectionStatus.DISCONNECTED) engine.getLocalAddress() else it.ipAddress
                )
            }
        }
    }

    private suspend fun observeSession(engine: TransportEngine) {
        engine.session.collect { s ->
            _uiState.update {
                it.copy(
                    remoteDeviceId = s?.remoteDeviceId ?: "",
                    sessionId = s?.sessionId ?: "",
                    protocolVersion = s?.protocolVersion ?: 0,
                    codecVersion = s?.codecVersion ?: 0,
                    supportedLanguages = s?.supportedLanguages?.map { l -> l.displayName } ?: emptyList(),
                    role = if (s != null) engine.getRoleLabel() else it.role,
                    ipAddress = if (s != null) engine.getLocalAddress() else it.ipAddress
                )
            }
        }
    }

    private suspend fun observeLinkMetrics(engine: TransportEngine) {
        engine.linkMetrics.collect { m ->
            _uiState.update {
                it.copy(
                    linkMetrics = m,
                    packetCount = Pair(m.packetsSent, m.packetsReceived)
                )
            }
        }
    }

    private suspend fun observeMessageInfo(engine: TransportEngine) {
        engine.messageInfo.collect { m ->
            _uiState.update { it.copy(messageInfo = m) }
        }
    }

    private suspend fun observeEvents(engine: TransportEngine) {
        engine.linkEvents.collect { ev ->
            when (ev) {
                is LinkMessageEvent.Connected -> {
                    _uiState.update {
                        it.copy(
                            linkError = null,
                            linkStatusLine = "● LINK ESTABLISHED → ${ev.session.remoteDeviceId}",
                            ipAddress = engine.getLocalAddress()
                        )
                    }
                }
                is LinkMessageEvent.Disconnected -> {
                    _uiState.update {
                        it.copy(linkError = ev.reason, linkStatusLine = "LINK CLOSED: ${ev.reason}")
                    }
                }
                is LinkMessageEvent.Transmitted -> { /* surfaced through messageInfo */ }
                is LinkMessageEvent.Received -> { /* surfaced through pipeline */ }
                is LinkMessageEvent.StatusChange -> { /* handled in observeStatus */ }
            }
        }
    }

    // ------------------------------------------------------------------
    // Connection controls
    // ------------------------------------------------------------------

    fun startHost() {
        _uiState.update { it.copy(isServer = true, linkError = null) }
        if (_uiState.value.transportMode == TransportType.MESH) {
            launchMeshHost()
            return
        }
        viewModelScope.launch {
            val engine = activeEngine()
            if (engine.startHost(_uiState.value.port.toIntOrNull() ?: 9876)) {
                _uiState.update { it.copy(ipAddress = engine.getLocalAddress()) }
            } else {
                val message = when (_uiState.value.transportMode) {
                    TransportType.BLUETOOTH -> "BLUETOOTH HOST FAILED (BT off / permissions?)"
                    TransportType.MESH -> "MESH INACTIVE (no live edge to relay over)"
                    else -> "HOST FAILED (port busy?)"
                }
                _uiState.update { it.copy(linkError = message, isServer = false) }
            }
        }
    }

    /**
     * MESH HOST = relay node: listen on Wi-Fi and BLE at the same time so any
     * peer that reaches either radio becomes a mesh edge. Returns when both
     * listeners resolve (peer joined, or timeout); the UI state is driven by
     * the mesh engine's own connectivity anyway.
     */
    private fun launchMeshHost() {
        val port = _uiState.value.port.toIntOrNull() ?: 9876
        viewModelScope.launch {
            val results = coroutineScope {
                listOf(
                    async { runCatching { wifiTransport.startHost(port) }.getOrDefault(false) },
                    async { runCatching { bleTransport.startHost(0) }.getOrDefault(false) }
                ).awaitAll()
            }
            if (!results.any { it } && !meshTransport.isConnected()) {
                _uiState.update { it.copy(linkError = "MESH HOST FAILED (could not listen)") }
            } else {
                _uiState.update { it.copy(ipAddress = meshTransport.getLocalAddress()) }
            }
        }
    }

    fun connectToHost(host: String) {
        val mode = _uiState.value.transportMode
        val blankHostAllowed = mode == TransportType.BLUETOOTH || mode == TransportType.MESH
        if (host.isBlank() && !blankHostAllowed) return
        _uiState.update { it.copy(isServer = false, linkError = null) }
        if (mode == TransportType.MESH) {
            launchMeshConnect(host)
            return
        }
        viewModelScope.launch {
            val engine = activeEngine()
            if (engine.connect(host, _uiState.value.port.toIntOrNull() ?: 9876)) {
                _uiState.update { it.copy(ipAddress = engine.getLocalAddress()) }
            } else {
                val message = when (mode) {
                    TransportType.BLUETOOTH -> "BLE CONNECT FAILED (no peer found / BT off?)"
                    TransportType.MESH -> "MESH INACTIVE (no live edge to relay over)"
                    else -> "CONNECT FAILED to $host"
                }
                _uiState.update { it.copy(linkError = message, isConnected = false) }
            }
        }
    }

    /**
     * MESH DEVICE = attach to every reachable radio: Wi-Fi dials the given IP,
     * BLE auto-scans (a blank host means "find any advertiser"). Just one live
     * link is enough for the mesh to start relaying.
     */
    private fun launchMeshConnect(host: String) {
        val port = _uiState.value.port.toIntOrNull() ?: 9876
        viewModelScope.launch {
            val results = coroutineScope {
                val tasks = mutableListOf<Deferred<Boolean>>()
                if (host.isNotBlank()) {
                    tasks += async { runCatching { wifiTransport.connect(host, port) }.getOrDefault(false) }
                }
                tasks += async { runCatching { bleTransport.connect(host, port) }.getOrDefault(false) }
                tasks.awaitAll()
            }
            if (!results.any { it } && !meshTransport.isConnected()) {
                _uiState.update { it.copy(linkError = "MESH CONNECT FAILED (no peer found)") }
            } else {
                _uiState.update { it.copy(ipAddress = meshTransport.getLocalAddress()) }
            }
        }
    }

    fun disconnect() {
        _uiState.update { it.copy(linkError = null) }
        viewModelScope.launch {
            if (_uiState.value.transportMode == TransportType.MESH) {
                // Tear down the overlay and the radios it was flooding over.
                coroutineScope {
                    async { meshTransport.disconnect() }
                    async { wifiTransport.disconnect() }
                    async { bleTransport.disconnect() }
                }
            } else {
                activeEngine().disconnect()
            }
        }
    }

    // ------------------------------------------------------------------
    // Speech controls
    // ------------------------------------------------------------------

    fun setManualText(text: String) {
        _manualText.value = text
    }

    /** Send the manual text field (or a default sample when empty). */
    fun sendManual(textOverride: String? = null) {
        val text = (textOverride ?: _manualText.value).ifBlank { "I need help." }
        speechPipeline?.launchSend(text, _uiState.value.currentLanguage, forceEmergency = false)
    }

    fun sendEmergency() {
        val text = _manualText.value.ifBlank { "I need help." }
        speechPipeline?.launchSend(text, _uiState.value.currentLanguage, forceEmergency = true)
    }

    fun startRecording() {
        speechPipeline?.startListening()
        _uiState.value = _uiState.value.copy(isRecording = true)
    }

    fun stopRecording() {
        speechPipeline?.stopListening()
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    fun togglePTT() {
        val newState = !_uiState.value.isPTTMode
        _uiState.value = _uiState.value.copy(isPTTMode = newState)
        speechPipeline?.setPTTMode(newState)
    }

    fun setLanguage(language: Language) {
        _uiState.value = _uiState.value.copy(currentLanguage = language)
        speechPipeline?.setLanguage(language)
    }

    fun setMode(retro: Boolean) {
        if (useRetroCodec == retro) return
        useRetroCodec = retro
        _uiState.value = _uiState.value.copy(mode = if (retro) "RETRO" else "BASELINE")
        startObservation()
    }

    fun setPort(port: String) {
        _uiState.value = _uiState.value.copy(port = port)
    }

    fun setActiveTab(tab: Int) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    // ------------------------------------------------------------------
    // Transport mode (WIFI / SIMULATED / BLUETOOTH)
    // ------------------------------------------------------------------

    fun setTransportMode(mode: TransportType) {
        if (_uiState.value.transportMode == mode) return
        _uiState.update {
            it.copy(
                transportMode = mode,
                simulationEnabled = mode == TransportType.SIMULATED
            )
        }
        if (mode == TransportType.MESH) {
            // The mesh is a relay overlay: it composes whatever live
            // point-to-point links already exist, so nothing is torn down here.
            if (!meshEdgesAttached) {
                meshTransport.addEdge(wifiTransport)
                meshTransport.addEdge(simulatedTransport)
                meshTransport.addEdge(bleTransport)
                meshEdgesAttached = true
            }
        } else {
            viewModelScope.launch {
                wifiTransport.disconnect()
                simulatedTransport.disconnect()
                bleTransport.disconnect()
            }
        }
        startObservation()
    }

    // ------------------------------------------------------------------
    // Network simulation (debug only; results labeled SIMULATION)
    // ------------------------------------------------------------------

    fun toggleSimulation(enabled: Boolean) {
        if (_uiState.value.simulationEnabled == enabled) return
        setTransportMode(if (enabled) TransportType.SIMULATED else TransportType.WIFI)
    }

    fun setSimLoss(rate: Float) {
        networkSimulator.config.lossRate = rate
        _uiState.update { it.copy(simConfig = networkSimulator.config.copy()) }
    }

    fun setSimLatency(ms: Long) {
        networkSimulator.config.latencyMs = ms
        _uiState.update { it.copy(simConfig = networkSimulator.config.copy()) }
    }

    fun setSimDuplication(enabled: Boolean) {
        networkSimulator.config.duplicationRate = if (enabled) 1f else 0f
        _uiState.update { it.copy(simConfig = networkSimulator.config.copy()) }
    }

    fun setSimCorruption(enabled: Boolean) {
        networkSimulator.config.corruptionRate = if (enabled) 1f else 0f
        _uiState.update { it.copy(simConfig = networkSimulator.config.copy()) }
    }

    // ------------------------------------------------------------------
    // CODEC LAB (unchanged)
    // ------------------------------------------------------------------

    fun setLabInput(text: String) {
        _codecLab.update { it.copy(input = text) }
    }

    fun setLabLanguage(language: Language) {
        _codecLab.update { it.copy(language = language) }
    }

    fun runLabEncode() {
        val text = _codecLab.value.input
        val language = _codecLab.value.language
        if (text.isBlank()) return
        _codecLab.update { it.copy(running = true) }
        viewModelScope.launch {
            val value = withContext(Dispatchers.Default) {
                val (result, payload) = retroCodec.encodeLab(text, language)
                result to payload
            }
            _codecLab.update {
                it.copy(running = false, encodeResult = value.first, payload = value.second, decodeResult = null)
            }
        }
    }

    fun runLabDecode() {
        val payload = _codecLab.value.payload ?: return
        val base = _codecLab.value.encodeResult ?: return
        _codecLab.update { it.copy(running = true) }
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.Default) {
                retroCodec.decodeLab(payload, base)
            }
            _codecLab.update { it.copy(running = false, decodeResult = decoded) }
        }
    }

    fun runLabRoundtrip(text: String, language: Language) {
        if (text.isBlank()) return
        _codecLab.update { it.copy(input = text, language = language, running = true) }
        viewModelScope.launch {
            val full = withContext(Dispatchers.Default) {
                retroCodec.performLab(text, language)
            }
            _codecLab.update { it.copy(running = false, encodeResult = full, decodeResult = full, payload = null) }
        }
    }

    fun testSentence(language: Language) {
        val sentence = when (language) {
            Language.ENGLISH -> "I need help."
            Language.HINDI -> "मुझे मदद चाहिए।"
            Language.TAMIL -> "எனக்கு உதவி தேவை."
            else -> ""
        }
        _codecLab.update { it.copy(input = sentence, language = language) }
    }

    // ------------------------------------------------------------------
    // EXPERIMENTS
    // ------------------------------------------------------------------

    fun runExperiment(phrase: String) {
        viewModelScope.launch {
            val comparison = retroCodec.encodeWithComparison(phrase, _uiState.value.currentLanguage)
            _uiState.value = _uiState.value.copy(codecComparison = comparison)

            app.experimentLogger.logEntry(
                language = _uiState.value.currentLanguage,
                message = phrase,
                originalBytes = comparison.originalUtf8Bytes,
                encodedBytes = comparison.retroEncodedBytes,
                packetCount = comparison.packetCount,
                packetLoss = _uiState.value.linkMetrics.packetLoss,
                retransmissions = _uiState.value.linkMetrics.retransmissions,
                sttLatencyMs = 0,
                encodeLatencyMs = 0,
                transportLatencyMs = 0,
                decodeLatencyMs = 0,
                ttsLatencyMs = 0,
                totalLatencyMs = 0,
                codecType = if (useRetroCodec) "RETRO" else "BASELINE",
                compressionPercentage = comparison.compressionPercentage
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechPipeline?.shutdown()
        transportObserverJob?.cancel()
        pipelineStateJob?.cancel()
        pipelineIncomingJob?.cancel()
        viewModelScope.launch {
            wifiTransport.disconnect()
            simulatedTransport.disconnect()
            bleTransport.disconnect()
        }
    }
}