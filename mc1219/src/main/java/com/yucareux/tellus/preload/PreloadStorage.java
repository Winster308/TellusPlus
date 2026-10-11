package com.yucareux.tellus.preload;

import com.yucareux.tellus.Tellus;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * **Disk usage statistics and deletion** for the preload cache.
 *
 * <p>The caches of the two data sources are independent, and statistics and deletion are also split per source:
 * <ul>
 *   <li>{@code tellus} -- the upstream's own cache directory {@code tellus/cache/*}
 *       (elevation-tellus, worldcover2021, koppen... layout decided by the upstream);</li>
 *   <li>{@code arnis} -- the {@code telluscn-cache/arnis/*} newly created by this mod,
 *       with layout {@code <layer>/<z>/<x>/<y>.ext}, **can be deleted exactly by lat/lon rectangle**.</li>
 * </ul>
 */
public final class PreloadStorage {

   private PreloadStorage() {
   }

   /** One source's usage snapshot. */
   public record Usage(String source, long bytes, int files) {

      public String humanBytes() {
         return formatBytes(this.bytes);
      }
   }

   public static Path tellusCacheRoot() {
      return FabricLoader.getInstance().getGameDir().resolve("tellus/cache");
   }

   /** Usage per source. */
   public static List<Usage> usage() {
      List<Usage> list = new ArrayList<>();
      list.add(sizeOf(TellusPreloadRegistry.SOURCE_TELLUS, tellusCacheRoot()));
      list.add(sizeOf(TellusPreloadRegistry.SOURCE_ARNIS, ArnisPreloader.cacheRoot()));
      return list;
   }

   public static long totalBytes() {
      long total = 0L;
      for (Usage usage : usage()) {
         total += usage.bytes();
      }
      return total;
   }

   /** One-line summary for display in the UI / chat bar. */
   public static String describe() {
      StringBuilder sb = new StringBuilder();
      sb.append("预加载缓存占用: ");
      for (Usage usage : usage()) {
         sb.append(String.format(Locale.ROOT, "%s %s（%d 个文件）; ", usage.source(), usage.humanBytes(), usage.files()));
      }
      sb.append("合计 ").append(formatBytes(totalBytes()));
      sb.append(String.format(Locale.ROOT, "; 账本里共 %d 个区域", TellusPreloadRegistry.regionCount()));
      return sb.toString();
   }

   /**
    * Delete one downloaded region.
    *
    * <p>For the arnis source, cache files are **deleted exactly** by lat/lon rectangle; the tellus source's cache file names are determined by the upstream tile key,
    * here only the ledger record is removed (for the cache itself please use "clear this source cache" or the upstream's own clear-cache button).
    *
    * @return the number of files actually deleted; -1 means the ledger has no such region
    */
   public static int deleteRegion(TellusPreloadRegistry.Region region) {
      boolean removed = TellusPreloadRegistry.removeRegion(region);
      if (!removed) {
         return -1;
      }
      if (!TellusPreloadRegistry.SOURCE_ARNIS.equals(region.source())) {
         return 0;
      }
      return deleteArnisTiles(region);
   }

   /** Clear a source's cache directory. */
   public static int clearSource(String source) {
      Path root = TellusPreloadRegistry.SOURCE_ARNIS.equals(source) ? ArnisPreloader.cacheRoot() : tellusCacheRoot();
      int deleted = deleteTree(root);
      TellusPreloadRegistry.removeRegionsOfSource(source);
      Tellus.LOGGER.info("Cleared cache of data source {}, deleted {} files ({})", source, deleted, root);
      return deleted;
   }

   // ------------------------------------------------------------------ internals

   /** Statistics cap: stop once this many files have been reached (to avoid holding up the calling thread on a huge cache). */
   private static final int MAX_SCAN_FILES = Integer.getInteger("telluscn.preload.maxScanFiles", 200_000);

   private static Usage sizeOf(String source, Path root) {
      if (!Files.isDirectory(root)) {
         return new Usage(source, 0L, 0);
      }
      long[] acc = new long[2];
      try (Stream<Path> walk = Files.walk(root)) {
         walk.filter(Files::isRegularFile).limit(MAX_SCAN_FILES).forEach(path -> {
            try {
               acc[0] += Files.size(path);
               acc[1]++;
            } catch (IOException ignored) {
            }
         });
      } catch (IOException e) {
         Tellus.LOGGER.debug("Failed to compute cache usage for {}: {}", root, e.toString());
      }
      return new Usage(source, acc[0], (int) acc[1]);
   }

   /** Delete tile files in the arnis cache that fall inside the rectangle (tile number -> lat/lon range -> intersect with the rectangle). */
   private static int deleteArnisTiles(TellusPreloadRegistry.Region region) {
      Path root = ArnisPreloader.cacheRoot();
      if (!Files.isDirectory(root)) {
         return 0;
      }
      int deleted = 0;
      try (Stream<Path> walk = Files.walk(root)) {
         List<Path> candidates = walk.filter(Files::isRegularFile).toList();
         for (Path file : candidates) {
            int[] zxy = parseTileCoordinates(root, file);
            if (zxy == null) {
               continue;
            }
            if (tileIntersectsRegion(zxy[0], zxy[1], zxy[2], region)) {
               try {
                  Files.deleteIfExists(file);
                  deleted++;
               } catch (IOException ignored) {
               }
            }
         }
         // Clean up empty directories
         try (Stream<Path> dirs = Files.walk(root)) {
            dirs.filter(Files::isDirectory)
                  .sorted(Comparator.reverseOrder())
                  .forEach(dir -> {
                     try (Stream<Path> children = Files.list(dir)) {
                        if (children.findAny().isEmpty() && !dir.equals(root)) {
                           Files.deleteIfExists(dir);
                        }
                     } catch (IOException ignored) {
                     }
                  });
         }
      } catch (IOException e) {
         Tellus.LOGGER.debug("Failed to delete arnis cache by region: {}", e.toString());
      }
      return deleted;
   }

   /** Parse z/x/y back from {@code <root>/<layer>/<z>/<x>/<y>.<ext>}. */
   private static int[] parseTileCoordinates(Path root, Path file) {
      Path rel = root.relativize(file);
      if (rel.getNameCount() != 4) {
         return null;
      }
      try {
         int z = Integer.parseInt(rel.getName(1).toString());
         int x = Integer.parseInt(rel.getName(2).toString());
         String name = rel.getName(3).toString();
         int dot = name.lastIndexOf('.');
         int y = Integer.parseInt(dot > 0 ? name.substring(0, dot) : name);
         return new int[]{z, x, y};
      } catch (NumberFormatException e) {
         return null;
      }
   }

   /** Whether the lat/lon range covered by the tile (z/x/y) intersects the rectangle. */
   private static boolean tileIntersectsRegion(int z, int x, int y, TellusPreloadRegistry.Region region) {
      double n = Math.pow(2.0, z);
      double tileMinLon = x / n * 360.0 - 180.0;
      double tileMaxLon = (x + 1) / n * 360.0 - 180.0;
      double tileMaxLat = tileLat(y, n);
      double tileMinLat = tileLat(y + 1, n);
      return tileMaxLat >= region.minLat() && tileMinLat <= region.maxLat()
            && tileMaxLon >= region.minLon() && tileMinLon <= region.maxLon();
   }

   private static double tileLat(double y, double n) {
      double rad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n)));
      return Math.toDegrees(rad);
   }

   private static int deleteTree(Path root) {
      if (!Files.isDirectory(root)) {
         return 0;
      }
      int[] deleted = new int[1];
      try (Stream<Path> walk = Files.walk(root)) {
         walk.sorted(Comparator.reverseOrder()).forEach(path -> {
            try {
               if (Files.deleteIfExists(path)) {
                  deleted[0]++;
               }
            } catch (IOException ignored) {
            }
         });
      } catch (IOException e) {
         Tellus.LOGGER.debug("Failed to clear cache directory {}: {}", root, e.toString());
      }
      return deleted[0];
   }

   public static String formatBytes(long bytes) {
      if (bytes < 1024L) {
         return bytes + " B";
      }
      double kb = bytes / 1024.0;
      if (kb < 1024.0) {
         return String.format(Locale.ROOT, "%.0f KB", kb);
      }
      double mb = kb / 1024.0;
      if (mb < 1024.0) {
         return String.format(Locale.ROOT, "%.1f MB", mb);
      }
      return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
   }
}
