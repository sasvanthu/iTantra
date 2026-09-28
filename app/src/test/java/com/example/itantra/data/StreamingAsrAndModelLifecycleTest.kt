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
    fun `StablePrefixTracker flags uncertain tokens so they never ship as truth`() {
        val tracker = StablePrefixTracker(stabilityThreshold = 2)

        // Fresh token: low confidence -> tagged UNCERTAIN until proven stable.
        val (_, t1) = tracker.update("need")
        assertTrue(t1[0].isUncertainFlagged)

        // Repeated token: stable confidence 0.95 -> no uncertainty flag.
        val (_, t2) = tracker.update("need more")
        assertFalse(t2[0].isUncertainFlagged)
        assertTrue(t2[1].isUncertainFlagged) // "more" is new -> still uncertain
        assertEquals("need", tracker.getCommittedPrefix())
    }

    @Test
    fun `ModelLifecycleManager refuses corrupted model files and reports the reason`() {
        val testDir = tempFolder.newFolder("models-validated")
        val corruptFile = File(testDir, "corrupt-stt.bin")
        corruptFile.writeText("not the real model")

        val manager = ModelLifecycleManager()
        val sttManifest = ModelManifest(
            modelId = "stt-validated",
            language = Language.ENGLISH,
            kind = ModelManager.ModelKind.STT,
            version = "1.0",
            sha256 = "a".repeat(64),
            expectedSizeBytes = 0L,
            runtime = "SHERPA_ONNX",
            quantization = "INT8",
            localFileName = "corrupt-stt.bin"
        )

        // Corrupted (size/hash mismatch) -> ERROR, never READY, never admitted to RAM.
        assertFalse("corrupted model file must be refused", manager.acquireValidatedStt(sttManifest, corruptFile))
        assertEquals(ModelLifecycleState.ERROR, manager.currentSttState)
        assertNull(manager.currentSttModel)
        assertNotNull("a validation reason must be surfaced", manager.lastValidationFailure)

        // Missing file -> ERROR too.
        val missingFile = File(testDir, "missing.bin")
        assertFalse("missing model file must be refused", manager.acquireValidatedStt(sttManifest, missingFile))
        assertEquals(ModelLifecycleState.ERROR, manager.currentSttState)
    }

    @Test
    fun `ModelLifecycleManager admits a valid model file to READY and evicts it cleanly`() {
        val testDir = tempFolder.newFolder("models-valid")
        val goodFile = File(testDir, "good-tts.bin")
        val content = "synthetic piper voice weights".toByteArray(StandardCharsets.UTF_8)
        goodFile.writeBytes(content)
        val sha = ModelValidator.computeSha256(goodFile)

        val manager = ModelLifecycleManager()
        val ttsManifest = ModelManifest(
            modelId = "tts-valid",
            language = Language.ENGLISH,
            kind = ModelManager.ModelKind.TTS,
            version = "1.0",
            sha256 = sha,
            expectedSizeBytes = content.size.toLong(),
            runtime = "PIPER",
            quantization = "INT8",
            localFileName = "good-tts.bin"
        )

        val admitted = manager.acquireValidatedTts(ttsManifest, goodFile)
        assertTrue("matching file must be admitted", admitted)
        assertEquals(ModelLifecycleState.READY, manager.currentTtsState)
        assertEquals(ttsManifest, manager.currentTtsModel)
        assertEquals("accepted admission must not set a failure reason", null, manager.lastValidationFailure)
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
