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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.codec.CodecLabResult
import com.example.itantra.codec.Language
import com.example.itantra.ui.theme.*

@Composable
fun CodecLabScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val lab by viewModel.codecLab.collectAsState()

    val encodeResult = lab.encodeResult
    val decodeResult = lab.decodeResult

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "RETRO CODEC LAB",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "BASELINE UTF-8 vs RETRO BINARY FRAME",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = RetroGray,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ------- Language selector -------
        LabLabel("LANGUAGE")
        Spacer(modifier = Modifier.height(6.dp))
        Row {
            Language.entries.filter { it != Language.UNKNOWN }.forEach { lang ->
                LabChip(
                    label = lang.displayName,
                    selected = lab.language == lang,
                    onClick = { viewModel.setLabLanguage(lang) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ------- Text input -------
        LabLabel("SOURCE TEXT")
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = lab.input,
            onValueChange = { viewModel.setLabInput(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Type a sentence…", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = RetroDarkGray)
            },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = RetroWhite
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RetroAmber,
                unfocusedBorderColor = RetroDarkGray,
                cursorColor = RetroAmber,
                focusedContainerColor = RetroSurface,
                unfocusedContainerColor = RetroSurface
            ),
            minLines = 2
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ------- Buttons -------
        Row {
            LabButton("TEST EN", { viewModel.testSentence(Language.ENGLISH) }, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(6.dp))
            LabButton("TEST TA", { viewModel.testSentence(Language.TAMIL) }, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(6.dp))
            LabButton("TEST HI", { viewModel.testSentence(Language.HINDI) }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            LabButton("ENCODE", { viewModel.runLabEncode() }, enabled = lab.input.isNotBlank() && !lab.running, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(6.dp))
            LabButton("DECODE", { viewModel.runLabDecode() }, enabled = lab.payload != null && !lab.running, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(6.dp))
            LabButton("ROUNDTRIP", { viewModel.runLabRoundtrip(lab.input, lab.language) },
                enabled = lab.input.isNotBlank() && !lab.running, primary = true, modifier = Modifier.weight(1f))
        }

        if (lab.running) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "PROCESSING…",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = RetroAmber
            )
        }

        // ------- Comparison panel -------
        if (encodeResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            ComparisonPanel(encodeResult)
        }

        // ------- Decode panel -------
        if (decodeResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            DecodePanel(decodeResult)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ComparisonPanel(r: CodecLabResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            LabPanelTitle("ENCODING  //  ${r.language.displayName}")
            Spacer(modifier = Modifier.height(8.dp))

            LabRow("ORIGINAL", r.originalText, color = RetroWhite)
            if (r.normalizedText != r.originalText) {
                LabRow("NORMALIZED", r.normalizedText, color = RetroGray)
            }

            Spacer(modifier = Modifier.height(6.dp))
            LabSep()

            LabRow("UTF-8 (BASELINE)", "${r.originalUtf8Bytes} bytes", color = RetroCyan)
            LabRow("RETRO TOKENS", "${r.tokenEncodedBytes} bytes", color = RetroCyan)
            LabRow("RETRO PHONEMES", "${r.phonemeEncodedBytes} bytes", color = RetroCyan)
            LabRow("RETRO FRAME (incl CRC)", "${r.encodedBytes} bytes", color = RetroAmber, bold = true)

            val percentColor = when {
                r.compressionPercent > 0 -> RetroGreen
                r.compressionPercent < 0 -> RetroOrange
                else -> RetroGray
            }
            LabRow("DELTA vs UTF-8", if (r.encodedBytes - r.originalUtf8Bytes >= 0) "+${r.encodedBytes - r.originalUtf8Bytes}" else "${r.encodedBytes - r.originalUtf8Bytes} bytes", color = percentColor)

            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when (r.modeLabel) {
                            "REDUCTION" -> RetroGreen.copy(alpha = 0.15f)
                            "OVERHEAD" -> RetroOrange.copy(alpha = 0.15f)
                            else -> RetroGray.copy(alpha = 0.15f)
                        }
                    )
            ) {
                Text(
                    text = if (r.modeLabel == "OVERHEAD")
                        "OVERHEAD  (short-message packet overhead is normal)"
                    else
                        "${r.modeLabel}: ${String.format("%.1f", r.compressionPercent)}% vs UTF-8",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (r.modeLabel == "OVERHEAD") RetroOrange else RetroGreen,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            LabSep()

            LabRow("PACKETS", "${r.packetCount}  (${r.packetBytes} serialized bytes)", color = RetroCyan)
            LabRow("IMPORTANCE", r.messageImportance.name, color = when (r.messageImportance.name) {
                "CRITICAL" -> RetroRed
                "HIGH" -> RetroOrange
                "LOW" -> RetroGray
                else -> RetroCyan
            })
            LabRow("TOKEN MIX", "DICT ${r.dictionaryTokens} · ESC ${r.escapedTokens} · PUNCT ${r.punctTokens}", color = RetroCyan)
            LabRow("PREDICTED", "${r.predictedTokens} token(s)", color = if (r.predictedTokens > 0) RetroGreen else RetroGray)
        }
    }
}

@Composable
private fun DecodePanel(r: CodecLabResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = RetroSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            LabPanelTitle("DECODE  //  INTEGRITY CHECK")
            Spacer(modifier = Modifier.height(8.dp))

            LabRow("DECODED", r.decodedText.ifBlank { "—" }, color = RetroGreen)

            Spacer(modifier = Modifier.height(6.dp))
            LabSep()

            val ok = r.exactMatch
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (ok) RetroGreen.copy(alpha = 0.15f) else RetroRed.copy(alpha = 0.15f))
                    .border(1.dp, if (ok) RetroGreen.copy(alpha = 0.5f) else RetroRed.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            ) {
                Text(
                    text = if (ok) "✓ SUCCESS — EXACT MATCH" else "✗ FAILED",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ok) RetroGreen else RetroRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            LabSep()

            LabRow("ENCODE", if (r.encodeMs >= 0) "${r.encodeMs} ms" else "— ms", color = RetroAmber)
            LabRow("PACKETIZE", "${r.packetizeMs} ms", color = RetroAmber)
            LabRow("DECODE", if (r.decodeMs >= 0) "${r.decodeMs} ms" else "— ms", color = RetroAmber)
            LabRow("TOTAL", "${r.encodeMs + r.packetizeMs + r.decodeMs} ms", color = RetroAmber, bold = true)
        }
    }
}

@Composable
private fun LabLabel(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = RetroGray,
        letterSpacing = 1.sp
    )
}

@Composable
private fun LabPanelTitle(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = RetroAmber,
        letterSpacing = 1.sp
    )
}

@Composable
private fun LabRow(label: String, value: String, color: Color = RetroCyan, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RetroGray,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = color,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun LabSep() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(RetroDarkGray.copy(alpha = 0.6f))
    )
}

@Composable
private fun LabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) RetroAmber else RetroSurface)
            .border(1.dp, if (selected) RetroAmber else RetroDarkGray, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) RetroBackground else RetroGray
        )
    }
}

@Composable
private fun LabButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) RetroAmber else RetroSurfaceVariant,
            contentColor = if (primary) RetroBackground else RetroCyan,
            disabledContainerColor = RetroSurface,
            disabledContentColor = RetroDarkGray
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}