package com.vizoptix.com

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class SonioxClient(private val apiKey: String) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    
    var onTranscription: ((String, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var isReadyForAudio = false

    fun connect() {
        val request = Request.Builder()
            .url("wss://stt-rt.soniox.com/transcribe-websocket")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    Log.d("SonioxClient", ">>> WebSocket onOpen - STARTING <<<")
                    val jsonConfig = """{"api_key":"$apiKey","model":"stt-rt-preview","language_hints":["ko"],"enable_language_identification":true,"enable_speaker_diarization":false,"enable_endpoint_detection":true,"audio_format":"pcm_s16le","sample_rate":16000,"num_channels":1,"translation":{"type":"one_way","target_language":"en"}}"""
                    Log.d("SonioxClient", "Sending config: $jsonConfig")
                    webSocket.send(jsonConfig)

                    isReadyForAudio = true
                    Log.d("SonioxClient", "isReadyForAudio set to true")

                    Log.d("SonioxClient", "Invoking onConnected callback")
                    onConnected?.invoke()
                    Log.d("SonioxClient", "onConnected callback completed")
                } catch (e: Exception) {
                    Log.e("SonioxClient", "Exception in onOpen: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("SonioxClient", "Received message: '$text'")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("SonioxClient", "Closing: $code / $reason")
                isReadyForAudio = false
                webSocket.close(1000, null)
                onDisconnected?.invoke()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SonioxClient", "Error: ${t.message}")
                isReadyForAudio = false
                onError?.invoke("Connection error: ${t.message}")
                onDisconnected?.invoke()
            }
        })
    }



    fun sendAudio(data: ByteArray) {
        if (isReadyForAudio) {
            Log.d("SonioxClient", "Sending ${data.size} bytes of audio data")
            webSocket?.send(data.toByteString())
        } else {
            Log.d("SonioxClient", "Dropping ${data.size} bytes of audio data - Soniox not ready")
        }
    }

    private fun handleMessage(text: String) {
        try {
            val response = gson.fromJson(text, JsonObject::class.java)
            
            if (response.has("error_code")) {
                val errorMsg = response.get("error_message").asString
                onError?.invoke("API Error: $errorMsg")
                return
            }

            if (response.has("tokens")) {
                val tokens = response.getAsJsonArray("tokens")
                val finalTokens = StringBuilder()
                val nonFinalTokens = StringBuilder()

                tokens.forEach { tokenElement ->
                    val token = tokenElement.asJsonObject
                    if (token.get("translation_status").asString == "translation") {
                        val textContent = token.get("text").asString
                        if (token.get("is_final").asBoolean) {
                            finalTokens.append(textContent)
                        } else {
                            nonFinalTokens.append(textContent)
                        }
                    }
                }

                if (nonFinalTokens.isNotEmpty()) {
                    onTranscription?.invoke(nonFinalTokens.toString(), false)
                } else if (finalTokens.isNotEmpty()) {
                    onTranscription?.invoke(finalTokens.toString(), true)
                }
            }
        } catch (e: Exception) {
            Log.e("SonioxClient", "Parse error: ${e.message}")
        }
    }

    fun close() {
        try {
            isReadyForAudio = false
            webSocket?.send("") // Signal end of audio
            webSocket?.close(1000, "Goodbye")
        } catch (e: Exception) {
            // Ignore
        }
        webSocket = null
    }
}
