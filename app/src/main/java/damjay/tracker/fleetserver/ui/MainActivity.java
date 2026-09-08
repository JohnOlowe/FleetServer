package damjay.tracker.fleetserver.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import damjay.tracker.fleetserver.R;
import damjay.tracker.fleetserver.databinding.ActivityMainBinding;
import damjay.tracker.fleetserver.map.FleetMap;
import damjay.tracker.fleetserver.map.OfflineFleetMap;
import damjay.tracker.fleetserver.map.OsmFleetMap;
import damjay.tracker.fleetserver.model.DeviceState;
import damjay.tracker.fleetserver.model.Geo;
import damjay.tracker.fleetserver.model.TrackedDevice;
import damjay.tracker.fleetserver.server.FleetServerService;
import damjay.tracker.fleetserver.store.FleetStore;
import damjay.tracker.fleetserver.util.NetInfo;
import damjay.tracker.fleetserver.util.Prefs;

/**
 * The single screen of the app: a map of the fleet, the list of trackers, and the controls for the
 * embedded telemetry server.
 */
public class MainActivity extends AppCompatActivity
    implements FleetStore.Listener, DeviceDetailSheet.Actions {

  private static final int REQUEST_NOTIFICATIONS = 101;
  private static final long REFRESH_INTERVAL_MS = 250L;

  /** Simulated packets walk around Ibadan so the demo shows movement. */
  private static final double SIM_LAT = 7.3775d;
  private static final double SIM_LON = 3.9470d;

  private ActivityMainBinding binding;
  private FleetStore store;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private DeviceAdapter adapter;

  private FleetMap osmMap;
  private FleetMap offlineMap;
  private FleetMap activeMap;

  private String focusedId;
  private double focusedLat = Double.NaN;
  private double focusedLon = Double.NaN;
  private boolean follow;
  private boolean refreshPending;
  private int simulateCounter;
  private ConnectivityManager.NetworkCallback networkCallback;

  // ------------------------------------------------------------------ lifecycle

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    store = FleetStore.get(this);
    follow = Prefs.followDevice(this);

    adapter = new DeviceAdapter(new DeviceAdapter.Callback() {
      @Override
      public void onDeviceClicked(TrackedDevice device) {
        focusDevice(device.id);
        showDetail(device.id);
      }
    });
    binding.devices.setLayoutManager(new LinearLayoutManager(this));
    binding.devices.setAdapter(adapter);

    osmMap = new OsmFleetMap(this, binding.osmMap);
    offlineMap = new OfflineFleetMap(binding.offlineMap);
    FleetMap.TapListener tapListener = new FleetMap.TapListener() {
      @Override
      public void onDeviceTapped(String deviceId) {
        focusDevice(deviceId);
        showDetail(deviceId);
      }
    };
    osmMap.setTapListener(tapListener);
    offlineMap.setTapListener(tapListener);

    binding.toolbar.setOnMenuItemClickListener(item -> onMenuItem(item));
    binding.chipStatus.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        showConnectionInfo();
      }
    });
    binding.chipMapMode.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        toggleMapMode();
      }
    });
    binding.chipFit.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        focusedId = null;
        activeMap.fitAll();
      }
    });
    binding.chipFollow.setChecked(follow);
    binding.chipFollow.setOnCheckedChangeListener((button, checked) -> {
      follow = checked;
      Prefs.get(MainActivity.this).edit().putBoolean(Prefs.KEY_FOLLOW, checked).apply();
      if (checked && focusedId != null) {
        focusDevice(focusedId);
      }
    });
    binding.offlineHint.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        setMapMode(Prefs.MAP_MODE_OFFLINE);
      }
    });
    binding.buttonClearAll.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        confirmClear();
      }
    });

    applyMapMode();
    requestNotificationPermission();
    if (Prefs.autoStart(this)) {
      FleetServerService.start(this);
    }
    refreshUi();
  }

  @Override
  protected void onStart() {
    super.onStart();
    store.addListener(this);
    activeMap.onResume();
    registerNetworkCallback();
    refreshUi();
  }

  @Override
  protected void onStop() {
    unregisterNetworkCallback();
    activeMap.onPause();
    store.removeListener(this);
    handler.removeCallbacksAndMessages(null);
    refreshPending = false;
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    binding = null;
  }

  // ------------------------------------------------------------------ store callbacks

  @Override
  public void onFleetChanged() {
    scheduleRefresh();
  }

  @Override
  public void onLogChanged() {
    // the log lives in its own screen
  }

  private void scheduleRefresh() {
    if (refreshPending) {
      return;
    }
    refreshPending = true;
    handler.postDelayed(new Runnable() {
      @Override
      public void run() {
        refreshPending = false;
        refreshUi();
      }
    }, REFRESH_INTERVAL_MS);
  }

  // ------------------------------------------------------------------ UI

  private void refreshUi() {
    if (binding == null) {
      return;
    }
    List<TrackedDevice> devices = store.snapshot();
    boolean running = store.isServerRunning();
    String error = store.getServerError();

    String subtitle;
    if (!error.isEmpty()) {
      subtitle = error;
    } else if (running) {
      subtitle = getString(R.string.server_listening, store.getServerPort()) + "  ·  "
          + getString(R.string.packets_received, store.totalPackets());
    } else {
      subtitle = getString(R.string.server_stopped);
    }
    binding.toolbar.setSubtitle(subtitle);

    MenuItem toggle = binding.toolbar.getMenu().findItem(R.id.action_toggle);
    if (toggle != null) {
      toggle.setTitle(running ? R.string.action_stop : R.string.action_start);
      toggle.setIcon(running ? R.drawable.ic_stop : R.drawable.ic_play_stop);
    }

    binding.chipStatus.setText(running
        ? getString(R.string.chip_listening, store.getServerPort())
        : getString(R.string.chip_stopped));
    binding.devicesSubtitle.setText(getString(R.string.devices_count, devices.size()));
    binding.emptyDevices.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
    binding.emptyState.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);

    adapter.submit(devices);
    activeMap.setDevices(devices);
    updateFollow(devices);
    updateConnectivityHint();
  }

  private void updateFollow(List<TrackedDevice> devices) {
    if (!follow) {
      return;
    }
    if (focusedId == null && devices.size() == 1) {
      focusedId = devices.get(0).id;
    }
    if (focusedId == null) {
      return;
    }
    TrackedDevice device = store.get(focusedId);
    if (device == null || !device.hasPosition()) {
      return;
    }
    boolean moved = Double.isNaN(focusedLat) || Double.isNaN(focusedLon)
        || Geo.distanceMeters(focusedLat, focusedLon, device.latitude(), device.longitude()) > 0.5d;
    if (moved) {
      focusedLat = device.latitude();
      focusedLon = device.longitude();
      activeMap.focus(device);
    }
  }

  private void updateConnectivityHint() {
    boolean osmMode = Prefs.MAP_MODE_OSM.equals(Prefs.mapMode(this));
    binding.offlineHint.setVisibility(!osmMode || hasInternet() ? View.GONE : View.VISIBLE);
  }

  private boolean hasInternet() {
    try {
      ConnectivityManager manager =
          (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
      if (manager == null) {
        return false;
      }
      Network network = manager.getActiveNetwork();
      if (network == null) {
        return false;
      }
      NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
      return capabilities != null
          && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    } catch (Exception e) {
      return false;
    }
  }

  private void registerNetworkCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return;
    }
    ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    if (manager == null) {
      return;
    }
    networkCallback = new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(@NonNull Network network) {
        handler.post(new Runnable() {
          @Override
          public void run() {
            onConnectivityChanged();
          }
        });
      }

      @Override
      public void onLost(@NonNull Network network) {
        handler.post(new Runnable() {
          @Override
          public void run() {
            onConnectivityChanged();
          }
        });
      }

      @Override
      public void onCapabilitiesChanged(@NonNull Network network,
          @NonNull NetworkCapabilities capabilities) {
        handler.post(new Runnable() {
          @Override
          public void run() {
            onConnectivityChanged();
          }
        });
      }
    };
    try {
      manager.registerDefaultNetworkCallback(networkCallback);
    } catch (Exception e) {
      networkCallback = null;
    }
  }

  private void unregisterNetworkCallback() {
    if (networkCallback == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return;
    }
    try {
      ConnectivityManager manager =
          (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
      if (manager != null) {
        manager.unregisterNetworkCallback(networkCallback);
      }
    } catch (Exception ignored) {
      // no-op
    }
    networkCallback = null;
  }

  private void onConnectivityChanged() {
    updateConnectivityHint();
    if (store.isServerRunning()) {
      // ask the service to re-publish the endpoint list for the new network
      startService(new Intent(this, FleetServerService.class)
          .setAction(FleetServerService.ACTION_START));
    }
  }

  // ------------------------------------------------------------------ map modes

  private void applyMapMode() {
    boolean osm = Prefs.MAP_MODE_OSM.equals(Prefs.mapMode(this));
    binding.osmMap.setVisibility(osm ? View.VISIBLE : View.GONE);
    binding.offlineMap.setVisibility(osm ? View.GONE : View.VISIBLE);
    activeMap = osm ? osmMap : offlineMap;
    binding.chipMapMode.setText(osm ? R.string.chip_map_mode_osm : R.string.chip_map_mode_offline);
    binding.attribution.setVisibility(osm ? View.VISIBLE : View.GONE);
    osmMap.setShowTrail(Prefs.showTrail(this));
    offlineMap.setShowTrail(Prefs.showTrail(this));
  }

  private void toggleMapMode() {
    setMapMode(Prefs.MAP_MODE_OSM.equals(Prefs.mapMode(this))
        ? Prefs.MAP_MODE_OFFLINE : Prefs.MAP_MODE_OSM);
  }

  private void setMapMode(String mode) {
    Prefs.setMapMode(this, mode);
    applyMapMode();
    refreshUi();
    Toast.makeText(this, Prefs.MAP_MODE_OSM.equals(mode)
        ? R.string.chip_map_mode_osm : R.string.chip_map_mode_offline, Toast.LENGTH_SHORT).show();
  }

  @Override
  public void onFocusDevice(String deviceId) {
    focusDevice(deviceId);
  }

  private void focusDevice(String deviceId) {
    focusedId = deviceId;
    focusedLat = Double.NaN;
    focusedLon = Double.NaN;
    TrackedDevice device = store.get(deviceId);
    if (device != null && device.hasPosition()) {
      focusedLat = device.latitude();
      focusedLon = device.longitude();
      activeMap.focus(device);
    }
  }

  private void showDetail(String deviceId) {
    DeviceDetailSheet.newInstance(deviceId).show(getSupportFragmentManager(), "device");
  }

  // ------------------------------------------------------------------ menu actions

  private boolean onMenuItem(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_toggle) {
      if (store.isServerRunning()) {
        FleetServerService.stop(this);
      } else {
        FleetServerService.start(this);
      }
      return true;
    }
    if (id == R.id.action_connection) {
      showConnectionInfo();
      return true;
    }
    if (id == R.id.action_traffic) {
      startActivity(new Intent(this, TrafficActivity.class));
      return true;
    }
    if (id == R.id.action_settings) {
      startActivity(new Intent(this, SettingsActivity.class));
      return true;
    }
    if (id == R.id.action_simulate) {
      simulate();
      return true;
    }
    if (id == R.id.action_clear) {
      confirmClear();
      return true;
    }
    if (id == R.id.action_about) {
      showAbout();
      return true;
    }
    return false;
  }

  private void simulate() {
    DeviceState[] states = {DeviceState.OPERATIONAL, DeviceState.OPERATIONAL,
        DeviceState.OPERATIONAL, DeviceState.NO_FIX, DeviceState.OFFLINE,
        DeviceState.GPS_NOT_FOUND};
    DeviceState state = states[simulateCounter % states.length];
    simulateCounter++;

    TrackedDevice previous = store.get("esp32-sim");
    double lat = SIM_LAT;
    double lon = SIM_LON;
    if (previous != null && previous.hasPosition()) {
      lat = previous.latitude() + (Math.random() - 0.5d) * 0.0015d;
      lon = previous.longitude() + (Math.random() - 0.5d) * 0.0015d;
    }
    if (state == DeviceState.GPS_NOT_FOUND || state == DeviceState.NO_FIX) {
      lat = 0d;
      lon = 0d;
    }
    store.ingestSimulated(lat, lon, state, "esp32-sim");
    focusDevice("esp32-sim");
  }

  private void confirmClear() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.action_clear)
        .setMessage(R.string.no_devices)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
          focusedId = null;
          store.clearDevices();
        })
        .show();
  }

  private void showConnectionInfo() {
    StringBuilder message = new StringBuilder();
    List<NetInfo.Address> addresses = NetInfo.localAddresses();
    message.append(getString(R.string.connection_transport, NetInfo.transportSummary(this)))
        .append("\n\n");
    message.append(getString(R.string.connection_endpoints)).append('\n');
    int port = store.isServerRunning() ? store.getServerPort() : Prefs.port(this);
    if (addresses.isEmpty()) {
      message.append("  (no network - connect to Wi-Fi or switch on the hotspot)\n");
    }
    for (NetInfo.Address address : addresses) {
      message.append("  ").append(address.url(port)).append("/telemetry")
          .append("   [").append(address.interfaceName).append("]\n");
    }
    message.append('\n').append(getString(R.string.connection_how)).append('\n')
        .append("  POST /telemetry\n")
        .append("  {\"device\":\"esp32-01\",\"lat\":6.5244,\"lon\":3.3792,\"state\":4}\n\n")
        .append("  GET  /telemetry?lat=6.5244&lon=3.3792&state=4\n")
        .append("  TCP  esp32-01,6.5244,3.3792,4\n\n")
        .append(getString(R.string.connection_curl)).append('\n')
        .append("  curl -X POST ").append(addresses.isEmpty() ? "http://<phone-ip>:" + port
            : addresses.get(0).url(port))
        .append("/telemetry -d '{\"device\":\"esp32-01\",\"lat\":6.52,\"lon\":3.37,\"state\":4}'\n\n")
        .append(getString(R.string.connection_hint));

    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.connection_title)
        .setMessage(message.toString())
        .setPositiveButton(R.string.button_close, null)
        .show();
  }

  private void showAbout() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.about_title)
        .setMessage(R.string.about_body)
        .setPositiveButton(R.string.button_close, null)
        .show();
  }

  // ------------------------------------------------------------------ permissions

  private void requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return;
    }
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED) {
      return;
    }
    ActivityCompat.requestPermissions(this,
        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
      @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_NOTIFICATIONS && grantResults.length > 0
        && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
      Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
    }
  }
}
