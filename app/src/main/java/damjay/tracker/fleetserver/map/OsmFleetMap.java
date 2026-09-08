package damjay.tracker.fleetserver.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import damjay.tracker.fleetserver.R;
import damjay.tracker.fleetserver.model.DeviceState;
import damjay.tracker.fleetserver.model.Geo;
import damjay.tracker.fleetserver.model.TrackedDevice;

/** The OpenStreetMap backed map (needs internet for tiles, no API key). */
public class OsmFleetMap implements FleetMap {

  private final Context context;
  private final MapView map;
  private final Map<String, Marker> markers = new HashMap<>();
  private final Map<String, Polyline> trails = new HashMap<>();
  private final Map<Integer, Bitmap> pinCache = new HashMap<>();
  private TapListener tapListener;
  private boolean showTrail = true;
  private final List<TrackedDevice> devices = new ArrayList<>();

  public OsmFleetMap(@NonNull Context context, @NonNull MapView map) {
    this.context = context.getApplicationContext();
    this.map = map;
    map.setTileSource(TileSourceFactory.MAPNIK);
    map.setMultiTouchControls(true);
    map.setTilesScaledToDpi(true);
    map.setMinZoomLevel(2.0d);
    map.setMaxZoomLevel(19.5d);
    map.getController().setZoom(4.0d);
    map.getController().setCenter(new GeoPoint(6.5244d, 3.3792d));
  }

  @Override
  public View view() {
    return map;
  }

  @Override
  public void setTapListener(TapListener listener) {
    tapListener = listener;
  }

  @Override
  public void setShowTrail(boolean showTrail) {
    this.showTrail = showTrail;
    for (Polyline polyline : trails.values()) {
      polyline.setVisible(showTrail);
    }
    map.invalidate();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void setDevices(List<TrackedDevice> newDevices) {
    devices.clear();
    devices.addAll(newDevices);
    Set<String> seen = new HashSet<>();
    for (final TrackedDevice device : devices) {
      if (!device.hasPosition()) {
        continue;
      }
      seen.add(device.id);
      Marker marker = markers.get(device.id);
      if (marker == null) {
        marker = new Marker(map);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setInfoWindow(null);
        marker.setPanToView(false);
        marker.setOnMarkerClickListener(new Marker.OnMarkerClickListener() {
          @Override
          public boolean onMarkerClick(Marker clicked, MapView mapView) {
            if (tapListener != null) {
              tapListener.onDeviceTapped(device.id);
            }
            return true;
          }
        });
        markers.put(device.id, marker);
        map.getOverlays().add(marker);
      }
      marker.setPosition(new GeoPoint(device.latitude(), device.longitude()));
      marker.setTitle(device.displayName());
      marker.setSubDescription(stateLabel(device.state()));
      marker.setIcon(pinFor(device.state(), false));
    }

    // remove markers for devices that are gone
    List<Marker> orphans = new ArrayList<>();
    for (Map.Entry<String, Marker> entry : markers.entrySet()) {
      if (!seen.contains(entry.getKey())) {
        orphans.add(entry.getValue());
      }
    }
    for (Marker orphan : orphans) {
      map.getOverlays().remove(orphan);
    }
    for (String id : new ArrayList<>(markers.keySet())) {
      if (!seen.contains(id)) {
        markers.remove(id);
      }
    }

    updateTrails();
    map.invalidate();
  }

  private void updateTrails() {
    // drop trails of devices that disappeared
    List<String> stale = new ArrayList<>();
    for (Map.Entry<String, Polyline> entry : trails.entrySet()) {
      boolean present = false;
      for (TrackedDevice device : devices) {
        if (device.id.equals(entry.getKey())) {
          present = true;
          break;
        }
      }
      if (!present) {
        stale.add(entry.getKey());
      }
    }
    for (String id : stale) {
      Polyline polyline = trails.remove(id);
      if (polyline != null) {
        map.getOverlays().remove(polyline);
      }
    }

    for (TrackedDevice device : devices) {
      if (device.trail.size() < 2) {
        Polyline existing = trails.remove(device.id);
        if (existing != null) {
          map.getOverlays().remove(existing);
        }
        continue;
      }
      List<GeoPoint> points = new ArrayList<>();
      for (TrackedDevice.TrailPoint point : device.trail) {
        points.add(new GeoPoint(point.latitude, point.longitude));
      }
      Polyline polyline = trails.get(device.id);
      if (polyline == null) {
        polyline = new Polyline(map);
        polyline.setGeodesic(true);
        polyline.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        polyline.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        polyline.setWidth(6f);
        trails.put(device.id, polyline);
        map.getOverlays().add(polyline);
      }
      polyline.setPoints(points);
      polyline.setColor(stateColor(device.state()));
      polyline.getOutlinePaint().setAlpha(170);
      polyline.setVisible(showTrail);
    }
  }

  @Override
  public void focus(TrackedDevice device) {
    if (device == null || !device.hasPosition()) {
      return;
    }
    map.getController().animateTo(new GeoPoint(device.latitude(), device.longitude()), 15.5d, 600L);
  }

  @Override
  public void fitAll() {
    List<TrackedDevice> withPosition = new ArrayList<>();
    for (TrackedDevice device : devices) {
      if (device.hasPosition()) {
        withPosition.add(device);
      }
    }
    if (withPosition.isEmpty()) {
      return;
    }
    double minLat = 90d;
    double maxLat = -90d;
    double minLon = 180d;
    double maxLon = -180d;
    for (TrackedDevice device : withPosition) {
      minLat = Math.min(minLat, device.latitude());
      maxLat = Math.max(maxLat, device.latitude());
      minLon = Math.min(minLon, device.longitude());
      maxLon = Math.max(maxLon, device.longitude());
    }
    int width = map.getWidth() > 0 ? map.getWidth() : 720;
    int height = map.getHeight() > 0 ? map.getHeight() : 720;
    double zoom = Math.max(2.0d, Math.min(18.0d,
        Geo.zoomForSpan(minLat, maxLat, Math.max(0.002d, maxLon - minLon), width, height)));
    map.getController().setZoom(zoom);
    map.getController().setCenter(new GeoPoint((minLat + maxLat) / 2d, (minLon + maxLon) / 2d));
  }

  @Override
  public void onResume() {
    map.onResume();
  }

  @Override
  public void onPause() {
    map.onPause();
  }

  private String stateLabel(DeviceState state) {
    return state.label;
  }

  private int stateColor(DeviceState state) {
    return ContextCompat.getColor(context, state.colorRes);
  }

  private Drawable pinFor(DeviceState state, boolean faded) {
    int color = stateColor(state);
    Bitmap bitmap = pinCache.get(color);
    if (bitmap == null) {
      bitmap = PinPainter.createPinBitmap(context, color, false);
      pinCache.put(color, bitmap);
    }
    android.graphics.drawable.BitmapDrawable drawable =
        new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
    if (faded) {
      drawable.setAlpha(140);
    }
    return drawable;
  }
}
