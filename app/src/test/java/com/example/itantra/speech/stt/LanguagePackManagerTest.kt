package com.example.itantra.speech.stt

import com.example.itantra.codec.Language
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LanguagePackManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `catalog contains english as mandatory and available`() {
        val english = LanguagePackManager.getDescriptor(Language.ENGLISH)
        assertNotNull("English must be present in catalog", english)
        assertTrue("English must be marked mandatory", english!!.isMandatory)
        assertTrue("English must be available", english.isAvailable)
        assertNotNull("English download URL must exist", english.downloadUrl)
        assertTrue("English size must be ~39MB", english.sizeBytes > 30_000_000L)
    }

    @Test
    fun `catalog contains hindi telugu gujarati as available optional languages`() {
        val hindi = LanguagePackManager.getDescriptor(Language.HINDI)
        assertNotNull("Hindi must be in catalog", hindi)
        assertTrue("Hindi must be available", hindi!!.isAvailable)
        assertFalse("Hindi must not be mandatory", hindi.isMandatory)

        val telugu = LanguagePackManager.getDescriptor(Language.TELUGU)
        assertNotNull("Telugu must be in catalog", telugu)
        assertTrue("Telugu must be available", telugu!!.isAvailable)

        val gujarati = LanguagePackManager.getDescriptor(Language.GUJARATI)
        assertNotNull("Gujarati must be in catalog", gujarati)
        assertTrue("Gujarati must be available", gujarati!!.isAvailable)
    }

    @Test
    fun `catalog contains tamil and kannada as available regional languages`() {
        val tamil = LanguagePackManager.getDescriptor(Language.TAMIL)
        assertNotNull("Tamil must be in catalog", tamil)
        assertTrue("Tamil must be available via AI4Bharat", tamil!!.isAvailable)
        assertNotNull("Tamil download URL must exist", tamil.downloadUrl)

        val kannada = LanguagePackManager.getDescriptor(Language.KANNADA)
        assertNotNull("Kannada must be in catalog", kannada)
        assertTrue("Kannada must be available via AI4Bharat", kannada!!.isAvailable)
        assertNotNull("Kannada download URL must exist", kannada.downloadUrl)
    }

    @Test
    fun `catalog honestly reports unavailable models without pretending they work`() {
        val bengali = LanguagePackManager.getDescriptor(Language.BENGALI)
        assertNotNull(bengali)
        assertFalse("Bengali must not be reported available", bengali!!.isAvailable)

        val marathi = LanguagePackManager.getDescriptor(Language.MARATHI)
        assertNotNull(marathi)
        assertFalse("Marathi must not be reported available", marathi!!.isAvailable)
    }

    @Test
    fun `isValidVoskModelDir returns false for null, empty or missing directories`() {
        assertFalse("Null dir must be invalid", LanguagePackManager.isValidVoskModelDir(null))

        val emptyDir = tempFolder.newFolder("empty")
        assertFalse("Empty dir must be invalid", LanguagePackManager.isValidVoskModelDir(emptyDir))

        val nonExistent = File(emptyDir, "non_existent")
        assertFalse("Non existent dir must be invalid", LanguagePackManager.isValidVoskModelDir(nonExistent))
    }

    @Test
    fun `isValidVoskModelDir returns true when acoustic model and graph are present`() {
        val modelDir = tempFolder.newFolder("vosk_en")
        File(modelDir, "am").mkdirs()
        File(modelDir, "am/final.mdl").writeText("fake acoustic model data for test")
        File(modelDir, "conf").mkdirs()
        File(modelDir, "conf/mfcc.conf").writeText("--sample-frequency=16000")
        File(modelDir, "graph").mkdirs()
        File(modelDir, "graph/HCLG.fst").writeText("fake graph")

        assertTrue("Complete Vosk model layout must be recognized as valid", LanguagePackManager.isValidVoskModelDir(modelDir))
    }

    @Test
    fun `resolveVoskRoot finds nested model folder when extracted with root prefix`() {
        val parentDir = tempFolder.newFolder("nested_model_test")
        val innerDir = File(parentDir, "vosk-model-small-en-us-0.15")
        innerDir.mkdirs()
        File(innerDir, "am").mkdirs()
        File(innerDir, "am/final.mdl").writeText("acoustic")
        File(innerDir, "conf").mkdirs()
        File(innerDir, "conf/mfcc.conf").writeText("conf")
        File(innerDir, "graph").mkdirs()

        val resolved = LanguagePackManager.resolveVoskRoot(parentDir)
        assertEquals("Must resolve to the inner directory containing am and conf", innerDir.absolutePath, resolved.absolutePath)
        assertTrue("Resolved directory must be valid", LanguagePackManager.isValidVoskModelDir(resolved))
    }

    @Test
    fun `HybridSTTEngine refuses recognition when model is not installed and never falls back to online`() {
        val engine = HybridSTTEngine()
        // Without an installed model, isInitialized must be false
        assertFalse("STT engine must not be initialized when no offline model is present", engine.isInitialized())

        var callbackCalled = false
        engine.startListening { callbackCalled = true }
        assertFalse("startListening must not invoke callback when uninitialized", callbackCalled)
    }
}
