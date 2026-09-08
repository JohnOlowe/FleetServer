package damjay.tracker.fleetserver.store;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import damjay.tracker.fleetserver.model.DeviceState;
import damjay.tracker.fleetserver.model.Telemetry;
import damjay.tracker.fleetserver.model.TrackedDevice;

/**
 * Process-wide memory of every tracker the app has heard from.
 *
 * <p>The ingest path is called from server threads, so everything public is thread safe and
 * listeners are always notified on the main thread. The fleet is persisted as JSON so the app
 * still shows the last known positions after a restart.
 */
public class FleetStore {

  private static final String TAG = "FleetStore";
  private static final String FILE_NAME = "fleet_state.json";
  private static final int MAX_LOGS = 300;
  private static final int TRAIL_LIMIT = 500;

  /** One line of the traffic log. */
  public static class LogEntry {
    public final long timeMillis;
    public final String level;   // "in", "err", "sys"
    public final String message;

    public LogEntry(long timeMillis, String level, String message) {
      this.timeMillis = timeMillis;
      this.level = level;
      this.message = message;
    }
  }

  public interface Listener {
    void onFleetChanged();

    void onLogChanged();
  }

  private static volatile FleetStore instance;

  private final Context appContext;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final LinkedHashMap<String, TrackedDevice> devices = new LinkedHashMap<>();
  private final ArrayList<LogEntry> logs = new ArrayList<>();
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();

  private long packetsAccepted;
  private long packetsRejected;
  private boolean serverRunning;
  private int serverPort;
  private String serverError = "";
  private final List<String> endpoints = new ArrayList<>();

  private final Runnable saveTask = new Runnable() {
    @Override
    public void run() {
      saveNow();
    }
  };

  public static FleetStore get(@NonNull Context context) {
    FleetStore local = instance;
    if (local == null) {
      synchronized (FleetStore.class) {
        local = instance;
        if (local == null) {
          local = new FleetStore(context.getApplicationContext());
          local.restore();
          instance = local;
        }
      }
    }
    return local;
  }

  private FleetStore(Context context) {
    this.appContext = context;
  }

  // ---------------------------------------------------------------- ingestion

  /** Records a report from a tracker identified by {@code sourceIp} when it has no id of its own. */
  public synchronized void ingest(@NonNull Telemetry telemetry, String sourceIp) {
    String id = telemetry.deviceId == null || telemetry.deviceId.trim().isEmpty()
        ? "esp32-" + safe(sourceIp)
        : telemetry.deviceId.trim();
    TrackedDevice device = devices.get(id);
    if (device == null) {
      device = new TrackedDevice(id);
      device.trailLimit = TRAIL_LIMIT;
      device.firstSeen = telemetry.receivedAt;
      devices.put(id, device);
    }
    device.apply(telemetry, sourceIp);
    packetsAccepted++;
    log("in", telemetry.describe());
    notifyFleetChanged();
    scheduleSave();
  }

  public void reject(String reason, String sourceIp) {
    packetsRejected++;
    log("err", reason);
  }

  // ---------------------------------------------------------------- queries

  public synchronized List<TrackedDevice> snapshot() {
    return new ArrayList<>(devices.values());
  }

  public synchronized TrackedDevice get(String id) {
    return devices.get(id);
  }

  public synchronized List<String> deviceIds() {
    return new ArrayList<>(devices.keySet());
  }

  public synchronized void remove(String id) {
    if (devices.remove(id) != null) {
      log("sys", "removed " + id);
      notifyFleetChanged();
      scheduleSave();
    }
  }

  public synchronized void clearTrail(String id) {
    TrackedDevice device = devices.get(id);
    if (device != null && !device.trail.isEmpty()) {
      device.trail.clear();
      log("sys", "cleared trail of " + id);
      notifyFleetChanged();
      scheduleSave();
    }
  }

  public synchronized void clearDevices() {
    devices.clear();
    log("sys", "cleared all devices");
    notifyFleetChanged();
    scheduleSave();
  }

  public synchronized long totalPackets() {
    return packetsAccepted;
  }

  public synchronized long rejectedPackets() {
    return packetsRejected;
  }

  // ---------------------------------------------------------------- server state

  public synchronized void setServerState(boolean running, int port, String error,
      List<String> endpointList) {
    serverRunning = running;
    serverPort = port;
    serverError = error == null ? "" : error;
    endpoints.clear();
    if (endpointList != null) {
      endpoints.addAll(endpointList);
    }
    notifyFleetChanged();
  }

  public synchronized boolean isServerRunning() {
    return serverRunning;
  }

  public synchronized int getServerPort() {
    return serverPort;
  }

  public synchronized String getServerError() {
    return serverError;
  }

  public synchronized List<String> getEndpoints() {
    return new ArrayList<>(endpoints);
  }

  // ---------------------------------------------------------------- logging

  public void log(String level, String message) {
    synchronized (this) {
      logs.add(new LogEntry(System.currentTimeMillis(), level, message));
      while (logs.size() > MAX_LOGS) {
        logs.remove(0);
      }
    }
    mainHandler.post(new Runnable() {
      @Override
      public void run() {
        for (Listener listener : listeners) {
          listener.onLogChanged();
        }
      }
    });
  }

  public synchronized List<LogEntry> logs() {
    return new ArrayList<>(logs);
  }

  public synchronized void clearLogs() {
    logs.clear();
  }

  // ---------------------------------------------------------------- listeners

  public void addListener(Listener listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  private void notifyFleetChanged() {
    mainHandler.post(new Runnable() {
      @Override
      public void run() {
        for (Listener listener : listeners) {
          listener.onFleetChanged();
        }
      }
    });
  }

  // ---------------------------------------------------------------- persistence

  private void scheduleSave() {
    mainHandler.removeCallbacks(saveTask);
    mainHandler.postDelayed(saveTask, 2000L);
  }

  /** org.json refuses NaN/Infinity, so missing values are simply left out. */
  private static void putDouble(JSONObject object, String key, double value) {
    if (!Double.isNaN(value) && !Double.isInfinite(value)) {
      try {
        object.put(key, value);
      } catch (Exception ignored) {
        // never fatal for persistence
      }
    }
  }

  private synchronized String toJson() {
    try {
      JSONObject root = new JSONObject();
      root.put("version", 1);
      JSONArray array = new JSONArray();
      for (TrackedDevice device : devices.values()) {
        JSONObject o = new JSONObject();
        o.put("id", device.id);
        o.put("name", device.name);
        o.put("firstSeen", device.firstSeen);
        o.put("lastSeen", device.lastSeen);
        o.put("packets", device.packets);
        o.put("lastSourceIp", device.lastSourceIp);
        Telemetry t = device.last;
        if (t != null) {
          JSONObject last = new JSONObject();
          last.put("deviceId", t.deviceId);
          last.put("name", t.name);
          putDouble(last, "latitude", t.latitude);
          putDouble(last, "longitude", t.longitude);
          last.put("state", t.state.code);
          putDouble(last, "speed", t.speed);
          putDouble(last, "heading", t.heading);
          putDouble(last, "altitude", t.altitude);
          putDouble(last, "accuracy", t.accuracy);
          putDouble(last, "hdop", t.hdop);
          putDouble(last, "battery", t.battery);
          last.put("satellites", t.satellites);
          last.put("rssi", t.rssi);
          last.put("deviceTime", t.deviceTime);
          last.put("receivedAt", t.receivedAt);
          last.put("note", t.note);
          o.put("last", last);
        }
        JSONArray trail = new JSONArray();
        for (TrackedDevice.TrailPoint point : device.trail) {
          JSONArray p = new JSONArray();
          p.put(point.latitude);
          p.put(point.longitude);
          p.put(point.timeMillis);
          p.put(point.stateCode);
          trail.put(p);
        }
        o.put("trail", trail);
        array.put(o);
      }
      root.put("devices", array);
      return root.toString();
    } catch (Exception e) {
      Log.w(TAG, "could not serialise fleet", e);
      return "{}";
    }
  }

  private void saveNow() {
    final String json;
    synchronized (this) {
      json = toJson();
    }
    io.execute(new Runnable() {
      @Override
      public void run() {
        try {
          File file = new File(appContext.getFilesDir(), FILE_NAME);
          File tmp = new File(appContext.getFilesDir(), FILE_NAME + ".tmp");
          try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
          }
          if (!tmp.renameTo(file)) {
            Files.copy(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tmp.delete();
          }
        } catch (IOException e) {
          Log.w(TAG, "could not persist fleet", e);
        }
      }
    });
  }

  private void restore() {
    File file = new File(appContext.getFilesDir(), FILE_NAME);
    if (!file.exists()) {
      return;
    }
    try {
      String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      JSONObject root = new JSONObject(json);
      JSONArray array = root.optJSONArray("devices");
      if (array == null) {
        return;
      }
      synchronized (this) {
        for (int i = 0; i < array.length(); i++) {
          JSONObject o = array.optJSONObject(i);
          if (o == null) {
            continue;
          }
          String id = o.optString("id", "");
          if (id.isEmpty()) {
            continue;
          }
          TrackedDevice device = new TrackedDevice(id);
          device.trailLimit = TRAIL_LIMIT;
          device.name = o.optString("name", "");
          device.firstSeen = o.optLong("firstSeen", 0L);
          device.lastSeen = o.optLong("lastSeen", 0L);
          device.packets = o.optLong("packets", 0L);
          device.lastSourceIp = o.optString("lastSourceIp", "");
          JSONObject last = o.optJSONObject("last");
          if (last != null) {
            Telemetry t = new Telemetry();
            t.deviceId = last.optString("deviceId", id);
            t.name = last.optString("name", "");
            t.latitude = last.optDouble("latitude", Double.NaN);
            t.longitude = last.optDouble("longitude", Double.NaN);
            t.state = DeviceState.fromCode(last.optInt("state", 0));
            t.speed = last.optDouble("speed", Double.NaN);
            t.heading = last.optDouble("heading", Double.NaN);
            t.altitude = last.optDouble("altitude", Double.NaN);
            t.accuracy = last.optDouble("accuracy", Double.NaN);
            t.hdop = last.optDouble("hdop", Double.NaN);
            t.battery = last.optDouble("battery", Double.NaN);
            t.satellites = last.optInt("satellites", -1);
            t.rssi = last.optInt("rssi", Integer.MIN_VALUE);
            t.deviceTime = last.optLong("deviceTime", 0L);
            t.receivedAt = last.optLong("receivedAt", System.currentTimeMillis());
            t.note = last.optString("note", "");
            device.last = t;
          }
          JSONArray trail = o.optJSONArray("trail");
          if (trail != null) {
            for (int j = 0; j < trail.length() && device.trail.size() < TRAIL_LIMIT; j++) {
              JSONArray p = trail.optJSONArray(j);
              if (p == null || p.length() < 4) {
                continue;
              }
              device.trail.add(new TrackedDevice.TrailPoint(p.optDouble(0, Double.NaN),
                  p.optDouble(1, Double.NaN), p.optLong(2, 0L), p.optInt(3, 0)));
            }
          }
          devices.put(id, device);
        }
      }
      log("sys", "restored " + devices.size() + " device(s) from disk");
    } catch (Exception e) {
      Log.w(TAG, "could not restore fleet", e);
    }
  }

  /** Injects a synthetic report - used by the "simulate packet" action in the UI. */
  public void ingestSimulated(double lat, double lon, DeviceState state, String id) {
    Telemetry t = new Telemetry();
    t.deviceId = id;
    t.latitude = lat;
    t.longitude = lon;
    t.state = state;
    t.satellites = state == DeviceState.OPERATIONAL ? 9 : 0;
    t.accuracy = state == DeviceState.OPERATIONAL ? 4.5d : Double.NaN;
    t.speed = state == DeviceState.OPERATIONAL ? 12.3d : 0d;
    t.heading = 42d;
    t.note = "simulated";
    ingest(t, "127.0.0.1");
  }

  private static String safe(String value) {
    if (value == null || value.isEmpty()) {
      return "unknown";
    }
    return value.replace(":", "-").replace("/", "-");
  }

  /** Endpoints sorted so the most likely one (wifi / hotspot) comes first. */
  public static List<String> sortEndpoints(List<String> values) {
    List<String> copy = new ArrayList<>(values);
    Collections.sort(copy, (a, b) -> score(b) - score(a));
    return copy;
  }

  private static int score(String address) {
    String a = address.toLowerCase(Locale.US);
    if (a.startsWith("192.168.43.")) {
      return 5;   // classic Android hotspot range
    }
    if (a.startsWith("192.168.")) {
      return 4;
    }
    if (a.startsWith("10.")) {
      return 3;
    }
    if (a.startsWith("172.")) {
      return 2;
    }
    return 1;
  }
}
