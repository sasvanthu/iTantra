package com.example.itantra.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itantra.ui.theme.*

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroBackground)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (uiState.activeTab) {
                0 -> CommunicationScreen(viewModel)
                1 -> MetricsScreen(viewModel)
                2 -> CodecLabScreen(viewModel)
                3 -> SettingsScreen(viewModel)
                4 -> HardwareTestScreen()
                else -> CommunicationScreen(viewModel)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        BottomTabs(
            activeTab = uiState.activeTab,
            onTabSelected = { viewModel.setActiveTab(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}