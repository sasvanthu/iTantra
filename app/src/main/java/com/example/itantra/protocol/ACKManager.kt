package com.example.itantra.protocol

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ACKManager {

    companion object {
        private const val TAG = "ACKManager"
        private const val RETRANSMIT_TIMEOUT_MS = 2000L
        private const val MAX_RETRANSMISSIONS = 3
    }

    private val pendingAcks = ConcurrentHashMap<Long, PendingPacket>()
    private val receivedPackets = ConcurrentHashMap.newKeySet<Long>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _ackEvents = MutableStateFlow<ACKEvent?>(null)
    val ackEvents: StateFlow<ACKEvent?> = _ackEvents

    data class PendingPacket(
        val packet: Packet,
        val timestamp: Long = System.currentTimeMillis(),
        var retransmitCount: Int = 0
    )

    sealed class ACKEvent {
        data class AckReceived(val messageId: Long, val sequenceId: Int) : ACKEvent()
        data class NackReceived(val messageId: Long, val sequenceId: Int) : ACKEvent()
        data class RetransmitNeeded(val packet: Packet) : ACKEvent()
        data class MaxRetransmissionsReached(val messageId: Long, val sequenceId: Int) : ACKEvent()
    }

    fun trackPacket(packet: Packet) {
        val key = packetKey(packet.messageId, packet.sequenceId)
        pendingAcks[key] = PendingPacket(packet)
        scheduleRetransmitCheck(packet, key)
    }

    private fun scheduleRetransmitCheck(packet: Packet, key: Long) {
        scope.launch {
            delay(RETRANSMIT_TIMEOUT_MS)
            val pending = pendingAcks[key] ?: return@launch
            if (pending.retransmitCount < MAX_RETRANSMISSIONS) {
                pending.retransmitCount++
                Log.w(TAG, "Retransmit needed for msg=${packet.messageId} seq=${packet.sequenceId}")
                _ackEvents.value = ACKEvent.RetransmitNeeded(pending.packet)
                scheduleRetransmitCheck(pending.packet, key)
            } else {
                pendingAcks.remove(key)
                Log.e(TAG, "Max retransmissions reached for msg=${packet.messageId} seq=${packet.sequenceId}")
                _ackEvents.value = ACKEvent.MaxRetransmissionsReached(
                    packet.messageId, packet.sequenceId
                )
            }
        }
    }

    fun receiveAck(messageId: Long, sequenceId: Int) {
        val key = packetKey(messageId, sequenceId)
        pendingAcks.remove(key)
        _ackEvents.value = ACKEvent.AckReceived(messageId, sequenceId)
    }

    fun receiveNack(messageId: Long, sequenceId: Int) {
        val key = packetKey(messageId, sequenceId)
        val pending = pendingAcks[key]
        if (pending != null) {
            pending.retransmitCount++
            _ackEvents.value = ACKEvent.NackReceived(messageId, sequenceId)
            _ackEvents.value = ACKEvent.RetransmitNeeded(pending.packet)
        }
    }

    fun isDuplicate(messageId: Long, sequenceId: Int): Boolean {
        val key = packetKey(messageId, sequenceId)
        return !receivedPackets.add(key)
    }

    fun getPendingCount(): Int = pendingAcks.size

    fun getStats(): ACKStats {
        return ACKStats(
            pendingCount = pendingAcks.size,
            receivedCount = receivedPackets.size
        )
    }

    fun reset() {
        pendingAcks.clear()
        receivedPackets.clear()
    }

    fun shutdown() {
        scope.cancel()
        reset()
    }

    private fun packetKey(messageId: Long, sequenceId: Int): Long {
        return messageId * 10000 + sequenceId
    }

    data class ACKStats(
        val pendingCount: Int,
        val receivedCount: Int
    )
}
