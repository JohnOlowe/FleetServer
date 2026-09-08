package damjay.tracker.fleetserver.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import damjay.tracker.fleetserver.databinding.ItemDeviceBinding;
import damjay.tracker.fleetserver.model.TrackedDevice;

/** The list of trackers below the map. */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {

  public interface Callback {
    void onDeviceClicked(TrackedDevice device);
  }

  private final List<TrackedDevice> items = new ArrayList<>();
  private final Callback callback;

  public DeviceAdapter(Callback callback) {
    this.callback = callback;
  }

  public void submit(List<TrackedDevice> devices) {
    items.clear();
    items.addAll(devices);
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    ItemDeviceBinding binding =
        ItemDeviceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
    return new Holder(binding);
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position) {
    holder.bind(items.get(position));
  }

  @Override
  public int getItemCount() {
    return items.size();
  }

  class Holder extends RecyclerView.ViewHolder {
    private final ItemDeviceBinding binding;

    Holder(ItemDeviceBinding binding) {
      super(binding.getRoot());
      this.binding = binding;
      binding.getRoot().setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View view) {
          int index = getBindingAdapterPosition();
          if (index != RecyclerView.NO_POSITION && callback != null) {
            callback.onDeviceClicked(items.get(index));
          }
        }
      });
    }

    void bind(TrackedDevice device) {
      Context context = itemView.getContext();
      int color = ContextCompat.getColor(context, device.state().colorRes);

      binding.deviceName.setText(device.displayName());
      if (device.hasPosition()) {
        binding.deviceCoords.setText(Format.coordinates(device.latitude(), device.longitude()));
      } else {
        binding.deviceCoords.setText(context.getString(R.string.no_position));
      }
      binding.deviceMeta.setText(context.getString(R.string.packets_received, device.packets)
          + " · " + Format.timeAgo(context, device.lastSeen));
      binding.deviceState.setText(device.state().label);

      GradientDrawable pill = (GradientDrawable) binding.deviceState.getBackground().mutate();
      pill.setColor(color);
      ViewCompat.setBackgroundTintList(binding.stateBar, ColorStateList.valueOf(color));
    }
  }
}
