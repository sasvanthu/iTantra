package com.example.itantra.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.example.itantra.codec.Language
import com.example.itantra.data.ModelManager
import com.example.itantra.speech.stt.LanguagePackManager
import com.example.itantra.ui.theme.*

private val DISPLAYED_LANGUAGES = listOf(
    Language.ENGLISH, Language.HINDI, Language.TELUGU, Language.GUJARATI,
    Language.TAMIL, Language.BENGALI, Language.MARATHI,
    Language.KANNADA, Language.MALAYALAM, Language.ODIA
)

@Composable
fun ModelCenterScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val status by viewModel.modelStatus.collectAsState()
    val packStatuses by viewModel.languagePackStatuses.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "iTantra MODEL CENTER",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        MetricSection("OFFLINE MODEL STATUS") {
            MetricRow("Usable:", "${uiState.modelsUsable}/${uiState.modelsTotal}",
                if (uiState.modelsUsable > 0) RetroGreen else RetroOrange)
            MetricRow("STT engines:", "VOSK (pure offline)", RetroCyan)
            MetricRow("TTS engines:", "ANDROID DEVICE FALLBACK (not bundled)", RetroOrange)
            MetricRow("Policy:", "No model is ever reported usable unless real files are present", RetroGray)
        }

        Spacer(modifier = Modifier.height(14.dp))

        MetricSection("OFFLINE LANGUAGE PACKS (VOSK ASR)") {
            Text(
                text = "Genuine on-device speech models. English is required; install one additional regional language for zero-cloud tactical operation.",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = RetroGray,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LanguagePackManager.CATALOG.forEach { desc ->
                val packStatus = packStatuses[desc.language]
                val state = packStatus?.state ?: LanguagePackManager.InstallState.NOT_INSTALLED
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
                        .padding(vertical = 4.dp)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = desc.displayName,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isReady) RetroGreen else RetroWhite
                                    )
                                    if (desc.isMandatory) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "[REQUIRED]",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = RetroAmber
                                        )
                                    }
                                }
                                Text(
                                    text = if (desc.isAvailable) "${desc.modelName} (~${desc.sizeBytes / (1024 * 1024)} MB)" else desc.availabilityNote,
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
                                        text = "${((packStatus?.progress ?: 0f) * 100).toInt()}%",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RetroCyan
                                    )
                                }
                                isError -> {
                                    Button(
                                        onClick = { viewModel.installLanguagePack(desc.language) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = RetroOrange)
                                    ) {
                                        Text("RETRY", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroBackground, fontWeight = FontWeight.Bold)
                                    }
                                }
                                desc.isAvailable -> {
                                    Button(
                                        onClick = { viewModel.installLanguagePack(desc.language) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = RetroAmber)
                                    ) {
                                        Text("INSTALL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = RetroBackground, fontWeight = FontWeight.Bold)
                                    }
                                }
                                else -> {
                                    Text(
                                        text = "UNAVAILABLE",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = RetroGray
                                    )
                                }
                            }
                        }

                        if (isInstalling) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { packStatus?.progress ?: 0f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = RetroCyan,
                                trackColor = RetroDarkGray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = packStatus?.statusMessage ?: "Installing...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = RetroCyan
                            )
                        } else if (isError) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = packStatus?.errorMessage ?: "Installation failed",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = RetroRed
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        MetricSection("PER-LANGUAGE TABLE") {
            for (language in DISPLAYED_LANGUAGES) {
                val isUsable = LanguagePackManager.isModelReady(viewModel.getApplication(), language)
                val isWired = language in setOf(Language.ENGLISH, Language.HINDI, Language.TELUGU, Language.GUJARATI)
                val tts = status.firstOrNull {
                    it.entry.kind == ModelManager.ModelKind.TTS && it.entry.language == language
                }
                LanguageModelRow(
                    language = language,
                    sttUsable = isUsable,
                    sttWired = isWired,
                    ttsHint = tts?.entry?.sizeHintBytes ?: (64 * 1024 * 1024)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        MetricSection("INSTALL (OFFLINE MANUAL SIDELOAD)") {
            ModelNoteRow("1.", "Tap [INSTALL] above to download & validate over Wi-Fi/data.", RetroWhite)
            ModelNoteRow("2.", "Or copy a Vosk model zip as stt-<LANG>.zip into filesDir/", RetroWhite)
            ModelNoteRow("3.", "Or extract directly into filesDir/models/<code>/ (en, hi, te, gu)", RetroWhite)
            ModelNoteRow("4.", "The STT column flips to READY immediately once validated.", RetroCyan)
            ModelNoteRow("5.", "Speech recognition then runs 100% offline in Airplane Mode.", RetroGreen)
        }

        Spacer(modifier = Modifier.height(14.dp))

        MetricSection("HONESTY NOTES") {
            ModelNoteRow("STT:", "Offline Vosk models available for English, Hindi, Telugu, Gujarati; others pending", RetroGray)
            ModelNoteRow("AIRPLANE:", "Recognition path is strictly on-device with zero cloud or network dependencies", RetroCyan)
            ModelNoteRow("TTS:", "Android device voices are DEVELOPMENT fallback; neural open-source TTS not bundled", RetroGray)
        }
    }
}

@Composable
private fun LanguageModelRow(
    language: Language,
    sttUsable: Boolean,
    sttWired: Boolean,
    ttsHint: Int
) {
    val sttLabel = when {
        sttUsable -> "STT READY"
        sttWired -> "NOT INSTALLED"
        else -> "NO OFFLINE MODEL"
    }
    val sttColor = when {
        sttUsable -> RetroGreen
        sttWired -> RetroOrange
        else -> RetroGray
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${language.displayName.uppercase()}  [${language.code}]",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = RetroCyan
            )
            Text(
                text = "$sttLabel  ·  TTS ANDROID",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = sttColor
            )
        }
        Text(
            text = if (sttUsable) "installed — pure offline speech recognition active" else "size hint ~${ttsHint / (1024 * 1024)} MB (TTS) — not shipped",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RetroGray
        )
    }
}

@Composable
internal fun ModelNoteRow(prefix: String, text: String, color: Color) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = prefix,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color
        )
    }
}