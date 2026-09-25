package com.example.itantra.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.transport.TransportType
import com.example.itantra.ui.main.MainViewModel.ActivityKind
import com.example.itantra.ui.main.MainViewModel.PacketActivity
import com.example.itantra.ui.theme.*

@Composable
fun DemoModeScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val lane by viewModel.packetActivity.collectAsState()
    val report = uiState.lastSendReport

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "SIH DEMO MODE",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )
        Text(
            text = "offline voice-relay: 10 languages · low-bandwidth codec · RETRO reliability · mesh flood",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RetroGray
        )

        Spacer(modifier = Modifier.height(16.dp))

        MetricSection("AUTOPILOT (REAL PIPELINE)") {
            MetricRow("Transport:", uiState.transportMode.name,
                if (uiState.isConnected) RetroGreen else RetroOrange)
            MetricRow("Language:", uiState.currentLanguage.displayName +
                " [${uiState.currentLanguage.code}]", RetroCyan)
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
                DemoAction("EMERGENCY", RetroRed) {
                    viewModel.sendEmergency()
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("SELF-CHECK (MEASURED, NOT STAGED)") {
            MetricRow("Link:", if (uiState.isConnected) "CONNECTED" else "DISCONNECTED",
                if (uiState.isConnected) RetroGreen else RetroRed)
            MetricRow("Handshake:", "proto v${uiState.protocolVersion} · codec v${uiState.codecVersion}",
                if (uiState.protocolVersion > 0) RetroGreen else RetroOrange)
            MetricRow("Peer:", uiState.remoteDeviceId.ifBlank { "—" }, RetroCyan)
            MetricRow("Offline codes:", "${uiState.supportedLanguages.size} of 10", RetroCyan)
            MetricRow("STT models usable:", "${uiState.modelsUsable}/${uiState.modelsTotal}",
                if (uiState.modelsUsable > 0) RetroGreen else RetroOrange)
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
                MetricRow("Network:", if (report.overNetwork) "${report.networkLatencyMs} ms" else "loopback", RetroGray)
                MetricRow("RTT:", if (report.roundTripTimeMs > 0) "${report.roundTripTimeMs} ms" else "NOT MEASURED", RetroGray)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("TOPOLOGY") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                NodeBox("LOCAL", uiState.role, if (uiState.isConnected) RetroGreen else RetroOrange)
                NodeBox("REMOTE", uiState.remoteDeviceId.ifBlank { "—" },
                    if (uiState.isConnected) RetroGreen else RetroGray)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricRow("Edge:", "${uiState.transportMode.name.ifEmpty { "SIMULATED" }} (${uiState.subStatusLabel})",
                    RetroAmber)
                MetricRow("Loss/RTT:", "${uiState.linkMetrics.packetLoss} · ${uiState.linkMetrics.roundTripTimeMs} ms",
                    RetroGray)
            }
            if (uiState.transportMode == TransportType.MESH) {
                ModelNoteRow("MESH:", "flooding overlay over edge transports; neighbor discovery not built — real coverage requires mesh nodes", RetroGray)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("LIVE PACKET LANE (LAST ${lane.size})") {
            if (lane.isEmpty()) {
                ModelNoteRow("—", "no transport events yet — press SIM LINK then SEND SAMPLE", RetroGray)
            } else {
                for (activity in lane) {
                    PacketLaneRow(activity)
                }
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
private fun RowScope.NodeBox(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroGray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
    }
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