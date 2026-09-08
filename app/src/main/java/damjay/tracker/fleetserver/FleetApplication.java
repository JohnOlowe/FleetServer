package damjay.tracker.fleetserver;

import android.app.Application;
import android.util.Log;

import org.osmdroid.config.Configuration;

import java.io.File;

import damjay.tracker.fleetserver.store.FleetStore;

/** Application entry point: warms up the store and points osmdroid at our own cache directory. */
public class FleetApplication extends Application {

  private static final String TAG = "FleetApplication";

  @Override
  public void onCreate() {
    super.onCreate();
    FleetStore.get(this);
    configureOsmdroid();
  }

  private void configureOsmdroid() {
    try {
      File base = new File(getFilesDir(), "osmdroid");
      File tiles = new File(base, "tiles");
      Configuration configuration = Configuration.getInstance();
      configuration.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
      configuration.setUserAgentValue(getPackageName());
      configuration.setOsmdroidBasePath(base);
      configuration.setOsmdroidTileCache(tiles);
    } catch (Exception e) {
      Log.w(TAG, "could not configure osmdroid", e);
    }
  }
}
