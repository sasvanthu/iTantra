package com.example.itantra.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.SpeechPipeline
import com.example.itantra.codec.CodecLabResult
import com.example.itantra.transport.ConnectionStatus
import com.example.itantra.transport.TransportType
import com.example.itantra.ui.theme.*

@Composable
fun CommunicationScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RetroHeader()

        if (uiState.simulationEnabled) {
            Text(
                text = "● SIMULATION",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroOrange,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        TransportModeRow(uiState.transportMode) { viewModel.setTransportMode(it) }

        Spacer(modifier = Modifier.height(12.dp))

        LinkPanel(uiState, viewModel)

        Spacer(modifier = Modifier.height(12.dp))

        SessionPanel(uiState)

        Spacer(modifier = Modifier.height(12.dp))

        MessageStateLine(uiState.messageInfo, viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        TalkButton(
            isRecording = uiState.isRecording,
            isPTTMode = uiState.isPTTMode,
            onStartRecording = { viewModel.startRecording() },
            onStopRecording = { viewModel.stopRecording() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PTTToggle(uiState.isPTTMode) { viewModel.togglePTT() }

        Spacer(modifier = Modifier.height(12.dp))

        ManualSendPanel(uiState, viewModel)

        Spacer(modifier = Modifier.height(12.dp))

        SendReportPanel(uiState.lastSendReport, uiState.lastSentText)

        Spacer(modifier = Modifier.height(12.dp))

        ReceivedPanel(uiState)

        if (uiState.speechLoopback != null) {
            Spacer(modifier = Modifier.height(12.dp))
            SpeechLoopbackPanel(uiState.speechLoopback!!)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
internal fun TransportModeRow(current: TransportType, onSelect: (TransportType) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroDarkGray, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportType.entries.forEach { mode ->
                val label = when (mode) {
                    TransportType.WIFI -> "WIFI"
                    TransportType.SIMULATED -> "SIM"
                    TransportType.BLUETOOTH -> "BT"
                    TransportType.MESH -> "MESH"
                }
                val active = current == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) RetroAmber else RetroBackground)
                        .border(1.dp, if (active) RetroAmber else RetroDarkGray, RoundedCornerShape(4.dp))
                        .clickable { onSelect(mode) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) RetroBackground else RetroWhite
                    )
                }
            }
        }
    }
}

@Composable
fun LinkPanel(uiState: MainViewModel.UIState, viewModel: MainViewModel) {
    var ipInput by remember { mutableStateOf("") }
    val connected = uiState.isConnected
    val isBtMode = uiState.transportMode == TransportType.BLUETOOTH
    val isMeshMode = uiState.transportMode == TransportType.MESH
    val blankHostAllowed = isBtMode || isMeshMode
    val statusColor = when (uiState.connectionStatus) {
        ConnectionStatus.CONNECTED -> StatusConnected
        ConnectionStatus.WAITING, ConnectionStatus.CONNECTING -> StatusConnecting
        else -> StatusDisconnected
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "RETRO VOICE LINK // CH 0",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RetroAmber,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            StatusRow("ROLE:", uiState.role, RetroCyan)
            StatusRow("STATUS:", uiState.subStatusLabel, statusColor)
            if (uiState.ipAddress.isNotBlank()) {
                StatusRow("LOCAL:", uiState.ipAddress, RetroCyan)
            }
            if (connected) {
                StatusRow("REMOTE:", uiState.remoteDeviceId.ifBlank { "—" }, RetroGreen)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                RoleOption("HOST", uiState.isServer) {
                    viewModel.setPort(uiState.port)
                    viewModel.startHost()
                }
                Spacer(modifier = Modifier.width(8.dp))
                RoleOption("DEVICE", !uiState.isServer && !connected) { /* typing below */ }
            }

            if (!connected) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(
                        when {
                            isBtMode -> "peer BT address (blank = auto scan)"
                            isMeshMode -> "peer IP for Wi-Fi (BLE auto-scans)"
                            else -> "peer IP address"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = RetroGray
                    ) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = RetroCyan
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RetroAmber,
                        unfocusedBorderColor = RetroDarkGray
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.connectToHost(ipInput) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ipInput.isNotBlank() || blankHostAllowed,
                    colors = ButtonDefaults.buttonColors(containerColor = RetroGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "CONNECT",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = RetroBackground
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "DISCONNECT",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = RetroWhite
                    )
                }
            }

            if (uiState.linkError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ ${uiState.linkError}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroRed
                )
            }
            if (uiState.linkStatusLine.isNotEmpty() && uiState.linkError == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.linkStatusLine,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroGreen
                )
            }
        }
    }
}

@Composable
fun SessionPanel(uiState: MainViewModel.UIState) {
    if (uiState.sessionId.isBlank()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SESSION",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RetroCyan,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusRow("SESSION:", uiState.sessionId, RetroAmber)
            StatusRow("LANG(S):", uiState.supportedLanguages.joinToString(", ").ifBlank { "—" }, RetroCyan)
            StatusRow("PROTOCOL:", "v${uiState.protocolVersion}", RetroCyan)
            StatusRow("CODEC:", "v${uiState.codecVersion}", RetroCyan)
        }
    }
}

@Composable
fun MessageStateLine(messageInfo: com.example.itantra.transport.MessageInfo, viewModel: MainViewModel) {
    val stateColor = when {
        messageInfo.failed -> RetroRed
        messageInfo.isEmergency -> RetroRed
        messageInfo.state == com.example.itantra.transport.MessageState.COMPLETE -> RetroGreen
        messageInfo.state == com.example.itantra.transport.MessageState.IDLE -> RetroDarkGray
        else -> RetroAmber
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (messageInfo.isEmergency && messageInfo.state != com.example.itantra.transport.MessageState.IDLE) {
                Text(
                    text = "🚨 CRITICAL TRANSMISSION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroRed,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            StatusRow("MSG STATE:", messageInfo.state.name, stateColor)
            if (messageInfo.detail.isNotEmpty()) {
                StatusRow("DETAIL:", messageInfo.detail, RetroGray)
            }
            if (messageInfo.packetCount > 0) {
                StatusRow("ACKS:", "${messageInfo.acknowledgedPackets}/${messageInfo.packetCount}", RetroCyan)
            }
        }
    }
}

@Composable
fun PTTToggle(isPTTMode: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(RetroSurface)
            .border(1.dp, RetroDarkGray, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "PUSH-TO-TALK",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = RetroWhite
        )
        Switch(
            checked = isPTTMode,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = RetroAmber,
                uncheckedTrackColor = RetroDarkGray
            )
        )
    }
}

@Composable
fun ManualSendPanel(uiState: MainViewModel.UIState, viewModel: MainViewModel) {
    val manualText by viewModel.manualText.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "MANUAL TRANSMISSION",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroAmber,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row {
                com.example.itantra.codec.Language.entries
                    .filter { it != com.example.itantra.codec.Language.UNKNOWN }
                    .forEach { lang ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (uiState.currentLanguage == lang) RetroAmber else RetroSurface)
                            .clickable { viewModel.setLanguage(lang) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.currentLanguage == lang) RetroBackground else RetroGray
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = manualText,
                onValueChange = { viewModel.setManualText(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("type a message to send", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = RetroGray) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = RetroGreen
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RetroAmber,
                    unfocusedBorderColor = RetroDarkGray
                ),
                singleLine = false,
                minLines = 2
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                Button(
                    onClick = { viewModel.sendManual() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroCyan),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("SEND", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = RetroBackground)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.sendEmergency() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("🚨 EMERGENCY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = RetroWhite)
                }
            }
        }
    }
}

@Composable
fun SendReportPanel(report: SpeechPipeline.SpeechSendReport?, lastSentText: String) {
    if (report == null) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "LAST TX REPORT",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroAmber,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusRow("TEXT:", report.text.take(36), RetroGreen)
            StatusRow("UTF-8:", "${report.originalUtf8Bytes} B", RetroGray)
            StatusRow("CODEC PAYLOAD:", "${report.encodedBytes} B", RetroCyan)
            StatusRow("TRANSMITTED:", "${report.transmittedBytes} B (incl. frames)", RetroAmber)
            StatusRow("NSA COMPARISON:", when {
                report.originalUtf8Bytes == 0 -> "—"
                report.transmittedBytes <= report.originalUtf8Bytes ->
                    "NET-BREAK-EVEN ${String.format("%.1f", 100.0 * (1.0 - report.transmittedBytes.toDouble() / report.originalUtf8Bytes))}% SMALLER"
                else ->
                    "NET OVERHEAD +${report.transmittedBytes - report.originalUtf8Bytes} B"
            }, if (report.transmittedBytes <= report.originalUtf8Bytes) RetroGreen else RetroOrange)
            StatusRow("PKTS:", "${report.packetCount}", RetroCyan)
            StatusRow("RETRIES:", "${report.retransmissions}", if (report.retransmissions > 0) RetroAmber else RetroGreen)
            StatusRow("RTT:", "${report.roundTripTimeMs} ms", RetroCyan)
            StatusRow("NET LATENCY:", "${report.networkLatencyMs} ms", RetroCyan)
            StatusRow("TOTAL:", "${report.totalLatencyMs} ms", RetroAmber)
            StatusRow("RESULT:", when {
                report.failed -> "FAILED"
                report.overNetwork -> "✓ DELIVERED + ACK"
                else -> "LOCAL ROUND-TRIP"
            }, if (report.failed) RetroRed else RetroGreen)
        }
    }
}

@Composable
fun ReceivedPanel(uiState: MainViewModel.UIState) {
    if (uiState.receivedMessages.isEmpty()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "RECEIVED",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroCyan,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (msg in uiState.receivedMessages.takeLast(6)) {
                StatusRow(
                    if (msg.isEmergency) "🚨 RX:" else "RX:",
                    "${msg.text.take(48)} ${msg.language.code}",
                    if (msg.isEmergency) RetroRed else RetroGreen
                )
                if (msg.isEmergency) {
                    Text(
                        text = "🚨 CRITICAL TRANSMISSION",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = RetroRed
                    )
                }
            }
        }
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
            .size(180.dp)
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
                modifier = Modifier.size(44.dp)
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
fun SpeechLoopbackPanel(lb: CodecLabResult) {
    val ok = lb.exactMatch
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SPEECH → CODEC → DECODE",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroAmber,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            StatusRow("RECOGNIZED:", lb.originalText.ifBlank { "—" }, RetroGreen)
            StatusRow("UTF-8 / RETRO:", "${lb.originalUtf8Bytes} B / ${lb.encodedBytes} B", RetroCyan)
            StatusRow("MODE:", lb.modeLabel.let { if (it == "OVERHEAD") "OVERHEAD" else "$it ${String.format("%.1f", lb.compressionPercent)}%" },
                if (lb.compressionPercent > 0) RetroGreen else RetroOrange)
            StatusRow("PKTS:", "${lb.packetCount}", RetroCyan)
            StatusRow("DECODED:", lb.decodedText.ifBlank { "—" }, RetroGreen)
            StatusRow("INTEGRITY:", if (ok) "OK EXACT" else "FAILED", if (ok) RetroGreen else RetroRed)
            StatusRow("TOTAL:", "${lb.encodeMs + lb.packetizeMs + lb.decodeMs} ms (enc ${lb.encodeMs} / pkt ${lb.packetizeMs} / dec ${lb.decodeMs})", RetroAmber)
        }
    }
}

@Composable
fun BottomTabs(activeTab: Int, onTabSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RetroSurface)
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabItem("LINK", Icons.Default.Home, activeTab == 0) { onTabSelected(0) }
        TabItem("METRICS", Icons.Default.Analytics, activeTab == 1) { onTabSelected(1) }
        TabItem("CODEC", Icons.Default.Science, activeTab == 2) { onTabSelected(2) }
        TabItem("CONFIG", Icons.Default.Settings, activeTab == 3) { onTabSelected(3) }
    }
}

@Composable
fun TabItem(label: String, icon: ImageVector, isActive: Boolean, onClick: () -> Unit) {
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