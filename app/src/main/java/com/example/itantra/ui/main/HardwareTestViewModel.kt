package com.example.itantra.ui.main

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.itantra.SpeechPipeline
import com.example.itantra.iTantraApp
import com.example.itantra.codec.BinaryCodec
import com.example.itantra.codec.Language
import com.example.itantra.codec.RetroSpeechCodec
import com.example.itantra.lab.*
import com.example.itantra.speech.stt.VoskSTTEngine
import com.example.itantra.speech.tts.TTSEngine
import com.example.itantra.transport.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PHASE 5 — PHYSICAL DEVICE VALIDATION.
 *
 * Dedicated lab engines (isolated from the main Communication screen) plus a
 * JVM-verified, transport-agnostic [HardwareTestRunner]. Everything here is
 * either measured from real radio traffic (BLE chunk counts, negotiated MTU,
 * link metrics, session epochs) or explicitly labeled SIMULATED — never
 * presented as physical measurements.
 */
class HardwareTestViewModel(application: Application) : AndroidViewModel(application) {

    private val labSim = NetworkSimulator()
    private val labWifi = WifiTransportEngine(labSim)
    private val labBle = BleTransportEngine(application, labSim)
    private val labLoopback = SimulatedTransport(labSim)
    private val labMesh = MeshTransportEngine(maxTtl = 2)
    private val runner = HardwareTestRunner(retroCodec)
    private var speechPipeline: SpeechPipeline? = null

    companion object {
        private val retroCodec = RetroSpeechCodec()
        private val NOOP_TTS = object : TTSEngine {
            override fun initialize(context: Context, language: Language, onReady: () -> Unit) = onReady()
            override fun speak(text: String, utteranceId: String) {}
            override fun stop() {}
            override fun isInitialized(): Boolean = true
            override fun shutdown() {}
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    data class HardwareTestState(
        val transport: LabRadio = LabRadio.BLE,
        val role: LabRole = LabRole.HOST,
        val address: String = "",
        val port: String = "9876",
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val sessionLabel: String = "",
        val bleCheck: BleLinkCheck = BleLinkCheck(),
        val negotiatedMtu: Int = 0,
        val simulatedLossDropEveryNth: Int = 0,
        val simulatedChunksDropped: Long = 0,
        val duplicationInjected: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,

        val lastProbe: ProbeResult? = null,
        val probeHistory: List<ProbeResult> = emptyList(),
        val sweepRows: List<SweepRow> = emptyList(),
        val receivedItems: List<ReceivedItem> = emptyList(),
        val duplicateCheck: DuplicateCheck? = null,
        val reconnectReport: ReconnectReport? = null,

        val meshNodeRole: MeshNodeRole = MeshNodeRole.SOURCE,
        val edge1: EdgeSpec = EdgeSpec(radio = LabRadio.BLE, role = LabRole.HOST),
        val edge2: EdgeSpec = EdgeSpec(radio = LabRadio.WIFI, role = LabRole.DEVICE),
        val meshNode: StartedMeshNode? = null,
        val meshRelay: MeshRelayStats = MeshRelayStats(),

        val speechLanguage: Language = Language.ENGLISH,
        val speechLastReport: SpeechPipeline.SpeechSendReport? = null,
        val speechRecording: Boolean = false,

        val reportRows: Int = 0,
        val reportText: String = "",
        val reportSavedPath: String = ""
    )

    data class ReceivedItem(
        val payloadBytes: Int,
        val matched: String,
        val pass: String,
        val note: String
    )

    private val _state = MutableStateFlow(HardwareTestState())
    val state: StateFlow<HardwareTestState> = _state

    private val report = mutableListOf<List<String>>()
    private val receivedCollector = mutableListOf<ReassembledPayload>()
    private var receivedJob: kotlinx.coroutines.Job? = null
    private var sessionSnapshot: Pair<String, Int>? = null

    init {
        viewModelScope.launch {
            labBle.bleCheck.collect { check ->
                _state.update { it.copy(bleCheck = check, negotiatedMtu = check.mtu) }
            }
        }
        viewModelScope.launch {
            labMesh.relayStats.collect { stats ->
                _state.update { it.copy(meshRelay = stats) }
            }
        }
        viewModelScope.launch {
            labMesh.connectionStatus.collect { s ->
                if (_state.value.meshNode != null) {
                    _state.update { it.copy(connectionStatus = s) }
                }
            }
        }
        viewModelScope.launch {
            labWifi.connectionStatus.collect { s ->
                if (_state.value.meshNode == null && _state.value.transport == LabRadio.WIFI) {
                    _state.update { it.copy(connectionStatus = s) }
                }
            }
        }
        viewModelScope.launch {
            labBle.connectionStatus.collect { s ->
                if (_state.value.meshNode == null && _state.value.transport == LabRadio.BLE) {
                    _state.update { it.copy(connectionStatus = s) }
                }
            }
        }
        rebootReport()
    }

    private fun deviceReport(): DeviceReport {
        val appContext = getApplication<Application>()
        val ver = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        return DeviceReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = ver,
            deviceId = activeEngine().getDeviceId(),
            codecVersion = BinaryCodec.VERSION,
            protocolVersion = 1
        )
    }

    // ------------------------------------------------------------------
    // Engine selection
    // ------------------------------------------------------------------

    private fun activeEngine(): TransportEngine =
        when (_state.value.transport) {
            LabRadio.WIFI -> labWifi
            LabRadio.BLE -> labBle
            LabRadio.SIMULATED -> labLoopback
        }

    private fun meshEngine(): TransportEngine = labMesh

    private fun fragmentSource(): (() -> Long)? =
        if (activeEngine() is BleTransportEngine) { (labBle as BleTransportEngine)::chunksSent } else null

    private fun currentMtu(): Int =
        if (activeEngine() is BleTransportEngine) labBle.negotiatedMtu() else 0

    private fun transportLabel(): String = _state.value.transport.label

    // ------------------------------------------------------------------
    // Link controls (WIFI / BLE / SIMULATED)
    // ------------------------------------------------------------------

    fun setTransport(radio: LabRadio) = _state.update { it.copy(transport = radio, error = null) }

    fun setRole(role: LabRole) = _state.update { it.copy(role = role, error = null) }

    fun setAddress(address: String) = _state.update { it.copy(address = address) }

    fun setPort(port: String) = _state.update { it.copy(port = port) }

    fun startLink() {
        val s = _state.value
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val engine = activeEngine()
            val ok = if (s.role == LabRole.HOST) {
                engine.startHost(s.port.toIntOrNull() ?: 9876)
            } else {
                engine.connect(s.address, s.port.toIntOrNull() ?: 9876)
            }
            if (ok) {
                _state.update {
                    it.copy(
                        busy = false,
                        connectionStatus = ConnectionStatus.CONNECTED,
                        sessionLabel = engine.session.value?.sessionId ?: "connected",
                        error = null
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        connectionStatus = ConnectionStatus.ERROR,
                        error = "${transportLabel()} ${if (s.role == LabRole.HOST) "HOST FAILED" else "CONNECT FAILED"} (peer / permissions)"
                    )
                }
            }
        }
    }

    fun stopLink() {
        viewModelScope.launch {
            activeEngine().disconnect()
            _state.update { it.copy(connectionStatus = ConnectionStatus.DISCONNECTED, sessionLabel = "", error = null) }
        }
    }

    // ------------------------------------------------------------------
    // Session snapshots (disconnect/reconnect test)
    // ------------------------------------------------------------------

    fun saveSessionSnapshot() {
        val s = activeEngine().session.value
        sessionSnapshot = s?.let { it.sessionId to it.epoch }
    }

    fun recordReconnect() {
        val engine = activeEngine()
        val before = sessionSnapshot
        val after = engine.session.value
        val beforeLabel = before?.let { it.first } ?: "—"
        val beforeEpoch = before?.second ?: -1
        _state.update {
            it.copy(
                reconnectReport = ReconnectReport(
                    sessionBefore = beforeLabel,
                    epochBefore = beforeEpoch,
                    sessionAfter = after?.sessionId ?: "—",
                    epochAfter = after?.epoch ?: -1,
                    deliveredAfter = false,
                    detail = if (before != null && (before.first != after?.sessionId || before.second != after?.epoch)) "fresh session, old-session frames rejected" else "same session"
                )
            )
        }
        appendReportRow(listOf("RECONNECT", "", "", "", "", "", "", "", "", "", "", "", "", "", "", beforeEpoch.toString(), after?.epoch?.toString() ?: "", "", "", "", "", "recorded"))
    }

    // ------------------------------------------------------------------
    // BLE SIMULATED LOSS hook (labeled, never real)
    // ------------------------------------------------------------------

    fun setSimulatedLossDropEveryNth(n: Int) {
        (activeEngine() as? BleTransportEngine)?.chunkDropEveryNth = n
        _state.update { it.copy(simulatedLossDropEveryNth = n) }
    }

    fun refreshSimulatedDrops() {
        _state.update {
            it.copy(simulatedChunksDropped = (activeEngine() as? BleTransportEngine)?.simulatedChunksDropped() ?: 0)
        }
    }

    // ------------------------------------------------------------------
    // Raw / unicode / sweep / duplicate probes
    // ------------------------------------------------------------------

    fun runRawProbe() {
        runProbe(HardwareTestSuite.BLE_RAW)
    }

    fun runUnicodeSuite() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val results = mutableListOf<ProbeResult>()
            for (case in HardwareTestSuite.UNICODE) {
                results += runner.probeSend(
                    activeEngine(), case, transportLabel(),
                    _state.value.role.label, currentMtu(), fragmentSource()
                )
            }
            _state.update { it.copy(busy = false, lastProbe = results.last(), probeHistory = results) }
            results.forEach { appendProbeRow(it) }
        }
    }

    private fun runProbe(case: TestCase) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runner.probeSend(
                activeEngine(), case, transportLabel(),
                _state.value.role.label, currentMtu(), fragmentSource()
            )
            _state.update { it.copy(busy = false, lastProbe = result, probeHistory = listOf(result)) }
            appendProbeRow(result)
        }
    }

    fun runSizeSweep() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val rows = runner.runSweep(activeEngine(), currentMtu(), fragmentSource())
            _state.update { it.copy(busy = false, sweepRows = rows) }
            rows.forEach { appendSweepRow(it) }
        }
    }

    fun setDuplicateInjection(enabled: Boolean) {
        labSim.config.duplicationRate = if (enabled) 1f else 0f
        _state.update { it.copy(duplicationInjected = enabled) }
    }

    fun runDuplicateCheck() {
        val engine = activeEngine()
        viewModelScope.launch {
            val dups = engine.linkMetrics.value.duplicatePackets
            val delivered = receivedCollector.count { it.payload.isNotEmpty() }
            val check = DuplicateCheck(
                payloadsDelivered = delivered,
                messagesExpected = 1,
                duplicatesDetected = dups,
                okay = delivered == 1 && dups >= 1
            )
            _state.update { it.copy(duplicateCheck = check) }
            appendReportRow(listOf("DUP-CHECK", transportLabel(), _state.value.role.label, "", "", "", "", "", "", "", "", "", "", "", "", "", "", dups.toString(), "", "", check.pass, "delivered=$delivered expected=1"))
        }
    }

    // ------------------------------------------------------------------
    // Receive window (the receiving phone's verdict)
    // ------------------------------------------------------------------

    fun startReceiveWindow() {
        receivedCollector.clear()
        _state.update { it.copy(busy = true, receivedItems = emptyList(), error = null) }
        viewModelScope.launch {
            val engine = activeEngine()
            val (job, list) = runner.collector(engine, viewModelScope)
            receivedJob = job
            var timedOut = false
            try {
                withTimeout(20_000) {
                    while (list.isEmpty()) delay(100)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                timedOut = true
            } finally {
                job.cancel()
            }
            receivedCollector.addAll(list)
            val items = list.map { matchIncoming(it, engine) }
            _state.update { it.copy(busy = false, receivedItems = items, error = if (timedOut) "no payload arrived in 20s" else null) }
        }
    }

    private fun matchIncoming(payload: ReassembledPayload, engine: TransportEngine): ReceivedItem {
        if (payload.payload.size in HardwareTestSuite.SWEEP_SIZES) {
            return ReceivedItem(payload.payload.size, "SWEEP ${payload.payload.size}B", "PASS", "bytes exact")
        }
        val candidates = HardwareTestSuite.UNICODE + listOf(HardwareTestSuite.BLE_RAW) + HardwareTestSuite.SPEECH_PHRASES
        for (case in candidates) {
            val probe = runner.verify(engine, payload, case, transportLabel(), _state.value.role.label)
            if (probe.decodePass == true && probe.crcPass == true) {
                return ReceivedItem(payload.payload.size, case.name, "PASS", "decode+CRC exact")
            }
        }
        return ReceivedItem(payload.payload.size, "UNMATCHED", "FAIL", "decode mismatch")
    }

    // ------------------------------------------------------------------
    // Mesh node configuration (SOURCE / RELAY / DESTINATION)
    // ------------------------------------------------------------------

    fun setMeshNodeRole(role: MeshNodeRole) = _state.update { it.copy(meshNodeRole = role) }

    fun setEdge1Radio(radio: LabRadio) = _state.update { it.copy(edge1 = it.edge1.copy(radio = radio)) }

    fun setEdge1Role(role: LabRole) = _state.update { it.copy(edge1 = it.edge1.copy(role = role)) }

    fun setEdge1Address(address: String) = _state.update { it.copy(edge1 = it.edge1.copy(address = address)) }

    fun setEdge2Radio(radio: LabRadio) = _state.update { it.copy(edge2 = it.edge2.copy(radio = radio)) }

    fun setEdge2Role(role: LabRole) = _state.update { it.copy(edge2 = it.edge2.copy(role = role)) }

    fun setEdge2Address(address: String) = _state.update { it.copy(edge2 = it.edge2.copy(address = address)) }

    fun startMeshNode() {
        val s = _state.value
        val specs = if (s.meshNodeRole == MeshNodeRole.RELAY) listOf(s.edge1, s.edge2) else listOf(s.edge1)
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            teardownMeshEngines()
            labMesh.clearRelayStats()

            val started = coroutineScope {
                specs.map { spec ->
                    async { startEdge(spec) }
                }.awaitAll().filterNotNull()
            }
            if (started.isEmpty()) {
                _state.update { it.copy(busy = false, error = "no mesh edge could be started") }
                return@launch
            }
            for (edge in started.map { it.second }) {
                labMesh.addEdge(edge)
                labMesh.awaitEdgeReady(edge)
            }
            _state.update {
                it.copy(
                    busy = false,
                    meshNode = StartedMeshNode(
                        role = s.meshNodeRole,
                        deviceId = labMesh.getDeviceId(),
                        edgeLabels = started.map { it.first.label() },
                        startedAtMs = System.currentTimeMillis()
                    ),
                    connectionStatus = ConnectionStatus.CONNECTED
                )
            }
        }
    }

    /** Returns (edgeLabel, engine) once the radio edge is live, or null. */
    private suspend fun startEdge(spec: EdgeSpec): Pair<EdgeSpec, TransportEngine>? {
        val engine = when (spec.radio) {
            LabRadio.WIFI -> labWifi
            LabRadio.BLE -> labBle
            LabRadio.SIMULATED -> labLoopback
        }
        val port = spec.port.toIntOrNull() ?: 9876
        val ok = if (spec.role == LabRole.HOST) engine.startHost(port) else engine.connect(spec.address, port)
        return if (ok) spec to engine else null
    }

    fun stopMeshNode() {
        viewModelScope.launch {
            teardownMeshEngines()
            _state.update { it.copy(meshNode = null, connectionStatus = ConnectionStatus.DISCONNECTED, error = null) }
        }
    }

    private suspend fun teardownMeshEngines() {
        labMesh.disconnect()
        labWifi.disconnect()
        labBle.disconnect()
        labLoopback.disconnect()
    }

    fun sendMeshMessage() {
        val s = _state.value
        val node = s.meshNode ?: return
        val case = TestCase("MESH", HardwareTestSuite.MESH_SOURCE_MESSAGE, Language.ENGLISH)
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runner.probeSend(labMesh, case, "MESH", node.role.label, 0, null)
            _state.update { it.copy(busy = false, lastProbe = result) }
            appendProbeRow(result)
        }
    }

    // ------------------------------------------------------------------
    // Speech over BLE (raw tests must pass first; NO TTS yet)
    // ------------------------------------------------------------------

    fun setSpeechLanguage(language: Language) = _state.update { it.copy(speechLanguage = language) }

    fun startSpeech() {
        val app = getApplication<iTantraApp>()
        val engine = activeEngine()
        val pipeline = SpeechPipeline(
            context = app,
            sttEngine = VoskSTTEngine(),
            ttsEngine = NOOP_TTS,
            speechCodec = retroCodec,
            transport = engine,
            metricsEngine = app.metricsEngine
        )
        pipeline.setLanguage(_state.value.speechLanguage)
        pipeline.startReceiving()
        speechPipeline = pipeline
    }

    fun stopSpeech() {
        speechPipeline?.shutdown()
        speechPipeline = null
        _state.update { it.copy(speechRecording = false) }
    }

    fun toggleSpeechRecording() {
        val p = speechPipeline ?: return
        if (_state.value.speechRecording) {
            p.stopListening()
            _state.update { it.copy(speechRecording = false) }
        } else {
            p.setPTTMode(false)
            p.startListening()
            _state.update { it.copy(speechRecording = true) }
        }
    }

    // ------------------------------------------------------------------
    // Report (spec #22 + #26)
    // ------------------------------------------------------------------

    private fun rebootReport() {
        report.clear()
        report.add(listOf("iTantra HARDWARE TEST REPORT (exported from ${transportLabel()})"))
    }

    private fun appendProbeRow(p: ProbeResult) {
        appendReportRow(listOf(
            "PROBE", p.transport, p.role, p.language.displayName, p.text,
            p.originalBytes.toString(), p.codecBytes.toString(), p.packetBytes.toString(), p.packetCount.toString(),
            p.fragments.toString(), p.mtu.toString(), p.encodeMs.toString(), p.transportMs.toString(), p.decodeMs.toString(), p.totalMs.toString(),
            p.retransmissions.toString(), p.packetLoss.toString(), p.duplicatesDetected.toString(),
            (p.crcPass?.let { if (it) "PASS" else "FAIL" } ?: "—"),
            (p.decodePass?.let { if (it) "PASS" else "FAIL" } ?: "—"),
            p.status, p.detail
        ))
    }

    private fun appendSweepRow(r: SweepRow) {
        appendReportRow(listOf(
            "SWEEP", transportLabel(), _state.value.role.label, "", "${r.sizeBytes}B",
            r.sizeBytes.toString(), "", "", r.packetCount.toString(),
            r.fragments.toString(), r.mtu.toString(), "", r.timeMs.toString(), "", "",
            "", "", "", "", "", r.pass, if (r.fragmentMeasured) "measured frags" else "estimated frags"
        ))
    }

    private fun appendReportRow(row: List<String>) {
        report.add(row)
        _state.update { it.copy(reportRows = report.size) }
    }

    private fun header(): List<String> = listOf(
        "EVENT", "TRANSPORT", "ROLE", "LANG", "MESSAGE", "ORIG_B", "CODEC_B", "PACKET_B", "PKTS",
        "FRAGS", "MTU", "ENC_MS", "TX_MS", "DEC_MS", "TOT_MS", "RETX", "LOSS", "DUP", "CRC", "DECODE", "STATUS", "NOTE"
    )

    fun rebuildReport() {
        val device = _state.value.meshNode?.let {
            // Honor the mesh node identity once configured
            deviceReport().copy(deviceId = it.deviceId)
        } ?: deviceReport()

        val sb = StringBuilder()
        device.headerRows().forEach { (k, v) -> sb.append(k).append(",").append(csv(v)).append("\n") }
        sb.append(header().joinToString(",")).append("\n")
        if (report.size > 1) {
            report.subList(1, report.size).forEach { sb.append(it.joinToString(",") { csv(it) }).append("\n") }
        }
        _state.update { it.copy(reportText = sb.toString()) }
    }

    private fun csv(v: String): String = if (v.contains(',') || v.contains('"')) "\"${v.replace("\"", "\"\"")}\"" else v

    fun saveReport() {
        rebuildReport()
        val text = _state.value.reportText
        val dir = getApplication<Application>().getExternalFilesDir(null) ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "hwtest_report_$stamp.csv")
        file.parentFile?.mkdirs()
        file.writeText(text)
        _state.update { it.copy(reportSavedPath = file.absolutePath) }
    }

    fun copyReport() {
        rebuildReport()
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("iTantra HW Report", _state.value.reportText))
    }

    override fun onCleared() {
        super.onCleared()
        receivedJob?.cancel()
        speechPipeline?.shutdown()
        viewModelScope.launch {
            teardownMeshEngines()
        }
    }
}