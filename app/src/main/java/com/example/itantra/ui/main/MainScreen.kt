package com.example.itantra.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.itantra.ui.theme.*

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = RetroBackground
    ) {
        when (uiState.activeTab) {
            0 -> TalkScreen(viewModel)
            1 -> MetricsScreen(viewModel)
            2 -> ExperimentScreen(viewModel)
            3 -> SettingsScreen(viewModel)
            else -> TalkScreen(viewModel)
        }
    }
}
