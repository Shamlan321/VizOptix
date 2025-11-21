package com.vizoptix.com

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vizoptix.com.ui.theme.VizOptixTheme

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.entries.filter { !it.value }
        if (deniedPermissions.isNotEmpty()) {
            // Show a message to the user that permissions are required
            val deniedNames = deniedPermissions.map { permission ->
                when (permission.key) {
                    Manifest.permission.RECORD_AUDIO -> "Microphone"
                    Manifest.permission.INTERNET -> "Internet"
                    Manifest.permission.ACCESS_NETWORK_STATE -> "Network State"
                    Manifest.permission.ACCESS_WIFI_STATE -> "WiFi State"
                    else -> permission.key
                }
            }
            // In a real app, you might show a dialog or toast here
            println("Required permissions denied: $deniedNames")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkPermissions()
        
        setContent {
            VizOptixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Real-time Translation Server",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = viewModel.apiKey,
            onValueChange = { viewModel.apiKey = it },
            label = { Text("Soniox API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Server IP: ${viewModel.serverIp}")
                Text("Port: 8888")
                Text("ESP Status: ${viewModel.espStatus}")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { viewModel.toggleSession() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.isSessionActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (viewModel.isSessionActive) "Stop Server" else "Start Server")
        }
        
        if (viewModel.isSessionActive && viewModel.espStatus.startsWith("Connected") && !viewModel.isTranslationActive) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.startTranslation() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Start Translation")
            }
        } else if (viewModel.isTranslationActive) {
             Spacer(modifier = Modifier.height(8.dp))
             Text(
                 text = "Translation Active (Mic On)",
                 color = MaterialTheme.colorScheme.primary,
                 style = MaterialTheme.typography.labelLarge
             )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Logs:", style = MaterialTheme.typography.titleMedium)
        
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            items(viewModel.logs) { log ->
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}
