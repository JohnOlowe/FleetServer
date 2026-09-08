package damjay.tracker.fleetserver.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** App settings shared by the UI and the background service. */
public final class Prefs {

  public static final String NAME = "fleet_prefs";
  public static final String KEY_PORT = "port";
  public static final String KEY_AUTO_START = "auto_start";
  public static final String KEY_MAP_MODE = "map_mode";
  public static final String KEY_FOLLOW = "follow_device";
  public static final String KEY_SHOW_TRAIL = "show_trail";

  public static final int DEFAULT_PORT = 8080;
  public static final String MAP_MODE_OSM = "osm";
  public static final String MAP_MODE_OFFLINE = "offline";

  private Prefs() {
  }

  public static SharedPreferences get(@NonNull Context context) {
    return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
  }

  public static int port(@NonNull Context context) {
    int port = get(context).getInt(KEY_PORT, DEFAULT_PORT);
    if (port < 1024 || port > 65535) {
      return DEFAULT_PORT;
    }
    return port;
  }

  public static boolean autoStart(@NonNull Context context) {
    return get(context).getBoolean(KEY_AUTO_START, true);
  }

  public static boolean followDevice(@NonNull Context context) {
    return get(context).getBoolean(KEY_FOLLOW, true);
  }

  public static boolean showTrail(@NonNull Context context) {
    return get(context).getBoolean(KEY_SHOW_TRAIL, true);
  }

  public static String mapMode(@NonNull Context context) {
    return get(context).getString(KEY_MAP_MODE, MAP_MODE_OSM);
  }

  public static void setMapMode(@NonNull Context context, @NonNull String mode) {
    get(context).edit().putString(KEY_MAP_MODE, mode).apply();
  }
}
