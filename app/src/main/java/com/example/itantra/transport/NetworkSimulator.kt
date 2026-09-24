package com.example.itantra.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.util.Random

/**
 * Debug-only link impairment on top of a real transport. Every number produced
 * under an active simulation is labeled "SIMULATION" in the UI and must never
 * be presented as real-world data.
 */
data class NetworkSimConfig(
    var lossRate: Float = 0f,           // 0..1  (0/5/10/20% in the UI)
    var corruptionRate: Float = 0f,     // 0..1
    var duplicationRate: Float = 0f,    // 0..1
    var latencyMs: Long = 0             // ms extra one-way delay (0/50/100/250)
) {
    val isActive: Boolean
        get() = lossRate > 0f || corruptionRate > 0f || duplicationRate > 0f || latencyMs > 0

    fun label(): String = buildString {
        if (lossRate > 0f) append("LOSS ${(lossRate * 100).toInt()}% ")
        if (corruptionRate > 0f) append("CORRUPT ")
        if (duplicationRate > 0f) append("DUP ")
        if (latencyMs > 0) append("LAT ${latencyMs}ms")
    }.trim()
}

class NetworkSimulator {

    val config = NetworkSimConfig()
    private val random = Random()

    fun reset() {
        config.lossRate = 0f
        config.corruptionRate = 0f
        config.duplicationRate = 0f
        config.latencyMs = 0
    }

    /**
     * Applies the configured impairments to one outgoing frame. Returns a
     * possibly-empty list of frames to actually transmit:
     *  - empty  -> dropped (simulated packet loss)
     *  - size 1 -> passed through (possibly corrupted)
     *  - size 2 -> duplicated
     */
    suspend fun applyOutgoing(frame: ByteArray, eventSink: (() -> Unit)? = null): List<ByteArray> {
        if (!config.isActive) return listOf(frame)
        yield()

        if (config.latencyMs > 0) delay(config.latencyMs)

        if (config.lossRate > 0f && random.nextFloat() < config.lossRate) {
            return emptyList()
        }
        var result = frame
        if (config.corruptionRate > 0f && random.nextFloat() < config.corruptionRate) {
            result = corrupt(frame)
            eventSink?.invoke()
        }
        return if (config.duplicationRate > 0f && random.nextFloat() < config.duplicationRate) {
            listOf(result, result.copyOf())
        } else {
            listOf(result)
        }
    }

    /**
     * Flip bytes inside the payload region (after the 8-byte header, before
     * the trailing CRC) so the frame boundary survives but the CRC check fails.
     */
    private fun corrupt(frame: ByteArray): ByteArray {
        if (frame.size < 16) return frame
        val copy = frame.copyOf()
        val start = 8
        val end = frame.size - CopyMargin
        if (start >= end) return frame
        val index = start + random.nextInt(end - start)
        copy[index] = (copy[index].toInt() xor 0x5A).toByte()
        return copy
    }

    companion object {
        private const val CopyMargin = 4
    }
}