package damjay.tracker.fleetserver.model;

import androidx.annotation.NonNull;

import damjay.tracker.fleetserver.R;

/**
 * The four states an ESP32 tracker can report, plus UNKNOWN for anything we cannot decode.
 *
 * <p>Wire codes: 1 = GPS device not found, 2 = No GPS fix, 3 = Offline mode, 4 = Operational.
 */
public enum DeviceState {
  GPS_NOT_FOUND(1, "GPS device not found", R.color.state_gps_not_found, "GPS_NOT_FOUND"),
  NO_FIX(2, "No GPS fix", R.color.state_no_fix, "NO_FIX"),
  OFFLINE(3, "Offline mode", R.color.state_offline, "OFFLINE"),
  OPERATIONAL(4, "Operational", R.color.state_operational, "OPERATIONAL"),
  UNKNOWN(0, "Unknown", R.color.state_unknown, "UNKNOWN");

  public final int code;
  public final String label;
  public final int colorRes;
  public final String wireName;

  DeviceState(int code, String label, int colorRes, String wireName) {
    this.code = code;
    this.label = label;
    this.colorRes = colorRes;
    this.wireName = wireName;
  }

  /** Decode a numeric state code. */
  public static DeviceState fromCode(int code) {
    switch (code) {
      case 1:
        return GPS_NOT_FOUND;
      case 2:
        return NO_FIX;
      case 3:
        return OFFLINE;
      case 4:
        return OPERATIONAL;
      default:
        return UNKNOWN;
    }
  }

  /**
   * Decode a state sent as a number (1-4) or as text, e.g. {@code "no_fix"}, {@code "offline"},
   * {@code "operational"}. Unrecognised input becomes {@link #UNKNOWN}.
   */
  public static DeviceState from(@NonNull Object raw) {
    String s = String.valueOf(raw).trim();
    if (s.isEmpty()) {
      return UNKNOWN;
    }
    try {
      return fromCode((int) Double.parseDouble(s));
    } catch (NumberFormatException ignored) {
      // fall through to the textual mapping
    }
    String v = s.toLowerCase().replace(' ', '_').replace('-', '_');
    if (v.contains("not_found") || (v.contains("no_gps") && v.contains("device")) || v.equals("nogps")) {
      return GPS_NOT_FOUND;
    }
    if (v.contains("offline") || v.contains("cached") || v.contains("buffer")) {
      return OFFLINE;
    }
    if (v.contains("no_fix") || v.contains("nofix") || v.equals("searching") || v.equals("nofix_yet")) {
      return NO_FIX;
    }
    if (v.contains("operational") || v.contains("online") || v.equals("ok") || v.equals("fix")
        || v.equals("valid") || v.equals("locked") || v.equals("tracking")) {
      return OPERATIONAL;
    }
    return UNKNOWN;
  }

  /** True when the state usually comes with usable coordinates. */
  public boolean expectsPosition() {
    return this == OPERATIONAL || this == OFFLINE;
  }
}
