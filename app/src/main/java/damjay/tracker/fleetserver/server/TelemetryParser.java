package damjay.tracker.fleetserver.server;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import damjay.tracker.fleetserver.model.DeviceState;
import damjay.tracker.fleetserver.model.Telemetry;

/**
 * Turns whatever an ESP32 sends into a {@link Telemetry}.
 *
 * <p>Three shapes are understood, so almost any sketch works without changes:
 * <ul>
 *   <li>JSON - {@code {"device":"esp32-01","lat":6.52,"lon":3.37,"state":4}}</li>
 *   <li>key/value - {@code lat=6.52&lon=3.37&state=4} (query string or plain text)</li>
 *   <li>ordered values - {@code esp32-01,6.52,3.37,4} (CSV-ish, comma/semicolon/space separated)</li>
 * </ul>
 * Field names are matched case-insensitively against a list of common aliases.
 */
public final class TelemetryParser {

  private TelemetryParser() {
  }

  /** Parse a payload, guessing its shape. Throws with a readable message when it makes no sense. */
  public static Telemetry parse(String payload) {
    String s = payload == null ? "" : payload.trim();
    if (s.isEmpty()) {
      throw new IllegalArgumentException("empty payload");
    }
    if (s.charAt(0) == '{' || s.charAt(0) == '[') {
      return fromJson(s);
    }
    if (s.indexOf('=') >= 0) {
      return fromKeyValue(s);
    }
    return fromDelimited(s);
  }

  public static Telemetry fromJson(String json) {
    JSONObject object;
    try {
      object = new JSONObject(json);
    } catch (org.json.JSONException e) {
      throw new IllegalArgumentException("invalid JSON: " + e.getMessage());
    }
    Map<String, Object> values = new HashMap<>();
    Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!object.isNull(key)) {
        values.put(key.toLowerCase(Locale.US), object.opt(key));
      }
    }
    return fromMap(values);
  }

  /** Parses {@code a=1&b=2} style payloads (query strings included). */
  public static Telemetry fromKeyValue(String raw) {
    Map<String, Object> values = new HashMap<>();
    String[] tokens = raw.split("[&\\r\\n;]+");
    for (String token : tokens) {
      int eq = token.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = decode(token.substring(0, eq)).trim().toLowerCase(Locale.US);
      String value = decode(token.substring(eq + 1)).trim();
      if (!key.isEmpty()) {
        values.put(key, value);
      }
    }
    return fromMap(values);
  }

  /** Parses {@code device,lat,lon,state[,speed,heading]} style payloads. */
  public static Telemetry fromDelimited(String raw) {
    String[] tokens = raw.trim().split("[,;\\s]+");
    Map<String, Object> values = new HashMap<>();
    if (tokens.length >= 4) {
      values.put("device", tokens[0]);
      values.put("lat", tokens[1]);
      values.put("lon", tokens[2]);
      values.put("state", tokens[3]);
      if (tokens.length >= 5) {
        values.put("speed", tokens[4]);
      }
      if (tokens.length >= 6) {
        values.put("heading", tokens[5]);
      }
    } else if (tokens.length == 3) {
      values.put("lat", tokens[0]);
      values.put("lon", tokens[1]);
      values.put("state", tokens[2]);
    } else {
      throw new IllegalArgumentException("expected JSON, key=value or lat,lon,state - got: " + raw);
    }
    return fromMap(values);
  }

  private static Telemetry fromMap(@NonNull Map<String, Object> values) {
    Telemetry t = new Telemetry();
    t.deviceId = clean(firstString(values, "deviceid", "device_id", "device", "dev", "id", "name",
        "vehicle", "unit", "tracker", "tag"));
    t.name = clean(firstString(values, "label", "displayname", "display_name", "vehiclename"));

    t.latitude = requireInRange(firstDouble(values, "lat", "latitude", "gpslat", "gps_lat", "y"), -90d, 90d, "latitude");
    t.longitude = requireInRange(firstDouble(values, "lon", "lng", "long", "longitude", "gpslon",
        "gps_lon", "gpslng", "gps_lng", "x"), -180d, 180d, "longitude");

    Object stateValue = first(values, "state", "status", "mode", "gps_state", "gpsstate", "fix_state", "devicestate");
    if (stateValue != null) {
      t.state = DeviceState.from(stateValue);
    } else if (t.hasPosition()) {
      // A device that reports real coordinates but no state is treated as operational.
      t.state = DeviceState.OPERATIONAL;
    }

    Double speedKmh = firstDouble(values, "speed_kmh", "speedkmh", "kmh");
    if (speedKmh != null) {
      t.speed = speedKmh / 3.6d;
    } else {
      t.speed = or(firstDouble(values, "speed", "spd", "velocity"), Double.NaN);
    }
    t.heading = or(firstDouble(values, "heading", "course", "cog", "bearing"), Double.NaN);
    t.altitude = or(firstDouble(values, "altitude", "alt", "elevation"), Double.NaN);
    t.accuracy = or(firstDouble(values, "accuracy", "acc", "hdop_m"), Double.NaN);
    t.hdop = or(firstDouble(values, "hdop"), Double.NaN);
    t.battery = or(firstDouble(values, "battery", "batt", "battery_level", "vbat"), Double.NaN);
    t.satellites = or(firstInt(values, "satellites", "sats", "sat"), -1);
    t.rssi = or(firstInt(values, "rssi", "signal"), Integer.MIN_VALUE);
    Long uptime = firstLong(values, "uptime", "millis", "uptime_ms", "boot_ms");
    t.deviceTime = uptime == null ? 0L : uptime;
    t.note = clean(firstString(values, "note", "message", "msg", "info", "log"));

    if (t.deviceId.isEmpty() && !t.hasPosition() && t.state == DeviceState.UNKNOWN) {
      throw new IllegalArgumentException("no device id, position or state in payload");
    }
    return t;
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static double or(Double value, double fallback) {
    return value == null ? fallback : value;
  }

  private static int or(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private static double requireInRange(Double value, double min, double max, String name) {
    if (value == null || Double.isNaN(value)) {
      return Double.NaN;
    }
    if (value < min || value > max) {
      throw new IllegalArgumentException(name + " out of range: " + value);
    }
    return value;
  }

  private static Object first(Map<String, Object> values, String... keys) {
    for (String key : keys) {
      Object value = values.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) {
        return value;
      }
    }
    return null;
  }

  private static String firstString(Map<String, Object> values, String... keys) {
    Object value = first(values, keys);
    return value == null ? "" : String.valueOf(value);
  }

  private static Double firstDouble(Map<String, Object> values, String... keys) {
    Object value = first(values, keys);
    if (value == null) {
      return null;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      String s = String.valueOf(value).trim().replace(',', '.');
      if (s.isEmpty()) {
        return null;
      }
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not a number: " + value);
    }
  }

  private static Integer firstInt(Map<String, Object> values, String... keys) {
    Double value = firstDouble(values, keys);
    return value == null ? null : (int) Math.round(value);
  }

  private static Long firstLong(Map<String, Object> values, String... keys) {
    Double value = firstDouble(values, keys);
    return value == null ? null : (long) Math.round(value);
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return value;
    }
  }
}
