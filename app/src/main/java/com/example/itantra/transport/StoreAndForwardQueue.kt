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
    val priority: Byte,
    val id: String = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8)
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
 * Bounded, priority-aware, persistent DTN retention for messages whose destination is
 * temporarily unavailable (Phase 10 & 11).
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
 *  - [storageDir] optional disk directory backing the queue. If provided, all
 *    messages are written to disk and restored across app restarts and power cycles.
 */
class StoreAndForwardQueue(
    maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    maxMessageAgeMs: Long = DEFAULT_MAX_MESSAGE_AGE_MS,
    maxQueueBytes: Long = DEFAULT_MAX_QUEUE_BYTES,
    maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    val storageDir: java.io.File? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val DEFAULT_MAX_QUEUE_SIZE = 64
        const val DEFAULT_MAX_MESSAGE_AGE_MS = 30L * 60L * 1000L // 30 minutes
        /** Bounded disk/byte budget for the whole persistent queue (4 MiB). */
        const val DEFAULT_MAX_QUEUE_BYTES = 4L * 1024L * 1024L
        /** Upper bound for a single retained message (256 KiB). */
        const val DEFAULT_MAX_MESSAGE_BYTES = 256 * 1024
        const val DTN_MAGIC = 0x44544E31 // "DTN1"
    }

    @Volatile
    var maxQueueSize: Int = maxQueueSize
        set(value) {
            field = value.coerceAtLeast(1)
        }

    @Volatile
    var maxMessageAgeMs: Long = maxMessageAgeMs
        set(value) {
            field = value.coerceAtLeast(1L)
        }

    @Volatile
    var maxQueueBytes: Long = maxQueueBytes
        set(value) {
            field = value.coerceAtLeast(1L)
        }

    @Volatile
    var maxMessageBytes: Int = maxMessageBytes
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private val queue = mutableListOf<QueuedMessage>()
    /** Total retained payload bytes (bounded by [maxQueueBytes]). */
    private var queuedBytes = 0L
    private val _stats = MutableStateFlow(StoreForwardStats())
    val stats: StateFlow<StoreForwardStats> = _stats.asStateFlow()

    val size: Int get() = synchronized(queue) { queue.size }

    init {
        storageDir?.mkdirs()
        loadPersistedMessages()
    }

    private fun loadPersistedMessages() {
        val dir = storageDir ?: return
        if (!dir.exists() || !dir.isDirectory) return

        val files = dir.listFiles { _, name -> name.startsWith("dtn_") && name.endsWith(".bin") } ?: return
        val now = nowMillis()
        synchronized(queue) {
            for (file in files) {
                try {
                    val bytes = file.readBytes()
                    if (bytes.size < 19) {
                        file.delete()
                        continue
                    }
                    val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val magic = bb.int
                    if (magic != DTN_MAGIC) {
                        file.delete()
                        continue
                    }
                    val timestamp = bb.long
                    val langByte = bb.get()
                    val emergencyByte = bb.get()
                    val priorityByte = bb.get()
                    val dataLen = bb.int
                    if (dataLen < 0 || bb.remaining() < dataLen) {
                        file.delete()
                        continue
                    }
                    val data = ByteArray(dataLen)
                    bb.get(data)

                    // Check TTL expiry on reboot
                    if (now - timestamp >= maxMessageAgeMs) {
                        file.delete()
                        bump { expired++ }
                        continue
                    }

                    val id = file.name.removePrefix("dtn_").removeSuffix(".bin")
                    val msg = QueuedMessage(
                        enqueueTimeMs = timestamp,
                        data = data,
                        language = Language.fromByte(langByte),
                        isEmergency = emergencyByte == 1.toByte(),
                        priority = priorityByte,
                        id = id
                    )
                    queue.add(msg)
                    queuedBytes += data.size
                    bump { queued++ }
                } catch (_: Throwable) {
                    file.delete()
                }
            }
        }
    }

    private fun saveToDisk(msg: QueuedMessage) {
        val dir = storageDir ?: return
        try {
            val bb = java.nio.ByteBuffer.allocate(19 + msg.data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(DTN_MAGIC)
            bb.putLong(msg.enqueueTimeMs)
            bb.put(msg.language.wireByte)
            bb.put(if (msg.isEmergency) 1.toByte() else 0.toByte())
            bb.put(msg.priority)
            bb.putInt(msg.data.size)
            bb.put(msg.data)

            val targetFile = java.io.File(dir, "dtn_${msg.id}.bin")
            targetFile.writeBytes(bb.array())
        } catch (_: Throwable) {}
    }

    private fun deleteFromDisk(msg: QueuedMessage) {
        val dir = storageDir ?: return
        try {
            java.io.File(dir, "dtn_${msg.id}.bin").delete()
            java.io.File(dir, "dtn_${msg.id}.tmp").delete()
        } catch (_: Throwable) {}
    }

    fun enqueue(
        data: ByteArray,
        language: Language,
        isEmergency: Boolean,
        priority: Byte
    ): StoreAndForwardResult {
        val now = nowMillis()
        val incomingPriority = priorityLevelOf(priority)
        synchronized(queue) {
            expireLocked(now)
            if (data.size > maxMessageBytes) {
                bump { dropped++ }
                return StoreAndForwardResult.Dropped(
                    "message too large (${data.size} bytes, max $maxMessageBytes)"
                )
            }

            // Enforce the aggregate byte budget: displace weaker messages until
            // the arrival fits (bounded by CRITICAL replacement below).
            var madeRoomByBytes = false
            while (queue.isNotEmpty() && queuedBytes + data.size > maxQueueBytes) {
                if (!makeRoomForLocked(incomingPriority)) {
                    bump { dropped++ }
                    return StoreAndForwardResult.Dropped(
                        "byte budget exceeded (${queuedBytes + data.size} > $maxQueueBytes)"
                    )
                }
                madeRoomByBytes = true
            }

            if (queue.size < maxQueueSize) {
                val newMsg = QueuedMessage(now, data.copyOf(), language, isEmergency, priority)
                queue.add(newMsg)
                queuedBytes += data.size
                saveToDisk(newMsg)
                bump { queued++ }
                return StoreAndForwardResult.Accepted(droppedToMakeRoom = madeRoomByBytes)
            }
            // Queue full: displace the weakest message if the arrival is more
            // important; a lower/equal arrival is dropped on the spot.
            val weakest = queue.minWithOrNull(compareBy({ it.priorityLevel }, { it.enqueueTimeMs }))
            return when {
                weakest != null && incomingPriority > weakest.priorityLevel &&
                    weakest.priorityLevel < Importance.CRITICAL.level -> {
                    removeWeakestLocked(weakest)
                    val newMsg = QueuedMessage(now, data.copyOf(), language, isEmergency, priority)
                    queue.add(newMsg)
                    queuedBytes += data.size
                    saveToDisk(newMsg)
                    bump { queued++ }
                    StoreAndForwardResult.Accepted(droppedToMakeRoom = true)
                }
                incomingPriority >= Importance.CRITICAL.level -> {
                    // Bounded even when everything is CRITICAL: oldest replaced.
                    val oldest = queue.minByOrNull { it.enqueueTimeMs }
                    if (oldest != null) {
                        removeWeakestLocked(oldest)
                    }
                    val newMsg = QueuedMessage(now, data.copyOf(), language, isEmergency, priority)
                    queue.add(newMsg)
                    queuedBytes += data.size
                    saveToDisk(newMsg)
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
     * Removes a retained message from the index, byte accounting and disk.
     * Must be called with [queue] held.
     */
    private fun removeWeakestLocked(msg: QueuedMessage) {
        queue.remove(msg)
        queuedBytes -= msg.data.size
        deleteFromDisk(msg)
        bump { dropped++ }
    }

    /**
     * Attempts to free room for an incoming message by displacing the weakest
     * retained message (LOW first, then NORMAL). CRITICAL arrivals may replace
     * the oldest retained message to keep the queue bounded. Returns false when
     * nothing can be displaced.
     */
    private fun makeRoomForLocked(incomingPriority: Int): Boolean {
        val weakest = queue.minWithOrNull(compareBy({ it.priorityLevel }, { it.enqueueTimeMs })) ?: return false
        return when {
            incomingPriority > weakest.priorityLevel &&
                weakest.priorityLevel < Importance.CRITICAL.level -> {
                removeWeakestLocked(weakest)
                true
            }
            incomingPriority >= Importance.CRITICAL.level -> {
                val oldest = queue.minByOrNull { it.enqueueTimeMs }
                if (oldest != null) removeWeakestLocked(oldest)
                true
            }
            else -> false
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
            queuedBytes -= msg.data.size
            deleteFromDisk(msg)
            bump { forwarded++ }
            true
        } else false
    }

    fun clear() {
        synchronized(queue) {
            queue.forEach { deleteFromDisk(it) }
            queue.clear()
            queuedBytes = 0L
        }
    }

    private fun expireLocked(now: Long) {
        val cutoff = now - maxMessageAgeMs
        val it = queue.iterator()
        while (it.hasNext()) {
            val msg = it.next()
            if (msg.enqueueTimeMs < cutoff) {
                it.remove()
                queuedBytes -= msg.data.size
                deleteFromDisk(msg)
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