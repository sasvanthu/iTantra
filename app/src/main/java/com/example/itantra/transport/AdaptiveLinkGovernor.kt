package com.example.itantra.transport

import com.example.itantra.data.AdaptiveBandwidth
import com.example.itantra.data.AdaptiveBandwidth.BandwidthMode

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
 * retransmission rate, and where available RSSI / queue depth); nothing here
 * fakes bandwidth numbers.
 *
 * ## Hysteresis
 *
 * The underlying 20-sample estimator avoids jitter, but a single bad sample
 * could still trip a mode change. A committed (displayed) mode only moves to
 * a *new* mode after [hysteresisSamples] consecutive samples leaning toward
 * it. This prevents rapid up/down flapping while remaining configurable.
 *
 * ## Link health
 *
 * [health] projects the four estimator modes onto
 * GOOD / NORMAL / DEGRADED / CRITICAL so callers and UI can act on a single
 * human-meaningful label. [UNKNOWN] is reported until the first sample.
 */
class AdaptiveLinkGovernor(
    private val adaptive: AdaptiveBandwidth = AdaptiveBandwidth(),
    private val hysteresisSamples: Int = DEFAULT_HYSTERESIS_SAMPLES
) {

    /** Semantic link quality projection used for policy and UI. */
    enum class LinkHealth { GOOD, NORMAL, DEGRADED, CRITICAL, UNKNOWN }

    /** One committed mode transition, kept so switches are auditable. */
    data class ModeSwitchEvent(
        val timestampMs: Long,
        val from: BandwidthMode,
        val to: BandwidthMode,
        val reason: String
    )

    /** Throughput is a rate, so it needs the previous sample to be meaningful. */
    private var lastSampleMs = 0L
    private var lastTransmittedBytes = 0L

    // ---- hysteresis state -------------------------------------------------
    private var displayedMode: BandwidthMode = adaptive.getMode()
    private var leanMode: BandwidthMode? = null
    private var leanCount = 0
    private var sampleCount = 0

    private val switchLog = mutableListOf<ModeSwitchEvent>()

    /** Per-mode DATA packet payload adopted when the link changes state. */
    fun maxPayload(): Int = when (displayedMode) {
        BandwidthMode.EMERGENCY -> EMERGENCY_MAX_PAYLOAD
        BandwidthMode.LOW_BANDWIDTH -> LOW_MAX_PAYLOAD
        BandwidthMode.NORMAL -> NORMAL_MAX_PAYLOAD
        BandwidthMode.HIGH_BANDWIDTH -> HIGH_MAX_PAYLOAD
    }

    /** The committed (hysteresis-filtered) mode. */
    fun mode(): BandwidthMode = displayedMode

    /** Human-meaningful link health derived from the committed mode. */
    fun health(): LinkHealth {
        if (!hasSamples()) return LinkHealth.UNKNOWN
        return when (displayedMode) {
            BandwidthMode.HIGH_BANDWIDTH -> LinkHealth.GOOD
            BandwidthMode.NORMAL -> LinkHealth.NORMAL
            BandwidthMode.LOW_BANDWIDTH -> LinkHealth.DEGRADED
            BandwidthMode.EMERGENCY -> LinkHealth.CRITICAL
        }
    }

    fun hasSamples(): Boolean = sampleCount > 0

    fun shouldDeferLowPriority(): Boolean =
        displayedMode == BandwidthMode.LOW_BANDWIDTH || displayedMode == BandwidthMode.EMERGENCY

    fun shouldUsePrediction(): Boolean = displayedMode == BandwidthMode.LOW_BANDWIDTH

    /** Total committed mode switches since construction / [reset]. */
    fun modeSwitchCount(): Int = synchronized(switchLog) { switchLog.size }

    /** Snapshot of logged mode switches (bounded, newest retained). */
    fun recentModeSwitches(): List<ModeSwitchEvent> = synchronized(switchLog) { switchLog.toList() }

    /**
     * Feed one measured [LinkMetrics] sample into the estimator. Loss and the
     * retransmission rate are expressed as fractions of the observed traffic
     * totals, with a 100-packet resolution floor: below 100 observed packets
     * the counters are small integers, so a tiny sample is treated as a
     * per-cent-of-100 estimate rather than being inflated to 100% loss.
     */
    fun observe(metrics: LinkMetrics) {
        sampleCount++
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

        val sensed = adaptive.getMode()
        propose(applyEnvironmentPenalty(sensed, metrics))
    }

    /**
     * Applies a conservativeness FLOOR when measured signal strength is weak or
     * the outbound queue is backing up. Unlike the loss/RTT path this can only
     * ever make the mode MORE conservative, never cheaper: a virgin link in
     * HIGH_BANDWIDTH with a critical radio (-90 dBm or worse) is floored all
     * the way to EMERGENCY, while a merely degraded radio only floors to LOW.
     */
    private fun applyEnvironmentPenalty(sensed: BandwidthMode, metrics: LinkMetrics): BandwidthMode {
        var floor: BandwidthMode? = null
        val rssi = metrics.rssiDbm
        if (rssi != Int.MIN_VALUE) {
            if (rssi < RSSI_CRITICAL_DBM) floor = BandwidthMode.EMERGENCY
            else if (rssi < RSSI_DEGRADED_DBM) floor = BandwidthMode.LOW_BANDWIDTH
        }
        val depth = metrics.queuedDepth
        if (depth >= QUEUE_CRITICAL_DEPTH) floor = BandwidthMode.EMERGENCY
        else if (depth >= QUEUE_DEGRADED_DEPTH && floor != BandwidthMode.EMERGENCY) {
            floor = BandwidthMode.LOW_BANDWIDTH
        }
        if (floor == null) return sensed
        return if (CONSERVATISM.indexOf(floor) > CONSERVATISM.indexOf(sensed)) floor else sensed
    }

    /** Hysteresis gate: only commit a mode after consecutive consistent leans. */
    private fun propose(candidate: BandwidthMode) {
        if (candidate == displayedMode) {
            leanMode = null
            leanCount = 0
            return
        }
        if (leanMode != candidate) {
            leanMode = candidate
            leanCount = 1
            return
        }
        leanCount++
        if (leanCount >= hysteresisSamples.coerceAtLeast(1)) {
            val from = displayedMode
            displayedMode = candidate
            leanMode = null
            leanCount = 0
            recordSwitch(from, candidate)
        }
    }

    private fun recordSwitch(from: BandwidthMode, to: BandwidthMode) {
        val reason = when (to) {
            BandwidthMode.EMERGENCY -> "measured link quality critical"
            BandwidthMode.LOW_BANDWIDTH -> "measured link quality degraded"
            BandwidthMode.NORMAL -> "measured link recovered to nominal"
            BandwidthMode.HIGH_BANDWIDTH -> "measured link recovered to good"
        }
        val evt = ModeSwitchEvent(System.currentTimeMillis(), from, to, reason)
        synchronized(switchLog) {
            switchLog.add(evt)
            if (switchLog.size > MAX_LOG_ENTRIES) switchLog.removeAt(0)
        }
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
        displayedMode = adaptive.getMode()
        leanMode = null
        leanCount = 0
        sampleCount = 0
        lastSampleMs = 0L
        lastTransmittedBytes = 0L
        synchronized(switchLog) { switchLog.clear() }
    }

    companion object {
        const val EMERGENCY_MAX_PAYLOAD = 256
        const val LOW_MAX_PAYLOAD = 512
        const val NORMAL_MAX_PAYLOAD = 1024
        const val HIGH_MAX_PAYLOAD = 2048

        /** Below this many observed packets, counters read as per-cent-of-100. */
        const val RESOLUTION_FLOOR = 100

        /** Samples of a new candidate mode required before the mode commits. */
        const val DEFAULT_HYSTERESIS_SAMPLES = 3

        /** RSSI (dBm) below which the link is treated as degraded. */
        const val RSSI_DEGRADED_DBM = -80
        /** RSSI (dBm) below which the link is treated as critical. */
        const val RSSI_CRITICAL_DBM = -90

        /** Outbound queue depth at which the link is treated as degraded. */
        const val QUEUE_DEGRADED_DEPTH = 32
        /** Outbound queue depth at which the link is treated as critical. */
        const val QUEUE_CRITICAL_DEPTH = 64

        /** Upper bound on retained mode-switch log entries. */
        const val MAX_LOG_ENTRIES = 64

        /** Bandwidth modes ordered from cheapest to most conservative. */
        private val CONSERVATISM = listOf(
            BandwidthMode.HIGH_BANDWIDTH,
            BandwidthMode.NORMAL,
            BandwidthMode.LOW_BANDWIDTH,
            BandwidthMode.EMERGENCY
        )
    }
}