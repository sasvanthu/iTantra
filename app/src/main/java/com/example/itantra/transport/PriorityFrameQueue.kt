package com.example.itantra.transport

import kotlinx.coroutines.channels.Channel

/**
 * Priority-scheduled outbound frame queue for a RETRO link.
 *
 * Frames are bucketed by importance (0 = LOW ... 3 = CRITICAL, matching
 * [com.example.itantra.codec.Importance]). Consumers drain CRITICAL before
 * HIGH before NORMAL before LOW, so a newly queued emergency frame always
 * overtakes ordinary traffic that is still waiting to be written.
 *
 * Ordering guarantees within a priority bucket are strict FIFO (a message's
 * START / DATA / END frames share the same bucket, keeping their wire
 * order). The queue is bounded only by the buffer each call-site chooses;
 * callers that need back-pressure should wrap it with their own limits.
 *
 * Pure Kotlin so the preemption behaviour is JVM-testable end to end.
 */
class PriorityFrameQueue {

    private val buckets = Array(PRIORITY_LEVELS) { ArrayDeque<ByteArray>() }
    private val signal = Channel<Unit>(Channel.CONFLATED)

    companion object {
        const val PRIORITY_LEVELS = 4
    }

    fun enqueue(frame: ByteArray, priority: Int) {
        synchronized(buckets) {
            buckets[priority.coerceIn(0, PRIORITY_LEVELS - 1)].addLast(frame)
        }
        signal.trySend(Unit)
    }

    val size: Int
        get() = synchronized(buckets) { buckets.sumOf { it.size } }

    /**
     * Returns the next frame to transmit: the head of the highest non-empty
     * priority bucket. Suspends until a frame is available; the conflation of
     * [signal] only drops redundant wake-ups, never frames (the frame is the
     * source of truth, the signal is just a hint to re-check).
     */
    suspend fun take(): ByteArray {
        while (true) {
            synchronized(buckets) {
                for (bucket in PRIORITY_LEVELS - 1 downTo 0) {
                    val head = buckets[bucket].removeFirstOrNull()
                    if (head != null) return head
                }
            }
            signal.receive()
        }
    }
}