package com.example.itantra.data

import com.example.itantra.codec.Language
import com.example.itantra.codec.PacketType

class AdaptiveBandwidth {

    enum class BandwidthMode {
        HIGH_BANDWIDTH,
        NORMAL,
        LOW_BANDWIDTH,
        EMERGENCY
    }

    data class ChannelStats(
        var packetLoss: Float = 0f,
        var rttMs: Long = 0,
        var throughput: Float = 0f,
        var retransmissionRate: Float = 0f,
        var mode: BandwidthMode = BandwidthMode.NORMAL
    )

    private val stats = ChannelStats()
    private var lossHistory = mutableListOf<Float>()
    private var rttHistory = mutableListOf<Long>()

    private val windowSize = 20

    fun updatePacketLoss(loss: Float) {
        lossHistory.add(loss)
        if (lossHistory.size > windowSize) lossHistory.removeAt(0)
        stats.packetLoss = lossHistory.average().toFloat()
        updateMode()
    }

    fun updateRTT(rttMs: Long) {
        rttHistory.add(rttMs)
        if (rttHistory.size > windowSize) rttHistory.removeAt(0)
        stats.rttMs = rttHistory.average().toLong()
        updateMode()
    }

    fun updateThroughput(bytesPerSecond: Float) {
        stats.throughput = bytesPerSecond
    }

    fun updateRetransmissionRate(rate: Float) {
        stats.retransmissionRate = rate
        updateMode()
    }

    private fun updateMode() {
        stats.mode = when {
            stats.packetLoss > 0.3f -> BandwidthMode.EMERGENCY
            stats.packetLoss > 0.15f || stats.rttMs > 500 -> BandwidthMode.LOW_BANDWIDTH
            stats.packetLoss > 0.05f || stats.rttMs > 200 -> BandwidthMode.NORMAL
            else -> BandwidthMode.HIGH_BANDWIDTH
        }
    }

    fun getStats(): ChannelStats = stats.copy()

    fun getMode(): BandwidthMode = stats.mode

    fun shouldPrioritizeImportance(): Boolean {
        return stats.mode == BandwidthMode.LOW_BANDWIDTH ||
               stats.mode == BandwidthMode.EMERGENCY
    }

    fun shouldUsePrediction(): Boolean {
        return stats.mode == BandwidthMode.LOW_BANDWIDTH
    }

    fun shouldDeferLowPriority(): Boolean {
        return stats.mode == BandwidthMode.LOW_BANDWIDTH ||
               stats.mode == BandwidthMode.EMERGENCY
    }

    fun reset() {
        lossHistory.clear()
        rttHistory.clear()
        stats.packetLoss = 0f
        stats.rttMs = 0
        stats.throughput = 0f
        stats.retransmissionRate = 0f
        stats.mode = BandwidthMode.NORMAL
    }
}
