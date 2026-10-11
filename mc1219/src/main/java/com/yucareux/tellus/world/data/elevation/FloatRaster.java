package com.yucareux.tellus.world.data.elevation;

final class FloatRaster {
   private final int width;
   private final int height;
   private final float[] data;

   private FloatRaster(int width, int height, float[] data) {
      this.width = width;
      this.height = height;
      this.data = data;
   }

   static FloatRaster create(int width, int height) {
      return new FloatRaster(width, height, new float[width * height]);
   }

   int width() {
      return this.width;
   }

   int height() {
      return this.height;
   }

   float get(int x, int y) {
      return this.data[y * this.width + x];
   }

   void set(int x, int y, float value) {
      this.data[y * this.width + x] = value;
   }
}
