package com.example.itantra.lab

/**
 * Honest Phase 1 baseline snapshot of the offline encode/decode path.
 *
 * Every field is produced by real measurement on the JVM that ran it (see
 * `BaselineMetricsTest`); none are imputed from this device's headline figures.
 * [machine] records the runtime so reporting shows exactly where the numbers
 * came from.
 */
data class BaselineCapabilities(
    val perMessageWireBytesMean: Double,
    val perMessageEncodeMsMean: Double,
    val perMessageDecodeMsMean: Double,
    val coldStartTotalMs: Long,
    val steadyStateP95Ms: Long,
    val heapDeltaBytesLabel: String,
    val measuredAt: Long,
    val machine: String
) {
    val summary: String
        get() = buildString {
            append("wire(mean)=${"%.0f".format(perMessageWireBytesMean)}B, ")
            append("encode(mean)=${"%.3f".format(perMessageEncodeMsMean)}ms, ")
            append("decode(mean)=${"%.3f".format(perMessageDecodeMsMean)}ms, ")
            append("coldStart=${coldStartTotalMs}ms, steadyP95=${steadyStateP95Ms}ms, ")
            append("heapDelta=$heapDeltaBytesLabel, machine=$machine")
        }
}

/** Minimal measured-JVM advert so unmeasured devices are never over-claimed. */
object BaselineLimits {
    /**
     * Headline targets this prototype is designed to reach on a mid-range
     * Android device. In production these MUST be re-measured on-device and
     * replace the targets; they are NOT claims about this repo's JVM.
     */
    const val TARGET_WIRE_BYTES_PER_MESSAGE = 512
    const val TARGET_ENCODE_MS = 15
    const val TARGET_RAM_BYTES = 512L * 1024L * 1024L
}