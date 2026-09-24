package com.example.itantra

import android.content.Context
import com.example.itantra.codec.*
import com.example.itantra.metrics.MetricsEngine
import com.example.itantra.protocol.Packetizer
import com.example.itantra.speech.stt.STTEngine
import com.example.itantra.speech.stt.SimulatedSTTEngine
import com.example.itantra.speech.tts.TTSEngine
import com.example.itantra.transport.ConnectionStatus
import com.example.itantra.transport.ReassembledPayload
import com.example.itantra.transport.SendResult
import com.example.itantra.transport.TransportEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SpeechPipeline(
    private val context: Context,
    private val sttEngine: STTEngine,
    private val ttsEngine: TTSEngine,
    private val speechCodec: SpeechCodec,
    private val transport: TransportEngine,
    private val metricsEngine: MetricsEngine
) {

    companion object {
        private const val TAG = "SpeechPipeline"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentLanguage = Language.ENGLISH
    private var isPTTMode = false
    private var isRecording = false
    private var recordingStartTime = 0L
    private var currentText = ""
    private var receiveJob: Job? = null

    /**
     * Single-device round trip hook used when there is no connected peer:
     * STT output -> codec -> packet -> decode, proving the representation works.
     */
    var loopbackHandler: ((String, Language) -> CodecLabResult)? = null

    private val _pipelineState = MutableStateFlow(PipelineState())
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages

    data class PipelineState(
        val isRecording: Boolean = false,
        val isProcessing: Boolean = false,
        val isPlaying: Boolean = false,
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val lastSentText: String = "",
        val lastReceivedText: String = "",
        val currentMetrics: MetricsEngine.FullMetrics? = null,
        val lastLoopback: CodecLabResult? = null,
        val lastSendReport: SpeechSendReport? = null,
        val language: Language = Language.ENGLISH,
        val mode: String = "RETRO"
    )

    data class IncomingMessage(
        val text: String,
        val language: Language,
        val timestamp: Long,
        val isEmergency: Boolean
    )

    /**
     * Honest per-message send measurements: codec size vs actual transmitted
     * bytes, both network latency figures and the total wall-clock time.
     */
    data class SpeechSendReport(
        val text: String,
        val language: Language,
        val originalUtf8Bytes: Int,
        val encodedBytes: Int,
        val packetCount: Int,
        val transmittedBytes: Int,
        val retransmissions: Int,
        val roundTripTimeMs: Long,
        val sttLatencyMs: Long,
        val encodeLatencyMs: Long,
        val packetizeLatencyMs: Long,
        val networkLatencyMs: Long,
        val totalLatencyMs: Long,
        val compressionPercentage: Double,
        val isEmergency: Boolean,
        val failed: Boolean,
        val detail: String,
        val overNetwork: Boolean
    )

    fun setLanguage(language: Language) {
        currentLanguage = language
        _pipelineState.value = _pipelineState.value.copy(language = language)
        sttEngine.initialize(context, language)
        ttsEngine.initialize(context, language)
    }

    fun setPTTMode(enabled: Boolean) {
        isPTTMode = enabled
    }

    fun startListening() {
        if (isRecording) return
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        currentText = ""
        _pipelineState.value = _pipelineState.value.copy(isRecording = true)

        sttEngine.startListening { text ->
            currentText = text
            if (!isPTTMode) {
                launchSend(text)
            }
        }
    }

    fun stopListening() {
        if (!isRecording) return
        isRecording = false
        sttEngine.stopListening()
        _pipelineState.value = _pipelineState.value.copy(isRecording = false)

        if (isPTTMode && currentText.isNotBlank()) {
            launchSend(currentText)
        }
    }

    /** Send an already-transcribed string (manual send / PTT release). */
    fun launchSend(text: String, language: Language = currentLanguage, forceEmergency: Boolean = false) {
        scope.launch {
            sendText(text, language, forceEmergency)
        }
    }

    /** Full send pipeline. Safe to call from the UI thread. */
    suspend fun sendText(
        text: String,
        language: Language = currentLanguage,
        forceEmergency: Boolean = false
    ): SpeechSendReport? {
        if (text.isBlank()) return null

        _pipelineState.value = _pipelineState.value.copy(isProcessing = true)
        val totalStart = System.currentTimeMillis()
        val sttLatencyMs = System.currentTimeMillis() - recordingStartTime
        metricsEngine.recordSTTLatency(sttLatencyMs.toLong())

        val connected = transport.isConnected()
        val isEmergency = forceEmergency

        val encodeStart = System.nanoTime()
        val encoded = withContext(Dispatchers.Default) { speechCodec.encode(text, currentLanguage) }
        val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000L
        metricsEngine.recordEncodingLatency(encodeMs)

        val effectiveEmergency = isEmergency || encoded.importance == Importance.CRITICAL
        metricsEngine.recordSizeMetrics(
            originalUtf8 = text.toByteArray(Charsets.UTF_8).size,
            tokenEncoded = encoded.tokenEncodedSize,
            phonemeEncoded = encoded.phonemeEncodedSize,
            finalEncoded = encoded.finalEncodedSize
        )

        val packetizeStart = System.nanoTime()
        val packets = Packetizer.buildPackets(
            payload = encoded.data,
            language = language,
            messageId = 1,
            priority = encoded.importance.level,
            isEmergency = effectiveEmergency
        )
        val packetizeMs = (System.nanoTime() - packetizeStart) / 1_000_000L
        metricsEngine.recordPacketizationLatency(packetizeMs)
        val packetBytes = packets.sumOf { it.serialize().size }
        metricsEngine.recordTotalPacketBytes(packetBytes)

        val report: SpeechSendReport = if (connected) {
            val networkStart = System.currentTimeMillis()
            val result: SendResult? = transport.send(
                data = encoded.data,
                language = language,
                isEmergency = effectiveEmergency,
                priority = encoded.importance.level.toByte()
            )
            val networkLatencyMs = System.currentTimeMillis() - networkStart
            metricsEngine.recordTransportLatency(networkLatencyMs)
            metricsEngine.recordNetworkReceiveLatency(0)
            metricsEngine.recordAckLatency(result?.roundTripTimeMs ?: 0)

            SpeechSendReport(
                text = text,
                language = language,
                originalUtf8Bytes = encoded.originalUtf8Size,
                encodedBytes = encoded.finalEncodedSize,
                packetCount = result?.packetCount ?: packets.size,
                transmittedBytes = result?.transmittedBytes ?: 0,
                retransmissions = result?.retransmissions ?: 0,
                roundTripTimeMs = result?.roundTripTimeMs ?: 0,
                sttLatencyMs = sttLatencyMs,
                encodeLatencyMs = encodeMs,
                packetizeLatencyMs = packetizeMs,
                networkLatencyMs = networkLatencyMs,
                totalLatencyMs = System.currentTimeMillis() - totalStart,
                compressionPercentage = encoded.compressionPercentage,
                isEmergency = effectiveEmergency,
                failed = result?.failed == true,
                detail = result?.detail ?: "queued",
                overNetwork = true
            )
        } else {
            // No peer: verify the local STT->codec->packet->decode path.
            val loopback = withContext(Dispatchers.Default) {
                speechCodec.performLab(text, language)
            }
            SpeechSendReport(
                text = text,
                language = language,
                originalUtf8Bytes = encoded.originalUtf8Size,
                encodedBytes = encoded.finalEncodedSize,
                packetCount = packets.size,
                transmittedBytes = packetBytes,
                retransmissions = 0,
                roundTripTimeMs = 0,
                sttLatencyMs = sttLatencyMs,
                encodeLatencyMs = encodeMs,
                packetizeLatencyMs = packetizeMs,
                networkLatencyMs = 0,
                totalLatencyMs = System.currentTimeMillis() - totalStart,
                compressionPercentage = encoded.compressionPercentage,
                isEmergency = effectiveEmergency,
                failed = loopback?.exactMatch == false,
                detail = if (loopback?.exactMatch == true) "local round-trip exact" else "local decode mismatch",
                overNetwork = false
            )
        }

        metricsEngine.recordTotalLatency(report.totalLatencyMs)

        val metrics = metricsEngine.snapshot()
        _pipelineState.value = _pipelineState.value.copy(
            isProcessing = false,
            lastSentText = text,
            currentMetrics = metrics,
            lastSendReport = report
        )

        return report
    }

    /** Start collecting decoded messages from the transport. */
    fun startReceiving() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            transport.incomingPayloads.collect { payload ->
                handleIncomingPayload(payload)
            }
        }
    }

    private suspend fun handleIncomingPayload(payload: ReassembledPayload) {
        _pipelineState.value = _pipelineState.value.copy(isPlaying = true)
        val start = System.currentTimeMillis()
        val decodeResult = withContext(Dispatchers.Default) { speechCodec.decode(payload.payload) }
        val decodeMs = System.currentTimeMillis() - start
        metricsEngine.recordDecodingLatency(decodeMs)
        metricsEngine.recordNetworkReceiveLatency(decodeMs)

        if (decodeResult is DecodeResult.Success) {
            val text = decodeResult.reconstructedText
            if (text.isNotEmpty()) {
                _incomingMessages.emit(
                    IncomingMessage(
                        text = text,
                        language = decodeResult.representation.language,
                        timestamp = payload.receivedAt,
                        isEmergency = payload.isEmergency
                    )
                )
                _pipelineState.value = _pipelineState.value.copy(
                    lastReceivedText = text,
                    isPlaying = false
                )
            }
        }

        val total = System.currentTimeMillis() - start
        metricsEngine.recordTotalLatency(total)
    }

    fun shutdown() {
        receiveJob?.cancel()
        scope.cancel()
        sttEngine.shutdown()
        ttsEngine.shutdown()
    }

    fun getMetrics(): MetricsEngine.FullMetrics = metricsEngine.snapshot()
}