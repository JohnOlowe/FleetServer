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
 * Turns whatever an ESP32 - or a laptop with curl - sends into a {@link Telemetry}.
 *
 * <p>These shapes are all understood:
 * <ul>
 *   <li>JSON - {@code {"device":"esp32-01","lat":6.52,"lon":3.37,"state":4}}</li>
 *   <li>key/value - {@code lat=6.52&lon=3.37&state=4} (query string or plain text)</li>
 *   <li>key:value - {@code device:esp32-01,lat:6.52,lon:3.37,state:4}</li>
 *   <li>ordered values - {@code esp32-01,6.52,3.37,4} (CSV-ish, comma/semicolon/space separated)</li>
 * </ul>
 * <p>Quotes and braces are optional: a payload that a shell has mangled (unquoted JSON, stray
 * quotes around the body, {@code {device:x,lat:1.2}}) is still parsed. Field names are matched
 * case-insensitively against a list of common aliases.
 */
public final class TelemetryParser {

  private TelemetryParser() {
  }

  /** Parse a payload, guessing its shape. Throws with a readable message when it makes no sense. */
  public static Telemetry parse(String payload) {
    String s = stripOuterQuotes(payload == null ? "" : payload.trim());
    if (s.isEmpty()) {
      throw new IllegalArgumentException("empty payload");
    }
    if (s.charAt(0) == '{' || s.charAt(0) == '[') {
      try {
        return fromJson(s);
      } catch (RuntimeException e) {
        // JSON that a shell mangled - quotes or braces missing, e.g.
        // {device:esp32-01,lat:6.52,lon:3.37,state:4}. Parse it leniently instead.
        return fromKeyValue(s);
      }
    }
    if (s.indexOf('=') >= 0 || s.indexOf(':') >= 0) {
      return fromKeyValue(s);
    }
    return fromDelimited(s);
  }

  /** Drops one matching pair of quotes around a body - Windows cmd.exe leaves these behind. */
  private static String stripOuterQuotes(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
        return value.substring(1, value.length() - 1).trim();
      }
    }
    return value;
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

  /**
   * Parses {@code a=1&b=2} and {@code a:1,b:2} payloads - query strings included, and any
   * JSON-ish body whose quotes went missing on the way here.
   */
  public static Telemetry fromKeyValue(String raw) {
    Map<String, Object> values = new HashMap<>();
    String body = raw.replace('{', ' ').replace('}', ' ').replace('[', ' ').replace(']', ' ');
    for (String token : body.split("[,&;\\r\\n]+")) {
      int separator = separatorIndex(token);
      if (separator <= 0) {
        continue;
      }
      String key = cleanKey(token.substring(0, separator));
      String value = cleanValue(token.substring(separator + 1));
      if (!key.isEmpty() && !value.isEmpty()) {
        values.put(key, value);
      }
    }
    return fromMap(values);
  }

  /** Position of the '=' or ':' that separates a key from its value. */
  private static int separatorIndex(String token) {
    int equals = token.indexOf('=');
    int colon = token.indexOf(':');
    if (equals >= 0 && (colon < 0 || equals < colon)) {
      return equals;
    }
    return colon;
  }

  private static String cleanKey(String raw) {
    return decode(raw).trim().replace("\"", "").replace("'", "").replace("{", "")
        .replace("[", "").trim();
  }

  private static String cleanValue(String raw) {
    return decode(raw).trim().replace("\"", "").replace("'", "").replace("}", "")
        .replace("]", "").trim();
  }

  /** Parses {@code device,lat,lon,state[,speed,heading]} style payloads. */
  public static Telemetry fromDelimited(String raw) {
    String[] tokens = raw.trim().split("[,;\\s]+");
    Map<String, Object> values = new HashMap<>();
    if (tokens.length >= 4) {
      values.put("device", unglue(tokens[0]));
      values.put("lat", unglue(tokens[1]));
      values.put("lon", unglue(tokens[2]));
      values.put("state", unglue(tokens[3]));
      if (tokens.length >= 5) {
        values.put("speed", unglue(tokens[4]));
      }
      if (tokens.length >= 6) {
        values.put("heading", unglue(tokens[5]));
      }
    } else if (tokens.length == 3) {
      values.put("lat", unglue(tokens[0]));
      values.put("lon", unglue(tokens[1]));
      values.put("state", unglue(tokens[2]));
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

  /** Strips a leading {@code key:} if a field arrived glued to its value. */
  private static String unglue(String token) {
    int colon = token.indexOf(':');
    return colon >= 0 ? token.substring(colon + 1) : token;
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
      throw new IllegalArgumentException("\"" + keys[0] + "\" is not a number (got \"" + value
          + "\")");
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
