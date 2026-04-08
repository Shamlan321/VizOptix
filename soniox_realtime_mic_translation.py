import os
import json
import queue
import signal
import sys
import threading
from typing import Optional

import numpy as np
import sounddevice as sd
from websockets import ConnectionClosedOK
from websockets.sync.client import connect

from cont import ESP32Display, TerminalDisplay

SONIOX_WEBSOCKET_URL = "wss://stt-rt.soniox.com/transcribe-websocket"


def get_config(api_key: str) -> dict:
    return {
        "api_key": api_key,
        "model": "stt-rt-preview",
        "language": "ko", # <--- CHANGE 1: Set the source language explicitly to Korean
        # "language_hints": ["ko"], # <--- REMOVE/COMMENT OUT: No longer needed with "language"
        "enable_language_identification": False, # <--- CHANGE 2: Disable language identification
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


def run_session(api_key: str) -> None:
    config = get_config(api_key)

    # Initialize ESP display first
    display = ESP32Display('/dev/ttyUSB0')  # adjust port if needed
    terminal = TerminalDisplay(display)

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

        print("Session started. Speak Korean; English translation will appear below. Press Ctrl+C to stop.\n")

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
                    print(f"Error: {res['error_code']} - {res['error_message']}")
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
                            # Send finalized translation to ESP terminal
                            terminal.add_text(line)
                        except Exception as e:
                            print(f"\nESP display error: {e}")

                if res.get("finished"):
                    print("\nSession finished.")
                    break

        except ConnectionClosedOK:
            pass
        except KeyboardInterrupt:
            print("\nInterrupted by user.")
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

    run_session(api_key)


if __name__ == "__main__":
    main()
