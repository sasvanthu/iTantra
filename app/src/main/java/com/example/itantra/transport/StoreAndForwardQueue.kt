package com.example.itantra.transport

import com.example.itantra.codec.Importance
import com.example.itantra.codec.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live store-and-forward accounting for the MESH RELAY / QUEUED panel. */
data class StoreForwardStats(
    var queued: Long = 0,
    var forwarded: Long = 0,
    var expired: Long = 0,
    var dropped: Long = 0
)

/**
 * One application message retained while its destination is unreachable.
 */
class QueuedMessage(
    val enqueueTimeMs: Long,
    val data: ByteArray,
    val language: Language,
    val isEmergency: Boolean,
    val priority: Byte
) {
    val priorityLevel: Int get() = priority.toInt().coerceIn(Importance.LOW.level, Importance.CRITICAL.level)
}

sealed class StoreAndForwardResult {
    /** The message is retained. [droppedToMakeRoom] names a displaced lower-priority message, if any. */
    data class Accepted(val droppedToMakeRoom: Boolean = false) : StoreAndForwardResult()
    /** The queue was full and the message's own priority was too low to displace anything. */
    data class Dropped(val reason: String) : StoreAndForwardResult()
}

/**
 * Bounded, priority-aware retention for messages whose destination is
 * temporarily unavailable (Phase 11).
 *
 * Policy:
 *  - [maxQueueSize] caps how many outbound messages a relay retains. When
 *    full, the lowest-priority item is displaced by a higher-priority arrival
 *    (LOW first, then NORMAL), recorded as DROPPED — never silently.
 *  - CRITICAL messages never get displaced by lower-priority traffic; once the
 *    queue is entirely CRITICAL it is bounded by replacing the oldest retained
 *    message so memory stays finite (recorded as DROPPED+REPLACED).
 *  - [maxMessageAgeMs] expires stale retention; expired entries are counted
 *    as EXPIRED, never delivered half-way.
 *
 * Both policy values may be retuned live; the counters are cumulative for the
 * queue lifetime. Pure Kotlin so retention/expiry/drop rules are JVM-testable.
 */
class StoreAndForwardQueue(
    maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    maxMessageAgeMs: Long = DEFAULT_MAX_MESSAGE_AGE_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val DEFAULT_MAX_QUEUE_SIZE = 64
        const val DEFAULT_MAX_MESSAGE_AGE_MS = 30L * 60L * 1000L // 30 minutes
    }

    @Volatile
    var maxQueueSize: Int = maxQueueSize
        set(value) {
            field = value.coerceAtLeast(1)
        }

    @Volatile
    var maxMessageAgeMs: Long = maxMessageAgeMs
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private val queue = mutableListOf<QueuedMessage>()
    private val _stats = MutableStateFlow(StoreForwardStats())
    val stats: StateFlow<StoreForwardStats> = _stats.asStateFlow()

    val size: Int get() = synchronized(queue) { queue.size }

    fun enqueue(
        data: ByteArray,
        language: Language,
        isEmergency: Boolean,
        priority: Byte
    ): StoreAndForwardResult {
        val now = nowMillis()
        synchronized(queue) {
            expireLocked(now)
            if (queue.size < maxQueueSize) {
                queue.add(QueuedMessage(now, data.copyOf(), language, isEmergency, priority))
                bump { queued++ }
                return StoreAndForwardResult.Accepted()
            }
            // Queue full: displace the weakest message if the arrival is more
            // important; a lower/equal arrival is dropped on the spot.
            val weakest = queue.minWithOrNull(compareBy({ it.priorityLevel }, { it.enqueueTimeMs }))
            return when {
                weakest != null && priorityLevelOf(priority) > weakest.priorityLevel &&
                    weakest.priorityLevel < Importance.CRITICAL.level -> {
                    queue.remove(weakest)
                    bump { dropped++ }
                    queue.add(QueuedMessage(now, data.copyOf(), language, isEmergency, priority))
                    bump { queued++ }
                    StoreAndForwardResult.Accepted(droppedToMakeRoom = true)
                }
                priorityLevelOf(priority) >= Importance.CRITICAL.level -> {
                    // Bounded even when everything is CRITICAL: oldest replaced.
                    val oldest = queue.minByOrNull { it.enqueueTimeMs }
                    if (oldest != null) {
                        queue.remove(oldest)
                        bump { dropped++ }
                    }
                    queue.add(QueuedMessage(now, data.copyOf(), language, isEmergency, priority))
                    bump { queued++ }
                    StoreAndForwardResult.Accepted(droppedToMakeRoom = true)
                }
                else -> {
                    bump { dropped++ }
                    StoreAndForwardResult.Dropped(
                        "queue full (${queue.size}/$maxQueueSize), message priority too low"
                    )
                }
            }
        }
    }

    /**
     * Highest-priority retained message without removing it (the caller only
     * removes once it has really been handed to an edge). Oldest-first within
     * equal priority so retention order is stable.
     */
    fun peekNext(): QueuedMessage? = synchronized(queue) {
        expireLocked(nowMillis())
        queue.minWithOrNull(
            compareByDescending<QueuedMessage> { it.priorityLevel }.thenBy { it.enqueueTimeMs }
        )
    }

    /** Confirm successful delivery of the retained message returned by [peekNext]. */
    fun markForwarded(msg: QueuedMessage): Boolean = synchronized(queue) {
        if (queue.remove(msg)) {
            bump { forwarded++ }
            true
        } else false
    }

    fun clear() {
        synchronized(queue) {
            queue.clear()
        }
    }

    private fun expireLocked(now: Long) {
        val cutoff = now - maxMessageAgeMs
        val it = queue.iterator()
        while (it.hasNext()) {
            if (it.next().enqueueTimeMs < cutoff) {
                it.remove()
                bump { expired++ }
            }
        }
    }

    private fun priorityLevelOf(priority: Byte): Int =
        priority.toInt().coerceIn(Importance.LOW.level, Importance.CRITICAL.level)

    private fun bump(block: StoreForwardStats.() -> Unit) {
        _stats.value = _stats.value.copy().apply(block)
    }
}