package damjay.tracker.fleetserver.ui;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import damjay.tracker.fleetserver.R;

/** Formatting helpers shared by the list, the detail sheet and the traffic log. */
public final class Format {

  private Format() {
  }

  public static String coordinate(double value) {
    if (Double.isNaN(value)) {
      return "—";
    }
    return String.format(Locale.US, "%.6f", value);
  }

  public static String coordinates(double latitude, double longitude) {
    if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
      return "—";
    }
    return coordinate(latitude) + ", " + coordinate(longitude);
  }

  public static String timeAgo(Context context, long timestamp) {
    long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
    if (seconds < 5) {
      return context.getString(R.string.last_seen_just_now);
    }
    String value;
    if (seconds < 60) {
      value = seconds + "s";
    } else if (seconds < 3600) {
      value = (seconds / 60) + "m";
    } else if (seconds < 86400) {
      value = (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    } else {
      value = (seconds / 86400) + "d";
    }
    return context.getString(R.string.ago_format, value);
  }

  public static String clock(long timestamp) {
    return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timestamp));
  }

  public static String dateTime(long timestamp) {
    return new SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(new Date(timestamp));
  }

  /** Formats a value with a unit, or "—" when it is missing. */
  public static String value(double number, String unit, int decimals) {
    if (Double.isNaN(number) || Double.isInfinite(number)) {
      return "—";
    }
    return String.format(Locale.US, "%." + decimals + "f", number) + (unit.isEmpty() ? "" : " " + unit);
  }

  public static String integer(int number, String unit) {
    if (number < 0) {
      return "—";
    }
    return number + (unit.isEmpty() ? "" : " " + unit);
  }

  /** Cardinal direction for a heading in degrees. */
  public static String compass(double heading) {
    if (Double.isNaN(heading)) {
      return "";
    }
    String[] points = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW",
        "W", "WNW", "NW", "NNW"};
    int index = (int) Math.round(((heading % 360d) + 360d) % 360d / 22.5d) % 16;
    return " " + points[index];
  }
}
