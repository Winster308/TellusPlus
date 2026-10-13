package com.yucareux.tellus.world.data.elevation;

import java.util.Arrays;

final class ShortRaster {
   private final int width;
   private final int height;
   private final short[] data;

   private ShortRaster(int width, int height, short[] data) {
      this.width = width;
      this.height = height;
      this.data = data;
   }

   static ShortRaster create(int width, int height) {
      return new ShortRaster(width, height, new short[width * height]);
   }

   static ShortRaster wrap(int width, int height, short[] data) {
      if (data.length != width * height) {
         throw new IllegalArgumentException("Invalid raster buffer");
      } else {
         return new ShortRaster(width, height, data);
      }
   }

   int width() {
      return this.width;
   }

   int height() {
      return this.height;
   }

   short get(int x, int y) {
      return this.data[x + y * this.width];
   }

   // 2026-10-05: the raw buffer, row major. Added for TellusElevationSource.repairDecodedTile,
   // which has to hand a whole raster to the repair pipeline in one call; going through get(x, y)
   // there would recompute x + y * width 65536 times per tile on the heightGrid hot path.
   short[] data() {
      return this.data;
   }

   void set(int x, int y, short value) {
      this.data[x + y * this.width] = value;
   }

   void fill(short value) {
      Arrays.fill(this.data, value);
   }
}
