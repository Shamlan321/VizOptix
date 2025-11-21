package com.vizoptix.com

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ESPServer(private val port: Int = 8888) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var out: PrintWriter? = null
    private var `in`: BufferedReader? = null
    
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val commandQueue = ConcurrentLinkedQueue<String>()
    
    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                log("Server started on port $port")
                
                while (isRunning.get()) {
                    log("Waiting for ESP32 connection...")
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                log("Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            clientSocket = socket
            out = PrintWriter(socket.getOutputStream(), true)
            `in` = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            val clientIp = socket.inetAddress.hostAddress
            log("ESP32 connected from $clientIp")
            
            // Wait for READY signal
            val data = withContext(Dispatchers.IO) {
                `in`?.readLine()?.trim()
            }
            
            if (data == "READY") {
                isConnected.set(true)
                onClientConnected?.invoke(clientIp ?: "Unknown")
                log("ESP32 is ready")
                
                // Process queued commands
                processCommandQueue()
                
                // Keep connection alive
                while (isConnected.get() && isRunning.get()) {
                    // Check for incoming data (heartbeat/response)
                    if (socket.getInputStream().available() > 0) {
                         val response = `in`?.readLine()?.trim()
                         if (response != null) {
                             // Log response if needed
                         } else {
                             break // Connection closed
                         }
                    }
                    processCommandQueue()
                    Thread.sleep(10)
                }
            }
        } catch (e: Exception) {
            log("Client error: ${e.message}")
        } finally {
            disconnectClient()
        }
    }

    private fun processCommandQueue() {
        while (!commandQueue.isEmpty() && isConnected.get()) {
            val command = commandQueue.poll()
            if (command != null) {
                sendCommandInternal(command)
            }
        }
    }

    private fun sendCommandInternal(command: String) {
        try {
            out?.println(command)
            log("ESP Command Sent: $command")
        } catch (e: Exception) {
            log("Failed to send command: ${e.message}")
            disconnectClient()
        }
    }

    fun sendCommand(command: String) {
        log("ESP Command Request: $command")
        if (isConnected.get()) {
            GlobalScope.launch(Dispatchers.IO) {
                sendCommandInternal(command)
            }
        } else {
            commandQueue.offer(command)
            log("Queued (not connected): $command")
        }
    }

    fun clear(color: Int = 0) {
        sendCommand("CLEAR:$color")
    }

    fun text(x: Int, y: Int, text: String, size: Int = 1, color: Int = 0xFFFF) {
        val sanitizedText = text.replace("\n", " ").replace("\r", " ")
        sendCommand("TEXT:$x,$y,$size,$color,$sanitizedText")
    }
    
    fun setFont(fontType: Int) {
        sendCommand("SETFONT:$fontType")
    }

    private fun disconnectClient() {
        if (isConnected.getAndSet(false)) {
            try {
                clientSocket?.close()
                out?.close()
                `in`?.close()
            } catch (e: Exception) {
                // Ignore
            }
            clientSocket = null
            onClientDisconnected?.invoke()
            log("ESP32 disconnected")
        }
    }

    fun stop() {
        isRunning.set(false)
        disconnectClient()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        log("Server stopped")
    }

    private fun log(message: String) {
        Log.d("ESPServer", message)
        onLog?.invoke(message)
    }
}
