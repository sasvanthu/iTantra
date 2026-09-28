package com.example.itantra.dispatch

import com.example.itantra.codec.Language
import com.example.itantra.codec.SutraDomain
import com.example.itantra.codec.SutraIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDispatchRouterTest {

    @Test
    fun `route transcribes and classifies an emergency utterance`() {
        val router = SemanticDispatchRouter()
        val route = router.route("Trapped inside building 3 people emergency", Language.ENGLISH)

        assertTrue(route.isCriticalIntent)
        assertEquals(SutraDomain.SOS, route.sutra.domain)
        assertEquals(SutraIntent.TRAPPED, route.sutra.intent)
        assertEquals("Trapped inside building 3 people emergency", route.transcript)
        assertEquals(2, route.layerCount)
        assertTrue(route.importance.toInt() >= 2) // CRITICAL importance
        assertTrue(route.summary.lowercase().contains("trapped"))
    }

    @Test
    fun `non critical utterance stays normal importance`() {
        val router = SemanticDispatchRouter()
        val route = router.route("Please send drinking water and more supplies", Language.ENGLISH)

        assertFalse(route.isCriticalIntent)
        assertEquals(SutraDomain.SUPPLY, route.sutra.domain)
        assertEquals(SutraIntent.DRINKING_WATER, route.sutra.intent)
        assertTrue(route.importance.toInt() < 2)
    }

    @Test
    fun `route is deterministic and message ids increase`() {
        val router = SemanticDispatchRouter()
        val first = router.route("fire emergency", Language.ENGLISH)
        val second = router.route("fire emergency", Language.ENGLISH)

        assertTrue(second.messageId > first.messageId)
        assertEquals(first.sutra.domain, second.sutra.domain)
        assertEquals(first.sutra.intent, second.sutra.intent)

        router.reset()
        val afterReset = router.route("fire emergency", Language.ENGLISH)
        assertEquals(1L, afterReset.messageId)
    }

    @Test
    fun `multilingual utterance flows through the chain losslessly`() {
        val router = SemanticDispatchRouter()
        val route = router.route("khana chahiye 25 logo ke liye", Language.HINDI)

        assertEquals(SutraDomain.SUPPLY, route.sutra.domain)
        assertEquals(SutraIntent.FOOD_RATIONS, route.sutra.intent)
        assertEquals("khana chahiye 25 logo ke liye", route.transcript)
    }
}