package com.example.itantra.metrics

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class MetricsEngine {

    data class LatencyMetrics(
        var sttLatencyMs: Long = 0,
        var encodingLatencyMs: Long = 0,
        var packetizationLatencyMs: Long = 0,
        var transportLatencyMs: Long = 0,
        var decodingLatencyMs: Long = 0,
        var ttsLatencyMs: Long = 0,
        var totalLatencyMs: Long = 0,
        var networkReceiveLatencyMs: Long = 0,
        var ackLatencyMs: Long = 0,
        var roundTripTimeMs: Long = 0
    )

    data class SizeMetrics(
        var originalUtf8Bytes: Int = 0,
        var tokenEncodedBytes: Int = 0,
        var phonemeEncodedBytes: Int = 0,
        var finalEncodedBytes: Int = 0,
        var compressionPercentage: Double = 0.0
    )

    data class TransportMetrics(
        var packetsSent: Int = 0,
        var packetsReceived: Int = 0,
        var retransmissions: Int = 0,
        var packetLoss: Int = 0,
        var throughput: Double = 0.0,
        var transmittedBytes: Long = 0,
        var receivedBytes: Long = 0,
        var totalPacketBytes: Int = 0,
        var duplicatePackets: Int = 0,
        var corruptedFrames: Int = 0,
        var sessionCount: Int = 0
    )

    data class SystemMetrics(
        var cpuUsage: Float = 0f,
        var memoryUsageMB: Float = 0f,
        var heapUsedMB: Float = 0f,
        var heapMaxMB: Float = 0f
    )

    data class FullMetrics(
        val latency: LatencyMetrics,
        val size: SizeMetrics,
        val transport: TransportMetrics,
        val system: SystemMetrics,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val latency = LatencyMetrics()
    private val sizeMetrics = SizeMetrics()
    private val transportMetrics = TransportMetrics()

    private val metricsHistory = ConcurrentLinkedQueue<FullMetrics>()

    private val totalSttLatency = AtomicLong(0)
    private val totalEncodingLatency = AtomicLong(0)
    private val totalDecodingLatency = AtomicLong(0)
    private val totalTtsLatency = AtomicLong(0)
    private val measurementCount = AtomicLong(0)

    fun recordSTTLatency(ms: Long) {
        latency.sttLatencyMs = ms
        totalSttLatency.addAndGet(ms)
    }

    fun recordEncodingLatency(ms: Long) {
        latency.encodingLatencyMs = ms
        totalEncodingLatency.addAndGet(ms)
    }

    fun recordPacketizationLatency(ms: Long) {
        latency.packetizationLatencyMs = ms
    }

    fun recordTransportLatency(ms: Long) {
        latency.transportLatencyMs = ms
    }

    fun recordDecodingLatency(ms: Long) {
        latency.decodingLatencyMs = ms
        totalDecodingLatency.addAndGet(ms)
    }

    fun recordTTSLatency(ms: Long) {
        latency.ttsLatencyMs = ms
        totalTtsLatency.addAndGet(ms)
    }

    fun recordTotalLatency(ms: Long) {
        latency.totalLatencyMs = ms
    }

    fun recordNetworkReceiveLatency(ms: Long) {
        latency.networkReceiveLatencyMs = ms
    }

    fun recordAckLatency(ms: Long) {
        latency.ackLatencyMs = ms
    }

    fun recordRoundTripTime(ms: Long) {
        latency.roundTripTimeMs = ms
    }

    fun recordSizeMetrics(
        originalUtf8: Int,
        tokenEncoded: Int,
        phonemeEncoded: Int,
        finalEncoded: Int
    ) {
        sizeMetrics.originalUtf8Bytes = originalUtf8
        sizeMetrics.tokenEncodedBytes = tokenEncoded
        sizeMetrics.phonemeEncodedBytes = phonemeEncoded
        sizeMetrics.finalEncodedBytes = finalEncoded
        sizeMetrics.compressionPercentage = if (originalUtf8 > 0) {
            100.0 * (1.0 - finalEncoded.toDouble() / originalUtf8.toDouble())
        } else 0.0
    }

    fun recordPacketSent() {
        transportMetrics.packetsSent++
    }

    fun recordPacketReceived() {
        transportMetrics.packetsReceived++
    }

    fun recordRetransmission() {
        transportMetrics.retransmissions++
    }

    fun recordPacketLoss() {
        transportMetrics.packetLoss++
    }

    fun recordTransmittedBytes(bytes: Long) {
        transportMetrics.transmittedBytes += bytes
    }

    fun recordReceivedBytes(bytes: Long) {
        transportMetrics.receivedBytes += bytes
    }

    fun recordTotalPacketBytes(bytes: Int) {
        transportMetrics.totalPacketBytes = bytes
    }

    fun recordDuplicatePacket() {
        transportMetrics.duplicatePackets++
    }

    fun recordCorruptedFrame() {
        transportMetrics.corruptedFrames++
    }

    fun recordSession() {
        transportMetrics.sessionCount++
    }

    /**
     * Mirror the transport layer's own live session counters (packets, bytes,
     * loss, retransmissions, duplicates, corruption) so the dashboard metrics
     * reflect the real wire, not hard-coded placeholders.
     */
    fun syncTransport(
        packetsSent: Int,
        packetsReceived: Int,
        retransmissions: Int,
        packetLoss: Int,
        transmittedBytes: Long,
        receivedBytes: Long,
        duplicatePackets: Int,
        corruptedFrames: Int
    ) {
        transportMetrics.packetsSent = packetsSent
        transportMetrics.packetsReceived = packetsReceived
        transportMetrics.retransmissions = retransmissions
        transportMetrics.packetLoss = packetLoss
        transportMetrics.transmittedBytes = transmittedBytes
        transportMetrics.receivedBytes = receivedBytes
        transportMetrics.duplicatePackets = duplicatePackets
        transportMetrics.corruptedFrames = corruptedFrames
    }

    fun getSystemMetrics(): SystemMetrics {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()).toFloat() / (1024f * 1024f)
        val maxMemory = runtime.maxMemory().toFloat() / (1024f * 1024f)

        return SystemMetrics(
            cpuUsage = 0f,
            memoryUsageMB = usedMemory,
            heapUsedMB = usedMemory,
            heapMaxMB = maxMemory
        )
    }

    fun snapshot(): FullMetrics {
        measurementCount.incrementAndGet()
        val metrics = FullMetrics(
            latency = latency.copy(),
            size = sizeMetrics.copy(),
            transport = transportMetrics.copy(),
            system = getSystemMetrics()
        )
        metricsHistory.add(metrics)
        return metrics
    }

    fun getAverageLatency(): LatencyMetrics {
        val count = measurementCount.get()
        if (count == 0L) return LatencyMetrics()
        return LatencyMetrics(
            sttLatencyMs = totalSttLatency.get() / count,
            encodingLatencyMs = totalEncodingLatency.get() / count,
            decodingLatencyMs = totalDecodingLatency.get() / count,
            ttsLatencyMs = totalTtsLatency.get() / count
        )
    }

    fun getHistory(): List<FullMetrics> = metricsHistory.toList()

    fun reset() {
        latency.sttLatencyMs = 0
        latency.encodingLatencyMs = 0
        latency.packetizationLatencyMs = 0
        latency.transportLatencyMs = 0
        latency.decodingLatencyMs = 0
        latency.ttsLatencyMs = 0
        latency.totalLatencyMs = 0

        sizeMetrics.originalUtf8Bytes = 0
        sizeMetrics.tokenEncodedBytes = 0
        sizeMetrics.phonemeEncodedBytes = 0
        sizeMetrics.finalEncodedBytes = 0
        sizeMetrics.compressionPercentage = 0.0

        transportMetrics.packetsSent = 0
        transportMetrics.packetsReceived = 0
        transportMetrics.retransmissions = 0
        transportMetrics.packetLoss = 0
        transportMetrics.transmittedBytes = 0
        transportMetrics.receivedBytes = 0
        transportMetrics.totalPacketBytes = 0
        transportMetrics.duplicatePackets = 0
        transportMetrics.corruptedFrames = 0
    }
}
