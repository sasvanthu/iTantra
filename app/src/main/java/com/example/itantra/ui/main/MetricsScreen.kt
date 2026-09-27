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
            text = "iTantra METRICS",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        fun msLocal(v: Long): String? = if (v > 0) "$v ms [MEASURED]" else null
        fun msNet(v: Long): String = when {
            uiState.isConnected && v > 0 -> "$v ms [MEASURED]"
            uiState.simulationEnabled && v > 0 -> "$v ms [SIMULATED]"
            else -> "PHYSICAL TEST PENDING"
        }
        fun b(v: Int): String? = if (v > 0) "$v B" else null

        // Active Transport & Route Info
        MetricSection("ACTIVE TRANSPORT & ROUTE") {
            MetricRow("Selected Transport:", uiState.transportMode.name, RetroAmber)
            MetricRow("Voice Route Status:", uiState.voiceTransportRoute,
                if (uiState.voiceTransportRoute.contains("WI-FI")) RetroGreen else RetroCyan)
            MetricRow("Physical Peer Status:", if (uiState.isConnected) "CONNECTED" else "PHYSICAL TEST PENDING",
                if (uiState.isConnected) RetroGreen else RetroGray)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Latency Section — strictly distinguishes MEASURED, SIMULATED, TARGET, and PHYSICAL TEST PENDING
        MetricSection("LATENCY (MEASURED vs TARGET)") {
            LabeledMetricRow("STT capture:", msLocal(metrics?.latency?.sttLatencyMs ?: 0L), "< 1200 ms")
            LabeledMetricRow("Encoding (codec):", msLocal(metrics?.latency?.encodingLatencyMs ?: 0L), "< 15 ms")
            LabeledMetricRow("Packetization:", msLocal(metrics?.latency?.packetizationLatencyMs ?: 0L), "< 5 ms")
            LabeledMetricRow("Transport TX:", msNet(metrics?.latency?.transportLatencyMs ?: 0L), "< 150 ms")
            LabeledMetricRow("Network receive:", msNet(metrics?.latency?.networkReceiveLatencyMs ?: 0L), "< 25 ms")
            LabeledMetricRow("ACK / RTT:", msNet(metrics?.latency?.roundTripTimeMs ?: 0L), "< 200 ms", RetroAmber)
            LabeledMetricRow("Decoding:", msLocal(metrics?.latency?.decodingLatencyMs ?: 0L), "< 15 ms")
            LabeledMetricRow("TTS synthesis:", msLocal(metrics?.latency?.ttsLatencyMs ?: 0L), "< 200 ms")
            LabeledMetricRow("Total speech path:", msLocal(metrics?.latency?.totalLatencyMs ?: 0L), "< 1500 ms", RetroAmber)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Size Section
        MetricSection("BANDWIDTH & COMPRESSION") {
            val compVal = metrics?.size?.compressionPercentage ?: 0.0
            val compStr = if (compVal > 0.0) String.format("%.1f%%", compVal) else null
            LabeledMetricRow("Compression ratio:", compStr, "> 50.0%",
                if (compVal > 0) RetroGreen else RetroRed)
            LabeledMetricRow("Original UTF-8:", b(metrics?.size?.originalUtf8Bytes ?: 0), "input size")
            LabeledMetricRow("Token Encoded:", b(metrics?.size?.tokenEncodedBytes ?: 0), "< original")
            LabeledMetricRow("Final Encoded:", b(metrics?.size?.finalEncodedBytes ?: 0), "< original")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Transport Section
        MetricSection("TRANSPORT RELIABILITY") {
            val loss = metrics?.transport?.packetLoss ?: 0
            val retr = metrics?.transport?.retransmissions ?: 0
            LabeledMetricRow("Packets Sent:", "${metrics?.transport?.packetsSent ?: 0}", "session total")
            LabeledMetricRow("Packets Received:", "${metrics?.transport?.packetsReceived ?: 0}", "session total")
            LabeledMetricRow("Retransmissions:", "$retr", "0 (ideal)",
                if (retr > 0) RetroAmber else RetroGreen)
            LabeledMetricRow("Packet Loss:", "$loss", "0 (reliable)",
                if (loss > 0) RetroRed else RetroGreen)
            MetricRow("TX Wire Bytes:", "${uiState.linkMetrics.transmittedBytes} B")
            MetricRow("RX Wire Bytes:", "${uiState.linkMetrics.receivedBytes} B")
            MetricRow("Duplicate frames:", "${uiState.linkMetrics.duplicatePackets}",
                if (uiState.linkMetrics.duplicatePackets > 0) RetroAmber else RetroGreen)
            MetricRow("Corrupted frames:", "${uiState.linkMetrics.corruptedFrames}",
                if (uiState.linkMetrics.corruptedFrames > 0) RetroRed else RetroGreen)
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
                MetricRow("STT:", if (rep.sttMeasured) "${rep.sttLatencyMs} ms" else "NOT MEASURED", RetroGray)
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
            MetricRow("CPU:", "NOT MEASURED", RetroGray)
            MetricRow("Throughput:", if ((metrics?.transport?.throughput ?: 0.0) > 0)
                "${String.format("%.1f", metrics!!.transport.throughput)} B/s" else "NOT MEASURED", RetroGray)
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

@Composable
fun LabeledMetricRow(
    label: String,
    measured: String?,
    target: String? = null,
    valueColor: Color = RetroCyan
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = RetroGray
            )
            if (measured != null && measured.isNotBlank()) {
                Text(
                    text = "[MEASURED: $measured]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
            } else {
                Text(
                    text = "[NOT AVAILABLE]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroDarkGray.copy(alpha = 0.8f)
                )
            }
        }
        if (target != null) {
            Text(
                text = "  ↳ TARGET: $target",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = RetroGray.copy(alpha = 0.7f)
            )
        }
    }
}
