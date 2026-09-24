package com.example.itantra.data

import com.example.itantra.codec.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import org.junit.Test

class ModelManagerTest {

    @Test
    fun `catalog wants stt for the three working languages and tts for all ten`() {
        val stt = ModelManager.wanted.filter { it.kind == ModelManager.ModelKind.STT }
        val tts = ModelManager.wanted.filter { it.kind == ModelManager.ModelKind.TTS }
        assertEquals(listOf(Language.ENGLISH, Language.HINDI, Language.TAMIL),
            stt.map { it.language })
        assertEquals(10, tts.size)
        assertTrue(ModelManager.wanted.none { it.id.isBlank() })
    }

    @Test
    fun `a probe that finds nothing reports everything missing`() {
        val missing = ModelManager.missing { false }
        assertEquals(ModelManager.wanted.size, missing.size)
        assertTrue(missing.all { !it.isUsable })
    }

    @Test
    fun `a probe that finds english stt only reports exactly that usable`() {
        val probe = ModelManager.ModelProbe { entry ->
            entry.id == "stt-ENGLISH" &&
                entry.kind == ModelManager.ModelKind.STT
        }
        val missing = ModelManager.missing(probe)
        assertEquals("13 wanted, 1 present -> 12 missing", 12, missing.size)
        assertTrue("present stt must not be reported missing",
            missing.none { it.entry.id == "stt-ENGLISH" })
        assertEquals(ModelManager.Status.PRESENT,
            ModelManager.resolve(probe).first { it.entry.id == "stt-ENGLISH" }.status)
        assertEquals(ModelManager.Status.MISSING,
            ModelManager.resolve(probe).first { it.entry.id == "stt-HINDI" }.status)
    }

    @Test
    fun `the honest production default is zero usable models`() {
        // The truth today: this repository ships no offline model data.
        val byKind = ModelManager.countByKind { false }
        assertEquals(ModelManager.Status.MISSING,
            byKind.getValue(ModelManager.ModelKind.STT).keys.first())
        assertEquals(3, byKind.getValue(ModelManager.ModelKind.STT).getValue(ModelManager.Status.MISSING))
        assertEquals(10, byKind.getValue(ModelManager.ModelKind.TTS).getValue(ModelManager.Status.MISSING))
    }

    @Test
    fun `size hints are informational and positive`() {
        ModelManager.wanted.forEach { entry -> assertTrue(entry.sizeHintBytes >= 0) }
        assertEquals(40 * 1024 * 1024,
            ModelManager.wanted.first { it.id == "stt-ENGLISH" }.sizeHintBytes)
    }
}