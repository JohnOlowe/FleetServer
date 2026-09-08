package damjay.tracker.fleetserver.model;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** A tracker the server has heard from, with its most recent report and its trail. */
public class TrackedDevice {

  /** One point of the trail: position, when it was recorded, and the state at that moment. */
  public static class TrailPoint {
    public final double latitude;
    public final double longitude;
    public final long timeMillis;
    public int stateCode;

    public TrailPoint(double latitude, double longitude, long timeMillis, int stateCode) {
      this.latitude = latitude;
      this.longitude = longitude;
      this.timeMillis = timeMillis;
      this.stateCode = stateCode;
    }
  }

  public final String id;
  public String name = "";
  public Telemetry last;
  public final List<TrailPoint> trail = new ArrayList<>();
  public long firstSeen;
  public long lastSeen;
  public long packets;
  public String lastSourceIp = "";
  public int trailLimit = 500;

  public TrackedDevice(@NonNull String id) {
    this.id = id;
  }

  public String displayName() {
    if (name != null && !name.isEmpty()) {
      return name;
    }
    return id;
  }

  public boolean hasPosition() {
    return last != null && last.hasPosition();
  }

  public double latitude() {
    return last == null ? Double.NaN : last.latitude;
  }

  public double longitude() {
    return last == null ? Double.NaN : last.longitude;
  }

  public DeviceState state() {
    return last == null ? DeviceState.UNKNOWN : last.state;
  }

  /** Applies a new report and appends it to the trail when it carries a position. */
  public void apply(@NonNull Telemetry telemetry, String sourceIp) {
    boolean isFirst = (last == null);
    last = telemetry;
    if (isFirst || firstSeen == 0L) {
      firstSeen = telemetry.receivedAt;
    }
    lastSeen = telemetry.receivedAt;
    packets++;
    if (sourceIp != null) {
      lastSourceIp = sourceIp;
    }
    if (telemetry.name != null && !telemetry.name.isEmpty()) {
      name = telemetry.name;
    }
    if (telemetry.hasPosition()) {
      TrailPoint newest = trail.isEmpty() ? null : trail.get(trail.size() - 1);
      boolean moved = newest == null
          || newest.stateCode != telemetry.state.code
          || Geo.distanceMeters(newest.latitude, newest.longitude, telemetry.latitude, telemetry.longitude) > 1.0d;
      if (moved) {
        trail.add(new TrailPoint(telemetry.latitude, telemetry.longitude, telemetry.receivedAt,
            telemetry.state.code));
        while (trail.size() > trailLimit) {
          trail.remove(0);
        }
      } else if (newest != null) {
        newest.stateCode = telemetry.state.code;
      }
    }
  }

  /** Straight-line length of the recorded trail, in metres. */
  public double trailLengthMeters() {
    double total = 0.0d;
    for (int i = 1; i < trail.size(); i++) {
      TrailPoint a = trail.get(i - 1);
      TrailPoint b = trail.get(i);
      total += Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
    }
    return total;
  }
}
