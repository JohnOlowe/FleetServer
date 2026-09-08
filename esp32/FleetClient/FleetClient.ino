/*
 * FleetClient - ESP32 GPS tracker for the FleetServer Android app.
 *
 * The board joins the same Wi-Fi network as the phone (or the phone's own hotspot),
 * reads a GPS module over UART, and posts latitude / longitude / state to the app:
 *
 *   POST http://<phone-ip>:8080/telemetry
 *   {"device":"esp32-01","lat":7.377500,"lon":3.947000,"state":4,"sats":9,"speed":12.3,
 *    "heading":42.0,"alt":218.0,"hdop":0.90,"battery":87}
 *
 * States (see docs/API.md):
 *   1 = GPS device not found   - the GPS module is silent (not wired / wrong baud / no power)
 *   2 = No GPS fix             - module is talking, but it has no position yet
 *   3 = Offline mode           - reporting a fix that was buffered while Wi-Fi was down
 *   4 = Operational            - live fix, sent straight to the app
 *
 * No third-party Arduino libraries are needed: the NMEA sentences are parsed here and the
 * JSON is written by hand.
 *
 * Quick start
 *   1. Set WIFI_SSID / WIFI_PASS to the phone's hotspot (or your router).
 *   2. Set SERVER_IP to the address the app shows under "Connection info".
 *   3. Leave SIMULATE_GPS at 1 to see it working without a GPS module, then set it to 0
 *      and wire the module to GPS_RX_PIN / GPS_TX_PIN.
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include <Preferences.h>

// ------------------------------------------------------------------ configuration

static const char *WIFI_SSID = "FleetServer";     // phone hotspot or router
static const char *WIFI_PASS = "fleet1234";
static const char *SERVER_IP = "192.168.43.1";    // phone IP - see app > Connection info
static const uint16_t SERVER_PORT = 8080;
static const char *DEVICE_ID = "esp32-01";

static const unsigned long SEND_INTERVAL_MS = 5000;   // how often to report
static const unsigned long GPS_TIMEOUT_MS = 10000;    // silence = "GPS device not found"

#define SIMULATE_GPS 1        // 1 = fake walking fix (demo), 0 = real NMEA from the module
#define GPS_RX_PIN 16
#define GPS_TX_PIN 17
#define GPS_BAUD 9600

#define MAX_BUFFERED_FIXES 32  // fixes kept while Wi-Fi is down

// ------------------------------------------------------------------ model

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
  float speed = 0.0f;     // m/s
  float heading = 0.0f;   // degrees
  float altitude = 0.0f;  // metres
  float hdop = 0.0f;
  int satellites = 0;
  unsigned long millisAtFix = 0;
};

static Preferences prefs;
static HardwareSerial GPS(2);

static Fix gpsFix;
static unsigned long lastGpsByteAt = 0;
static bool gpsDeviceSeen = false;
static unsigned long lastSendAt = 0;

static Fix buffered[MAX_BUFFERED_FIXES];
static int bufferedCount = 0;

// ------------------------------------------------------------------ setup

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\nFleetClient starting");

  prefs.begin("fleet", false);
  WiFi.setAutoReconnect(true);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

#if SIMULATE_GPS
  Serial.println("SIMULATE_GPS = 1, no hardware GPS needed");
#else
  GPS.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  Serial.println("Listening for NMEA on UART2");
#endif

  // Start from the last known position so "offline mode" has something to report.
  gpsFix.lat = prefs.getDouble("lat", 0.0);
  gpsFix.lon = prefs.getDouble("lon", 0.0);
  gpsFix.valid = (gpsFix.lat != 0.0 || gpsFix.lon != 0.0);
}

// ------------------------------------------------------------------ main loop

void loop() {
#if SIMULATE_GPS
  updateSimulatedFix();
#else
  readGpsSerial();
#endif

  if (millis() - lastSendAt >= SEND_INTERVAL_MS) {
    lastSendAt = millis();
    report();
  }
  delay(20);
}

/** Builds the payload for one fix and sends it, buffering it when Wi-Fi is down. */
void report() {
  bool online = ensureWifi();
  bool flushed = false;

  if (online && bufferedCount > 0) {
    // Send everything that was collected while we were offline (state 3).
    for (int i = 0; i < bufferedCount; i++) {
      String payload = buildPayload(buffered[i].valid ? buffered[i] : gpsFix, STATE_OFFLINE);
      if (!postTelemetry(payload.c_str())) {
        break;  // still offline, keep the rest for later
      }
      flushed = true;
      delay(20);
    }
    if (flushed) {
      bufferedCount = 0;
    }
  }

  TrackerState state = currentState();
  String payload = buildPayload(gpsFix, state);

  if (online) {
    bool ok = postTelemetry(payload.c_str());
    Serial.printf("[%s] %s -> %s\n", stateName(state), payload.c_str(), ok ? "sent" : "FAILED");
    if (!ok) {
      bufferCurrentFix(state);
    }
  } else {
    Serial.printf("[%s] offline, buffering (%d)\n", stateName(currentState()), bufferedCount + 1);
    bufferCurrentFix(state);
  }
}

TrackerState currentState() {
  if (!gpsDeviceSeen) {
    return STATE_GPS_NOT_FOUND;
  }
  if (!gpsFix.valid) {
    return STATE_NO_FIX;
  }
  if (WiFi.status() != WL_CONNECTED) {
    return STATE_OFFLINE;
  }
  return STATE_OPERATIONAL;
}

static const char *stateName(TrackerState state) {
  switch (state) {
    case STATE_GPS_NOT_FOUND: return "GPS_NOT_FOUND";
    case STATE_NO_FIX:        return "NO_FIX";
    case STATE_OFFLINE:       return "OFFLINE";
    default:                  return "OPERATIONAL";
  }
}

// ------------------------------------------------------------------ payload

/**
 * {"device":"esp32-01","lat":7.377500,"lon":3.947000,"state":4,"sats":9,
 *  "speed":12.30,"heading":42.0,"alt":218.0,"hdop":0.90,"battery":87}
 *
 * The app accepts the numeric state (1-4) or the textual name, and ignores any field it
 * does not have, so "device", "lat", "lon" and "state" are the only required ones.
 */
String buildPayload(const Fix &fix, TrackerState state) {
  char json[320];
  int battery = readBatteryPercent();
  if (fix.valid) {
    snprintf(json, sizeof(json),
             "{\"device\":\"%s\",\"lat\":%.6f,\"lon\":%.6f,\"state\":%d,\"sats\":%d,"
             "\"speed\":%.2f,\"heading\":%.1f,\"alt\":%.1f,\"hdop\":%.2f,\"battery\":%d,"
             "\"uptime\":%lu}",
             DEVICE_ID, fix.lat, fix.lon, (int) state, fix.satellites, fix.speed, fix.heading,
             fix.altitude, fix.hdop, battery, (unsigned long) millis());
  } else {
    // No fix: still report, so the app can show the device and its state.
    snprintf(json, sizeof(json),
             "{\"device\":\"%s\",\"lat\":0,\"lon\":0,\"state\":%d,\"sats\":%d,\"battery\":%d,"
             "\"uptime\":%lu}",
             DEVICE_ID, (int) state, fix.satellites, battery, (unsigned long) millis());
  }
  return String(json);
}

bool postTelemetry(const char *payload) {
  if (WiFi.status() != WL_CONNECTED) {
    return false;
  }
  HTTPClient http;
  String url = String("http://") + SERVER_IP + ":" + SERVER_PORT + "/telemetry";
  if (!http.begin(url)) {
    return false;
  }
  http.addHeader("Content-Type", "application/json");
  http.setTimeout(3000);
  int code = http.POST((uint8_t *) payload, strlen(payload));
  http.end();
  return code > 0 && code < 400;
}

// ------------------------------------------------------------------ Wi-Fi

bool ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 5000) {
    delay(200);
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Wi-Fi up, IP ");
    Serial.println(WiFi.localIP());
    return true;
  }
  return false;
}

void bufferCurrentFix(TrackerState state) {
  if (bufferedCount >= MAX_BUFFERED_FIXES) {
    // Drop the oldest fix, keeping the most recent history.
    for (int i = 1; i < MAX_BUFFERED_FIXES; i++) {
      buffered[i - 1] = buffered[i];
    }
    bufferedCount = MAX_BUFFERED_FIXES - 1;
  }
  buffered[bufferedCount] = gpsFix;
  buffered[bufferedCount].millisAtFix = millis();
  bufferedCount++;
  (void) state;
}

// ------------------------------------------------------------------ real GPS (NMEA)

/**
 * Reads $GxRMC (position, speed, course) and $GxGGA (fix quality, satellites, HDOP,
 * altitude) from the module. Anything else is ignored.
 */
void readGpsSerial() {
  while (GPS.available() > 0) {
    char c = (char) GPS.read();
    static char line[160];
    static size_t length = 0;

    if (c == '\n') {
      line[length] = '\0';
      if (length > 6) {
        parseNmea(line);
      }
      length = 0;
      return;  // one sentence per loop tick keeps the watchdog happy
    }
    if (c != '\r' && length < sizeof(line) - 1) {
      line[length++] = c;
    }
  }
  if (gpsDeviceSeen && millis() - lastGpsByteAt > GPS_TIMEOUT_MS) {
    // The module went quiet - report it as missing rather than showing a stale dot.
    gpsDeviceSeen = false;
    gpsFix.valid = false;
  }
}

void parseNmea(const char *line) {
  if (line[0] != '$') {
    return;
  }
  gpsDeviceSeen = true;
  lastGpsByteAt = millis();

  if (strstr(line, "RMC") != nullptr) {
    // $GNRMC,hhmmss.ss,A,llll.ll,N,yyyyy.yy,E,speed,course,date,...
    double lat = nmeaToDegrees(field(line, 3), field(line, 4));
    double lon = nmeaToDegrees(field(line, 5), field(line, 6));
    bool valid = (field(line, 2)[0] == 'A') && !isnan(lat) && !isnan(lon);
    if (valid) {
      gpsFix.lat = lat;
      gpsFix.lon = lon;
      gpsFix.valid = true;
      gpsFix.millisAtFix = millis();
      gpsFix.speed = atof(field(line, 7)) * 0.514444f;   // knots -> m/s
      gpsFix.heading = atof(field(line, 8));
      prefs.putDouble("lat", lat);
      prefs.putDouble("lon", lon);
    } else {
      gpsFix.valid = false;
    }
  } else if (strstr(line, "GGA") != nullptr) {
    // $GNGGA,time,lat,N,lon,E,quality,sats,hdop,alt,M,...
    gpsFix.satellites = atoi(field(line, 7));
    gpsFix.hdop = atof(field(line, 8));
    gpsFix.altitude = atof(field(line, 9));
    int quality = atoi(field(line, 6));
    if (quality == 0) {
      gpsFix.valid = false;   // module is alive but has no fix yet
    }
  }
}

/** Returns the nth comma separated field of a sentence (n starts at 1). */
static const char *field(const char *line, int index) {
  static char buffer[32];
  int current = 1;
  const char *start = line;
  for (const char *p = line; *p; p++) {
    if (*p == ',') {
      if (current == index) {
        size_t length = min((size_t) (p - start), sizeof(buffer) - 1);
        memcpy(buffer, start, length);
        buffer[length] = '\0';
        return buffer;
      }
      current++;
      start = p + 1;
    }
  }
  if (current == index) {
    snprintf(buffer, sizeof(buffer), "%s", start);
    return buffer;
  }
  buffer[0] = '\0';
  return buffer;
}

/** "3750.1234",N -> 37.835390 */
static double nmeaToDegrees(const char *value, const char *hemisphere) {
  if (value == nullptr || value[0] == '\0') {
    return NAN;
  }
  double raw = atof(value);
  double degrees = floor(raw / 100.0);
  double minutes = raw - degrees * 100.0;
  double result = degrees + minutes / 60.0;
  if (hemisphere[0] == 'S' || hemisphere[0] == 'W') {
    result = -result;
  }
  return result;
}

// ------------------------------------------------------------------ demo + battery

/** Walks a small circle around Ibadan so the app can be tested without a GPS module. */
void updateSimulatedFix() {
  static unsigned long lastStep = 0;
  if (millis() - lastStep < 1000) {
    return;
  }
  lastStep = millis();

  const double baseLat = 7.3775;
  const double baseLon = 3.9470;
  double angle = (millis() / 1000.0) * 0.12;
  gpsFix.lat = baseLat + sin(angle) * 0.004;
  gpsFix.lon = baseLon + cos(angle) * 0.004;
  gpsFix.speed = 12.3f;
  gpsFix.heading = fmod((angle * 180.0 / M_PI) + 90.0, 360.0);
  gpsFix.altitude = 218.0f;
  gpsFix.hdop = 0.9f;
  gpsFix.satellites = 9;
  gpsFix.valid = true;
  gpsFix.millisAtFix = millis();
  gpsDeviceSeen = true;
  lastGpsByteAt = millis();
}

/**
 * Battery percentage. Defaults to -1 (reported as nothing) unless you add a voltage
 * divider on ADC pin 34 - then fill in the values below.
 */
int readBatteryPercent() {
#if defined(ADC_PIN)
  int raw = analogRead(ADC_PIN);
  float volts = raw * (3.3f / 4095.0f) * 2.0f;   // divide by two if using a 1:1 divider
  int percent = (int) ((volts - 3.3f) / (4.2f - 3.3f) * 100.0f);
  return constrain(percent, 0, 100);
#else
  return -1;
#endif
}
