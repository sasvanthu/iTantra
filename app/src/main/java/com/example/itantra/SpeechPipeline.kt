package com.example.itantra

import android.content.Context
import com.example.itantra.codec.*
import com.example.itantra.metrics.MetricsEngine
import com.example.itantra.ops.LowPowerController
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
    private val metricsEngine: MetricsEngine,
    private val lowPowerController: LowPowerController? = null
) {

    companion object {
        private const val TAG = "SpeechPipeline"
        private const val TTS_UTTERANCE_TIMEOUT_MS = 20_000L
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
        val sttMeasured: Boolean,
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
        // Honest STT latency: only a send that follows an actual recording session
        // can report transcription cost. Manual SEND / EMERGENCY with no captured
        // speech must NOT re-hash the process lifetime into a bogus millisecond
        // figure; those sends report sttMeasured = false.
        val sttMeasured = recordingStartTime > 0L
        val sttLatencyMs = if (sttMeasured) System.currentTimeMillis() - recordingStartTime else 0L
        if (sttMeasured) metricsEngine.recordSTTLatency(sttLatencyMs)

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
            // Low-power governor gating: at LOW the last battery is reserved for
            // NORMAL+ traffic, at CRITICAL only CRITICAL/emergency may transmit.
            // A deferred message is reported honestly — nothing goes on the wire,
            // and no success is claimed.
            if (lowPowerController?.canTransmit(encoded.importance) == false) {
                val deferred = SpeechSendReport(
                    text = text,
                    language = language,
                    originalUtf8Bytes = encoded.originalUtf8Size,
                    encodedBytes = encoded.finalEncodedSize,
                    packetCount = 0,
                    transmittedBytes = 0,
                    retransmissions = 0,
                    roundTripTimeMs = 0,
                    sttLatencyMs = sttLatencyMs,
                    sttMeasured = sttMeasured,
                    encodeLatencyMs = encodeMs,
                    packetizeLatencyMs = packetizeMs,
                    networkLatencyMs = 0,
                    totalLatencyMs = System.currentTimeMillis() - totalStart,
                    compressionPercentage = encoded.compressionPercentage,
                    isEmergency = effectiveEmergency,
                    failed = true,
                    detail = "deferred by power governor (${lowPowerController.profile.name})",
                    overNetwork = true
                )
                _pipelineState.value = _pipelineState.value.copy(
                    isProcessing = false,
                    lastSendReport = deferred
                )
                return deferred
            }
            val networkStart = System.currentTimeMillis()
            val result: SendResult? = transport.send(
                data = encoded.data,
                language = language,
                isEmergency = effectiveEmergency,
                priority = encoded.importance.level.toByte()
            )
            val networkLatencyMs = System.currentTimeMillis() - networkStart
            metricsEngine.recordTransportLatency(networkLatencyMs)
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
                sttMeasured = sttMeasured,
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
                sttMeasured = sttMeasured,
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
                _pipelineState.value = _pipelineState.value.copy(lastReceivedText = text)

                // Speak the received message (the injected engine applies its
                // own presence/emergency gating). Synthesis time is measured.
                speakReceived(text, payload.messageId, payload.isEmergency)
            }
        }
        _pipelineState.value = _pipelineState.value.copy(isPlaying = false)

        val total = System.currentTimeMillis() - start
        metricsEngine.recordTotalLatency(total)
    }

    /**
     * Play the received text through the offline TTS engine and record the
     * real synthesis latency (bounded so a missing/failed engine never blocks
     * the receive path).
     */
    private suspend fun speakReceived(text: String, messageId: Long, isEmergency: Boolean) {
        if (!ttsEngine.isInitialized()) return
        _pipelineState.value = _pipelineState.value.copy(isPlaying = true)
        val started = System.currentTimeMillis()
        val done = CompletableDeferred<Unit>()
        ttsEngine.speak(
            text = text,
            utteranceId = "recv-$messageId-${System.nanoTime()}",
            onDone = { done.complete(Unit) }
        )
        withTimeoutOrNull(TTS_UTTERANCE_TIMEOUT_MS) { done.await() }
        metricsEngine.recordTTSLatency(System.currentTimeMillis() - started)
        _pipelineState.value = _pipelineState.value.copy(isPlaying = false)
    }

    fun shutdown() {
        receiveJob?.cancel()
        scope.cancel()
        sttEngine.shutdown()
        ttsEngine.shutdown()
    }

    fun getMetrics(): MetricsEngine.FullMetrics = metricsEngine.snapshot()
}