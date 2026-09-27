package com.example.itantra.protocol

import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Identifier generator for iTantra: Setu communication frames.
 *
 * Produces structured, visually identifiable identifiers:
 * - TRANSMISSION ID: e.g. TX-20260928-A7F31C (Identifies one communication transaction)
 * - MESSAGE ID: e.g. MSG-8F2A91 (Identifies the logical message)
 * - PACKET ID: e.g. PKT-8F2A91-001 (Identifies an individual packet belonging to a message)
 */
object IdGenerator {

    private val random = SecureRandom()
    private val sequenceCounter = AtomicInteger(1)
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    private const val HEX_CHARS = "0123456789ABCDEF"

    private fun randomHex(length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(HEX_CHARS[random.nextInt(HEX_CHARS.length)])
        }
        return sb.toString()
    }

    /**
     * Generates a unique Transmission Identifier.
     * Format: TX-YYYYMMDD-XXXXXX
     * Example: TX-20260928-A7F31C
     */
    fun generateTransmissionId(timestamp: Long = System.currentTimeMillis()): String {
        val dateStr = synchronized(dateFormat) { dateFormat.format(Date(timestamp)) }
        val hex = randomHex(6)
        return "TX-$dateStr-$hex"
    }

    /**
     * Generates a unique Message Identifier.
     * Format: MSG-XXXXXX
     * Example: MSG-8F2A91
     */
    fun generateMessageId(): String {
        return "MSG-${randomHex(6)}"
    }

    /**
     * Generates a Packet Identifier associated with a given [messageId] and [sequenceNumber].
     * Format: PKT-XXXXXX-NNN
     * Example: PKT-8F2A91-001
     */
    fun generatePacketId(messageId: String, sequenceNumber: Int = 1): String {
        val cleanMsgId = messageId.removePrefix("MSG-")
        val seqStr = String.format(Locale.US, "%03d", sequenceNumber)
        return "PKT-$cleanMsgId-$seqStr"
    }

    /**
     * Monotonically increasing sequence number for session packets.
     */
    fun nextSequenceNumber(): Int = sequenceCounter.getAndIncrement()

    fun resetSequence() {
        sequenceCounter.set(1)
    }
}
