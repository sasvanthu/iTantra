package com.example.itantra

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.itantra.ui.main.MainViewModel
import com.example.itantra.ui.main.MainScreen
import com.example.itantra.ui.theme.ITantraTheme

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

class MainActivity : ComponentActivity() {

    private var currentViewModel: MainViewModel? = null

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra("action") ?: return
            val vm = currentViewModel ?: return
            Log.i("iTantraTest", "Received test action: $action")
            when (action) {
                "DISMISS_GUIDE" -> {
                    vm.toggleOnboardingGuide(false)
                }
                "START_HOST" -> {
                    val port = intent.getStringExtra("port")
                    if (!port.isNullOrBlank()) vm.setPort(port)
                    vm.startHost()
                }
                "CONNECT" -> {
                    val ip = intent.getStringExtra("ip") ?: ""
                    val port = intent.getStringExtra("port")
                    if (!port.isNullOrBlank()) vm.setPort(port)
                    vm.connectToHost(ip)
                }
                "DISCONNECT" -> {
                    vm.disconnect()
                }
                "SET_TRANSPORT" -> {
                    val modeStr = intent.getStringExtra("mode") ?: "WIFI"
                    val mode = when (modeStr.uppercase()) {
                        "BLE", "BLUETOOTH" -> com.example.itantra.transport.TransportType.BLUETOOTH
                        "MESH" -> com.example.itantra.transport.TransportType.MESH
                        "SIMULATED" -> com.example.itantra.transport.TransportType.SIMULATED
                        else -> com.example.itantra.transport.TransportType.WIFI
                    }
                    vm.setTransportMode(mode)
                }
                "SEND_TEXT" -> {
                    val rawText = intent.getStringExtra("text")
                    val b64Text = intent.getStringExtra("text_b64")
                    val text = when {
                        !b64Text.isNullOrBlank() -> String(android.util.Base64.decode(b64Text, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        !rawText.isNullOrBlank() -> rawText
                        else -> "HELLO"
                    }
                    val langStr = intent.getStringExtra("lang") ?: "ENGLISH"
                    val lang = when (langStr.uppercase()) {
                        "TAMIL" -> com.example.itantra.codec.Language.TAMIL
                        "HINDI" -> com.example.itantra.codec.Language.HINDI
                        else -> com.example.itantra.codec.Language.ENGLISH
                    }
                    vm.setLanguage(lang)
                    vm.setManualText(text)
                    vm.sendManual(text)
                }
                "SEND_EMERGENCY" -> {
                    val text = intent.getStringExtra("text") ?: "EMERGENCY ALERT"
                    vm.setManualText(text)
                    vm.sendEmergency()
                }
                "SET_PTT" -> {
                    val enabled = intent.getBooleanExtra("enabled", true)
                    if (vm.uiState.value.isPTTMode != enabled) {
                        vm.togglePTT()
                    }
                }
                "RUN_VOICE_TEST" -> {
                    val text = intent.getStringExtra("text") ?: "Emergency test"
                    val langStr = intent.getStringExtra("lang") ?: "ENGLISH"
                    val lang = when (langStr.uppercase()) {
                        "TAMIL" -> com.example.itantra.codec.Language.TAMIL
                        "HINDI" -> com.example.itantra.codec.Language.HINDI
                        else -> com.example.itantra.codec.Language.ENGLISH
                    }
                    vm.runSingleDeviceVoiceTest(text, lang)
                }
                "GET_STATUS" -> {
                    val s = vm.uiState.value
                    val m = s.linkMetrics
                    Log.i("iTantraTest", "STATUS: role=${s.role}, isServer=${s.isServer}, isConnected=${s.isConnected}, status=${s.connectionStatus}, subStatus=${s.subStatusLabel}, ip=${s.ipAddress}, port=${s.port}, remote=${s.remoteDeviceId}, mode=${s.transportMode}, voiceRoute=${s.voiceTransportRoute}, ptt=${s.isPTTMode}, emergency=${s.emergencyActive}, pktsSent=${m.packetsSent}, pktsRecv=${m.packetsReceived}, rtt=${m.roundTripTimeMs}, retr=${m.retransmissions}, txB=${m.transmittedBytes}, rxB=${m.receivedBytes}, dup=${m.duplicatePackets}, corrupted=${m.corruptedFrames}")
                }
            }
        }
    }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            currentViewModel?.updatePermissions(
                granted,
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            )
        }

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            currentViewModel?.updatePermissions(
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                grants.values.all { it }
            )
        }

    private fun bluetoothPermissionsNeeded(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun requestMissingPermissions() {
        val missing = bluetoothPermissionsNeeded().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            btPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter("com.example.itantra.CMD")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(testReceiver, filter)
        }

        setContent {
            ITantraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = viewModel()
                    currentViewModel = viewModel
                    MainScreen(viewModel)

                    LaunchedEffect(Unit) {
                        val audioGranted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        val btGranted = bluetoothPermissionsNeeded().all {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                        }
                        viewModel.updatePermissions(audioGranted, btGranted)

                        val prefs = getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
                        val isFirstRun = prefs.getBoolean("is_first_run", true)
                        if (isFirstRun) {
                            viewModel.toggleOnboardingGuide(true)
                            prefs.edit().putBoolean("is_first_run", false).apply()
                        }

                        if (!audioGranted) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        requestMissingPermissions()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(testReceiver)
        } catch (_: Exception) {}
    }
}