package com.example.itantra.ui.main

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.itantra.*
import com.example.itantra.codec.*
import com.example.itantra.data.*
import com.example.itantra.metrics.MetricsEngine
import com.example.itantra.ops.EmergencyController
import com.example.itantra.ops.LowPowerController
import com.example.itantra.ops.OperationMode
import com.example.itantra.ops.OperationModeController
import com.example.itantra.speech.stt.VoskSTTEngine
import com.example.itantra.speech.tts.AndroidTTSEngine
import com.example.itantra.speech.tts.PresenceAwareTTS
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
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as iTantraApp

    private val sttEngine = VoskSTTEngine()
    private val ttsEngine = AndroidTTSEngine()

    /**
     * Phase 18: TTS only speaks while the operator has announcement audio on
     * AND a listener is plausibly present (a live link exists). Every withheld
     * utterance is counted and surfaced, never silently dropped.
     */
    private val presenceAwareTTS = PresenceAwareTTS(ttsEngine) {
        _uiState.value.announceAudio &&
            (_uiState.value.isConnected || _uiState.value.transportMode == TransportType.SIMULATED)
    }
    private val retroCodec = RetroSpeechCodec()
    private val baselineCodec = BaselineCodec()

    // Phase 19/20: operation modes + emergency state machine (pure controllers).
    private val opController = OperationModeController()
    private val emergencyController = EmergencyController()

    // Phase 24: battery-driven power governor fed by the real OS battery level.
    private val lowPowerController = LowPowerController {
        val raw = batteryPercent()
        if (raw < 0) 100 else raw.coerceAtMost(100)
    }

    /** Phase 22: model presence probe — a file really on this device. An STT
     *  model counts as present exactly when VoskSTTEngine can load it: either an
     *  unpacked dir at filesDir/models/<code>/ or a zip at filesDir/stt-<LANG>.zip.
     *  No open-source TTS voices are bundled, so TTS entries are always absent. */
    private val modelProbe = ModelManager.ModelProbe { entry ->
        when (entry.kind) {
            ModelManager.ModelKind.STT ->
                File(app.filesDir, "${entry.id}.zip").isFile ||
                    File(app.filesDir, "models/${modelDirCode(entry.language)}").isDirectory
            ModelManager.ModelKind.TTS -> false
        }
    }

    private fun modelDirCode(language: Language): String =
        when (language) {
            Language.ENGLISH -> "en"
            Language.HINDI -> "hi"
            Language.TAMIL -> "ta"
            Language.BENGALI -> "bn"
            Language.TELUGU -> "te"
            Language.MARATHI -> "mr"
            Language.GUJARATI -> "gu"
            Language.KANNADA -> "kn"
            Language.MALAYALAM -> "ml"
            Language.ODIA -> "or"
            Language.UNKNOWN -> "xx"
        }

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

    private val _modelStatus = MutableStateFlow(ModelManager.resolve(modelProbe))
    val modelStatus: StateFlow<List<ModelManager.Resolution>> = _modelStatus

    private val _packetActivity = MutableStateFlow<List<PacketActivity>>(emptyList())
    val packetActivity: StateFlow<List<PacketActivity>> = _packetActivity.asStateFlow()

    data class ReceivedMessageUI(
        val text: String,
        val language: Language,
        val timestamp: Long,
        val isEmergency: Boolean
    )

    enum class ActivityKind { TX, RX, LINK, ALERT }

    /** One real transport-layer event, kept for the live packet lane. */
    data class PacketActivity(
        val kind: ActivityKind,
        val label: String,
        val detail: String
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
        val isPlaying: Boolean = false,
        val currentLanguage: Language = Language.ENGLISH,
        val mode: String = "RETRO",
        val lastSentText: String = "",
        val lastReceivedText: String = "",
        val lastSendReport: SpeechPipeline.SpeechSendReport? = null,
        val metrics: MetricsEngine.FullMetrics? = null,
        val codecComparison: EncodingComparison? = null,
        val experimentSummary: List<ExperimentSummary> = emptyList(),
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
        val latencyMs: Long = 0,

        // P19-24 system status surfaced by the dashboard.
        val opMode: OperationMode = OperationMode.NORMAL,
        val opModeHistory: List<String> = emptyList(),
        val emergencyActive: Boolean = false,
        val emergencyReason: String? = null,
        val emergencyClosedCount: Int = 0,
        val batteryPercentRaw: Int = -1,
        val powerProfile: String = "HEALTHY",
        val modelsUsable: Int = 0,
        val modelsTotal: Int = 0,
        val ttsSuppressedSpeeches: Int = 0,
        val announceAudio: Boolean = true,
        val benchmarkResults: List<BenchmarkResult> = emptyList(),
        val benchmarkRunning: Boolean = false,
        val progressivePreview: String = "",
        val progressivePreviewBytes: Int = 0,
        val progressiveQuality: Float = 0f
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
        refreshSystemStatus()
    }

    // ------------------------------------------------------------------
    // System status helpers (real device readings, honestly labeled)
    // ------------------------------------------------------------------

    private fun batteryPercent(): Int {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun refreshSystemStatus() {
        val resolutions = ModelManager.resolve(modelProbe)
        _modelStatus.value = resolutions
        _uiState.update {
            it.copy(
                batteryPercentRaw = batteryPercent(),
                powerProfile = lowPowerController.profile.name,
                modelsUsable = resolutions.count { it.isUsable },
                modelsTotal = resolutions.size,
                ttsSuppressedSpeeches = presenceAwareTTS.suppressedSpeeches,
                opModeHistory = opController.history.takeLast(6)
                    .map { t -> "${t.from} -> ${t.to} (${t.cause})" },
                emergencyClosedCount = emergencyController.countClosed()
            )
        }
    }

    // ------------------------------------------------------------------
    // Operation mode + emergency controls (Phase 19/20)
    // ------------------------------------------------------------------

    fun switchOperationMode(mode: OperationMode) {
        opController.switchTo(mode, "operator switch")
        _uiState.update {
            it.copy(
                opMode = opController.mode,
                opModeHistory = opController.history.takeLast(6)
                    .map { t -> "${t.from} -> ${t.to} (${t.cause})" }
            )
        }
    }

    fun raiseEmergency() {
        emergencyController.raise("operator-triggered")
        // The latched alert mode is entered; audio stays enabled for the alarm.
        opController.switchTo(OperationMode.EMERGENCY, "emergency raised")
        _uiState.update {
            it.copy(
                emergencyActive = emergencyController.isActive,
                emergencyReason = emergencyController.currentReason,
                opMode = opController.mode,
                opModeHistory = opController.history.takeLast(6)
                    .map { t -> "${t.from} -> ${t.to} (${t.cause})" }
            )
        }
    }

    /**
     * A CRITICAL message arrived over the air: mirror the operator-triggered
     * emergency latch so the receiving device switches into alert mode and
     * surfaces the incoming SOS prominently.
     */
    fun raiseEmergencyFromPeer(text: String) {
        if (emergencyController.isActive) return
        emergencyController.raise("incoming emergency: $text")
        opController.switchTo(OperationMode.EMERGENCY, "emergency received")
        _uiState.update {
            it.copy(
                emergencyActive = emergencyController.isActive,
                emergencyReason = emergencyController.currentReason,
                opMode = opController.mode,
                opModeHistory = opController.history.takeLast(6)
                    .map { t -> "${t.from} -> ${t.to} (${t.cause})" }
            )
        }
    }

    fun acknowledgeEmergency() {
        emergencyController.acknowledge("operator")
        opController.acknowledgeEmergency("operator")
        _uiState.update {
            it.copy(
                emergencyActive = emergencyController.isActive,
                emergencyReason = emergencyController.currentReason,
                opMode = opController.mode,
                opModeHistory = opController.history.takeLast(6)
                    .map { t -> "${t.from} -> ${t.to} (${t.cause})" },
                emergencyClosedCount = emergencyController.countClosed()
            )
        }
        refreshSystemStatus()
    }

    fun setAnnounceAudio(enabled: Boolean) {
        _uiState.update { it.copy(announceAudio = enabled) }
        refreshSystemStatus()
    }

    // ------------------------------------------------------------------
    // Benchmark (Phase 27, surfaced here): real JVM round-trips, labeled.
    // ------------------------------------------------------------------

    fun runBenchmark() {
        if (_uiState.value.benchmarkRunning) return
        _uiState.update { it.copy(benchmarkRunning = true) }
        viewModelScope.launch {
            val corpus = "I need help near the railway station please come immediately and bring water."
            val scenarios = listOf(
                BenchmarkScenario("RETRO", retroCodec, corpus, Language.ENGLISH, 30),
                BenchmarkScenario("BASELINE", baselineCodec, corpus, Language.ENGLISH, 30)
            )
            val results = withContext(Dispatchers.Default) {
                BenchmarkRunner().run(scenarios)
            }
            _uiState.update { it.copy(benchmarkRunning = false, benchmarkResults = results) }
        }
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
            ttsEngine = presenceAwareTTS,
            speechCodec = currentCodec(),
            transport = engine,
            metricsEngine = app.metricsEngine,
            lowPowerController = lowPowerController
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
                        isPlaying = ps.isPlaying,
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
                // A CRITICAL message received over the air is not just text:
                // latch the emergency mode and surface the alert immediately.
                if (msg.isEmergency) {
                    raiseEmergencyFromPeer(msg.text)
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
        // Mirror the transport layer's live counters into the metrics engine so
        // the dashboard reports the real wire numbers (packets/bytes/loss/dups).
        app.metricsEngine.syncTransport(
            packetsSent = m.packetsSent,
            packetsReceived = m.packetsReceived,
            retransmissions = m.retransmissions,
            packetLoss = m.packetLoss,
            transmittedBytes = m.transmittedBytes,
            receivedBytes = m.receivedBytes,
            duplicatePackets = m.duplicatePackets,
            corruptedFrames = m.corruptedFrames
        )
        if (m.roundTripTimeMs > 0) app.metricsEngine.recordRoundTripTime(m.roundTripTimeMs)
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
                    pushActivity(ActivityKind.LINK, "LINK UP",
                        "${ev.session.sessionId} vs ${ev.session.protocolVersion} · epoch ${ev.session.epoch}")
                }
                is LinkMessageEvent.Disconnected -> {
                    _uiState.update {
                        it.copy(linkError = ev.reason, linkStatusLine = "LINK CLOSED: ${ev.reason}")
                    }
                    pushActivity(ActivityKind.LINK, "LINK DOWN", ev.reason)
                }
                is LinkMessageEvent.Transmitted -> {
                    pushActivity(
                        if (ev.failed) ActivityKind.ALERT else ActivityKind.TX,
                        "TX #${ev.messageId}",
                        buildString {
                            append("${ev.packetCount} pkts · ${ev.transmittedBytes} B")
                            if (ev.retransmissions > 0) append(" · ${ev.retransmissions} retr")
                            if (ev.roundTripTimeMs > 0) append(" · ${ev.roundTripTimeMs} ms")
                            if (ev.failed) append(" · FAILED")
                        }
                    )
                }
                is LinkMessageEvent.Received -> {
                    pushActivity(
                        if (ev.isEmergency) ActivityKind.ALERT else ActivityKind.RX,
                        "RX #${ev.messageId}",
                        buildString {
                            append("${ev.dataPackets} pkts · ${ev.payloadBytes} B")
                            if (ev.isEmergency) append(" · EMERGENCY")
                        }
                    )
                }
                is LinkMessageEvent.StatusChange -> { /* handled in observeStatus */ }
            }
        }
    }

    /** Bounded, real-event-only console feed for the packet lane. */
    private fun pushActivity(kind: ActivityKind, label: String, detail: String) {
        val entry = PacketActivity(kind, label, detail)
        _packetActivity.update { (listOf(entry) + it).takeLast(40) }
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
        recordProgressiveStage(text)
        speechPipeline?.launchSend(text, _uiState.value.currentLanguage, forceEmergency = false)
    }

    fun sendEmergency() {
        val text = _manualText.value.ifBlank { "I need help." }
        recordProgressiveStage(text)
        speechPipeline?.launchSend(text, _uiState.value.currentLanguage, forceEmergency = true)
    }

    /**
     * Phase 13: publish the instantly-available preview stage with its real,
     * measured quality versus the full message. Both numbers come from the
     * actual message text — never from a model of quality.
     */
    private fun recordProgressiveStage(text: String) {
        val plan = ProgressiveTransmission.plan(text.toByteArray(Charsets.UTF_8).size)
        val preview = ProgressiveTransmission.preview(text, plan)
        _uiState.update {
            it.copy(
                progressivePreview = preview,
                progressivePreviewBytes = plan.previewBytes,
                progressiveQuality = ProgressiveTransmission.measuredQuality(text, preview)
            )
        }
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
        if (tab == 5) refreshSystemStatus()
        if (tab == 6) refreshSystemStatus()
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
            // P21 languages: community translations pending — fall back to the
            // English template rather than emitting an empty or fake phrase.
            else -> "I need help."
        }
        _codecLab.update { it.copy(input = sentence, language = language) }
    }

    // ------------------------------------------------------------------
    // EXPERIMENTS
    // ------------------------------------------------------------------

    fun runExperiment(phrase: String) {
        viewModelScope.launch {
            val language = _uiState.value.currentLanguage

            // Phase 25/26: the A/B engine assigns the condition for this trial
            // (balanced block randomization); every measurement below is real.
            val arm = app.experimentEngine.nextArm()
            val codec = if (arm.codec == "BASELINE") baselineCodec else retroCodec
            val lab = codec.performLab(phrase, language)
            val comparison = codec.encodeWithComparison(phrase, language)

            val accepted = app.experimentEngine.record(
                ExperimentOutcome(
                    unitId = app.experimentEngine.assigned,
                    armId = arm.id,
                    success = lab.exactMatch,
                    originalBytes = lab.originalUtf8Bytes,
                    encodedBytes = lab.encodedBytes,
                    packetCount = lab.packetCount,
                    encodeMs = (lab.encodeMs + lab.packetizeMs).coerceAtLeast(0),
                    decodeMs = lab.decodeMs.coerceAtLeast(0),
                    totalMs = lab.totalMs.coerceAtLeast(0),
                    compressionPercentage = lab.compressionPercent
                )
            )

            _uiState.value = _uiState.value.copy(
                codecComparison = comparison,
                experimentSummary = app.experimentEngine.summarize(),
                messageInfo = _uiState.value.messageInfo.copy(
                    detail = "trial ${app.experimentEngine.assigned}: arm ${arm.id}, " +
                        "${lab.packetCount} pkt, ${if (lab.exactMatch) "exact" else "FAILED"}" +
                        if (!accepted) " (invalid sample rejected)" else ""
                )
            )

            app.experimentLogger.logEntry(
                language = language,
                message = phrase,
                originalBytes = comparison.originalUtf8Bytes,
                encodedBytes = comparison.retroEncodedBytes,
                packetCount = comparison.packetCount,
                packetLoss = _uiState.value.linkMetrics.packetLoss,
                retransmissions = _uiState.value.linkMetrics.retransmissions,
                sttLatencyMs = 0,
                encodeLatencyMs = lab.encodeMs.coerceAtLeast(0),
                transportLatencyMs = 0,
                decodeLatencyMs = lab.decodeMs.coerceAtLeast(0),
                ttsLatencyMs = 0,
                totalLatencyMs = lab.totalMs.coerceAtLeast(0),
                codecType = if (arm.codec == "BASELINE") "BASELINE" else "RETRO",
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