package com.vizoptix.com

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class DisplayManager(private val espServer: ESPServer) {
    
    // Terminal display configuration - must match ESP32 sketch defines
    private val displayWidth = 160
    private val displayHeight = 80
    private val lineHeight = 8  // pixels for textSize=1
    private val charWidth = 6
    
    // How many lines to keep EMPTY at the bottom so the newest line
    // sits visually above the edge of the screen (easier to read)
    private val bottomMarginLines = 2
    
    // Total addressable lines on screen
    private val totalLines = displayHeight / lineHeight          // 10
    // Lines we actually write into (remaining after bottom margin)
    private val usableLines = totalLines - bottomMarginLines     // 8
    private val charsPerLine = displayWidth / charWidth          // 26
    
    // Color scheme
    private val textColor = 0x07E0   // Green
    private val bgColor = 0x0000     // Black
    private val accentColor = 0x07FF // Cyan
    private val fontType = 1

    var onLog: ((String) -> Unit)? = null

    fun initializeDisplay() {
        log("initializeDisplay() called - showing welcome sequence with TEXTMODE")

        // Match Python's WiFiTerminalDisplay._initialize_display()
        // Send TEXTMODE command to set colors and font
        espServer.textMode(textColor, bgColor, fontType)
        
        // Send welcome lines using ADDLINE (hardware scroll)
        espServer.addLine("Translation Ready")
        espServer.addLine("WiFi Connected")
        
        // Wait 1.5 seconds then reinitialize to clear welcome messages
        log("Waiting 1.5 seconds before clearing screen...")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Thread.sleep(1500)
                // Reinitialize to clear welcome messages before real use
                espServer.textMode(textColor, bgColor, fontType)
                Thread.sleep(200)
                log("WiFiTerminalDisplay ready.")
            } catch (e: Exception) {
                log("Error in delayed clear: ${e.message}")
            }
        }
    }
    
    fun clearScreen() {
        log("Clearing display via TEXTMODE")
        espServer.textMode(textColor, bgColor, fontType)
    }

    fun displayText(text: String) {
        log(">>> DISPLAY TEXT: '$text' <<<")
        if (text.isBlank()) return

        // Word-wrap text and send each line as ADDLINE (hardware scrolling)
        val wrappedLines = wrapText(text)
        
        for (line in wrappedLines) {
            espServer.addLine(line)
        }

        // After the real content, push bottomMarginLines blank lines
        // so the text always sits in the upper portion of the screen and
        // the bottom margin stays clear.
        // The ESP hardware scroll handles the visual movement.
        repeat(bottomMarginLines) {
            espServer.addLine("")
        }
    }

    private fun wrapText(text: String): List<String> {
        val limit = charsPerLine
        if (text.length <= limit) return listOf(text)
        
        val wrappedLines = mutableListOf<String>()
        var currentLineText = ""
        
        for (word in text.split(" ")) {
            // Long word that must be split
            var remainingWord = word
            while (remainingWord.length > limit) {
                val spaceLeft = limit - currentLineText.length
                if (spaceLeft > 1) {
                    wrappedLines.add((currentLineText + remainingWord.substring(0, spaceLeft)).trim())
                    remainingWord = remainingWord.substring(spaceLeft)
                    currentLineText = ""
                } else {
                    if (currentLineText.isNotEmpty()) {
                        wrappedLines.add(currentLineText.trim())
                    }
                    currentLineText = ""
                }
            }
            
            val candidate = if (currentLineText.isNotEmpty()) {
                "$currentLineText $remainingWord".trimStart()
            } else {
                remainingWord
            }
            
            if (candidate.length <= limit) {
                currentLineText = candidate
            } else {
                if (currentLineText.isNotEmpty()) {
                    wrappedLines.add(currentLineText.trim())
                }
                currentLineText = remainingWord
            }
        }
        
        if (currentLineText.isNotEmpty()) {
            wrappedLines.add(currentLineText.trim())
        }
        
        return wrappedLines
    }
    
    private fun log(msg: String) {
        Log.d("DisplayManager", msg)
        onLog?.invoke("Display: $msg")
    }
}
