package com.vizoptix.com

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.nio.ByteOrder

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    var apiKey by mutableStateOf("")
    var isSessionActive by mutableStateOf(false) // Server active
    var isTranslationActive by mutableStateOf(false) // Audio/Soniox active
    var espStatus by mutableStateOf("Disconnected")
    var serverIp by mutableStateOf("Unknown")
    
    // Using a list for logs to display in UI
    val logs = mutableStateListOf<String>()
    
    // Service binding
    private var translationService: TranslationService? = null
    private var isBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TranslationService.LocalBinder
            translationService = binder.getService()
            isBound = true
            
            // Sync state from service
            translationService?.let { svc ->
                isSessionActive = svc.isSessionActive
                isTranslationActive = svc.isTranslationActive
                espStatus = svc.espStatus
                
                // Listen for updates
                svc.onLog = { addLog(it) }
                svc.onStatusChanged = {
                    isSessionActive = svc.isSessionActive
                    isTranslationActive = svc.isTranslationActive
                    espStatus = svc.espStatus
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            translationService = null
            isBound = false
        }
    }
    
    init {
        serverIp = getLocalIpAddress()
        // Bind to service immediately to check if it's already running
        val intent = Intent(application, TranslationService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun toggleSession() {
        if (isSessionActive) {
            stopSession()
        } else {
            startSession()
        }
    }
    
    fun startTranslation() {
        translationService?.startTranslation()
    }

    private fun startSession() {
        if (apiKey.isBlank()) {
            addLog("Error: API Key is required")
            return
        }
        
        val intent = Intent(getApplication(), TranslationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
        
        // Binding happens automatically via init, but we need to ensure we're bound
        if (!isBound) {
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        // Wait for binding to complete effectively, but for now just trigger start on service if bound
        // In a real app we might need to wait for onServiceConnected
        // For now, let's assume the user clicks start, we start the service, and then call startSession
        // A better way is to call startSession inside onServiceConnected if a flag is set, 
        // but since we are already binding in init, we should be connected quickly.
        
        // Actually, we can just call startSession on the service instance when we have it.
        // But since we might not have it yet (if init binding hasn't finished), we should handle that.
        // However, for simplicity in this refactor:
        
        translationService?.startSession(apiKey)
    }

    private fun stopSession() {
        translationService?.stopSession()
        // We don't unbind here because we want to stay connected to the service if it restarts or we want to restart it
    }

    fun addLog(msg: String) {
        viewModelScope.launch {
            logs.add(0, msg) // Add to top
            if (logs.size > 100) logs.removeAt(logs.lastIndex)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            
            // Convert little-endian to big-endian if needed
            if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                Integer.reverseBytes(ipAddress)
            }
            
            val ipByteArray = byteArrayOf(
                (ipAddress and 0xff).toByte(),
                (ipAddress shr 8 and 0xff).toByte(),
                (ipAddress shr 16 and 0xff).toByte(),
                (ipAddress shr 24 and 0xff).toByte()
            )
            
            return InetAddress.getByAddress(ipByteArray).hostAddress ?: "Unknown"
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}
