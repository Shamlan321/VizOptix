package com.vizoptix.com

import android.util.Log
import java.util.LinkedList

class TranslationManager(
    private val espServer: ESPServer,
    private val audioRecorder: AudioRecorder,
    private val sonioxClient: SonioxClient
) {
    // Terminal display logic ported from Python
    private val displayWidth = 160
    private val displayHeight = 80
    private val leftMargin = 3
    private val topMargin = 2
    private val lineSpacing = 10
    
    private val textWidth = displayWidth - (leftMargin * 2)
    private val charWidth = 6
    private val charsPerLine = textWidth / charWidth
    private val maxLines = (displayHeight - (topMargin * 2)) / lineSpacing
    
    private var currentLine = 0
    private val textBuffer = LinkedList<String>()
    
    private val textColor = 0x07E0 // Green
    private val bgColor = 0x0000   // Black

    private var isSonioxReady = false

    init {
        setupCallbacks()
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
                log("SonioxClient.onConnected callback triggered - starting ESP preparation")
                prepareEspForTranslation()
                log("SonioxClient.onConnected callback completed successfully")
            } catch (e: Exception) {
                log("ERROR: Exception in onConnected callback: ${e.message}")
                Log.e("TranslationManager", "Exception in onConnected callback", e)
            }
        }

        sonioxClient.onDisconnected = {
            log("SonioxClient.onDisconnected triggered")
            // Continue recording for debugging/analysis
        }

        sonioxClient.onError = { error ->
            log("SonioxClient.onError triggered: $error")
            displayTextOnEsp("Error: $error")
        }

        sonioxClient.onTranscription = { text, isFinal ->
            log("SonioxClient.onTranscription triggered: text='$text', isFinal=$isFinal")
            if (isFinal) {
                log("Soniox: Processing final transcription: '$text'")
                displayTextOnEsp(text)
            } else {
                log("Soniox: Ignoring non-final transcription: '$text'")
                // Optional: Show non-final text on ESP? Python code only shows final.
                // But Python code prints non-final to console.
                // We will stick to final for ESP to avoid flickering, as per Python logic.
            }
        }
        
        espServer.onClientConnected = { ip ->
            log("ESP Connected: $ip")
            initializeEspDisplay()
            // Wait for manual trigger
        }
        
        espServer.onClientDisconnected = {
            log("ESP Disconnected")
        }
    }

    fun start(apiKey: String) {
        espServer.start()
        log("Server started. Waiting for ESP connection...")
    }
    
    fun startTranslation() {
        log("Starting translation session...")
        audioRecorder.start()
        sonioxClient.connect()
    }

    fun stop() {
        audioRecorder.stop()
        sonioxClient.close()
        espServer.stop()
    }

    private fun initializeEspDisplay() {
        log("initializeEspDisplay() called - showing 'Translation Ready!'")
        espServer.clear(bgColor)
        espServer.text(leftMargin, topMargin, "Translation Ready!", 1, textColor)
        Thread.sleep(2000)
        espServer.clear(bgColor)
        currentLine = 0
        textBuffer.clear()
        log("initializeEspDisplay() completed")
    }

    private fun prepareEspForTranslation() {
        log("prepareEspForTranslation() called - showing 'Translation Active!'")
        espServer.clear(bgColor)
        espServer.text(leftMargin, topMargin, "Translation Active!", 1, textColor)
        espServer.text(leftMargin, topMargin + 20, "25x7 Text", 1, textColor)
        espServer.text(leftMargin, topMargin + 40, "WiFi Connected", 1, textColor)
        Thread.sleep(2000)
        espServer.clear(bgColor)
        currentLine = 0
        textBuffer.clear()
        log("prepareEspForTranslation() completed - screen cleared and ready for translation text")
    }

    private fun displayTextOnEsp(text: String) {
        log("displayTextOnEsp() called with text: '$text'")
        val wrappedLines = wrapText(text)

        for (line in wrappedLines) {
            log("Processing line: '$line'")
            textBuffer.add(line)

            // Keep buffer manageable
            if (textBuffer.size > maxLines * 3) {
                textBuffer.removeFirst()
            }

            if (currentLine >= maxLines) {
                log("Text buffer full, scrolling up")
                scrollUp()
            }

            val yPos = topMargin + (currentLine * lineSpacing)
            log("Sending TEXT command for line $currentLine: '$line' at position ($leftMargin, $yPos)")
            espServer.text(leftMargin, yPos, line, 1, textColor)
            currentLine++
        }
        log("displayTextOnEsp() completed for text: '$text'")
    }

    private fun wrapText(text: String): List<String> {
        if (text.length <= charsPerLine) return listOf(text)
        
        val wrappedLines = mutableListOf<String>()
        val words = text.split(" ")
        var currentLineText = ""
        
        for (word in words) {
            if (word.length > charsPerLine) {
                if (currentLineText.isNotEmpty()) {
                    wrappedLines.add(currentLineText.trim())
                    currentLineText = ""
                }
                var w = word
                while (w.length > charsPerLine) {
                    wrappedLines.add(w.substring(0, charsPerLine))
                    w = w.substring(charsPerLine)
                }
                if (w.isNotEmpty()) {
                    currentLineText = "$w "
                }
            } else {
                val testLine = "$currentLineText$word "
                if (testLine.length > charsPerLine) {
                    if (currentLineText.isNotEmpty()) {
                        wrappedLines.add(currentLineText.trim())
                    }
                    currentLineText = "$word "
                } else {
                    currentLineText = testLine
                }
            }
        }
        
        if (currentLineText.isNotEmpty()) {
            wrappedLines.add(currentLineText.trim())
        }
        
        return wrappedLines
    }

    private fun scrollUp() {
        espServer.clear(bgColor)
        val visibleLines = textBuffer.takeLast(maxLines)
        
        visibleLines.forEachIndexed { index, line ->
            val yPos = topMargin + (index * lineSpacing)
            espServer.text(leftMargin, yPos, line, 1, textColor)
        }
        
        currentLine = visibleLines.size
        // Show scroll indicator
        espServer.text(displayWidth - 18, topMargin, "^^^", 1, textColor)
    }
    
    private fun log(msg: String) {
        Log.d("TranslationManager", msg)
        // In a real app, we'd expose this via Flow/LiveData to UI
    }
}
