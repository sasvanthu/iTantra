package com.example.itantra.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


/**
 * Standard disaster response domains for semantic ultra-compact transmission.
 */
enum class SutraDomain(val id: Byte) {
    SOS(0x01),
    MEDICAL(0x02),
    LOCATION(0x03),
    SUPPLY(0x04),
    EVACUATION(0x05),
    DANGER(0x06);

    companion object {
        fun fromId(id: Byte): SutraDomain = entries.firstOrNull { it.id == id } ?: SOS
    }
}

/**
 * Semantic intents per domain.
 */
enum class SutraIntent(val id: Byte, val domain: SutraDomain) {
    // SOS
    TRAPPED(0x01, SutraDomain.SOS),
    INJURED(0x02, SutraDomain.SOS),
    IMMEDIATE_ASSISTANCE(0x03, SutraDomain.SOS),
    LIFE_THREAT(0x04, SutraDomain.SOS),

    // MEDICAL
    FIRST_AID(0x10, SutraDomain.MEDICAL),
    OXYGEN_NEEDED(0x11, SutraDomain.MEDICAL),
    BLOOD_LOSS(0x12, SutraDomain.MEDICAL),
    MEDICATION(0x13, SutraDomain.MEDICAL),
    FRACTURE(0x14, SutraDomain.MEDICAL),

    // LOCATION
    GPS_COORDINATES(0x20, SutraDomain.LOCATION),
    LANDMARK(0x21, SutraDomain.LOCATION),
    SECTOR_FLOOR(0x22, SutraDomain.LOCATION),

    // SUPPLY
    DRINKING_WATER(0x30, SutraDomain.SUPPLY),
    FOOD_RATIONS(0x31, SutraDomain.SUPPLY),
    BLANKETS_SHELTER(0x32, SutraDomain.SUPPLY),
    POWER_BATTERY(0x33, SutraDomain.SUPPLY),

    // EVACUATION
    ROUTE_CLEAR(0x40, SutraDomain.EVACUATION),
    SHELTER_REACHED(0x41, SutraDomain.EVACUATION),
    STRANDED(0x42, SutraDomain.EVACUATION),
    BOAT_NEEDED(0x43, SutraDomain.EVACUATION),

    // DANGER
    FLOOD_RISING(0x50, SutraDomain.DANGER),
    FIRE_EXPLOSION(0x51, SutraDomain.DANGER),
    GAS_LEAK(0x52, SutraDomain.DANGER),
    BUILDING_COLLAPSE(0x53, SutraDomain.DANGER),
    LIVE_ELECTRIC_WIRE(0x54, SutraDomain.DANGER);

    companion object {
        fun fromId(id: Byte): SutraIntent = entries.firstOrNull { it.id == id } ?: IMMEDIATE_ASSISTANCE
    }
}

/**
 * Semantic confidence and operational flags.
 */
object SutraFlags {
    const val FLAG_NONE: Byte = 0x00
    const val FLAG_CRITICAL: Byte = 0x01.toByte()     // Urgent, high priority (P0)
    const val FLAG_UNCERTAIN: Byte = 0x02.toByte()    // Low confidence inference (<0.70)
    const val FLAG_NEGATION: Byte = 0x04.toByte()     // Semantic NOT (e.g., "no water", "not safe")
    const val FLAG_URGENT: Byte = 0x08.toByte()       // Immediate action required (<30 min)
}

/**
 * Pre-quantized slot keys for key-value extraction.
 */
enum class SutraSlotKey(val id: Byte) {
    PEOPLE_COUNT(0x01),
    URGENCY_LEVEL(0x02),
    LATITUDE_E5(0x03),
    LONGITUDE_E5(0x04),
    QUANTITY(0x05),
    STATUS_CODE(0x06),
    TEXT_NOTE(0x07);

    companion object {
        fun fromId(id: Byte): SutraSlotKey = entries.firstOrNull { it.id == id } ?: TEXT_NOTE
    }
}

/**
 * Strongly typed slot value representing primitive measurements or short text.
 */
sealed class SutraSlotValue {
    data class IntVal(val value: Int) : SutraSlotValue()
    data class CoordVal(val e5: Int) : SutraSlotValue() {
        val degrees: Double get() = e5 / 100000.0
    }
    data class TextVal(val text: String) : SutraSlotValue()
}

/**
 * SUTRA (Semantic Ultra-compact Transmission for Resilient Action) frame.
 * Packs intent, confidence, negation, urgency and slots into 5 to 14 bytes.
 */
data class SutraFrame(
    val domain: SutraDomain,
    val intent: SutraIntent,
    val flags: Byte = SutraFlags.FLAG_NONE,
    val confidence: Int = 100, // 0..100%
    val slots: Map<SutraSlotKey, SutraSlotValue> = emptyMap()
) {
    val isCritical: Boolean get() = (flags.toInt() and SutraFlags.FLAG_CRITICAL.toInt()) != 0
    val isUncertain: Boolean get() = (flags.toInt() and SutraFlags.FLAG_UNCERTAIN.toInt()) != 0
    val isNegated: Boolean get() = (flags.toInt() and SutraFlags.FLAG_NEGATION.toInt()) != 0
    val isUrgent: Boolean get() = (flags.toInt() and SutraFlags.FLAG_URGENT.toInt()) != 0

    companion object {
        const val SUTRA_MAGIC: Byte = 0x53 // 'S'

        /**
         * Serializes a [SutraFrame] into ultra-compact binary wire bytes.
         */
        fun serialize(frame: SutraFrame): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(SUTRA_MAGIC.toInt())
            out.write(frame.domain.id.toInt())
            out.write(frame.intent.id.toInt())
            out.write(frame.flags.toInt())
            out.write(frame.confidence.coerceIn(0, 100))
            out.write(frame.slots.size.coerceAtMost(15))

            val bb4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            for ((key, value) in frame.slots) {
                out.write(key.id.toInt())
                when (value) {
                    is SutraSlotValue.IntVal -> {
                        out.write(0x01) // Int type tag
                        writeVarint(out, value.value)
                    }
                    is SutraSlotValue.CoordVal -> {
                        out.write(0x02) // Coord type tag
                        bb4.clear()
                        bb4.putInt(value.e5)
                        out.write(bb4.array())
                    }
                    is SutraSlotValue.TextVal -> {
                        out.write(0x03) // Text type tag
                        val textBytes = BrahmicCodec.encode(value.text)
                        writeVarint(out, textBytes.size)
                        out.write(textBytes)
                    }
                }
            }
            return out.toByteArray()
        }

        /**
         * Deserializes a [SutraFrame] from binary wire bytes.
         */
        fun deserialize(bytes: ByteArray): SutraFrame? {
            if (bytes.size < 6) return null
            val input = ByteArrayInputStream(bytes)

            val magic = input.read().toByte()
            if (magic != SUTRA_MAGIC) return null

            val domainId = input.read().toByte()
            val intentId = input.read().toByte()
            val flags = input.read().toByte()
            val confidence = input.read()
            val slotCount = input.read()

            val domain = SutraDomain.fromId(domainId)
            val intent = SutraIntent.fromId(intentId)
            val slots = mutableMapOf<SutraSlotKey, SutraSlotValue>()

            val bb4 = ByteArray(4)
            for (i in 0 until slotCount) {
                if (input.available() <= 0) break
                val keyId = input.read().toByte()
                val typeTag = input.read()
                val key = SutraSlotKey.fromId(keyId)

                val value: SutraSlotValue = when (typeTag) {
                    0x01 -> {
                        val num = readVarint(input)
                        SutraSlotValue.IntVal(num)
                    }
                    0x02 -> {
                        val readLen = input.read(bb4)
                        if (readLen == 4) {
                            val e5 = ByteBuffer.wrap(bb4).order(ByteOrder.LITTLE_ENDIAN).int
                            SutraSlotValue.CoordVal(e5)
                        } else {
                            SutraSlotValue.CoordVal(0)
                        }
                    }
                    0x03 -> {
                        val textLen = readVarint(input)
                        val textBuf = ByteArray(textLen)
                        input.read(textBuf)
                        val str = BrahmicCodec.decode(textBuf) ?: ""
                        SutraSlotValue.TextVal(str)
                    }
                    else -> SutraSlotValue.IntVal(0)
                }
                slots[key] = value
            }

            return SutraFrame(
                domain = domain,
                intent = intent,
                flags = flags,
                confidence = confidence,
                slots = slots
            )
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while ((v and 0x7F.inv()) != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v and 0x7F)
        }

        private fun readVarint(input: ByteArrayInputStream): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
                if (shift >= 35) break
            }
            return result
        }

        /**
         * Reconstructs human-readable natural language message from a [SutraFrame].
         */
        fun toHumanReadable(frame: SutraFrame, language: Language = Language.ENGLISH): String {
            val prefix = buildString {
                if (frame.isCritical) append("[CRITICAL] ")
                if (frame.isUrgent) append("[URGENT] ")
                if (frame.isUncertain) append("[UNCERTAIN] ")
                if (frame.isNegated) append("[NO/NEGATIVE] ")
            }

            val slotDetails = frame.slots.entries.joinToString(", ") { (k, v) ->
                when (k) {
                    SutraSlotKey.PEOPLE_COUNT -> "People: ${(v as SutraSlotValue.IntVal).value}"
                    SutraSlotKey.URGENCY_LEVEL -> "Urgency: ${(v as SutraSlotValue.IntVal).value}"
                    SutraSlotKey.QUANTITY -> "Qty: ${(v as SutraSlotValue.IntVal).value}"
                    SutraSlotKey.LATITUDE_E5 -> "Lat: ${(v as SutraSlotValue.CoordVal).degrees}"
                    SutraSlotKey.LONGITUDE_E5 -> "Lon: ${(v as SutraSlotValue.CoordVal).degrees}"
                    SutraSlotKey.TEXT_NOTE -> (v as SutraSlotValue.TextVal).text
                    else -> "$k=$v"
                }
            }

            val mainText = "${frame.domain.name}: ${frame.intent.name.replace('_', ' ')}"
            return if (slotDetails.isNotEmpty()) "$prefix$mainText ($slotDetails)" else "$prefix$mainText"
        }
    }
}
