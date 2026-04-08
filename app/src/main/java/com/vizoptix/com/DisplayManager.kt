package com.vizoptix.com

import android.util.Log
import java.util.LinkedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class DisplayManager(private val espServer: ESPServer) {
    
    // Terminal display configuration
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

    var onLog: ((String) -> Unit)? = null

    fun initializeDisplay() {
        log("initializeDisplay() called - showing welcome sequence")

        // Match Python's WiFiTerminalDisplay._initialize_display()
        espServer.clear(bgColor)
        espServer.text(leftMargin, topMargin, "VizOptix Ready!", 1, textColor)
        espServer.text(leftMargin, topMargin + 12, "Waiting...", 1, textColor)
        
        // Wait 2 seconds then clear screen
        log("Waiting 2 seconds before clearing screen...")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Thread.sleep(2000)
                clearScreen()
            } catch (e: Exception) {
                log("Error in delayed clear: ${e.message}")
            }
        }
    }
    
    fun clearScreen() {
        log("Clearing ESP screen and resetting buffer")
        espServer.clear(bgColor)
        currentLine = 0
        textBuffer.clear()
    }

    fun displayText(text: String) {
        log(">>> DISPLAY TEXT: '$text' <<<")
        if (text.isBlank()) return

        val wrappedLines = wrapText(text)
        
        for (line in wrappedLines) {
            textBuffer.add(line)

            // Keep buffer manageable
            if (textBuffer.size > maxLines * 3) {
                textBuffer.removeFirst()
            }

            if (currentLine >= maxLines) {
                scrollUp()
            }

            val yPos = topMargin + (currentLine * lineSpacing)
            espServer.text(leftMargin, yPos, line, 1, textColor)
            currentLine++
        }
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
        Log.d("DisplayManager", msg)
        onLog?.invoke("Display: $msg")
    }
}
