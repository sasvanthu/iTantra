package com.example.itantra.ops

/**
 * Phase 20 — emergency contact book & alert log.
 *
 * An emergency is an auditable, operator-visible event: who/what raised it,
 * how many times it was re-raised while already active, and who acknowledged it
 * and when. Nothing here pretends an alert was delivered on the wire — the
 * transport keeps telling [isActive] what it saw, and the log below records what
 * *this* controller did. Rule of thumb: emergency decodes to `Importance.
 * CRITICAL` frames upstream ([com.example.itantra.codec.Importance]) so a
 * raised alert always travels on the highest-priority path.
 */
class EmergencyController(
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class EmergencyLogEntry(
        val raisedAt: Long,
        val reason: String,
        val reRaises: Int,
        val acknowledgedAt: Long?,
        val acknowledgedBy: String?
    )

    private var active: EmergencyLogEntry? = null

    private val _log = mutableListOf<EmergencyLogEntry>()
    val log: List<EmergencyLogEntry> = _log

    val isActive: Boolean get() = active != null

    val currentReason: String? get() = active?.reason

    /** Raise the emergency, or re-raise it (counted) if already active. */
    fun raise(reason: String) {
        val existing = active
        if (existing == null) {
            val entry = EmergencyLogEntry(
                raisedAt = clock(),
                reason = reason,
                reRaises = 0,
                acknowledgedAt = null,
                acknowledgedBy = null
            )
            active = entry
            _log.add(entry)
        } else {
            active = existing.copy(reason = reason, reRaises = existing.reRaises + 1)
            _log[_log.lastIndex] = active!!
        }
    }

    /**
     * Acknowledge and dismiss the active alert. Accepts only a non-blank
     * [by]; refuses dismissals from nobody so the log stays trustworthy.
     */
    fun acknowledge(by: String): Boolean {
        val existing = active ?: return false
        if (by.isBlank()) return false
        val closed = existing.copy(acknowledgedAt = clock(), acknowledgedBy = by)
        active = null
        _log[_log.lastIndex] = closed
        return true
    }

    /** Historical alerts can still be read after they are closed. */
    fun countClosed(): Int = _log.count { it.acknowledgedAt != null }
}