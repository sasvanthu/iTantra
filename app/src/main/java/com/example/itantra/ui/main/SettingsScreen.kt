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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.codec.Language
import com.example.itantra.ui.theme.*

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(uiState.port) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "CONFIGURATION",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connection Section
        SettingsSection("CONNECTION") {
            // Role selector
            Text(
                text = "ROLE:",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = RetroGray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                RoleOption("SERVER", uiState.isServer) { viewModel.startServer() }
                Spacer(modifier = Modifier.width(8.dp))
                RoleOption("CLIENT", !uiState.isServer) { /* Show connect UI */ }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // IP Address input
            Text(
                text = "IP ADDRESS:",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = RetroGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                modifier = Modifier.fillMaxWidth(),
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

            // Port input
            Text(
                text = "PORT:",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = RetroGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it; viewModel.setPort(it) },
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(12.dp))

            // Connect button
            if (!uiState.isServer && ipAddress.isNotBlank()) {
                Button(
                    onClick = { viewModel.connectToServer(ipAddress) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroAmber),
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

            // Disconnect button
            if (uiState.isConnected) {
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
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Language Section
        SettingsSection("LANGUAGE") {
            Language.entries.filter { it != Language.UNKNOWN }.forEach { lang ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (uiState.currentLanguage == lang) RetroSurfaceVariant else RetroSurface
                        )
                        .clickable { viewModel.setLanguage(lang) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.displayName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = if (uiState.currentLanguage == lang) RetroAmber else RetroWhite
                    )
                    if (uiState.currentLanguage == lang) {
                        Text(
                            text = "ACTIVE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = RetroGreen
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Codec Section
        SettingsSection("CODEC") {
            Row {
                CodecOption("RETRO", uiState.mode == "RETRO") { viewModel.setMode(true) }
                Spacer(modifier = Modifier.width(8.dp))
                CodecOption("BASELINE", uiState.mode == "BASELINE") { viewModel.setMode(false) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // PTT Mode
        SettingsSection("MODE") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(RetroSurface)
                    .clickable { viewModel.togglePTT() }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PUSH-TO-TALK",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = RetroWhite
                )
                Switch(
                    checked = uiState.isPTTMode,
                    onCheckedChange = { viewModel.togglePTT() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = RetroAmber,
                        uncheckedTrackColor = RetroDarkGray
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun RoleOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) RetroAmber else RetroSurface)
            .border(1.dp, if (isSelected) RetroAmber else RetroDarkGray, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) RetroBackground else RetroGray
        )
    }
}

@Composable
fun CodecOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) RetroAmber else RetroSurface)
            .border(1.dp, if (isSelected) RetroAmber else RetroDarkGray, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) RetroBackground else RetroGray
        )
    }
}
