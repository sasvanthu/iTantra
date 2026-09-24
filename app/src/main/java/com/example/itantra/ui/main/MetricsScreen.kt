package com.example.itantra.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.data.ExperimentSummary
import com.example.itantra.ui.theme.*

@Composable
fun MetricsScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val metrics = uiState.metrics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "LIVE METRICS",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Latency Section
        MetricSection("LATENCY") {
            MetricRow("STT:", "${metrics?.latency?.sttLatencyMs ?: 0} ms")
            MetricRow("Encoding:", "${metrics?.latency?.encodingLatencyMs ?: 0} ms")
            MetricRow("Packetization:", "${metrics?.latency?.packetizationLatencyMs ?: 0} ms")
            MetricRow("Network send:", "${metrics?.latency?.transportLatencyMs ?: 0} ms")
            MetricRow("Network receive:", "${metrics?.latency?.networkReceiveLatencyMs ?: 0} ms")
            MetricRow("ACK latency:", "${metrics?.latency?.ackLatencyMs ?: 0} ms")
            MetricRow("RTT:", "${metrics?.latency?.roundTripTimeMs ?: 0} ms", RetroAmber)
            MetricRow("Decoding:", "${metrics?.latency?.decodingLatencyMs ?: 0} ms")
            MetricRow("TTS:", "${metrics?.latency?.ttsLatencyMs ?: 0} ms")
            MetricRow("Total:", "${metrics?.latency?.totalLatencyMs ?: 0} ms", RetroAmber)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Size Section
        MetricSection("SIZE ANALYSIS") {
            MetricRow("Original UTF-8:", "${metrics?.size?.originalUtf8Bytes ?: 0} bytes")
            MetricRow("Token Encoded:", "${metrics?.size?.tokenEncodedBytes ?: 0} bytes")
            MetricRow("Phoneme Encoded:", "${metrics?.size?.phonemeEncodedBytes ?: 0} bytes")
            MetricRow("Final Encoded:", "${metrics?.size?.finalEncodedBytes ?: 0} bytes")
            MetricRow("Compression:", "${String.format("%.1f", metrics?.size?.compressionPercentage ?: 0.0)}%",
                if ((metrics?.size?.compressionPercentage ?: 0.0) > 0) RetroGreen else RetroRed)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Transport Section
        MetricSection("TRANSPORT") {
            MetricRow("Packets Sent:", "${metrics?.transport?.packetsSent ?: 0}")
            MetricRow("Packets Received:", "${metrics?.transport?.packetsReceived ?: 0}")
            MetricRow("Retransmissions:", "${metrics?.transport?.retransmissions ?: 0}")
            MetricRow("Packet Loss:", "${metrics?.transport?.packetLoss ?: 0}")
            MetricRow("TX Bytes:", "${uiState.linkMetrics.transmittedBytes}")
            MetricRow("RX Bytes:", "${uiState.linkMetrics.receivedBytes}")
            MetricRow("Duplicates:", "${uiState.linkMetrics.duplicatePackets}")
            MetricRow("Corrupted frames:", "${uiState.linkMetrics.corruptedFrames}")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Last send sizes
        if (uiState.lastSendReport != null) {
            val rep = uiState.lastSendReport!!
            MetricSection("LAST SEND (CODEc vs WIRE)") {
                MetricRow("UTF-8:", "${rep.originalUtf8Bytes} B")
                MetricRow("Codec payload:", "${rep.encodedBytes} B")
                MetricRow("Transmitted:", "${rep.transmittedBytes} B",
                    if (rep.transmittedBytes <= rep.originalUtf8Bytes) RetroGreen else RetroOrange)
                MetricRow("Packets:", "${rep.packetCount}")
                MetricRow("Retries:", "${rep.retransmissions}",
                    if (rep.retransmissions > 0) RetroAmber else RetroGreen)
                MetricRow("Result:", if (rep.failed) "FAILED" else "DELIVERED + ACK",
                    if (rep.failed) RetroRed else RetroGreen)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // System Section
        MetricSection("SYSTEM") {
            MetricRow("Memory Used:", "${String.format("%.1f", metrics?.system?.memoryUsageMB ?: 0f)} MB")
            MetricRow("Heap Used:", "${String.format("%.1f", metrics?.system?.heapUsedMB ?: 0f)} MB")
            MetricRow("Heap Max:", "${String.format("%.1f", metrics?.system?.heapMaxMB ?: 0f)} MB")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Comparison Section
        if (uiState.codecComparison != null) {
            MetricSection("CODEC COMPARISON") {
                val comp = uiState.codecComparison!!
                MetricRow("Original:", "${comp.originalUtf8Bytes} bytes")
                MetricRow("Retro Encoded:", "${comp.retroEncodedBytes} bytes")
                MetricRow("Token Bytes:", "${comp.tokenEncodedBytes} bytes")
                MetricRow("Phoneme Bytes:", "${comp.phonemeEncodedBytes} bytes")
                MetricRow("Compression:", "${String.format("%.1f", comp.compressionPercentage)}%", RetroGreen)
                MetricRow("Packets:", "${comp.packetCount}")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Phase 25/26 A/B experiment: only measured trials appear; relevance
        // stays "unrated" until a human rating is attached.
        if (uiState.experimentSummary.isNotEmpty()) {
            MetricSection("A/B EXPERIMENT (measured)") {
                for (arm in uiState.experimentSummary) {
                    Text(
                        text = "ARM ${arm.armId} — n=${arm.sampleCount}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RetroAmber,
                        letterSpacing = 1.sp
                    )
                    if (arm.sampleCount == 0) {
                        MetricRow("Trials:", "no trials yet")
                    } else {
                        MetricRow("Success:", "${arm.successCount}/${arm.sampleCount} (${String.format("%.1f", arm.successRate * 100)}%)",
                            if (arm.successRate < 1.0) RetroRed else RetroGreen)
                        MetricRow("Encoded avg:", String.format("%.0f bytes", arm.meanEncodedBytes))
                        MetricRow("Compression avg:", String.format("%.1f%%", arm.meanCompressionPercentage))
                        MetricRow("Encode avg:", String.format("%.1f ms", arm.meanEncodeMs))
                        MetricRow("Decode avg:", String.format("%.1f ms", arm.meanDecodeMs))
                        MetricRow("Round-trip avg:", String.format("%.1f ms", arm.meanTotalMs))
                        if (arm.relevanceCount > 0) {
                            MetricRow("Relevance:", "${arm.relevanceCount} rated, mean ${String.format("%.2f", arm.relevanceMean ?: 0f)}/5")
                        } else {
                            MetricRow("Relevance:", "unrated", RetroGray)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Phase 13 progressive stage: real preview + measured quality of the
        // last send. Quality is computed from the actual strings involved.
        MetricSection("PROGRESSIVE STAGE (P13, measured)") {
            if (uiState.progressivePreview.isEmpty()) {
                MetricRow("Preview:", "(none yet — send a message)")
            } else {
                MetricRow("Preview budget:", "${uiState.progressivePreviewBytes} B")
                Text(
                    text = "\u201C${uiState.progressivePreview}\u201D",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroCyan,
                    modifier = Modifier.padding(top = 2.dp)
                )
                MetricRow("Measured quality:", String.format("%.1f%%", uiState.progressiveQuality * 100), RetroGreen)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Phase 19-24 ops/power/models dashboard — every figure is a real
        // reading (mode controller, battery API, model file probe).
        MetricSection("OPS & POWER (P19-P24, real readings)") {
            MetricRow("Operation mode:", uiState.opMode.name,
                if (uiState.emergencyActive) RetroRed else RetroCyan)
            if (uiState.opModeHistory.isNotEmpty()) {
                MetricRow("Last switch:", uiState.opModeHistory.last(), RetroGray)
            }
            if (uiState.emergencyActive) {
                MetricRow("EMERGENCY:", uiState.emergencyReason ?: "active", RetroRed)
            } else {
                MetricRow("Emergency:", "none raised")
            }
            MetricRow("Emergency logbook:", "${uiState.emergencyClosedCount} closed", RetroGray)
            val battery = if (uiState.batteryPercentRaw >= 0) "${uiState.batteryPercentRaw}%" else "unknown (API)"
            MetricRow("Battery:", battery)
            MetricRow("Power profile:", uiState.powerProfile,
                if (uiState.powerProfile == "CRITICAL") RetroRed
                else if (uiState.powerProfile == "LOW") RetroAmber else RetroGreen)
            MetricRow("Models usable:", "${uiState.modelsUsable}/${uiState.modelsTotal}",
                if (uiState.modelsUsable == 0) RetroOrange else RetroGreen)
            MetricRow("TTS announce:", "${if (uiState.announceAudio) "ON" else "OFF"} — ${uiState.ttsSuppressedSpeeches} suppressed",
                if (uiState.ttsSuppressedSpeeches > 0) RetroAmber else RetroCyan)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Phase 27 benchmark: ran here on the JVM, every iteration measured.
        MetricSection("BENCHMARK (P27, measured on this device)") {
            Button(
                onClick = { viewModel.runBenchmark() },
                enabled = !uiState.benchmarkRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroSurface,
                    contentColor = RetroAmber
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            ) {
                Text(
                    text = if (uiState.benchmarkRunning) "MEASURING…" else "RUN BENCHMARK (30 iter/codec)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            if (uiState.benchmarkResults.isNotEmpty()) {
                for (r in uiState.benchmarkResults) {
                    Text(
                        text = "${r.name.uppercase()} — n=${r.iterations}, success ${String.format("%.0f%%", r.successRate * 100)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RetroAmber,
                        letterSpacing = 1.sp
                    )
                    MetricRow("Encode p95:", "${r.encodeStats.p95Ms} ms (mean ${String.format("%.1f", r.encodeStats.meanMs)})")
                    MetricRow("Decode p95:", "${r.decodeStats.p95Ms} ms (mean ${String.format("%.1f", r.decodeStats.meanMs)})")
                    MetricRow("Round-trip p95:", "${r.totalStats.p95Ms} ms", RetroAmber)
                    MetricRow("Encoded avg:", String.format("%.0f B", r.meanEncodedBytes))
                    MetricRow("Compression avg:", String.format("%.1f%%", r.meanCompressionPercentage), RetroGreen)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            } else {
                MetricRow("Results:", "press RUN BENCHMARK", RetroGray)
            }
        }
    }
}

@Composable
fun MetricSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = RetroAmber,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, valueColor: Color = RetroCyan) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = RetroGray
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}
