package com.example.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SutraFrameTest {

    @Test
    fun `minimal sutra frame serializes to exactly 6 bytes`() {
        val frame = SutraFrame(
            domain = SutraDomain.SOS,
            intent = SutraIntent.IMMEDIATE_ASSISTANCE,
            flags = SutraFlags.FLAG_NONE,
            confidence = 95,
            slots = emptyMap()
        )

        val bytes = SutraFrame.serialize(frame)
        assertEquals("Minimal SUTRA frame must be exactly 6 bytes", 6, bytes.size)

        val decoded = SutraFrame.deserialize(bytes)
        assertNotNull(decoded)
        assertEquals(SutraDomain.SOS, decoded!!.domain)
        assertEquals(SutraIntent.IMMEDIATE_ASSISTANCE, decoded.intent)
        assertEquals(95, decoded.confidence)
        assertEquals(0, decoded.slots.size)
        assertFalse(decoded.isCritical)
    }

    @Test
    fun `standard emergency message fits within 8 to 14 bytes`() {
        // "HELP TRAPPED 5 PEOPLE"
        val frame = SutraFrame(
            domain = SutraDomain.SOS,
            intent = SutraIntent.TRAPPED,
            flags = (SutraFlags.FLAG_CRITICAL.toInt() or SutraFlags.FLAG_URGENT.toInt()).toByte(),
            confidence = 98,
            slots = mapOf(
                SutraSlotKey.PEOPLE_COUNT to SutraSlotValue.IntVal(5)
            )
        )

        val bytes = SutraFrame.serialize(frame)
        // Magic (1) + Domain (1) + Intent (1) + Flags (1) + Conf (1) + SlotCount (1) + Key (1) + Type (1) + Varint (1) = 9 bytes!
        println("SUTRA frame wire size: ${bytes.size} bytes")
        assertTrue("SUTRA binary frame size (${bytes.size}B) must be between 8 and 14 bytes", bytes.size in 8..14)

        val plainText = "HELP TRAPPED 5 PEOPLE"
        val plainBytes = plainText.toByteArray(Charsets.UTF_8).size
        val compression = 100.0 * (1.0 - bytes.size.toDouble() / plainBytes.toDouble())
        println("Plain text size: ${plainBytes}B, SUTRA size: ${bytes.size}B (${String.format("%.1f", compression)}% reduction)")
        assertTrue("Semantic compression must exceed 50%", compression > 50.0)

        val decoded = SutraFrame.deserialize(bytes)
        assertNotNull(decoded)
        assertEquals(SutraDomain.SOS, decoded!!.domain)
        assertEquals(SutraIntent.TRAPPED, decoded.intent)
        assertTrue(decoded.isCritical)
        assertTrue(decoded.isUrgent)
        assertEquals(1, decoded.slots.size)
        assertEquals(5, (decoded.slots[SutraSlotKey.PEOPLE_COUNT] as SutraSlotValue.IntVal).value)
    }

    @Test
    fun `gps coordinates slot serializes compactly`() {
        val latE5 = (13.0827 * 100000).toInt() // Chennai Lat
        val lonE5 = (80.2707 * 100000).toInt() // Chennai Lon

        val frame = SutraFrame(
            domain = SutraDomain.LOCATION,
            intent = SutraIntent.GPS_COORDINATES,
            flags = SutraFlags.FLAG_CRITICAL,
            confidence = 100,
            slots = mapOf(
                SutraSlotKey.LATITUDE_E5 to SutraSlotValue.CoordVal(latE5),
                SutraSlotKey.LONGITUDE_E5 to SutraSlotValue.CoordVal(lonE5)
            )
        )

        val bytes = SutraFrame.serialize(frame)
        // 6 header + 2 * (1 key + 1 type + 4 val) = 18 bytes
        assertEquals(18, bytes.size)

        val decoded = SutraFrame.deserialize(bytes)
        assertNotNull(decoded)
        assertEquals(SutraDomain.LOCATION, decoded!!.domain)
        val latDec = (decoded.slots[SutraSlotKey.LATITUDE_E5] as SutraSlotValue.CoordVal).degrees
        val lonDec = (decoded.slots[SutraSlotKey.LONGITUDE_E5] as SutraSlotValue.CoordVal).degrees
        assertEquals(13.0827, latDec, 0.0001)
        assertEquals(80.2707, lonDec, 0.0001)
    }

    @Test
    fun `flags preserve negation and uncertainty`() {
        val frame = SutraFrame(
            domain = SutraDomain.SUPPLY,
            intent = SutraIntent.DRINKING_WATER,
            flags = (SutraFlags.FLAG_NEGATION.toInt() or SutraFlags.FLAG_UNCERTAIN.toInt()).toByte(),
            confidence = 55,
            slots = emptyMap()
        )

        val bytes = SutraFrame.serialize(frame)
        val decoded = SutraFrame.deserialize(bytes)
        assertNotNull(decoded)
        assertTrue(decoded!!.isNegated)
        assertTrue(decoded.isUncertain)
        assertFalse(decoded.isCritical)
        assertEquals(55, decoded.confidence)
    }

    @Test
    fun `sutra parser extracts intents and numbers from natural language`() {
        val f1 = SutraParser.parse("We are trapped inside 3 people emergency")
        assertEquals(SutraDomain.SOS, f1.domain)
        assertEquals(SutraIntent.TRAPPED, f1.intent)
        assertTrue(f1.isCritical)
        assertEquals(3, (f1.slots[SutraSlotKey.PEOPLE_COUNT] as SutraSlotValue.IntVal).value)

        val f2 = SutraParser.parse("Need drinking water 20 liters")
        assertEquals(SutraDomain.SUPPLY, f2.domain)
        assertEquals(SutraIntent.DRINKING_WATER, f2.intent)
        assertEquals(20, (f2.slots[SutraSlotKey.QUANTITY] as SutraSlotValue.IntVal).value)

        val f3 = SutraParser.parse("Flood rising rapidly danger")
        assertEquals(SutraDomain.DANGER, f3.domain)
        assertEquals(SutraIntent.FLOOD_RISING, f3.intent)
        assertTrue(f3.isCritical)

        val f4 = SutraParser.parse("No medicine available")
        assertEquals(SutraDomain.MEDICAL, f4.domain)
        assertTrue(f4.isNegated)
    }

    @Test
    fun `every disaster domain maps to the correct intent from English`() {
        val cases = listOf(
            "we are trapped under debris help" to (SutraDomain.SOS to SutraIntent.TRAPPED),
            "two people injured" to (SutraDomain.SOS to SutraIntent.INJURED),
            "need first aid immediately" to (SutraDomain.MEDICAL to SutraIntent.FIRST_AID),
            "patient needs oxygen cylinder" to (SutraDomain.MEDICAL to SutraIntent.OXYGEN_NEEDED),
            "heavy blood loss" to (SutraDomain.MEDICAL to SutraIntent.BLOOD_LOSS),
            "send medicine for fever" to (SutraDomain.MEDICAL to SutraIntent.MEDICATION),
            "water rising fast in village" to (SutraDomain.DANGER to SutraIntent.FLOOD_RISING),
            "fire in the market" to (SutraDomain.DANGER to SutraIntent.FIRE_EXPLOSION),
            "gas leak near the kitchen" to (SutraDomain.DANGER to SutraIntent.GAS_LEAK),
            "electric wire down on road" to (SutraDomain.DANGER to SutraIntent.LIVE_ELECTRIC_WIRE),
            "building collapsed near temple" to (SutraDomain.DANGER to SutraIntent.BUILDING_COLLAPSE),
            "need drinking water" to (SutraDomain.SUPPLY to SutraIntent.DRINKING_WATER),
            "send food rations for 40 people" to (SutraDomain.SUPPLY to SutraIntent.FOOD_RATIONS),
            "we need a boat to cross river" to (SutraDomain.EVACUATION to SutraIntent.BOAT_NEEDED),
            "we are stranded on the roof" to (SutraDomain.EVACUATION to SutraIntent.STRANDED),
            "reach shelter safely now" to (SutraDomain.EVACUATION to SutraIntent.STRANDED)
        )
        for ((text, expected) in cases) {
            val frame = SutraParser.parse(text)
            assertEquals("domain for '$text'", expected.first, frame.domain)
            assertEquals("intent for '$text'", expected.second, frame.intent)
        }
    }

    @Test
    fun `sutra parser recognises romanized hindi and tamil keywords`() {
        val trapped = SutraParser.parse("hum 4 log fanse hai emergency")
        assertEquals(SutraDomain.SOS, trapped.domain)
        assertEquals(SutraIntent.TRAPPED, trapped.intent)
        assertEquals(4, (trapped.slots[SutraSlotKey.PEOPLE_COUNT] as SutraSlotValue.IntVal).value)

        val injured = SutraParser.parse("do log ghayal hai emergency")
        assertEquals(SutraDomain.SOS, injured.domain)
        assertEquals(SutraIntent.INJURED, injured.intent)
        assertTrue(injured.isCritical)

        val water = SutraParser.parse("thanni venum 10 liter")
        assertEquals(SutraDomain.SUPPLY, water.domain)
        assertEquals(SutraIntent.DRINKING_WATER, water.intent)
        assertEquals(10, (water.slots[SutraSlotKey.QUANTITY] as SutraSlotValue.IntVal).value)

        val food = SutraParser.parse("khana chahiye 25 logo ke liye")
        assertEquals(SutraDomain.SUPPLY, food.domain)
        assertEquals(SutraIntent.FOOD_RATIONS, food.intent)
        assertEquals(25, (food.slots[SutraSlotKey.QUANTITY] as SutraSlotValue.IntVal).value)

        val fire = SutraParser.parse("thee pidichu, avaram!")
        assertEquals(SutraDomain.DANGER, fire.domain)
        assertEquals(SutraIntent.FIRE_EXPLOSION, fire.intent)
        assertTrue(fire.isCritical)

        val oxygen = SutraParser.parse("saans lene me takleef, oxygen chahiye")
        assertEquals(SutraDomain.MEDICAL, oxygen.domain)
        assertEquals(SutraIntent.OXYGEN_NEEDED, oxygen.intent)
    }

    @Test
    fun `multilingual negation and urgency words set the right flags`() {
        val hindiNo = SutraParser.parse("dawa nahi hai")
        assertTrue(hindiNo.isNegated)
        assertEquals(SutraDomain.MEDICAL, hindiNo.domain)

        val tamilNo = SutraParser.parse("thanni illa")
        assertTrue(tamilNo.isNegated)
        assertEquals(SutraDomain.SUPPLY, tamilNo.domain)

        val hindiUrgent = SutraParser.parse("bahut khatra, aapat stithi")
        assertTrue(hindiUrgent.isCritical)
        assertTrue(hindiUrgent.isUrgent)

        val tamilUrgent = SutraParser.parse("avasaram, SOS")
        assertTrue(tamilUrgent.isCritical)
        assertEquals(SutraDomain.SOS, tamilUrgent.domain)
    }

    @Test
    fun `default fallback is immediate assistance when nothing matches`() {
        val frame = SutraParser.parse("Hello world")
        assertEquals(SutraDomain.SOS, frame.domain)
        assertEquals(SutraIntent.IMMEDIATE_ASSISTANCE, frame.intent)
        assertFalse(frame.isCritical)
    }

    @Test
    fun `human readable reconstruction displays flags and slots`() {
        val frame = SutraFrame(
            domain = SutraDomain.SOS,
            intent = SutraIntent.TRAPPED,
            flags = SutraFlags.FLAG_CRITICAL,
            slots = mapOf(SutraSlotKey.PEOPLE_COUNT to SutraSlotValue.IntVal(4))
        )
        val text = SutraFrame.toHumanReadable(frame)
        assertTrue(text.contains("[CRITICAL]"))
        assertTrue(text.contains("SOS: TRAPPED"))
        assertTrue(text.contains("People: 4"))
    }
}
