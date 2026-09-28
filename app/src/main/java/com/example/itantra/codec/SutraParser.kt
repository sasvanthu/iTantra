package com.example.itantra.codec

import java.util.regex.Pattern

/**
 * Fast, zero-allocation-where-possible semantic intent & slot parser for disaster communications.
 * Maps unstructured text or ASR outputs into [SutraFrame].
 */
object SutraParser {

    private val NUMBER_PATTERN = Pattern.compile("(\\d+)")

    /**
     * Parses emergency text into a structured [SutraFrame].
     */
    fun parse(text: String, language: Language = Language.ENGLISH): SutraFrame {
        val lower = text.lowercase().trim()

        // 1. Detect Negation
        val isNegated = lower.contains("no ") || lower.contains("not ") ||
            lower.contains("nahi") || lower.contains("illa") || lower.contains("illai")

        // 2. Detect Urgency / Criticality
        val isCritical = lower.contains("emergency") || lower.contains("critical") ||
            lower.contains("danger") || lower.contains("urgent") || lower.contains("khatra") ||
            lower.contains("aapat") || lower.contains("avaram") || lower.contains("sos")

        var flags: Byte = SutraFlags.FLAG_NONE
        if (isCritical) flags = (flags.toInt() or SutraFlags.FLAG_CRITICAL.toInt() or SutraFlags.FLAG_URGENT.toInt()).toByte()
        if (isNegated) flags = (flags.toInt() or SutraFlags.FLAG_NEGATION.toInt()).toByte()

        // 3. Extract numbers for slot population
        val matcher = NUMBER_PATTERN.matcher(lower)
        val extractedNumber = if (matcher.find()) matcher.group(1)?.toIntOrNull() else null

        val slots = mutableMapOf<SutraSlotKey, SutraSlotValue>()

        // 4. Domain & Intent matching
        val (domain, intent) = when {
            // SOS: Trapped / Help / Injured
            lower.contains("trap") || lower.contains("fanse") || lower.contains("sikkiy") -> {
                if (extractedNumber != null) {
                    slots[SutraSlotKey.PEOPLE_COUNT] = SutraSlotValue.IntVal(extractedNumber)
                }
                SutraDomain.SOS to SutraIntent.TRAPPED
            }
            lower.contains("injur") || lower.contains("ghayal") || lower.contains("kayam") -> {
                if (extractedNumber != null) {
                    slots[SutraSlotKey.PEOPLE_COUNT] = SutraSlotValue.IntVal(extractedNumber)
                }
                SutraDomain.SOS to SutraIntent.INJURED
            }

            // Medical: First Aid / Oxygen / Blood
            lower.contains("first aid") || lower.contains("sahay") -> {
                SutraDomain.MEDICAL to SutraIntent.FIRST_AID
            }
            lower.contains("oxygen") || lower.contains("saans") -> {
                if (extractedNumber != null) {
                    slots[SutraSlotKey.QUANTITY] = SutraSlotValue.IntVal(extractedNumber)
                }
                SutraDomain.MEDICAL to SutraIntent.OXYGEN_NEEDED
            }
            lower.contains("blood") || lower.contains("khoon") || lower.contains("rath") -> {
                SutraDomain.MEDICAL to SutraIntent.BLOOD_LOSS
            }
            lower.contains("doctor") || lower.contains("medic") ||
                lower.contains("dawa") || lower.contains("marunthu") -> {
                SutraDomain.MEDICAL to SutraIntent.MEDICATION
            }

            // Danger: Flood / Fire / Collapse / Wire
            lower.contains("flood") || lower.contains("paani") || lower.contains("vellam") || lower.contains("water rising") -> {
                SutraDomain.DANGER to SutraIntent.FLOOD_RISING
            }
            lower.contains("fire") || lower.contains("aag") || lower.contains("thee") -> {
                SutraDomain.DANGER to SutraIntent.FIRE_EXPLOSION
            }
            lower.contains("gas") || lower.contains("leak") || lower.contains("gundha") || lower.contains("vaasanai") -> {
                SutraDomain.DANGER to SutraIntent.GAS_LEAK
            }
            lower.contains("wire") || lower.contains("electric") || lower.contains("current") -> {
                SutraDomain.DANGER to SutraIntent.LIVE_ELECTRIC_WIRE
            }
            lower.contains("collapse") || lower.contains("gir gaya") || lower.contains("building") -> {
                SutraDomain.DANGER to SutraIntent.BUILDING_COLLAPSE
            }

            // Supply: Water / Food / Shelter
            lower.contains("water") || lower.contains("drinking") || lower.contains("peene") || lower.contains("thanni") -> {
                if (extractedNumber != null) {
                    slots[SutraSlotKey.QUANTITY] = SutraSlotValue.IntVal(extractedNumber)
                }
                SutraDomain.SUPPLY to SutraIntent.DRINKING_WATER
            }
            lower.contains("food") || lower.contains("ration") || lower.contains("khana") || lower.contains("saapadu") -> {
                if (extractedNumber != null) {
                    slots[SutraSlotKey.QUANTITY] = SutraSlotValue.IntVal(extractedNumber)
                }
                SutraDomain.SUPPLY to SutraIntent.FOOD_RATIONS
            }

            // Evacuation: Boat / Stranded / Shelter
            lower.contains("boat") || lower.contains("naav") || lower.contains("padagu") -> {
                SutraDomain.EVACUATION to SutraIntent.BOAT_NEEDED
            }
            lower.contains("strand") || lower.contains("atke") || lower.contains("shelter") -> {
                SutraDomain.EVACUATION to SutraIntent.STRANDED
            }

            // Default SOS immediate assistance
            else -> {
                if (extractedNumber != null) {
                    slots[SutraSlotKey.PEOPLE_COUNT] = SutraSlotValue.IntVal(extractedNumber)
                }
                SutraDomain.SOS to SutraIntent.IMMEDIATE_ASSISTANCE
            }
        }

        return SutraFrame(
            domain = domain,
            intent = intent,
            flags = flags,
            confidence = 90,
            slots = slots
        )
    }
}
