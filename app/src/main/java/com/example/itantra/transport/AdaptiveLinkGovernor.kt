package com.example.itantra.transport

import com.example.itantra.data.AdaptiveBandwidth

/**
 * Bridges the raw per-session [LinkMetrics] into the [AdaptiveBandwidth]
 * controller and turns its mode into concrete transmission decisions.
 *
 * Decisions are deliberately conservative and always measurable:
 *
 *  - **maxPayload** — the per-DATA-packet ceiling used for packetization.
 *    Lower loss/throughput modes use smaller packets so a dropped BLE/radio
 *    atom hurts far less, at the cost of more packets.
 *  - **shouldDeferLowPriority** — under LOW/EMERGENCY conditions LOW-priority
 *    traffic is de-prioritized behind everything else (it is still sent; it
 *    is never dropped silently).
 *  - **shouldUsePrediction** — hint to the encoder path whether the compact
 *    representation should be favoured (the codec always stays lossless).
 *
 * The mode is derived ONLY from measured inputs (packet loss, RTT,
 * retransmission rate); nothing here fakes bandwidth numbers.
 */
class AdaptiveLinkGovernor(
    private val adaptive: AdaptiveBandwidth = AdaptiveBandwidth()
) {

    /** Throughput is a rate, so it needs the previous sample to be meaningful. */
    private var lastSampleMs = 0L
    private var lastTransmittedBytes = 0L

    /** Per-mode DATA packet payload adopted when the link changes state. */
    fun maxPayload(): Int = when (adaptive.getMode()) {
        AdaptiveBandwidth.BandwidthMode.EMERGENCY -> EMERGENCY_MAX_PAYLOAD
        AdaptiveBandwidth.BandwidthMode.LOW_BANDWIDTH -> LOW_MAX_PAYLOAD
        AdaptiveBandwidth.BandwidthMode.NORMAL -> NORMAL_MAX_PAYLOAD
        AdaptiveBandwidth.BandwidthMode.HIGH_BANDWIDTH -> HIGH_MAX_PAYLOAD
    }

    fun mode(): AdaptiveBandwidth.BandwidthMode = adaptive.getMode()

    fun shouldDeferLowPriority(): Boolean = adaptive.shouldDeferLowPriority()

    fun shouldUsePrediction(): Boolean = adaptive.shouldUsePrediction()

    /**
     * Feed one measured [LinkMetrics] sample into the estimator. Loss and the
     * retransmission rate are expressed as fractions of the observed traffic
     * totals, with a 100-packet resolution floor: below 100 observed packets
     * the counters are small integers, so a tiny sample is treated as a
     * per-cent-of-100 estimate rather than being inflated to 100% loss.
     */
    fun observe(metrics: LinkMetrics) {
        val observedTraffic = (metrics.packetsSent +
            metrics.controlPacketsSent +
            metrics.packetsReceived +
            metrics.packetLoss +
            metrics.retransmissions).coerceAtLeast(0)
        val denominator = maxOf(observedTraffic, RESOLUTION_FLOOR)
        adaptive.updatePacketLoss(
            (metrics.packetLoss.toFloat() / denominator).coerceIn(0f, 1f)
        )
        adaptive.updateRTT(metrics.roundTripTimeMs.coerceAtLeast(0))
        adaptive.updateRetransmissionRate(
            (metrics.retransmissions.toFloat() / denominator).coerceIn(0f, 1f)
        )
        adaptive.updateThroughput(measureThroughput(metrics.transmittedBytes))
    }

    /** Bytes-per-second over the interval between two samples, not lifetime totals. */
    private fun measureThroughput(currentBytes: Long): Float {
        val now = System.currentTimeMillis()
        if (lastSampleMs == 0L) {
            lastSampleMs = now
            lastTransmittedBytes = currentBytes
            return 0f
        }
        val dtMs = (now - lastSampleMs).coerceAtLeast(1)
        val deltaBytes = (currentBytes - lastTransmittedBytes).coerceAtLeast(0)
        lastSampleMs = now
        lastTransmittedBytes = currentBytes
        return deltaBytes * 1000f / dtMs
    }

    fun reset() {
        adaptive.reset()
        lastSampleMs = 0L
        lastTransmittedBytes = 0L
    }

    companion object {
        const val EMERGENCY_MAX_PAYLOAD = 256
        const val LOW_MAX_PAYLOAD = 512
        const val NORMAL_MAX_PAYLOAD = 1024
        const val HIGH_MAX_PAYLOAD = 2048

        /** Below this many observed packets, counters read as per-cent-of-100. */
        const val RESOLUTION_FLOOR = 100
    }
}