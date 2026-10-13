package com.yucareux.tellus.client.widget.map.component;

import com.yucareux.tellus.client.screen.CoordinateFormat;
import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Locale;

/**
 * The "select a region" component drawn on the map.
 *
 * <p>Hold the left button to drag out a rectangle and release to confirm it. The rectangle is
 * stored as latitude/longitude, and the screen uses it to show the region and its area and to hand
 * it to the server for preloading.
 *
 * <p>Coordinate conversion: the map internals use "physical pixels" while GuiGraphics uses GUI
 * coordinates, so map coordinates are divided by the GUI scale (same approach as
 * {@code MarkerMapComponent}).
 */
@Environment(EnvType.CLIENT)
public class RegionSelectionMapComponent implements MapComponent {

   private static final int FILL = 0x332E7D32;      // translucent green: the selected region
   private static final int BORDER = 0xFF4CAF50;
   private static final int BORDER_HOT = 0xFFFFC107; // while dragging

   private SlippyMapPoint start;
   private SlippyMapPoint end;
   private boolean dragging;
   private boolean enabled = true;

   public void setEnabled(boolean enabled) {
      this.enabled = enabled;
      if (!enabled) {
         this.dragging = false;
      }
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public boolean hasSelection() {
      return this.start != null && this.end != null;
   }

   public double minLatitude() {
      return Math.min(this.start.getLatitude(), this.end.getLatitude());
   }

   public double maxLatitude() {
      return Math.max(this.start.getLatitude(), this.end.getLatitude());
   }

   public double minLongitude() {
      return Math.min(this.start.getLongitude(), this.end.getLongitude());
   }

   public double maxLongitude() {
      return Math.max(this.start.getLongitude(), this.end.getLongitude());
   }

   public void clear() {
      this.start = null;
      this.end = null;
      this.dragging = false;
   }

   @Override
   public boolean onMouseClicked(SlippyMap map, SlippyMapPoint mouse, int button) {
      if (!this.enabled || button != 0) {
         return false;
      }
      this.start = mouse;
      this.end = mouse;
      this.dragging = true;
      return true; // swallow this click so the marker is not dragged along at the same time
   }

   @Override
   public boolean onMouseReleased(SlippyMap map, SlippyMapPoint mouse, int button) {
      if (!this.enabled || !this.dragging || button != 0) {
         return false;
      }
      this.end = mouse;
      this.dragging = false;
      return true;
   }

   @Override
   public void onDrawMap(SlippyMap map, GuiGraphics graphics, int mouseX, int mouseY, SlippyMapPoint mouse) {
      if (!this.hasSelection()) {
         return;
      }
      int scale = Math.max(1, (int)Minecraft.getInstance().getWindow().getGuiScale());
      int zoom = map.getCameraZoom();
      int cameraX = map.getCameraX();
      int cameraY = map.getCameraY();

      SlippyMapPoint a = new SlippyMapPoint(this.maxLatitude(), this.minLongitude());
      SlippyMapPoint b = new SlippyMapPoint(this.minLatitude(), this.maxLongitude());
      int x1 = (a.getX(zoom) - cameraX) / scale;
      int y1 = (a.getY(zoom) - cameraY) / scale;
      int x2 = (b.getX(zoom) - cameraX) / scale;
      int y2 = (b.getY(zoom) - cameraY) / scale;
      int left = Math.min(x1, x2);
      int top = Math.min(y1, y2);
      int right = Math.max(x1, x2);
      int bottom = Math.max(y1, y2);

      graphics.fill(left, top, right, bottom, FILL);
      int border = this.dragging ? BORDER_HOT : BORDER;
      graphics.fill(left, top, right, top + 1, border);
      graphics.fill(left, bottom - 1, right, bottom, border);
      graphics.fill(left, top, left + 1, bottom, border);
      graphics.fill(right - 1, top, right, bottom, border);

      // Region and area (the area uses a spherical approximation, same as the ledger algorithm)
      Font font = Minecraft.getInstance().font;
      double latSpan = this.maxLatitude() - this.minLatitude();
      double lonSpan = this.maxLongitude() - this.minLongitude();
      double midLat = Math.toRadians((this.minLatitude() + this.maxLatitude()) / 2.0);
      double areaKm2 = latSpan * 111.32 * lonSpan * 111.32 * Math.cos(midLat);
      String label = String.format(Locale.ROOT, "%.4f,%.4f → %.4f,%.4f | %.1f km²",
            this.minLatitude(), this.minLongitude(), this.maxLatitude(), this.maxLongitude(), areaKm2);

      int textY = Math.max(2, top - 12);
      int textWidth = font.width(label) + 6;
      int textX = Math.min(Math.max(2, left), Math.max(2, map.getCameraX()));
      graphics.fill(textX, textY, textX + textWidth, textY + 11, 0xAA000000);
      graphics.drawString(font, label, textX + 3, textY + 2, 0xFFFFFF, false);
      graphics.drawString(font, CoordinateFormat.formatPair(this.minLatitude(), this.minLongitude()),
            left + 2, bottom + 2, 0xCCFFFFFF, false);
   }
}
