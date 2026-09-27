package com.example.itantra.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
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
import com.example.itantra.transport.TransportType
import com.example.itantra.ui.components.LiveEventLogPanel
import com.example.itantra.ui.components.SecurityPanel
import com.example.itantra.ui.main.MainViewModel.ActivityKind
import com.example.itantra.ui.main.MainViewModel.PacketActivity
import com.example.itantra.ui.map.CampusCommunicationMap
import com.example.itantra.ui.theme.*

@Composable
fun DemoModeScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val lane by viewModel.packetActivity.collectAsState()
    val demoState = uiState.demoSequenceState
    val report = uiState.lastSendReport

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "iTantra DEMO MODE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroAmber,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "SIH JUDGE VERIFICATION SUITE — 10-STEP PIPELINE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroGray
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(RetroSurface)
                    .border(1.dp, RetroAmber, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "JUDGE READY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroAmber
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ------------------------------------------------------------------
        // SECTION 6 & 7: 10-STEP VERIFIED DEMONSTRATION SEQUENCE
        // ------------------------------------------------------------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, RetroAmber.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = RetroSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "10-STEP DEMO SEQUENCE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RetroAmber,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (demoState.isRunning) "RUNNING..." else "READY",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (demoState.isRunning) RetroAmber else RetroGreen
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(RetroBackground)
                        .border(1.dp, RetroDarkGray.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = demoState.overallStatus,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (demoState.error != null) RetroRed else RetroCyan
                    )
                }

                if (demoState.error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(RetroRed.copy(alpha = 0.2f))
                            .border(1.dp, RetroRed, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = RetroRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Actionable Error: ${demoState.error} — tap RETRY STEP",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = RetroRed
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Control Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { viewModel.runDemoSequence(isEmergency = false) },
                        enabled = !demoState.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = RetroGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("RUN 10 STEPS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RetroBackground)
                    }

                    Button(
                        onClick = { viewModel.runDemoSequence(isEmergency = true) },
                        enabled = !demoState.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("EMERGENCY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RetroWhite)
                    }

                    OutlinedButton(
                        onClick = { viewModel.stepNextDemo() },
                        enabled = !demoState.isRunning,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RetroCyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RetroCyan),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("STEP NEXT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    IconButton(
                        onClick = { viewModel.resetDemo() },
                        modifier = Modifier
                            .size(36.dp)
                            .background(RetroDarkGray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Demo", tint = RetroAmber, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // The 10 Steps Cards
                demoState.steps.forEach { step ->
                    DemoStepRow(step)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ------------------------------------------------------------------
        // PROTOTYPE CAMPUS COMMUNICATION MAP & MULTI-HOP CONTROL
        // ------------------------------------------------------------------
        CampusCommunicationMap(
            relayState = uiState.multiHopState,
            onRunRelay = { viewModel.runMultiHopSimulation() },
            onTestDuplicate = { viewModel.testMultiHopDuplicate() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ------------------------------------------------------------------
        // SECURITY & AES-256-GCM INTEGRITY INSPECTOR
        // ------------------------------------------------------------------
        SecurityPanel(
            lastPacket = uiState.lastSecurePacket,
            onRunSecurityTest = { viewModel.runSecurityTest() },
            securityReport = uiState.securityReport
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ------------------------------------------------------------------
        // LIVE COMMUNICATION EVENT LOG (SIMULATED GPS & AUDIT STREAM)
        // ------------------------------------------------------------------
        LiveEventLogPanel(
            events = uiState.eventRecords,
            onClearLogs = { viewModel.clearEventLogs() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ------------------------------------------------------------------
        // AUTOPILOT & PHYSICAL TEST DISCLOSURE
        // ------------------------------------------------------------------
        MetricSection("AUTOPILOT (INTERACTIVE HOOKS)") {
            MetricRow("Active Transport:", uiState.transportMode.name,
                if (uiState.isConnected) RetroGreen else RetroOrange)
            MetricRow("Language:", "${uiState.currentLanguage.displayName} [${uiState.currentLanguage.code}]", RetroCyan)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DemoAction("SIM LINK", RetroCyan) {
                    viewModel.setTransportMode(TransportType.SIMULATED)
                    viewModel.startHost()
                }
                DemoAction("SEND SAMPLE", RetroGreen) {
                    viewModel.sendManual("EVACUATION NEEDED: 27 INJURED, ROUTE 4N1 BLOCKED")
                }
                DemoAction("EMERGENCY SOS", RetroRed) {
                    viewModel.sendEmergency()
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("STATUS & INTEGRITY CHECK") {
            MetricRow("Transport Mode:", if (uiState.transportMode == TransportType.SIMULATED) "SIMULATION ACTIVE" else uiState.transportMode.name,
                if (uiState.transportMode == TransportType.SIMULATED) RetroOrange else RetroGreen)
            MetricRow("Physical Multi-Peer:", "PENDING ADDITIONAL HARDWARE", RetroGray)
            MetricRow("Handshake:", "proto v${uiState.protocolVersion} · codec v${uiState.codecVersion}",
                if (uiState.protocolVersion > 0) RetroGreen else RetroOrange)
            MetricRow("Speech Pipeline:", "100% OFFLINE (Vosk + Formant PCM)", RetroGreen)
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (report != null) {
            MetricSection("LAST SEND REPORT") {
                MetricRow("Result:", report.detail, if (report.failed) RetroRed else RetroGreen)
                MetricRow("Bytes:", "${report.originalUtf8Bytes} → ${report.encodedBytes} B",
                    if (report.compressionPercentage > 0) RetroGreen else RetroCyan)
                MetricRow("Compression:", "${String.format("%.1f", report.compressionPercentage)}%", RetroCyan)
                MetricRow("Packets:", "${report.packetCount}", RetroCyan)
                MetricRow("STT:", if (report.sttMeasured) "${report.sttLatencyMs} ms" else "NOT MEASURED", RetroGray)
                MetricRow("Encode/Pkt:", "${report.encodeLatencyMs} / ${report.packetizeLatencyMs} ms", RetroGray)
                MetricRow("Network:", if (report.overNetwork) "${report.networkLatencyMs} ms" else "loopback [SIMULATION]", RetroGray)
                MetricRow("RTT:", if (report.roundTripTimeMs > 0) "${report.roundTripTimeMs} ms" else "NOT MEASURED", RetroGray)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("LIVE PACKET LANE (LAST ${lane.size})") {
            if (lane.isEmpty()) {
                ModelNoteRow("—", "no transport events yet — tap RUN 10 STEPS above", RetroGray)
            } else {
                for (activity in lane) {
                    PacketLaneRow(activity)
                }
            }
        }
    }
}

@Composable
private fun DemoStepRow(step: MainViewModel.DemoStepInfo) {
    val statusColor = when (step.status) {
        "PASS" -> RetroGreen
        "ACTIVE" -> RetroAmber
        "FAIL" -> RetroRed
        else -> RetroGray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (step.status == "ACTIVE") RetroAmber else RetroDarkGray.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (step.status == "ACTIVE") RetroSurfaceVariant else RetroBackground
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "STEP ${step.stepNumber}: ${step.title}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (step.status == "ACTIVE") RetroAmber else RetroWhite
                    )
                    if (step.isSimulation) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(RetroOrange.copy(alpha = 0.2f))
                                .border(1.dp, RetroOrange, RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "SIMULATION",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = RetroOrange
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor.copy(alpha = 0.2f))
                        .border(1.dp, statusColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = step.status,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = step.description,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = RetroGray
            )

            if (step.value.isNotBlank() && step.value != "—") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = step.value,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (step.status == "FAIL") RetroRed else if (step.isSimulation) RetroOrange else RetroCyan
                )
            }
        }
    }
}

@Composable
private fun RowScope.DemoAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = RetroBackground,
        modifier = Modifier
            .weight(1f)
            .background(color, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun PacketLaneRow(activity: PacketActivity) {
    val color = when (activity.kind) {
        ActivityKind.TX -> RetroCyan
        ActivityKind.RX -> RetroGreen
        ActivityKind.LINK -> RetroAmber
        ActivityKind.ALERT -> RetroRed
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(activity.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, color = color)
        Spacer(modifier = Modifier.width(8.dp))
        Text(activity.detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = RetroWhite)
    }
}