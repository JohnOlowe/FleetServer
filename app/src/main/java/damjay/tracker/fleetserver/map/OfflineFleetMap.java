package damjay.tracker.fleetserver.map;

import android.view.View;

import androidx.annotation.NonNull;

import java.util.List;

import damjay.tracker.fleetserver.model.TrackedDevice;

/** Adapter that lets the offline schematic map stand in for the tile based map. */
public class OfflineFleetMap implements FleetMap {

  private final OfflineMapView view;

  public OfflineFleetMap(@NonNull OfflineMapView view) {
    this.view = view;
  }

  @Override
  public View view() {
    return view;
  }

  @Override
  public void setDevices(List<TrackedDevice> devices) {
    view.setDevices(devices);
  }

  @Override
  public void setShowTrail(boolean showTrail) {
    view.setShowTrail(showTrail);
  }

  @Override
  public void focus(TrackedDevice device) {
    view.focus(device);
  }

  @Override
  public void fitAll() {
    view.fitAll();
  }

  @Override
  public void setTapListener(final TapListener listener) {
    view.setTapListener(listener == null ? null : new OfflineMapView.TapListener() {
      @Override
      public void onDeviceTapped(String deviceId) {
        listener.onDeviceTapped(deviceId);
      }
    });
  }

  @Override
  public void onResume() {
    // nothing to do
  }

  @Override
  public void onPause() {
    // nothing to do
  }
}
