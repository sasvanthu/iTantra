package com.example.itantra.ui.map

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.mesh.MultiHopRelayEngine
import com.example.itantra.telemetry.CommunicationEventLog
import com.example.itantra.ui.theme.*

/**
 * Prototype Communication Map for iTantra: Setu.
 *
 * Visualizes Phone A (Library) -> Phone B (Relay) -> Phone C (Crisis Center).
 * Explicitly labeled with "SIMULATED LOCATION" to ensure honest technical disclosure.
 * Animates packet travel along Bluetooth and Wi-Fi link vectors.
 */
@Composable
fun CampusCommunicationMap(
    relayState: MultiHopRelayEngine.MultiHopState,
    onRunRelay: () -> Unit,
    onTestDuplicate: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pulse animation for active transmitting nodes
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Smooth packet travel interpolation
    val animatedProgress by animateFloatAsState(
        targetValue = relayState.packetPosition,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "packetPos"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F141C))
            .border(1.dp, RetroCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        // Map Title Bar & Simulated GPS Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "PROTOTYPE COMMUNICATION MAP",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroCyan,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "CAMPUS EMERGENCY RELAY OVERLAY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.LightGray.copy(alpha = 0.7f)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(RetroOrange.copy(alpha = 0.2f))
                    .border(1.dp, RetroOrange, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "SIMULATED LOCATION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroOrange
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Visual Canvas Map
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A0D14))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                // Draw radar / grid background
                val gridStep = 30f
                var x = 0f
                while (x < width) {
                    drawLine(
                        color = Color(0xFF162032),
                        start = Offset(x, 0f),
                        end = Offset(x, height),
                        strokeWidth = 1f
                    )
                    x += gridStep
                }
                var y = 0f
                while (y < height) {
                    drawLine(
                        color = Color(0xFF162032),
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1f
                    )
                    y += gridStep
                }

                // Coordinates for 3 Nodes in Canvas space
                val posA = Offset(width * 0.18f, height * 0.72f) // Phone A (bottom-left)
                val posB = Offset(width * 0.50f, height * 0.32f) // Phone B (center-top relay)
                val posC = Offset(width * 0.82f, height * 0.72f) // Phone C (bottom-right)

                // Dashed link lines
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

                // Link A -> B (Bluetooth)
                drawLine(
                    color = if (animatedProgress in 0.01f..0.55f) RetroCyan else Color(0xFF334155),
                    start = posA,
                    end = posB,
                    strokeWidth = 3f,
                    pathEffect = dashEffect
                )

                // Link B -> C (Wi-Fi)
                drawLine(
                    color = if (animatedProgress > 0.45f) RetroGreen else Color(0xFF334155),
                    start = posB,
                    end = posC,
                    strokeWidth = 3f,
                    pathEffect = dashEffect
                )

                // Draw Node circles
                drawCircle(color = RetroCyan.copy(alpha = 0.2f), radius = 24f, center = posA)
                drawCircle(color = RetroCyan, radius = 10f, center = posA)

                val bPulseColor = if (relayState.currentStep in 3..4) RetroOrange else RetroCyan
                drawCircle(color = bPulseColor.copy(alpha = pulseAlpha * 0.4f), radius = 28f, center = posB)
                drawCircle(color = bPulseColor, radius = 12f, center = posB)

                val cColor = if (relayState.currentStep == 5) RetroGreen else Color(0xFF64748B)
                drawCircle(color = cColor.copy(alpha = 0.3f), radius = 26f, center = posC)
                drawCircle(color = cColor, radius = 11f, center = posC)

                // Draw animated traveling packet along path
                if (animatedProgress in 0.02f..0.98f) {
                    val pktPos = if (animatedProgress <= 0.5f) {
                        val t = animatedProgress / 0.5f
                        Offset(
                            posA.x + (posB.x - posA.x) * t,
                            posA.y + (posB.y - posA.y) * t
                        )
                    } else {
                        val t = (animatedProgress - 0.5f) / 0.5f
                        Offset(
                            posB.x + (posC.x - posB.x) * t,
                            posB.y + (posC.y - posB.y) * t
                        )
                    }

                    // Outer pulse
                    drawCircle(color = RetroOrange.copy(alpha = 0.6f), radius = 16f, center = pktPos)
                    // Inner packet core
                    drawCircle(color = Color.White, radius = 7f, center = pktPos)
                }
            }

            // Overlay Node Labels
            // Node A Label
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 10.dp)
            ) {
                Text(
                    text = "PHONE A",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroCyan
                )
                Text(
                    text = "Library (13.0102° N)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color.Gray
                )
                Text(
                    text = "ORIGIN",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroCyan.copy(alpha = 0.8f)
                )
            }

            // Link 1 Badge: BLUETOOTH
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 70.dp, top = 65.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, RetroCyan.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "BLUETOOTH",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroCyan
                )
            }

            // Node B Label (Relay)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PHONE B (RELAY)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroOrange
                )
                Text(
                    text = "Tech Quad (13.0118° N)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color.Gray
                )
            }

            // Link 2 Badge: WI-FI
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 70.dp, top = 65.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, RetroGreen.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "WI-FI",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroGreen
                )
            }

            // Node C Label (Destination)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "PHONE C",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (relayState.currentStep == 5) RetroGreen else Color.LightGray
                )
                Text(
                    text = "Crisis Ops (13.0135° N)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color.Gray
                )
                Text(
                    text = "FINAL DESTINATION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroGreen.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Live Relay Step & Transmission Badge
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF161F2E))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TRANSMISSION: ${if (relayState.activeTxId.isNotEmpty()) relayState.activeTxId else "STANDBY"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroGreen
                )
                Text(
                    text = "HOP TRANSPORT: ${relayState.currentHopTransport}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (relayState.currentHopTransport == "BLUETOOTH") RetroCyan else RetroGreen
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "MESSAGE: ${if (relayState.activeMsgId.isNotEmpty()) relayState.activeMsgId else "MSG-—"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.LightGray
                )
                Text(
                    text = "PACKET: ${if (relayState.activePktId.isNotEmpty()) relayState.activePktId else "PKT-—"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.LightGray
                )
            }

            if (relayState.deliveredMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "DELIVERED TO PHONE C: \"${relayState.deliveredMessage}\"",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Demonstration Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRunRelay,
                enabled = !relayState.isSimulating,
                colors = ButtonDefaults.buttonColors(containerColor = RetroCyan, contentColor = Color.Black),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (relayState.isSimulating) "RELAYING..." else "RUN MULTI-HOP (A->B->C)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onTestDuplicate,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, RetroOrange),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (relayState.duplicateDetected) "DUPLICATE IGNORED ✓" else "TEST DUP REJECTION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroOrange
                )
            }
        }
    }
}
