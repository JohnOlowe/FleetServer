package damjay.tracker.fleetserver.server;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import damjay.tracker.fleetserver.R;

/**
 * Loads the browser dashboard (app/src/main/res/raw/dashboard.html) that the phone serves, so a
 * laptop on the same network can watch the fleet on a map.
 */
public final class DashboardPage {

  private DashboardPage() {
  }

  @NonNull
  public static String load(@NonNull Context context) {
    try (InputStream in = context.getResources().openRawResource(R.raw.dashboard);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fallback();
    }
  }

  /** Used only if the packaged page cannot be read - still tells you where the data is. */
  @NonNull
  private static String fallback() {
    return "<!doctype html><meta charset='utf-8'><title>FleetServer</title>"
        + "<body style='font-family:system-ui;background:#0b131c;color:#e6edf3;padding:28px'>"
        + "<h1>FleetServer</h1><p>The map page could not be loaded on the phone.</p>"
        + "<p>Live data is still available:</p><ul>"
        + "<li><a style='color:#12bfa5' href='api/devices?trail=1'>api/devices?trail=1</a></li>"
        + "<li><a style='color:#12bfa5' href='api/log'>api/log</a></li></ul></body>";
  }
}
