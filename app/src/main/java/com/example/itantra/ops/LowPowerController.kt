package com.example.itantra.ops

import com.example.itantra.codec.Importance

/**
 * Phase 24 — low-power governor.
 *
 * When the battery is running out the device must not pretend it runs at full
 * capacity. [LowPowerController] derives a [PowerProfile] from the real battery
 * figure the host injects ([batteryPercentProvider], e.g. the OS battery level),
 * then answers honest capability questions:
 *
 *  - [canTransmit]: only emergency/CRITICAL traffic is guaranteed at ANY level —
 *    below CRITICAL_THRESHOLD even normal, non-urgent traffic can be refused so
 *    the last joules stay reserved for the emergency path.
 *  - [deferFullQualityExtras]: energy-hungry extras (preview rendering, eager
 *    retransmission) are deferred on LOW and dropped on CRITICAL profiles.
 *
 * The controller never invents a battery number: it only reasons about what the
 * injector told it, so "14%" means the OS said 14%.
 */
class LowPowerController(
    private val batteryPercentProvider: () -> Int
) {

    enum class PowerProfile { HEALTHY, LOW, CRITICAL }

    companion object {
        const val LOW_THRESHOLD = 20
        const val CRITICAL_THRESHOLD = 10
    }

    data class ProfileChange(val from: PowerProfile, val to: PowerProfile)

    private var lastProfiled: PowerProfile? = null

    val observations = mutableListOf<Int>()

    val profile: PowerProfile
        get() {
            val percent = batteryPercentProvider().coerceIn(0, 100)
            observations.add(percent)
            val p = when {
                percent <= CRITICAL_THRESHOLD -> PowerProfile.CRITICAL
                percent <= LOW_THRESHOLD -> PowerProfile.LOW
                else -> PowerProfile.HEALTHY
            }
            val previous = lastProfiled
            lastProfiled = p
            if (previous != null && previous != p) {
                changes.add(ProfileChange(previous, p))
            }
            return p
        }

    val changes = mutableListOf<ProfileChange>()

    /** May this traffic class go out at the current battery level? */
    fun canTransmit(importance: Importance): Boolean =
        when (profile) {
            PowerProfile.HEALTHY -> true
            PowerProfile.LOW -> importance.orBetterThan(Importance.NORMAL)
            PowerProfile.CRITICAL -> importance == Importance.CRITICAL
        }

    /** True when full-quality extras should be held back to save power. */
    fun deferFullQualityExtras(): Boolean =
        profile != PowerProfile.HEALTHY
}

private fun Importance.orBetterThan(other: Importance): Boolean = level >= other.level