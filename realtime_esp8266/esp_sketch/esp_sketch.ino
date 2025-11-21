#include <ESP8266WiFi.h>
#include <TFT_eSPI.h>

// WiFi credentials - modify these for your network
const char* ssid = "Family Guy's wifi";
const char* password = "petergriffin";

// Server details - modify IP to match your PC's IP address
const char* server_ip = "192.168.0.18";  // Replace with your PC's IP
const int server_port = 8888;

TFT_eSPI tft = TFT_eSPI();
WiFiClient client;

// Connection management
unsigned long lastConnectionAttempt = 0;
const unsigned long connectionInterval = 5000; // Try to reconnect every 5 seconds
bool isConnected = false;

void setup() {
  Serial.begin(115200);
  delay(100);
  
  // Initialize display
  tft.init();
  tft.setRotation(1);
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextSize(2);
  tft.setCursor(10, 10);
  tft.println("Starting WiFi...");
  
  // Connect to WiFi
  WiFi.begin(ssid, password);
  tft.setCursor(10, 40);
  tft.println("Connecting...");
  
  int attempt = 0;
  while (WiFi.status() != WL_CONNECTED && attempt < 20) {
    delay(500);
    Serial.print(".");
    attempt++;
  }
  
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println();
    Serial.println("WiFi connected!");
    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());
    
    tft.fillScreen(TFT_BLACK);
    tft.setCursor(10, 10);
    tft.println("WiFi Connected!");
    tft.setCursor(10, 40);
    tft.print("IP: ");
    tft.println(WiFi.localIP());
    tft.setCursor(10, 70);
    tft.println("Connecting to server...");
    
    connectToServer();
  } else {
    Serial.println("WiFi connection failed!");
    tft.fillScreen(TFT_BLACK);
    tft.setCursor(10, 10);
    tft.setTextColor(TFT_RED, TFT_BLACK);
    tft.println("WiFi Failed!");
    tft.println("Check credentials");
  }
}

void loop() {
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
        client.println("OK"); // Send acknowledgment back to server
      }
    }
  } else if (isConnected) {
    // Connection was lost
    Serial.println("Server connection lost");
    isConnected = false;
    displayConnectionStatus("Server disconnected");
  }
  
  delay(10); // Small delay to prevent excessive CPU usage
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
    tft.setTextColor(TFT_GREEN, TFT_BLACK);
    tft.setTextSize(2);
    tft.setCursor(10, 10);
    tft.println("Server Connected!");
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setCursor(10, 40);
    tft.println("Ready for translation");
    
    // Send ready signal to server
    client.println("READY");
  } else {
    Serial.println("Server connection failed");
    displayConnectionStatus("Server connection failed");
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
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_YELLOW, TFT_BLACK);
  tft.setTextSize(2);
  tft.setCursor(10, 10);
  tft.println(message);
  tft.setCursor(10, 40);
  tft.println("Retrying...");
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
    tft.fillScreen((uint16_t)parms.toInt());
    Serial.println("Command: CLEAR executed");

  } else if (hdr == "TEXT") {
    int x, y, size, color;
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
    color = parms.substring(c3 + 1, c4).toInt();
    String txt = parms.substring(c4 + 1);

    tft.setCursor(x, y);
    tft.setTextSize(size);
    tft.setTextColor(color, TFT_BLACK);
    tft.println(txt);
    
    Serial.print("Command: TEXT executed - ");
    Serial.println(txt);

  } else if (hdr == "SETFONT") {
    int font_type = parms.toInt();
    // TFT_eSPI supports different fonts
    // Font 0: Default, Font 1: Small, Font 2: Large, etc.
    // You can extend this based on your TFT_eSPI library capabilities
    switch(font_type) {
      case 0:
        tft.setTextFont(0); // Built-in font
        break;
      case 1:
        tft.setTextFont(1); // Default font
        break;
      case 2:
        tft.setTextFont(2); // Font 2 (if available)
        break;
      case 3:
        tft.setTextFont(4); // Font 4 (if available)
        break;
      default:
        tft.setTextFont(1); // Default
        break;
    }
    Serial.print("Command: SETFONT executed - Font type ");
    Serial.println(font_type);

  } else {
    Serial.println("ERROR: Unknown command");
  }
}