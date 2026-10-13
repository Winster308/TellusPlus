package com.yucareux.tellus.preload;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.MirrorConfig;
import com.yucareux.tellus.config.TellusCnOptions;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Arnis data source preload (Mapterhorn elevation / planetary imagery / planetary DEM).
 *
 * <p>Deliberately separated from tellus source preload: **the cache and ledger of the two data sources do not affect each other**
 * (user requirement: "both data sources' preload needs to be re-downloaded"), cache is at
 * {@code telluscn-cache/arnis/<layer>/<z>/<x>/<y>.ext}, distinguished in the ledger by
 * {@code source=arnis}.
 *
 * <p>All downloads go through the unified multi-threaded download pool ({@link TellusDownloadExecutor}),
 * so selecting a large area won't block the calling thread; tiles already on disk are skipped (no duplicate downloads).
 */
public final class ArnisPreloader {

   private static final int MAX_TILES_PER_LAYER = Integer.getInteger("telluscn.arnis.maxTiles", 4096);

   private ArnisPreloader() {
   }

   /** Preload result. */
   public record Result(int queued, int alreadyOnDisk, int cappedLayers, String detail) {

      public String describe() {
         StringBuilder sb = new StringBuilder();
         sb.append(String.format(Locale.ROOT, "arnis 数据源: 已排队 %d 个瓦片，%d 个已在磁盘上", this.queued, this.alreadyOnDisk));
         if (this.cappedLayers > 0) {
            sb.append(String.format(Locale.ROOT, "（有 %d 个图层因区域过大被截断，请分批选择）", this.cappedLayers));
         }
         return sb.toString();
      }
   }

   /** Whether this layer belongs to the arnis source. */
   public static boolean isArnisLayer(String layer) {
      return ArnisSources.LAYER_MAPTERHORN.equals(layer)
            || ArnisSources.LAYER_PLANET_IMAGERY.equals(layer)
            || ArnisSources.LAYER_PLANET_DEM.equals(layer)
            || ArnisSources.LAYER_MAP_TILES.equals(layer);
   }

   /**
    * Preload a rectangle.
    *
    * @param layers Values see {@link ArnisSources} LAYER_* constants
    */
   public static Result preload(double lat1, double lon1, double lat2, double lon2, Set<String> layers) {
      double minLat = Math.min(lat1, lat2);
      double maxLat = Math.max(lat1, lat2);
      double minLon = Math.min(lon1, lon2);
      double maxLon = Math.max(lon1, lon2);

      String base = MirrorConfig.getActiveDomain();
      if (base.isBlank()) {
         return new Result(0, 0, 0, "arnis 数据源必须走镜像代理，但 mirror.enabled=false 或没有配置任何域名");
      }

      AtomicInteger queued = new AtomicInteger();
      AtomicInteger existed = new AtomicInteger();
      AtomicInteger capped = new AtomicInteger();
      Set<String> used = new LinkedHashSet<>();

      TellusCnOptions.CelestialBody body = TellusCnOptions.celestialBody();

      for (String layer : layers) {
         switch (layer) {
            case ArnisSources.LAYER_MAPTERHORN -> {
               used.add(layer);
               queueSlippy(base, "/mapterhorn", ArnisSources.mapterhornZoom(), "webp",
                     "mapterhorn", minLat, minLon, maxLat, maxLon, queued, existed, capped);
            }
            case ArnisSources.LAYER_PLANET_IMAGERY -> {
               used.add(layer);
               String src = ArnisSources.planetImagerySource(body);
               queueSlippy(base, "/planet/" + src + "/imagery", ArnisSources.planetImageryZoom(), "png",
                     "planet-imagery-" + src, minLat, minLon, maxLat, maxLon, queued, existed, capped);
            }
            case ArnisSources.LAYER_PLANET_DEM -> {
               used.add(layer);
               queuePlanetaryDem(base, body, queued, existed, capped);
            }
            case ArnisSources.LAYER_MAP_TILES -> {
                // Tile template includes a release version, must be fetched from tilejson;
                // cache path matches the rasterizer, so client rendering hits the cache directly.
               String template = ArnisSources.openFreeMapTileTemplate(base);
               if (template != null) {
                  used.add(layer);
                  queueVectorTiles(template, ArnisSources.mapTileZoom(), minLat, minLon, maxLat, maxLon,
                        queued, existed, capped);
               }
            }
            default -> {
            }
         }
      }

      // Record an entry (distinguished by source), for map coloring, area stats, and "no duplicate downloads" check
      TellusPreloadRegistry.mark(minLat, minLon, maxLat, maxLon, used, TellusPreloadRegistry.SOURCE_ARNIS);

      String detail = queued.get() == 0 && existed.get() == 0
            ? "没有可下载的 arnis 图层（检查一下 layers 参数）"
            : "已提交到下载池";
      return new Result(queued.get(), existed.get(), capped.get(), detail);
   }

   // ------------------------------------------------------------------ Tiles

   /** Tile a rectangle with Web Mercator tiles and queue them. */
   private static void queueSlippy(String base, String route, int zoom, String ext, String cacheName,
                                   double minLat, double minLon, double maxLat, double maxLon,
                                   AtomicInteger queued, AtomicInteger existed, AtomicInteger capped) {
      int n = 1 << zoom;
      int x1 = clampTile(tileX(minLon, zoom), n);
      int x2 = clampTile(tileX(maxLon, zoom), n);
      int y1 = clampTile(tileY(maxLat, zoom), n);
      int y2 = clampTile(tileY(minLat, zoom), n);

      Path dir = cacheRoot().resolve(cacheName);
      int count = 0;
      boolean wasCapped = false;
      outer:
      for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
         for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
            if (count >= MAX_TILES_PER_LAYER) {
               wasCapped = true;
               break outer;
            }
            count++;
            Path target = dir.resolve(zoom + "/" + x + "/" + y + "." + ext);
            if (Files.exists(target) && fileSize(target) > 0L) {
               existed.incrementAndGet();
               continue;
            }
            String url = String.format(Locale.ROOT, "%s%s/%d/%d/%d.%s", base, route, zoom, x, y, ext);
            final Path out = target;
            TellusDownloadExecutor.prefetch("arnis-" + cacheName, zoom + "/" + x + "/" + y, ignored -> {
               downloadToFile(url, out);
               return Boolean.TRUE;
            });
            queued.incrementAndGet();
         }
      }
      if (wasCapped) {
         capped.incrementAndGet();
      }
   }

   /** Vector basemap tiles: tile by Web Mercator, persist to the same directory used by the rasterizer. */
   private static void queueVectorTiles(String template, int zoom, double minLat, double minLon,
                                        double maxLat, double maxLon,
                                        AtomicInteger queued, AtomicInteger existed, AtomicInteger capped) {
      int n = 1 << zoom;
      int x1 = clampTile(tileX(minLon, zoom), n);
      int x2 = clampTile(tileX(maxLon, zoom), n);
      int y1 = clampTile(tileY(maxLat, zoom), n);
      int y2 = clampTile(tileY(minLat, zoom), n);
      Path dir = cacheRoot().resolve("openfreemap");
      int count = 0;
      boolean wasCapped = false;
      outer:
      for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
         for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
            if (count >= MAX_TILES_PER_LAYER) {
               wasCapped = true;
               break outer;
            }
            count++;
            Path target = dir.resolve(zoom + "/" + x + "/" + y + ".pbf");
            if (Files.exists(target) && fileSize(target) > 0L) {
               existed.incrementAndGet();
               continue;
            }
            String url = template.replace("{z}", Integer.toString(zoom))
                  .replace("{x}", Integer.toString(x))
                  .replace("{y}", Integer.toString(y));
            final Path out = target;
            TellusDownloadExecutor.prefetch("arnis-openfreemap", zoom + "/" + x + "/" + y, ignored -> {
               downloadToFile(url, out);
               return Boolean.TRUE;
            });
            queued.incrementAndGet();
         }
      }
      if (wasCapped) {
         capped.incrementAndGet();
      }
   }

   /** Planetary DEM: PDS rasters are read whole-file via Range, here we download the whole file. */
   private static void queuePlanetaryDem(String base, TellusCnOptions.CelestialBody body,
                                         AtomicInteger queued, AtomicInteger existed, AtomicInteger capped) {
      if (!ArnisSources.hasPlanetaryDem(body)) {
         return;
      }
      Path dir = cacheRoot().resolve("planet-dem-" + body.id());
      if (body == TellusCnOptions.CelestialBody.MOON) {
         queueDemFile(base, body, dir, ArnisSources.MOON_DEM_FILE, queued, existed);
         return;
      }
      // Mars: 88° × 90° bands, 4 longitude bands × both hemispheres (0/44/88 three latitude levels)
      int files = 0;
      for (int latBand : new int[]{0, 44, 88}) {
         for (boolean north : new boolean[]{true, false}) {
            for (int lonBand : new int[]{0, 90, 180, 270}) {
               if (files >= 16) {
                  capped.incrementAndGet();
                  return;
               }
               files++;
               queueDemFile(base, body, dir, ArnisSources.marsDemFile(latBand, north, lonBand), queued, existed);
            }
         }
      }
   }

   private static void queueDemFile(String base, TellusCnOptions.CelestialBody body, Path dir, String file,
                                    AtomicInteger queued, AtomicInteger existed) {
      Path target = dir.resolve(file);
      if (Files.exists(target) && fileSize(target) > 0L) {
         existed.incrementAndGet();
         return;
      }

      // 2026-10-05: ask the filesystem before queueing. Planetary DEMs are whole-file downloads (the
      // Moon alone is a multi-GB ldem_128.img) and nothing checked that the volume could hold them,
      // so a preload could fill the disk -- which on Windows takes the whole game/server down with
      // it, not just this mod. The 2 GiB margin covers the copy plus the temp file we are about to
      // create next to it.
      long required = expectedDemSize(file);
      if (required > 0L) {
         long usable = usableSpace(dir);
         if (usable >= 0L && usable < required + 2L * 1024 * 1024 * 1024) {
            Tellus.LOGGER.warn(
               "Skipping planetary DEM {}: disk has about {} bytes available, not enough for about {} bytes (plus 2 GiB margin)",
               file, usable, required);
            return;
         }
      }

      String url = base + "/planet/" + body.id() + "/dem/" + file;
      TellusDownloadExecutor.prefetch("arnis-planet-dem", body.id() + "-" + file, ignored -> {
         downloadToFile(url, target);
         return Boolean.TRUE;
      });
      queued.incrementAndGet();
   }

    // ------------------------------------------------------------------ Utilities

   /**
    * Download URL to target file (write .part first, then atomic rename).
    *
    * @return true on success (HTTP 200/206 and persisted); false on failure (caller can use this to write negative cache)
    */
   public static boolean downloadToFile(String url, Path target) {
      Path tmp = null;
      try {
         // 2026-10-05: unique temp name. The old fixed "<name>.part" was shared by every writer of
         // the same target, so two concurrent downloads truncated and interleaved into one file and
         // the final move could promote a half-written result. This is the same class of bug already
         // fixed in ParsedTileCodec, in the shared preloader used by every arnis source.
         Path dir = target.toAbsolutePath().getParent();
         Files.createDirectories(dir);
         tmp = Files.createTempFile(dir, target.getFileName() + ".", ".part");
         HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
         connection.setRequestProperty("User-Agent", "TellusCN/1.0 (Minecraft Mod)");
         connection.setConnectTimeout(15000);
         connection.setReadTimeout(60000);
         int status = connection.getResponseCode();
         if (status != 200 && status != 206) {
               Tellus.LOGGER.debug("arnis preload failed {}: HTTP {}", url, status);
            return false;
         }
         try (InputStream in = connection.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
         }
         moveIntoPlace(tmp, target);
         return true;
      } catch (IOException e) {
            Tellus.LOGGER.debug("arnis preload error {}: {}", url, e.toString());
         return false;
      } finally {
         if (tmp != null) {
            try {
               Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
         }
      }
   }

   /** Arnis source cache root directory. */
   public static Path cacheRoot() {
      return FabricLoader.getInstance().getGameDir().resolve("telluscn-cache/arnis");
   }

   private static long fileSize(Path path) {
      try {
         return Files.size(path);
      } catch (IOException e) {
         return 0L;
      }
   }

   /** Rough on-disk size of a planetary DEM file, or 0 when we do not know it. */
   private static long expectedDemSize(String file) {
      if (ArnisSources.MOON_DEM_FILE.equals(file)) {
         return 2_100_000_000L;
      }

      return file.endsWith(".img") ? 150_000_000L : 0L;
   }

   /** Usable bytes on the volume holding {@code dir}, or -1 when it cannot be determined. */
   private static long usableSpace(Path dir) {
      try {
         Path probe = Files.exists(dir) ? dir : dir.getParent();
         return probe == null ? -1L : Files.getFileStore(probe).getUsableSpace();
      } catch (IOException | RuntimeException e) {
         return -1L;
      }
   }

   /**
    * 2026-10-05: atomic publish with a bounded retry.
    *
    * <p>Windows refuses to replace a file another handle has open, so two downloads of the same
    * target can collide with a transient {@link java.nio.file.AccessDeniedException}; that resolves
    * in milliseconds, so a short backoff turns a hard failure into a success.
    */
   private static void moveIntoPlace(Path temp, Path target) throws IOException {
      IOException lastFailure = null;

      for (int attempt = 0; attempt < 8; attempt++) {
         try {
            try {
               Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
               Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return;
         } catch (java.nio.file.AccessDeniedException targetBusy) {
            lastFailure = targetBusy;

            try {
               Thread.sleep(5L * (attempt + 1));
            } catch (InterruptedException interrupted) {
               Thread.currentThread().interrupt();
               throw targetBusy;
            }
         }
      }

      throw lastFailure;
   }

   static int tileX(double lon, int zoom) {
      int n = 1 << zoom;
      double x = (lon + 180.0) / 360.0 * n;
      return (int) Math.floor(x);
   }

   static int tileY(double lat, int zoom) {
      int n = 1 << zoom;
      double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
      double rad = Math.toRadians(clamped);
      double y = (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n;
      return (int) Math.floor(y);
   }

   private static int clampTile(int value, int n) {
      return Math.max(0, Math.min(n - 1, value));
   }
}
