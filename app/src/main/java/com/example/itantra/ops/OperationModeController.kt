package com.example.itantra.ops

/**
 * Phase 19 — operation modes.
 *
 * The device's behaviour is governed by exactly one mode at a time. The model
 * stays transport-agnostic: it decides *what the operator may do with audio and
 * transmissions*, never the wire format.
 *
 * Rules (all lawful transitions are logged in [history]):
 *  - EMERGENCY overrides any other mode and latches: it can only be left via
 *    the explicit [OperationModeController.acknowledgeEmergency] path. This is
 *    the safety latch — the alert is NOT silent-able or flickable away.
 *  - SILENT suppresses both capture and playback.
 *  - PTT restricts transmission to the push-to-talk gate while leaving playback
 *    enabled, so an operator can listen while keying the mic.
 *  - NORMAL is unconstrained.
 */
enum class OperationMode {
    /** Full audio + transmission. */
    NORMAL,

    /** No audio capture, no playback. Display only. */
    SILENT,

    /** Transmit only while the PTT gate is held; playback still allowed. */
    PTT,

    /** Latched alert state; raised by [OperationModeController.enterEmergency]. */
    EMERGENCY
}

class OperationModeController(
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class ModeTransition(
        val from: OperationMode,
        val to: OperationMode,
        val at: Long,
        val cause: String
    )

    private var current: OperationMode = OperationMode.NORMAL

    private val _history = mutableListOf<ModeTransition>()
    val history: List<ModeTransition> = _history

    val mode: OperationMode get() = current

    /** Audio capture (mic) is permitted in this mode. */
    val isCaptureAllowed: Boolean
        get() = current != OperationMode.SILENT

    /** Playback (result audio/TTS) is permitted in this mode. */
    val isPlaybackAllowed: Boolean
        get() = current != OperationMode.SILENT

    /** Transmission is permitted without a PTT gate. */
    val isTransmissionFree: Boolean
        get() = current == OperationMode.NORMAL

    val isEmergency: Boolean
        get() = current == OperationMode.EMERGENCY

    /**
     * Request a mode change. Lawfulness is decided here and every accepted or
     * rejected attempt is recorded so the operator can audit what the device
     * actually did and why.
     */
    fun switchTo(target: OperationMode, cause: String): OperationMode {
        val from = current
        val allowed = from == target ||
            (from == OperationMode.EMERGENCY && target == OperationMode.NORMAL) ||
            (from != OperationMode.EMERGENCY &&
                target in setOf(
                    OperationMode.NORMAL,
                    OperationMode.SILENT,
                    OperationMode.PTT,
                    OperationMode.EMERGENCY
                ))
        if (!allowed) {
            _history.add(
                ModeTransition(from, target, clock(), "REJECTED: $cause (EMERGENCY is latched)")
            )
            return from
        }
        current = target
        _history.add(ModeTransition(from, target, clock(), cause))
        return target
    }

    /** The valid way to leave EMERGENCY: explicit acknowledgement. */
    fun acknowledgeEmergency(by: String): OperationMode {
        if (current != OperationMode.EMERGENCY) return current
        return switchTo(OperationMode.NORMAL, "acknowledged by $by")
    }
}