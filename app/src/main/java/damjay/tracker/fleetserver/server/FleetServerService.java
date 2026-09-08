package damjay.tracker.fleetserver.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import damjay.tracker.fleetserver.R;
import damjay.tracker.fleetserver.store.FleetStore;
import damjay.tracker.fleetserver.ui.MainActivity;
import damjay.tracker.fleetserver.util.NetInfo;
import damjay.tracker.fleetserver.util.Prefs;

/**
 * Keeps the telemetry server alive while the app is in the background.
 *
 * <p>Runs as a foreground service (type "connectedDevice"), holds a Wi-Fi lock so the radio does
 * not sleep between packets, and publishes the current endpoint list to {@link FleetStore}.
 */
public class FleetServerService extends Service {

  private static final String TAG = "FleetServerService";
  private static final String CHANNEL_ID = "fleet_server";
  private static final int NOTIFICATION_ID = 1001;

  public static final String ACTION_START = "damjay.tracker.fleetserver.action.START";
  public static final String ACTION_STOP = "damjay.tracker.fleetserver.action.STOP";

  private FleetStore store;
  private FleetHttpServer server;
  private WifiManager.WifiLock wifiLock;
  private int currentPort = -1;

  public static void start(Context context) {
    Intent intent = new Intent(context, FleetServerService.class);
    intent.setAction(ACTION_START);
    Context context1 = context.getApplicationContext();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context1.startForegroundService(intent);
    } else {
      context1.startService(intent);
    }
  }

  public static void stop(Context context) {
    Intent intent = new Intent(context, FleetServerService.class);
    intent.setAction(ACTION_STOP);
    context.getApplicationContext().startService(intent);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    store = FleetStore.get(this);
    createChannel();
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      store.log("sys", "server stopped");
      stopSelf();
      return START_NOT_STICKY;
    }
    int port = Prefs.port(this);
    if (server != null && server.isRunning() && port == currentPort) {
      publishState(port, null);
      return START_STICKY;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
    startServer(port);
    return START_STICKY;
  }

  private void startServer(int port) {
    FleetHttpServer created = new FleetHttpServer(port, store);
    try {
      created.start();
    } catch (IOException e) {
      Log.w(TAG, "could not bind port " + port, e);
      String message = "Port " + port + " is busy - try another one in Settings";
      store.setServerState(false, port, message, new ArrayList<String>());
      store.log("err", message);
      stopSelf();
      return;
    }
    server = created;
    currentPort = port;
    acquireWifiLock();
    publishState(port, null);
    store.log("sys", "listening on port " + port);
    startForeground(NOTIFICATION_ID, buildNotification(port));
  }

  private void publishState(int port, String error) {
    List<String> urls = new ArrayList<>();
    for (NetInfo.Address address : NetInfo.localAddresses()) {
      urls.add(address.url(port));
    }
    store.setServerState(true, port, error, FleetStore.sortEndpoints(urls));
  }

  private void acquireWifiLock() {
    if (wifiLock != null) {
      return;
    }
    try {
      WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      if (manager != null) {
        wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FleetServer::wifi");
        wifiLock.acquire();
      }
    } catch (Exception e) {
      Log.w(TAG, "could not acquire wifi lock", e);
    }
  }

  private void createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Fleet server",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Keeps the telemetry receiver running");
      NotificationManager manager = getSystemService(NotificationManager.class);
      if (manager != null) {
        manager.createNotificationChannel(channel);
      }
    }
  }

  private Notification buildNotification(int port) {
    NetInfo.Address address = NetInfo.preferredAddress(this);
    String endpoint = address == null ? "port " + port : address.url(port);
    Intent contentIntent = new Intent(this, MainActivity.class);
    contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pendingIntent = PendingIntent.getActivity(this, 2, contentIntent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

    Intent stopIntent = new Intent(this, FleetServerService.class);
    stopIntent.setAction(ACTION_STOP);
    PendingIntent stopPending = PendingIntent.getService(this, 3, stopIntent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_server)
        .setContentTitle("FleetServer is listening")
        .setContentText(endpoint + "  •  POST /telemetry")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(pendingIntent)
        .addAction(R.drawable.ic_stat_stop, "Stop", stopPending)
        .build();
  }

  @Override
  public void onDestroy() {
    if (server != null) {
      server.stop();
      server = null;
    }
    if (wifiLock != null && wifiLock.isHeld()) {
      wifiLock.release();
      wifiLock = null;
    }
    store.setServerState(false, currentPort, "", new ArrayList<String>());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } else {
      stopForeground(true);
    }
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
