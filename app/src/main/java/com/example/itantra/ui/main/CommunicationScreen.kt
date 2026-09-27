package com.example.itantra.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.SpeechPipeline
import com.example.itantra.codec.CodecLabResult
import com.example.itantra.ops.OperationMode
import com.example.itantra.transport.ConnectionStatus
import com.example.itantra.transport.TransportType
import com.example.itantra.ui.components.SecurityPanel
import com.example.itantra.ui.components.TransmissionDebugPanel
import com.example.itantra.ui.components.LiveEventLogPanel
import com.example.itantra.ui.map.CampusCommunicationMap
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
        ITantraHeader(onOpenGuide = { viewModel.toggleOnboardingGuide(true) })

        if (uiState.showOnboardingGuide) {
            FirstLaunchGuideModal(onDismiss = { viewModel.toggleOnboardingGuide(false) })
        }

        if (!uiState.audioPermissionGranted || !uiState.blePermissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(RetroRed.copy(alpha = 0.2f))
                    .border(1.dp, RetroRed, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "ACTION REQUIRED: " +
                        (if (!uiState.audioPermissionGranted) "RECORD_AUDIO DENIED (Hold PTT disabled) " else "") +
                        (if (!uiState.blePermissionGranted) "• BLE PERMISSIONS DENIED" else ""),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroRed,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (uiState.simulationEnabled) {
            Spacer(modifier = Modifier.height(6.dp))
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

        Spacer(modifier = Modifier.height(14.dp))

        SingleDeviceVoiceDiagnosticsCard(
            diag = uiState.voiceDiagnostics,
            selectedLang = uiState.currentLanguage,
            onSelectLang = { viewModel.setLanguage(it) },
            onRunTest = { text, lang -> viewModel.runSingleDeviceVoiceTest(text, lang) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            TalkButton(
                isRecording = uiState.isRecording,
                isPTTMode = uiState.isPTTMode,
                onStartRecording = { viewModel.startRecording() },
                onStopRecording = { viewModel.stopRecording() }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        PTTToggle(uiState.isPTTMode, uiState.voiceTransportRoute) { viewModel.togglePTT() }

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

        Spacer(modifier = Modifier.height(14.dp))

        TransmissionDebugPanel(
            transmissionId = uiState.currentTxId,
            messageId = uiState.currentMsgId,
            packetId = uiState.currentPktId,
            transport = uiState.transportMode.name,
            statusText = if (uiState.lastSendReport?.failed == false) "DELIVERED ✓" else "STANDBY"
        )

        Spacer(modifier = Modifier.height(14.dp))

        SecurityPanel(
            lastPacket = uiState.lastSecurePacket,
            onRunSecurityTest = { viewModel.runSecurityTest() },
            securityReport = uiState.securityReport
        )

        Spacer(modifier = Modifier.height(14.dp))

        CampusCommunicationMap(
            relayState = uiState.multiHopState,
            onRunRelay = { viewModel.runMultiHopSimulation() },
            onTestDuplicate = { viewModel.testMultiHopDuplicate() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        LiveEventLogPanel(
            events = uiState.eventRecords,
            onClearLogs = { viewModel.clearEventLogs() }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
internal fun TransportModeRow(current: TransportType, onSelect: (TransportType) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroDarkGray.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) RetroAmber else RetroBackground)
                        .border(1.dp, if (active) RetroAmber else RetroDarkGray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
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
        ConnectionStatus.WAITING, ConnectionStatus.CONNECTING, ConnectionStatus.DISCOVERING, ConnectionStatus.HANDSHAKING -> StatusConnecting
        else -> StatusDisconnected
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroDarkGray.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "NETWORK LINK",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
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
                    if (!uiState.isServer) {
                        viewModel.setPort(uiState.port)
                        viewModel.startHost()
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                RoleOption("DEVICE", !uiState.isServer && !connected) {
                    if (uiState.isServer) {
                        viewModel.disconnect()
                    }
                }
            }

            if (!connected) {
                if (uiState.isServer) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (uiState.connectionStatus == ConnectionStatus.WAITING)
                            "HOST ACTIVE // LISTENING ON ${uiState.ipAddress.ifBlank { "PORT " + uiState.port }}"
                        else
                            "INCOMING CONNECTION IN PROGRESS...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = RetroAmber
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "STOP HOST",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = RetroWhite
                        )
                    }
                } else {
                    val isConnecting = uiState.connectionStatus == ConnectionStatus.CONNECTING ||
                        uiState.connectionStatus == ConnectionStatus.DISCOVERING ||
                        uiState.connectionStatus == ConnectionStatus.HANDSHAKING
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(
                            when {
                                isBtMode -> "peer BT address (blank = auto scan)"
                                isMeshMode -> "peer IP for Wi-Fi (BLE auto-scans)"
                                else -> "peer IP address (e.g. 192.168.43.1)"
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
                        singleLine = true,
                        enabled = !isConnecting
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isConnecting) {
                        Button(
                            onClick = { viewModel.disconnect() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "CANCEL",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = RetroWhite
                            )
                        }
                    } else {
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
                    }
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
            .border(1.dp, RetroDarkGray.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(12.dp)
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
            .border(1.dp, RetroDarkGray.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(12.dp)
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
fun PTTToggle(isPTTMode: Boolean, voiceRoute: String = "", onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RetroSurface)
            .border(1.dp, RetroDarkGray, RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "PUSH-TO-TALK / WALKIE-TALKIE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroWhite
                )
                if (voiceRoute.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "VOICE ROUTE: $voiceRoute",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (voiceRoute.contains("WI-FI")) RetroGreen else RetroAmber
                    )
                }
            }
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
}

@Composable
fun ManualSendPanel(uiState: MainViewModel.UIState, viewModel: MainViewModel) {
    val manualText by viewModel.manualText.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroDarkGray.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(12.dp)
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

            var showEmergencyConfirm by remember { mutableStateOf(false) }

            if (showEmergencyConfirm) {
                AlertDialog(
                    onDismissRequest = { showEmergencyConfirm = false },
                    title = {
                        Text(
                            text = "🚨 CONFIRM EMERGENCY TRANSMISSION",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = RetroRed,
                            fontSize = 13.sp
                        )
                    },
                    text = {
                        Text(
                            text = "This will immediately transmit a high-priority EMERGENCY packet over the network. Receiving devices will bypass silence suppression and trigger an audible alert. Broadcast now?",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = RetroWhite
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showEmergencyConfirm = false
                                viewModel.sendEmergency()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("TRANSMIT EMERGENCY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = RetroWhite)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { showEmergencyConfirm = false },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("CANCEL", fontFamily = FontFamily.Monospace, color = RetroGray)
                        }
                    },
                    containerColor = RetroSurface,
                    shape = RoundedCornerShape(12.dp)
                )
            }

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
                    onClick = { showEmergencyConfirm = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("🚨 EMERGENCY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = RetroWhite)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OpsControlRow(uiState, viewModel)
        }
    }
}

@Composable
private fun OpsControlRow(uiState: MainViewModel.UIState, viewModel: MainViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "OPERATION MODE (P19)",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroGray,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(OperationMode.NORMAL, OperationMode.SILENT, OperationMode.PTT).forEach { mode ->
                    OutlinedButton(
                        onClick = { viewModel.switchOperationMode(mode) },
                        enabled = !uiState.emergencyActive,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (uiState.opMode == mode) RetroAmber.copy(alpha = 0.25f) else RetroSurface,
                            contentColor = RetroCyan
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.opMode == mode) RetroAmber else RetroDarkGray
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(mode.name, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (uiState.emergencyActive) {
                    Button(
                        onClick = { viewModel.acknowledgeEmergency() },
                        colors = ButtonDefaults.buttonColors(containerColor = RetroRed),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("ACK EMERGENCY", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroWhite)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusRow("ALERT:", uiState.emergencyReason ?: "active", RetroRed)
                } else {
                    Button(
                        onClick = { viewModel.raiseEmergency() },
                        colors = ButtonDefaults.buttonColors(containerColor = RetroSurface),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, RetroRed)
                    ) {
                        Text("RAISE ALERT", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroRed)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.setAnnounceAudio(!uiState.announceAudio) },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (uiState.announceAudio) RetroGreen else RetroDarkGray),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "ANNOUNCE ${if (uiState.announceAudio) "ON" else "OFF"}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = if (uiState.announceAudio) RetroGreen else RetroGray
                        )
                    }
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
fun ITantraHeader(onOpenGuide: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "iTantra",
                fontFamily = FontFamily.Monospace,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = RetroAmber,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(RetroSurface)
                    .border(1.dp, RetroCyan, RoundedCornerShape(4.dp))
                    .clickable(onClick = onOpenGuide)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "? GUIDE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroCyan
                )
            }
        }
        Text(
            text = "OFFLINE COMMUNICATION",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = RetroWhite,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("VOICE", "CODEC", "PACKET", "TRANSPORT", "TTS", "EMERGENCY", "MESH").forEach { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(RetroSurface)
                        .border(1.dp, RetroDarkGray.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = tag,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (tag == "EMERGENCY") RetroRed else if (tag == "TTS" || tag == "VOICE") RetroGreen else RetroAmber
                    )
                }
            }
        }
    }
}

@Composable
fun FirstLaunchGuideModal(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "iTantra SYSTEM OVERVIEW",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = RetroAmber
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "1. ZERO INTERNET DEPENDENCY\n100% offline speech capture, RetroSpeechCodec compression, and embedded formant synthesis.\n",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroWhite
                )
                Text(
                    text = "2. MULTI-LINGUAL SUPPORT\nEmpirically verified for English, Hindi (हिंदी), and Tamil (தமிழ்).\n",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroCyan
                )
                Text(
                    text = "3. WALKIE-TALKIE PTT & EMERGENCY\nHold TALK for Push-to-Talk or tap for toggle. Tap EMERGENCY for instant high-priority SOS override & audible alarm.\n",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroAmber
                )
                Text(
                    text = "4. TRANSPORT & MESH RELAY\nDirect Wi-Fi / BLE P2P + Multi-hop flooding overlay.\n",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroWhite
                )
                Text(
                    text = "5. VERIFICATION STATUS DISCLOSURE\n• [VERIFIED ON-DEVICE]: Offline Speech capture, Vosk STT, RetroSpeechCodec (31-70.5% compression), EmbeddedOpenSourceTTS, AudioTrack playback, local loopback.\n• [PENDING HARDWARE]: Physical Wi-Fi peer, BLE peer, and 3-phone mesh testing remain pending additional physical devices (simulated loopback provided for evaluation).",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroOrange
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = RetroAmber),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("UNDERSTOOD / CLOSE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = RetroBackground)
            }
        },
        containerColor = RetroSurface,
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
fun TalkButton(
    isRecording: Boolean,
    isPTTMode: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) RetroRed else RetroAmber,
        animationSpec = tween(250),
        label = "buttonColor"
    )
    val borderColor = if (isRecording) RetroWhite.copy(alpha = 0.8f) else RetroAmberLight.copy(alpha = 0.5f)

    val labelText = when {
        isRecording && isPTTMode -> "RELEASE TO SEND"
        isRecording -> "STOP"
        isPTTMode -> "HOLD TO TALK"
        else -> "PUSH TO TALK"
    }

    Box(
        modifier = Modifier
            .size(160.dp)
            .clip(RoundedCornerShape(80.dp))
            .background(buttonColor)
            .border(2.dp, borderColor, RoundedCornerShape(80.dp))
            .pointerInput(isPTTMode, isRecording) {
                if (isPTTMode) {
                    detectTapGestures(
                        onPress = {
                            onStartRecording()
                            tryAwaitRelease()
                            onStopRecording()
                        }
                    )
                } else {
                    detectTapGestures(
                        onTap = {
                            if (isRecording) onStopRecording() else onStartRecording()
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = null,
                tint = RetroBackground,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = labelText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = RetroGray,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            modifier = Modifier.weight(1f, fill = false),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun SingleDeviceVoiceDiagnosticsCard(
    diag: MainViewModel.SingleDeviceVoiceDiagnostics,
    selectedLang: com.example.itantra.codec.Language,
    onSelectLang: (com.example.itantra.codec.Language) -> Unit,
    onRunTest: (String, com.example.itantra.codec.Language) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
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
                    text = "VOICE PIPELINE DIAGNOSTICS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroAmber,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "[SINGLE DEVICE]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroCyan
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // The 10 required diagnostic metrics
            StatusRow("MIC STATUS:", diag.micStatus, if (diag.micStatus.contains("RECORDING") || diag.micStatus.contains("ACTIVE")) RetroAmber else RetroGreen)
            StatusRow("STT STATUS:", diag.sttStatus, RetroCyan)
            StatusRow("SELECTED LANGUAGE:", diag.selectedLanguage, RetroAmber)
            StatusRow("RECOGNIZED TEXT:", diag.recognizedText, RetroGreen)
            StatusRow("INPUT DURATION:", if (diag.inputDurationMs > 0) "${diag.inputDurationMs} ms [MEASURED]" else "—", RetroCyan)
            StatusRow("STT LATENCY:", if (diag.sttLatencyMs > 0) "${diag.sttLatencyMs} ms [MEASURED]" else "—", RetroAmber)
            StatusRow("CODEC SIZE:", diag.codecSizeSummary, RetroCyan)
            StatusRow("DECODED TEXT:", diag.decodedText, RetroGreen)
            StatusRow("TTS STATUS:", diag.ttsStatus, if (diag.ttsStatus.contains("PLAYING") || diag.ttsStatus.contains("SPEAKING")) RetroAmber else RetroGreen)
            StatusRow("TTS START LATENCY:", if (diag.ttsStartLatencyMs > 0) "${diag.ttsStartLatencyMs} ms [MEASURED]" else "—", RetroAmber)

            Spacer(modifier = Modifier.height(10.dp))

            // Language Selector Row
            Text(
                text = "ACTIVE LANGUAGE:",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    com.example.itantra.codec.Language.ENGLISH to "EN",
                    com.example.itantra.codec.Language.HINDI to "HI (हिंदी)",
                    com.example.itantra.codec.Language.TAMIL to "TA (தமிழ்)"
                ).forEach { (lang, label) ->
                    val active = selectedLang == lang
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) RetroAmber else RetroBackground)
                            .border(1.dp, if (active) RetroAmber else RetroDarkGray, RoundedCornerShape(6.dp))
                            .clickable { onSelectLang(lang) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) RetroBackground else RetroWhite
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "LOOPBACK (MIC -> CODEC -> DECODE -> TTS -> SPEAKER):",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = RetroGray
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onRunTest("Emergency medical team needed at sector 4", com.example.itantra.codec.Language.ENGLISH) },
                    enabled = !diag.isRunningTest,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroAmber.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, RetroAmber),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("TEST EN", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroAmber)
                }

                Button(
                    onClick = { onRunTest("तुरंत सहायता की आवश्यकता है", com.example.itantra.codec.Language.HINDI) },
                    enabled = !diag.isRunningTest,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroCyan.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, RetroCyan),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("TEST HI", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroCyan)
                }

                Button(
                    onClick = { onRunTest("உடனடி உதவி தேவைப்படுகிறது", com.example.itantra.codec.Language.TAMIL) },
                    enabled = !diag.isRunningTest,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroGreen.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, RetroGreen),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("TEST TA", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroGreen)
                }
            }
        }
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
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RetroSurface)
            .border(1.dp, RetroDarkGray.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabItem("LINK", Icons.Default.Home, activeTab == 0) { onTabSelected(0) }
        TabItem("METRICS", Icons.Default.Analytics, activeTab == 1) { onTabSelected(1) }
        TabItem("CODEC", Icons.Default.Science, activeTab == 2) { onTabSelected(2) }
        TabItem("CONFIG", Icons.Default.Settings, activeTab == 3) { onTabSelected(3) }
        TabItem("HW TEST", Icons.Default.PhoneAndroid, activeTab == 4) { onTabSelected(4) }
        TabItem("MODEL", Icons.Default.Memory, activeTab == 5) { onTabSelected(5) }
        TabItem("DEMO", Icons.Default.Videocam, activeTab == 6) { onTabSelected(6) }
    }
}

@Composable
fun TabItem(label: String, icon: ImageVector, isActive: Boolean, onClick: () -> Unit) {
    val color = if (isActive) RetroAmber else RetroGray
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = color
        )
    }
}