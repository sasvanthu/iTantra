package com.example.itantra.data

import com.example.itantra.codec.Language
import com.example.itantra.speech.stt.StablePrefixTracker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class StreamingAsrAndModelLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `StablePrefixTracker commits prefix only after threshold consecutive matches`() {
        val tracker = StablePrefixTracker(stabilityThreshold = 2)

        // Step 1: First interim hypothesis "need"
        val (p1, t1) = tracker.update("need")
        assertEquals("", p1) // Not yet committed (stability = 1 < 2)
        assertEquals(1, t1.size)
        assertFalse(t1[0].isStable)

        // Step 2: Second interim hypothesis "need medical"
        val (p2, t2) = tracker.update("need medical")
        assertEquals("need", p2) // "need" has appeared twice -> committed!
        assertTrue(t2[0].isStable)
        assertFalse(t2[1].isStable) // "medical" is new -> not yet stable

        // Step 3: Third interim hypothesis "need medical help"
        val (p3, t3) = tracker.update("need medical help")
        assertEquals("need medical", p3) // "medical" now committed
        assertTrue(t3[0].isStable)
        assertTrue(t3[1].isStable)
        assertFalse(t3[2].isStable)

        // Reset
        tracker.reset()
        assertEquals("", tracker.getCommittedPrefix())
    }

    @Test
    fun `ModelValidator identifies missing empty and valid files with SHA-256`() {
        val testDir = tempFolder.newFolder("models")
        val sampleFile = File(testDir, "test-model.bin")
        val content = "iTantra model weight data payload".toByteArray(StandardCharsets.UTF_8)
        sampleFile.writeBytes(content)

        val validSha256 = ModelValidator.computeSha256(sampleFile)
        val manifest = ModelManifest(
            modelId = "test-stt-hi",
            language = Language.HINDI,
            kind = ModelManager.ModelKind.STT,
            version = "1.0.0",
            sha256 = validSha256,
            expectedSizeBytes = content.size.toLong(),
            runtime = "SHERPA_ONNX",
            quantization = "INT8",
            localFileName = "test-model.bin"
        )

        // 1. Valid test
        val validRes = ModelValidator.validate(sampleFile, manifest)
        assertTrue("Model must be valid", validRes is ModelValidator.ValidationResult.Valid)

        // 2. Missing file test
        val missingFile = File(testDir, "non-existent.bin")
        val missingRes = ModelValidator.validate(missingFile, manifest)
        assertTrue("Missing file must return Missing", missingRes is ModelValidator.ValidationResult.Missing)

        // 3. Corrupted hash test
        val corruptManifest = manifest.copy(sha256 = "0000000000000000000000000000000000000000000000000000000000000000")
        val corruptRes = ModelValidator.validate(sampleFile, corruptManifest)
        assertTrue("Mismatched hash must return Corrupted", corruptRes is ModelValidator.ValidationResult.Corrupted)

        // 4. Empty file test
        val emptyFile = File(testDir, "empty.bin")
        emptyFile.createNewFile()
        val emptyRes = ModelValidator.validate(emptyFile, manifest)
        assertTrue("Zero byte file must return Corrupted", emptyRes is ModelValidator.ValidationResult.Corrupted)
    }

    @Test
    fun `ModelLifecycleManager enforces single active model residency in RAM`() {
        val manager = ModelLifecycleManager()

        val sttManifest = ModelManifest(
            modelId = "stt-en",
            language = Language.ENGLISH,
            kind = ModelManager.ModelKind.STT,
            version = "1.0",
            sha256 = "SKIP",
            expectedSizeBytes = 40_000_000L,
            runtime = "SHERPA_ONNX",
            quantization = "INT8",
            localFileName = "stt-en.onnx"
        )

        val ttsManifest = ModelManifest(
            modelId = "tts-en",
            language = Language.ENGLISH,
            kind = ModelManager.ModelKind.TTS,
            version = "1.0",
            sha256 = "SKIP",
            expectedSizeBytes = 20_000_000L,
            runtime = "PIPER",
            quantization = "INT8",
            localFileName = "tts-en.onnx"
        )

        // 1. Acquire STT
        assertTrue(manager.acquireStt(sttManifest))
        assertEquals(ModelLifecycleState.READY, manager.currentSttState)
        assertEquals(ModelLifecycleState.UNLOADED, manager.currentTtsState)

        // 2. Run STT inference
        assertTrue(manager.startSttInference())
        assertEquals(ModelLifecycleState.ACTIVE, manager.currentSttState)

        // 3. Attempting to acquire TTS while STT inference is active must be rejected
        assertFalse("Cannot acquire TTS while STT is actively recognizing speech", manager.acquireTts(ttsManifest))

        // 4. Finish STT inference
        manager.finishSttInference()
        assertEquals(ModelLifecycleState.READY, manager.currentSttState)

        // 5. Now acquire TTS -> Single-active-model policy must automatically evict STT
        assertTrue(manager.acquireTts(ttsManifest))
        assertEquals("STT must be evicted to free RAM", ModelLifecycleState.UNLOADED, manager.currentSttState)
        assertNull("Active STT model must be null after eviction", manager.currentSttModel)
        assertEquals(ModelLifecycleState.READY, manager.currentTtsState)
        assertEquals(ttsManifest, manager.currentTtsModel)

        // 6. Run TTS inference
        assertTrue(manager.startTtsInference())
        assertEquals(ModelLifecycleState.ACTIVE, manager.currentTtsState)
        manager.finishTtsInference()
        assertEquals(ModelLifecycleState.READY, manager.currentTtsState)

        // 7. Clean eviction
        assertTrue(manager.evictTts())
        assertEquals(ModelLifecycleState.UNLOADED, manager.currentTtsState)
    }
}
