package com.example.itantra.data

import com.example.itantra.codec.Language
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Metadata manifest describing an offline speech recognition or synthesis model asset.
 */
data class ModelManifest(
    val modelId: String,
    val language: Language,
    val kind: ModelManager.ModelKind,
    val version: String,
    val sha256: String,
    val expectedSizeBytes: Long,
    val runtime: String,
    val quantization: String,
    val localFileName: String
)

/**
 * Validates on-disk model files against their cryptographic manifest specifications.
 */
object ModelValidator {

    sealed class ValidationResult {
        data class Valid(val manifest: ModelManifest, val actualBytes: Long) : ValidationResult()
        data class Missing(val manifest: ModelManifest, val expectedPath: String) : ValidationResult()
        data class Corrupted(
            val manifest: ModelManifest,
            val reason: String,
            val expectedHash: String,
            val actualHash: String
        ) : ValidationResult()
    }

    /**
     * Validates that [file] exists, matches the expected size bounds, and satisfies the SHA-256 checksum in [manifest].
     * If [manifest.sha256] is empty or "SKIP", hash verification is bypassed (for development/staging models).
     */
    fun validate(file: File, manifest: ModelManifest): ValidationResult {
        if (!file.exists() || !file.isFile) {
            return ValidationResult.Missing(manifest, file.absolutePath)
        }

        val actualSize = file.length()
        if (actualSize == 0L) {
            return ValidationResult.Corrupted(manifest, "File is zero bytes (empty)", manifest.sha256, "")
        }

        if (manifest.expectedSizeBytes > 0 && actualSize != manifest.expectedSizeBytes) {
            return ValidationResult.Corrupted(
                manifest,
                "Size mismatch: expected=${manifest.expectedSizeBytes} actual=$actualSize",
                manifest.sha256,
                ""
            )
        }

        if (manifest.sha256.isNotBlank() && manifest.sha256 != "SKIP") {
            val computedHash = computeSha256(file)
            if (!computedHash.equals(manifest.sha256, ignoreCase = true)) {
                return ValidationResult.Corrupted(
                    manifest,
                    "SHA-256 hash mismatch",
                    manifest.sha256,
                    computedHash
                )
            }
        }

        return ValidationResult.Valid(manifest, actualSize)
    }

    /**
     * Computes the SHA-256 hexadecimal checksum of [file] in 8 KB streaming chunks.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        val sb = StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
