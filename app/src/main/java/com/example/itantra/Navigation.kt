package com.example.itantra

import androidx.compose.runtime.Composable
import com.example.itantra.ui.main.MainScreen
import com.example.itantra.ui.main.MainViewModel

@Composable
fun MainNavigation(viewModel: MainViewModel) {
  MainScreen(viewModel)
}
