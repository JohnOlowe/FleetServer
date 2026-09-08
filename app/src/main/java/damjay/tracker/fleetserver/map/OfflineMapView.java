package damjay.tracker.fleetserver.map;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import damjay.tracker.fleetserver.R;
import damjay.tracker.fleetserver.model.DeviceState;
import damjay.tracker.fleetserver.model.Geo;
import damjay.tracker.fleetserver.model.TrackedDevice;

/**
 * A dependency-free, tile-free map.
 *
 * <p>It draws a latitude/longitude graticule, the trail of every tracker and a pin for the latest
 * fix, so the app still shows where the fleet is when the phone has no internet (for example when
 * the ESP32 is joined to the phone's own hotspot).
 */
public class OfflineMapView extends View {

  public interface TapListener {
    void onDeviceTapped(String deviceId);
  }

  private static final double[] STEPS = {45d, 30d, 15d, 10d, 5d, 2d, 1d, 0.5d, 0.2d, 0.1d, 0.05d,
      0.02d, 0.01d, 0.005d, 0.002d, 0.001d, 0.0005d, 0.0002d, 0.0001d};
  private static final double MIN_ZOOM = 2d;
  private static final double MAX_ZOOM = 19d;

  private double centerLat = 6.5244d;
  private double centerLon = 3.3792d;
  private double zoom = 13d;

  private final List<TrackedDevice> devices = new ArrayList<>();
  private final Map<String, Integer> colors = new HashMap<>();
  private boolean showTrail = true;
  @Nullable
  private TapListener tapListener;

  private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint gridMinorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint scalePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint scaleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final ScaleGestureDetector scaleDetector;
  private final GestureDetector gestureDetector;
  private boolean moved;

  public OfflineMapView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    ContextCompat.getColor(context, R.color.map_grid);
    gridPaint.setColor(ContextCompat.getColor(context, R.color.map_grid));
    gridPaint.setStrokeWidth(1.2f);
    gridMinorPaint.setColor(ContextCompat.getColor(context, R.color.map_grid_minor));
    gridMinorPaint.setStrokeWidth(1f);
    labelPaint.setColor(ContextCompat.getColor(context, R.color.map_label));
    labelPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
    labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    trailPaint.setStyle(Paint.Style.STROKE);
    trailPaint.setStrokeWidth(2.4f * getResources().getDisplayMetrics().density);
    trailPaint.setStrokeCap(Paint.Cap.ROUND);
    trailPaint.setStrokeJoin(Paint.Join.ROUND);
    dotPaint.setStyle(Paint.Style.FILL);
    scalePaint.setColor(ContextCompat.getColor(context, R.color.map_label));
    scalePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
    scaleTextPaint.setColor(ContextCompat.getColor(context, R.color.map_label));
    scaleTextPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
    scaleTextPaint.setTextAlign(Paint.Align.LEFT);
    emptyPaint.setColor(ContextCompat.getColor(context, R.color.map_label));
    emptyPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);
    emptyPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    haloPaint.setColor(ContextCompat.getColor(context, R.color.map_halo));
    haloPaint.setStyle(Paint.Style.FILL);

    scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override
      public boolean onScale(ScaleGestureDetector detector) {
        zoomAt(detector.getFocusX(), detector.getFocusY(),
            Math.log(detector.getScaleFactor()) / Math.log(2.0d));
        return true;
      }
    });
    gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (getWidth() == 0 || getHeight() == 0) {
          return true;
        }
        centerLon = lonOf(getWidth() / 2f + distanceX);
        centerLat = clampLat(latOf(getHeight() / 2f + distanceY));
        moved = true;
        invalidate();
        return true;
      }

      @Override
      public boolean onDoubleTap(MotionEvent e) {
        zoomAt(e.getX(), e.getY(), 1.0d);
        moved = true;
        return true;
      }

      @Override
      public boolean onSingleTapConfirmed(MotionEvent e) {
        String hit = deviceAt(e.getX(), e.getY());
        if (hit != null && tapListener != null) {
          tapListener.onDeviceTapped(hit);
          return true;
        }
        return false;
      }
    });
  }

  // ------------------------------------------------------------------ public API

  public void setDevices(@NonNull List<TrackedDevice> newDevices) {
    devices.clear();
    devices.addAll(newDevices);
    colors.clear();
    for (TrackedDevice device : devices) {
      colors.put(device.id, ContextCompat.getColor(getContext(), device.state().colorRes));
    }
    invalidate();
  }

  public void setShowTrail(boolean showTrail) {
    this.showTrail = showTrail;
    invalidate();
  }

  public void setTapListener(@Nullable TapListener listener) {
    tapListener = listener;
  }

  public void focus(@Nullable TrackedDevice device) {
    if (device != null && device.hasPosition()) {
      centerLat = clampLat(device.latitude());
      centerLon = device.longitude();
      zoom = 16d;
      invalidate();
    }
  }

  public void fitAll() {
    double minLat = 90d;
    double maxLat = -90d;
    double minLon = 180d;
    double maxLon = -180d;
    int count = 0;
    for (TrackedDevice device : devices) {
      if (!device.hasPosition()) {
        continue;
      }
      count++;
      minLat = Math.min(minLat, device.latitude());
      maxLat = Math.max(maxLat, device.latitude());
      minLon = Math.min(minLon, device.longitude());
      maxLon = Math.max(maxLon, device.longitude());
    }
    if (count == 0) {
      return;
    }
    int width = getWidth() > 0 ? getWidth() : 720;
    int height = getHeight() > 0 ? getHeight() : 720;
    double lonSpan = Math.max(0.002d, maxLon - minLon);
    zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM,
        Geo.zoomForSpan(minLat, maxLat, lonSpan, width, height)));
    centerLat = clampLat((minLat + maxLat) / 2d);
    centerLon = (minLon + maxLon) / 2d;
    invalidate();
  }

  public boolean userMoved() {
    return moved;
  }

  public void resetMoved() {
    moved = false;
  }

  // ------------------------------------------------------------------ projection

  private double worldPx() {
    return 256d * Math.pow(2d, zoom);
  }

  private float xOf(double lon) {
    return (float) (getWidth() / 2d + (lon - centerLon) * worldPx() / 360d);
  }

  private float yOf(double lat) {
    return (float) (getHeight() / 2d
        + (Geo.mercatorY(lat) - Geo.mercatorY(centerLat)) * worldPx());
  }

  private double lonOf(float x) {
    return centerLon + (x - getWidth() / 2d) * 360d / worldPx();
  }

  private double latOf(float y) {
    return Geo.latitudeFromMercatorY(Geo.mercatorY(centerLat) + (y - getHeight() / 2d) / worldPx());
  }

  private void zoomAt(float focusX, float focusY, double delta) {
    double latBefore = latOf(focusY);
    double lonBefore = lonOf(focusX);
    zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + delta));
    centerLat = clampLat(centerLat + (latBefore - latOf(focusY)));
    centerLon = centerLon + (lonBefore - lonOf(focusX));
    invalidate();
  }

  private static double clampLat(double lat) {
    return Math.max(-85d, Math.min(85d, lat));
  }

  private double gridStep() {
    double world = worldPx();
    for (double step : STEPS) {
      if (step * world / 360d >= 70d) {
        return step;
      }
    }
    return STEPS[STEPS.length - 1];
  }

  // ------------------------------------------------------------------ drawing

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    drawGraticule(canvas);
    if (showTrail) {
      drawTrails(canvas);
    }
    drawPins(canvas);
    drawScaleBar(canvas);
    if (devices.isEmpty()) {
      canvas.drawText("No packets yet - send telemetry from your ESP32",
          getWidth() / 2f, getHeight() / 2f, emptyPaint);
    }
  }

  private void drawGraticule(Canvas canvas) {
    double step = gridStep();
    double left = lonOf(0f);
    double right = lonOf(getWidth());
    double firstLon = Math.floor(left / step) * step;
    for (double lon = firstLon; lon <= right; lon += step) {
      float x = xOf(lon);
      canvas.drawLine(x, 0f, x, getHeight(), gridPaint);
      canvas.drawText(formatDegree(lon, "E", "W"), x + 4f, 14f * getResources().getDisplayMetrics().density + 8f, labelPaint);
    }

    double top = latOf(0f);
    double bottom = latOf(getHeight());
    double firstLat = Math.ceil(bottom / step) * step;
    for (double lat = firstLat; lat <= top; lat += step) {
      float y = yOf(lat);
      if (y < 0 || y > getHeight()) {
        continue;
      }
      canvas.drawLine(0f, y, getWidth(), y, gridPaint);
      canvas.drawText(formatDegree(lat, "N", "S"), 6f, y - 6f, labelPaint);
    }
  }

  private void drawTrails(Canvas canvas) {
    for (TrackedDevice device : devices) {
      if (device.trail.size() < 2) {
        continue;
      }
      Integer color = colors.get(device.id);
      trailPaint.setColor(color == null ? 0xFF12BFA5 : color);
      trailPaint.setAlpha(190);
      dotPaint.setColor(trailPaint.getColor());
      dotPaint.setAlpha(150);
      Path path = new Path();
      boolean started = false;
      TrackedDevice.TrailPoint previous = null;
      for (TrackedDevice.TrailPoint point : device.trail) {
        float x = xOf(point.longitude);
        float y = yOf(point.latitude);
        if (!started || previous == null
            || Geo.distanceMeters(previous.latitude, previous.longitude,
            point.latitude, point.longitude) > 5000d) {
          path.moveTo(x, y);
          started = true;
        } else {
          path.lineTo(x, y);
        }
        if (device.trail.size() <= 120) {
          canvas.drawCircle(x, y, 2.2f * getResources().getDisplayMetrics().density, dotPaint);
        }
        previous = point;
      }
      canvas.drawPath(path, trailPaint);
    }
  }

  @SuppressLint("DrawAllocation")
  private void drawPins(Canvas canvas) {
    float density = getResources().getDisplayMetrics().density;
    float height = 40f * density;
    for (TrackedDevice device : devices) {
      if (!device.hasPosition()) {
        continue;
      }
      float x = xOf(device.longitude());
      float y = yOf(device.latitude());
      Integer color = colors.get(device.id);
      int pinColor = color == null ? 0xFF12BFA5 : color;
      PinPainter.drawPin(canvas, x, y, height, pinColor, false);

      String label = device.displayName();
      float textWidth = textPaint.measureText(label);
      android.graphics.RectF box = new android.graphics.RectF(x - textWidth / 2f - 6f * density,
          y - height - 22f * density, x + textWidth / 2f + 6f * density, y - height - 4f * density);
      haloPaint.setAlpha(210);
      canvas.drawRoundRect(box, 6f * density, 6f * density, haloPaint);
      textPaint.setColor(pinColor);
      canvas.drawText(label, x, y - height - 10f * density, textPaint);
    }
  }

  private void drawScaleBar(Canvas canvas) {
    float density = getResources().getDisplayMetrics().density;
    double metersPerPixel = Geo.metersPerPixel(centerLat, zoom);
    double targetMeters = metersPerPixel * 110d * density;
    double nice = niceNumber(targetMeters);
    float pixels = (float) (nice / metersPerPixel);
    float left = 16f * density;
    float bottom = getHeight() - 18f * density;
    canvas.drawLine(left, bottom, left + pixels, bottom, scalePaint);
    canvas.drawLine(left, bottom - 5f * density, left, bottom, scalePaint);
    canvas.drawLine(left + pixels, bottom - 5f * density, left + pixels, bottom, scalePaint);
    canvas.drawText(formatDistance(nice), left, bottom - 10f * density, scaleTextPaint);
  }

  private static double niceNumber(double value) {
    double exponent = Math.floor(Math.log10(Math.max(1d, value)));
    double base = Math.pow(10d, exponent);
    double normalised = value / base;
    double step;
    if (normalised >= 5d) {
      step = 5d;
    } else if (normalised >= 2d) {
      step = 2d;
    } else {
      step = 1d;
    }
    return step * base;
  }

  private static String formatDistance(double meters) {
    if (meters >= 1000d) {
      return String.format(Locale.US, "%.0f km", meters / 1000d);
    }
    return String.format(Locale.US, "%.0f m", meters);
  }

  private static String formatDegree(double value, String positive, String negative) {
    double abs = Math.abs(value);
    String suffix = value >= 0 ? positive : negative;
    int decimals = abs < 0.01d ? 4 : (abs < 1d ? 3 : (abs < 10d ? 2 : 0));
    return String.format(Locale.US, "%." + decimals + "f°%s", abs, suffix);
  }

  @Nullable
  private String deviceAt(float x, float y) {
    float density = getResources().getDisplayMetrics().density;
    float threshold = 34f * density;
    String best = null;
    float bestDistance = threshold;
    for (TrackedDevice device : devices) {
      if (!device.hasPosition()) {
        continue;
      }
      float dx = xOf(device.longitude()) - x;
      float dy = (yOf(device.latitude()) - 20f * density) - y;
      float distance = (float) Math.hypot(dx, dy);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = device.id;
      }
    }
    return best;
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(MotionEvent event) {
    scaleDetector.onTouchEvent(event);
    gestureDetector.onTouchEvent(event);
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      performClick();
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  /** Colour used for a state, so callers can match the map. */
  public int colorFor(DeviceState state) {
    return ContextCompat.getColor(getContext(), state.colorRes);
  }
}
