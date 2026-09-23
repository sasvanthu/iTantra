package com.example.itantra.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.itantra.*
import com.example.itantra.codec.*
import com.example.itantra.data.ExperimentLogger
import com.example.itantra.metrics.MetricsEngine
import com.example.itantra.speech.stt.SimulatedSTTEngine
import com.example.itantra.speech.stt.VoskSTTEngine
import com.example.itantra.speech.tts.AndroidTTSEngine
import com.example.itantra.transport.ConnectionStatus
import com.example.itantra.transport.SimulatedTransport
import com.example.itantra.transport.WifiTransport
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as iTantraApp

    private val sttEngine = VoskSTTEngine()
    private val ttsEngine = AndroidTTSEngine()
    private val retroCodec = RetroSpeechCodec()
    private val baselineCodec = BaselineCodec()
    private val wifiTransport = WifiTransport()
    private val simulatedTransport = SimulatedTransport()

    private var speechPipeline: SpeechPipeline? = null
    private var useRetroCodec = true

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState

    data class UIState(
        val isServer: Boolean = false,
        val isConnected: Boolean = false,
        val isRecording: Boolean = false,
        val isProcessing: Boolean = false,
        val isPlaying: Boolean = false,
        val currentLanguage: Language = Language.ENGLISH,
        val mode: String = "RETRO",
        val lastSentText: String = "",
        val lastReceivedText: String = "",
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val ipAddress: String = "",
        val port: String = "9876",
        val metrics: MetricsEngine.FullMetrics? = null,
        val codecComparison: EncodingComparison? = null,
        val isPTTMode: Boolean = false,
        val showSettings: Boolean = false,
        val activeTab: Int = 0, // 0=home, 1=metrics, 2=experiment, 3=settings
        val signalStrength: Int = 0,
        val packetCount: Pair<Int, Int> = Pair(0, 0), // TX / RX
        val compressionPercent: Double = 0.0,
        val latencyMs: Long = 0
    )

    init {
        initializePipeline()
        observeTransport()
    }

    private fun initializePipeline() {
        speechPipeline = SpeechPipeline(
            context = getApplication(),
            sttEngine = sttEngine,
            ttsEngine = ttsEngine,
            speechCodec = if (useRetroCodec) retroCodec else baselineCodec,
            transport = simulatedTransport,
            metricsEngine = app.metricsEngine
        )
    }

    private fun observeTransport() {
        viewModelScope.launch {
            wifiTransport.connectionStatus.collect { status ->
                _uiState.value = _uiState.value.copy(
                    connectionStatus = status,
                    isConnected = status == ConnectionStatus.CONNECTED
                )
            }
        }
    }

    fun startServer() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isServer = true)
            wifiTransport.startServer(_uiState.value.port.toIntOrNull() ?: 9876)
            speechPipeline?.startReceiving()
        }
    }

    fun connectToServer(host: String) {
        viewModelScope.launch {
            wifiTransport.connect(host, _uiState.value.port.toIntOrNull() ?: 9876)
            speechPipeline?.startReceiving()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            wifiTransport.disconnect()
        }
    }

    fun setLanguage(language: Language) {
        _uiState.value = _uiState.value.copy(currentLanguage = language)
        speechPipeline?.setLanguage(language)
    }

    fun setMode(retro: Boolean) {
        useRetroCodec = retro
        _uiState.value = _uiState.value.copy(
            mode = if (retro) "RETRO" else "BASELINE"
        )
        initializePipeline()
    }

    fun setPort(port: String) {
        _uiState.value = _uiState.value.copy(port = port)
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

    fun setActiveTab(tab: Int) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun toggleSettings() {
        _uiState.value = _uiState.value.copy(showSettings = !_uiState.value.showSettings)
    }

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
                packetLoss = 0,
                retransmissions = 0,
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

    fun simulatePacketLoss(lossRate: Float) {
        simulatedTransport.setPacketLoss(lossRate)
    }

    fun updateMetrics() {
        val metrics = app.metricsEngine.snapshot()
        val transport = simulatedTransport.getStats()
        _uiState.value = _uiState.value.copy(
            metrics = metrics,
            compressionPercent = metrics.size.compressionPercentage,
            latencyMs = metrics.latency.totalLatencyMs
        )
    }

    override fun onCleared() {
        super.onCleared()
        speechPipeline?.shutdown()
        viewModelScope.launch {
            wifiTransport.disconnect()
        }
    }
}
