package com.example.itantra.ui.main

import android.os.Build
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.itantra.codec.Language
import com.example.itantra.lab.*
import com.example.itantra.transport.BleLinkCheck
import com.example.itantra.transport.ConnectionStatus
import com.example.itantra.transport.MeshRelayStats
import com.example.itantra.ui.theme.*

/**
 * PHASE 5 — HARDWARE TEST (physical-device validation lab).
 *
 * Validate on real phones, not this screen alone: Phone A sends with HOST,
 * Phone B receives with DEVICE. Every column is either measured from the radio
 * (chunk counts, MTU, link metrics, session epochs) or explicitly labeled
 * SIMULATED — never presented as physical measurements.
 */
@Composable
fun HardwareTestScreen(viewModel: HardwareTestViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "HARDWARE TEST",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )
        Text(
            text = "PHYSICAL DEVICE VALIDATION — RAW TESTS FIRST, THEN MESH",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RetroGray,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(6.dp))
        MetricSection("DEVICE") {
            MetricRow("MODEL:", "${Build.MANUFACTURER} ${Build.MODEL}", RetroCyan)
            MetricRow("ANDROID:", "v${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", RetroCyan)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ------------------------------------------------------------------
        // TRANSPORT + ROLE + CONNECT
        // ------------------------------------------------------------------
        MetricSection("LINK SETUP") {
            HwChipRow("TRANSPORT", LabRadio.values().map { it.label }, LabRadio.values().toList().indexOf(state.transport)) {
                viewModel.setTransport(LabRadio.values()[it])
            }
            Spacer(modifier = Modifier.height(6.dp))
            HwChipRow("ROLE", listOf("HOST", "DEVICE"), if (state.role == LabRole.HOST) 0 else 1) {
                viewModel.setRole(if (it == 0) LabRole.HOST else LabRole.DEVICE)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = viewModel::setAddress,
                    label = { Text("peer IP (BLE: auto-scan)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = RetroCyan)
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::setPort,
                    label = { Text("port") },
                    singleLine = true,
                    modifier = Modifier.width(84.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = RetroCyan)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwAction("CONNECT", enabled = !state.busy) { viewModel.startLink() }
                HwAction("DISCONNECT", enabled = !state.busy) { viewModel.stopLink() }
            }
            Spacer(modifier = Modifier.height(4.dp))
            HwStatusRow("CONNECTION:", hwStatusLabel(state.connectionStatus), hwStatusColor(state.connectionStatus))
            if (state.sessionLabel.isNotBlank()) {
                HwStatusRow("SESSION:", state.sessionLabel, RetroAmber)
            }
            state.error?.let {
                HwStatusRow("ERROR:", it, RetroRed)
            }
            if (state.meshNode == null) {
                HwStatusRow("NOTE:", "one phone HOST, the other DEVICE, both in HARDWARE TEST", RetroGray)
            }
        }

        if (state.transport == LabRadio.BLE) {
            Spacer(modifier = Modifier.height(12.dp))
            MetricSection("BLE CHECKLIST") {
                BleChecklist(state.bleCheck)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        MetricSection("RAW PACKET TEST") {
            Text(
                text = "\"${HardwareTestSuite.BLE_RAW.text}\"",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = RetroCyan
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwAction("SEND RAW", enabled = !state.busy) { viewModel.runRawProbe() }
                HwAction("UNICODE x3", enabled = !state.busy) { viewModel.runUnicodeSuite() }
                HwAction("SIZE SWEEP", enabled = !state.busy) { viewModel.runSizeSweep() }
            }
            if (state.transport == LabRadio.BLE) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HwAction("LOSS DROP 1/3", enabled = !state.busy && state.simulatedLossDropEveryNth == 0) {
                        viewModel.setSimulatedLossDropEveryNth(3)
                    }
                    HwAction("LOSS OFF", enabled = state.simulatedLossDropEveryNth != 0) {
                        viewModel.setSimulatedLossDropEveryNth(0)
                    }
                }
                HwStatusRow(
                    "SIMULATED LOSS:", if (state.simulatedLossDropEveryNth > 0) "ACTIVE (drop 1/${state.simulatedLossDropEveryNth} chunks)" else "OFF",
                    if (state.simulatedLossDropEveryNth > 0) RetroOrange else RetroGreen
                )
                MetricRow("DROPPED (SIM):", "${state.simulatedChunksDropped}")
            }
            state.lastProbe?.let { ProbeCard(it) }
            if (state.probeHistory.isNotEmpty() && state.lastProbe != state.probeHistory.lastOrNull()) {
                state.probeHistory.forEach { ProbeCard(it) }
            }
            if (state.sweepRows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                MetricRow("SWEEP SIZE | MTU | FRAGS | RESULT | TIME", "", RetroAmber)
                state.sweepRows.forEach { r ->
                    MetricRow("${r.sizeBytes}B", "mtu ${r.mtu} | ${r.fragments} frag(s) | ${r.pass} | ${r.timeMs} ms", if (r.sentOk) RetroGreen else RetroRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        MetricSection("DUPLICATE TEST") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwAction(if (state.duplicationInjected) "DUP OFF" else "INJECT DUP", enabled = !state.busy) {
                    viewModel.setDuplicateInjection(!state.duplicationInjected)
                }
                HwAction("CHECK DELIVERY", enabled = !state.busy) { viewModel.runDuplicateCheck() }
            }
            HwStatusRow(
                "DUPLICATION:", if (state.duplicationInjected) "INJECTED (SIMULATED)" else "off",
                if (state.duplicationInjected) RetroOrange else RetroGray
            )
            state.duplicateCheck?.let { c ->
                MetricRow("DUPLICATE:", if (c.duplicatesDetected >= 1) "DETECTED (${c.duplicatesDetected})" else "NONE", if (c.duplicatesDetected >= 1) RetroAmber else RetroRed)
                MetricRow("DELIVERY:", "${c.payloadsDelivered} of ${c.messagesExpected}", if (c.payloadsDelivered == c.messagesExpected) RetroGreen else RetroRed)
                HwStatusRow("RESULT:", c.pass, if (c.okay) RetroGreen else RetroRed)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        MetricSection("RECEIVE WINDOW") {
            HwAction("LISTEN 20s", enabled = !state.busy) { viewModel.startReceiveWindow() }
            state.receivedItems.forEach { item ->
                HwStatusRow(
                    "${item.payloadBytes}B", "${item.matched} | ${item.pass} | ${item.note}",
                    if (item.pass == "PASS") RetroGreen else RetroRed
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        MetricSection("DISCONNECT / RECONNECT") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwAction("SAVE SNAPSHOT", enabled = true) { viewModel.saveSessionSnapshot() }
                HwAction("RECONNECT", enabled = !state.busy) {
                    viewModel.stopLink()
                    viewModel.startLink()
                    viewModel.recordReconnect()
                }
            }
            state.reconnectReport?.let { r ->
                HwStatusRow("SESSION BEFORE:", "${r.sessionBefore} (epoch ${r.epochBefore})", RetroGray)
                HwStatusRow("SESSION AFTER:", "${r.sessionAfter} (epoch ${r.epochAfter})", RetroAmber)
                HwStatusRow("FRESH SESSION:", if (r.freshSession) "YES — stale rejected" else "same", if (r.freshSession) RetroGreen else RetroRed)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        MeshNodeSection(viewModel, state.meshNodeRole, state.meshNode, state.meshRelay, state.busy, state.edge1, state.edge2)

        Spacer(modifier = Modifier.height(12.dp))
        MetricSection("SPEECH → STT → CODEC → BLE (NO TTS)") {
            // The lab speaks only the languages with a bundled offline STT
            // model; P21 languages are surfaced in the app language pickers.
            val hwLanguages = listOf(Language.ENGLISH, Language.TAMIL, Language.HINDI)
            HwChipRow("LANG", hwLanguages.map { it.code.uppercase() },
                hwLanguages.indexOf(state.speechLanguage).coerceAtLeast(0)) {
                viewModel.setSpeechLanguage(hwLanguages[it])
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwAction("START", enabled = true) { viewModel.startSpeech() }
                HwAction(if (state.speechRecording) "STOP MIC" else "MIC", enabled = true) { viewModel.toggleSpeechRecording() }
                HwAction("STOP", enabled = true) { viewModel.stopSpeech() }
            }
            state.speechLastReport?.let { rep ->
                MetricRow("LAST:", "${rep.text.take(40)}", RetroCyan)
                MetricRow("CODEC/WIRE:", "${rep.originalUtf8Bytes}B / ${rep.transmittedBytes}B", if (rep.transmittedBytes <= rep.originalUtf8Bytes) RetroGreen else RetroOrange)
                MetricRow("RESULT:", if (rep.failed) "FAILED ${rep.detail}" else "SENT", if (rep.failed) RetroRed else RetroGreen)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        MetricSection("REPORT (CSV)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwAction("REBUILD", enabled = true) { viewModel.rebuildReport() }
                HwAction("COPY", enabled = true) { viewModel.copyReport() }
                HwAction("SAVE", enabled = true) { viewModel.saveReport() }
            }
            if (state.reportSavedPath.isNotBlank()) {
                HwStatusRow("SAVED:", state.reportSavedPath, RetroGreen)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.reportText.ifEmpty { "(run tests, then REBUILD)" }.take(1800),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = RetroCyan,
                lineHeight = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------
// Assist composables
// ------------------------------------------------------------------

@Composable
fun MeshNodeSection(
    viewModel: HardwareTestViewModel,
    role: MeshNodeRole,
    node: StartedMeshNode?,
    relay: MeshRelayStats,
    busy: Boolean,
    edge1: EdgeSpec,
    edge2: EdgeSpec
) {
    MetricSection("MESH NODE CONFIGURATION") {
        Text(
            text = "A=SOURCE → B=RELAY → C=DESTINATION. RELAY hosts one radio while dialing the other.",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RetroGray
        )
        Spacer(modifier = Modifier.height(6.dp))
        HwChipRow("NODE ROLE", MeshNodeRole.values().map { it.label }, MeshNodeRole.values().indexOf(role)) {
            viewModel.setMeshNodeRole(MeshNodeRole.values()[it])
        }
        Spacer(modifier = Modifier.height(6.dp))
        EdgeEditor("EDGE 1", edge1,
            onRadio = viewModel::setEdge1Radio,
            onRole = viewModel::setEdge1Role,
            onAddress = viewModel::setEdge1Address,
            editable = role == MeshNodeRole.RELAY
        )
        if (role == MeshNodeRole.RELAY) {
            Spacer(modifier = Modifier.height(6.dp))
            EdgeEditor("EDGE 2", edge2,
                onRadio = viewModel::setEdge2Radio,
                onRole = viewModel::setEdge2Role,
                onAddress = viewModel::setEdge2Address,
                editable = true
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwAction("START NODE", enabled = !busy && node == null) { viewModel.startMeshNode() }
            HwAction("STOP NODE", enabled = node != null) { viewModel.stopMeshNode() }
            HwAction("SEND MESH MSG", enabled = node != null) { viewModel.sendMeshMessage() }
        }
        node?.let {
            Spacer(modifier = Modifier.height(6.dp))
            HwStatusRow("RELAY STATUS:", "● ACTIVE", RetroGreen)
            HwStatusRow("ROLE:", it.role.label, RetroCyan)
            HwStatusRow("NODE ID:", it.deviceId, RetroAmber)
            HwStatusRow("EDGES:", it.edgeLabels.joinToString(" | "), RetroCyan)
            if (relay.packetsRelayed > 0 || relay.duplicatesDropped > 0 || relay.ttlExpired > 0) {
                MetricRow("PACKETS RELAYED:", "${relay.packetsRelayed}", RetroGreen)
                MetricRow("DUPLICATES DROPPED:", "${relay.duplicatesDropped}", RetroAmber)
                MetricRow("TTL EXPIRED:", "${relay.ttlExpired}", RetroOrange)
                MetricRow("LAST HOP:", relay.lastHop, RetroCyan)
            } else {
                Text(
                    text = "NO TRAFFIC YET — have a peer relay a message through this node.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroGray
                )
            }
            val meshState = viewModel.state.value
            HwStatusRow("STATUS:", hwStatusLabel(meshState.connectionStatus), hwStatusColor(meshState.connectionStatus))
        }
        Text(
            text = "MESH IS BROADCAST + PER-HOP RELIABLE. NO END-TO-END ACK — REPORT HOP DELIVERY SEPARATELY FROM END-TO-END.",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = RetroOrange
        )
    }
}

@Composable
fun EdgeEditor(
    label: String,
    spec: EdgeSpec,
    onRadio: (LabRadio) -> Unit,
    onRole: (LabRole) -> Unit,
    onAddress: (String) -> Unit,
    editable: Boolean
) {
    Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RetroGray)
    HwChipRow("RADIO", LabRadio.values().map { it.label }, LabRadio.values().indexOf(spec.radio)) {
        if (editable) onRadio(LabRadio.values()[it])
    }
    HwChipRow("ROLE", listOf("HOST", "DEVICE"), if (spec.role == LabRole.HOST) 0 else 1) {
        if (editable) onRole(if (it == 0) LabRole.HOST else LabRole.DEVICE)
    }
    if (editable) {
        OutlinedTextField(
            value = spec.address,
            onValueChange = onAddress,
            label = { Text("peer IP (BLE: auto-scan)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = RetroCyan)
        )
        Text(
            text = "${spec.label()} · port ${spec.port}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RetroCyan
        )
    }
}

@Composable
fun BleChecklist(check: BleLinkCheck) {
    CheckRow("Advertising", check.advertising)
    CheckRow("Scanning", check.scanning)
    CheckRow("Connected", check.connected)
    CheckRow("Service discovered", check.serviceReady)
    CheckRow("Characteristics discovered", check.characteristicsReady)
    CheckRow("Notifications enabled", check.notificationsEnabled)
    CheckRow("MTU negotiated", check.mtuNegotiated)
    HwStatusRow("MTU:", "${check.mtu} B", RetroCyan)
    HwStatusRow("READY:", if (check.ready) "READY" else "WAITING", if (check.ready) RetroGreen else RetroAmber)
}

@Composable
fun CheckRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = RetroGray)
        Text(
            text = if (ok) "[✓]" else "[ ]",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (ok) RetroGreen else RetroRed
        )
    }
}

@Composable
fun ProbeCard(p: ProbeResult) {
    Spacer(modifier = Modifier.height(4.dp))
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, RetroAmber.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
        color = RetroBackground,
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            HwStatusRow("CASE:", "${p.caseName} · ${p.language.displayName}", RetroAmber)
            MetricRow("ORIGINAL:", "${p.originalBytes} B")
            MetricRow("CODEC:", "${p.codecBytes} B")
            MetricRow("PACKET:", "${p.packetBytes} B")
            MetricRow("PKTS / FRAGS:", "${p.packetCount} / ${fragLabel(p)}")
            MetricRow("TIME:", "${p.encodeMs} enc + ${p.transportMs} tx + ${p.decodeMs} dec = ${p.totalMs} ms")
            MetricRow("RETX/LOSS/DUP:", "${p.retransmissions}/${p.packetLoss}/${p.duplicatesDetected}")
            HwStatusRow("CRC:", p.crcPass?.let { if (it) "PASS" else "FAIL" } ?: "— (verify on receiver)", if (p.crcPass == true) RetroGreen else if (p.crcPass == false) RetroRed else RetroGray)
            HwStatusRow("DECODE:", p.decodePass?.let { if (it) "PASS" else "FAIL" } ?: "— (verify on receiver)", if (p.decodePass == true) RetroGreen else if (p.decodePass == false) RetroRed else RetroGray)
            HwStatusRow("STATUS:", "${p.status} ${if (p.simulated) "· SIMULATED" else ""}", if (p.status == "PASS") RetroGreen else if (p.status == "FAIL") RetroRed else RetroAmber)
            if (p.detail.isNotBlank()) HwStatusRow("NOTE:", p.detail, RetroGray)
        }
    }
}

@Composable
fun HwChipRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label + ":", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroGray, modifier = Modifier.width(96.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, option ->
                val active = index == selected
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onSelect(index) }
                        .border(1.dp, if (active) RetroAmber else RetroGray.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                    color = if (active) RetroAmber.copy(alpha = 0.18f) else RetroSurface
                ) {
                    Text(
                        text = " $option ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) RetroAmber else RetroGray
                    )
                }
            }
        }
    }
}

@Composable
fun HwAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val enabledColor = if (enabled) RetroAmber.copy(alpha = 0.15f) else RetroGray.copy(alpha = 0.05f)
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled) { onClick() }
            .border(1.dp, if (enabled) RetroAmber.copy(alpha = 0.6f) else RetroGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
        color = enabledColor
    ) {
        Text(
            text = " $text ",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            color = if (enabled) RetroAmber else RetroGray
        )
    }
}

@Composable
fun HwStatusRow(label: String, value: String, valueColor: Color = RetroCyan) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = RetroGray)
        Text(text = value, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

private fun fragLabel(p: ProbeResult): String =
    if (p.fragments < 0) "N/A"
    else "${p.fragments}${if (p.fragmentMeasured) " (measured)" else " (est. mtu ${p.mtu})"}"

private fun hwStatusLabel(s: ConnectionStatus): String = when (s) {
    ConnectionStatus.DISCONNECTED -> "DISCONNECTED"
    ConnectionStatus.CONNECTING -> "CONNECTING"
    ConnectionStatus.WAITING -> "WAITING"
    ConnectionStatus.CONNECTED -> "CONNECTED"
    ConnectionStatus.ERROR -> "ERROR"
}

private fun hwStatusColor(s: ConnectionStatus): Color = when (s) {
    ConnectionStatus.CONNECTED -> RetroGreen
    ConnectionStatus.ERROR -> RetroRed
    ConnectionStatus.CONNECTING, ConnectionStatus.WAITING -> RetroAmber
    ConnectionStatus.DISCONNECTED -> RetroGray
}