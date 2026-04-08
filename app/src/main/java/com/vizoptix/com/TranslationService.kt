package com.vizoptix.com

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TranslationService : Service() {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Core components moved from ViewModel
    private var espServer: ESPServer? = null
    private var audioRecorder: AudioRecorder? = null
    private var sonioxClient: SonioxClient? = null
    private var translationManager: TranslationManager? = null
    private var displayManager: DisplayManager? = null
    
    // State exposed to ViewModel
    var isSessionActive = false
        private set
    var isTranslationActive = false
        private set
    var espStatus = "Disconnected"
        private set
    var serverIp = "Unknown"
        private set
        
    // Callbacks for ViewModel updates
    var onLog: ((String) -> Unit)? = null
    var onStatusChanged: (() -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "TranslationServiceChannel"
        private const val NOTIFICATION_ID = 1
        
        // Singleton instance for binding from NotificationListener
        var instance: TranslationService? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): TranslationService = this@TranslationService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the service is killed, restart it
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopSession()
        releaseWakeLock()
    }

    fun startSession(apiKey: String) {
        if (isSessionActive) return
        
        try {
            log("Starting background session...")
            
            // Start Foreground Service immediately
            val notification = createNotification("Server Running", "Waiting for connections...")
            startForeground(NOTIFICATION_ID, notification)
            
            espServer = ESPServer(8888)
            audioRecorder = AudioRecorder()
            sonioxClient = SonioxClient(apiKey)
            displayManager = DisplayManager(espServer!!)
            
            translationManager = TranslationManager(espServer!!, audioRecorder!!, sonioxClient!!, displayManager!!)
            
            // Wire up logging
            espServer?.onLog = { log(it) }
            audioRecorder?.onLog = { log("Audio: $it") }
            audioRecorder?.onError = { log("Audio Error: $it") }
            displayManager?.onLog = { log(it) }
            
            sonioxClient?.onConnected = { log("Soniox Connected") }
            sonioxClient?.onDisconnected = { log("Soniox Disconnected") }
            sonioxClient?.onError = { log("Soniox Error: $it") }
            
            // TranslationManager handles ESP callbacks and sends to ESP
            translationManager?.onEspStatusChanged = { connected, ip ->
                if (connected) {
                    espStatus = "Connected ($ip)"
                    log("ESP Connected: $ip")
                    updateNotification("ESP Connected", "Ready for translation")
                } else {
                    espStatus = "Disconnected"
                    log("ESP Disconnected")
                    updateNotification("Server Running", "Waiting for ESP...")
                }
                onStatusChanged?.invoke()
            }
            
            translationManager?.start(apiKey)
            isSessionActive = true
            onStatusChanged?.invoke()
            
        } catch (e: Exception) {
            log("Failed to start service: ${e.message}")
            stopSession()
        }
    }

    fun startTranslation() {
        if (translationManager != null && !isTranslationActive) {
            translationManager?.startTranslation()
            isTranslationActive = true
            updateNotification("Translation Active", "Listening and translating...")
            onStatusChanged?.invoke()
        }
    }
    
    fun showNotificationOnEsp(appName: String, text: String) {
        if (isSessionActive && espStatus.startsWith("Connected")) {
            log("Forwarding notification: [$appName] $text")
            displayManager?.displayText("[$appName] $text")
        }
    }

    fun stopSession() {
        log("Stopping session...")
        translationManager?.stop()
        translationManager = null
        espServer = null
        audioRecorder = null
        sonioxClient = null
        displayManager = null
        
        isSessionActive = false
        isTranslationActive = false
        espStatus = "Disconnected"
        
        onStatusChanged?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Translation Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Use a standard icon for now
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VizOptix:TranslationServiceWakeLock"
        )
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }
}
