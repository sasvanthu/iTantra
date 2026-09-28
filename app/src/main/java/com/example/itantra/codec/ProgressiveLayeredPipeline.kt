package com.example.itantra.codec

import com.example.itantra.data.AdaptiveBandwidth
import com.example.itantra.data.ProgressiveTransmission
import com.example.itantra.transport.AdaptiveLinkGovernor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Encapsulates an individual progressive transmission layer packet.
 *
 * Layer identifiers:
 *  0 = SUTRA Semantic frame (critical meaning, always sent)
 *  1 = Brahmic verbatim transcript (complete text, always sent)
 *  2 = Optional enhancement (context/acoustic annotation, best-effort only)
 */
data class LayeredPacket(
    val messageId: Long,
    val layerId: Int, // 0 = SUTRA Semantic, 1 = Brahmic Transcript, 2 = Optional enhancement
    val totalLayers: Int,
    val payload: ByteArray,
    val priority: Int // 0..3 matching PriorityFrameQueue
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayeredPacket) return false
        return messageId == other.messageId &&
            layerId == other.layerId &&
            totalLayers == other.totalLayers &&
            priority == other.priority &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + layerId
        result = 31 * result + totalLayers
        result = 31 * result + priority
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val LAYER_MAGIC: Byte = 0x50 // 'P'

        fun serialize(packet: LayeredPacket): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(LAYER_MAGIC.toInt())
            val bb8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(packet.messageId)
            out.write(bb8.array())
            out.write(packet.layerId)
            out.write(packet.totalLayers)
            out.write(packet.priority)
            val bb4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(packet.payload.size)
            out.write(bb4.array())
            out.write(packet.payload)
            return out.toByteArray()
        }

        fun deserialize(bytes: ByteArray): LayeredPacket? {
            if (bytes.size < 16) return null
            val input = ByteArrayInputStream(bytes)
            if (input.read().toByte() != LAYER_MAGIC) return null

            val bb8 = ByteArray(8)
            input.read(bb8)
            val messageId = ByteBuffer.wrap(bb8).order(ByteOrder.LITTLE_ENDIAN).long

            val layerId = input.read()
            val totalLayers = input.read()
            val priority = input.read()

            val bb4 = ByteArray(4)
            input.read(bb4)
            val payloadSize = ByteBuffer.wrap(bb4).order(ByteOrder.LITTLE_ENDIAN).int
            if (payloadSize < 0 || payloadSize > input.available()) return null

            val payload = ByteArray(payloadSize)
            input.read(payload)

            return LayeredPacket(
                messageId = messageId,
                layerId = layerId,
                totalLayers = totalLayers,
                payload = payload,
                priority = priority
            )
        }
    }
}

/**
 * Pipeline coordinating multi-layer progressive transmission with adaptive bandwidth governance.
 */
object ProgressiveLayeredPipeline {

    /**
     * Splits an outgoing message into progressive transmission layers adapted to the link governor.
     * Layer 0: SUTRA Semantic Frame (priority 3/CRITICAL).
     * Layer 1: Brahmic Compressed Transcript.
     * Layer 2: OPTIONAL enhancement. `contextPayload` is caller-supplied
     *          (Brahmic-encoded annotation bytes); when null no Layer 2 is
     *          produced, keeping the default two-layer wire form unchanged.
     *          Layer 2 is best-effort: losing it must never corrupt Layers 0/1.
     */
    fun createLayers(
        messageId: Long,
        text: String,
        language: Language,
        isEmergency: Boolean,
        linkGovernor: AdaptiveLinkGovernor? = null,
        explicitSutra: SutraFrame? = null,
        contextPayload: ByteArray? = null
    ): List<LayeredPacket> {
        val sutra = explicitSutra ?: SutraParser.parse(text, language)
        val sutraBytes = SutraFrame.serialize(sutra)

        val layer0Priority = if (isEmergency || sutra.isCritical) 3 else 2
        val baseLayerCount = if (contextPayload == null) 2 else 3
        val layer0 = LayeredPacket(
            messageId = messageId,
            layerId = 0,
            totalLayers = baseLayerCount,
            payload = sutraBytes,
            priority = layer0Priority
        )

        val isCongested = linkGovernor?.mode() == AdaptiveBandwidth.BandwidthMode.EMERGENCY ||
            linkGovernor?.shouldDeferLowPriority() == true

        val transcriptBytes = BrahmicCodec.encode(text)
        val layer1Priority = if (isCongested) 0 else 1
        val layer1 = LayeredPacket(
            messageId = messageId,
            layerId = 1,
            totalLayers = baseLayerCount,
            payload = transcriptBytes,
            priority = layer1Priority
        )

        if (contextPayload == null) return listOf(layer0, layer1)

        // Optional enhancement rides the background band and is never
        // required for the base message to be meaningful.
        val layer2 = LayeredPacket(
            messageId = messageId,
            layerId = 2,
            totalLayers = 3,
            payload = contextPayload,
            priority = 0
        )
        return listOf(layer0, layer1, layer2)
    }
}

/**
 * Reassembles multi-layer progressive packets into structured messages.
 * Signals immediately upon Layer 0 delivery, then refines to verbatim transcript upon Layer 1.
 */
class ProgressiveReassembler {

    data class ProgressiveMessageState(
        val messageId: Long,
        val sutraFrame: SutraFrame?,
        val transcript: String?,
        val layer0Received: Boolean = false,
        val layer1Received: Boolean = false,
        val layer2Received: Boolean = false,
        val context: String? = null,
        val estimatedQuality: Float = 0f,
        val isComplete: Boolean = false
    ) {
        val summaryText: String
            get() = transcript ?: (sutraFrame?.let { SutraFrame.toHumanReadable(it) } ?: "")
    }

    private val inFlight = ConcurrentHashMap<Long, ProgressiveMessageState>()
    private val completed = ConcurrentHashMap<Long, ProgressiveMessageState>()

    /**
     * Ingests one incoming [LayeredPacket] and returns the updated state.
     * Layer 2 (optional enhancement) can arrive before, between, or after
     * Layers 0/1 and is always optional: it never forces completeness and
     * never downgrades an already-complete message.
     */
    fun feed(packet: LayeredPacket): ProgressiveMessageState {
        val current = inFlight[packet.messageId] ?: completed[packet.messageId] ?: ProgressiveMessageState(
            messageId = packet.messageId,
            sutraFrame = null,
            transcript = null
        )

        val updated = when (packet.layerId) {
            0 -> {
                val frame = SutraFrame.deserialize(packet.payload)
                val allDone = current.layer1Received || current.isComplete
                current.copy(
                    sutraFrame = frame,
                    layer0Received = true,
                    estimatedQuality = if (allDone) 1.0f else 0.70f,
                    isComplete = allDone
                )
            }
            1 -> {
                val text = BrahmicCodec.decode(packet.payload)
                current.copy(
                    transcript = text,
                    layer1Received = true,
                    estimatedQuality = 1.0f,
                    isComplete = true
                )
            }
            2 -> {
                // Best-effort enhancement: never changes completeness.
                val context = BrahmicCodec.decode(packet.payload) ?: ""
                current.copy(
                    context = context,
                    layer2Received = true
                )
            }
            else -> current
        }

        if (updated.isComplete) {
            completed[packet.messageId] = updated
            inFlight.remove(packet.messageId)
        } else {
            inFlight[packet.messageId] = updated
        }
        return updated
    }

    fun getPendingCount(): Int = inFlight.size

    fun reset() {
        inFlight.clear()
        completed.clear()
    }
}
