package com.example.itantra.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.codec.Language
import com.example.itantra.data.ModelManager
import com.example.itantra.ui.theme.*

private val DISPLAYED_LANGUAGES = listOf(
    Language.ENGLISH, Language.HINDI, Language.TAMIL,
    Language.BENGALI, Language.TELUGU, Language.MARATHI,
    Language.GUJARATI, Language.KANNADA, Language.MALAYALAM, Language.ODIA
)

@Composable
fun ModelCenterScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val status by viewModel.modelStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "MODEL CENTER",
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
            MetricRow("STT engines:", "VOSK (offline)", RetroCyan)
            MetricRow("TTS engines:", "ANDROID DEVICE FALLBACK (not bundled)", RetroOrange)
            MetricRow("Policy:", "No model is ever reported usable unless real files are present", RetroGray)
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("PER-LANGUAGE TABLE") {
            for (language in DISPLAYED_LANGUAGES) {
                val stt = status.firstOrNull {
                    it.entry.kind == ModelManager.ModelKind.STT && it.entry.language == language
                }
                val tts = status.firstOrNull {
                    it.entry.kind == ModelManager.ModelKind.TTS && it.entry.language == language
                }
                LanguageModelRow(
                    language = language,
                    sttUsable = stt?.isUsable == true,
                    sttWired = language in setOf(Language.ENGLISH, Language.HINDI, Language.TAMIL),
                    ttsHint = tts?.entry?.sizeHintBytes ?: 0
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("INSTALL (OFFLINE DEMO)") {
            ModelNoteRow("1.", "Copy a Vosk model zip as stt-<LANG>.zip (e.g. stt-TAMIL.zip)", RetroWhite)
            ModelNoteRow("2.", "into Android/data/com.example.itantra/files/", RetroWhite)
            ModelNoteRow("3.", "Alternative: an already-unzipped model dir into files/models/<code>/ (en, hi, ta, …)", RetroWhite)
            ModelNoteRow("4.", "Re-open this tab — the STT column flips to READY", RetroCyan)
            ModelNoteRow("5.", "Recognition then runs fully offline in Speech Pipeline", RetroGreen)
        }

        Spacer(modifier = Modifier.height(12.dp))

        MetricSection("HONESTY NOTES") {
            ModelNoteRow("STT:", "Wired for en/hi/ta only; the other 7 languages are PLANNED — never claimed working", RetroGray)
            ModelNoteRow("TTS:", "Android device voices are DEVELOPMENT fallback; the SIH build targets open-source voices (Piper/eSpeak-NG) not bundled here", RetroGray)
            ModelNoteRow("SIZE:", "Shown sizes are catalog hints, not measured installs", RetroGray)
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
        sttWired -> "MODEL MISSING"
        else -> "STT PLANNED"
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
            text = if (sttUsable) "installed — offline recognition available" else "size hint ~${ttsHint / (1024 * 1024)} MB (TTS) — not shipped",
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