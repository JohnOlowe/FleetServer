package damjay.tracker.fleetserver.server;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import damjay.tracker.fleetserver.model.Telemetry;
import damjay.tracker.fleetserver.model.TrackedDevice;
import damjay.tracker.fleetserver.store.FleetStore;

/**
 * A tiny dependency-free HTTP server that runs on the phone and accepts telemetry from ESP32
 * trackers on the same network (or on the phone's own hotspot).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /telemetry} - JSON body, e.g. {@code {"device":"esp32-01","lat":6.5,"lon":3.4,"state":4}}</li>
 *   <li>{@code GET  /telemetry?lat=..&lon=..&state=..} - the same data as a query string</li>
 *   <li>{@code GET  /} - small HTML dashboard (handy from a laptop on the same network)</li>
 *   <li>{@code GET  /api/devices} - JSON snapshot of the fleet</li>
 *   <li>{@code GET  /api/devices/<id>} - JSON for one device, including its trail</li>
 *   <li>{@code GET  /api/log} - JSON traffic log</li>
 *   <li>{@code GET  /health} - plain text "ok"</li>
 * </ul>
 * A client that is not speaking HTTP at all (raw TCP) may simply write one line such as
 * {@code 6.52,3.37,4} or a JSON object; the server answers with a plain text "OK".
 */
public final class FleetHttpServer {

  private static final String TAG = "FleetHttpServer";
  private static final int SO_TIMEOUT_MS = 10000;
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private static final int MAX_TRAIL_POINTS = 300;

  private static final List<String> TELEMETRY_PATHS = Arrays.asList(
      "/telemetry", "/api/telemetry", "/update", "/post", "/gps", "/report", "/location", "/fix");

  private final int port;
  private final FleetStore store;
  private final String dashboardPage;

  private ServerSocket serverSocket;
  private Thread acceptThread;
  private volatile boolean running;
  private final ExecutorService workers = Executors.newFixedThreadPool(8);

  public FleetHttpServer(int port, @NonNull FleetStore store, String dashboardPage) {
    this.port = port;
    this.store = store;
    this.dashboardPage = dashboardPage;
  }

  public synchronized void start() throws IOException {
    if (running) {
      return;
    }
    ServerSocket socket = new ServerSocket();
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(port));
    serverSocket = socket;
    running = true;
    acceptThread = new Thread(new Runnable() {
      @Override
      public void run() {
        acceptLoop();
      }
    }, "fleet-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  public synchronized void stop() {
    running = false;
    closeQuietly(serverSocket);
    serverSocket = null;
    if (acceptThread != null) {
      acceptThread.interrupt();
      acceptThread = null;
    }
    workers.shutdownNow();
  }

  public boolean isRunning() {
    return running;
  }

  public int getPort() {
    return port;
  }

  private void acceptLoop() {
    while (running) {
      try {
        final Socket socket = serverSocket.accept();
        try {
          workers.execute(new Runnable() {
            @Override
            public void run() {
              handle(socket);
            }
          });
        } catch (RejectedExecutionException e) {
          closeQuietly(socket);
        }
      } catch (SocketException e) {
        if (running) {
          Log.w(TAG, "accept failed", e);
        }
      } catch (IOException e) {
        if (running) {
          Log.w(TAG, "accept failed", e);
        }
      }
    }
  }

  // ------------------------------------------------------------------ request handling

  private void handle(Socket socket) {
    String peer = socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
    InputStream in = null;
    OutputStream out = null;
    try {
      socket.setSoTimeout(SO_TIMEOUT_MS);
      socket.setTcpNoDelay(true);
      in = socket.getInputStream();
      out = socket.getOutputStream();

      String requestLine = readLine(in);
      if (requestLine == null || requestLine.isEmpty()) {
        return;
      }

      if (!isHttpRequest(requestLine)) {
        // Raw TCP client: the first line is the payload.
        ingest(requestLine, peer);
        write(out, 200, "OK", "text/plain", ("OK " + peer + "\n").getBytes(StandardCharsets.UTF_8));
        return;
      }

      Map<String, String> headers = new HashMap<>();
      String headerLine;
      int contentLength = 0;
      while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
        int colon = headerLine.indexOf(':');
        if (colon <= 0) {
          continue;
        }
        String key = headerLine.substring(0, colon).trim().toLowerCase(Locale.US);
        String value = headerLine.substring(colon + 1).trim();
        headers.put(key, value);
        if ("content-length".equals(key)) {
          try {
            contentLength = Integer.parseInt(value);
          } catch (NumberFormatException ignored) {
            contentLength = 0;
          }
        }
      }

      String body = "";
      if (contentLength > 0) {
        body = new String(readFully(in, Math.min(contentLength, MAX_BODY_BYTES)),
            StandardCharsets.UTF_8);
      }

      String[] parts = requestLine.split(" ");
      String method = parts.length > 0 ? parts[0].toUpperCase(Locale.US) : "GET";
      String target = parts.length > 1 ? parts[1] : "/";
      String path = target;
      String query = "";
      int q = target.indexOf('?');
      if (q >= 0) {
        path = target.substring(0, q);
        query = target.substring(q + 1);
      }
      path = normalise(path);

      if ("OPTIONS".equals(method)) {
        write(out, 204, "No Content", "text/plain", new byte[0]);
        return;
      }

      if (path.equals("/") || path.equals("/index.html") || path.equals("/map")) {
        String page = (dashboardPage == null || dashboardPage.isEmpty()) ? dashboard()
            : dashboardPage;
        write(out, 200, "OK", "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.equals("/health") || path.equals("/ping")) {
        write(out, 200, "OK", "text/plain", "ok\n".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.equals("/api/devices")) {
        write(out, 200, "OK", "application/json",
            devicesJson(hasFlag(query, "trail")).getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.startsWith("/api/devices/")) {
        String id = path.substring("/api/devices/".length());
        write(out, 200, "OK", "application/json", deviceJson(id).getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.equals("/api/log")) {
        write(out, 200, "OK", "application/json", logJson().getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (TELEMETRY_PATHS.contains(path)) {
        String payload = body.trim().isEmpty() ? query : body;
        ingest(payload, peer);
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        write(out, 200, "OK", "application/json", response.toString().getBytes(StandardCharsets.UTF_8));
        return;
      }

      JSONObject response = new JSONObject();
      response.put("status", "error");
      response.put("message", "unknown endpoint " + path);
      store.reject("404 " + method + " " + path + " from " + peer, peer);
      write(out, 404, "Not Found", "application/json",
          response.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      Log.w(TAG, "request from " + peer + " failed", e);
      try {
        if (out != null) {
          String message = e.getMessage() == null ? e.toString() : e.getMessage();
          JSONObject response = new JSONObject();
          response.put("status", "error");
          response.put("message", message);
          write(out, 400, "Bad Request", "application/json",
              response.toString().getBytes(StandardCharsets.UTF_8));
        }
      } catch (Exception ignored) {
        // the client is gone anyway
      }
    } finally {
      closeQuietly(socket);
    }
  }

  private void ingest(String payload, String peer) {
    try {
      Telemetry telemetry = TelemetryParser.parse(payload);
      store.ingest(telemetry, peer);
    } catch (IllegalArgumentException e) {
      String message = e.getMessage() == null ? "bad payload" : e.getMessage();
      store.reject("bad payload from " + peer + ": " + message + " | body: "
          + truncate(payload, 200), peer);
      throw new IllegalArgumentException(message
          + " - send JSON like {\"device\":\"esp32-01\",\"lat\":6.52,\"lon\":3.37,"
          + "\"state\":4} (key=value, key:value and device,lat,lon,state also accepted)");
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    String singleLine = value.replace('\n', ' ').replace('\r', ' ').trim();
    return singleLine.length() <= max ? singleLine : singleLine.substring(0, max) + "…";
  }

  private static boolean isHttpRequest(String requestLine) {
    String upper = requestLine.toUpperCase(Locale.US);
    return upper.startsWith("GET ") || upper.startsWith("POST ") || upper.startsWith("PUT ")
        || upper.startsWith("OPTIONS ") || upper.startsWith("HEAD ") || upper.endsWith("HTTP/1.1")
        || upper.endsWith("HTTP/1.0");
  }

  /** True when a query string contains a flag set to 1/true/yes. */
  private static boolean hasFlag(String query, String name) {
    if (query == null || query.isEmpty()) {
      return false;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      if (!pair.substring(0, eq).equalsIgnoreCase(name)) {
        continue;
      }
      String value = pair.substring(eq + 1).trim().toLowerCase(Locale.US);
      if (value.equals("1") || value.equals("true") || value.equals("yes")) {
        return true;
      }
    }
    return false;
  }

  private static String normalise(String path) {
    String p = path.trim();
    if (p.isEmpty()) {
      return "/";
    }
    if (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p;
  }

  // ------------------------------------------------------------------ responses

  private static void write(OutputStream out, int code, String reason, String contentType,
      byte[] body) throws IOException {
    StringBuilder header = new StringBuilder();
    header.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
    header.append("Content-Type: ").append(contentType).append("\r\n");
    header.append("Content-Length: ").append(body.length).append("\r\n");
    header.append("Cache-Control: no-store\r\n");
    header.append("Access-Control-Allow-Origin: *\r\n");
    header.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
    header.append("Access-Control-Allow-Headers: Content-Type\r\n");
    header.append("Connection: close\r\n\r\n");
    out.write(header.toString().getBytes(StandardCharsets.UTF_8));
    if (body.length > 0) {
      out.write(body);
    }
    out.flush();
  }

  private String devicesJson(boolean withTrail) throws JSONException {
    List<TrackedDevice> devices = store.snapshot();
    JSONArray array = new JSONArray();
    for (TrackedDevice device : devices) {
      array.put(summary(device, withTrail));
    }
    JSONObject root = new JSONObject();
    root.put("devices", array);
    root.put("count", devices.size());
    root.put("now", System.currentTimeMillis());
    return root.toString();
  }

  private String deviceJson(String id) throws JSONException {
    TrackedDevice device = store.get(id);
    JSONObject root = new JSONObject();
    if (device == null) {
      root.put("status", "error");
      root.put("message", "unknown device " + id);
      return root.toString();
    }
    root.put("device", summary(device, true));
    return root.toString();
  }

  private JSONObject summary(TrackedDevice device, boolean withTrail) throws JSONException {
    JSONObject o = new JSONObject();
    o.put("id", device.id);
    o.put("name", device.displayName());
    o.put("state", device.state().wireName);
    o.put("stateCode", device.state().code);
    o.put("hasPosition", device.hasPosition());
    o.put("packets", device.packets);
    o.put("firstSeen", device.firstSeen);
    o.put("lastSeen", device.lastSeen);
    o.put("sourceIp", device.lastSourceIp);
    Telemetry t = device.last;
    if (t != null) {
      if (!Double.isNaN(t.latitude)) {
        o.put("latitude", t.latitude);
      }
      if (!Double.isNaN(t.longitude)) {
        o.put("longitude", t.longitude);
      }
      if (!Double.isNaN(t.speed)) {
        o.put("speed", t.speed);
      }
      if (!Double.isNaN(t.heading)) {
        o.put("heading", t.heading);
      }
      if (!Double.isNaN(t.altitude)) {
        o.put("altitude", t.altitude);
      }
      if (!Double.isNaN(t.accuracy)) {
        o.put("accuracy", t.accuracy);
      }
      if (t.satellites >= 0) {
        o.put("satellites", t.satellites);
      }
      if (!Double.isNaN(t.battery)) {
        o.put("battery", t.battery);
      }
      if (t.note != null && !t.note.isEmpty()) {
        o.put("note", t.note);
      }
    }
    if (withTrail) {
      JSONArray trail = new JSONArray();
      int from = Math.max(0, device.trail.size() - MAX_TRAIL_POINTS);
      for (int i = from; i < device.trail.size(); i++) {
        TrackedDevice.TrailPoint point = device.trail.get(i);
        JSONArray p = new JSONArray();
        p.put(point.latitude);
        p.put(point.longitude);
        p.put(point.timeMillis);
        p.put(point.stateCode);
        trail.put(p);
      }
      o.put("trail", trail);
    } else {
      o.put("trailPoints", device.trail.size());
    }
    return o;
  }

  private String logJson() throws JSONException {
    JSONArray array = new JSONArray();
    for (FleetStore.LogEntry entry : store.logs()) {
      JSONObject o = new JSONObject();
      o.put("time", entry.timeMillis);
      o.put("level", entry.level);
      o.put("message", entry.message);
      array.put(o);
    }
    JSONObject root = new JSONObject();
    root.put("log", array);
    return root.toString();
  }

  /** A minimal dashboard, so you can also watch the fleet from any browser on the same network. */
  private String dashboard() {
    List<TrackedDevice> devices = store.snapshot();
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'>")
        .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
        .append("<meta http-equiv='refresh' content='5'>")
        .append("<title>FleetServer</title><style>")
        .append("body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:24px;background:#0f1720;color:#e6edf3}")
        .append("h1{font-size:20px;margin:0 0 4px}small{color:#8ba0b5}")
        .append("table{border-collapse:collapse;width:100%;margin-top:18px}")
        .append("th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #22303d;font-size:14px}")
        .append("th{color:#8ba0b5;font-weight:600}.s{font-weight:600}")
        .append(".op{color:#22c55e}.nf{color:#f59e0b}.of{color:#60a5fa}.gn{color:#ef4444}.un{color:#94a3b8}")
        .append("code{background:#16222d;padding:2px 6px;border-radius:4px}")
        .append("</style></head><body>")
        .append("<h1>FleetServer</h1><small>Telemetry receiver &middot; refreshes every 5s</small>")
        .append("<p><small>POST <code>/telemetry</code> with <code>{\"device\":\"esp32-01\","
            + "\"lat\":6.52,\"lon\":3.37,\"state\":4}</code></small></p>")
        .append("<table><tr><th>Device</th><th>State</th><th>Latitude</th><th>Longitude</th>"
            + "<th>Speed</th><th>Sats</th><th>Packets</th><th>Last seen</th></tr>");
    if (devices.isEmpty()) {
      html.append("<tr><td colspan='8'>No devices yet. Send a packet from your ESP32.</td></tr>");
    }
    for (TrackedDevice device : devices) {
      String cls = stateClass(device.state());
      Telemetry t = device.last;
      html.append("<tr><td>").append(escape(device.displayName())).append("</td>")
          .append("<td class='s ").append(cls).append("'>").append(device.state().label).append("</td>")
          .append("<td>").append(device.hasPosition() ? String.format(Locale.US, "%.6f", device.latitude()) : "-").append("</td>")
          .append("<td>").append(device.hasPosition() ? String.format(Locale.US, "%.6f", device.longitude()) : "-").append("</td>")
          .append("<td>").append(t != null && !Double.isNaN(t.speed) ? String.format(Locale.US, "%.1f m/s", t.speed) : "-").append("</td>")
          .append("<td>").append(t != null && t.satellites >= 0 ? t.satellites : "-").append("</td>")
          .append("<td>").append(device.packets).append("</td>")
          .append("<td>").append(ago(System.currentTimeMillis() - device.lastSeen)).append("</td></tr>");
    }
    html.append("</table></body></html>");
    return html.toString();
  }

  private static String stateClass(damjay.tracker.fleetserver.model.DeviceState state) {
    switch (state) {
      case OPERATIONAL:
        return "op";
      case NO_FIX:
        return "nf";
      case OFFLINE:
        return "of";
      case GPS_NOT_FOUND:
        return "gn";
      default:
        return "un";
    }
  }

  private static String ago(long millis) {
    long seconds = Math.max(0L, millis / 1000L);
    if (seconds < 60) {
      return seconds + "s ago";
    }
    if (seconds < 3600) {
      return (seconds / 60) + "m ago";
    }
    return (seconds / 3600) + "h ago";
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  // ------------------------------------------------------------------ socket helpers

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int value;
    while ((value = in.read()) != -1) {
      if (value == '\n') {
        break;
      }
      if (value != '\r') {
        buffer.write(value);
      }
      if (buffer.size() > 8192) {
        break;
      }
    }
    if (value == -1 && buffer.size() == 0) {
      return null;
    }
    return buffer.toString(StandardCharsets.UTF_8.name());
  }

  private static byte[] readFully(InputStream in, int length) throws IOException {
    byte[] data = new byte[length];
    int offset = 0;
    while (offset < length) {
      int read = in.read(data, offset, length - offset);
      if (read < 0) {
        break;
      }
      offset += read;
    }
    return offset == length ? data : Arrays.copyOf(data, offset);
  }

  private static void closeQuietly(Socket socket) {
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // ignore
      }
    }
  }

  private static void closeQuietly(ServerSocket socket) {
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // ignore
      }
    }
  }

  /** Used by the "connection info" dialog: the URLs a tracker can post to. */
  public static List<String> telemetryUrls(List<String> addresses, int port) {
    List<String> urls = new ArrayList<>();
    for (String address : addresses) {
      urls.add("http://" + address + ":" + port + "/telemetry");
    }
    return urls;
  }
}
