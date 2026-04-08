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

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.vizoptix.com.ui.SettingsScreen

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
                    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                    else -> permission.key
                }
            }
            // In a real app, you might show a dialog or toast here
            println("Required permissions denied: $deniedNames")
        } else {
            checkBatteryOptimization()
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
                    AppNavigation()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            checkBatteryOptimization()
        }
    }
    
    private fun checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val intent = android.content.Intent()
            val packageName = packageName
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }
}

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf("main") }
    
    when (currentScreen) {
        "main" -> MainScreen(onNavigateToSettings = { currentScreen = "settings" })
        "settings" -> SettingsScreen(onBack = { currentScreen = "main" })
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    
    // Refresh API key from prefs when screen appears
    LaunchedEffect(Unit) {
        viewModel.apiKey = prefs.apiKey
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VizOptix Server",
                style = MaterialTheme.typography.headlineMedium
            )
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
        
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
            onClick = { 
                // Ensure API key is up to date
                viewModel.apiKey = prefs.apiKey
                viewModel.toggleSession() 
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (viewModel.isSessionActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (viewModel.isSessionActive) "Stop Server" else "Start Server")
        }
        
        if (viewModel.isSessionActive && viewModel.espStatus.startsWith("Connected")) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // Translation Button
            Button(
                onClick = { viewModel.startTranslation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isTranslationActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(if (viewModel.isTranslationActive) "Translation Active" else "Start Translation")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Notification Service Button
            Button(
                onClick = {
                    if (!isNotificationServiceEnabled(context)) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    } else {
                        // Service is enabled, just show a toast or log
                        // The service starts automatically by system when enabled
                        // We can trigger a test notification
                        viewModel.addLog("Notification Service is enabled")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text("Enable Notification Service")
            }
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

fun isNotificationServiceEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(context.packageName)
}
