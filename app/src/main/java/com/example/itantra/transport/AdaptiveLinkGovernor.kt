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
     * retransmission rate are expressed as fractions of observed traffic.
     */
    fun observe(metrics: LinkMetrics) {
        adaptive.updatePacketLoss(metrics.packetLoss / 100f)
        adaptive.updateRTT(metrics.roundTripTimeMs.coerceAtLeast(0))
        adaptive.updateRetransmissionRate(metrics.retransmissions / 100f)
        adaptive.updateThroughput(
            metrics.transmittedBytes * 1000f / (System.currentTimeMillis() + 1)
        )
    }

    fun reset() {
        adaptive.reset()
    }

    companion object {
        const val EMERGENCY_MAX_PAYLOAD = 256
        const val LOW_MAX_PAYLOAD = 512
        const val NORMAL_MAX_PAYLOAD = 1024
        const val HIGH_MAX_PAYLOAD = 2048
    }
}