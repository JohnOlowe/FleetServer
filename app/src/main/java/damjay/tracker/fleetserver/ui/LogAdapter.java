package damjay.tracker.fleetserver.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import damjay.tracker.fleetserver.databinding.ItemLogBinding;
import damjay.tracker.fleetserver.store.FleetStore;

/** The traffic log: one line per accepted packet, error or system event. */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

  private final List<FleetStore.LogEntry> entries = new ArrayList<>();

  public void submit(List<FleetStore.LogEntry> newEntries) {
    entries.clear();
    entries.addAll(newEntries);
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    ItemLogBinding binding =
        ItemLogBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
    return new Holder(binding);
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position) {
    FleetStore.LogEntry entry = entries.get(position);
    holder.binding.logLine.setText(Format.clock(entry.timeMillis) + "  [" + entry.level + "]  "
        + entry.message);
  }

  @Override
  public int getItemCount() {
    return entries.size();
  }

  static class Holder extends RecyclerView.ViewHolder {
    final ItemLogBinding binding;

    Holder(ItemLogBinding binding) {
      super(binding.getRoot());
      this.binding = binding;
    }
  }
}
