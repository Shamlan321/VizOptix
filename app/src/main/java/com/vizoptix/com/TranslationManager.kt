package com.vizoptix.com

import android.util.Log
import java.util.LinkedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class TranslationManager(
    private val espServer: ESPServer,
    private val audioRecorder: AudioRecorder,
    private val sonioxClient: SonioxClient,
    private val displayManager: DisplayManager
) {

    var onEspStatusChanged: ((Boolean, String) -> Unit)? = null

    init {
        setupCallbacks()
        // Forward display logs
        displayManager.onLog = { msg -> log(msg) }
    }

    private fun setupCallbacks() {
        audioRecorder.onAudioData = { data ->
            sonioxClient.sendAudio(data)
        }

        audioRecorder.onError = { error ->
            log("Audio Error: $error")
        }

        sonioxClient.onConnected = {
            try {
                log("SonioxClient.onConnected callback triggered")
                log("Soniox connected successfully")
            } catch (e: Exception) {
                log("ERROR: Exception in onConnected callback: ${e.message}")
                Log.e("TranslationManager", "Exception in onConnected callback", e)
            }
        }

        sonioxClient.onDisconnected = {
            log("SonioxClient.onDisconnected triggered")
            log("Soniox disconnected - Audio recorder remains active")
        }

        sonioxClient.onError = { error ->
            log("SonioxClient.onError triggered: $error")
            displayManager.displayText("Error: $error")
        }

        sonioxClient.onTranscription = { text, isFinal ->
            log("SonioxClient.onTranscription triggered: text='$text', isFinal=$isFinal")
            log("Soniox: Processing transcription (isFinal=$isFinal): '$text'")
            displayManager.displayText(text)
        }
        
        espServer.onClientConnected = { ip ->
            log("ESP Connected callback received in TranslationManager: $ip")
            onEspStatusChanged?.invoke(true, ip)
            displayManager.initializeDisplay()
            log("ESP display initialized and ready for translations")
        }
        
        espServer.onClientDisconnected = {
            log("ESP Disconnected callback received in TranslationManager")
            onEspStatusChanged?.invoke(false, "")
        }
    }

    fun start(apiKey: String) {
        espServer.start()
        log("Server started. Waiting for ESP connection...")
    }
    
    fun startTranslation() {
        log("Starting translation session...")
        audioRecorder.start()
        log("Audio recorder started immediately")
        sonioxClient.connect()
    }

    fun stop() {
        audioRecorder.stop()
        sonioxClient.close()
        espServer.stop()
    }

    private fun log(msg: String) {
        Log.d("TranslationManager", msg)
        espServer.onLog?.invoke("TM: $msg")
    }
}
