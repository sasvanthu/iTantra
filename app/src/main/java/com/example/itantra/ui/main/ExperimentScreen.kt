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
fun ExperimentScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedPhrase by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf(Language.ENGLISH) }

    val testPhrases = mapOf(
        Language.ENGLISH to listOf(
            "I need help near the railway station.",
            "HELP! FIRE!",
            "There is an accident on the main road.",
            "Please send water and food.",
            "The building is damaged."
        ),
        Language.HINDI to listOf(
            "मुझे रेलवे स्टेशन के पास मदद चाहिए।",
            "बचाओ! आग लगी है!",
            "मुख्य सड़क पर दुर्घटना हुई है।",
            "कृपया पानी और भोजन भेजें।",
            "इमारत क्षतिग्रस्त है।"
        ),
        Language.TAMIL to listOf(
            "எனக்கு ரயில் நிலையம் அருகில் உதவி தேவை.",
            "உதவி! தீ விபத்து!",
            "முக்கிய சாலையில் விபத்து நடந்துள்ளது.",
            "தயவுசெய்து தண்ணீரும் உணவும் அனுப்புங்கள்.",
            "கட்டிடம் சேதமடைந்துள்ளது."
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "EXPERIMENT DASHBOARD",
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = RetroAmber,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Language selector
        Text(
            text = "LANGUAGE:",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = RetroGray
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Language.entries.filter { it != Language.UNKNOWN }.forEach { lang ->
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (selectedLanguage == lang) RetroAmber else RetroSurface
                        )
                        .border(
                            1.dp,
                            if (selectedLanguage == lang) RetroAmber else RetroDarkGray,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { selectedLanguage = lang }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = lang.displayName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (selectedLanguage == lang) RetroBackground else RetroGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Test phrases
        Text(
            text = "TEST PHRASES:",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = RetroGray
        )
        Spacer(modifier = Modifier.height(8.dp))

        testPhrases[selectedLanguage]?.forEach { phrase ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(
                        1.dp,
                        if (selectedPhrase == phrase) RetroAmber else RetroDarkGray,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { selectedPhrase = phrase },
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedPhrase == phrase) RetroSurfaceVariant else RetroSurface
                ),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = phrase,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (selectedPhrase == phrase) RetroAmber else RetroWhite,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Run button
        Button(
            onClick = { viewModel.runExperiment(selectedPhrase) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RetroAmber),
            shape = RoundedCornerShape(8.dp),
            enabled = selectedPhrase.isNotBlank()
        ) {
            Text(
                text = "RUN TEST",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = RetroBackground
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Results
        if (uiState.codecComparison != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, RetroAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(containerColor = RetroSurface),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "RESULTS",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = RetroAmber
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val comp = uiState.codecComparison!!
                    ResultRow("Metric", "Value")
                    ResultRow("Original", "${comp.originalUtf8Bytes} bytes")
                    ResultRow("Retro Encoded", "${comp.retroEncodedBytes} bytes")
                    ResultRow("Token Bytes", "${comp.tokenEncodedBytes} bytes")
                    ResultRow("Phoneme Bytes", "${comp.phonemeEncodedBytes} bytes")
                    ResultRow("Compression", "${String.format("%.1f", comp.compressionPercentage)}%")
                    ResultRow("Packets", "${comp.packetCount}")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ResultRow(label: String, value: String) {
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
            color = RetroCyan
        )
    }
}
