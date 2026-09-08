package damjay.tracker.fleetserver.model;

import androidx.annotation.NonNull;

/**
 * A single telemetry report received from an ESP32 tracker.
 *
 * <p>Missing numeric values stay {@link Double#NaN} so the UI can hide them instead of showing
 * zeroes. Latitude/longitude of exactly 0/0 is treated as "no position" - trackers send that
 * when the GPS has never had a fix.
 */
public class Telemetry {

  public String deviceId = "";
  public String name = "";
  public double latitude = Double.NaN;
  public double longitude = Double.NaN;
  public DeviceState state = DeviceState.UNKNOWN;

  public double speed = Double.NaN;        // metres per second
  public double heading = Double.NaN;      // degrees from true north
  public double altitude = Double.NaN;     // metres
  public double accuracy = Double.NaN;     // metres
  public double hdop = Double.NaN;
  public double battery = Double.NaN;      // percent or volts, as sent
  public int satellites = -1;
  public int rssi = Integer.MIN_VALUE;     // wifi signal, dBm
  public long deviceTime = 0L;             // millis reported by the device (uptime or epoch)
  public long receivedAt = System.currentTimeMillis();
  public String note = "";

  /** True when this report carries coordinates we can plot. */
  public boolean hasPosition() {
    if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
      return false;
    }
    if (latitude == 0.0d && longitude == 0.0d) {
      return false;
    }
    return Math.abs(latitude) <= 90.0d && Math.abs(longitude) <= 180.0d;
  }

  /** Human readable one-liner used by the traffic log. */
  @NonNull
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(deviceId == null || deviceId.isEmpty() ? "unknown" : deviceId);
    sb.append(' ');
    if (hasPosition()) {
      sb.append(String.format(java.util.Locale.US, "%.6f,%.6f", latitude, longitude));
    } else {
      sb.append("--,--");
    }
    sb.append(" [").append(state.wireName).append(']');
    return sb.toString();
  }
}
