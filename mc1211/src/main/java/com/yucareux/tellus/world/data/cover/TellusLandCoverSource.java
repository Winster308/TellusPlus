package com.yucareux.tellus.world.data.cover;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.TellusEndpointConfig;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.zip.InflaterInputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;

public final class TellusLandCoverSource implements TellusCacheHandle {
   private static final double MIN_LAT = -60.0;
   private static final double MAX_LAT = 84.0;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final int TILE_DEGREES = 3;
   private static final int SNOW_ICE_CLASS = 70;
   private static final int WATER_CLASS = 80;
   private static final int MANGROVES_CLASS = 95;
   private static final int BUILT_UP_CLASS = 50;
   private static final int NO_DATA_CLASS = 0;
   //  2026-09-29: 64 down to 16, and these are <b>measured numbers, not estimates</b>.
   //  The agent pulled real tiles from S3 with `Range: 0-131071` and parsed the TIFF header itself: the inner
   //  tiles are 1024x1024, that is 1 MiB, not the 256x256 / 64KB I assumed last round, which was off by 16x.
   //  Every land cover tile is therefore 32 inner tiles of 1 MiB, that is 32 MiB.
   //  At 64 tiles the worst case is 2 GiB, while this machine has only 15.9 GB of RAM and the JVM heap is capped at 1G.
   //  16 tiles means a worst case of 512 MiB and 16 file handles, which is fine.
   //  Why lower `cacheTiles` and not `tileCacheEntries`:
   //  scanning is row first, so the cache only has to hold the blocks a single row spans, which at `ws=10000`
   //  is 26 blocks per row, so `tileCacheEntries=32` is already enough and lowering it would wreck the hit rate.
   //  Fallback: -Dtellus.landcover.cacheTiles=N
   private static final int MAX_CACHE_TILES = intProperty("tellus.landcover.cacheTiles", 16);
   private static final double RESOLUTION_METERS = 10.0;
   private static final int TILE_CACHE_ENTRIES = intProperty("tellus.landcover.tileCacheEntries", 32);
   private static final int SMOOTH_RADIUS_PIXELS = 1;
   private static final ThreadLocal<TellusLandCoverSource.CoverSmoothScratch> COVER_SMOOTH_SCRATCH = ThreadLocal.withInitial(
      TellusLandCoverSource.CoverSmoothScratch::new
   );
   private static final ThreadLocal<TellusLandCoverSource.CoverBlendScratch> COVER_BLEND_SCRATCH = ThreadLocal.withInitial(
      TellusLandCoverSource.CoverBlendScratch::new
   );
   private static final String DEFAULT_ENDPOINT = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map";

   /**
    * Connect timeout: how long to wait for the TCP/TLS handshake. Short, because a host that does not
    * answer a connection within seconds is not going to answer this request either.
    */
   private static final int CONNECT_TIMEOUT_MS = 8000;

   /**
    * Read timeout: how long a single read may stall.
    *
    * <p>2026-10-11: this was 8000 ms for the read as well, and that is the cause of the
    * {@code TellusLandCoverSource} circuit breaker trips seen in the 2026-10-11 11:55 log
    * ({@code 8 consecutive download failures ... latest: TileKey[lat=-15, lon=108]}).
    *
    * <p>An ESA WorldCover tile is a GeoTIFF of roughly 100-150 MB. At a realistic 2-4 MB/s a single
    * tile needs 30-75 seconds of continuous transfer, and {@code HttpURLConnection}'s read timeout
    * applies to <b>each read call</b>, not to the whole transfer -- so a healthy download that happens
    * to stall for 8 seconds mid-stream (a normal thing on a congested link) is aborted and counted as
    * a failure. Eight of those in a row open the circuit for 120 s, during which every land cover
    * sample silently falls back -- which is exactly the "flat plate / single colour" the preview was
    * showing, because the fallback is {@code ESA_NO_DATA}.
    *
    * <p>Raised to 30 s: long enough to survive a congested link, still bounded so a dead peer cannot
    * hold a download thread forever. {@code -Dtellus.landcover.readTimeoutMs} overrides it.
    */
   private static final int READ_TIMEOUT_MS =
      Integer.getInteger("tellus.landcover.readTimeoutMs", 30000);

   // Lazy initialization so the configuration is loaded first.
   private static String getEndpoint() {
      return TellusEndpointConfig.getLandCoverEndpoint(DEFAULT_ENDPOINT);
   }
   private static final String TILE_PATTERN = "ESA_WorldCover_10m_2021_v200_%s_Map.tif";
   private final Path cacheRoot = FabricLoader.getInstance().getGameDir().resolve("tellus/cache/worldcover2021");
   // Tile resolution cache (2026-09-21, the same technique as on the DEM side).
   //   sampleVisualCoverClass takes the centre and the four corners at every point, and every corner costs one
   //   getTile, that is a Guava lookup. A land cover tile spans 3 x 3 degrees rather than the DEM 1 degree, so the
   //   hit rate on the same tile is higher.
   //   Approach: remember the tile resolved last on each thread and reuse it when the key (TileKey plus source) matches.
   //   The semantics are fully equivalent: the returned value is identical to the three getTile* methods including
   private final ThreadLocal<TellusLandCoverSource.CoverTileSlots> lastTile =
      ThreadLocal.withInitial(TellusLandCoverSource.CoverTileSlots::new);

   // 2026-09-29: the single slot table above becomes <b>32 directly addressed slots per thread</b>.
   //   A single slot only remembers "the last one", but callers switch back and forth between
   //   `sampleCoverClassForTerrainShell` / `sampleVisualCoverClassForTerrainShell`
   //   several sources at every point, all of them "memoryOnly(source=2) first, then localOnly(source=1)",
   //   plus the visual sampling that adds online(source=0), so the source differs between neighbouring points and
   //   the single slot hit rate collapses to zero, with a fresh TileKey plus a Guava lookup for every sample.
   //   Using 32 slots (a power of two, the index is a hash of lat, lon and source) means the active (tile, source)
   //   combinations within one ring of samples are only 2 to 4, so nearly everything hits.
   //   The decision result is bit for bit unchanged: a hit only decides whether to resolve the tile once more,
   //   the three getTile* methods are untouched, and the returned value is exactly the same including
   //   GeoTiffTile.MISSING and null. clearCache() now also bumps the cacheGeneration below, because once there
   //   are more slots, a tile another thread already closed must be invalidated everywhere, and the old single
   //   slot only cleared the calling thread copy. That hole is closed here.
   /** Length of the directly addressed table, it must be a power of two because the index is computed as `& (length-1)`. */
   private static final int COVER_TILE_SLOT_COUNT = 32;
   /** Bumped by clearCache() so the possibly already closed tile is invalidated in the slots of <b>all</b> threads at once. */
   private static final java.util.concurrent.atomic.AtomicInteger CACHE_GENERATION = new java.util.concurrent.atomic.AtomicInteger();

   /**
    * A per-thread table of the "land cover tile resolved most recently".
    *
    * <p>It stores ints (lat, lon, source, generation) rather than a {@code TileKey}, so a hit is <b>zero allocation</b>.
    * source: 0 = online, 1 = local disk only, 2 = memory only.
    *
    * <p>{@code probe} records whether it hit into {@code probedHit}, because
    * {@code null} is itself a legitimate cached value, since the memory-only path caches a null, so
    * the return value alone cannot tell a miss from a hit on a tile that has no data.
    */
   private static final class CoverTileSlots {
      private final int[] lats = new int[COVER_TILE_SLOT_COUNT];
      private final int[] lons = new int[COVER_TILE_SLOT_COUNT];
      private final int[] sources = new int[COVER_TILE_SLOT_COUNT];
      private final int[] generations = new int[COVER_TILE_SLOT_COUNT];
      private final TellusLandCoverSource.GeoTiffTile[] tiles = new TellusLandCoverSource.GeoTiffTile[COVER_TILE_SLOT_COUNT];
      private boolean probedHit;

      private CoverTileSlots() {
         // All -1 means a slot that stored nothing can never hit by accident, because lat=0, lon=0, source=0 is a real tile.
         java.util.Arrays.fill(this.sources, -1);
         java.util.Arrays.fill(this.generations, -1);
      }

      private static int slotFor(int tileLat, int tileLon, int source) {
         return (tileLat * 23 + tileLon * 5 + source * 7) & (COVER_TILE_SLOT_COUNT - 1);
      }

      private TellusLandCoverSource.GeoTiffTile probe(int tileLat, int tileLon, int source, int generation) {
         int slot = slotFor(tileLat, tileLon, source);
         if (this.generations[slot] == generation && this.lats[slot] == tileLat && this.lons[slot] == tileLon && this.sources[slot] == source) {
            this.probedHit = true;
            return this.tiles[slot];
         }

         this.probedHit = false;
         return null;
      }

      private void store(int tileLat, int tileLon, int source, int generation, TellusLandCoverSource.GeoTiffTile tile) {
         int slot = slotFor(tileLat, tileLon, source);
         this.lats[slot] = tileLat;
         this.lons[slot] = tileLon;
         this.sources[slot] = source;
         this.generations[slot] = generation;
         this.tiles[slot] = tile;
      }

      private void clear() {
         java.util.Arrays.fill(this.sources, -1);
         java.util.Arrays.fill(this.generations, -1);
         java.util.Arrays.fill(this.tiles, null);
      }
   }

   private final LoadingCache<TellusLandCoverSource.TileKey, TellusLandCoverSource.GeoTiffTile> cache = CacheBuilder.newBuilder()
      .maximumSize(MAX_CACHE_TILES)
      // OPT-2: exactly like the DEM, land cover is queried column by column, so the default concurrency of 4 becomes a hot lock.
      .concurrencyLevel(Math.max(8, Runtime.getRuntime().availableProcessors() * 4))
      .removalListener(notification -> {
         TellusLandCoverSource.GeoTiffTile tile = (TellusLandCoverSource.GeoTiffTile)notification.getValue();
         if (tile != null) {
            tile.close();
         }
      })
      .build(new CacheLoader<TellusLandCoverSource.TileKey, TellusLandCoverSource.GeoTiffTile>() {
         public TellusLandCoverSource.GeoTiffTile load(TellusLandCoverSource.TileKey key) throws Exception {
            return TellusDownloadExecutor.load("TellusLandCoverSource", key, TellusLandCoverSource.this::loadTile);
         }
      });

   /**
    * 2026-09-28: a short lived negative cache for load failures.
    *
    * <p><b>Real incident</b> (around 2026-09-28 21:00): the log printed <b>17328 lines</b> in six minutes,
    * {@code Failed to load land cover tile TileKey[lat=0, lon=0]}, the same tile failing <b>more than 1200 times</b>
    * (the tiles at the equator and on the prime meridian), while the log also reported
    * {@code Can't keep up! Running 10318ms or 206 ticks behind}, so <b>the server froze completely</b>.
    *
    * <p><b>Why it retried forever</b>: Guava's {@code LoadingCache.get()} <b>does not cache exceptions</b>, so
    * after a failure the cache holds nothing, the next sample downloads again, fails again and logs again, and so on.
    * Dozens of lines per second, each carrying a full stack trace, so the log IO itself became the bottleneck.
    *
    * <p><b>Now</b>: after a failure the key goes into this short TTL table and
    * inside the TTL it returns {@code MISSING} <b>without retrying</b>, which breaks the retry loop, and
    * a tile that later recovers, because a fresh download succeeds, also recovers by itself once the TTL expires.
    *
    * <p>TTL: {@code -Dtellus.landcover.failureTtlSeconds=N} (60 seconds by default, 0 restores the old behaviour).
    */
   private static final int LOAD_FAILURE_TTL_SECONDS = intProperty("tellus.landcover.failureTtlSeconds", 60);
   private static final int LOAD_FAILURE_LOG_TTL_SECONDS = intProperty("tellus.landcover.failureLogTtlSeconds", 300);
   private final com.google.common.cache.Cache<TellusLandCoverSource.TileKey, Boolean> loadFailures =
      LOAD_FAILURE_TTL_SECONDS > 0
         ? CacheBuilder.newBuilder().maximumSize(1024).expireAfterWrite(LOAD_FAILURE_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS).build()
         : CacheBuilder.newBuilder().maximumSize(1024).build();
   /** Rate limits the failure log of one tile: only the first line inside the TTL, so a future "any tile" flood cannot spam the log. */
   private final com.google.common.cache.Cache<TellusLandCoverSource.TileKey, Boolean> loadFailureLogged =
      LOAD_FAILURE_LOG_TTL_SECONDS > 0
         ? CacheBuilder.newBuilder().maximumSize(1024).expireAfterWrite(LOAD_FAILURE_LOG_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS).build()
         : CacheBuilder.newBuilder().maximumSize(1024).build();

   public TellusLandCoverSource() {
      TellusCacheRegistry.register(this);
   }

   public boolean isSnowIce(double blockX, double blockZ, double worldScale) {
      return this.sampleCoverClass(blockX, blockZ, worldScale) == SNOW_ICE_CLASS;
   }

   public int sampleCoverClass(double blockX, double blockZ, double worldScale) {
      return this.sampleCoverClass(blockX, blockZ, worldScale, worldScale);
   }

   public int sampleCoverClass(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      return this.sampleCoverClass(blockX, blockZ, worldScale, previewResolutionMeters, false);
   }

   public int sampleCoverClassLocalOnly(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      return this.sampleCoverClass(blockX, blockZ, worldScale, previewResolutionMeters, true);
   }

   public int sampleCoverClassMemoryOnly(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      if (worldScale <= 0.0) {
         return Integer.MIN_VALUE;
      } else {
         int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
         if (step > 1) {
            blockX = downsampleBlock(blockX, step);
            blockZ = downsampleBlock(blockZ, step);
         }

         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = EarthProjection.wrapLongitude(blockX / blocksPerDegree);
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.sampleCoverClassAtLonLatMemoryOnly(lon, lat);
      }
   }

   private int sampleCoverClass(double blockX, double blockZ, double worldScale, double previewResolutionMeters, boolean localOnly) {
      if (worldScale <= 0.0) {
         return 0;
      } else {
         int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
         if (step > 1) {
            blockX = downsampleBlock(blockX, step);
            blockZ = downsampleBlock(blockZ, step);
         }

         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = EarthProjection.wrapLongitude(blockX / blocksPerDegree);
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return localOnly ? this.sampleCoverClassAtLonLatLocalOnly(lon, lat) : this.sampleCoverClassAtLonLat(lon, lat);
      }
   }

   public int sampleSmoothedCoverClass(double blockX, double blockZ, double worldScale) {
      return this.sampleSmoothedCoverClass(blockX, blockZ, worldScale, worldScale);
   }

   public int sampleSmoothedCoverClass(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      if (worldScale <= 0.0) {
         return 0;
      } else {
         int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
         if (step > 1) {
            blockX = downsampleBlock(blockX, step);
            blockZ = downsampleBlock(blockZ, step);
         }

         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = EarthProjection.wrapLongitude(blockX / blocksPerDegree);
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.sampleSmoothedCoverClassAtLonLat(lon, lat, SMOOTH_RADIUS_PIXELS);
      }
   }

   public int sampleVisualCoverClass(double blockX, double blockZ, double worldScale) {
      return this.sampleVisualCoverClass(blockX, blockZ, worldScale, worldScale);
   }

   public int sampleVisualCoverClass(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      return this.sampleVisualCoverClass(blockX, blockZ, worldScale, previewResolutionMeters, false);
   }

   public int sampleVisualCoverClassLocalOnly(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      return this.sampleVisualCoverClass(blockX, blockZ, worldScale, previewResolutionMeters, true);
   }

   public int sampleVisualCoverClassMemoryOnly(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      if (worldScale <= 0.0) {
         return Integer.MIN_VALUE;
      } else {
         int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
         if (step > 1) {
            blockX = downsampleBlock(blockX, step);
            blockZ = downsampleBlock(blockZ, step);
         }

         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = EarthProjection.wrapLongitude(blockX / blocksPerDegree);
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.sampleVisualCoverClassAtLonLatMemoryOnly(
            lon, lat, blockX, blockZ, effectiveSampleResolutionMeters(worldScale, previewResolutionMeters)
         );
      }
   }

   private int sampleVisualCoverClass(double blockX, double blockZ, double worldScale, double previewResolutionMeters, boolean localOnly) {
      if (worldScale <= 0.0) {
         return 0;
      } else {
         int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
         if (step > 1) {
            blockX = downsampleBlock(blockX, step);
            blockZ = downsampleBlock(blockZ, step);
         }

         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = EarthProjection.wrapLongitude(blockX / blocksPerDegree);
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return localOnly
            ? this.sampleVisualCoverClassAtLonLatLocalOnly(lon, lat, blockX, blockZ, effectiveSampleResolutionMeters(worldScale, previewResolutionMeters))
            : this.sampleVisualCoverClassAtLonLat(lon, lat, blockX, blockZ, effectiveSampleResolutionMeters(worldScale, previewResolutionMeters));
      }
   }

   private int sampleCoverClassAtLonLat(double lon, double lat) {
      return this.sampleCoverClassAtLonLatWithTile(lon, lat, 0);
   }

   private int sampleCoverClassAtLonLatLocalOnly(double lon, double lat) {
      return this.sampleCoverClassAtLonLatWithTile(lon, lat, 1);
   }

   private int sampleCoverClassAtLonLatMemoryOnly(double lon, double lat) {
      return this.sampleCoverClassAtLonLatWithTile(lon, lat, 2);
   }

   /**
    * The shared implementation behind the three {@code *AtLonLat} methods: <b>zero allocation</b> on a thread local "last tile" hit.
    *
    * <p>Originally every call first built a {@code TileKey} with {@code tileKeyForLonLat(lon, lat)} and then compared it
    * against the cache. Land cover is sampled column after column, so neighbouring points nearly always land on the
    * same 1 degree tile, which made {@code tileKeyForLonLat} one of the top allocators on the hot path in JFR (261 samples).
    * Now the tile corner (two ints) is computed and compared with the cache fields first, and a TileKey is only built
    *
    * <p>The bounds check is deliberately written in the same negated form as {@code tileKeyForLonLat} so that
    * the path taken for {@code NaN} stays identical as well, since both keep going and return TileKey(0,0) rather than NO_DATA.
    */
   private int sampleCoverClassAtLonLatWithTile(double lon, double lat, int source) {
      boolean hasTile = !(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON);
      if (!hasTile) {
         return source == 2 ? Integer.MIN_VALUE : NO_DATA_CLASS;
      }

      int tileLat = (int)Math.floor(lat / TILE_DEGREES) * TILE_DEGREES;
      int tileLon = (int)Math.floor(lon / TILE_DEGREES) * TILE_DEGREES;
      TellusLandCoverSource.CoverTileSlots cached = this.lastTile.get();
      int generation = CACHE_GENERATION.get();
      // Compare the ints held in the cache instead of building a new TileKey first, which is <b>zero allocation</b> on a hit.
      // The other path, resolveTileAtLonLat, writes the same table with the same index algorithm, so the two copies cannot disagree.
      //
      // Only a hit with a non null tile returns directly, because the memory-only path (source=2) caches a null and
      // the original three methods applied `tile == null ? MIN_VALUE : ...` at their own call sites; folding that
      // into a single check here is required, otherwise the cache hit branch would NPE.
      TellusLandCoverSource.GeoTiffTile hit = cached.probe(tileLat, tileLon, source, generation);
      if (cached.probedHit && hit != null) {
         return hit.sample(lon, lat);
      }

      TellusLandCoverSource.TileKey key = new TellusLandCoverSource.TileKey(tileLat, tileLon);
      TellusLandCoverSource.GeoTiffTile resolved = switch (source) {
         case 1 -> this.getTileLocalOnly(key);
         case 2 -> this.getTileMemoryOnly(key);
         default -> this.getTile(key);
      };
      cached.store(tileLat, tileLon, source, generation, resolved);
      if (source == 2 && resolved == null) {
         return Integer.MIN_VALUE;
      }

      return resolved.sample(lon, lat);
   }

   /**
    * The single tile entry point shared by the visual and smoothed paths: <b>zero allocation</b> on a thread local table hit.
    *
    * <p>All four paths first built a {@code TileKey} with {@code tileKeyForLonLat(lon, lat)} and then compared it
    * against the thread local cache, one allocation per point. Now the two int corners are computed and the table is
    * looked up first, and that one {@code TileKey} is only built when the tile or the generation really changes.
    *
    * <p>Return value: the tile on a hit or after resolving; <b>null when out of bounds</b>.
    * The four call sites already returned the same thing for "out of bounds" and for "this tile is not in the
    * memory-only set" ({@code NO_DATA_CLASS} or {@code Integer.MIN_VALUE}), so a single null check is <b>bit for bit equivalent</b>.
    *
    * <p>The bounds check is deliberately written in the same negated form as {@code tileKeyForLonLat} so that
    * the path taken for {@code NaN} stays identical, since both keep going and resolve TileKey(0,0) instead of returning null.
    */
   private TellusLandCoverSource.GeoTiffTile resolveTileAtLonLat(double lon, double lat, int source) {
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         int tileLat = (int)Math.floor(lat / TILE_DEGREES) * TILE_DEGREES;
         int tileLon = (int)Math.floor(lon / TILE_DEGREES) * TILE_DEGREES;
         TellusLandCoverSource.CoverTileSlots slots = this.lastTile.get();
         int generation = CACHE_GENERATION.get();
         TellusLandCoverSource.GeoTiffTile cached = slots.probe(tileLat, tileLon, source, generation);
         if (slots.probedHit) {
            return cached;
         }

         TellusLandCoverSource.TileKey key = new TellusLandCoverSource.TileKey(tileLat, tileLon);
         TellusLandCoverSource.GeoTiffTile resolved = switch (source) {
            case 1 -> this.getTileLocalOnly(key);
            case 2 -> this.getTileMemoryOnly(key);
            default -> this.getTile(key);
         };
         slots.store(tileLat, tileLon, source, generation, resolved);
         return resolved;
      }

      return null;
   }

   private int sampleSmoothedCoverClassAtLonLat(double lon, double lat, int radiusPixels) {
      TellusLandCoverSource.GeoTiffTile tile = this.resolveTileAtLonLat(lon, lat, 0);
      if (tile == null) {
         return NO_DATA_CLASS;
      } else {
         TellusLandCoverSource.Pixel center = tile.toPixel(lon, lat);
         if (center == null) {
            return NO_DATA_CLASS;
         } else {
            int centerValue = tile.sampleValue(center.x(), center.y());
            if (radiusPixels > 0 && centerValue != WATER_CLASS && centerValue != NO_DATA_CLASS) {
               TellusLandCoverSource.CoverSmoothScratch scratch = COVER_SMOOTH_SCRATCH.get();
               scratch.reset();
               if (tile.isNeighborhoodInBounds(center.x(), center.y(), radiusPixels)) {
                  for (int dy = -radiusPixels; dy <= radiusPixels; dy++) {
                     int py = center.y() + dy;

                     for (int dx = -radiusPixels; dx <= radiusPixels; dx++) {
                        int px = center.x() + dx;
                        int value = tile.sampleValue(px, py);
                        if (value != WATER_CLASS && value != NO_DATA_CLASS) {
                           scratch.add(value);
                        }
                     }
                  }
               } else {
                  for (int dy = -radiusPixels; dy <= radiusPixels; dy++) {
                     int py = center.y() + dy;

                     for (int dxx = -radiusPixels; dxx <= radiusPixels; dxx++) {
                        int px = center.x() + dxx;
                        int value = this.sampleValueAcrossTiles(tile, px, py);
                        if (value != WATER_CLASS && value != NO_DATA_CLASS) {
                           scratch.add(value);
                        }
                     }
                  }
               }

               return scratch.pickMajority(centerValue);
            } else {
               return centerValue;
            }
         }
      }
   }

   private int sampleVisualCoverClassAtLonLat(double lon, double lat, double blockX, double blockZ, double effectiveResolutionMeters) {
      TellusLandCoverSource.GeoTiffTile tile = this.resolveTileAtLonLat(lon, lat, 0);
      if (tile == null) {
         return NO_DATA_CLASS;
      } else {
         TellusLandCoverSource.ContinuousPixel center = tile.toContinuousPixel(lon, lat);
         if (center == null) {
            return NO_DATA_CLASS;
         } else {
            int centerPixelX = Mth.floor(center.x());
            int centerPixelY = Mth.floor(center.y());
            int centerValue = tile.sampleValue(centerPixelX, centerPixelY);
            if (effectiveResolutionMeters >= RESOLUTION_METERS || isHardRawCoverClass(centerValue)) {
               return centerValue;
            } else {
               double transitionStrength = Mth.clamp((RESOLUTION_METERS - effectiveResolutionMeters) / 9.0, 0.0, 1.0);
               if (!(transitionStrength > 0.0)) {
                  return centerValue;
               } else {
                  double blendX = center.x() - 0.5;
                  double blendY = center.y() - 0.5;
                  int x0 = Mth.floor(blendX);
                  int y0 = Mth.floor(blendY);
                  int x1 = x0 + 1;
                  int y1 = y0 + 1;
                  double fx = blendX - x0;
                  double fy = blendY - y0;
                  int value00 = this.sampleValueAcrossTiles(tile, x0, y0);
                  int value10 = this.sampleValueAcrossTiles(tile, x1, y0);
                  int value01 = this.sampleValueAcrossTiles(tile, x0, y1);
                  int value11 = this.sampleValueAcrossTiles(tile, x1, y1);
                  if (isHardRawCoverClass(value00) || isHardRawCoverClass(value10) || isHardRawCoverClass(value01) || isHardRawCoverClass(value11)) {
                     return centerValue;
                  } else {
                     TellusLandCoverSource.CoverBlendScratch scratch = COVER_BLEND_SCRATCH.get();
                     scratch.reset();
                     double inverseFx = 1.0 - fx;
                     double inverseFy = 1.0 - fy;
                     scratch.add(centerValue, 1.0 - transitionStrength);
                     scratch.add(value00, inverseFx * inverseFy * transitionStrength);
                     scratch.add(value10, fx * inverseFy * transitionStrength);
                     scratch.add(value01, inverseFx * fy * transitionStrength);
                     scratch.add(value11, fx * fy * transitionStrength);
                     return scratch.pickWeighted(
                        centerValue, hashToUnitDouble(Mth.floor(blockX), Mth.floor(blockZ), 9154887495218319081L)
                     );
                  }
               }
            }
         }
      }
   }

   private int sampleVisualCoverClassAtLonLatLocalOnly(double lon, double lat, double blockX, double blockZ, double effectiveResolutionMeters) {
      TellusLandCoverSource.GeoTiffTile tile = this.resolveTileAtLonLat(lon, lat, 1);
      if (tile == null) {
         return NO_DATA_CLASS;
      } else {
         TellusLandCoverSource.ContinuousPixel center = tile.toContinuousPixel(lon, lat);
         if (center == null) {
            return NO_DATA_CLASS;
         } else {
            int centerPixelX = Mth.floor(center.x());
            int centerPixelY = Mth.floor(center.y());
            int centerValue = tile.sampleValue(centerPixelX, centerPixelY);
            if (effectiveResolutionMeters >= RESOLUTION_METERS || isHardRawCoverClass(centerValue)) {
               return centerValue;
            } else {
               double transitionStrength = Mth.clamp((RESOLUTION_METERS - effectiveResolutionMeters) / 9.0, 0.0, 1.0);
               if (!(transitionStrength > 0.0)) {
                  return centerValue;
               } else {
                  double blendX = center.x() - 0.5;
                  double blendY = center.y() - 0.5;
                  int x0 = Mth.floor(blendX);
                  int y0 = Mth.floor(blendY);
                  int x1 = x0 + 1;
                  int y1 = y0 + 1;
                  double fx = blendX - x0;
                  double fy = blendY - y0;
                  int value00 = this.sampleValueAcrossTilesLocalOnly(tile, x0, y0);
                  int value10 = this.sampleValueAcrossTilesLocalOnly(tile, x1, y0);
                  int value01 = this.sampleValueAcrossTilesLocalOnly(tile, x0, y1);
                  int value11 = this.sampleValueAcrossTilesLocalOnly(tile, x1, y1);
                  if (isHardRawCoverClass(value00) || isHardRawCoverClass(value10) || isHardRawCoverClass(value01) || isHardRawCoverClass(value11)) {
                     return centerValue;
                  } else {
                     TellusLandCoverSource.CoverBlendScratch scratch = COVER_BLEND_SCRATCH.get();
                     scratch.reset();
                     double inverseFx = 1.0 - fx;
                     double inverseFy = 1.0 - fy;
                     scratch.add(centerValue, 1.0 - transitionStrength);
                     scratch.add(value00, inverseFx * inverseFy * transitionStrength);
                     scratch.add(value10, fx * inverseFy * transitionStrength);
                     scratch.add(value01, inverseFx * fy * transitionStrength);
                     scratch.add(value11, fx * fy * transitionStrength);
                     return scratch.pickWeighted(
                        centerValue, hashToUnitDouble(Mth.floor(blockX), Mth.floor(blockZ), 9154887495218319081L)
                     );
                  }
               }
            }
         }
      }
   }

   private int sampleVisualCoverClassAtLonLatMemoryOnly(double lon, double lat, double blockX, double blockZ, double effectiveResolutionMeters) {
      // Out of bounds (resolveTileAtLonLat returned null) and "this tile is not in the memory-only set" (null):
      //   these used to be two checks that returned the same value, so they are folded into one here and the result is bit for bit unchanged.
      TellusLandCoverSource.GeoTiffTile tile = this.resolveTileAtLonLat(lon, lat, 2);
      if (tile == null) {
         return Integer.MIN_VALUE;
      } else {
         TellusLandCoverSource.ContinuousPixel center = tile.toContinuousPixel(lon, lat);
         if (center == null) {
            return Integer.MIN_VALUE;
         } else {
            int centerPixelX = Mth.floor(center.x());
            int centerPixelY = Mth.floor(center.y());
            int centerValue = tile.sampleValue(centerPixelX, centerPixelY);
            if (effectiveResolutionMeters >= RESOLUTION_METERS || isHardRawCoverClass(centerValue)) {
               return centerValue;
            } else {
               double transitionStrength = Mth.clamp((RESOLUTION_METERS - effectiveResolutionMeters) / 9.0, 0.0, 1.0);
               if (!(transitionStrength > 0.0)) {
                  return centerValue;
               } else {
                  double blendX = center.x() - 0.5;
                  double blendY = center.y() - 0.5;
                  int x0 = Mth.floor(blendX);
                  int y0 = Mth.floor(blendY);
                  int x1 = x0 + 1;
                  int y1 = y0 + 1;
                  double fx = blendX - x0;
                  double fy = blendY - y0;
                  int value00 = this.sampleValueAcrossTilesMemoryOnly(tile, x0, y0);
                  int value10 = this.sampleValueAcrossTilesMemoryOnly(tile, x1, y0);
                  int value01 = this.sampleValueAcrossTilesMemoryOnly(tile, x0, y1);
                  int value11 = this.sampleValueAcrossTilesMemoryOnly(tile, x1, y1);
                  if (value00 == Integer.MIN_VALUE || value10 == Integer.MIN_VALUE || value01 == Integer.MIN_VALUE || value11 == Integer.MIN_VALUE) {
                     return Integer.MIN_VALUE;
                  } else if (isHardRawCoverClass(value00)
                     || isHardRawCoverClass(value10)
                     || isHardRawCoverClass(value01)
                     || isHardRawCoverClass(value11)) {
                     return centerValue;
                  } else {
                     TellusLandCoverSource.CoverBlendScratch scratch = COVER_BLEND_SCRATCH.get();
                     scratch.reset();
                     double inverseFx = 1.0 - fx;
                     double inverseFy = 1.0 - fy;
                     scratch.add(centerValue, 1.0 - transitionStrength);
                     scratch.add(value00, inverseFx * inverseFy * transitionStrength);
                     scratch.add(value10, fx * inverseFy * transitionStrength);
                     scratch.add(value01, inverseFx * fy * transitionStrength);
                     scratch.add(value11, fx * fy * transitionStrength);
                     return scratch.pickWeighted(centerValue, hashToUnitDouble(Mth.floor(blockX), Mth.floor(blockZ), 9154887495218319081L));
                  }
               }
            }
         }
      }
   }

   private int sampleValueAcrossTiles(TellusLandCoverSource.GeoTiffTile tile, int pixelX, int pixelY) {
      if (tile.isInside(pixelX, pixelY)) {
         return tile.sampleValue(pixelX, pixelY);
      } else {
         double neighborLon = tile.lonForPixel(pixelX);
         double neighborLat = tile.latForPixel(pixelY);
         return this.sampleCoverClassAtLonLat(neighborLon, neighborLat);
      }
   }

   private int sampleValueAcrossTilesLocalOnly(TellusLandCoverSource.GeoTiffTile tile, int pixelX, int pixelY) {
      if (tile.isInside(pixelX, pixelY)) {
         return tile.sampleValue(pixelX, pixelY);
      } else {
         double neighborLon = tile.lonForPixel(pixelX);
         double neighborLat = tile.latForPixel(pixelY);
         return this.sampleCoverClassAtLonLatLocalOnly(neighborLon, neighborLat);
      }
   }

   private int sampleValueAcrossTilesMemoryOnly(TellusLandCoverSource.GeoTiffTile tile, int pixelX, int pixelY) {
      if (tile.isInside(pixelX, pixelY)) {
         return tile.sampleValue(pixelX, pixelY);
      } else {
         double neighborLon = tile.lonForPixel(pixelX);
         double neighborLat = tile.latForPixel(pixelY);
         return this.sampleCoverClassAtLonLatMemoryOnly(neighborLon, neighborLat);
      }
   }

   private static boolean isHardRawCoverClass(int coverClass) {
      return coverClass == NO_DATA_CLASS || coverClass == WATER_CLASS || coverClass == MANGROVES_CLASS || coverClass == BUILT_UP_CLASS;
   }

   private static double hashToUnitDouble(long x, long z, long salt) {
      long seed = x * 3129871L ^ z * 116129781L ^ salt;
      seed = seed * seed * 42317861L + seed * 11L;
      return ((seed >>> 11) & (1L << 53) - 1L) * 1.1102230246251565E-16;
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, worldScale);
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius, double previewResolutionMeters) {
      TellusLandCoverSource.TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, previewResolutionMeters);
      if (center != null) {
         int clampedRadius = Math.max(0, radius);

         for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            int lat = center.lat() + dz * TILE_DEGREES;
            if (!(lat < MIN_LAT) && !(lat > MAX_LAT)) {
               for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
                  int lon = center.lon() + dx * TILE_DEGREES;
                  if (!(lon < MIN_LON) && !(lon > MAX_LON)) {
                     this.prefetchTile(new TellusLandCoverSource.TileKey(lat, lon));
                  }
               }
            }
         }
      }
   }

   private static int downsampleStep(double worldScale, double resolutionMeters, double previewResolutionMeters) {
      if (!(worldScale > 0.0)) {
         return 1;
      } else if (!(effectiveSampleResolutionMeters(worldScale, previewResolutionMeters) >= resolutionMeters)) {
         return 1;
      } else {
         return Math.max(1, Mth.floor(resolutionMeters / worldScale));
      }
   }

   private static double downsampleBlock(double blockCoord, int step) {
      if (step <= 1) {
         return blockCoord;
      } else {
         int block = Mth.floor(blockCoord);
         int snapped = Math.floorDiv(block, step) * step;
         return snapped + step * 0.5;
      }
   }

   private static TellusLandCoverSource.TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, double previewResolutionMeters) {
      if (worldScale <= 0.0) {
         return null;
      } else {
         int step = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
         if (step > 1) {
            blockX = downsampleBlock(blockX, step);
            blockZ = downsampleBlock(blockZ, step);
         }

         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = EarthProjection.wrapLongitude(blockX / blocksPerDegree);
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return tileKeyForLonLat(lon, lat);
      }
   }

   private static double effectiveSampleResolutionMeters(double worldScale, double previewResolutionMeters) {
      return Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0 ? Math.max(worldScale, previewResolutionMeters) : worldScale;
   }

   private static TellusLandCoverSource.TileKey tileKeyForLonLat(double lon, double lat) {
      if (!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)) {
         int tileLat = (int)Math.floor(lat / TILE_DEGREES) * TILE_DEGREES;
         int tileLon = (int)Math.floor(lon / TILE_DEGREES) * TILE_DEGREES;
         return new TellusLandCoverSource.TileKey(tileLat, tileLon);
      } else {
         return null;
      }
   }

   private void prefetchTile( TellusLandCoverSource.TileKey key) {
      // 2026-09-28: if it failed recently, do not prefetch it again, otherwise it pairs with `getTile` into a retry storm.
      if (this.loadFailures.getIfPresent(key) == null && this.cache.getIfPresent(key) == null) {
         try {
            TellusDownloadExecutor.prefetch("TellusLandCoverSource", key, TellusLandCoverSource.this::loadTile);
         } catch (Exception var3) {
            if (isInterruptedLoad(var3)) {
               Thread.currentThread().interrupt();
               return;
            }

            // A failed prefetch must be recorded too, otherwise the prefetch thread keeps hammering that same bad tile.
            this.loadFailures.put(key, Boolean.TRUE);
            Tellus.LOGGER.debug("Prefetching the land cover tile failed {}", key, var3);
         }
      }
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException var4) {
            return defaultValue;
         }
      }
   }

   private TellusLandCoverSource.GeoTiffTile getTile(TellusLandCoverSource.TileKey key) {
      // 2026-09-28: check whether it failed recently first, and if so return MISSING right away.
      //   It must never enter `cache.get()`, because Guava does not cache exceptions, so every call would download, fail
      //   and log again. That path alone printed 17328 log lines in six minutes and dragged the server 206 ticks behind.
      if (this.loadFailures.getIfPresent(key) != null) {
         return TellusLandCoverSource.GeoTiffTile.MISSING;
      }

      try {
         return (TellusLandCoverSource.GeoTiffTile)this.cache.get(key);
      } catch (Exception var3) {
         if (isInterruptedLoad(var3)) {
            Thread.currentThread().interrupt();
            return TellusLandCoverSource.GeoTiffTile.MISSING;
         }

         // Record the failure, do not retry for a while, and the tile recovers on its own once the TTL expires.
         this.loadFailures.put(key, Boolean.TRUE);
         // The log is rate limited so one tile only prints its first line inside the TTL.
         //   The full stack trace is kept only for the first one, after that only a one line count summary.
         if (this.loadFailureLogged.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            Tellus.LOGGER.warn("Failed to load the land cover tile {}", key, var3);
         } else {
            Tellus.LOGGER.debug("Loading the land cover tile still failed (rate limited): {}", key);
         }

         return TellusLandCoverSource.GeoTiffTile.MISSING;
      }
   }

   private TellusLandCoverSource.GeoTiffTile getTileLocalOnly(TellusLandCoverSource.TileKey key) {
      TellusLandCoverSource.GeoTiffTile cached = (TellusLandCoverSource.GeoTiffTile)this.cache.getIfPresent(key);
      if (cached != null) {
         return cached;
      } else {
         Path cachePath = this.cacheRoot.resolve(key.fileName());
         if (!Files.exists(cachePath)) {
            return TellusLandCoverSource.GeoTiffTile.MISSING;
         } else {
            try {
               TellusLandCoverSource.GeoTiffTile opened = TellusLandCoverSource.GeoTiffTile.open(cachePath);
               TellusLandCoverSource.GeoTiffTile raced = (TellusLandCoverSource.GeoTiffTile)this.cache.asMap().putIfAbsent(key, opened);
               if (raced != null) {
                  opened.close();
                  return raced;
               } else {
                  return opened;
               }
            } catch (IOException error) {
               Tellus.LOGGER.debug("Reading the cached land cover tile failed {}", key, error);
               return TellusLandCoverSource.GeoTiffTile.MISSING;
            }
         }
      }
   }

   private TellusLandCoverSource.GeoTiffTile getTileMemoryOnly(TellusLandCoverSource.TileKey key) {
      TellusLandCoverSource.GeoTiffTile cached = (TellusLandCoverSource.GeoTiffTile)this.cache.getIfPresent(key);
      return cached == null || cached == TellusLandCoverSource.GeoTiffTile.MISSING ? null : cached;
   }

   private static boolean isInterruptedLoad(Throwable throwable) {
      if (Thread.currentThread().isInterrupted()) {
         return true;
      } else {
         for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ClosedByInterruptException || current instanceof InterruptedException || current instanceof InterruptedIOException) {
               return true;
            }
         }

         return false;
      }
   }

   private TellusLandCoverSource.GeoTiffTile loadTile(TellusLandCoverSource.TileKey key) throws IOException {
      Path cachePath = this.cacheRoot.resolve(key.fileName());
      if (Files.exists(cachePath)) {
         return TellusLandCoverSource.GeoTiffTile.open(cachePath);
      } else {
         byte[] data = this.downloadTile(key);
         if (data == null) {
            return TellusLandCoverSource.GeoTiffTile.MISSING;
         } else {
            this.cacheTile(cachePath, data);
            return TellusLandCoverSource.GeoTiffTile.open(cachePath);
         }
      }
   }

   private byte[] downloadTile(TellusLandCoverSource.TileKey key) throws IOException {
      URI uri = URI.create(String.format("%s/%s", getEndpoint(), key.fileName()));
      // Offline-first gate: with -Dtellus.network.offlineFirst=true a local cache miss never goes online
      com.yucareux.tellus.world.data.source.TellusNetworkGate.requireOnline(uri, "land cover tile");
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      try {
         connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
         connection.setReadTimeout(READ_TIMEOUT_MS);
         connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
         if (connection.getResponseCode() == 404) {
            return null;
         } else {
            DownloadProgressReporter.requestStarted(connection.getContentLengthLong());

            byte[] var5;
            try (InputStream input = connection.getInputStream()) {
               var5 = DownloadProgressReporter.readAllBytesWithProgress(input);
            } finally {
               DownloadProgressReporter.requestFinished();
            }

            return var5;
         }
      } finally {
         connection.disconnect();
      }
   }

   private void cacheTile(Path cachePath, byte[] data) {
      try {
         Files.createDirectories(cachePath.getParent());
         Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
         Files.write(tempPath, data);
         Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException var4) {
         Tellus.LOGGER.warn("Writing the land cover tile cache failed {}", cachePath, var4);
      }
   }

   private static final class CoverSmoothScratch {
      private final int[] counts = new int[256];
      private final int[] used = new int[256];
      private int usedCount;

      private void reset() {
         for (int i = 0; i < this.usedCount; i++) {
            this.counts[this.used[i]] = 0;
         }

         this.usedCount = 0;
      }

      private void add(int value) {
         if (this.counts[value] == 0) {
            this.used[this.usedCount++] = value;
         }

         this.counts[value]++;
      }

      private int pickMajority(int centerValue) {
         if (this.usedCount == 0) {
            return centerValue;
         } else {
            int bestValue = centerValue;
            int bestCount = -1;

            for (int i = 0; i < this.usedCount; i++) {
               int value = this.used[i];
               int count = this.counts[value];
               if (count > bestCount || count == bestCount && value == centerValue) {
                  bestCount = count;
                  bestValue = value;
               }
            }

            return bestValue;
         }
      }
   }

   private static final class CoverBlendScratch {
      private final double[] weights = new double[256];
      private final int[] used = new int[256];
      private int usedCount;

      private void reset() {
         for (int i = 0; i < this.usedCount; i++) {
            this.weights[this.used[i]] = 0.0;
         }

         this.usedCount = 0;
      }

      private void add(int value, double weight) {
         if (weight > 0.0) {
            if (!(this.weights[value] > 0.0)) {
               this.used[this.usedCount++] = value;
            }

            this.weights[value] += weight;
         }
      }

      private int pickWeighted(int fallbackValue, double threshold) {
         if (this.usedCount == 0) {
            return fallbackValue;
         } else {
            double total = 0.0;

            for (int i = 0; i < this.usedCount; i++) {
               total += this.weights[this.used[i]];
            }

            if (!(total > 0.0)) {
               return fallbackValue;
            } else {
               double target = Mth.clamp(threshold, 0.0, 0.9999999999999999) * total;
               double cumulative = 0.0;
               int lastValue = fallbackValue;

               for (int i = 0; i < this.usedCount; i++) {
                  int value = this.used[i];
                  lastValue = value;
                  cumulative += this.weights[value];
                  if (target < cumulative || i + 1 == this.usedCount) {
                     return value;
                  }
               }

               return lastValue;
            }
         }
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.LAND_COVER;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
      // The tile resolution cache of this thread is cleared as well.
      this.lastTile.get().clear();
      //  And bump the global generation, because a tile another thread already close()d must be invalidated too.
      //  Once the table grows from 1 slot to 32 that hole would be amplified 32 times, so it is plugged here in one go;
      //  invalidating only means "resolve again next time", the tile is still the same one and the result is bit for bit unchanged.
      CACHE_GENERATION.incrementAndGet();
   }

   private static final class GeoTiffTile {
      private static final int TAG_IMAGE_WIDTH = 256;
      private static final int TAG_IMAGE_HEIGHT = 257;
      private static final int TAG_BITS_PER_SAMPLE = 258;
      private static final int TAG_COMPRESSION = 259;
      private static final int TAG_SAMPLES_PER_PIXEL = 277;
      private static final int TAG_PLANAR_CONFIG = 284;
      private static final int TAG_PREDICTOR = 317;
      private static final int TAG_TILE_WIDTH = 322;
      private static final int TAG_TILE_HEIGHT = 323;
      private static final int TAG_TILE_OFFSETS = 324;
      private static final int TAG_TILE_BYTE_COUNTS = 325;
      private static final int TAG_MODEL_PIXEL_SCALE = 33550;
      private static final int TAG_MODEL_TIEPOINT = 33922;
      private static final int TYPE_SHORT = 3;
      private static final int TYPE_LONG = 4;
      private static final int COMPRESSION_DEFLATE = 8;
      private static final TellusLandCoverSource.GeoTiffTile MISSING = new TellusLandCoverSource.GeoTiffTile();
      /**
       * 2026-09-29: rate limit the log of a failed <b>chunk read</b>.
       *
       * <p>The same hole was still open on the <b>per pixel</b> path: {@code sampleValueInside} logged a line with a
       * full stack trace every time it could not read, which is the same shape as the 2026-09-28 "17328 lines in
       * six minutes, server 206 ticks behind" incident, only further in. It triggers easily, because
       * {@code clearCache()} closing the channel while a thread is sampling leaves every later {@code readTile}
       * failing, so one line per point and hundreds per chunk.
       *
       * <p>It copies the outer {@code loadFailureLogged} approach: only the first line per TIFF inside the TTL,
       * then a single debug count line. The return value is unchanged in every respect, a failure still returns 0.
       */
      private static final com.google.common.cache.Cache<String, Boolean> READ_FAILURE_LOGGED =
         com.google.common.cache.CacheBuilder.newBuilder().maximumSize(256).expireAfterWrite(5, java.util.concurrent.TimeUnit.MINUTES).build();
      /** The one time INFO for an "unexpected TIFF format", once globally rather than once per tile. */
      private static final java.util.concurrent.atomic.AtomicBoolean UNSUPPORTED_LAYOUT_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();
      private final Path path;
      private final FileChannel channel;
      private final int width;
      private final int height;
      private final int tileWidth;
      private final int tileHeight;
      private final int tilesPerRow;
      private final long[] tileOffsets;
      private final int[] tileByteCounts;
      private final double pixelScaleX;
      private final double pixelScaleY;
      private final double tieLon;
      private final double tieLat;
      /**
       * The decompressed TIFF inner tile table, changed from an LRU LinkedHashMap to direct addressing on 2026-09-29.
       *
       * <p>It used to be a {@code synchronized + LinkedHashMap<Integer, byte[]>}, so every single pixel paid:
       * entering a monitor, boxing with {@code Integer.valueOf(tileIndex)}, which really allocates once tileIndex
       * exceeds 127, and reordering the access-order list, which is dozens of pointer writes plus a write barrier.
       * All three sit on the {@code sample} path that every point takes.
       *
       * <p>Now it is an {@code AtomicReferenceArray} whose length is a power of two and whose index is
       * {@code tileIndex & mask}: one array read and one type check, no lock, no boxing, no list reordering.
       * Scanning along a row the tileIndex increases steadily, so neighbouring inner tiles land in neighbouring
       * slots and barely evict each other. The memory bound is the <b>same</b> as before, at most
       * TILE_CACHE_ENTRIES chunks of 64KB. The bytes read are <b>bit for bit identical</b>: same TIFF, same offset
       */
      private final java.util.concurrent.atomic.AtomicReferenceArray<Object> decodedTiles;
      private final int decodedSlotMask;

      private GeoTiffTile() {
         this.path = null;
         this.channel = null;
         this.width = 0;
         this.height = 0;
         this.tileWidth = 0;
         this.tileHeight = 0;
         this.tilesPerRow = 0;
         this.tileOffsets = null;
         this.tileByteCounts = null;
         this.pixelScaleX = 0.0;
         this.pixelScaleY = 0.0;
         this.tieLon = 0.0;
         this.tieLat = 0.0;
         this.decodedTiles = new java.util.concurrent.atomic.AtomicReferenceArray<Object>(0);
         this.decodedSlotMask = 0;
      }

      private GeoTiffTile(
         Path path,
         FileChannel channel,
         int width,
         int height,
         int tileWidth,
         int tileHeight,
         long[] tileOffsets,
         int[] tileByteCounts,
         double pixelScaleX,
         double pixelScaleY,
         double tieLon,
         double tieLat
      ) {
         this.path = path;
         this.channel = channel;
         this.width = width;
         this.height = height;
         this.tileWidth = tileWidth;
         this.tileHeight = tileHeight;
         this.tilesPerRow = (int)Math.ceil((double)width / tileWidth);
         this.tileOffsets = tileOffsets;
         this.tileByteCounts = tileByteCounts;
         this.pixelScaleX = pixelScaleX;
         this.pixelScaleY = pixelScaleY;
         this.tieLon = tieLon;
         this.tieLat = tieLat;
         // The slot count is rounded up to a power of two, because the index uses & mask.
         //  Capped at 16384, because the tileCacheEntries system property has no upper bound and
         //   intProperty accepts 2^30, and a value like that would blow up new Object[] directly. The cap only
         //   affects how many chunks can be cached at once, which is pure performance, and no pixel value.
         int slotCount = Integer.highestOneBit(Math.max(4, Math.min(TellusLandCoverSource.TILE_CACHE_ENTRIES, 16384)) - 1) * 2;
         this.decodedTiles = new java.util.concurrent.atomic.AtomicReferenceArray<Object>(slotCount);
         this.decodedSlotMask = slotCount - 1;
      }

      static TellusLandCoverSource.GeoTiffTile open(Path path) throws IOException {
         FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);

         try {
            return readFromChannel(path, channel);
         } catch (IOException var3) {
            channel.close();
            throw var3;
         }
      }

      int sample(double lon, double lat) {
         long pixel = this.toPixelEncoded(lon, lat);
         // toPixelEncoded already handled out of bounds and MISSING, so this takes the "definitely in range"
         //   half directly and drops the 4 repeated bounds checks in sampleValue on the path every point takes.
         return pixel < 0L ? 0 : this.sampleValueInside((int)(pixel >> 32), (int)pixel);
      }

      TellusLandCoverSource.ContinuousPixel toContinuousPixel(double lon, double lat) {
         if (this == MISSING) {
            return null;
         } else {
            double pixelX = (lon - this.tieLon) / this.pixelScaleX;
            double pixelY = (this.tieLat - lat) / this.pixelScaleY;
            return pixelX >= 0.0 && pixelY >= 0.0 && pixelX < this.width && pixelY < this.height
               ? new TellusLandCoverSource.ContinuousPixel(pixelX, pixelY)
               : null;
         }
      }

      /**
       * Pixel coordinates encoded into one long: x in the high 32 bits, y in the low 32 bits, with {@code -1L} for out of bounds.
       *
       * <p>This used to return {@code record Pixel(int x, int y)}. JFR sampling shows land cover is sampled column
       * by column, so every point on the hot path ({@code sample}) allocated a Pixel, which is 1.2% of all sampled
       * allocations and also drives GC up (8795 GCPhaseParallel in the same window). After the long encoding it is free.
       */
      long toPixelEncoded(double lon, double lat) {
         if (this == MISSING) {
            return -1L;
         } else {
            int pixelX = (int)Math.floor((lon - this.tieLon) / this.pixelScaleX);
            int pixelY = (int)Math.floor((this.tieLat - lat) / this.pixelScaleY);
            if (pixelX < 0 || pixelY < 0 || pixelX >= this.width || pixelY >= this.height) {
               return -1L;
            }

            return (long)pixelX << 32 | (long)pixelY & 4294967295L;
         }
      }

      TellusLandCoverSource.Pixel toPixel(double lon, double lat) {
         if (this == MISSING) {
            return null;
         } else {
            int pixelX = (int)Math.floor((lon - this.tieLon) / this.pixelScaleX);
            int pixelY = (int)Math.floor((this.tieLat - lat) / this.pixelScaleY);
            return pixelX >= 0 && pixelY >= 0 && pixelX < this.width && pixelY < this.height ? new TellusLandCoverSource.Pixel(pixelX, pixelY) : null;
         }
      }

      int sampleValue(int pixelX, int pixelY) {
         if (this != MISSING && pixelX >= 0 && pixelY >= 0 && pixelX < this.width && pixelY < this.height) {
            return this.sampleValueInside(pixelX, pixelY);
         } else {
            return 0;
         }
      }

      /** The "the coordinate is already known to be in range" half of {@link #sampleValue}, taken by {@link #sample} so the bounds are not checked twice. */
      private int sampleValueInside(int pixelX, int pixelY) {
         int tileX = pixelX / this.tileWidth;
         int tileY = pixelY / this.tileHeight;
         int tileIndex = tileY * this.tilesPerRow + tileX;
         // 2026-09-29: when the file is truncated, or tileOffsets has fewer entries than the grid,
         //   `tileOffsets[tileIndex]` throws ArrayIndexOutOfBoundsException, which is a RuntimeException that
         //   neither catch in `sampleValueInside` can take, so it escapes into world generation and blows up the
         //   whole chunk. It is blocked here instead, and the return value is 0 exactly like the other failure paths.
         //   On a well formed file the condition never holds, because tileX <= tilesPerRow-1 and
         //     tileY <= ceil(height/tileHeight)-1, so tileIndex is always in range.
         if (tileIndex < 0 || tileIndex >= this.tileOffsets.length) {
            return 0;
         }

         byte[] tile;
         try {
            tile = this.getTile(tileIndex);
         } catch (ClosedByInterruptException var9) {
            Thread.currentThread().interrupt();
            return 0;
         } catch (IOException var10) {
            this.logReadFailure(tileIndex, var10);
            return 0;
         }

         int localX = pixelX - tileX * this.tileWidth;
         int localY = pixelY - tileY * this.tileHeight;
         return Byte.toUnsignedInt(tile[localX + localY * this.tileWidth]);
      }

      /** Rate limits the log of a failed chunk read: only the first full stack trace per TIFF inside the TTL, then a single debug line. */
      private void logReadFailure(int tileIndex, IOException error) {
         if (READ_FAILURE_LOGGED.asMap().putIfAbsent(this.path == null ? "" : this.path.toString(), Boolean.TRUE) == null) {
            Tellus.LOGGER.warn("Reading the land cover tile failed {}, file {}", new Object[]{tileIndex, this.path, error});
         } else {
            Tellus.LOGGER.debug("Reading the land cover tile still failed (rate limited): {}, chunk {}", this.path, tileIndex);
         }
      }

      boolean isInside(int pixelX, int pixelY) {
         return pixelX >= 0 && pixelY >= 0 && pixelX < this.width && pixelY < this.height;
      }

      boolean isNeighborhoodInBounds(int pixelX, int pixelY, int radius) {
         return pixelX - radius >= 0 && pixelY - radius >= 0 && pixelX + radius < this.width && pixelY + radius < this.height;
      }

      double lonForPixel(int pixelX) {
         return this.tieLon + (pixelX + 0.5) * this.pixelScaleX;
      }

      double latForPixel(int pixelY) {
         return this.tieLat - (pixelY + 0.5) * this.pixelScaleY;
      }

      void close() {
         if (this.channel != null) {
            for (int slot = 0; slot < this.decodedTiles.length(); slot++) {
               this.decodedTiles.set(slot, null);
            }
            try {
               this.channel.close();
            } catch (IOException var2) {
               Tellus.LOGGER.warn("Closing the land cover tile failed {}", this.path, var2);
            }
         }
      }

      /**
       * Fetches one decompressed TIFF inner tile, with no lock and no boxing.
       *
       * <p>Published with {@code setRelease} and read with {@code getAcquire}: the chunk contents are written
       * {@code inflate()}, and release/acquire guarantees another thread that sees the reference also sees
       * all of the contents. The old {@code synchronized} did the same job at the cost of
       * one monitor per pixel.
       */
      private byte[] getTile(int tileIndex) throws IOException {
         int slot = tileIndex & this.decodedSlotMask;
         Object cached = this.decodedTiles.getAcquire(slot);
         if (cached instanceof TellusLandCoverSource.GeoTiffTile.DecodedTile hit && hit.index == tileIndex) {
            return hit.data;
         }

         byte[] tile = this.readTile(tileIndex);
         this.decodedTiles.setRelease(slot, new TellusLandCoverSource.GeoTiffTile.DecodedTile(tileIndex, tile));
         return tile;
      }

      private byte[] readTile(int tileIndex) throws IOException {
         long offset = this.tileOffsets[tileIndex];
         int length = this.tileByteCounts[tileIndex];
         byte[] compressed = new byte[length];

         try {
            readFully(this.channel, compressed, offset);
         } catch (ClosedChannelException var12) {
            if (this.path == null) {
               throw var12;
            }

            try (FileChannel reopened = FileChannel.open(this.path, StandardOpenOption.READ)) {
               readFully(reopened, compressed, offset);
            }
         }

         return inflate(compressed, this.tileWidth * this.tileHeight);
      }

      private static TellusLandCoverSource.GeoTiffTile readFromChannel(Path path, FileChannel channel) throws IOException {
         ByteBuffer header = ByteBuffer.allocate(8);
         readFully(channel, header, 0L);
         header.flip();
         short order = header.getShort();

         ByteOrder byteOrder = switch (order) {
            case 18761 -> ByteOrder.LITTLE_ENDIAN;
            case 19789 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid TIFF byte order");
         };
         header.order(byteOrder);
         short magic = header.getShort();
         if (magic != 42) {
            throw new IOException("Invalid TIFF magic number");
         } else {
            int ifdOffset = header.getInt();
            ByteBuffer countBuffer = ByteBuffer.allocate(2).order(byteOrder);
            readFully(channel, countBuffer, ifdOffset);
            countBuffer.flip();
            int entryCount = Short.toUnsignedInt(countBuffer.getShort());
            ByteBuffer entries = ByteBuffer.allocate(entryCount * 12).order(byteOrder);
            readFully(channel, entries, ifdOffset + 2L);
            entries.flip();
            int width = -1;
            int height = -1;
            int tileWidth = -1;
            int tileHeight = -1;
            int compression = -1;
            // 2026-09-29: the four checks below are <b>preconditions of decoding</b>, and none of them was verified before.
            //   Without them "every pixel is wrong" happens completely silently, the terrain sprouts wrong materials
            //   or a sea surface out of nowhere while neither the player nor the log sees anything odd. Better to fail
            //   loudly than to return silently wrong values. The defaults follow the TIFF spec (BitsPerSample=1, rest=1).
            int bitsPerSample = 1;
            int samplesPerPixel = 1;
            int planarConfig = 1;
            int predictor = 1;
            long[] tileOffsets = null;
            int[] tileByteCounts = null;
            double[] pixelScale = null;
            double[] tiepoint = null;

            for (int i = 0; i < entryCount; i++) {
               int tag = Short.toUnsignedInt(entries.getShort());
               int type = Short.toUnsignedInt(entries.getShort());
               int count = entries.getInt();
               int value = entries.getInt();
               switch (tag) {
                  case TAG_IMAGE_WIDTH:
                     width = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_IMAGE_HEIGHT:
                     height = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_COMPRESSION:
                     compression = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_BITS_PER_SAMPLE:
                     bitsPerSample = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_SAMPLES_PER_PIXEL:
                     samplesPerPixel = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_PLANAR_CONFIG:
                     planarConfig = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_PREDICTOR:
                     predictor = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_TILE_WIDTH:
                     tileWidth = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_TILE_HEIGHT:
                     tileHeight = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_TILE_OFFSETS:
                     tileOffsets = readLongArray(channel, value, count, byteOrder);
                     break;
                  case TAG_TILE_BYTE_COUNTS:
                     tileByteCounts = readIntArray(channel, value, count, byteOrder);
                     break;
                  case TAG_MODEL_PIXEL_SCALE:
                     pixelScale = readDoubleArray(channel, value, count, byteOrder);
                     break;
                  case TAG_MODEL_TIEPOINT:
                     tiepoint = readDoubleArray(channel, value, count, byteOrder);
               }
            }

            if (compression != COMPRESSION_DEFLATE) {
               throw new IOException("Unsupported TIFF compression: " + compression);
            } else if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0) {
               throw new IOException("TIFF is missing the size tags");
            } else if (tileOffsets == null || tileByteCounts == null) {
               throw new IOException("TIFF is missing the tile offsets");
            } else if (bitsPerSample != 8 || samplesPerPixel != 1 || planarConfig != 1 || predictor != 1) {
               // This turns "silently wrong values" into "loudly missing values".
               //   Once thrown it is caught by `loadTile` and then `getTile`, so the key lands in the `loadFailures`
               //   negative cache for 60 seconds and `loadFailureLogged` rate limits it to one warn line, after
               //   which the area returns NO_DATA (0) instead of terrain that looks plausible while every pixel is wrong.
               //   What the player sees: these areas are <b>temporarily</b> sea level or no cover, which is a deliberate trade off.
               if (UNSUPPORTED_LAYOUT_LOGGED.compareAndSet(false, true)) {
                  Tellus.LOGGER.info(
                     "Unexpected TIFF format detected, degraded to no cover: bitsPerSample={} samplesPerPixel={} planarConfig={} predictor={} file {}",
                     new Object[]{bitsPerSample, samplesPerPixel, planarConfig, predictor, path}
                  );
               }

               throw new IOException(
                  "Unsupported TIFF pixel layout bitsPerSample=" + bitsPerSample + " samplesPerPixel=" + samplesPerPixel
                     + " planarConfig=" + planarConfig + " predictor=" + predictor
               );
            } else if (pixelScale != null && pixelScale.length >= 2 && tiepoint != null && tiepoint.length >= 5) {
               return new TellusLandCoverSource.GeoTiffTile(
                  path, channel, width, height, tileWidth, tileHeight, tileOffsets, tileByteCounts, pixelScale[0], pixelScale[1], tiepoint[3], tiepoint[4]
               );
            } else {
               throw new IOException("TIFF is missing the georeference tags");
            }
         }
      }

      private static int readIntValue(int type, int count, int value, ByteOrder order) throws IOException {
         if (count != 1) {
            throw new IOException("Expected a single TIFF value");
         } else {
            ByteBuffer buffer = ByteBuffer.allocate(4).order(order);
            buffer.putInt(value);
            buffer.flip();
            if (type == TYPE_SHORT) {
               return Short.toUnsignedInt(buffer.getShort());
            } else if (type == TYPE_LONG) {
               return buffer.getInt();
            } else {
               throw new IOException("Unsupported TIFF value type: " + type);
            }
         }
      }

      private static long[] readLongArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
         if (count <= 0) {
            return new long[0];
         } else {
            ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(order);
            readFully(channel, buffer, offset);
            buffer.flip();
            long[] values = new long[count];

            for (int i = 0; i < count; i++) {
               values[i] = Integer.toUnsignedLong(buffer.getInt());
            }

            return values;
         }
      }

      private static int[] readIntArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
         if (count <= 0) {
            return new int[0];
         } else {
            ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(order);
            readFully(channel, buffer, offset);
            buffer.flip();
            int[] values = new int[count];

            for (int i = 0; i < count; i++) {
               values[i] = buffer.getInt();
            }

            return values;
         }
      }

      private static double[] readDoubleArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
         if (count <= 0) {
            return new double[0];
         } else {
            ByteBuffer buffer = ByteBuffer.allocate(count * 8).order(order);
            readFully(channel, buffer, offset);
            buffer.flip();
            double[] values = new double[count];

            for (int i = 0; i < count; i++) {
               values[i] = buffer.getDouble();
            }

            return values;
         }
      }

      private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
         long position = offset;

         while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
               throw new EOFException("The file ended unexpectedly");
            }

            position += read;
         }
      }

      private static void readFully(FileChannel channel, byte[] dest, long offset) throws IOException {
         readFully(channel, ByteBuffer.wrap(dest), offset);
      }

      private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
         byte[] output = new byte[expectedSize];

         byte[] var8;
         try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            int offset = 0;

            while (offset < expectedSize) {
               int read = inflater.read(output, offset, expectedSize - offset);
               if (read < 0) {
                  break;
               }

               offset += read;
            }

            if (offset != expectedSize) {
               throw new IOException("Unexpected decompressed data length");
            }

            var8 = output;
         }

         return var8;
      }

      /**
       * One decompressed TIFF inner tile, with the index and the data packed into <b>the same object</b>.
       *
       * <p>Packing them together is required, because with the key and the value in two separate arrays the tearing
       * "the key is new but the value is still old" would read <b>another chunk bytes</b>, whereas publishing both
       * together with a single reference plus release/acquire makes tearing impossible.
       */
      private static final class DecodedTile {
         private final int index;
         private final byte[] data;

         private DecodedTile(int index, byte[] data) {
            this.index = index;
            this.data = data;
         }
      }
   }

   private record Pixel(int x, int y) {
   }

   private record ContinuousPixel(double x, double y) {
   }

   private record TileKey(int lat, int lon) {
      String fileName() {
         return String.format(Locale.ROOT, TILE_PATTERN, formatLatLon(this.lat, this.lon));
      }

      private static String formatLatLon(int lat, int lon) {
         char latPrefix = (char)(lat >= 0 ? 78 : 83);
         char lonPrefix = (char)(lon >= 0 ? 69 : 87);
         int latAbs = Math.abs(lat);
         int lonAbs = Math.abs(lon);
         return String.format(Locale.ROOT, "%c%02d%c%03d", latPrefix, latAbs, lonPrefix, lonAbs);
      }
   }
}
