package com.example.itantra.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.codec.Language
import com.example.itantra.ui.theme.*

@Composable
fun TalkScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        RetroHeader()

        Spacer(modifier = Modifier.height(16.dp))

        // Status Panel
        StatusPanel(uiState)

        Spacer(modifier = Modifier.height(16.dp))

        // Talk Button
        TalkButton(
            isRecording = uiState.isRecording,
            isPTTMode = uiState.isPTTMode,
            onStartRecording = { viewModel.startRecording() },
            onStopRecording = { viewModel.stopRecording() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Last Message
        LastMessagePanel(uiState)

        Spacer(modifier = Modifier.weight(1f))

        // Navigation Tabs
        BottomTabs(
            activeTab = uiState.activeTab,
            onTabSelected = { viewModel.setActiveTab(it) }
        )
    }
}

@Composable
fun RetroHeader() {
    Text(
        text = "RETRO VOICE LINK",
        fontFamily = FontFamily.Monospace,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = RetroAmber,
        letterSpacing = 2.sp
    )
}

@Composable
fun StatusPanel(uiState: MainViewModel.UIState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatusRow("LINK:", if (uiState.isConnected) "CONNECTED" else "DISCONNECTED",
                if (uiState.isConnected) StatusConnected else StatusDisconnected)
            StatusRow("MODE:", uiState.mode, RetroCyan)
            StatusRow("LANG:", uiState.currentLanguage.displayName, RetroAmber)
            StatusRow("CODEC:", if (uiState.mode == "RETRO") "RETRO-SC-01" else "UTF-8-BASE", RetroCyan)

            Spacer(modifier = Modifier.height(8.dp))

            // Signal strength
            SignalIndicator(uiState.signalStrength)

            // Compression
            if (uiState.metrics != null) {
                CompressionIndicator(uiState.compressionPercent)
            }

            // Latency
            if (uiState.latencyMs > 0) {
                StatusRow("LATENCY:", "${uiState.latencyMs} ms", RetroAmber)
            }

            // Packets
            StatusRow("PKTS:", "${uiState.packetCount.first} TX / ${uiState.packetCount.second} RX", RetroCyan)
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = RetroGray
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
fun SignalIndicator(strength: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SIGNAL:",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = RetroGray
        )
        Row {
            for (i in 1..8) {
                val color = when {
                    i <= strength / 12 -> SignalStrong
                    i <= strength / 6 -> SignalMedium
                    else -> RetroDarkGray
                }
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height((8 + i * 2).dp)
                        .padding(horizontal = 1.dp)
                        .background(color, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

@Composable
fun CompressionIndicator(percent: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "COMPRESSION:",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = RetroGray
        )
        Text(
            text = "${String.format("%.1f", percent)}%",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (percent > 0) RetroGreen else RetroAmber
        )
    }
}

@Composable
fun TalkButton(
    isRecording: Boolean,
    isPTTMode: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) RetroRed else RetroAmber,
        label = "buttonColor"
    )

    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(buttonColor.copy(alpha = if (isRecording) pulseAlpha else 1f))
            .border(3.dp, RetroAmberLight, RoundedCornerShape(100.dp))
            .clickable {
                if (isRecording) onStopRecording() else onStartRecording()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = null,
                tint = RetroBackground,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isRecording) "STOP" else "HOLD TO TALK",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = RetroBackground
            )
        }
    }
}

@Composable
fun LastMessagePanel(uiState: MainViewModel.UIState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "LAST MESSAGE:",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = RetroGray
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (uiState.lastSentText.isNotBlank()) {
                Text(
                    text = "TX: ${uiState.lastSentText}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = RetroGreen,
                    maxLines = 2
                )
            }

            if (uiState.lastReceivedText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "RX: ${uiState.lastReceivedText}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = RetroCyan,
                    maxLines = 2
                )
            }

            if (uiState.lastSentText.isBlank() && uiState.lastReceivedText.isBlank()) {
                Text(
                    text = "No messages yet",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = RetroDarkGray
                )
            }
        }
    }
}

@Composable
fun BottomTabs(activeTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RetroSurface)
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabItem("LINK", Icons.Default.Home, activeTab == 0) { onTabSelected(0) }
        TabItem("METRICS", Icons.Default.Analytics, activeTab == 1) { onTabSelected(1) }
        TabItem("EXPERIMENT", Icons.Default.Science, activeTab == 2) { onTabSelected(2) }
        TabItem("CONFIG", Icons.Default.Settings, activeTab == 3) { onTabSelected(3) }
    }
}

@Composable
fun TabItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isActive: Boolean, onClick: () -> Unit) {
    val color = if (isActive) RetroAmber else RetroGray
    Column(
        modifier = Modifier
            .padding(12.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = color
        )
    }
}
