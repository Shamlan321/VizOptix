package com.vizoptix.com

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
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
    
    private var translationManager: TranslationManager? = null
    
    init {
        serverIp = getLocalIpAddress()
    }

    fun toggleSession() {
        if (isSessionActive) {
            stopSession()
        } else {
            startSession()
        }
    }
    
    fun startTranslation() {
        if (translationManager != null && !isTranslationActive) {
            translationManager?.startTranslation()
            isTranslationActive = true
        }
    }

    private fun startSession() {
        if (apiKey.isBlank()) {
            addLog("Error: API Key is required")
            return
        }
        
        try {
            addLog("Starting session...")
            
            val espServer = ESPServer(8888)
            val audioRecorder = AudioRecorder()
            val sonioxClient = SonioxClient(apiKey)
            
            translationManager = TranslationManager(espServer, audioRecorder, sonioxClient)
            
            // Wire up logging to UI
            espServer.onLog = { addLog(it) }
            espServer.onClientConnected = { 
                espStatus = "Connected ($it)"
                addLog("ESP Connected: $it")
            }
            espServer.onClientDisconnected = { 
                espStatus = "Disconnected"
                addLog("ESP Disconnected")
            }
            
            audioRecorder.onError = { addLog("Audio Error: $it") }
            audioRecorder.onLog = { addLog("Audio: $it") }
            
            sonioxClient.onConnected = { addLog("Soniox Connected") }
            sonioxClient.onDisconnected = { addLog("Soniox Disconnected") }
            sonioxClient.onError = { addLog("Soniox Error: $it") }
            sonioxClient.onTranscription = { text, isFinal ->
                val prefix = if (isFinal) "[FINAL]" else "[PARTIAL]"
                addLog("$prefix $text")
            }
            
            translationManager?.start(apiKey)
            isSessionActive = true
            
        } catch (e: Exception) {
            addLog("Failed to start: ${e.message}")
            stopSession()
        }
    }

    private fun stopSession() {
        addLog("Stopping session...")
        translationManager?.stop()
        translationManager = null
        isSessionActive = false
        isTranslationActive = false
        espStatus = "Disconnected"
    }

    private fun addLog(msg: String) {
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
        stopSession()
    }
}
