package com.example.itantra.data

import android.content.Context
import android.util.Log
import com.example.itantra.codec.Language
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExperimentLogger(private val context: Context) {

    companion object {
        private const val TAG = "ExperimentLogger"
        private const val LOG_DIR = "experiment_logs"
    }

    @Serializable
    data class ExperimentEntry(
        val timestamp: Long,
        val language: String,
        val message: String,
        val originalBytes: Int,
        val encodedBytes: Int,
        val packetCount: Int,
        val packetLoss: Int,
        val retransmissions: Int,
        val sttLatencyMs: Long,
        val encodeLatencyMs: Long,
        val transportLatencyMs: Long,
        val decodeLatencyMs: Long,
        val ttsLatencyMs: Long,
        val totalLatencyMs: Long,
        val codecType: String,
        val compressionPercentage: Double
    )

    @Serializable
    data class ExperimentSession(
        val sessionId: String,
        val startTime: Long,
        val entries: MutableList<ExperimentEntry> = mutableListOf()
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var currentSession: ExperimentSession? = null

    fun startSession(): String {
        val sessionId = "session_${System.currentTimeMillis()}"
        currentSession = ExperimentSession(
            sessionId = sessionId,
            startTime = System.currentTimeMillis()
        )
        return sessionId
    }

    fun logEntry(
        language: Language,
        message: String,
        originalBytes: Int,
        encodedBytes: Int,
        packetCount: Int,
        packetLoss: Int,
        retransmissions: Int,
        sttLatencyMs: Long,
        encodeLatencyMs: Long,
        transportLatencyMs: Long,
        decodeLatencyMs: Long,
        ttsLatencyMs: Long,
        totalLatencyMs: Long,
        codecType: String,
        compressionPercentage: Double
    ) {
        val entry = ExperimentEntry(
            timestamp = System.currentTimeMillis(),
            language = language.code,
            message = message,
            originalBytes = originalBytes,
            encodedBytes = encodedBytes,
            packetCount = packetCount,
            packetLoss = packetLoss,
            retransmissions = retransmissions,
            sttLatencyMs = sttLatencyMs,
            encodeLatencyMs = encodeLatencyMs,
            transportLatencyMs = transportLatencyMs,
            decodeLatencyMs = decodeLatencyMs,
            ttsLatencyMs = ttsLatencyMs,
            totalLatencyMs = totalLatencyMs,
            codecType = codecType,
            compressionPercentage = compressionPercentage
        )

        currentSession?.entries?.add(entry)
        saveCurrentSession()
    }

    fun endSession(): ExperimentSession? {
        val session = currentSession
        currentSession = null
        return session
    }

    private fun saveCurrentSession() {
        val session = currentSession ?: return
        try {
            val dir = File(context.filesDir, LOG_DIR)
            dir.mkdirs()
            val file = File(dir, "${session.sessionId}.json")
            file.writeText(json.encodeToString(session))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
        }
    }

    fun loadSessions(): List<ExperimentSession> {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<ExperimentSession>(file.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load session: ${file.name}", e)
                    null
                }
            }
            ?.sortedByDescending { it.startTime }
            ?: emptyList()
    }

    fun clearSessions() {
        val dir = File(context.filesDir, LOG_DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    fun getComparisonData(): List<Pair<ExperimentEntry, ExperimentEntry>> {
        val sessions = loadSessions()
        val entries = sessions.flatMap { it.entries }

        val baselineEntries = entries.filter { it.codecType == "BASELINE" }
        val retroEntries = entries.filter { it.codecType == "RETRO" }

        return baselineEntries.zip(retroEntries)
    }

    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }
}
