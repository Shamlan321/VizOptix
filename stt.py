#!/usr/bin/env python3
"""
Real-time microphone transcription using Soniox API.
Requires: pip install soniox pyaudio
"""

import os
import sys
import queue
import threading
import io

import pyaudio
from soniox import SonioxClient
from soniox.types import RealtimeSTTConfig
from soniox.utils import render_tokens


# Audio settings
SAMPLE_RATE = 16000
CHANNELS = 1
CHUNK_DURATION_MS = 100  # 100ms chunks
CHUNK_SIZE = int(SAMPLE_RATE * CHUNK_DURATION_MS / 1000)  # samples per chunk
FORMAT = pyaudio.paInt16  # 16-bit PCM


class MicrophoneAudioStream:
    """Captures microphone audio and yields chunks compatible with Soniox."""
    
    def __init__(self):
        self.audio_queue = queue.Queue()
        self.is_recording = False
        self.pa = pyaudio.PyAudio()
        
    def start(self):
        """Start recording from microphone in a background thread."""
        self.is_recording = True
        
        def callback(in_data, frame_count, time_info, status):
            """PyAudio callback - puts audio data into queue."""
            self.audio_queue.put(in_data)
            return (None, pyaudio.paContinue)
        
        self.stream = self.pa.open(
            format=FORMAT,
            channels=CHANNELS,
            rate=SAMPLE_RATE,
            input=True,
            frames_per_buffer=CHUNK_SIZE,
            stream_callback=callback,
        )
        
        self.stream.start_stream()
        
    def stop(self):
        """Stop recording."""
        self.is_recording = False
        if hasattr(self, 'stream'):
            self.stream.stop_stream()
            self.stream.close()
        self.pa.terminate()
        
    def __iter__(self):
        """Make the stream iterable - yields audio chunks."""
        while self.is_recording:
            try:
                chunk = self.audio_queue.get(timeout=0.1)
                yield chunk
            except queue.Empty:
                continue
                
    def __enter__(self):
        self.start()
        return self
        
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()
        return False


def get_config() -> RealtimeSTTConfig:
    """Configure real-time STT settings."""
    config = RealtimeSTTConfig(
        model="stt-rt-v4",
        # Language hints improve accuracy - adjust for your use case
        language_hints=["en"],
        # Enable endpoint detection for faster finalization when you pause
        enable_endpoint_detection=True,
        # Raw PCM format since we're streaming from microphone
        audio_format="pcm_s16le",
        sample_rate=SAMPLE_RATE,
        num_channels=CHANNELS,
    )
    return config


def transcribe_microphone():
    """Main transcription loop."""
    api_key = os.environ.get("SONIOX_API_KEY")
    if not api_key:
        print("Error: Set SONIOX_API_KEY environment variable")
        print("Example: $env:SONIOX_API_KEY='your_key'  (PowerShell)")
        print("         export SONIOX_API_KEY='your_key'  (Bash)")
        sys.exit(1)

    client = SonioxClient()
    config = get_config()
    
    print("=" * 50)
    print("🎤 Real-time Microphone Transcription")
    print("=" * 50)
    print("Speak into your microphone...")
    print("Press Ctrl+C to stop")
    print("-" * 50)
    
    try:
        with MicrophoneAudioStream() as mic_stream:
            print("Connecting to Soniox...")
            
            with client.realtime.stt.connect(config=config) as session:
                final_tokens = []
                
                # Start audio streaming thread
                def send_audio():
                    """Send microphone chunks to Soniox."""
                    for chunk in mic_stream:
                        session.send_bytes(chunk)
                
                audio_thread = threading.Thread(target=send_audio, daemon=True)
                audio_thread.start()
                
                print("🟢 Listening... (speak now)\n")
                
                # Process transcription events
                for event in session.receive_events():
                    # Handle errors
                    if event.error_code:
                        print(f"\n⚠️  Error: {event.error_code} - {event.error_message}")
                        continue
                    
                    # Separate final and non-final tokens
                    non_final_tokens = []
                    for token in event.tokens:
                        if token.is_final:
                            final_tokens.append(token)
                        else:
                            non_final_tokens.append(token)
                    
                    # Render and display transcription
                    transcript = render_tokens(final_tokens, non_final_tokens)
                    
                    # Clear line and print updated transcript
                    # Using \r to overwrite the line for live updates
                    print(f"\r📝 {transcript}", end="", flush=True)
                    
                    # Session finished (shouldn't happen in streaming mode unless error)
                    if event.finished:
                        print("\n\nSession finished.")
                        break
                        
    except KeyboardInterrupt:
        print("\n\n🛑 Stopping transcription...")
    except Exception as e:
        print(f"\n\n❌ Error: {e}")
        raise


if __name__ == "__main__":
    transcribe_microphone()