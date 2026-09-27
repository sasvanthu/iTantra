package com.example.itantra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.itantra.protocol.SetuPacket
import com.example.itantra.security.SecurityStatus
import com.example.itantra.telemetry.CommunicationEventLog
import com.example.itantra.ui.theme.*

/**
 * Security panel demonstrating AES-256-GCM, fresh nonces, tag verification,
 * tamper detection, and replay prevention.
 */
@Composable
fun SecurityPanel(
    lastPacket: SetuPacket?,
    onRunSecurityTest: () -> Unit,
    securityReport: SecurityStatus.SecurityTestReport?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111722))
            .border(1.dp, RetroGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SECURITY & INTEGRITY",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = RetroGreen,
                letterSpacing = 1.sp
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(RetroGreen.copy(alpha = 0.15f))
                    .border(1.dp, RetroGreen, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "AES-256-GCM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Security Checklist Grid
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SecurityCheckItem(label = "CIPHER", value = "AES-256-GCM ✓", color = RetroGreen)
            SecurityCheckItem(label = "INTEGRITY", value = "AUTHENTICATED ✓", color = RetroGreen)
            SecurityCheckItem(label = "NONCE/IV", value = "12B FRESH", color = RetroCyan)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SecurityCheckItem(label = "AUTH TAG", value = "128-BIT (VERIFIED ✓)", color = RetroGreen)
            SecurityCheckItem(label = "TAMPER DETECT", value = "ENFORCED ✓", color = RetroGreen)
            SecurityCheckItem(label = "REPLAY SHIELD", value = "ACTIVE ✓", color = RetroCyan)
        }

        if (lastPacket != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0A0E17))
                    .padding(6.dp)
            ) {
                Text(
                    text = "CIPHERTEXT (BASE64): ${lastPacket.encryptedPayload.take(24)}... (${lastPacket.encryptedPayload.length} chars)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color.LightGray
                )
                Text(
                    text = "NONCE: ${lastPacket.nonce}  |  AUTH TAG: ${lastPacket.authenticationTag.take(12)}...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = RetroCyan
                )
            }
        }

        if (securityReport != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0B1914))
                    .border(1.dp, RetroGreen.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(6.dp)
            ) {
                Text(
                    text = "SECURITY SELF-TEST: ${if (securityReport.allPassed) "ALL 5 CHECKS PASSED ✓" else "FAILED"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (securityReport.allPassed) RetroGreen else RetroRed
                )
                for (detail in securityReport.details.take(3)) {
                    Text(
                        text = "• $detail",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = Color.LightGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onRunSecurityTest,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, RetroGreen),
            modifier = Modifier.fillMaxWidth().height(36.dp)
        ) {
            Text(
                text = "RUN 5-STEP SECURITY & TAMPER TEST",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = RetroGreen
            )
        }
    }
}

@Composable
private fun SecurityCheckItem(label: String, value: String, color: Color) {
    Column {
        Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color.Gray)
        Text(text = value, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * Developer and operator transmission debug panel displaying structured IDs.
 */
@Composable
fun TransmissionDebugPanel(
    transmissionId: String,
    messageId: String,
    packetId: String,
    transport: String,
    statusText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF12151D))
            .border(1.dp, RetroCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TRANSMISSION IDENTIFIERS",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = RetroCyan,
                letterSpacing = 1.sp
            )

            Text(
                text = statusText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (statusText.contains("DELIVERED") || statusText.contains("SUCCESS")) RetroGreen else RetroOrange
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "TRANSMISSION ID", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color.Gray)
                Text(text = transmissionId.ifEmpty { "TX-STANDBY" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RetroCyan)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "TRANSPORT", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color.Gray)
                Text(text = transport, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (transport == "BLUETOOTH") RetroCyan else RetroGreen)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "MESSAGE ID", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color.Gray)
                Text(text = messageId.ifEmpty { "MSG-—" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "PACKET ID", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color.Gray)
                Text(text = packetId.ifEmpty { "PKT-—" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/**
 * Live communication and simulated GPS event feed.
 */
@Composable
fun LiveEventLogPanel(
    events: List<CommunicationEventLog.EventRecord>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showGpsJson by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0D1117))
            .border(1.dp, Color(0xFF21262D), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "COMMUNICATION & EVENT LOG",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroCyan,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${events.size} records",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color.Gray
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showGpsJson = !showGpsJson },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    border = BorderStroke(1.dp, RetroOrange)
                ) {
                    Text(
                        text = if (showGpsJson) "NORMAL VIEW" else "GPS JSON",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = RetroOrange
                    )
                }

                OutlinedButton(
                    onClick = onClearLogs,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    border = BorderStroke(1.dp, Color.Gray)
                ) {
                    Text(text = "CLEAR", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color.LightGray)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Awaiting transmissions...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                for (event in events.take(15)) {
                    EventRow(event = event, showGpsJson = showGpsJson)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: CommunicationEventLog.EventRecord, showGpsJson: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF161B22))
            .padding(6.dp)
    ) {
        if (showGpsJson) {
            Text(
                text = event.gpsJsonRecord(),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = RetroGreen
            )
        } else {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "[${event.formattedTime()}] ${event.device} -> ${event.destinationDevice ?: "ANY"}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = RetroCyan
                    )
                    Text(
                        text = event.transport,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (event.transport == "BLUETOOTH") RetroCyan else RetroGreen
                    )
                }
                Text(
                    text = "${event.eventType}: ${event.detail}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = Color.LightGray
                )
                Text(
                    text = "LOC: ${event.locationLabel} (${event.latitude}, ${event.longitude}) [SIMULATED]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
