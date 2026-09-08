/*
* ===============================================================
*                    GEOLINKER GPS TRACKER
*              + FLEETSERVER DEVICE REGISTRATION
* ===============================================================
*
* FEATURES:
* - Real-time GPS tracking with NMEA data parsing
* - WiFi connectivity for data transmission
* - Offline data storage with configurable buffer limits
* - Automatic reconnection capabilities
* - Timezone offset configuration
* - Multiple debug levels for troubleshooting
* - Status monitoring with detailed error codes
* - Configurable update intervals
* - Registers itself as a device in the FleetServer Android app
*
* WORKING:
* 1. Initializes GPS module on Serial1 with custom RX/TX pins
* 2. Connects to WiFi network using provided credentials
* 3. Continuously reads GPS data in main loop
* 4. Processes and validates GPS coordinates
* 5. Transmits location data to GeoLinker cloud service
* 6. Registers the same fix with the FleetServer app on the phone
* 7. Stores data offline when network is unavailable
* 8. Provides real-time status feedback via serial monitor
*
* HARDWARE REQUIREMENTS:
* - ESP32 development board
* - GPS module (NMEA compatible)
* - WiFi network access
*
* PIN CONNECTIONS:
* - GPS RX: GPIO25
* - GPS TX: GPIO26
*
* CREDITS:
* - GeoLinker Library: GPS tracking and cloud connectivity
* - ESP32 Arduino Core: Hardware abstraction layer
* - NMEA GPS Protocol: Location data standardization
*
* Author: CircuitDigest/Rithik_Krisna_M
* Version: 1.0
* Last Modified: 28/06/2025
*
* ===============================================================
*
* ADDED FOR FLEETSERVER (everything above is the original sketch):
*
* - The GPS is handed to GeoLinker through a GpsTap, a Stream that forwards every
*   byte to the library and mirrors it into our own parser, so both see the whole
*   feed. GeoLinker's parsed coordinates are private, so this is the only way to
*   get the fix without touching the library.
* - Every update interval the fix is POSTed to the phone as well:
*     POST http://<phone-ip>:8080/telemetry
* - The four states the app expects:
*     1 GPS device not found  - the module is silent (no NMEA for 10 s)
*     2 No GPS Fix            - the module is talking but has no position
*     3 Offline Mode          - the GeoLinker cloud upload did not go through
*     4 Operational           - the cloud upload succeeded and the app got the fix
*
* Version: 2.0 (FleetServer)
* ===============================================================
*/
#include <GeoLinker.h>
#include <WiFi.h>
#include <HTTPClient.h>
// ==================================================================
//                    HARDWARE CONFIGURATION
// ==================================================================
// GPS Serial Communication Setup
HardwareSerial gpsSerial(1);  // Using Serial1 for GPS communication
#define GPS_RX 25             // GPIO25 connected to GPS module TX pin
#define GPS_TX 26             // GPIO26 connected to GPS module RX pin
// GPS Communication Settings
#define GPS_BAUD 9600         // Standard NMEA GPS baud rate (9600 bps)
// ==================================================================
//                    NETWORK CONFIGURATION
// ==================================================================
// WiFi Network Credentials
const char* ssid = "YOUR_WIFI_SSID";       // Your WiFi network name (SSID)
const char* password = "YOUR_WIFI_PASSWORD";   // Your WiFi network password
// ==================================================================
//                   FLEETSERVER CONFIGURATION
// ==================================================================
// The phone running the app. Use the address shown under Connection info in the
// app, and reserve it in the router's DHCP settings so it does not move.
const char* fleetHost = "192.168.1.198";
const uint16_t fleetPort = 8080;
const unsigned long FLEET_REPORT_DELAY_MS = 2000;  // report just after GeoLinker's own cycle
// ==================================================================
//                   GEOLINKER CONFIGURATION
// ==================================================================
// API Authentication
const char* apiKey = "YOUR_GEOLINKER_API_KEY";    // Your unique GeoLinker API key
const char* deviceID = "ESP-32_Tracker"; // Unique identifier for this device
// Data Transmission Settings
const uint16_t updateInterval = 15;       // How often to send data (seconds)
const bool enableOfflineStorage = true; // Store data when offline
const uint8_t offlineBufferLimit = 20;  // Maximum offline records to store
                                       // Keep minimal for MCUs with limited RAM
// Connection Management
const bool enableAutoReconnect = true;  // Automatically reconnect to WiFi
                                       // Note: Only applies to WiFi, ignored with GSM
// Timezone Configuration
const int8_t timeOffsetHours = 1;       // Timezone hours offset from UTC
const int8_t timeOffsetMinutes = 0;    // Timezone minutes offset from UTC
                                      // Example: IST = UTC+5:30
// Create GeoLinker instance
GeoLinker geo;
// ==================================================================
//                    FLEETSERVER STATES + TAPPED FIX
// ==================================================================
enum TrackerState {
  STATE_GPS_NOT_FOUND = 1,
  STATE_NO_FIX = 2,
  STATE_OFFLINE = 3,
  STATE_OPERATIONAL = 4
};

struct Fix {
  bool valid = false;
  double lat = 0.0;
  double lon = 0.0;
  float speedKmh = 0.0f;
  float heading = 0.0f;
  float altitude = 0.0f;
  float hdop = 0.0f;
  int satellites = 0;
};

static Fix currentFix;
static bool gpsDeviceSeen = false;
static unsigned long lastGpsByteAt = 0;
static unsigned long lastFleetReportMs = 0;
static bool cloudUploadFailed = false;   // last GeoLinker cloud attempt did not go through

static const unsigned long GPS_SILENCE_MS = 10000;
static const unsigned long HTTP_TIMEOUT_MS = 4000;

static const char* stateName(uint8_t state);
void parseNmea(const char* line);
bool nmeaField(const char* line, int index, char* out, size_t outSize);
static bool copyField(const char* start, const char* end, char* out, size_t outSize);
double nmeaToDegrees(const char* value, const char* hemisphere);

/**
 * Hands every byte to GeoLinker and keeps a copy for our own parser.
 * GeoLinker calls available()/read() on whatever Stream it is given, so it never
 * knows the difference.
 */
class GpsTap : public Stream {
 public:
  explicit GpsTap(Stream& source) : source_(source) {}

  int available() override { return source_.available(); }
  int peek() override { return source_.peek(); }
  size_t write(uint8_t b) override { return source_.write(b); }

  int read() override {
    int c = source_.read();
    if (c >= 0) {
      feed((char) c);
    }
    return c;
  }

  void feed(char c);

 private:
  Stream& source_;
  char line_[160] = {0};
  size_t length_ = 0;
};

static GpsTap gpsTap(gpsSerial);

void GpsTap::feed(char c) {
  if (c == '\n') {
    line_[length_] = '\0';
    length_ = 0;
    if (line_[0] == '$') {
      parseNmea(line_);
    }
    return;
  }
  if (c != '\r' && length_ < sizeof(line_) - 1) {
    line_[length_++] = c;
  }
}
// ==================================================================
//                    INITIALIZATION SETUP
// ==================================================================
void setup() {
 // Initialize serial communication for debugging
 Serial.begin(115200);
 delay(1000);  // Allow serial to initialize
 Serial.println("Starting GeoLinker GPS Tracker...");
 // Initialize GPS serial communication with custom pins
 gpsSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX, GPS_TX);
 Serial.printf("GPS Serial initialized on pins %d(RX) and %d(TX)\n", GPS_RX, GPS_TX);
 // ==================================================================
 //                   GEOLINKER LIBRARY SETUP
 // ==================================================================
 // Initialize GeoLinker with the tapped GPS stream
 geo.begin(gpsTap);
 Serial.println("GeoLinker library initialized");
 // Configure API authentication
 geo.setApiKey(apiKey);
 Serial.println("API key configured");
 // Set unique device identifier
 geo.setDeviceID(deviceID);
 Serial.println("Device ID set");
 // Configure data transmission interval
 geo.setUpdateInterval_seconds(updateInterval);
 Serial.print("Update interval set to ");
 Serial.print(updateInterval);
 Serial.println(" seconds");
 // Set debug verbosity level
 // Options: DEBUG_NONE, DEBUG_BASIC, DEBUG_VERBOSE
 geo.setDebugLevel(DEBUG_BASIC);
 Serial.println("Debug level set to BASIC");
 // Enable offline data storage capability
 geo.enableOfflineStorage(enableOfflineStorage);
 if(enableOfflineStorage) {
   Serial.println("Offline storage enabled");
 }
 // Enable automatic WiFi reconnection
 geo.enableAutoReconnect(enableAutoReconnect);
 if(enableAutoReconnect) {
   Serial.println("Auto-reconnect enabled");
 }
 // Set maximum offline buffer size (important for memory management)
 geo.setOfflineBufferLimit(offlineBufferLimit);
 Serial.print("Offline buffer limit set to ");
 Serial.print(offlineBufferLimit);
 Serial.println(" records");
 // Configure timezone offset for accurate timestamps
 geo.setTimeOffset(timeOffsetHours, timeOffsetMinutes);
 Serial.print("Timezone offset set to UTC+");
 Serial.print(timeOffsetHours);
 Serial.print(":");
 Serial.println(timeOffsetMinutes);
 // ==================================================================
 //                    NETWORK CONNECTION SETUP
 // ==================================================================
 // Configure for WiFi mode (alternative: GEOLINKER_GSM for cellular)
 geo.setNetworkMode(GEOLINKER_WIFI);
 Serial.println("Network mode set to WiFi");
 // Set WiFi network credentials
 geo.setWiFiCredentials(ssid, password);
 Serial.println("WiFi credentials configured");
 // Attempt WiFi connection
 Serial.print("Connecting to WiFi network: ");
 Serial.println(ssid);
 if (!geo.connectToWiFi()) {
   Serial.println("ERROR: WiFi connection failed!");
   Serial.println("Device will continue with offline storage mode");
 } else {
   Serial.println("WiFi connected successfully!");
 }
 Serial.printf("FleetServer target: http://%s:%u/telemetry\n", fleetHost, fleetPort);
 // First report lands just after GeoLinker's first upload, so the state it sends
 // already reflects whether that upload worked.
 lastFleetReportMs = millis() + FLEET_REPORT_DELAY_MS;
 Serial.println("\n" + String("=").substring(0,50));
 Serial.println("GeoLinker GPS Tracker setup complete!");
 Serial.println("Starting main tracking loop...");
 Serial.println(String("=").substring(0,50) + "\n");
}
// ==================================================================
//                   MAIN PROGRAM LOOP
// ==================================================================
void loop() {
 // ==========================================
 //         GEOLINKER MAIN OPERATION
 // ==========================================
 // Execute main GeoLinker processing cycle
 // This function handles:
 // - GPS data reading and parsing
 // - Network connectivity checking
 // - Data transmission to cloud service
 // - Offline storage management
 // - Error handling and recovery
 uint8_t status = geo.loop();
 // Process and display status information
 if (status > 0) {
   Serial.print("[STATUS] GeoLinker Operation: ");
   // Interpret status codes and provide user feedback
   switch(status) {
     case STATUS_SENT:
       Serial.println("✓ Data transmitted successfully to cloud!");
       break;
     case STATUS_GPS_ERROR:
       Serial.println("✗ GPS module connection error - Check wiring!");
       break;
     case STATUS_NETWORK_ERROR:
       Serial.println("⚠ Network connectivity issue - Data buffered offline");
       break;
     case STATUS_BAD_REQUEST_ERROR:
       Serial.println("✗ Server rejected request - Check API key and data format");
       break;
     case STATUS_PARSE_ERROR:
       Serial.println("✗ GPS data parsing error - Invalid NMEA format");
       break;
     case STATUS_INTERNAL_SERVER_ERROR:
       Serial.println("✗ GeoLinker server internal error - Try again later");
       break;
     default:
       Serial.print("? Unknown status code: ");
       Serial.println(status);
       break;
   }
 }
 // ==========================================
 //      FLEETSERVER DEVICE REGISTRATION
 // ==========================================
 reportToFleetServer(status);
 // Small delay to prevent overwhelming the serial output
 // The actual timing is controlled by GeoLinker's internal mechanisms
 delay(100);
}

/**
 * Registers this board with the FleetServer app once per update interval.
 *
 * The state follows the cloud upload: STATUS_SENT means the data got out, so the
 * device is Operational (4); a network/server rejection means Offline (3). A GPS
 * error is not counted as a failed upload - the library never attempted one - so
 * the device stays Operational as long as the app receives it.
 */
void reportToFleetServer(uint8_t cloudStatus) {
  if (cloudStatus == STATUS_SENT) {
    cloudUploadFailed = false;
  } else if (cloudStatus == STATUS_NETWORK_ERROR
             || cloudStatus == STATUS_BAD_REQUEST_ERROR
             || cloudStatus == STATUS_INTERNAL_SERVER_ERROR) {
    cloudUploadFailed = true;
  }

  if (millis() - lastFleetReportMs < (unsigned long) updateInterval * 1000UL) {
    return;
  }
  lastFleetReportMs = millis();

  uint8_t state = currentState();
  bool registered = postToFleetServer(state);
  Serial.printf("[FLEETSERVER] state %u (%s) -> %s\n", state, stateName(state),
                registered ? "registered" : "FAILED (app not reachable)");
}

/** The four states, in priority order. */
uint8_t currentState() {
  if (!gpsDeviceSeen || millis() - lastGpsByteAt > GPS_SILENCE_MS) {
    return STATE_GPS_NOT_FOUND;
  }
  if (!currentFix.valid) {
    return STATE_NO_FIX;
  }
  if (cloudUploadFailed) {
    return STATE_OFFLINE;
  }
  return STATE_OPERATIONAL;
}

static const char* stateName(uint8_t state) {
  switch (state) {
    case STATE_GPS_NOT_FOUND: return "GPS device not found";
    case STATE_NO_FIX:        return "No GPS Fix";
    case STATE_OFFLINE:       return "Offline Mode";
    default:                  return "Operational";
  }
}

/**
 * {"device":"ESP-32_Tracker","lat":6.524400,"lon":3.379200,"state":4,"sats":9,
 *  "speed_kmh":12.3,"heading":42.0,"alt":218.0,"hdop":0.90,"uptime":61234}
 *
 * Only device, lat, lon and state matter to the app; the rest is shown in the
 * device detail sheet. With no fix the coordinates are left out entirely, so the
 * device still appears in the app's list, just without a pin.
 */
String buildFleetPayload(uint8_t state) {
  char json[320];
  if (currentFix.valid) {
    snprintf(json, sizeof(json),
             "{\"device\":\"%s\",\"lat\":%.6f,\"lon\":%.6f,\"state\":%u,\"sats\":%d,"
             "\"speed_kmh\":%.1f,\"heading\":%.1f,\"alt\":%.1f,\"hdop\":%.2f,\"uptime\":%lu}",
             deviceID, currentFix.lat, currentFix.lon, state, currentFix.satellites,
             currentFix.speedKmh, currentFix.heading, currentFix.altitude, currentFix.hdop,
             (unsigned long) millis());
  } else {
    snprintf(json, sizeof(json),
             "{\"device\":\"%s\",\"state\":%u,\"sats\":%d,\"uptime\":%lu}",
             deviceID, state, currentFix.satellites, (unsigned long) millis());
  }
  return String(json);
}

bool postToFleetServer(uint8_t state) {
  if (WiFi.status() != WL_CONNECTED) {
    return false;
  }
  HTTPClient http;
  char url[96];
  snprintf(url, sizeof(url), "http://%s:%u/telemetry", fleetHost, fleetPort);
  if (!http.begin(url)) {
    return false;
  }
  http.addHeader("Content-Type", "application/json");
  http.setTimeout(HTTP_TIMEOUT_MS);
  int code = http.POST(buildFleetPayload(state));
  http.end();
  return code > 0 && code < 400;
}
// ==================================================================
//              NMEA PARSING (the bytes mirrored off the tap)
// ==================================================================

/**
 * $GxRMC,time,A,llll.ll,N,yyyyy.yy,E,speed,course,date,...  position, speed, course
 * $GxGGA,time,llll.ll,N,yyyyy.yy,E,quality,sats,hdop,alt,M,...  quality, altitude
 *
 * Fields are counted from 1, so field 1 is the sentence name. GeoLinker only looks
 * at $GPRMC; this accepts any talker ($GNRMC from multi-constellation modules too).
 */
void parseNmea(const char* line) {
  gpsDeviceSeen = true;
  lastGpsByteAt = millis();

  char value[24];
  if (strstr(line, "RMC") != nullptr) {
    char status[4] = "", lat[24] = "", ns[4] = "", lon[24] = "", ew[4] = "";
    nmeaField(line, 3, status, sizeof(status));   // A = valid, V = void
    nmeaField(line, 4, lat, sizeof(lat));
    nmeaField(line, 5, ns, sizeof(ns));
    nmeaField(line, 6, lon, sizeof(lon));
    nmeaField(line, 7, ew, sizeof(ew));

    double latitude = nmeaToDegrees(lat, ns);
    double longitude = nmeaToDegrees(lon, ew);

    if (status[0] == 'A' && !isnan(latitude) && !isnan(longitude)) {
      currentFix.valid = true;
      currentFix.lat = latitude;
      currentFix.lon = longitude;
      nmeaField(line, 8, value, sizeof(value));
      currentFix.speedKmh = atof(value) * 1.852f;   // knots -> km/h
      nmeaField(line, 9, value, sizeof(value));
      currentFix.heading = atof(value);             // degrees from north
    } else {
      currentFix.valid = false;   // module is alive but has not locked on yet
    }
  } else if (strstr(line, "GGA") != nullptr) {
    nmeaField(line, 7, value, sizeof(value));
    int quality = atoi(value);
    nmeaField(line, 8, value, sizeof(value));
    currentFix.satellites = atoi(value);
    nmeaField(line, 9, value, sizeof(value));
    currentFix.hdop = atof(value);
    nmeaField(line, 10, value, sizeof(value));
    currentFix.altitude = atof(value);
    if (quality == 0) {
      currentFix.valid = false;   // 0 = no fix, 1 = GPS, 2 = DGPS, ...
    }
  }
}

/** Copies the nth comma separated field into a caller-owned buffer. */
bool nmeaField(const char* line, int index, char* out, size_t outSize) {
  int current = 1;
  const char* start = line;
  for (const char* p = line; *p != '\0'; p++) {
    if (*p == ',') {
      if (current == index) {
        return copyField(start, p, out, outSize);
      }
      current++;
      start = p + 1;
    }
  }
  if (current == index) {
    return copyField(start, line + strlen(line), out, outSize);
  }
  out[0] = '\0';
  return false;
}

static bool copyField(const char* start, const char* end, char* out, size_t outSize) {
  size_t length = (size_t) (end - start);
  if (length > outSize - 1) {
    length = outSize - 1;
  }
  memcpy(out, start, length);
  out[length] = '\0';
  return length > 0;
}

/** "0627.1234",N -> 6.452057; southern and western hemispheres come back negative. */
double nmeaToDegrees(const char* value, const char* hemisphere) {
  if (value == nullptr || value[0] == '\0') {
    return NAN;
  }
  double raw = atof(value);
  double degrees = floor(raw / 100.0);
  double minutes = raw - degrees * 100.0;
  double result = degrees + minutes / 60.0;
  if (hemisphere != nullptr && (hemisphere[0] == 'S' || hemisphere[0] == 'W')) {
    result = -result;
  }
  return result;
}
