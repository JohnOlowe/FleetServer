package damjay.tracker.fleetserver.map;

import android.view.View;

import java.util.List;

import damjay.tracker.fleetserver.model.TrackedDevice;

/** Common surface for the two map engines (online tiles and offline schematic). */
public interface FleetMap {

  /** Called when the user taps a tracker on the map. */
  interface TapListener {
    void onDeviceTapped(String deviceId);
  }

  View view();

  void setDevices(List<TrackedDevice> devices);

  void setShowTrail(boolean showTrail);

  /** Centres the map on one tracker. */
  void focus(TrackedDevice device);

  /** Centres and zooms so every tracker is visible. */
  void fitAll();

  void setTapListener(TapListener listener);

  void onResume();

  void onPause();
}
