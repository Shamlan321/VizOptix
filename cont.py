import socket
import threading
import time
import logging
from typing import Optional, List
from collections import deque

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class WiFiESP32Display:
    """WiFi-based ESP32 display communication"""

    def __init__(self, port: int = 8888, host: str = '0.0.0.0', command_callback=None):
        self.host = host
        self.port = port
        self.server_socket: Optional[socket.socket] = None
        self.client_socket: Optional[socket.socket] = None
        self.client_address: Optional[tuple] = None
        self.is_running = False
        self.is_connected = False
        self.command_queue = deque()
        self.lock = threading.Lock()
        self.command_callback = command_callback

        # ── ACK gate ──────────────────────────────────────────────────────────
        # Each command waits for "OK\n" before the next one is sent.
        # This prevents the ESP's TCP buffer from filling up and causing
        # burst redraws that look like a full-screen refresh.
        self._ack_event = threading.Event()
        self._ack_event.set()          # starts open (no pending command)
        self._ACK_TIMEOUT = 2.0        # seconds to wait for OK before giving up

    # ── Server lifecycle ──────────────────────────────────────────────────────

    def start_server(self) -> None:
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind((self.host, self.port))
            self.server_socket.listen(1)
            self.is_running = True
            logger.info(f"Server started on {self.host}:{self.port}")
            logger.info("Waiting for ESP32 connection...")
            self.server_thread = threading.Thread(target=self._server_loop, daemon=True)
            self.server_thread.start()
        except Exception as e:
            logger.error(f"Failed to start server: {e}")
            raise

    def _server_loop(self) -> None:
        while self.is_running:
            try:
                if self.server_socket:
                    client_socket, client_address = self.server_socket.accept()
                    logger.info(f"ESP32 connected from {client_address}")
                    with self.lock:
                        self.client_socket = client_socket
                        self.client_address = client_address
                        self.is_connected = True
                    self._handle_client()
            except Exception as e:
                if self.is_running:
                    logger.error(f"Server error: {e}")
                    time.sleep(1)

    def _handle_client(self) -> None:
        try:
            # Wait for READY
            data = self.client_socket.recv(1024).decode().strip()
            if data != "READY":
                logger.warning(f"Expected READY, got: {data!r}")
                return
            logger.info("ESP32 is ready")

            # Drain any leftover queued commands from before connection
            self._process_command_queue()

            # Main loop: pump the queue and listen for ACKs / mode commands
            while self.is_connected and self.is_running:
                self._process_command_queue()

                self.client_socket.settimeout(0.05)
                try:
                    response = self.client_socket.recv(1024).decode().strip()
                    if response:
                        for line in response.splitlines():
                            line = line.strip()
                            if not line:
                                continue
                            if line == "OK":
                                # Release the ACK gate so the next command can go
                                self._ack_event.set()
                                logger.debug("ACK received")
                            elif self.command_callback:
                                try:
                                    self.command_callback(line)
                                except Exception as e:
                                    logger.error(f"Error in command callback: {e}")
                except socket.timeout:
                    pass
                except Exception as e:
                    logger.error(f"Recv error: {e}")
                    break

        except Exception as e:
            logger.error(f"Client handling error: {e}")
        finally:
            self._disconnect_client()

    def _process_command_queue(self) -> None:
        while self.command_queue and self.is_connected:
            try:
                command = self.command_queue.popleft()
                self._send_command(command)
            except IndexError:
                break

    def _send_command(self, command: str) -> bool:
        """Send one command, waiting for the previous ACK first."""
        # Wait until the previous command was acknowledged (or timed out)
        if not self._ack_event.wait(timeout=self._ACK_TIMEOUT):
            logger.warning(f"ACK timeout — sending anyway: {command}")

        # Close the gate before sending
        self._ack_event.clear()

        try:
            if self.client_socket and self.is_connected:
                self.client_socket.send((command + '\n').encode())
                logger.debug(f"Sent: {command}")
                return True
        except Exception as e:
            logger.error(f"Failed to send command: {e}")
            self._ack_event.set()   # reopen gate so we don't deadlock
            self._disconnect_client()
        return False

    def _disconnect_client(self) -> None:
        with self.lock:
            self._ack_event.set()   # unblock any waiting sender
            if self.client_socket:
                try:
                    self.client_socket.close()
                except Exception:
                    pass
                self.client_socket = None
                self.client_address = None
                self.is_connected = False
                logger.info("ESP32 disconnected")

    def send_command(self, command: str) -> bool:
        """Public: send immediately if connected, otherwise queue."""
        with self.lock:
            if self.is_connected:
                return self._send_command(command)
            else:
                self.command_queue.append(command)
                logger.info(f"Queued (not connected): {command}")
                return True

    # ── Display primitives ────────────────────────────────────────────────────

    def clear(self, color: int = 0x0000) -> bool:
        return self.send_command(f"CLEAR:{color}")

    def text(self, x: int, y: int, text: str, size: int = 1, color: int = 0xFFFF) -> bool:
        text = text.replace('\n', ' ').replace('\r', ' ')
        return self.send_command(f"TEXT:{x},{y},{size},{color},{text}")

    def set_font(self, font_type: int = 0) -> bool:
        return self.send_command(f"SETFONT:{font_type}")

    def text_mode(self, text_color: int = 0x07E0, bg_color: int = 0x0000, text_size: int = 1) -> bool:
        return self.send_command(f"TEXTMODE:{text_color},{bg_color},{text_size}")

    def add_line(self, text: str) -> bool:
        text = text.replace('\n', ' ').replace('\r', ' ')
        return self.send_command(f"ADDLINE:{text}")

    def set_color(self, text_color: int, bg_color: int = 0x0000) -> bool:
        return self.send_command(f"SETCOLOR:{text_color},{bg_color}")

    def close(self) -> None:
        self.is_running = False
        with self.lock:
            self._ack_event.set()
            if self.client_socket:
                try:
                    self.client_socket.close()
                except Exception:
                    pass
                self.client_socket = None
            if self.server_socket:
                try:
                    self.server_socket.close()
                except Exception:
                    pass
                self.server_socket = None
        logger.info("Server closed")


class WiFiTerminalDisplay:
    """Terminal-style display for WiFi ESP32 with hardware vertical scrolling."""

    # ── Layout constants (must match ESP32 sketch defines) ────────────────────
    DISPLAY_WIDTH   = 160
    DISPLAY_HEIGHT  = 80
    CHAR_WIDTH      = 6
    LINE_HEIGHT     = 8    # pixels; textSize=1

    # How many lines to keep EMPTY at the bottom so the newest line
    # sits visually above the edge of the screen (easier to read).
    BOTTOM_MARGIN_LINES = 2

    # Total addressable lines on screen
    TOTAL_LINES     = DISPLAY_HEIGHT // LINE_HEIGHT          # 10
    # Lines we actually write into (remaining after bottom margin)
    USABLE_LINES    = TOTAL_LINES - BOTTOM_MARGIN_LINES      # 8
    CHARS_PER_LINE  = DISPLAY_WIDTH // CHAR_WIDTH            # 26

    def __init__(self, display: WiFiESP32Display):
        self.display = display

        # Color scheme
        self.text_color    = 0x07E0   # green
        self.bg_color      = 0x0000   # black
        self.accent_color  = 0x07FF   # cyan
        self.font_type     = 1

        print(f"Display      : {self.DISPLAY_WIDTH}x{self.DISPLAY_HEIGHT} px")
        print(f"Usable lines : {self.USABLE_LINES}  (bottom {self.BOTTOM_MARGIN_LINES} rows reserved)")
        print(f"Chars/line   : {self.CHARS_PER_LINE}")

        self._initialize_display()

    def _initialize_display(self) -> None:
        """Send TEXTMODE then a couple of welcome lines."""
        self.display.text_mode(self.text_color, self.bg_color, self.font_type)
        time.sleep(0.3)
        self.display.add_line("Translation Ready")
        self.display.add_line("WiFi Connected")
        time.sleep(1.5)
        # Reinitialise to clear welcome messages before real use
        self.display.text_mode(self.text_color, self.bg_color, self.font_type)
        time.sleep(0.2)
        print("WiFiTerminalDisplay ready.")

    # ── Public API ────────────────────────────────────────────────────────────

    def add_text(self, text: str) -> bool:
        """Word-wrap text and send each line as a vertical-scrolling ADDLINE."""
        if not text:
            return True

        if text.strip().lower() == 'clear':
            self.clear_screen()
            return True

        for line in self._wrap(text):
            self.display.add_line(line)

        # After the real content, push USABLE_LINES worth of blank lines
        # so the text always sits in the upper portion of the screen and
        # the bottom margin stays clear.
        #
        # Actually: we keep a running line counter and only pad enough to
        # ensure BOTTOM_MARGIN_LINES empty rows sit below the last line.
        # Simplest correct approach: always send BOTTOM_MARGIN_LINES blank
        # lines after every translated sentence.  The ESP hardware scroll
        # handles the visual movement and blank rows cost only one SPI
        # command each (fillRect + no text).
        for _ in range(self.BOTTOM_MARGIN_LINES):
            self.display.add_line("")

        return True

    def clear_screen(self) -> None:
        self.display.text_mode(self.text_color, self.bg_color, self.font_type)
        time.sleep(0.1)
        print("Display cleared.")

    def set_color_scheme(self, scheme: str = "default") -> None:
        schemes = {
            "green" : (0x07E0, 0x07FF),
            "blue"  : (0x001F, 0x07FF),
            "white" : (0xFFFF, 0xFFE0),
            "red"   : (0xF800, 0xFFE0),
        }
        self.text_color, self.accent_color = schemes.get(scheme, (0x07E0, 0x07FF))
        self.display.set_color(self.text_color, self.bg_color)
        print(f"Color scheme: {scheme}")

    # ── Internal helpers ──────────────────────────────────────────────────────

    def _wrap(self, text: str) -> List[str]:
        """Word-wrap text to CHARS_PER_LINE, returning a list of lines."""
        limit = self.CHARS_PER_LINE
        if len(text) <= limit:
            return [text]

        lines: List[str] = []
        current = ""

        for word in text.split(' '):
            # Long word that must be split
            while len(word) > limit:
                space_left = limit - len(current)
                if space_left > 1:
                    lines.append((current + word[:space_left]).strip())
                    word = word[space_left:]
                    current = ""
                else:
                    if current:
                        lines.append(current.strip())
                    current = ""

            candidate = (current + " " + word).lstrip() if current else word
            if len(candidate) <= limit:
                current = candidate
            else:
                if current:
                    lines.append(current.strip())
                current = word

        if current:
            lines.append(current.strip())

        return lines


# ── Legacy compatibility ──────────────────────────────────────────────────────

class ESP32Display(WiFiESP32Display):
    """Legacy wrapper — accepts a serial-port-style path but uses WiFi."""
    def __init__(self, port_or_host='0.0.0.0', **kwargs):
        if isinstance(port_or_host, str) and not port_or_host.startswith('/dev/'):
            super().__init__(host=port_or_host, **kwargs)
        else:
            super().__init__(**kwargs)
        self.start_server()


class TerminalDisplay(WiFiTerminalDisplay):
    """Legacy wrapper."""
    pass