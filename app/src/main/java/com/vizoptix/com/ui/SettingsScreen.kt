package com.vizoptix.com.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vizoptix.com.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    
    // App selection state
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    
    // Load apps asynchronously
    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val apps = pm.getInstalledPackages(0)
            .filter { 
                val appInfo = it.applicationInfo
                if (appInfo != null) {
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    
                    // Logic: Must be (User App OR Updated System App) AND Launchable
                    // This filters out internal system services but keeps apps like WhatsApp, etc.
                    val isUserApp = !isSystemApp || isUpdatedSystemApp
                    val isLaunchable = pm.getLaunchIntentForPackage(it.packageName) != null
                    
                    isUserApp && isLaunchable
                } else {
                    false
                }
            }
            .map { 
                AppInfo(
                    name = it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName,
                    packageName = it.packageName,
                    isSelected = prefs.isAppSelected(it.packageName)
                )
            }
            .sortedBy { it.name }
        installedApps = apps
        isLoadingApps = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("General", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = { 
                    apiKey = it
                    prefs.apiKey = it
                },
                label = { Text("Soniox API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Notification Forwarding", style = MaterialTheme.typography.titleMedium)
            Text(
                "Select apps to forward notifications to ESP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            if (isLoadingApps) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(installedApps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.isSelected,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) {
                                        prefs.addSelectedApp(app.packageName)
                                    } else {
                                        prefs.removeSelectedApp(app.packageName)
                                    }
                                    // Update local state
                                    installedApps = installedApps.map {
                                        if (it.packageName == app.packageName) it.copy(isSelected = isChecked) else it
                                    }
                                }
                            )
                            Text(
                                text = app.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

data class AppInfo(
    val name: String,
    val packageName: String,
    val isSelected: Boolean
)
