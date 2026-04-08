package com.vizoptix.com

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    
    // Synchronization for ACK
    private var ackLatch: CountDownLatch? = null
    private val sendLock = Any()
    
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
            log("Waiting for READY signal...")
            val data = withContext(Dispatchers.IO) {
                `in`?.readLine()?.trim()
            }
            log("Received initial data: '$data'")
            
            if (data == "READY") {
                isConnected.set(true)
                onClientConnected?.invoke(clientIp ?: "Unknown")
                log("ESP32 is ready and handshake complete")
                
                // Process queued commands in a separate thread to not block reader
                GlobalScope.launch(Dispatchers.IO) {
                    log("Starting command queue processor")
                    processCommandQueue()
                }
                
                // Keep connection alive and read ACKs
                while (isConnected.get() && isRunning.get()) {
                    val response = withContext(Dispatchers.IO) {
                        try {
                            `in`?.readLine()?.trim()
                        } catch (e: Exception) {
                            log("Error reading from socket: ${e.message}")
                            null
                        }
                    }
                    
                    if (response != null) {
                        log("RAW RX: '$response'")
                        if (response == "OK") {
                            log("ACK received")
                            ackLatch?.countDown()
                        } else {
                            log("ESP Message: $response")
                        }
                    } else {
                        log("Socket closed or EOF")
                        break // Connection closed
                    }
                }
            } else {
                log("Invalid handshake: Expected 'READY', got '$data'")
            }
        } catch (e: Exception) {
            log("Client error: ${e.message}")
            e.printStackTrace()
        } finally {
            disconnectClient()
        }
    }

    private fun processCommandQueue() {
        while (isConnected.get()) {
            val command = commandQueue.poll()
            if (command != null) {
                log("Processing queued command: $command")
                sendCommandInternal(command)
            } else {
                Thread.sleep(50)
            }
        }
        log("Command queue processor stopped")
    }

    private fun sendCommandInternal(command: String) {
        synchronized(sendLock) {
            try {
                ackLatch = CountDownLatch(1)
                log("Sending raw: '$command'")
                out?.println(command)
                if (out?.checkError() == true) {
                    log("PrintWriter reported error after println")
                }
                
                // Wait for ACK with timeout (e.g., 2 seconds)
                log("Waiting for ACK...")
                val received = ackLatch?.await(2000, TimeUnit.MILLISECONDS) ?: false
                if (!received) {
                    log("TIMEOUT waiting for ACK for: $command")
                } else {
                    log("Command '$command' acknowledged")
                }
            } catch (e: Exception) {
                log("Failed to send command: ${e.message}")
                disconnectClient()
            }
        }
    }

    fun sendCommand(command: String) {
        log("ESP Command Request: $command")
        if (isConnected.get()) {
            commandQueue.offer(command)
            log("Command added to queue. Queue size: ${commandQueue.size}")
        } else {
            log("Command dropped (not connected): $command")
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

    fun textMode(textColor: Int = 0x07E0, bgColor: Int = 0x0000, textSize: Int = 1) {
        sendCommand("TEXTMODE:$textColor,$bgColor,$textSize")
    }

    fun addLine(text: String) {
        val sanitizedText = text.replace("\n", " ").replace("\r", " ")
        sendCommand("ADDLINE:$sanitizedText")
    }

    fun setColor(textColor: Int, bgColor: Int = 0x0000) {
        sendCommand("SETCOLOR:$textColor,$bgColor")
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
            ackLatch?.countDown() // Release any waiting threads
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
