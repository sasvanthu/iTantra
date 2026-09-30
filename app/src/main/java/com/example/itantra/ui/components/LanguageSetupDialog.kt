package com.example.itantra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.itantra.codec.Language
import com.example.itantra.speech.stt.LanguagePackManager
import com.example.itantra.ui.main.MainViewModel
import com.example.itantra.ui.theme.*

@Composable
fun LanguageSetupDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val statuses by viewModel.languagePackStatuses.collectAsState()
    var selectedSecondLang by remember { mutableStateOf(Language.TAMIL) }

    val englishStatus = statuses[Language.ENGLISH]
    val secondLangStatus = statuses[selectedSecondLang]

    val englishReady = englishStatus?.state == LanguagePackManager.InstallState.READY
    val secondReady = secondLangStatus?.state == LanguagePackManager.InstallState.READY
    val bothReady = englishReady && secondReady

    val isInstalling = englishStatus?.state in listOf(
        LanguagePackManager.InstallState.DOWNLOADING,
        LanguagePackManager.InstallState.VERIFYING,
        LanguagePackManager.InstallState.EXTRACTING,
        LanguagePackManager.InstallState.VALIDATING
    ) || secondLangStatus?.state in listOf(
        LanguagePackManager.InstallState.DOWNLOADING,
        LanguagePackManager.InstallState.VERIFYING,
        LanguagePackManager.InstallState.EXTRACTING,
        LanguagePackManager.InstallState.VALIDATING
    )

    Dialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .border(1.dp, RetroAmber, RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = RetroSurface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SETU LANGUAGE PACKS",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = RetroAmber,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "OFFLINE SPEECH-TO-TEXT INSTALLATION",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = RetroCyan
                        )
                    }
                    if (!isInstalling) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = RetroGray)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Setu works with zero internet and zero cloud APIs. To enable real on-device speech recognition, install the required Kaldi/Vosk language packs. Once installed, speech recognition functions fully in Airplane Mode.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = RetroWhite.copy(alpha = 0.85f),
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Section 1: Mandatory English
                Text(
                    text = "1. MANDATORY LANGUAGE:",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroAmber
                )
                Spacer(modifier = Modifier.height(4.dp))
                PackInstallCard(
                    desc = LanguagePackManager.getDescriptor(Language.ENGLISH)!!,
                    status = englishStatus,
                    onInstallClick = { viewModel.installLanguagePack(Language.ENGLISH) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: Choose second language
                Text(
                    text = "2. CHOOSE YOUR LANGUAGE (ONE REQUIRED):",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroAmber
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Available options
                listOf(Language.TAMIL, Language.KANNADA, Language.HINDI, Language.TELUGU, Language.GUJARATI).forEach { lang ->
                    val desc = LanguagePackManager.getDescriptor(lang) ?: return@forEach
                    val status = statuses[lang]
                    val isSelected = selectedSecondLang == lang
                    val isReady = status?.state == LanguagePackManager.InstallState.READY

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) RetroAmber.copy(alpha = 0.15f) else RetroBackground)
                            .border(1.dp, if (isSelected) RetroAmber else RetroDarkGray, RoundedCornerShape(8.dp))
                            .clickable(enabled = !isInstalling) { selectedSecondLang = lang }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { if (!isInstalling) selectedSecondLang = lang },
                            colors = RadioButtonDefaults.colors(selectedColor = RetroAmber, unselectedColor = RetroGray)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = desc.displayName,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) RetroAmber else RetroWhite
                            )
                            Text(
                                text = "${desc.modelName} (~${desc.sizeBytes / (1024 * 1024)} MB)",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = RetroGray
                            )
                        }
                        if (isReady) {
                            Text(
                                text = "READY",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = RetroGreen
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Selected Language Install Card
                secondLangStatus?.let { status ->
                    PackInstallCard(
                        desc = status.descriptor,
                        status = status,
                        onInstallClick = { viewModel.installLanguagePack(selectedSecondLang) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Unavailable Languages Note
                Text(
                    text = "OTHER INDIAN LANGUAGES (HONEST AVAILABILITY):",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = RetroGray
                )
                Spacer(modifier = Modifier.height(4.dp))
                listOf(
                    Language.BENGALI to "Bengali (বাংলা)",
                    Language.MARATHI to "Marathi (मराठी)",
                    Language.MALAYALAM to "Malayalam (മലയാളം)",
                    Language.ODIA to "Odia (ଓଡ଼ିଆ)"
                ).forEach { (_, name) ->
                    Text(
                        text = "• $name: Offline Vosk model pending (not bundled/simulated)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = RetroGray.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action Button
                if (bothReady) {
                    Button(
                        onClick = {
                            viewModel.completeFirstRunLanguageSetup(selectedSecondLang)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RetroGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "OFFLINE READY — START SETU",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = RetroBackground,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            if (!englishReady) {
                                viewModel.installLanguagePack(Language.ENGLISH)
                            }
                            if (!secondReady) {
                                viewModel.installLanguagePack(selectedSecondLang)
                            }
                        },
                        enabled = !isInstalling,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RetroAmber),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = RetroBackground)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isInstalling) "INSTALLING PACKS..." else "INSTALL REQUIRED LANGUAGE PACKS",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = RetroBackground,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PackInstallCard(
    desc: LanguagePackManager.LanguagePackDescriptor,
    status: LanguagePackManager.LanguagePackStatus?,
    onInstallClick: () -> Unit
) {
    val state = status?.state ?: LanguagePackManager.InstallState.NOT_INSTALLED
    val isReady = state == LanguagePackManager.InstallState.READY
    val isInstalling = state in listOf(
        LanguagePackManager.InstallState.DOWNLOADING,
        LanguagePackManager.InstallState.VERIFYING,
        LanguagePackManager.InstallState.EXTRACTING,
        LanguagePackManager.InstallState.VALIDATING
    )
    val isError = state == LanguagePackManager.InstallState.ERROR

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isReady) RetroGreen else if (isError) RetroRed else RetroDarkGray, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroBackground),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = desc.displayName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RetroWhite
                    )
                    Text(
                        text = "Vosk Small Kaldi (~${desc.sizeBytes / (1024 * 1024)} MB)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = RetroGray
                    )
                }

                when {
                    isReady -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = RetroGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("READY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RetroGreen)
                        }
                    }
                    isInstalling -> {
                        Text(
                            text = "${(status?.progress ?: 0f * 100).toInt()}%",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = RetroCyan
                        )
                    }
                    isError -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = RetroRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ERROR", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RetroRed)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onInstallClick,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RetroAmber)
                        ) {
                            Text("INSTALL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (isInstalling) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { status?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = RetroCyan,
                    trackColor = RetroDarkGray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = status?.statusMessage ?: "Installing...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroCyan
                )
            } else if (isError) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = status?.errorMessage ?: "Installation failed. Check internet connection.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = RetroRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onInstallClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RetroOrange)
                ) {
                    Text("RETRY", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
