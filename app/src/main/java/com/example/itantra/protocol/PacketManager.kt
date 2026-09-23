package com.example.itantra.protocol

import android.util.Log
import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PacketManager {

    companion object {
        private const val TAG = "PacketManager"
        private const val MAX_PAYLOAD_SIZE = 1024
    }

    private val ackManager = ACKManager()
    private val messageIdCounter = AtomicLong(System.currentTimeMillis())
    private val sequenceCounter = AtomicInteger(0)

    private val receivedMessages = ConcurrentHashMap<Long, MutableMap<Int, ByteArray>>()

    private val _packetEvents = MutableStateFlow<PacketEvent?>(null)
    val packetEvents: StateFlow<PacketEvent?> = _packetEvents

    sealed class PacketEvent {
        data class PacketSent(val packet: Packet, val size: Int) : PacketEvent()
        data class PacketReceived(val packet: Packet, val size: Int) : PacketEvent()
        data class MessageComplete(val messageId: Long, val payload: ByteArray) : PacketEvent()
        data class CorruptedPacket(val messageId: Long, val sequenceId: Int) : PacketEvent()
    }

    fun createPackets(
        payload: ByteArray,
        language: Language,
        priority: Byte = 2,
        isEmergency: Boolean = false
    ): List<Packet> {
        val messageId = messageIdCounter.incrementAndGet()
        sequenceCounter.set(0)

        val packets = mutableListOf<Packet>()

        // START packet
        packets.add(Packet.createStartPacket(messageId, language))

        // Split payload into chunks
        val chunks = splitPayload(payload, MAX_PAYLOAD_SIZE)
        for (chunk in chunks) {
            val seqId = sequenceCounter.incrementAndGet()
            val packet = if (isEmergency) {
                Packet.createEmergencyPacket(messageId, seqId, language, chunk)
            } else {
                Packet.createTextPacket(messageId, seqId, language, chunk, priority)
            }
            packets.add(packet)
        }

        // END packet
        packets.add(Packet.createEndPacket(messageId, language))

        return packets
    }

    fun handleReceivedPacket(packet: Packet): PacketHandlingResult {
        // Verify CRC
        if (!verifyCRC(packet)) {
            Log.w(TAG, "CRC verification failed for msg=${packet.messageId} seq=${packet.sequenceId}")
            return PacketHandlingResult.Corrupted(packet)
        }

        // Check for duplicates
        if (ackManager.isDuplicate(packet.messageId, packet.sequenceId)) {
            Log.d(TAG, "Duplicate packet ignored: msg=${packet.messageId} seq=${packet.sequenceId}")
            return PacketHandlingResult.Duplicate(packet)
        }

        when (packet.packetType) {
            PacketType.START -> {
                receivedMessages[packet.messageId] = mutableMapOf()
                return PacketHandlingResult.Started(packet.messageId)
            }
            PacketType.TEXT_DATA -> {
                val messageParts = receivedMessages.getOrPut(packet.messageId) { mutableMapOf() }
                messageParts[packet.sequenceId] = packet.payload
                _packetEvents.value = PacketEvent.PacketReceived(packet, packet.payload.size)

                // Send ACK
                val ack = Packet.createAckPacket(packet.messageId, packet.sequenceId)
                return PacketHandlingResult.NeedsAck(packet, ack)
            }
            PacketType.END -> {
                val messageParts = receivedMessages.remove(packet.messageId)
                if (messageParts != null) {
                    val assembledPayload = assemblePayload(messageParts)
                    _packetEvents.value = PacketEvent.MessageComplete(packet.messageId, assembledPayload)
                    return PacketHandlingResult.Complete(packet.messageId, assembledPayload)
                }
                return PacketHandlingResult.Error("No message parts for end packet")
            }
            PacketType.ACK -> {
                ackManager.receiveAck(packet.messageId, packet.sequenceId)
                return PacketHandlingResult.AckReceived(packet)
            }
            PacketType.NACK -> {
                ackManager.receiveNack(packet.messageId, packet.sequenceId)
                return PacketHandlingResult.NackReceived(packet)
            }
            PacketType.RETRANSMIT -> {
                return PacketHandlingResult.RetransmitRequested(packet)
            }
            else -> {
                return PacketHandlingResult.Unknown(packet)
            }
        }
    }

    fun trackOutgoingPacket(packet: Packet) {
        ackManager.trackPacket(packet)
    }

    private fun splitPayload(payload: ByteArray, maxSize: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxSize, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    private fun assemblePayload(parts: Map<Int, ByteArray>): ByteArray {
        val sorted = parts.toSortedMap()
        val totalSize = sorted.values.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for ((_, part) in sorted) {
            System.arraycopy(part, 0, result, offset, part.size)
            offset += part.size
        }
        return result
    }

    private fun verifyCRC(packet: Packet): Boolean {
        if (packet.payload.isEmpty() && packet.packetType != PacketType.TEXT_DATA) {
            return true
        }
        val headerData = ByteArray(16)
        System.arraycopy(longToBytes(packet.messageId), 0, headerData, 0, 8)
        System.arraycopy(intToBytes(packet.sequenceId), 0, headerData, 8, 4)
        headerData[12] = packet.language.code.toByteArray()[0]
        headerData[13] = packet.packetType.id
        headerData[14] = packet.priority
        headerData[15] = packet.flags

        val crcData = headerData + packet.payload
        val computedCRC = Packet.computeCRC(crcData)
        return computedCRC == packet.crc
    }

    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value shr 56).toByte(), (value shr 48).toByte(),
            (value shr 40).toByte(), (value shr 32).toByte(),
            (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte()
        )
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte()
        )
    }

    fun getAckManager(): ACKManager = ackManager

    fun reset() {
        receivedMessages.clear()
        sequenceCounter.set(0)
        ackManager.reset()
    }

    fun shutdown() {
        ackManager.shutdown()
        reset()
    }
}

sealed class PacketHandlingResult {
    data class Started(val messageId: Long) : PacketHandlingResult()
    data class Complete(val messageId: Long, val payload: ByteArray) : PacketHandlingResult()
    data class NeedsAck(val received: Packet, val ack: Packet) : PacketHandlingResult()
    data class Corrupted(val packet: Packet) : PacketHandlingResult()
    data class Duplicate(val packet: Packet) : PacketHandlingResult()
    data class AckReceived(val packet: Packet) : PacketHandlingResult()
    data class NackReceived(val packet: Packet) : PacketHandlingResult()
    data class RetransmitRequested(val packet: Packet) : PacketHandlingResult()
    data class Error(val message: String) : PacketHandlingResult()
    data class Unknown(val packet: Packet) : PacketHandlingResult()
}
