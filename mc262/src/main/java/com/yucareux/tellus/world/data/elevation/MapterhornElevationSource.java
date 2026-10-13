package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.MirrorConfig;
import com.yucareux.tellus.preload.ArnisPreloader;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The global elevation source of arnis: **Mapterhorn** ({@code tiles.mapterhorn.com}).
 *
 * <h2>Specification (matches arnis {@code src/elevation/providers/mapterhorn.rs})</h2>
 * <ul>
 *   <li>512x512 **lossless WebP**, elevations encoded with **terrarium**:
 *       {@code h = R*256 + G + B/256 - 32768} (metres);</li>
 *   <li>zoom 6 to 17 (z<=12 comes from GLO-30, z13+ is national LiDAR);</li>
 *   <li>A missing tile answers 404 (**including every pure-ocean tile**), falling back up the pyramid, and 404s are negatively cached on disk.</li>
 * </ul>
 *
 * <h2>The two key points on the Java side</h2>
 * <ol>
 *   <li>ImageIO in Java 21 does **not** read WebP, so this mod bundles the pure Java
 *       TwelveMonkeys {@code imageio-webp}, which makes {@link ImageIO#read} work directly;</li>
 *   <li>Tiles land in {@code telluscn-cache/arnis/mapterhorn/...} and share one and the
 *       same cache as the preloader ({@link ArnisPreloader}): a tile that was preloaded is
 *       hit directly during generation and never downloaded again.</li>
 * </ol>
 *
 * <p>Decoded tiles are cached as {@code short[]} (metres, clamped to +/-32767), 512x512x2B is about 512 KB per tile.
 */
public final class MapterhornElevationSource {

   private static final int TILE_PX = 512;
   private static final double TERRARIUM_OFFSET = 32768.0;
   static final int MIN_ZOOM = 6;
   static final int MAX_ZOOM = 17;
   /** Upper bound on decoded tiles held in memory. At 512 KB each the default cap is about 64 MB. */
   private static final int MEMORY_TILES = Integer.getInteger("telluscn.arnis.mapterhornMemoryTiles", 128);
   /** How long a failed tile stays blacklisted before we retry it. */
   private static final long MISSING_TTL_SECONDS = longProperty("telluscn.arnis.mapterhornMissingTtlSeconds", 300L);

   private static final double EQUATOR_METERS = 40075016.686;
   private static final short NO_DATA = Short.MIN_VALUE;

   private static final LoadingCache<Long, short[]> TILES = CacheBuilder.newBuilder()
         .maximumSize(MEMORY_TILES)
         .concurrencyLevel(Math.max(8, Runtime.getRuntime().availableProcessors() * 4))
         .build(new CacheLoader<Long, short[]>() {
            @Override
            public short[] load(Long key) {
               return loadTile(key);
            }
         });

   /** Probes that remember that a tile is confirmed absent, to avoid repeated 404s. */
   private static final AtomicBoolean WARNED_NO_PROXY = new AtomicBoolean();

   private MapterhornElevationSource() {
   }

   /**
    * Samples an elevation in metres. Returns {@code null} when the data cannot be fetched, and the caller falls back to the original chain.
    *
    * @param latitude  latitude in degrees
    * @param longitude longitude in degrees
    * @param worldScale metres per block, used to pick the zoom so the tile pixel scale stays close to the block scale
    */
   public static Double sample(double latitude, double longitude, double worldScale) {
      if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
         return null;
      }
      if (Math.abs(latitude) > 85.0) {
         // The polar regions are outside Web Mercator coverage
         return null;
      }
      int zoom = chooseZoom(latitude, worldScale);
      // Following arnis: when this level has nothing, walk up the pyramid parents until MIN_ZOOM.
      // The whole walk only reads the memory and disk caches and never downloads here (see the comment on getTile:
      // a synchronous download on the generation thread stalls a single tick for seconds and cascades into "stuck in the air / daylight mob spawning").
      // A tile that misses is only prefetched asynchronously, while this call falls back to the original elevation chain.
      for (int z = zoom; z >= MIN_ZOOM; z--) {
         int n = 1 << z;
         double normX = (longitude + 180.0) / 360.0;
         double latRad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, latitude)));
         double normY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0;
         int tileX = (int) Math.floor(normX * n);
         int tileY = (int) Math.floor(normY * n);
         short[] tile = tileCached(z, clamp(tileX, n), clamp(tileY, n), true);
         if (tile == null) {
            continue;
         }
         // Normalised tile coordinates to pixels (bilinear)
         double px = normX * n - tileX;
         double py = normY * n - tileY;
         double fx = Math.max(0.0, Math.min(TILE_PX - 1.001, px * TILE_PX));
         double fy = Math.max(0.0, Math.min(TILE_PX - 1.001, py * TILE_PX));
         int x0 = (int) fx;
         int y0 = (int) fy;
         int x1 = Math.min(TILE_PX - 1, x0 + 1);
         int y1 = Math.min(TILE_PX - 1, y0 + 1);
         double dx = fx - x0;
         double dy = fy - y0;
         double v00 = tile[y0 * TILE_PX + x0];
         double v10 = tile[y0 * TILE_PX + x1];
         double v01 = tile[y1 * TILE_PX + x0];
         double v11 = tile[y1 * TILE_PX + x1];
         if (v00 == NO_DATA || v10 == NO_DATA || v01 == NO_DATA || v11 == NO_DATA) {
            return null;
         }
         return (v00 * (1 - dx) + v10 * dx) * (1 - dy) + (v01 * (1 - dx) + v11 * dx) * dy;
      }
      return null;
   }

   /**
    * Whether a cached tile is already available near this coordinate (memory and disk only, never downloads).
    *
    * <p>Purpose: the caller picks one elevation source per chunk so two sources never mix
    * inside a chunk, because that makes neighbouring blocks differ in height, which shows up as "buildings with missing limbs, or growing spires".
    */
   public static boolean hasCachedCoverage(double latitude, double longitude, double worldScale) {
      if (Double.isNaN(latitude) || Double.isNaN(longitude) || Math.abs(latitude) > 85.0) {
         return false;
      }
      int zoom = chooseZoom(latitude, worldScale);
      for (int z = zoom; z >= MIN_ZOOM; z--) {
         int n = 1 << z;
         double normX = (longitude + 180.0) / 360.0;
         double latRad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, latitude)));
         double normY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0;
         int tx = clamp((int) Math.floor(normX * n), n);
         int ty = clamp((int) Math.floor(normY * n), n);
         if (tileCached(z, tx, ty, true) != null) {
            return true;
         }
      }
      return false;
   }

   /** Keeps the tile pixel scale close to the block scale: a larger z is finer. */
   static int chooseZoom(double latitude, double worldScale) {
      double metersPerBlock = worldScale > 0.0 ? worldScale : 30.0;
      double metersPerPixelTarget = Math.max(1.0, metersPerBlock);
      double cos = Math.max(0.05, Math.cos(Math.toRadians(latitude)));
      double tilesAtEquator = EQUATOR_METERS * cos / (TILE_PX * metersPerPixelTarget);
      int zoom = (int) Math.round(Math.log(tilesAtEquator) / Math.log(2.0));
      return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
   }

   /**
    * Fetches one tile: **memory then disk**, and on a miss it optionally prefetches asynchronously and returns null.
    *
    * @param prefetchOnMiss true = queue the download into the background pool on a miss, without blocking the calling thread
    */
   static short[] tileCached(int z, int x, int y, boolean prefetchOnMiss) {
      long key = tileKey(z, x, y);
      short[] cached = TILES.getIfPresent(key);
      if (cached != null) {
         return cached == EMPTY ? null : cached;
      }
      // Disk only, never online: the network branch of loadTile is split out separately
      Path file = ArnisPreloader.cacheRoot().resolve("mapterhorn").resolve(z + "/" + x + "/" + y + ".webp");
      Path missing = file.resolveSibling(y + ".missing");
      if (!Files.exists(file)) {
         if (Files.exists(missing)) {
            TILES.put(key, EMPTY);
            return null;
         }
         if (prefetchOnMiss) {
            prefetchAsync(z, x, y, file);
         }
         return null;
      }
      short[] decoded = decodeTile(file);
      TILES.put(key, decoded == null ? EMPTY : decoded);
      return decoded;
   }

   /** Queues the download into the background pool. The same tile is only queued once, the pool de-duplicates. */
   private static void prefetchAsync(int z, int x, int y, Path file) {
      String base = MirrorConfig.getActiveDomain();
      if (base.isBlank()) {
         return;
      }
      String url = base + "/mapterhorn/" + z + "/" + x + "/" + y + ".webp";
      TellusDownloadExecutor.prefetch("arnis-mapterhorn-prefetch", z + "/" + x + "/" + y,
            ignored -> ArnisPreloader.downloadToFile(url, file));
   }

   private static final short[] EMPTY = new short[0];

   private static long tileKey(int z, int x, int y) {
      return ((long) z << 58) | ((long) x << 29) | y;
   }

   private static short[] loadTile(long key) {
      int z = (int) (key >>> 58);
      int x = (int) ((key >>> 29) & 0x1FFFFFFF);
      int y = (int) (key & 0x1FFFFFFF);
      Path file = ArnisPreloader.cacheRoot().resolve("mapterhorn").resolve(z + "/" + x + "/" + y + ".webp");
      Path missing = file.resolveSibling(y + ".missing");

      if (!Files.exists(file) && Files.exists(missing) && isMarkerFresh(missing)) {
         return EMPTY; // confirmed absent recently (pure-ocean tiles and the like)
      }
      if (!Files.exists(file)) {
         String base = MirrorConfig.getActiveDomain();
         if (base.isBlank()) {
            if (WARNED_NO_PROXY.compareAndSet(false, true)) {
               Tellus.LOGGER.warn("Mapterhorn needs a mirror proxy but no mirror host is configured, falling back to the original elevation chain");
            }
            return EMPTY;
         }
         String url = base + "/mapterhorn/" + z + "/" + x + "/" + y + ".webp";
         // Go through the shared multi-threaded download pool with single-flight: one tile downloads once even under concurrency
         try {
            Boolean ok = TellusDownloadExecutor.load("arnis-mapterhorn", z + "/" + x + "/" + y,
                  ignored -> ArnisPreloader.downloadToFile(url, file));
            if (ok == null || !ok) {
               writeMarker(missing);
               return EMPTY;
            }
         } catch (Exception e) {
            Tellus.LOGGER.debug("Mapterhorn download failed z{}/{}/{}: {}", z, x, y, e.toString());
            writeMarker(missing);
            return EMPTY;
         }
      }
      short[] decoded = decodeTile(file);
      return decoded == null ? EMPTY : decoded;
   }

   /**
    * Decodes an already present WebP into an elevation array (pure CPU, it does not decide whether to download).
    *
    * <p>Split out specifically for the cache-only path: the generation thread only decodes and never touches the network.
    */
   private static short[] decodeTile(Path file) {
      BufferedImage image = readWebp(file);
      if (image == null || image.getWidth() != TILE_PX || image.getHeight() != TILE_PX) {
         Tellus.LOGGER.debug("Mapterhorn tile decode failed or has unexpected dimensions: {}", file);
         return null;
      }
      short[] result = new short[TILE_PX * TILE_PX];
      for (int row = 0; row < TILE_PX; row++) {
         for (int col = 0; col < TILE_PX; col++) {
            int rgb = image.getRGB(col, row);
            double r = (rgb >> 16) & 0xFF;
            double g = (rgb >> 8) & 0xFF;
            double b = rgb & 0xFF;
            double meters = (r * 256.0 + g + b / 256.0) - TERRARIUM_OFFSET;
            result[row * TILE_PX + col] = (short) Math.max(Short.MIN_VALUE + 1,
                  Math.min(Short.MAX_VALUE, Math.round(meters)));
         }
      }
      return result;
   }

   private static BufferedImage readWebp(Path file) {
      try (InputStream in = Files.newInputStream(file)) {
         return ImageIO.read(in);
      } catch (IOException e) {
         Tellus.LOGGER.debug("WebP decode failed {}: {}", file, e.toString());
         return null;
      }
   }

   private static void writeMarker(Path missing) {
      try {
         Files.createDirectories(missing.getParent());
         Files.writeString(missing, Long.toString(System.currentTimeMillis()));
      } catch (IOException ignored) {
      }
   }

   /**
    * Whether a {@code .missing} marker is still young enough to be trusted.
    *
    * <p>★★ 2026-10-04: the marker originally **never expired** and the timestamp in the file was **never read** ✗
    * ⇒ one transient network hiccup / one offline period (an IOException thrown by {@code TellusNetworkGate} takes the same catch ✗)
    * would black-list this tile on disk **permanently**, never to be retried again ✗.
    *
    * <p>Now it expires according to {@code telluscn.arnis.mapterhornMissingTtlSeconds} (default 300s) ✓:
    * once the marker expires it is deleted and the tile is downloaded again ✓; pure-ocean tiles merely retry once every 5 minutes, a negligible cost ✓.
    * Aligned with the TTL negative-cache semantics of the OSM / landcover sources ✓.
    *
    * @return {@code true} = the marker is still fresh and the download should be skipped because of it
    */
   private static boolean isMarkerFresh(Path missing) {
      long ttlMillis = MISSING_TTL_SECONDS * 1000L;
      try {
         long writtenAt = Long.parseLong(Files.readString(missing).trim());
         if (System.currentTimeMillis() - writtenAt < ttlMillis) {
            return true;
         }
         Files.deleteIfExists(missing);
         return false;
      } catch (IOException | NumberFormatException expired) {
         // If it cannot be read (truncated file / non-numeric), treat it as expired so a single re-download self-heals it ✓
         try {
            Files.deleteIfExists(missing);
         } catch (IOException ignored) {
         }
         return false;
      }
   }

   private static int clamp(int value, int n) {
      return Math.max(0, Math.min(n - 1, value));
   }

   private static long longProperty(String key, long defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         return Math.max(0L, Long.parseLong(value));
      } catch (NumberFormatException ignored) {
         return defaultValue;
      }
   }

   /** Drops the decoded tile cache in memory (call after changing the elevation source). */
   public static void invalidate() {
      TILES.invalidateAll();
   }
}
