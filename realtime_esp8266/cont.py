import socket
import threading
import time
import logging
import textwrap
from typing import Optional, List
from collections import deque

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class WiFiESP32Display:
    """WiFi-based ESP32 display communication"""
    
    def __init__(self, port: int = 8888, host: str = '0.0.0.0'):
        self.host = host
        self.port = port
        self.server_socket: Optional[socket.socket] = None
        self.client_socket: Optional[socket.socket] = None
        self.client_address: Optional[tuple] = None
        self.is_running = False
        self.is_connected = False
        self.command_queue = deque()
        self.lock = threading.Lock()
        
    def start_server(self) -> None:
        """Start the TCP server"""
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind((self.host, self.port))
            self.server_socket.listen(1)
            self.is_running = True
            
            logger.info(f"Server started on {self.host}:{self.port}")
            logger.info("Waiting for ESP32 connection...")
            
            # Start server thread
            self.server_thread = threading.Thread(target=self._server_loop, daemon=True)
            self.server_thread.start()
            
        except Exception as e:
            logger.error(f"Failed to start server: {e}")
            raise
    
    def _server_loop(self) -> None:
        """Main server loop to handle connections"""
        while self.is_running:
            try:
                if self.server_socket:
                    # Accept connection
                    client_socket, client_address = self.server_socket.accept()
                    logger.info(f"ESP32 connected from {client_address}")
                    
                    with self.lock:
                        self.client_socket = client_socket
                        self.client_address = client_address
                        self.is_connected = True
                    
                    # Handle this client
                    self._handle_client()
                    
            except Exception as e:
                if self.is_running:
                    logger.error(f"Server error: {e}")
                    time.sleep(1)
    
    def _handle_client(self) -> None:
        """Handle communication with connected ESP32"""
        try:
            # Wait for READY signal
            data = self.client_socket.recv(1024).decode().strip()
            if data == "READY":
                logger.info("ESP32 is ready")
                
                # Process queued commands
                self._process_command_queue()
                
                # Keep connection alive and handle responses
                while self.is_connected and self.is_running:
                    try:
                        # Check for queued commands
                        self._process_command_queue()
                        
                        # Check for responses (non-blocking)
                        self.client_socket.settimeout(0.1)
                        try:
                            response = self.client_socket.recv(1024).decode().strip()
                            if response:
                                logger.debug(f"ESP32 response: {response}")
                        except socket.timeout:
                            pass  # No response available, continue
                        
                        time.sleep(0.01)  # Small delay
                        
                    except Exception as e:
                        logger.error(f"Error in client handler: {e}")
                        break
                        
        except Exception as e:
            logger.error(f"Client handling error: {e}")
        finally:
            self._disconnect_client()
    
    def _process_command_queue(self) -> None:
        """Process any queued commands"""
        while self.command_queue and self.is_connected:
            try:
                command = self.command_queue.popleft()
                self._send_command(command)
            except IndexError:
                break
    
    def _send_command(self, command: str) -> bool:
        """Send command to ESP32"""
        try:
            if self.client_socket and self.is_connected:
                self.client_socket.send((command + '\n').encode())
                logger.debug(f"Sent command: {command}")
                return True
        except Exception as e:
            logger.error(f"Failed to send command: {e}")
            self._disconnect_client()
        return False
    
    def _disconnect_client(self) -> None:
        """Disconnect current client"""
        with self.lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except:
                    pass
                self.client_socket = None
                self.client_address = None
                self.is_connected = False
                logger.info("ESP32 disconnected")
    
    def send_command(self, command: str) -> bool:
        """Public method to send command (queues if not connected)"""
        with self.lock:
            if self.is_connected:
                return self._send_command(command)
            else:
                # Queue command for when connection is established
                self.command_queue.append(command)
                logger.info(f"Queued command (not connected): {command}")
                return True
    
    def clear(self, color: int = 0x0000) -> bool:
        """Clear display with specified color"""
        return self.send_command(f"CLEAR:{color}")
    
    def text(self, x: int, y: int, text: str, size: int = 1, color: int = 0xFFFF) -> bool:
        """Display text at specified position"""
        # Escape any problematic characters
        text = text.replace('\n', ' ').replace('\r', ' ')
        return self.send_command(f"TEXT:{x},{y},{size},{color},{text}")
    
    def set_font(self, font_type: int = 0) -> bool:
        """Set display font type"""
        return self.send_command(f"SETFONT:{font_type}")
    
    def close(self) -> None:
        """Close server and all connections"""
        self.is_running = False
        
        with self.lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except:
                    pass
                self.client_socket = None
            
            if self.server_socket:
                try:
                    self.server_socket.close()
                except:
                    pass
                self.server_socket = None
        
        logger.info("Server closed")


class WiFiTerminalDisplay:
    """Terminal-style display for WiFi ESP32 with advanced features"""
    
    def __init__(self, display: WiFiESP32Display, 
                 width: int = 160, height: int = 80):
        self.display = display
        
        # Display specifications (adjustable for different screen sizes)
        self.display_width = width
        self.display_height = height
        
        # Text specifications with padding
        self.left_margin = 3
        self.top_margin = 2
        self.line_spacing = 10
        
        self.text_width = self.display_width - (self.left_margin * 2)
        self.text_height = self.display_height - (self.top_margin * 2)
        
        self.char_width = 6
        self.chars_per_line = self.text_width // self.char_width
        self.max_lines = self.text_height // self.line_spacing
        
        self.current_line = 0
        self.text_buffer = []
        
        # Color scheme
        self.text_color = 0x07E0  # Green
        self.bg_color = 0x0000    # Black
        self.accent_color = 0x07FF # Cyan
        self.warning_color = 0xFFE0 # Yellow
        
        self.font_type = 1
        
        print(f"Display: {self.display_width}x{self.display_height}")
        print(f"Text area: {self.chars_per_line} chars x {self.max_lines} lines")
        print(f"Margins: Left={self.left_margin}, Top={self.top_margin}")
        print(f"Line spacing: {self.line_spacing}px")
        
        # Initialize display
        self._initialize_display()
    
    def _initialize_display(self) -> None:
        """Initialize the display with welcome message"""
        self.display.set_font(self.font_type)
        self.display.clear(self.bg_color)
        
        # Show initialization messages
        self.display.text(self.left_margin, self.top_margin, "Translation Ready!", 1, self.text_color)
        self.display.text(self.left_margin, self.top_margin + 12, f"{self.chars_per_line}x{self.max_lines} text", 1, self.accent_color)
        self.display.text(self.left_margin, self.top_margin + 24, "WiFi Connected", 1, self.warning_color)
        
        time.sleep(2)
        self.clear_screen()
        
        print("WiFi Terminal Display Ready!")
        print(f"Max line length: {self.chars_per_line} characters")
    
    def add_text(self, text: str) -> bool:
        """Add text to the terminal display with auto-scrolling"""
        if not text:
            return True
        
        # Handle special commands
        if text.lower() == 'clear':
            self.clear_screen()
            return True
        
        if text.lower() == 'font':
            self.cycle_font()
            return True
        
        if text.lower() == 'info':
            self.show_info()
            return True
        
        # Process and wrap text
        wrapped_lines = self.wrap_text(text)
        
        for line in wrapped_lines:
            self.text_buffer.append(line)
            
            # Keep buffer manageable (3x screen height)
            max_buffer_lines = self.max_lines * 3
            if len(self.text_buffer) > max_buffer_lines:
                self.text_buffer.pop(0)
            
            # Handle scrolling
            if self.current_line >= self.max_lines:
                self.scroll_up()
            
            # Display the line
            y_pos = self.top_margin + (self.current_line * self.line_spacing)
            self.display.text(self.left_margin, y_pos, line, 1, self.text_color)
            self.current_line += 1
        
        return True
    
    def wrap_text(self, text: str) -> List[str]:
        """Wrap text to fit display width"""
        if len(text) <= self.chars_per_line:
            return [text]
        
        wrapped_lines = []
        words = text.split(' ')
        current_line = ""
        
        for word in words:
            # Handle very long words
            if len(word) > self.chars_per_line:
                if current_line:
                    wrapped_lines.append(current_line.strip())
                    current_line = ""
                
                # Split long word across lines
                while len(word) > self.chars_per_line:
                    wrapped_lines.append(word[:self.chars_per_line])
                    word = word[self.chars_per_line:]
                
                if word:
                    current_line = word + " "
            else:
                test_line = current_line + word + " "
                if len(test_line) > self.chars_per_line:
                    if current_line:
                        wrapped_lines.append(current_line.strip())
                    current_line = word + " "
                else:
                    current_line = test_line
        
        if current_line:
            wrapped_lines.append(current_line.strip())
        
        return wrapped_lines
    
    def scroll_up(self) -> None:
        """Scroll display up to show new content"""
        self.display.clear(self.bg_color)
        visible_lines = self.text_buffer[-self.max_lines:]
        
        for i, line in enumerate(visible_lines):
            y_pos = self.top_margin + (i * self.line_spacing)
            self.display.text(self.left_margin, y_pos, line, 1, self.text_color)
        
        self.current_line = len(visible_lines)
        
        # Show scroll indicator
        self.display.text(self.display_width - 18, self.top_margin, "^^^", 1, self.text_color)
    
    def cycle_font(self) -> None:
        """Cycle through available fonts"""
        font_names = ["Mono 9pt", "Default", "Bold Mono", "Sans-serif"]
        self.font_type = (self.font_type + 1) % 4
        self.display.set_font(self.font_type)
        
        # Show font change notification
        self.display.clear(self.bg_color)
        self.display.text(self.left_margin, self.display_height // 2 - 6, 
                         f"Font: {font_names[self.font_type]}", 1, self.accent_color)
        
        time.sleep(1)
        self.clear_screen()
        print(f"Font changed to: {font_names[self.font_type]}")
    
    def clear_screen(self) -> None:
        """Clear the terminal screen"""
        self.display.clear(self.bg_color)
        self.current_line = 0
        self.text_buffer = []
        print("Display cleared.")
    
    def show_info(self) -> None:
        """Display system information"""
        self.clear_screen()
        
        font_names = ["Mono 9pt", "Default", "Bold Mono", "Sans-serif"]
        
        info_lines = [
            "Translation Display",
            f"Size: {self.display_width}x{self.display_height}",
            f"Text: {self.chars_per_line}x{self.max_lines}",
            f"Margin: L{self.left_margin} T{self.top_margin}",
            f"Font: {font_names[self.font_type]}",
            "",
            "WiFi Connected",
            "Korean -> English",
            "Auto-scroll active",
        ]
        
        for i, line in enumerate(info_lines):
            if i < self.max_lines:
                y_pos = self.top_margin + (i * self.line_spacing)
                # Use accent color for headers
                color = self.accent_color if line.endswith(":") or i == 0 else self.text_color
                self.display.text(self.left_margin, y_pos, line, 1, color)
        
        print("Info displayed on ESP32")
        
        # Auto-clear info after 3 seconds
        time.sleep(3)
        self.clear_screen()
    
    def set_color_scheme(self, scheme: str = "default") -> None:
        """Change color scheme"""
        if scheme == "green":
            self.text_color = 0x07E0  # Green
            self.accent_color = 0x07FF  # Cyan
        elif scheme == "blue":
            self.text_color = 0x001F  # Blue
            self.accent_color = 0x07FF  # Cyan
        elif scheme == "white":
            self.text_color = 0xFFFF  # White
            self.accent_color = 0xFFE0  # Yellow
        elif scheme == "red":
            self.text_color = 0xF800  # Red
            self.accent_color = 0xFFE0  # Yellow
        else:  # default
            self.text_color = 0x07E0  # Green
            self.accent_color = 0x07FF  # Cyan
            
        print(f"Color scheme changed to: {scheme}")


# Legacy compatibility classes for existing code
class ESP32Display(WiFiESP32Display):
    """Legacy compatibility wrapper"""
    def __init__(self, port_or_host='0.0.0.0', **kwargs):
        if isinstance(port_or_host, str) and not port_or_host.startswith('/dev/'):
            # It's a host/IP address
            super().__init__(host=port_or_host, **kwargs)
        else:
            # Legacy serial port parameter, use default WiFi settings
            super().__init__(**kwargs)
        self.start_server()


class TerminalDisplay(WiFiTerminalDisplay):
    """Legacy compatibility wrapper"""
    pass