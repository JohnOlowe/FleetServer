package damjay.tracker.fleetserver.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Finds the addresses an ESP32 can reach us on - including the hotspot interface. */
public final class NetInfo {

  private static final String TAG = "NetInfo";

  private NetInfo() {
  }

  /** An IPv4 address on one of the device's network interfaces. */
  public static class Address {
    public final String interfaceName;
    public final String ip;

    Address(String interfaceName, String ip) {
      this.interfaceName = interfaceName;
      this.ip = ip;
    }

    public String url(int port) {
      return "http://" + ip + ":" + port;
    }
  }

  /** Every usable IPv4 address, most likely first (wifi, then hotspot, then ethernet). */
  @NonNull
  public static List<Address> localAddresses() {
    List<Address> found = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) {
        return found;
      }
      for (NetworkInterface networkInterface : Collections.list(interfaces)) {
        if (!networkInterface.isUp() || networkInterface.isLoopback()) {
          continue;
        }
        String name = networkInterface.getName();
        for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
          if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
            continue;
          }
          if (address instanceof Inet4Address) {
            found.add(new Address(name, address.getHostAddress()));
          }
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "could not enumerate interfaces", e);
    }
    Collections.sort(found, (a, b) -> rank(b.interfaceName) - rank(a.interfaceName));
    return found;
  }

  @NonNull
  public static List<String> localIps() {
    List<String> ips = new ArrayList<>();
    for (Address address : localAddresses()) {
      ips.add(address.ip);
    }
    return ips;
  }

  /** The address a tracker is most likely to reach, or null when we are offline. */
  @Nullable
  public static Address preferredAddress(Context context) {
    List<Address> addresses = localAddresses();
    if (addresses.isEmpty()) {
      return null;
    }
    String active = activeInterfaceName(context);
    if (active != null) {
      for (Address address : addresses) {
        if (active.equals(address.interfaceName)) {
          return address;
        }
      }
    }
    return addresses.get(0);
  }

  @Nullable
  public static String activeInterfaceName(Context context) {
    try {
      ConnectivityManager manager =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (manager == null) {
        return null;
      }
      Network network = manager.getActiveNetwork();
      if (network == null) {
        return null;
      }
      LinkProperties properties = manager.getLinkProperties(network);
      return properties == null ? null : properties.getInterfaceName();
    } catch (Exception e) {
      return null;
    }
  }

  /** Short description of how the phone is connected, e.g. "Wi-Fi" or "Hotspot + mobile data". */
  @NonNull
  public static String transportSummary(Context context) {
    try {
      ConnectivityManager manager =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (manager == null) {
        return "no connectivity info";
      }
      Network network = manager.getActiveNetwork();
      if (network == null) {
        return "offline";
      }
      NetworkCapabilities caps = manager.getNetworkCapabilities(network);
      if (caps == null) {
        return "connected";
      }
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return "Wi-Fi";
      }
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        return "Ethernet";
      }
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        return "Mobile data (hotspot clients share this connection)";
      }
      return "connected";
    } catch (Exception e) {
      return "connected";
    }
  }

  private static int rank(String interfaceName) {
    if (interfaceName == null) {
      return 0;
    }
    String n = interfaceName.toLowerCase();
    if (n.startsWith("wlan0") || n.equals("wlan")) {
      return 6;
    }
    if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("wlan1") || n.startsWith("softap")) {
      return 5;   // hotspot interfaces
    }
    if (n.startsWith("eth")) {
      return 4;
    }
    if (n.startsWith("rndis") || n.startsWith("usb")) {
      return 3;
    }
    if (n.startsWith("wlan")) {
      return 2;
    }
    return 1;
  }
}
