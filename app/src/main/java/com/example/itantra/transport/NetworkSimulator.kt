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
    var lossRate: Float = 0f,           // 0..1  (0/5/10/20/30% in the UI)
    var corruptionRate: Float = 0f,     // 0..1
    var duplicationRate: Float = 0f,    // 0..1
    var latencyMs: Long = 0,            // ms extra one-way delay (0/50/100/250/500)
    var reorderRate: Float = 0f         // 0..1 probability a frame is delayed one position
) {
    val isActive: Boolean
        get() = lossRate > 0f || corruptionRate > 0f || duplicationRate > 0f
            || latencyMs > 0 || reorderRate > 0f

    fun label(): String = buildString {
        if (lossRate > 0f) append("LOSS ${(lossRate * 100).toInt()}% ")
        if (corruptionRate > 0f) append("CORRUPT ")
        if (duplicationRate > 0f) append("DUP ")
        if (latencyMs > 0) append("LAT ${latencyMs}ms ")
        if (reorderRate > 0f) append("REORDER ${(reorderRate * 100).toInt()}%")
    }.trim()
}

/**
 * Applies simulated reordering by holding one frame and releasing it AFTER the
 * following frame, so the next frame overtakes it (order A,B,C becomes
 * B,A,C). Retention is exactly one frame: consecutive reorder events overwrite
 * the hold, which is equivalent to dropping the older frame under simulated
 * stress — exactly like [NetworkSimulator]'s loss/duplication impairments.
 *
 * The reliable base transport tolerates this at the packet layer via ACK /
 * reassembly; BLE GATT itself is strictly ordered, so this knob only models
 * degraded intermediaries (Wi-Fi/TCP or mesh hops).
 */
class NetworkSimulator(
    seed: Long? = null
) {
    val config = NetworkSimConfig()
    private val random = if (seed != null) Random(seed) else Random()

    /** Frame delayed one position while [NetworkSimConfig.reorderRate] fires. */
    private var held: ByteArray? = null

    fun reset() {
        config.lossRate = 0f
        config.corruptionRate = 0f
        config.duplicationRate = 0f
        config.latencyMs = 0
        config.reorderRate = 0f
        held = null
    }

    /**
     * Applies the configured impairments to one outgoing frame. Returns a
     * possibly-empty list of frames to actually transmit:
     *  - empty  -> dropped (simulated packet loss), or held for reordering
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

        if (config.reorderRate > 0f && random.nextFloat() < config.reorderRate) {
            // Hold this frame: it is emitted after the NEXT frame.
            held = result
            return emptyList()
        }
        val prevHeld = held
        held = null
        if (prevHeld != null) {
            // Previous frame was delayed: this frame overtakes it.
            return emitWithDuplication(prevHeld, result)
        }
        return emitWithDuplication(result)
    }

    private fun emitWithDuplication(vararg frames: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(frames.size * 2)
        for (frame in frames) {
            out += frame
            if (config.duplicationRate > 0f && random.nextFloat() < config.duplicationRate) {
                out += frame.copyOf()
            }
        }
        return out
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