package damjay.tracker.fleetserver.model;

/** Small geodesy helpers shared by the map layers. */
public final class Geo {

  private static final double EARTH_RADIUS_M = 6371008.8d;

  private Geo() {
  }

  /** Great-circle distance in metres. */
  public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0d, Math.sqrt(a)));
  }

  /** Initial bearing in degrees (0 = north, 90 = east). */
  public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dLon = Math.toRadians(lon2 - lon1);
    double y = Math.sin(dLon) * Math.cos(p2);
    double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dLon);
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0d) % 360.0d;
  }

  /** Web-mercator Y of a latitude, normalised so that the whole world spans 1.0. */
  public static double mercatorY(double latitude) {
    double lat = Math.max(-85.05112878d, Math.min(85.05112878d, latitude));
    return 0.5d - Math.log(Math.tan(Math.PI / 4.0d + Math.toRadians(lat) / 2.0d)) / (2.0d * Math.PI);
  }

  /** Inverse of {@link #mercatorY(double)}. */
  public static double latitudeFromMercatorY(double y) {
    double n = Math.PI * (1.0d - 2.0d * y);
    return Math.toDegrees(Math.atan(Math.sinh(n)));
  }

  /** Metres per pixel at a given latitude for a slippy-map zoom level (256px tiles). */
  public static double metersPerPixel(double latitude, double zoom) {
    return 156543.03392804097d * Math.cos(Math.toRadians(latitude)) / Math.pow(2.0d, zoom);
  }

  /**
   * Zoom level (256px tiles) at which the given bounding box fits into the given pixel area,
   * leaving a small margin.
   */
  public static double zoomForSpan(double minLat, double maxLat, double lonSpan,
      double widthPx, double heightPx) {
    double worldPx = 256.0d;
    double usableW = Math.max(1.0d, widthPx * 0.85d);
    double usableH = Math.max(1.0d, heightPx * 0.85d);
    double lonZoom = Math.log(usableW * 360.0d / (worldPx * Math.max(1e-7d, lonSpan))) / Math.log(2.0d);
    double mercSpan = Math.max(1e-7d, Math.abs(mercatorY(minLat) - mercatorY(maxLat)));
    double latZoom = Math.log(usableH / (worldPx * mercSpan)) / Math.log(2.0d);
    return Math.min(lonZoom, latZoom);
  }
}
