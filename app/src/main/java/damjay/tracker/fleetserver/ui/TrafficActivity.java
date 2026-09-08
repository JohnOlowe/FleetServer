package damjay.tracker.fleetserver.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import damjay.tracker.fleetserver.R;
import damjay.tracker.fleetserver.databinding.ActivityTrafficBinding;
import damjay.tracker.fleetserver.store.FleetStore;

/** Shows the raw ingest log - the quickest way to see whether an ESP32 is talking to us. */
public class TrafficActivity extends AppCompatActivity implements FleetStore.Listener {

  private ActivityTrafficBinding binding;
  private final LogAdapter adapter = new LogAdapter();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private FleetStore store;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityTrafficBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());
    store = FleetStore.get(this);

    binding.logList.setLayoutManager(new LinearLayoutManager(this));
    binding.logList.setAdapter(adapter);
    binding.toolbar.setNavigationOnClickListener(v -> finish());
    binding.toolbar.setOnMenuItemClickListener(item -> {
      if (item.getItemId() == R.id.action_clear_log) {
        store.clearLogs();
        refresh();
        return true;
      }
      return false;
    });
    binding.toolbar.inflateMenu(R.menu.menu_traffic);

    refresh();
  }

  @Override
  protected void onStart() {
    super.onStart();
    store.addListener(this);
    refresh();
  }

  @Override
  protected void onStop() {
    store.removeListener(this);
    handler.removeCallbacksAndMessages(null);
    super.onStop();
  }

  @Override
  public void onLogChanged() {
    scheduleRefresh();
  }

  @Override
  public void onFleetChanged() {
    // not needed here
  }

  private void scheduleRefresh() {
    handler.removeCallbacksAndMessages(null);
    handler.postDelayed(this::refresh, 200L);
  }

  private void refresh() {
    adapter.submit(store.logs());
    binding.emptyLog.setVisibility(adapter.getItemCount() == 0 ? android.view.View.VISIBLE
        : android.view.View.GONE);
    if (adapter.getItemCount() > 0) {
      binding.logList.scrollToPosition(adapter.getItemCount() - 1);
    }
  }
}
