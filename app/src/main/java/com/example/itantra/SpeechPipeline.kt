package com.example.itantra

import android.content.Context
import android.util.Log
import com.example.itantra.codec.*
import com.example.itantra.data.AdaptiveBandwidth
import com.example.itantra.metrics.MetricsEngine
import com.example.itantra.protocol.Packet
import com.example.itantra.protocol.PacketHandlingResult
import com.example.itantra.protocol.PacketManager
import com.example.itantra.speech.stt.STTEngine
import com.example.itantra.speech.stt.SimulatedSTTEngine
import com.example.itantra.speech.tts.TTSEngine
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

    private val packetManager = PacketManager()
    private val adaptiveBandwidth = AdaptiveBandwidth()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentLanguage = Language.ENGLISH
    private var isPTTMode = false
    private var isRecording = false

    private val _pipelineState = MutableStateFlow(PipelineState())
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>()
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages

    private var recordingStartTime = 0L
    private var currentText = ""

    data class PipelineState(
        val isRecording: Boolean = false,
        val isProcessing: Boolean = false,
        val isPlaying: Boolean = false,
        val connectionStatus: com.example.itantra.transport.ConnectionStatus = com.example.itantra.transport.ConnectionStatus.DISCONNECTED,
        val lastSentText: String = "",
        val lastReceivedText: String = "",
        val currentMetrics: MetricsEngine.FullMetrics? = null,
        val language: Language = Language.ENGLISH,
        val mode: String = "RETRO",
        val bandwidthMode: AdaptiveBandwidth.BandwidthMode = AdaptiveBandwidth.BandwidthMode.NORMAL
    )

    data class IncomingMessage(
        val text: String,
        val language: Language,
        val timestamp: Long,
        val isEmergency: Boolean
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
            Log.d(TAG, "STT result: $text")

            if (isPTTMode) {
                // In PTT mode, process when user releases button
            } else {
                // In normal mode, process after stable sentence
                processAndSend(text)
            }
        }
    }

    fun stopListening() {
        if (!isRecording) return
        isRecording = false
        sttEngine.stopListening()
        _pipelineState.value = _pipelineState.value.copy(isRecording = false)

        if (isPTTMode && currentText.isNotBlank()) {
            processAndSend(currentText)
        }
    }

    private fun processAndSend(text: String) {
        if (text.isBlank()) return

        scope.launch {
            val state = _pipelineState.value.copy(isProcessing = true)
            _pipelineState.value = state

            val totalStartTime = System.currentTimeMillis()

            // STT latency
            val sttLatency = System.currentTimeMillis() - recordingStartTime
            metricsEngine.recordSTTLatency(sttLatency)

            // Encoding
            val encodeStart = System.currentTimeMillis()
            val encodedPayload = speechCodec.encodeWithComparison(text, currentLanguage)
            val encodeLatency = System.currentTimeMillis() - encodeStart
            metricsEngine.recordEncodingLatency(encodeLatency)

            // Packetization
            val packetizeStart = System.currentTimeMillis()
            val packets = packetManager.createPackets(
                speechCodec.encode(text, currentLanguage).data,
                currentLanguage,
                priority = if (isEmergencyMessage(text)) 0 else 2,
                isEmergency = isEmergencyMessage(text)
            )
            val packetizeLatency = System.currentTimeMillis() - packetizeStart
            metricsEngine.recordPacketizationLatency(packetizeLatency)

            // Record size metrics
            metricsEngine.recordSizeMetrics(
                originalUtf8 = encodedPayload.originalUtf8Bytes,
                tokenEncoded = encodedPayload.tokenEncodedBytes,
                phonemeEncoded = encodedPayload.phonemeEncodedBytes,
                finalEncoded = encodedPayload.retroEncodedBytes
            )

            // Transport
            val transportStart = System.currentTimeMillis()
            for (packet in packets) {
                packetManager.trackOutgoingPacket(packet)
                transport.send(packet)
                metricsEngine.recordPacketSent()
            }
            val transportLatency = System.currentTimeMillis() - transportStart
            metricsEngine.recordTransportLatency(transportLatency)

            // Total latency
            val totalLatency = System.currentTimeMillis() - totalStartTime
            metricsEngine.recordTotalLatency(totalLatency)

            val metrics = metricsEngine.snapshot()

            _pipelineState.value = _pipelineState.value.copy(
                isProcessing = false,
                lastSentText = text,
                currentMetrics = metrics
            )

            Log.i(TAG, "Sent: $text (${encodedPayload.retroEncodedBytes} bytes, " +
                    "${encodedPayload.compressionPercentage}% compression, " +
                    "${packets.size} packets, ${totalLatency}ms total)")
        }
    }

    fun startReceiving() {
        scope.launch {
            transport.receivedPackets.collect { packet ->
                val result = packetManager.handleReceivedPacket(packet)
                handlePacketResult(result)
            }
        }
    }

    private suspend fun handlePacketResult(result: PacketHandlingResult) {
        when (result) {
            is PacketHandlingResult.Complete -> {
                val decodeStart = System.currentTimeMillis()
                val decodeResult = speechCodec.decode(result.payload)
                val decodeLatency = System.currentTimeMillis() - decodeStart
                metricsEngine.recordDecodingLatency(decodeLatency)

                if (decodeResult is DecodeResult.Success) {
                    val ttsStart = System.currentTimeMillis()
                    ttsEngine.speak(decodeResult.reconstructedText)
                    val ttsLatency = System.currentTimeMillis() - ttsStart
                    metricsEngine.recordTTSLatency(ttsLatency)

                    _incomingMessages.emit(
                        IncomingMessage(
                            text = decodeResult.reconstructedText,
                            language = decodeResult.representation.language,
                            timestamp = System.currentTimeMillis(),
                            isEmergency = false
                        )
                    )

                    _pipelineState.value = _pipelineState.value.copy(
                        lastReceivedText = decodeResult.reconstructedText,
                        isPlaying = true
                    )
                }
            }
            is PacketHandlingResult.NeedsAck -> {
                transport.send(result.ack)
            }
            is PacketHandlingResult.Corrupted -> {
                metricsEngine.recordPacketLoss()
                val nack = Packet.createNackPacket(
                    result.packet.messageId,
                    result.packet.sequenceId
                )
                transport.send(nack)
            }
            is PacketHandlingResult.RetransmitRequested -> {
                metricsEngine.recordRetransmission()
                transport.send(result.packet)
            }
            else -> {
                Log.d(TAG, "Unhandled packet result: $result")
            }
        }
    }

    private fun isEmergencyMessage(text: String): Boolean {
        val emergencyWords = setOf(
            "HELP", "FIRE", "SOS", "DANGER", "EMERGENCY",
            "मदद", "आग", "खतरा", "आपातकाल",
            "உதவி", "தீ", "ஆபத்து", "அவசரம்"
        )
        val upperText = text.uppercase()
        return emergencyWords.any { upperText.contains(it) }
    }

    fun shutdown() {
        scope.cancel()
        sttEngine.shutdown()
        ttsEngine.shutdown()
        packetManager.shutdown()
    }

    fun getMetrics(): MetricsEngine.FullMetrics = metricsEngine.snapshot()
    fun getAdaptiveBandwidth(): AdaptiveBandwidth = adaptiveBandwidth
    fun getPacketManager(): PacketManager = packetManager
}
