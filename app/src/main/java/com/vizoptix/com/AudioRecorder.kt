package com.vizoptix.com

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    var onAudioData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (recordingJob?.isActive == true) {
            onLog?.invoke("Recording already active")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError?.invoke("Failed to get buffer size: $bufferSize")
            return
        }
        
        onLog?.invoke("Buffer size: $bufferSize")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("AudioRecord init failed - state: ${audioRecord?.state}")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            onLog?.invoke("Recording started successfully")

            // Collect PCM in a coroutine
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(bufferSize)
                var chunkCount = 0
                
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (read > 0) {
                        // Convert ShortArray to ByteArray (PCM 16-bit LE)
                        val byteBuffer = ByteBuffer.allocate(read * 2)
                        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) {
                            byteBuffer.putShort(buffer[i])
                        }
                        
                        onAudioData?.invoke(byteBuffer.array())
                        
                        if (chunkCount == 0) {
                            onLog?.invoke("First audio chunk: $read samples (${read * 2} bytes)")
                        }
                        chunkCount++
                    } else if (read < 0) {
                        onError?.invoke("Audio read error: $read")
                        break
                    }
                }
                
                onLog?.invoke("Recording loop ended. Total chunks: $chunkCount")
            }
        } catch (e: Exception) {
            onError?.invoke("Start recording exception: ${e.message}")
            Log.e("AudioRecorder", "Exception", e)
        }
    }

    fun stop() {
        onLog?.invoke("Stopping recording...")
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Stop exception", e)
        }
        audioRecord = null
        onLog?.invoke("Recording stopped")
    }
}
