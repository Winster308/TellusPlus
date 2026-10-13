package com.yucareux.tellus.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small client-side drawing helpers.
 *
 * <p>The 26.1 {@code GuiGraphicsExtractor#outline(x, y, w, h, color)} has no counterpart on 1.21.9
 * (1.21.9 only offers {@code submitOutline}, which goes through the render state batching pipeline
 * instead of drawing a frame immediately), so the border is assembled from four 1 pixel
 * {@code fill} calls.
 */
public final class TellusGui {

   private TellusGui() {
   }

   /** Draws a 1 pixel wide rectangle outline (outer edge lands exactly on x..x+w / y..y+h). */
   public static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
      if (width <= 0 || height <= 0) {
         return;
      }
      graphics.fill(x, y, x + width, y + 1, color);
      graphics.fill(x, y + height - 1, x + width, y + height, color);
      if (height > 2) {
         graphics.fill(x, y + 1, x + 1, y + height - 1, color);
         graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
      }
   }
}
