import os
import json
import queue
import signal
import sys
import threading
import time
from typing import Optional

import numpy as np
import sounddevice as sd
from websockets import ConnectionClosedOK
from websockets.sync.client import connect

from cont import WiFiESP32Display, WiFiTerminalDisplay

SONIOX_WEBSOCKET_URL = "wss://stt-rt.soniox.com/transcribe-websocket"


def get_config(api_key: str) -> dict:
    return {
        "api_key": api_key,
        "model": "stt-rt-preview",
        "language_hints": ["ko"],
        "enable_language_identification": True,
        "enable_speaker_diarization": False,
        "enable_endpoint_detection": True,
        "audio_format": "pcm_s16le",
        "sample_rate": 16000,
        "num_channels": 1,
        "translation": {
            "type": "one_way",
            "target_language": "en",
        },
    }


class MicrophoneStreamer:
    def __init__(self, sample_rate: int = 16000, channels: int = 1, block_size: int = 480):
        self.sample_rate = sample_rate
        self.channels = channels
        self.block_size = block_size  # frames per block
        self.q: queue.Queue[np.ndarray] = queue.Queue()
        self.stream: Optional[sd.InputStream] = None

    def _callback(self, indata, frames, time_info, status):
        if status:
            print(str(status), file=sys.stderr)
        pcm = np.clip(indata[:, 0], -1.0, 1.0)
        pcm = (pcm * 32767.0).astype(np.int16)
        self.q.put(pcm.tobytes())

    def __enter__(self):
        self.stream = sd.InputStream(
            samplerate=self.sample_rate,
            channels=self.channels,
            dtype="float32",
            blocksize=self.block_size,
            callback=self._callback,
        )
        self.stream.start()
        return self

    def __exit__(self, exc_type, exc, tb):
        if self.stream is not None:
            self.stream.stop()
            self.stream.close()
        with self.q.mutex:
            self.q.queue.clear()

    def read(self) -> Optional[bytes]:
        try:
            return self.q.get(timeout=0.5)
        except queue.Empty:
            return None


def get_local_ip():
    """Get the local IP address of this machine"""
    import socket
    try:
        # Connect to a remote address to determine local IP
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def run_session(api_key: str, server_port: int = 8888) -> None:
    config = get_config(api_key)

    # Get local IP address
    local_ip = get_local_ip()
    print(f"Starting WiFi server on {local_ip}:{server_port}")
    print(f"Make sure your ESP32 is configured to connect to: {local_ip}:{server_port}")
    print("="*60)

    # Initialize WiFi ESP display
    try:
        display = WiFiESP32Display(port=server_port, host='0.0.0.0')
        display.start_server()
        
        print("WiFi server started. Waiting for ESP32 connection...")
        
        # Wait for ESP32 to connect with timeout
        connection_timeout = 60  # seconds
        start_time = time.time()
        while not display.is_connected:
            if time.time() - start_time > connection_timeout:
                print(f"\nTimeout waiting for ESP32 connection after {connection_timeout}s")
                print("Please check:")
                print("1. ESP32 is powered on and running the WiFi sketch")
                print("2. ESP32 and PC are on the same WiFi network")
                print(f"3. ESP32 is configured to connect to {local_ip}:{server_port}")
                return
            time.sleep(0.5)
            print(".", end="", flush=True)
        
        print(f"\nESP32 connected from {display.client_address}!")
        
        # Initialize terminal display
        # You can adjust display size here based on your ESP32 screen
        # Common sizes: 160x80, 240x135, 320x240
        terminal = WiFiTerminalDisplay(display, width=160, height=80)
        
        print("Terminal display initialized. Starting translation session...")
        
    except Exception as e:
        print(f"Failed to start WiFi server: {e}")
        print("Please check that the port is not in use and try again.")
        return

    print("Connecting to Soniox...")
    with connect(SONIOX_WEBSOCKET_URL) as ws:
        ws.send(json.dumps(config))

        stop_event = threading.Event()

        def audio_sender():
            try:
                with MicrophoneStreamer(sample_rate=16000, channels=1, block_size=480) as mic:
                    while not stop_event.is_set():
                        chunk = mic.read()
                        if chunk is not None:
                            try:
                                ws.send(chunk)
                            except Exception:
                                break
            finally:
                try:
                    ws.send("")  # signal end-of-audio
                except Exception:
                    pass

        sender_thread = threading.Thread(target=audio_sender, daemon=True)
        sender_thread.start()

        print("Session started. Speak Korean; English translation will appear below and on ESP32. Press Ctrl+C to stop.\n")

        def handle_sigint(signum, frame):
            stop_event.set()
            raise KeyboardInterrupt

        signal.signal(signal.SIGINT, handle_sigint)

        last_line = ""  # new variable for deduplication

        try:
            while True:
                message = ws.recv()
                res = json.loads(message)

                if res.get("error_code") is not None:
                    error_msg = f"Error: {res['error_code']} - {res['error_message']}"
                    print(error_msg)
                    # Also send error to ESP32
                    try:
                        terminal.add_text(f"ERROR: {res['error_message']}")
                    except Exception as e:
                        print(f"ESP display error: {e}")
                    break

                tokens = res.get("tokens", [])
                translated_nonfinal: list[str] = []
                translated_final: list[str] = []
                for token in tokens:
                    if token.get("translation_status") == "translation":
                        text = token.get("text", "")
                        if not text:
                            continue
                        if token.get("is_final"):
                            translated_final.append(text)
                        else:
                            translated_nonfinal.append(text)

                if translated_nonfinal:
                    line = "".join(translated_nonfinal).lstrip()
                    if line:
                        print(f"\r{line}", end="", flush=True)

                elif translated_final:
                    line = "".join(translated_final).strip()
                    if line and line != last_line:  # deduplication
                        last_line = line
                        print(f"\r{line}")
                        try:
                            # Send finalized translation to ESP terminal via WiFi
                            terminal.add_text(line)
                            print(f"  [Sent to ESP32 via WiFi]")
                        except Exception as e:
                            print(f"\nESP display error: {e}")
                            # Try to reconnect if there's an error
                            if not display.is_connected:
                                print("Waiting for ESP32 to reconnect...")

                if res.get("finished"):
                    finish_msg = "Session finished."
                    print(f"\n{finish_msg}")
                    try:
                        terminal.add_text(finish_msg)
                    except Exception:
                        pass
                    break

        except ConnectionClosedOK:
            pass
        except KeyboardInterrupt:
            print("\nInterrupted by user.")
            try:
                terminal.add_text("Session ended by user")
            except Exception:
                pass
        except Exception as e:
            print(f"Error: {e}")
        finally:
            stop_event.set()
            sender_thread.join(timeout=1.0)
            try:
                display.close()
            except Exception:
                pass


def main():
    api_key = os.environ.get("SONIOX_API_KEY")
    if api_key is None:
        raise RuntimeError("Missing SONIOX_API_KEY.")

    # Allow custom port via environment variable
    server_port = int(os.environ.get("WIFI_SERVER_PORT", "8888"))
    
    run_session(api_key, server_port)


if __name__ == "__main__":
    main()