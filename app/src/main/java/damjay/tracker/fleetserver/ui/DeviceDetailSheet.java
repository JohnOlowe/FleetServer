package damjay.tracker.fleetserver.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import damjay.tracker.fleetserver.R;
import damjay.tracker.fleetserver.databinding.SheetDeviceDetailBinding;
import damjay.tracker.fleetserver.model.Telemetry;
import damjay.tracker.fleetserver.model.TrackedDevice;
import damjay.tracker.fleetserver.store.FleetStore;

/** Details of one tracker: last report, trail summary and a few actions. */
public class DeviceDetailSheet extends BottomSheetDialogFragment implements FleetStore.Listener {

  /** Implemented by the hosting activity so the sheet can move the map. */
  public interface Actions {
    void onFocusDevice(String deviceId);
  }

  private static final String ARG_DEVICE_ID = "device_id";

  private SheetDeviceDetailBinding binding;
  private FleetStore store;
  private String deviceId;

  public static DeviceDetailSheet newInstance(String deviceId) {
    DeviceDetailSheet sheet = new DeviceDetailSheet();
    Bundle args = new Bundle();
    args.putString(ARG_DEVICE_ID, deviceId);
    sheet.setArguments(args);
    return sheet;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Bundle args = getArguments();
    deviceId = args == null ? "" : args.getString(ARG_DEVICE_ID, "");
    store = FleetStore.get(requireContext());
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    binding = SheetDeviceDetailBinding.inflate(inflater, container, false);

    binding.buttonCenter.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        Actions actions = actions();
        if (actions != null) {
          actions.onFocusDevice(deviceId);
        }
        dismiss();
      }
    });
    binding.buttonCopy.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        copyCoordinates();
      }
    });
    binding.buttonClearTrail.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        store.clearTrail(deviceId);
        render();
      }
    });
    binding.buttonRemove.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        store.remove(deviceId);
        dismiss();
      }
    });

    render();
    return binding.getRoot();
  }

  @Override
  public void onStart() {
    super.onStart();
    store.addListener(this);
  }

  @Override
  public void onStop() {
    store.removeListener(this);
    super.onStop();
  }

  @Override
  public void onFleetChanged() {
    if (isAdded() && binding != null) {
      render();
    }
  }

  @Override
  public void onLogChanged() {
    // not needed here
  }

  @Nullable
  private Actions actions() {
    if (getActivity() instanceof Actions) {
      return (Actions) getActivity();
    }
    return null;
  }

  private void render() {
    TrackedDevice device = store.get(deviceId);
    if (device == null || binding == null) {
      dismissAllowingStateLoss();
      return;
    }
    Context context = binding.getRoot().getContext();
    int color = ContextCompat.getColor(context, device.state().colorRes);

    binding.detailName.setText(device.displayName());
    binding.detailState.setText(device.state().label);
    GradientDrawable pill = (GradientDrawable) binding.detailState.getBackground().mutate();
    pill.setColor(color);
    binding.detailState.setTextColor(0xFFFFFFFF);
    binding.detailCoords.setText(device.hasPosition()
        ? Format.coordinates(device.latitude(), device.longitude())
        : context.getString(R.string.no_position));

    binding.detailRows.removeAllViews();
    Telemetry t = device.last;
    addRow(context.getString(R.string.label_accuracy),
        t == null ? "—" : Format.value(t.accuracy, "m", 1));
    addRow(context.getString(R.string.label_satellites),
        t == null ? "—" : Format.integer(t.satellites, ""));
    addRow(context.getString(R.string.label_speed),
        t == null ? "—" : Format.value(t.speed, "m/s", 1));
    addRow(context.getString(R.string.label_heading),
        t == null ? "—" : Format.value(t.heading, "°" + Format.compass(t.heading), 0));
    addRow(context.getString(R.string.label_altitude),
        t == null ? "—" : Format.value(t.altitude, "m", 1));
    addRow(context.getString(R.string.label_hdop),
        t == null ? "—" : Format.value(t.hdop, "", 2));
    addRow(context.getString(R.string.label_battery),
        t == null ? "—" : Format.value(t.battery, "%", 0));
    addRow(context.getString(R.string.label_rssi),
        t == null || t.rssi == Integer.MIN_VALUE ? "—" : Format.integer(t.rssi, "dBm"));
    addRow(context.getString(R.string.label_source),
        device.lastSourceIp == null || device.lastSourceIp.isEmpty() ? "—" : device.lastSourceIp);
    addRow(context.getString(R.string.label_packets), String.valueOf(device.packets));
    addRow(context.getString(R.string.label_trail),
        device.trail.size() + "  ("
            + Format.value(device.trailLengthMeters() / 1000d, "km", 2) + ")");
    addRow(context.getString(R.string.label_first_seen), Format.dateTime(device.firstSeen));
    addRow(context.getString(R.string.label_last_update),
        Format.dateTime(device.lastSeen) + "  (" + Format.timeAgo(context, device.lastSeen) + ")");
    if (t != null && t.note != null && !t.note.isEmpty()) {
      addRow(context.getString(R.string.label_note), t.note);
    }
  }

  private void addRow(String label, String value) {
    Context context = requireContext();
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, (int) (4 * getResources().getDisplayMetrics().density), 0,
        (int) (4 * getResources().getDisplayMetrics().density));

    TextView labelView = new TextView(context);
    labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    labelView.setText(label);
    labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
    labelView.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));

    TextView valueView = new TextView(context);
    valueView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT));
    valueView.setText(value);
    valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
    valueView.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
    valueView.setTypeface(android.graphics.Typeface.MONOSPACE);

    row.addView(labelView);
    row.addView(valueView);
    binding.detailRows.addView(row);
  }

  private void copyCoordinates() {
    TrackedDevice device = store.get(deviceId);
    if (device == null || !device.hasPosition()) {
      return;
    }
    Context context = requireContext();
    ClipboardManager manager =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (manager != null) {
      manager.setPrimaryClip(ClipData.newPlainText("coordinates",
          Format.coordinates(device.latitude(), device.longitude())));
      Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }
  }
}
