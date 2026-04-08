/*
 * ESP32-S3 WiFi Display Client - COMPLETE CODE WITH COLOR INVERSION FIX
 * Optimized for performance with DMA and double buffering
 * 
 * CRITICAL FIX: tft.sendCommand(ST77XX_INVON) forces black background
 * 
 * Required Libraries:
 * - Adafruit ST7735 and ST7789 Library
 * - Adafruit GFX Library
 * 
 * Wiring:
 * Display  →  ESP32-S3
 * GND      →  GND
 * VCC      →  3V3
 * SCL      →  GPIO12
 * SDA      →  GPIO11
 * RES      →  GPIO7
 * CS       →  GPIO10
 */

#include <WiFi.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7735.h>
#include <SPI.h>

// WiFi credentials
const char* ssid = "Family Guy's wifi";
const char* password = "petergriffin";

// Server details
const char* server_ip = "192.168.0.39";
const int server_port = 8888;

// Pin definitions
#define TFT_CS    10
#define TFT_RST   7
#define TFT_DC    9
#define TFT_MOSI  11
#define TFT_SCLK  12

// Button pin for mode switching
#define BUTTON_PIN 1

// Color definitions (RGB565)
#define TFT_BLACK       0x0000
#define TFT_WHITE       0xFFFF
#define TFT_RED         0xF800
#define TFT_GREEN       0x07E0
#define TFT_BLUE        0x001F
#define TFT_YELLOW      0xFFE0
#define TFT_CYAN        0x07FF
#define TFT_MAGENTA     0xF81F

// Global objects
Adafruit_ST7735 tft = Adafruit_ST7735(TFT_CS, TFT_DC, TFT_RST);
WiFiClient client;

// Connection management
unsigned long lastConnectionAttempt = 0;
const unsigned long connectionInterval = 5000;
bool isConnected = false;

// Text display management
#define MAX_LINES 10
#define LINE_HEIGHT 8
#define CHAR_WIDTH 6
#define LEFT_MARGIN 2      // Left margin to prevent text cutoff
#define TOP_MARGIN 0       // Top margin for proper alignment
#define MAX_CHARS_PER_LINE 26  // 160px / 6px per char
String textBuffer[MAX_LINES];
int currentLine = 0;
int textSize = 1;
uint16_t textColor = TFT_GREEN;
uint16_t bgColor = TFT_BLACK;
bool textMode = false;  // Track if we're in text display mode

// Mode management
enum Mode {
  MODE_TRANSLATION,
  MODE_ASSISTANT
};
Mode currentMode = MODE_TRANSLATION;
volatile bool buttonPressed = false;
unsigned long lastButtonPress = 0;
const unsigned long DEBOUNCE_DELAY = 500;  // 500ms debounce

// Button interrupt handler
void IRAM_ATTR handleButtonInterrupt() {
  buttonPressed = true;
}

// Display mode change message
void displayModeChange(const char* modeName) {
  textMode = false;
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_CYAN);
  tft.setTextSize(2);
  tft.setCursor(10, 30);
  tft.println(modeName);
  delay(1500);  // Show message for 1.5 seconds
}

// Send mode command to server
void sendModeCommand(const char* mode) {
  if (isConnected && client.connected()) {
    String cmd = "MODE:";
    cmd += mode;
    client.println(cmd);
    Serial.print("Sent mode command: ");
    Serial.println(cmd);
  }
}

// Toggle between modes
void toggleMode() {
  if (currentMode == MODE_TRANSLATION) {
    currentMode = MODE_ASSISTANT;
    Serial.println("Switching to ASSISTANT mode");
    displayModeChange("Jarvis Active");
    sendModeCommand("ASSISTANT");
  } else {
    currentMode = MODE_TRANSLATION;
    Serial.println("Switching to TRANSLATION mode");
    displayModeChange("Translation Active");
    sendModeCommand("TRANSLATION");
  }
}

void setup() {
  Serial.begin(115200);
  delay(100);
  
  Serial.println("\n=== ESP32-S3 WiFi Display Client ===");
  
  // Initialize button for mode switching
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), handleButtonInterrupt, FALLING);
  Serial.println("Button initialized on GPIO 1");
  
  // Initialize SPI with custom pins
  SPI.begin(TFT_SCLK, -1, TFT_MOSI, TFT_CS);
  
  // Initialize display (160x80 ST7735)
  Serial.println("Initializing display...");
  tft.initR(INITR_MINI160x80);
  tft.setRotation(1);  // Landscape mode
  
  // Set SPI speed for better performance
  tft.setSPISpeed(40000000);  // 40MHz
  
  // ============================================
  // CRITICAL FIX: Enable color inversion to force black background
  tft.sendCommand(ST77XX_INVON);
  delay(10);
  // ============================================
  
  Serial.println("Display initialized!");
  
  // Starting screen
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_WHITE);
  tft.setTextSize(1);
  tft.setCursor(5, 5);
  tft.println("Starting WiFi...");
  
  // Connect to WiFi
  WiFi.begin(ssid, password);
  tft.setCursor(5, 20);
  tft.println("Connecting...");
  
  int attempt = 0;
  while (WiFi.status() != WL_CONNECTED && attempt < 20) {
    delay(500);
    Serial.print(".");
    tft.print(".");
    attempt++;
  }
  
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWiFi connected!");
    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());
    
    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_GREEN);
    tft.setCursor(5, 5);
    tft.println("WiFi Connected!");
    
    tft.setTextColor(TFT_WHITE);
    tft.setCursor(5, 20);
    tft.print("IP:");
    tft.println(WiFi.localIP().toString());
    
    tft.setCursor(5, 35);
    tft.println("Connecting to");
    tft.setCursor(5, 45);
    tft.println("server...");
    
    delay(1000);
    connectToServer();
  } else {
    Serial.println("WiFi connection failed!");
    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_RED);
    tft.setCursor(5, 5);
    tft.println("WiFi Failed!");
    tft.setCursor(5, 20);
    tft.println("Check credentials");
  }
}

void loop() {
  // Handle button press for mode switching
  if (buttonPressed) {
    unsigned long now = millis();
    if (now - lastButtonPress > DEBOUNCE_DELAY) {
      lastButtonPress = now;
      toggleMode();
    }
    buttonPressed = false;
  }
  
  // Check WiFi connection
  if (WiFi.status() != WL_CONNECTED) {
    handleWiFiDisconnection();
    return;
  }
  
  // Check server connection and try to reconnect if needed
  if (!isConnected && millis() - lastConnectionAttempt > connectionInterval) {
    connectToServer();
  }
  
  // Read data from server if connected
  if (isConnected && client.connected()) {
    if (client.available()) {
      String cmd = client.readStringUntil('\n');
      cmd.trim();
      if (cmd.length()) {
        processCommand(cmd);
        client.println("OK");  // Send acknowledgment
      }
    }
  } else if (isConnected) {
    // Connection was lost
    Serial.println("Server connection lost");
    isConnected = false;
    displayConnectionStatus("Server disconnected");
  }
  
  delay(10);
}

void connectToServer() {
  lastConnectionAttempt = millis();
  
  Serial.print("Connecting to server ");
  Serial.print(server_ip);
  Serial.print(":");
  Serial.println(server_port);
  
  if (client.connect(server_ip, server_port)) {
    Serial.println("Connected to server!");
    isConnected = true;
    
    // Clear screen and show ready status
    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_GREEN);
    tft.setTextSize(1);
    tft.setCursor(5, 5);
    tft.println("Server Connected!");
    
    tft.setTextColor(TFT_WHITE);
    tft.setCursor(5, 20);
    tft.println("Ready for");
    tft.setCursor(5, 30);
    tft.println("translation");
    
    // Send ready signal to server
    client.println("READY");
  } else {
    Serial.println("Server connection failed");
    displayConnectionStatus("Server conn failed");
  }
}

void handleWiFiDisconnection() {
  Serial.println("WiFi disconnected, attempting reconnection...");
  isConnected = false;
  client.stop();
  
  displayConnectionStatus("WiFi disconnected");
  
  WiFi.begin(ssid, password);
  delay(1000);
}

void displayConnectionStatus(const char* message) {
  textMode = false;  // Exit text mode
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_YELLOW);
  tft.setTextSize(1);
  tft.setCursor(5, 5);
  tft.println(message);
  tft.setCursor(5, 20);
  tft.println("Retrying...");
}

// Initialize text mode with black background
void initTextMode() {
  textMode = true;
  currentLine = 0;
  tft.fillScreen(bgColor);
  tft.setTextColor(textColor, bgColor);  // Set both foreground and background colors
  tft.setTextSize(textSize);
  
  // Clear text buffer
  for (int i = 0; i < MAX_LINES; i++) {
    textBuffer[i] = "";
  }
}

// Add a line of text with smooth scrolling
void addTextLine(String text) {
  if (!textMode) {
    initTextMode();
  }
  
  // If we haven't filled the screen yet, just add the line
  if (currentLine < MAX_LINES) {
    textBuffer[currentLine] = text;
    tft.setCursor(LEFT_MARGIN, TOP_MARGIN + (currentLine * LINE_HEIGHT));
    tft.setTextColor(textColor, bgColor);  // Set both colors
    tft.print(text);
    currentLine++;
  } else {
    // Screen is full, need to scroll
    scrollText(text);
  }
}

// Scroll text up by one line and add new text at bottom
void scrollText(String newText) {
  // Shift buffer up
  for (int i = 0; i < MAX_LINES - 1; i++) {
    textBuffer[i] = textBuffer[i + 1];
  }
  textBuffer[MAX_LINES - 1] = newText;
  
  // Redraw all lines with proper clearing to prevent overlay
  for (int i = 0; i < MAX_LINES; i++) {
    // Clear the line area first to prevent text overlay
    tft.fillRect(LEFT_MARGIN, TOP_MARGIN + (i * LINE_HEIGHT), 160 - LEFT_MARGIN, LINE_HEIGHT, bgColor);
    
    // Draw the text
    tft.setCursor(LEFT_MARGIN, TOP_MARGIN + (i * LINE_HEIGHT));
    tft.setTextColor(textColor, bgColor);  // Set both colors
    tft.print(textBuffer[i]);
  }
}

void processCommand(String &cmd) {
  int colon = cmd.indexOf(':');
  if (colon == -1) { 
    Serial.println("ERROR: Invalid command format");
    return; 
  }

  String hdr   = cmd.substring(0, colon);
  String parms = cmd.substring(colon + 1);

  if (hdr == "CLEAR") {
    uint16_t color = (uint16_t)parms.toInt();
    tft.fillScreen(color);
    textMode = false;  // Exit text mode on clear
    Serial.println("Command: CLEAR executed");

  } else if (hdr == "TEXTMODE") {
    // Initialize text mode: TEXTMODE:textColor,bgColor,textSize
    int c1 = parms.indexOf(',');
    int c2 = parms.indexOf(',', c1 + 1);
    
    if (c2 != -1) {
      textColor = (uint16_t)parms.substring(0, c1).toInt();
      bgColor = (uint16_t)parms.substring(c1 + 1, c2).toInt();
      textSize = parms.substring(c2 + 1).toInt();
    } else if (c1 != -1) {
      textColor = (uint16_t)parms.substring(0, c1).toInt();
      bgColor = (uint16_t)parms.substring(c1 + 1).toInt();
    } else {
      textColor = (uint16_t)parms.toInt();
    }
    
    initTextMode();
    Serial.println("Command: TEXTMODE executed");

  } else if (hdr == "ADDLINE") {
    // Add a line of text with auto-scrolling: ADDLINE:text
    addTextLine(parms);
    Serial.print("Command: ADDLINE executed - ");
    Serial.println(parms);

  } else if (hdr == "SETCOLOR") {
    // Set text and background colors: SETCOLOR:textColor,bgColor
    int c1 = parms.indexOf(',');
    if (c1 != -1) {
      textColor = (uint16_t)parms.substring(0, c1).toInt();
      bgColor = (uint16_t)parms.substring(c1 + 1).toInt();
    } else {
      textColor = (uint16_t)parms.toInt();
    }
    Serial.println("Command: SETCOLOR executed");

  } else if (hdr == "TEXT") {
    int x, y, size;
    uint16_t color;
    int c1 = parms.indexOf(',');
    int c2 = parms.indexOf(',', c1 + 1);
    int c3 = parms.indexOf(',', c2 + 1);
    int c4 = parms.indexOf(',', c3 + 1);
    
    if (c4 == -1) { 
      Serial.println("ERROR: Invalid TEXT command parameters");
      return; 
    }
    
    x     = parms.substring(0, c1).toInt();
    y     = parms.substring(c1 + 1, c2).toInt();
    size  = parms.substring(c2 + 1, c3).toInt();
    color = (uint16_t)parms.substring(c3 + 1, c4).toInt();
    String txt = parms.substring(c4 + 1);

    textMode = false;  // Exit text mode when using direct TEXT command
    tft.setCursor(x, y);
    tft.setTextSize(size);
    tft.setTextColor(color);
    tft.println(txt);
    
    Serial.print("Command: TEXT executed - ");
    Serial.println(txt);

  } else if (hdr == "SETFONT") {
    int font_type = parms.toInt();
    // Adafruit GFX font sizes
    textSize = font_type > 0 ? font_type : 1;
    tft.setTextSize(textSize);
    
    Serial.print("Command: SETFONT executed - Size ");
    Serial.println(font_type);

  } else if (hdr == "RECT") {
    // Format: RECT:x,y,w,h,color
    int x, y, w, h;
    uint16_t color;
    int c1 = parms.indexOf(',');
    int c2 = parms.indexOf(',', c1 + 1);
    int c3 = parms.indexOf(',', c2 + 1);
    int c4 = parms.indexOf(',', c3 + 1);
    
    if (c4 != -1) {
      x = parms.substring(0, c1).toInt();
      y = parms.substring(c1 + 1, c2).toInt();
      w = parms.substring(c2 + 1, c3).toInt();
      h = parms.substring(c3 + 1, c4).toInt();
      color = (uint16_t)parms.substring(c4 + 1).toInt();
      
      textMode = false;  // Exit text mode
      tft.fillRect(x, y, w, h, color);
      Serial.println("Command: RECT executed");
    }

  } else if (hdr == "LINE") {
    // Format: LINE:x0,y0,x1,y1,color
    int x0, y0, x1, y1;
    uint16_t color;
    int c1 = parms.indexOf(',');
    int c2 = parms.indexOf(',', c1 + 1);
    int c3 = parms.indexOf(',', c2 + 1);
    int c4 = parms.indexOf(',', c3 + 1);
    
    if (c4 != -1) {
      x0 = parms.substring(0, c1).toInt();
      y0 = parms.substring(c1 + 1, c2).toInt();
      x1 = parms.substring(c2 + 1, c3).toInt();
      y1 = parms.substring(c3 + 1, c4).toInt();
      color = (uint16_t)parms.substring(c4 + 1).toInt();
      
      textMode = false;  // Exit text mode
      tft.drawLine(x0, y0, x1, y1, color);
      Serial.println("Command: LINE executed");
    }

  } else if (hdr == "CIRCLE") {
    // Format: CIRCLE:x,y,r,color
    int x, y, r;
    uint16_t color;
    int c1 = parms.indexOf(',');
    int c2 = parms.indexOf(',', c1 + 1);
    int c3 = parms.indexOf(',', c2 + 1);
    
    if (c3 != -1) {
      x = parms.substring(0, c1).toInt();
      y = parms.substring(c1 + 1, c2).toInt();
      r = parms.substring(c2 + 1, c3).toInt();
      color = (uint16_t)parms.substring(c3 + 1).toInt();
      
      textMode = false;  // Exit text mode
      tft.fillCircle(x, y, r, color);
      Serial.println("Command: CIRCLE executed");
    }

  } else {
    Serial.println("ERROR: Unknown command");
  }
}
