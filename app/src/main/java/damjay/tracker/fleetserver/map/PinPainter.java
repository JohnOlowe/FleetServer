package damjay.tracker.fleetserver.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws the map pin used for a tracker. Shared by the OSM markers (rendered into a bitmap) and the
 * offline map (drawn straight onto the canvas), so both look identical.
 */
public final class PinPainter {

  private PinPainter() {
  }

  /** Creates the bitmap used as an osmdroid marker icon. */
  public static Bitmap createPinBitmap(Context context, int colorArgb, boolean faded) {
    float density = context.getResources().getDisplayMetrics().density;
    float height = 44f * density;
    float width = 38f * density;
    int bitmapWidth = (int) Math.ceil(width + 4f * density);
    int bitmapHeight = (int) Math.ceil(height + 4f * density);
    Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    drawPin(canvas, bitmapWidth / 2f, height, height, colorArgb, faded);
    return bitmap;
  }

  /**
   * Draws a pin whose tip sits at {@code (centreX, baseY)}.
   *
   * @param heightPx total height of the pin, tip to top of the head
   */
  public static void drawPin(Canvas canvas, float centreX, float baseY, float heightPx,
      int colorArgb, boolean faded) {
    Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    fill.setStyle(Paint.Style.FILL);
    fill.setColor(colorArgb);
    fill.setAlpha(faded ? 110 : 255);

    Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(Math.max(1.5f, heightPx * 0.055f));
    stroke.setColor(0xFFFFFFFF);
    stroke.setAlpha(faded ? 140 : 230);

    Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
    hole.setStyle(Paint.Style.FILL);
    hole.setColor(0xFFFFFFFF);
    hole.setAlpha(faded ? 150 : 255);

    float radius = heightPx * 0.36f;
    float centreY = baseY - heightPx + radius;
    float distance = baseY - centreY;

    canvas.save();
    if (faded) {
      canvas.saveLayerAlpha(0, 0, canvas.getWidth(), canvas.getHeight(), 140);
    }

    Path path = new Path();
    if (distance > radius * 1.05f) {
      double phi = Math.acos(radius / distance);
      float dx = (float) (radius * Math.sin(phi));
      float dy = radius * radius / distance;
      float startAngle = (float) Math.toDegrees(Math.atan2(dy, -dx));
      float endAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
      float sweep = 360f - (startAngle - endAngle);
      path.moveTo(centreX, baseY);
      path.lineTo(centreX - dx, centreY + dy);
      path.arcTo(new RectF(centreX - radius, centreY - radius, centreX + radius, centreY + radius),
          startAngle, sweep, false);
      path.lineTo(centreX, baseY);
      path.close();
    } else {
      path.addCircle(centreX, centreY, radius, Path.Direction.CW);
    }
    canvas.drawPath(path, fill);
    canvas.drawPath(path, stroke);
    canvas.drawCircle(centreX, centreY, radius * 0.40f, hole);

    if (faded) {
      canvas.restore();
    }
    canvas.restore();
  }

}
