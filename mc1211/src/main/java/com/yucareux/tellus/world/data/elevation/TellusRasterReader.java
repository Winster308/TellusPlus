package com.yucareux.tellus.world.data.elevation;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tukaani.xz.SingleXZInputStream;

final class TellusRasterReader {
   private static final byte[] SIGNATURE = "TELLUS/RASTER".getBytes(StandardCharsets.US_ASCII);
   private static final int FORMAT_SHORT = 2;
   /**
    * 2026-10-05: sanity ceiling for one chunk, used to reject a corrupt length field before it is
    * used for an allocation. A real chunk is a 256x256 short chunk (131072 bytes of payload) run
    * through XZ, so it stays far below this; 4 MiB leaves ~32x headroom and still keeps a garbage
    * length from turning into an OutOfMemoryError, which nothing upstream catches.
    */
   private static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;

   private TellusRasterReader() {
   }

   static ShortRaster readShortRaster(InputStream input) throws IOException {
      DataInputStream dataIn = new DataInputStream(input);
      byte[] signature = new byte[SIGNATURE.length];
      dataIn.readFully(signature);
      if (!Arrays.equals(signature, SIGNATURE)) {
         throw new IOException("Invalid Tellus raster signature");
      } else {
         int version = dataIn.readUnsignedByte();
         if (version != 0) {
            throw new IOException("Unsupported Tellus raster version: " + version);
      } else {
         int width = dataIn.readInt();
         int height = dataIn.readInt();
         int format = dataIn.readUnsignedByte();
         if (format != FORMAT_SHORT) {
            throw new IOException("Expected short-integer raster format");
         } else {
               ShortRaster raster = ShortRaster.create(width, height);

               // 2026-10-05: a truncated or short-covering file must NOT produce a partial raster.
               //   Two distinct triggers used to end in a silent success:
               //   (a) EOFException on the chunk header was answered with `return raster`. But the
               //       writer emits no terminator, so the loop always *had* to end on that EOF after
               //       the last complete chunk, which is why the catch was there -- and that made a
               //       header read that ran out of bytes half way through indistinguishable from the
               //       legitimate end. A file cut short by a crash, a full disk or a partial download
               //       therefore returned "success".
               //   (b) Nothing ever checked that the chunks actually covered the declared width x
               //       height. A header promising 256x256 with chunks covering only part of it
               //       returned "success" with the remainder still at the ShortRaster.create zero
               //       fill.
               //   The consequence in both cases was identical silent data corruption: ShortRaster
               //   zero-fills, so every unwritten pixel read back as 0 = sea level, i.e. half a real
               //   tile next to a fabricated flat 0 m half -- the square-plateau artefact. Policy
               //   chosen: throw. NormalizedElevationCache.readTile already catches IOException,
               //   calls deleteCorruptTile and returns null, so the tile is rebuilt from the DEM chain
               //   instead of being served half fake.
               int expectedPixels = width * height;
               int writtenPixels = 0;

               while (true) {
                  // Read the four length bytes one at a time so a genuine end of stream (the writer
                  // puts nothing after the last chunk) is still a success, while a partial header is
                  // not. readInt() is deliberately NOT used here: it consumes four bytes, which would
                  // read one byte past the header and shift the assembled length.
                  int firstByte = dataIn.read();
                  if (firstByte < 0) {
                     if (writtenPixels != expectedPixels) {
                        throw new IOException(
                           "Incomplete Tellus raster: " + writtenPixels + " of " + expectedPixels + " pixels decoded"
                        );
                     }

                     return raster;
                  }

                  int chunkLength = (firstByte << 24) | (dataIn.readUnsignedByte() << 16) | (dataIn.readUnsignedByte() << 8) | dataIn.readUnsignedByte();
                  if (chunkLength < 0 || chunkLength > MAX_CHUNK_BYTES) {
                     throw new IOException("Invalid Tellus raster chunk length: " + chunkLength);
                  }

                  byte[] chunkBytes = new byte[chunkLength];
                  dataIn.readFully(chunkBytes);
                  writtenPixels += readChunk(new ByteArrayInputStream(chunkBytes), raster);
               }
            }
         }
      }
   }

   private static int readChunk(InputStream input, ShortRaster raster) throws IOException {
      DataInputStream dataIn = new DataInputStream(input);
      int chunkX = dataIn.readInt();
      int chunkY = dataIn.readInt();
      int chunkWidth = dataIn.readInt();
      int chunkHeight = dataIn.readInt();
      TellusRasterReader.RasterFilter filter = TellusRasterReader.RasterFilter.byId(dataIn.readUnsignedByte());
      short[] raw = new short[chunkWidth * chunkHeight];
      try (SingleXZInputStream xzIn = new SingleXZInputStream(input); DataInputStream xzData = new DataInputStream(xzIn)) {
         for (int i = 0; i < raw.length; i++) {
            raw[i] = xzData.readShort();
         }
      }
      applyFilter(filter, raw, chunkWidth, chunkHeight);
      return copyChunk(raster, raw, chunkX, chunkY, chunkWidth, chunkHeight);
   }

   private static void applyFilter(TellusRasterReader.RasterFilter filter, short[] raw, int width, int height) {
      if (filter != TellusRasterReader.RasterFilter.NONE) {
         short[] decoded = new short[raw.length];

         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int index = x + y * width;
               int value = raw[index];
               int left = x > 0 ? decoded[index - 1] : 0;
               int up = y > 0 ? decoded[index - width] : 0;
               int upLeft = x > 0 && y > 0 ? decoded[index - width - 1] : 0;
               decoded[index] = (short)filter.apply(value, left, up, upLeft);
            }
         }

         System.arraycopy(decoded, 0, raw, 0, raw.length);
      }
   }

   /**
    * Copies one chunk and reports how many pixels of {@code raster} it actually filled.
    *
    * <p>2026-10-05: returns the written count so the caller can prove full coverage. Counting is done
    * from the clipped destination coordinates, so pixels the chunk would have landed outside the
    * raster do not count towards it -- an overlapping or out-of-bounds chunk therefore cannot make a
    * partial raster look complete.
    */
   private static int copyChunk(ShortRaster raster, short[] raw, int chunkX, int chunkY, int width, int height) {
      int written = 0;

      for (int y = 0; y < height; y++) {
         int destY = chunkY + y;
         if (destY >= 0 && destY < raster.height()) {
            for (int x = 0; x < width; x++) {
               int destX = chunkX + x;
               if (destX >= 0 && destX < raster.width()) {
                  raster.set(destX, destY, raw[x + y * width]);
                  written++;
               }
            }
         }
      }

      return written;
   }

   private static enum RasterFilter {
      NONE {
         @Override
         int apply(int value, int left, int up, int upLeft) {
            return value;
         }
      },
      LEFT {
         @Override
         int apply(int value, int left, int up, int upLeft) {
            return value + left;
         }
      },
      UP {
         @Override
         int apply(int value, int left, int up, int upLeft) {
            return value + up;
         }
      },
      AVERAGE {
         @Override
         int apply(int value, int left, int up, int upLeft) {
            return value + (left + up) / 2;
         }
      },
      PAETH {
         @Override
         int apply(int value, int left, int up, int upLeft) {
            int estimate = left + up - upLeft;
            int deltaLeft = Math.abs(left - estimate);
            int deltaUp = Math.abs(up - estimate);
            int deltaUpLeft = Math.abs(upLeft - estimate);
            if (deltaLeft < deltaUp && deltaLeft < deltaUpLeft) {
               return value + left;
            } else {
               return deltaUp < deltaUpLeft ? value + up : value + upLeft;
            }
         }
      };

      abstract int apply(int var1, int var2, int var3, int var4);

      static TellusRasterReader.RasterFilter byId(int id) {
         return switch (id) {
            case 1 -> LEFT;
            case 2 -> UP;
            case 3 -> AVERAGE;
            case 4 -> PAETH;
            default -> NONE;
         };
      }
   }
}
