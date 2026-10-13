package com.yucareux.tellus.client.widget.map.component;

import com.yucareux.tellus.client.preload.TellusPreloadClientState;
import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapTile;
import com.yucareux.tellus.client.widget.map.SlippyMapTilePos;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.preload.TellusPreloadRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import java.util.List;
import java.util.Locale;

/**
 * Map overlay: "downloaded = original colour, not downloaded = grey".
 *
 * <p>How it works: lay a translucent grey veil over the whole map, then redraw the tiles of every
 * <b>downloaded region</b> the usual way (scissored to that region), so those areas return to the
 * original map colours while the rest stays grey. That needs no change to the tile cache and does
 * not affect map dragging or zooming.
 *
 * <p>Each downloaded region is outlined in green and the total area is shown in the bottom left.
 */
@Environment(EnvType.CLIENT)
public class PreloadOverlayMapComponent implements MapComponent {

   private static final int VEIL = 0x99000000;        // the grey veil (translucent black)
   private static final int BORDER = 0xCC2E7D32;
   private static final int TEXT_BG = 0xAA000000;
   private static final int TILE_SIZE = 256;

   private final SlippyMapWidget widget;
   private boolean enabled = true;

   public PreloadOverlayMapComponent(SlippyMapWidget widget) {
      this.widget = widget;
   }

   public void setEnabled(boolean enabled) {
      this.enabled = enabled;
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   @Override
   public void onDrawMap(SlippyMap map, GuiGraphics graphics, int mouseX, int mouseY, SlippyMapPoint mouse) {
      if (!this.enabled) {
         return;
      }
      List<TellusPreloadRegistry.Region> regions = TellusPreloadClientState.regions();
      int width = this.widget.getWidth();
      int height = this.widget.getHeight();

      // 1) Grey out the whole map
      graphics.fill(0, 0, width, height, VEIL);

      int scale = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
      int zoom = map.getCameraZoom();
      int cameraX = map.getCameraX();
      int cameraY = map.getCameraY();

      for (TellusPreloadRegistry.Region region : regions) {
         SlippyMapPoint cornerA = new SlippyMapPoint(region.maxLat(), region.minLon());
         SlippyMapPoint cornerB = new SlippyMapPoint(region.minLat(), region.maxLon());
         int x1 = (cornerA.getX(zoom) - cameraX) / scale;
         int y1 = (cornerA.getY(zoom) - cameraY) / scale;
         int x2 = (cornerB.getX(zoom) - cameraX) / scale;
         int y2 = (cornerB.getY(zoom) - cameraY) / scale;
         int left = Math.min(x1, x2);
         int top = Math.min(y1, y2);
         int right = Math.max(x1, x2);
         int bottom = Math.max(y1, y2);

         // 2) This region is downloaded: redraw its tiles to restore the original colours
         //    scissor works in absolute screen coordinates, so the map relative coordinates
         //    must be offset by the widget origin
         graphics.enableScissor(
               this.widget.getX() + Math.max(0, left),
               this.widget.getY() + Math.max(0, top),
               this.widget.getX() + Math.min(width, right),
               this.widget.getY() + Math.min(height, bottom));
         // 1.20.1 real-machine self-check: try/finally so a failure while redrawing tiles cannot
         // escape with the scissor still enabled (a leaked scissor clips every later GUI draw).
         try {
            this.redrawTiles(map, graphics, scale);
         } finally {
            graphics.disableScissor();
         }

         // 3) Outline
         graphics.fill(left, top, right, top + 1, BORDER);
         graphics.fill(left, bottom - 1, right, bottom, BORDER);
         graphics.fill(left, top, left + 1, bottom, BORDER);
         graphics.fill(right - 1, top, right, bottom, BORDER);
      }

      // 4) Total area
      if (!regions.isEmpty()) {
         Font font = Minecraft.getInstance().font;
         String summary = String.format(Locale.ROOT, "已下载 %d 块区域 / %.1f km^2（绿色 = 已下载，灰色 = 未下载）",
               regions.size(), TellusPreloadClientState.downloadedAreaKm2());
         int textY = Math.max(1, height - 24);
         graphics.fill(0, textY, Math.min(width, font.width(summary) + 6), textY + 11, TEXT_BG);
         graphics.drawString(font, summary, 3, textY + 2, 0xE0FFE0, false);
      }
   }

   /** Redraws the visible tiles with the same algorithm as {@code SlippyMapWidget#renderTile}. */
   private void redrawTiles(SlippyMap map, GuiGraphics graphics, int guiScale) {
      int cameraX = map.getCameraX();
      int cameraY = map.getCameraY();
      int cameraZoom = map.getCameraZoom();
      List<SlippyMapTilePos> tiles = map.getVisibleTiles();
      if (tiles == null) {
         return;
      }
      for (SlippyMapTilePos pos : tiles) {
         SlippyMapTile tile = map.getTile(pos);
         if (tile == null || !tile.isReady()) {
            continue;
         }
         ResourceLocation location = tile.getLocation();
         if (location == null) {
            continue;
         }
         int deltaZoom = cameraZoom - pos.getZoom();
         double zoomScale = Math.pow(2.0, deltaZoom);
         int size = Mth.floor(TILE_SIZE * zoomScale);
         int renderX = (pos.getX() << deltaZoom) * TILE_SIZE - cameraX;
         int renderY = (pos.getY() << deltaZoom) * TILE_SIZE - cameraY;
         int textureSize = Math.max(TILE_SIZE, size);
         graphics.pose().pushPose();
         graphics.pose().scale(1.0F / guiScale, 1.0F / guiScale, 1.0F);
         graphics.blit(location, renderX, renderY, 0.0F, 0.0F, size, size,
               textureSize, textureSize);
         graphics.pose().popPose();
      }
   }
}
