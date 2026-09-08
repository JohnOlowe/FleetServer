package damjay.tracker.fleetserver.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import damjay.tracker.fleetserver.R;
import damjay.tracker.fleetserver.databinding.ActivitySettingsBinding;
import damjay.tracker.fleetserver.server.FleetServerService;
import damjay.tracker.fleetserver.store.FleetStore;
import damjay.tracker.fleetserver.util.NetInfo;
import damjay.tracker.fleetserver.util.Prefs;

/** Port, auto-start, map source and network information. */
public class SettingsActivity extends AppCompatActivity {

  private ActivitySettingsBinding binding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivitySettingsBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    binding.toolbar.setNavigationOnClickListener(v -> finish());

    binding.portInput.setText(String.valueOf(Prefs.port(this)));
    binding.switchAutostart.setChecked(Prefs.autoStart(this));
    binding.switchFollow.setChecked(Prefs.followDevice(this));
    binding.switchTrail.setChecked(Prefs.showTrail(this));

    if (Prefs.MAP_MODE_OFFLINE.equals(Prefs.mapMode(this))) {
      binding.mapModeGroup.check(R.id.button_mode_offline);
    } else {
      binding.mapModeGroup.check(R.id.button_mode_osm);
    }

    binding.switchAutostart.setOnCheckedChangeListener((button, checked) ->
        Prefs.get(this).edit().putBoolean(Prefs.KEY_AUTO_START, checked).apply());
    binding.switchFollow.setOnCheckedChangeListener((button, checked) ->
        Prefs.get(this).edit().putBoolean(Prefs.KEY_FOLLOW, checked).apply());
    binding.switchTrail.setOnCheckedChangeListener((button, checked) ->
        Prefs.get(this).edit().putBoolean(Prefs.KEY_SHOW_TRAIL, checked).apply());

    binding.mapModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
      if (!isChecked) {
        return;
      }
      Prefs.setMapMode(this, checkedId == R.id.button_mode_offline
          ? Prefs.MAP_MODE_OFFLINE : Prefs.MAP_MODE_OSM);
    });

    binding.buttonRestart.setOnClickListener(v -> restartServer());
    updateNetworkInfo();
  }

  private void restartServer() {
    int port = parsePort();
    if (port < 0) {
      binding.portLayout.setError(getString(R.string.settings_port_hint));
      return;
    }
    binding.portLayout.setError(null);
    Prefs.get(this).edit().putInt(Prefs.KEY_PORT, port).apply();
    FleetServerService.stop(this);
    FleetServerService.start(this);
    Toast.makeText(this, R.string.settings_restart, Toast.LENGTH_SHORT).show();
    updateNetworkInfo();
  }

  private int parsePort() {
    String text = binding.portInput.getText() == null ? "" : binding.portInput.getText().toString();
    try {
      int port = Integer.parseInt(text.trim());
      if (port < 1024 || port > 65535) {
        return -1;
      }
      return port;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private void updateNetworkInfo() {
    StringBuilder builder = new StringBuilder();
    builder.append(getString(R.string.connection_transport, NetInfo.transportSummary(this)))
        .append("\n");
    FleetStore store = FleetStore.get(this);
    if (store.isServerRunning()) {
      for (String endpoint : store.getEndpoints()) {
        builder.append(endpoint).append("/telemetry\n");
      }
    } else {
      for (NetInfo.Address address : NetInfo.localAddresses()) {
        builder.append(address.url(Prefs.port(this))).append("/telemetry\n");
      }
    }
    builder.append("\n").append(getString(R.string.connection_hint));
    binding.networkInfo.setText(builder.toString());
  }
}
