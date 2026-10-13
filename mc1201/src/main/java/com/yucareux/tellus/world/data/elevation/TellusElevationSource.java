package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.TellusEndpointConfig;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;
import com.yucareux.tellus.worldgen.TellusArnisElevation;
import com.yucareux.tellus.worldgen.TellusElevationRepair;
import com.yucareux.tellus.worldgen.TellusNative;

public final class TellusElevationSource implements TellusCacheHandle {
   private static final double EQUATOR_CIRCUMFERENCE = 4.0075017E7;
   private static final int TILE_SIZE = 256;
   private static final int MIN_ZOOM = 0;
   private static final int LAND_MAX_ZOOM = 15;
   private static final int OCEAN_MAX_ZOOM = 10;
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final double POLAR_NORTH_MIN_LAT = 60.0;
   private static final double POLAR_SOUTH_MAX_LAT = -60.0;
   private static final double RESOLUTION_METERS = 30.0;
   private static final String DEFAULT_ENDPOINT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium";
   // Lazy initialization so the configuration is loaded first.
   private static String getEndpoint() {
      return TellusEndpointConfig.getElevationEndpoint(DEFAULT_ENDPOINT);
   }
   private static final int MAX_CACHE_TILES = intProperty("tellus.elevation.cacheTiles", 512);
   private static final int CACHE_CONCURRENCY = cacheConcurrency();
   private static final int PREFETCH_THREADS = prefetchThreads();
   /** Upper bound on prefetch queueing: enough to cover a few screens of terrain without piling tens of thousands of tasks onto a weak machine. */
   private static final int PREFETCH_QUEUE_CAPACITY = intProperty("tellus.elevation.prefetchQueue", 4096);
   /**
    * How long {@link #prefetchTilesBlocking} may block the caller, in milliseconds.
    *
    * <p>Why an upper bound is mandatory: the caller is a <b>chunk generation thread</b>, and this
    * mod learned the hard way that "the server stalls a single tick for 4 to 24 seconds" is a
    * fatal regression. Two seconds covers the real cases (a whole chunk is missing only 4 to 5
    * tiles at 200 to 400 ms each, and they download in parallel, so 0.4 to 0.8 s should be enough),
    * while still leaving an exit on a slow or broken network so the generation thread is never pinned.
    */
   private static final long BLOCKING_PREFETCH_BUDGET_MILLIS = 2000L;
   // The normalized cache currently incurs a very expensive first-build path on cache misses.
   // Keep it opt-in until the ingest/build cost is low enough for preview and spawn-time terrain reads.
   private static final boolean NORMALIZED_ENABLED = booleanProperty("tellus.elevation.normalized.enabled", false);
   private static final boolean NORMALIZED_COMPARE = booleanProperty("tellus.elevation.normalized.compare", false);
   private static final int NORMALIZED_COMPARE_LOG_LIMIT = intProperty("tellus.elevation.normalized.compareLogLimit", 100);
   private static final boolean DEBUG_DEM = Boolean.getBoolean("tellus.debug.dem");
   /**
    * 2026-09-26: whether a memory-only sample falls back to a disk-only read.
    *
    * <p>It is on by default. See {@link #getTileMemoryOnly}: when a tile is not in memory and the
    * disk is not consulted, the chunk height depends on <b>memory state</b>, because a chunk that
    * hits memory and a chunk that misses compute different heights. The visible symptom is
    * <b>squares or a checkerboard pattern</b> all over the map, seen on a real server.
    *
    * <p>To get the old strictly-memory-only behaviour back, set
    * {@code -Dtellus.elevation.memoryOnlyDiskFallback=false}. The cost is that a chunk with a
    * missing tile falls back to a sea level platform, which is that same square bump.
    */
   private static final boolean MEMORY_ONLY_DISK_FALLBACK = booleanProperty("tellus.elevation.memoryOnlyDiskFallback", true);
   private static final ShortRaster MISSING_RASTER = ShortRaster.create(1, 1);
   /**
    * 2026-10-05: <b>the</b> "no elevation" value of this class, in memory. The full policy is documented on
    * {@link #storedElevationMeters}.
    *
    * <p>NaN, and not {@code 0.0}: {@code 0.0} is a real elevation (sea level) that the water resolver and the
    * preview colour legend have to special case, while NaN is what every miss already returns
    * ({@code sampleAtZoom*}, {@code samplePixel*}, {@code PolarDemSample.none()}) and the only value
    * {@code EarthChunkGenerator.sampleSurfaceHeightForHeightGrid} tests to report a miss <b>honestly</b>
    * instead of inventing a sea level height.
    */
   private static final double NO_ELEVATION_METERS = Double.NaN;
   private static final NormalizedElevationCache NORMALIZED_CACHE = NORMALIZED_ENABLED ? new NormalizedElevationCache() : null;
   private static final AtomicInteger NORMALIZED_COMPARE_LOGGED = new AtomicInteger();
   private final Path cacheRoot;
   private final LoadingCache<TellusElevationSource.TileKey, ShortRaster> cache;
   /** OPT-1: the background prefetch thread pool (daemon threads, it does not keep the JVM alive). */
   private final ThreadPoolExecutor prefetchExecutor;
   /** OPT-1: tiles already queued or in flight, so the same tile is never submitted twice. */
   private final java.util.Set<TellusElevationSource.TileKey> prefetchQueue = ConcurrentHashMap.newKeySet();
   private final ArcticDemElevationSource arcticDem = new ArcticDemElevationSource();
   private final RemaElevationSource rema = new RemaElevationSource();
   private final SwissAlti3dElevationSource swissAlti3d = new SwissAlti3dElevationSource();
   private final AhnElevationSource ahn = new AhnElevationSource();
   private final CanElevationSource canElevation = new CanElevationSource();
   private final NorwayDtm1ElevationSource norwayDtm1 = new NorwayDtm1ElevationSource();
   private final JapanGsiElevationSource japanGsi = new JapanGsiElevationSource();
   private final Usgs3depElevationSource usgs = new Usgs3depElevationSource();
   private final CopernicusDemElevationSource copernicus = new CopernicusDemElevationSource();
   private final TerrainTilesResolutionIndex terrainResolutionIndex = TerrainTilesResolutionIndex.create();
   private final TellusLandMaskSource landMask = TellusWorldgenSources.landMask();
   private volatile EarthGeneratorSettings.DemSelection lastLoggedSelection;

   public TellusElevationSource() {
      this.prefetchExecutor = new ThreadPoolExecutor(
         PREFETCH_THREADS, PREFETCH_THREADS, 30L, TimeUnit.SECONDS,
         new ArrayBlockingQueue<>(PREFETCH_QUEUE_CAPACITY),
         runnable -> {
            Thread thread = new Thread(runnable, "tellus-dem-prefetch");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
         });
      this.prefetchExecutor.allowCoreThreadTimeOut(true);
      this.cacheRoot = FabricLoader.getInstance().getGameDir().resolve("tellus/cache/elevation-tellus");
      // OPT-2: Guava defaults to concurrencyLevel=4. Chunk generation has dozens of threads looking up
      // elevation column by column, so 4 striped locks turn "look up the nearby terrain" into a queue.
      this.cache = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_TILES)
         .concurrencyLevel(CACHE_CONCURRENCY)
         .build(new CacheLoader<TellusElevationSource.TileKey, ShortRaster>() {
         public ShortRaster load( TellusElevationSource.TileKey key) throws Exception {
            return TellusDownloadExecutor.load("TellusElevationSource", key, TellusElevationSource.this::loadTile);
         }
      });
      TellusCacheRegistry.register(this);
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale) {
      return this.sampleElevationMeters(blockX, blockZ, worldScale, true, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public double sampleElevationMeters(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      return this.sampleElevationMeters(blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public double sampleElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      //  2026-10-05: `Double.isFinite(worldScale)` and **not** `worldScale <= 0.0` at this entry point.
      //  Every IEEE-754 comparison against NaN is false, so `worldScale <= 0.0` does not reject a NaN scale
      //  (and neither does the positive form `worldScale > 0.0`, which this file also used elsewhere).
      //  A NaN scale then makes `EarthProjection.blocksPerDegree` return NaN, every latitude and longitude NaN,
      //  and the negated range guards below pass (`!(NaN < MIN_LAT)` is TRUE and `!(NaN > MAX_LAT)` is TRUE as
      //  well), so `Mth.floor(NaN) == 0` and the whole column is read from DEM tile **(0,0)**, the map's
      //  north-west corner. `Double.isFinite` closes that and also rejects both infinities, which the
      //  comparison forms let through as well: `blocksPerDegree(+Infinity) == 0.0`, so `lon = blockX / 0.0`.
      //  `worldScale <= 0.0` is deliberately still handled below, it is a supported flat world configuration.
      if (!Double.isFinite(worldScale)) {
         return NO_ELEVATION_METERS;
      }

      // Data source dispatch: when arnis is configured (Mapterhorn / planetary DEM) ask it first, and
      // only fall back to the original chain when it returns null.
      Double arnis = TellusArnisElevation.sampleMeters(blockX, blockZ, worldScale);
      if (arnis != null) {
         //  NaN is let through as is, it means "no data"; only finite bad samples below -12000 m are clamped
         //  here. Mapterhorn already filters its exact NO_DATA (see MapterhornElevationSource:118),
         //  but its decoded lower bound is Short.MIN_VALUE+1 = -32767, which is not NO_DATA and slips past
         //  that filter. Such near-sentinel values follow the old "a finite value escapes every isNaN
         return arnis < EarthGeneratorSettings.DEM_NODATA_METERS ? EarthGeneratorSettings.DEM_NODATA_METERS : arnis;
      }
      return this.sampleElevationMeters(blockX, blockZ, worldScale, highResOcean, demSelection, worldScale);
   }

   public double samplePreviewElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleElevationMeters(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   public double samplePreviewElevationMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      //  2026-10-05: `Double.isFinite(worldScale)` and not `worldScale <= 0.0`, see the full reason on
      //  `sampleElevationMeters` above: a NaN scale is not rejected by a `<= 0.0` test (every NaN comparison is
      //  false) and then reads DEM tile (0,0), the north-west corner, for every column of the chunk.
      //  A non-finite scale is a bug, not a configuration, so it reports "no data" through the class sentinel.
      if (!Double.isFinite(worldScale)) {
         return NO_ELEVATION_METERS;
      } else if (worldScale <= 0.0) {
         return 0.0;
      } else {
         return this.sampleElevationMetersFromProvidersLocalOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   public double samplePreviewElevationMetersMemoryOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      //  2026-10-05: `Double.isFinite(worldScale)` and not `worldScale <= 0.0`, see `sampleElevationMeters`
      //  above for why the comparison form lets a NaN scale through and lands on DEM tile (0,0).
      //  Both rejected branches already returned NaN, so this only closes the NaN hole, nothing else moves.
      if (!Double.isFinite(worldScale) || worldScale <= 0.0) {
         return NO_ELEVATION_METERS;
      } else {
         return this.sampleElevationMetersFromProvidersMemoryOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   public double samplePreviewTerrainTilesMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      //  2026-10-05: this entry point had **no** guard at all. A NaN scale reaches `sampleAtZoomLocalOnly`,
      //  whose negated lat/lon range test also accepts NaN, and the sample is then taken from DEM tile (0,0),
      //  the map's north-west corner. `Double.isFinite` and not `worldScale <= 0.0`: every comparison against
      //  NaN is false, so the comparison form would not reject it. `worldScale <= 0.0` keeps its old `0.0`,
      //  because that is the deliberate flat world configuration.
      if (!Double.isFinite(worldScale)) {
         return NO_ELEVATION_METERS;
      } else if (worldScale <= 0.0) {
         return 0.0;
      } else {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }
   }

   private double sampleElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      //  2026-10-05: `Double.isFinite(worldScale)` and not `worldScale <= 0.0`, see `sampleElevationMeters`
      //  above: a NaN scale is not rejected by `<= 0.0`, and then `blocksPerDegree`, every lat/lon and every
      //  tile coordinate are NaN, which collapses the sample onto DEM tile (0,0).
      if (!Double.isFinite(worldScale)) {
         return NO_ELEVATION_METERS;
      } else if (worldScale <= 0.0) {
         return 0.0;
      } else {
         if (DEBUG_DEM && !Objects.equals(demSelection, this.lastLoggedSelection)) {
            this.lastLoggedSelection = demSelection;
            Tellus.LOGGER.info(
              "DEM selection is set: automatic={}, providers={}.",
               demSelection.automatic(),
               String.join(",", demSelection.enabledProviderIds())
            );
         }

         if (NORMALIZED_ENABLED) {
            try {
               TellusElevationSource.ElevationDiagnostic normalized = this.sampleDiagnosticFromNormalizedCache(
                  blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters
               );
               this.compareNormalizedElevation(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters, normalized.elevation());
               return normalized.elevation();
            } catch (RuntimeException error) {
              Tellus.LOGGER.debug("Normalized cache missed at {},{}, falling back to direct DEM sampling", blockX, blockZ, error);
            }
         }

         return this.sampleElevationMetersFromProviders(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   private double sampleElevationMetersFromProviders(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return demSelection.isAllEnabled()
         ? this.sampleElevationMetersFromLegacyProvider(
            blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         )
         : this.sampleFilteredElevationMeters(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleElevationMetersFromProvidersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return demSelection.isAllEnabled()
         ? this.sampleElevationMetersFromLegacyProviderLocalOnly(
            blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         )
         : this.sampleFilteredElevationMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleElevationMetersFromProvidersMemoryOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleTerrariumMetersMemoryOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private double sampleElevationMetersFromLegacyProvider(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemProvider demProvider,
      double previewResolutionMeters
   ) {
      return switch (demProvider) {
            case AUTO -> this.sampleAutomaticMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case SWISSALTI3D -> {
               SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield swiss.usable() ? swiss.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case AHN -> {
               AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
               yield ahn.usable() ? ahn.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case CANELEVATION -> {
               CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield canada.usable() ? canada.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case NORWAYDTM1 -> {
               NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield norway.usable() ? norway.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case JAPANGSI -> {
               JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield japan.usable()
                  ? japan.elevation()
                  : this.sampleAutomaticMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, false);
            }
            case USGS -> this.sampleUsgsMetersWithFallback(blockX, blockZ, worldScale, highResOcean);
            case COPERNICUS -> this.sampleCopernicusPreferredMeters(blockX, blockZ, worldScale, highResOcean);
            case ARCTICDEM -> {
               TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
               yield polar.usable() ? polar.elevation() : this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case TERRARIUM -> this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         };
   }

   private double sampleElevationMetersFromLegacyProviderLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemProvider demProvider,
      double previewResolutionMeters
   ) {
      return switch (demProvider) {
            case AUTO -> this.sampleAutomaticMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case SWISSALTI3D -> {
               SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield swiss.usable()
                  ? swiss.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case AHN -> {
               AhnElevationSource.Sample ahn = this.ahn.sampleLocalOnly(blockX, blockZ, worldScale);
               yield ahn.usable()
                  ? ahn.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case CANELEVATION -> {
               CanElevationSource.Sample canada = this.canElevation.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield canada.usable()
                  ? canada.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case NORWAYDTM1 -> {
               NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield norway.usable()
                  ? norway.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case JAPANGSI -> {
               JapanGsiElevationSource.Sample japan = this.japanGsi.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
               yield japan.usable()
                  ? japan.elevation()
                  : this.sampleAutomaticMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, false);
            }
            case USGS -> this.sampleUsgsMetersWithFallbackLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case COPERNICUS -> this.sampleCopernicusPreferredMetersLocalOnly(
               blockX, blockZ, worldScale, highResOcean, previewResolutionMeters
            );
            case ARCTICDEM -> {
               TellusElevationSource.PolarDemSample polar = this.samplePolarDemLocalOnly(blockX, blockZ, worldScale);
               yield polar.usable()
                  ? polar.elevation()
                  : this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case TERRARIUM -> this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         };
   }

   public TellusElevationSource.ElevationDiagnostic sampleDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, EarthGeneratorSettings.DemSelection demSelection
   ) {
      return this.sampleDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, worldScale);
   }

   public TellusElevationSource.ElevationDiagnostic samplePreviewDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return this.sampleDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      //  2026-10-05: `Double.isFinite(worldScale)` and not `worldScale <= 0.0`, see `sampleElevationMeters`
      //  above: a NaN scale survives `<= 0.0` and turns every provider decision and every tile coordinate into
      //  NaN, which is then read as "this column is in the provider's coverage" and resolves to DEM tile (0,0).
      if (!Double.isFinite(worldScale)) {
         return diagnostic(NO_ELEVATION_METERS, TellusElevationSource.DemUsage.TERRAIN_TILES);
      } else if (worldScale <= 0.0) {
         return diagnostic(0.0, TellusElevationSource.DemUsage.TERRAIN_TILES);
      } else {
         if (NORMALIZED_ENABLED) {
            try {
               TellusElevationSource.ElevationDiagnostic normalized = this.sampleDiagnosticFromNormalizedCache(
                  blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters
               );
               this.compareNormalizedElevation(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters, normalized.elevation());
               return normalized;
            } catch (RuntimeException error) {
              Tellus.LOGGER.debug("Normalized cache missed at {},{}, falling back to direct DEM diagnostics", blockX, blockZ, error);
            }
         }

         return this.sampleDiagnosticFromProviders(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnosticFromProviders(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      return demSelection.isAllEnabled()
         ? this.sampleDiagnosticFromLegacyProvider(
            blockX, blockZ, worldScale, highResOcean, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         )
         : this.sampleFilteredDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnosticFromLegacyProvider(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemProvider demProvider,
      double previewResolutionMeters
   ) {
      return switch (demProvider) {
            case AUTO -> this.sampleAutomaticDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            case SWISSALTI3D -> {
               SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield swiss.usable()
                  ? diagnostic(swiss.elevation(), swiss.usage(), swiss.usage().bit(), swiss.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case AHN -> {
               AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
               yield ahn.usable()
                  ? diagnostic(ahn.elevation(), ahn.usage(), ahn.usage().bit(), ahn.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case CANELEVATION -> {
               CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield canada.usable()
                  ? diagnostic(canada.elevation(), canada.usage(), canada.usage().bit(), canada.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case NORWAYDTM1 -> {
               NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield norway.usable()
                  ? diagnostic(norway.elevation(), norway.usage(), norway.usage().bit(), norway.resolutionMeters())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case JAPANGSI -> {
               JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
               yield japan.usable()
                  ? diagnostic(japan.elevation(), japan.usage(), japan.usage().bit(), japan.resolutionMeters())
                  : this.sampleAutomaticDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, false);
            }
            case USGS -> this.sampleUsgsMetersWithFallbackDiagnostic(blockX, blockZ, worldScale, highResOcean);
            case COPERNICUS -> this.sampleCopernicusPreferredDiagnostic(blockX, blockZ, worldScale, highResOcean);
            case ARCTICDEM -> {
               TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
               yield polar.usable()
                  ? diagnostic(polar.elevation(), polar.usage())
                  : this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
            }
            case TERRARIUM -> this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         };
   }

   private double sampleFilteredElevationMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D)) {
         SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (swiss.usable()) {
            return swiss.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN)) {
         AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
         if (ahn.usable()) {
            return ahn.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION)) {
         CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (canada.usable()) {
            return canada.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1)) {
         NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (norway.usable()) {
            return norway.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI)) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
         if (polar.usable()) {
            return polar.elevation();
         }
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
            double usgsSample = this.sampleUsgsPreferredMeters(blockX, blockZ, worldScale, highResOcean, decision.landMaskSample());
            if (!Double.isNaN(usgsSample)) {
               return usgsSample;
            }
         }

         if (demSelection.copernicusEnabled()) {
            double copernicusSample = this.sampleCopernicusPreferredMeters(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
            );
            if (!Double.isNaN(copernicusSample)) {
               return copernicusSample;
            }
         }
      } else if (decision.preferCopernicus() && demSelection.copernicusEnabled()) {
         double copernicusSample = this.sampleCopernicusPreferredMeters(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleFinalFallbackMeters(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleFilteredElevationMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D)) {
         SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (swiss.usable()) {
            return swiss.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN)) {
         AhnElevationSource.Sample ahn = this.ahn.sampleLocalOnly(blockX, blockZ, worldScale);
         if (ahn.usable()) {
            return ahn.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION)) {
         CanElevationSource.Sample canada = this.canElevation.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (canada.usable()) {
            return canada.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1)) {
         NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (norway.usable()) {
            return norway.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI)) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         TellusElevationSource.PolarDemSample polar = this.samplePolarDemLocalOnly(blockX, blockZ, worldScale);
         if (polar.usable()) {
            return polar.elevation();
         }
      }

      TellusElevationSource.AutoDecision decision = this.autoDecisionLocalOnly(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
            double usgsSample = this.sampleUsgsPreferredMetersLocalOnly(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), previewResolutionMeters
            );
            if (!Double.isNaN(usgsSample)) {
               return usgsSample;
            }
         }

         if (demSelection.copernicusEnabled()) {
            double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled(), previewResolutionMeters
            );
            if (!Double.isNaN(copernicusSample)) {
               return copernicusSample;
            }
         }
      } else if (decision.preferCopernicus() && demSelection.copernicusEnabled()) {
         double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled(), previewResolutionMeters
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleFinalFallbackMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleFilteredDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D)) {
         SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (swiss.usable()) {
            return diagnostic(swiss.elevation(), swiss.usage(), swiss.usage().bit(), swiss.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN)) {
         AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
         if (ahn.usable()) {
            return diagnostic(ahn.elevation(), ahn.usage(), ahn.usage().bit(), ahn.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION)) {
         CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (canada.usable()) {
            return diagnostic(canada.elevation(), canada.usage(), canada.usage().bit(), canada.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1)) {
         NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (norway.usable()) {
            return diagnostic(norway.elevation(), norway.usage(), norway.usage().bit(), norway.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI)) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return diagnostic(japan.elevation(), japan.usage(), japan.usage().bit(), japan.resolutionMeters());
         }
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
         if (polar.usable()) {
            return diagnostic(polar.elevation(), polar.usage());
         }
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
            TellusElevationSource.ElevationDiagnostic usgsSample = this.sampleUsgsPreferredDiagnostic(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample()
            );
            if (!Double.isNaN(usgsSample.elevation())) {
               return usgsSample;
            }
         }

         if (demSelection.copernicusEnabled()) {
            TellusElevationSource.ElevationDiagnostic copernicusSample = this.sampleCopernicusPreferredDiagnostic(
               blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
            );
            if (!Double.isNaN(copernicusSample.elevation())) {
               return copernicusSample;
            }
         }
      } else if (decision.preferCopernicus() && demSelection.copernicusEnabled()) {
         TellusElevationSource.ElevationDiagnostic copernicusSample = this.sampleCopernicusPreferredDiagnostic(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), demSelection.terrainTilesEnabled()
         );
         if (!Double.isNaN(copernicusSample.elevation())) {
            return copernicusSample;
         }
      }

      return this.sampleFinalFallbackDiagnostic(blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters);
   }

   private double sampleFinalFallbackMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.terrainTilesEnabled()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (demSelection.copernicusEnabled()) {
         return this.sampleCopernicusPreferredMeters(
            blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), false
         );
      }

      return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private double sampleFinalFallbackMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.terrainTilesEnabled()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (demSelection.copernicusEnabled()) {
         double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
            blockX,
            blockZ,
            worldScale,
            highResOcean,
            this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale),
            false,
            previewResolutionMeters
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleFinalFallbackDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.terrainTilesEnabled()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (demSelection.copernicusEnabled()) {
         return this.sampleCopernicusPreferredDiagnostic(
            blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), false
         );
      }

      return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   public void prefetchTiles(double blockX, double blockZ, double worldScale, int radius) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, EarthGeneratorSettings.DEFAULT.demSelection());
   }

   public void prefetchTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      this.prefetchTiles(blockX, blockZ, worldScale, radius, demSelection, worldScale);
   }

   public void prefetchTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      // The whole ring of prefetches, including the fan-out of every DEM provider, goes to the download pool:
      // the caller may be the server main thread and that synchronous fetch would stall the "join world" frame.
      TellusDownloadExecutor.prefetch("prefetch-ring",
            blockX + "," + blockZ + "," + worldScale + "," + radius + "," + previewResolutionMeters,
            ignored -> {
               this.prefetchRingInline(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
               return Boolean.TRUE;
            });
   }

   /**
    * 2026-09-29: fill the tiles in **synchronously**, without pushing them onto the download pool.
    *
    * <p><b>Why this has to exist</b>: {@link #prefetchTiles} is <b>asynchronous</b>, it returns as soon as the
    * work has been submitted. The chunk generation thread samples right away while the tile has <b>not arrived yet</b>,
    * gets {@code NaN}, records a miss, and {@code fillMissingTerrainShellHeights} <b>invents one height to flatten all 256 columns</b>.
    *
    * <p>Real incident: a player on the <b>Tibetan Plateau</b> (surface about 4150) whose chunk lay far from the
    * preload area around Guangzhou got not a single elevation sample, so the whole chunk was filled to <b>sea level 64</b>,
    * over <b>4000 blocks</b> from its neighbours, which the player saw as a screen full of <b>vertical cliffs</b>.
    *
    * <p><b>This method waits here</b>: it serves the caller that already knows it lacks data and genuinely needs the
    * real terrain. The cost is a slower call, and what it buys is <b>real terrain instead of invented terrain</b>.
    */
   public void prefetchTilesBlocking(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      this.prefetchRingInline(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
      this.awaitPrefetchDrain(BLOCKING_PREFETCH_BUDGET_MILLIS);
   }

   /**
    * Waits until the prefetch queue has drained, meaning every tile of this ring that can be fetched is already cached,
    * and waits at most {@link #BLOCKING_PREFETCH_BUDGET_MILLIS} milliseconds.
    *
    * <p><b>Why queue and drain instead of one {@code cache.get} per tile on the calling thread</b>: fetching them
    * one by one pulls the (2r+1) squared tiles <b>serially</b>. The radius the caller passes counts <b>blocks</b>, while
    * here it is read as <b>tiles</b>, so r=13 means 27x27, or <b>729</b> tiles, and the serial worst case is 729 x 90 seconds.
    * Queueing goes through the existing prefetch and download pools, which are parallel and already de-duplicate by key,
    *
    * and this method is only responsible for <b>waiting</b>. A timeout <b>does not throw</b>: it returns when the time
    * is up so the caller runs {@code resampleMissingShellHeights} as usual. Everything fetchable has been fetched, and
    */
   private void awaitPrefetchDrain(long budgetMillis) {
      long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
      while (!this.prefetchQueue.isEmpty()) {
         if (System.nanoTime() - deadlineNanos >= 0L) {
            return;
         }

         try {
            Thread.sleep(5L);
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
         }
      }
   }

   private void prefetchRingInline(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      //  2026-10-05: this guard was written as `!(worldScale <= 0.0)`, and the negated form is exactly the
      //  broken one: `NaN <= 0.0` is false, so `!(false)` is **true** and a NaN scale walked straight into the
      //  whole prefetch ring. Every comparison against NaN is false, so the positive form
      //  `Double.isFinite(worldScale) && worldScale > 0.0` is the one that rejects it, and it rejects both
      //  infinities too (`blocksPerDegree(+Infinity) == 0.0`).
      if (Double.isFinite(worldScale) && worldScale > 0.0) {
         if (NORMALIZED_ENABLED) {
            this.prefetchNormalizedTiles(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
            return;
         }

         this.prefetchTilesFromProviders(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
      }
   }

   private void prefetchTilesFromProviders(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isAllEnabled()) {
         this.prefetchTilesFromLegacyProvider(
            blockX, blockZ, worldScale, radius, EarthGeneratorSettings.DemProvider.AUTO, previewResolutionMeters
         );
         return;
      }

      this.prefetchFilteredTiles(blockX, blockZ, worldScale, radius, demSelection, previewResolutionMeters);
   }

   private void prefetchTilesFromLegacyProvider(
      double blockX, double blockZ, double worldScale, int radius, EarthGeneratorSettings.DemProvider demProvider, double previewResolutionMeters
   ) {
         TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
         boolean manualSwissAlti3d = demProvider == EarthGeneratorSettings.DemProvider.SWISSALTI3D;
         boolean autoSwissAlti3d = demProvider == EarthGeneratorSettings.DemProvider.AUTO && this.shouldPreferSwissAlti3d(blockX, blockZ, worldScale);
         if (manualSwissAlti3d || autoSwissAlti3d) {
            this.swissAlti3d.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualSwissAlti3d) {
            return;
         }

         boolean manualAhn = demProvider == EarthGeneratorSettings.DemProvider.AHN;
         boolean autoAhn = !autoSwissAlti3d && demProvider == EarthGeneratorSettings.DemProvider.AUTO && this.shouldPreferAhn(blockX, blockZ, worldScale);
         if (manualAhn || autoAhn) {
            this.ahn.prefetchTiles(blockX, blockZ, worldScale, radius);
         }

         if (manualAhn) {
            return;
         }

         boolean manualCanElevation = demProvider == EarthGeneratorSettings.DemProvider.CANELEVATION;
         boolean autoCanElevation = !autoSwissAlti3d
            && !autoAhn
            && demProvider == EarthGeneratorSettings.DemProvider.AUTO
            && this.shouldPreferCanElevation(blockX, blockZ, worldScale);
         if (manualCanElevation || autoCanElevation) {
            this.canElevation.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualCanElevation) {
            return;
         }

         boolean manualNorwayDtm1 = demProvider == EarthGeneratorSettings.DemProvider.NORWAYDTM1;
         boolean autoNorwayDtm1 = !autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && demProvider == EarthGeneratorSettings.DemProvider.AUTO
            && this.shouldPreferNorwayDtm1(blockX, blockZ, worldScale);
         if (manualNorwayDtm1 || autoNorwayDtm1) {
            this.norwayDtm1.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualNorwayDtm1) {
            return;
         }

         boolean manualJapanGsi = demProvider == EarthGeneratorSettings.DemProvider.JAPANGSI;
         boolean autoJapanGsi = !autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && !autoNorwayDtm1
            && demProvider == EarthGeneratorSettings.DemProvider.AUTO
            && this.shouldPreferJapanGsi(blockX, blockZ, worldScale);
         if (manualJapanGsi || autoJapanGsi) {
            this.japanGsi.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }

         if (manualJapanGsi) {
            return;
         }

         TellusElevationSource.AutoDecision autoDecision = demProvider == EarthGeneratorSettings.DemProvider.AUTO
            ? this.autoDecision(blockX, blockZ, worldScale)
            : TellusElevationSource.AutoDecision.DO_NOT_PREFER;
         if (autoSwissAlti3d || autoAhn || autoCanElevation || autoNorwayDtm1 || autoJapanGsi) {
            autoDecision = TellusElevationSource.AutoDecision.DO_NOT_PREFER;
         }

         if (demProvider == EarthGeneratorSettings.DemProvider.ARCTICDEM || demProvider == EarthGeneratorSettings.DemProvider.AUTO) {
            if (!autoSwissAlti3d && !autoAhn && !autoCanElevation && !autoNorwayDtm1 && !autoJapanGsi) {
               this.prefetchPolarDem(blockX, blockZ, worldScale, radius);
            }
         }

         if (!autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && !autoNorwayDtm1
            && !autoJapanGsi
            && (demProvider == EarthGeneratorSettings.DemProvider.USGS || autoDecision.preferUsgs())) {
            this.usgs.prefetchTiles(blockX, blockZ, worldScale, radius);
         }

         if (!autoSwissAlti3d
            && !autoAhn
            && !autoCanElevation
            && !autoNorwayDtm1
            && !autoJapanGsi
            && (demProvider == EarthGeneratorSettings.DemProvider.COPERNICUS || autoDecision.preferCopernicus() && !autoDecision.preferUsgs())) {
            this.copernicus.prefetchTiles(blockX, blockZ, worldScale, radius);
         }

         boolean autoPolar = demProvider == EarthGeneratorSettings.DemProvider.AUTO && latLon != null && isPolarCoverage(latLon.lat());
         boolean skipTerrainPrefetch = switch (demProvider) {
            case SWISSALTI3D, AHN, CANELEVATION, NORWAYDTM1, JAPANGSI, ARCTICDEM, USGS, COPERNICUS -> true;
            case AUTO -> autoSwissAlti3d || autoAhn || autoCanElevation || autoNorwayDtm1 || autoJapanGsi || autoPolar || autoDecision.preferUsgs()
               || autoDecision.preferCopernicus();
            default -> false;
         };
         boolean prefetchFallbackTerrain = switch (demProvider) {
            case SWISSALTI3D, AHN, CANELEVATION, NORWAYDTM1, JAPANGSI, ARCTICDEM, USGS, COPERNICUS -> true;
            case AUTO -> autoSwissAlti3d
               || autoAhn
               || autoCanElevation
               || autoNorwayDtm1
               || autoJapanGsi
               || autoPolar
               || autoDecision.preferUsgs()
               || autoDecision.preferCopernicus();
            default -> false;
         };
         if (!skipTerrainPrefetch || prefetchFallbackTerrain) {
            this.prefetchTerrainTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
         }
   }

   private void prefetchFilteredTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.SWISSALTI3D) && this.shouldPreferSwissAlti3d(blockX, blockZ, worldScale)) {
         this.swissAlti3d.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.AHN) && this.shouldPreferAhn(blockX, blockZ, worldScale)) {
         this.ahn.prefetchTiles(blockX, blockZ, worldScale, radius);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.CANELEVATION) && this.shouldPreferCanElevation(blockX, blockZ, worldScale)) {
         this.canElevation.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.NORWAYDTM1) && this.shouldPreferNorwayDtm1(blockX, blockZ, worldScale)) {
         this.norwayDtm1.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.JAPANGSI) && this.shouldPreferJapanGsi(blockX, blockZ, worldScale)) {
         this.japanGsi.prefetchTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
      }

      if (demSelection.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM)) {
         this.prefetchPolarDem(blockX, blockZ, worldScale, radius);
      }

      TellusElevationSource.AutoDecision autoDecision = this.autoDecision(blockX, blockZ, worldScale);
      if (autoDecision.preferUsgs() && demSelection.isEnabled(EarthGeneratorSettings.DemProvider.USGS)) {
         this.usgs.prefetchTiles(blockX, blockZ, worldScale, radius);
      }

      boolean shouldPrefetchCopernicus = demSelection.copernicusEnabled()
         && (!demSelection.terrainTilesEnabled() || autoDecision.preferUsgs() || autoDecision.preferCopernicus());
      if (shouldPrefetchCopernicus) {
         this.copernicus.prefetchTiles(blockX, blockZ, worldScale, radius);
      }

      this.prefetchTerrainTiles(blockX, blockZ, worldScale, radius, previewResolutionMeters);
   }

   private void prefetchTerrainTiles(double blockX, double blockZ, double worldScale, int radius, double previewResolutionMeters) {
      TellusElevationSource.ZoomPlan plan = this.zoomPlan(worldScale, previewResolutionMeters);
      if (plan.downsampleStep > 1) {
         blockX = downsampleBlock(blockX, plan.downsampleStep);
         blockZ = downsampleBlock(blockZ, plan.downsampleStep);
      }

      int zoom = plan.zoom;
      TellusElevationSource.TileKey center = tileKeyForBlock(blockX, blockZ, worldScale, zoom);
      if (center == null) {
         return;
      }

      int tilesPerAxis = 1 << zoom;
      int clampedRadius = Math.max(0, radius);
      int minX = Math.max(0, center.x() - clampedRadius);
      int maxX = Math.min(tilesPerAxis - 1, center.x() + clampedRadius);
      int minY = Math.max(0, center.y() - clampedRadius);
      int maxY = Math.min(tilesPerAxis - 1, center.y() + clampedRadius);

      for (int tileY = minY; tileY <= maxY; tileY++) {
         for (int tileX = minX; tileX <= maxX; tileX++) {
            this.prefetchTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
         }
      }
   }

   private void prefetchNormalizedTiles(
      double blockX,
      double blockZ,
      double worldScale,
      int radius,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double effectiveResolutionMeters = effectiveSampleResolutionMeters(worldScale, previewResolutionMeters);
      double projectedX = blockX * worldScale;
      double projectedZ = blockZ * worldScale;
      double projectedRadius = Math.max(1, radius) * TILE_SIZE * worldScale;
      NORMALIZED_CACHE.prefetchRange(
         projectedX - projectedRadius,
         projectedZ - projectedRadius,
         projectedX + projectedRadius,
         projectedZ + projectedRadius,
         effectiveResolutionMeters,
         demSelection,
         true,
         this::buildNormalizedTile
      );
   }

   private TellusElevationSource.ElevationDiagnostic sampleDiagnosticFromNormalizedCache(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters
   ) {
      double effectiveResolutionMeters = effectiveSampleResolutionMeters(worldScale, previewResolutionMeters);
      NormalizedElevationTileSample sample = NORMALIZED_CACHE.sample(
         blockX * worldScale, blockZ * worldScale, effectiveResolutionMeters, demSelection, highResOcean, this::buildNormalizedTile
      );
      return diagnostic(sample.elevationMeters(), sample.primaryProvider(), sample.providerMask(), sample.resolutionMeters());
   }

   private NormalizedElevationTile buildNormalizedTile(NormalizedElevationTileKey key) {
      this.prefetchNormalizedIngestSources(key);
      ShortRaster heights = ShortRaster.create(NormalizedElevationTileKey.TILE_SIZE, NormalizedElevationTileKey.TILE_SIZE);
      int sampleCount = NormalizedElevationTileKey.TILE_SIZE * NormalizedElevationTileKey.TILE_SIZE;
      byte[] primaryProviders = new byte[sampleCount];
      byte[] blendedFlags = new byte[TellusElevationProvenance.bitSetLength(sampleCount)];
      int providerMask = 0;
      double sampleResolutionMeters = key.sampleResolutionMeters();

      for (int localZ = 0; localZ < NormalizedElevationTileKey.TILE_SIZE; localZ++) {
         for (int localX = 0; localX < NormalizedElevationTileKey.TILE_SIZE; localX++) {
            double projectedX = key.sampleProjectedX(localX);
            double projectedZ = key.sampleProjectedZ(localZ);
            double pseudoBlockX = projectedX / sampleResolutionMeters;
            double pseudoBlockZ = projectedZ / sampleResolutionMeters;
            TellusElevationSource.ElevationDiagnostic diagnostic = this.sampleDiagnosticFromProviders(
               pseudoBlockX, pseudoBlockZ, sampleResolutionMeters, key.highResOcean(), key.demSelection(), sampleResolutionMeters
            );
            int sampleIndex = localX + localZ * NormalizedElevationTileKey.TILE_SIZE;
            //  2026-09-29: out of range and sentinel values are stopped right here, they never enter the cache.
            //  Measured over 527 real tiles the deepest value is -23070 m, while the deepest point on Earth
            //  is only -10994 m, so that reading is noise. `MapterhornElevationSource:54` has NO_DATA = Short.MIN_VALUE =
            //  -32768, and the old `Mth.clamp(..., Short.MIN_VALUE, Short.MAX_VALUE)` let it through unchanged.
            //  It is a finite value and not NaN, so every `Double.isNaN` guard on every path misses it.
            //  It keeps flowing into the height conversion and lands at Y = -6157, which clamps a whole area
            //  to the world floor and produces squares. Clamping to DEM_NODATA_METERS (-12000) lands at Y = -1936, which fits.
            //
            //  2026-10-05: the three local spellings of "no data" are gone, this is the single conversion:
            //    - `Double.isFinite(diagnostic.elevation()) ? ... : 0.0` used to write a **real 0.0 m elevation**
            //      (sea level) for every sample a provider had no data for, so a provider gap became a flat
            //      plain in the raster instead of the no-data floor it really was.
            //    - the following `if (< DEM_NODATA_METERS)` clamp and the `Mth.clamp(..., Short.MIN_VALUE, Short.MAX_VALUE)`
            //      that followed it are both inside `storedElevationMeters` now. The `Short.MIN_VALUE` lower bound of
            //      that clamp **was** the NO_DATA sentinel, so the clamp was a no-op for the one value it existed to reject.
            heights.set(localX, localZ, storedElevationMeters(diagnostic.elevation()));
            primaryProviders[sampleIndex] = (byte)diagnostic.primaryProvider().ordinal();
            providerMask |= diagnostic.providerMask();
            if (diagnostic.usesMultipleProviders()) {
               blendedFlags[sampleIndex >> 3] = (byte)(blendedFlags[sampleIndex >> 3] | 1 << (sampleIndex & 7));
            }
         }
      }

      return new NormalizedElevationTile(
         key,
         heights,
         new TellusElevationProvenance(
            NormalizedElevationTileKey.TILE_SIZE, NormalizedElevationTileKey.TILE_SIZE, providerMask, primaryProviders, blendedFlags
         )
      );
   }

   private void prefetchNormalizedIngestSources(NormalizedElevationTileKey key) {
      double sampleResolutionMeters = key.sampleResolutionMeters();
      double centerBlockX = key.sampleProjectedX(NormalizedElevationTileKey.TILE_SIZE / 2) / sampleResolutionMeters;
      double centerBlockZ = key.sampleProjectedZ(NormalizedElevationTileKey.TILE_SIZE / 2) / sampleResolutionMeters;

      try {
         this.prefetchTilesFromProviders(centerBlockX, centerBlockZ, sampleResolutionMeters, 1, key.demSelection(), sampleResolutionMeters);
      } catch (RuntimeException error) {
        Tellus.LOGGER.debug("Prefetching the data source for normalized elevation tile {} failed", key, error);
      }
   }

   private void compareNormalizedElevation(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      double normalizedElevationMeters
   ) {
      if (!NORMALIZED_COMPARE) {
         return;
      }

      double providerElevationMeters = this.sampleElevationMetersFromProviders(
         blockX, blockZ, worldScale, highResOcean, demSelection, previewResolutionMeters
      );
      if (Math.abs(providerElevationMeters - normalizedElevationMeters) > 1.0) {
         this.logNormalizedMismatch(blockX, blockZ, worldScale, demSelection, previewResolutionMeters, normalizedElevationMeters, providerElevationMeters);
      }
   }

   private void logNormalizedMismatch(
      double blockX,
      double blockZ,
      double worldScale,
      EarthGeneratorSettings.DemSelection demSelection,
      double previewResolutionMeters,
      double normalizedElevationMeters,
      double providerElevationMeters
   ) {
      int logged = NORMALIZED_COMPARE_LOGGED.getAndIncrement();
      if (logged < NORMALIZED_COMPARE_LOG_LIMIT) {
         Tellus.LOGGER.warn(
            "Normalized elevation mismatch: position {},{}, scale={}, selection={}, resolution={}, normalized={}, source={}",
            new Object[]{
               blockX,
               blockZ,
               worldScale,
               demSelection.fingerprint(),
               previewResolutionMeters,
               normalizedElevationMeters,
               providerElevationMeters
            }
         );
      }
   }

   public static boolean usesPolarDem(EarthGeneratorSettings.DemSelection demSelection) {
      return demSelection != null && demSelection.usesPolarDem();
   }

   public static double remaBoundaryBlockZ(double worldScale) {
      return worldScale > 0.0 ? EarthProjection.latToBlockZ(POLAR_SOUTH_MAX_LAT, worldScale) : Double.POSITIVE_INFINITY;
   }

   private double sampleAutomaticMeters(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      return this.sampleAutomaticMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, true);
   }

   private double sampleAutomaticMeters(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters, boolean allowJapanGsi
   ) {
      SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (swiss.usable()) {
         return swiss.elevation();
      }

      AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
      if (ahn.usable()) {
         return ahn.elevation();
      }

      CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (canada.usable()) {
         return canada.elevation();
      }

      NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (norway.usable()) {
         return norway.elevation();
      }

      if (allowJapanGsi) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
      if (polar.usable()) {
         return polar.elevation();
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         double usgsSample = this.sampleUsgsMetersWithFallback(blockX, blockZ, worldScale, highResOcean, decision.landMaskSample());
         if (!Double.isNaN(usgsSample)) {
            return usgsSample;
         }
      }

      if (decision.preferTerrainTiles()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
      }

      if (decision.preferCopernicus()) {
         double copernicusSample = this.sampleCopernicusPreferredMeters(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), false
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
   }

   private double sampleAutomaticMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleAutomaticMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, true);
   }

   private double sampleAutomaticMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters, boolean allowJapanGsi
   ) {
      SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
      if (swiss.usable()) {
         return swiss.elevation();
      }

      AhnElevationSource.Sample ahn = this.ahn.sampleLocalOnly(blockX, blockZ, worldScale);
      if (ahn.usable()) {
         return ahn.elevation();
      }

      CanElevationSource.Sample canada = this.canElevation.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
      if (canada.usable()) {
         return canada.elevation();
      }

      NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
      if (norway.usable()) {
         return norway.elevation();
      }

      if (allowJapanGsi) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sampleLocalOnly(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return japan.elevation();
         }
      }

      TellusElevationSource.PolarDemSample polar = this.samplePolarDemLocalOnly(blockX, blockZ, worldScale);
      if (polar.usable()) {
         return polar.elevation();
      }

      TellusElevationSource.AutoDecision decision = this.autoDecisionLocalOnly(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         double usgsSample = this.sampleUsgsMetersWithFallbackLocalOnly(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), previewResolutionMeters
         );
         if (!Double.isNaN(usgsSample)) {
            return usgsSample;
         }
      }

      if (decision.preferTerrainTiles()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      }

      if (decision.preferCopernicus()) {
         double copernicusSample = this.sampleCopernicusPreferredMetersLocalOnly(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), false, previewResolutionMeters
         );
         if (!Double.isNaN(copernicusSample)) {
            return copernicusSample;
         }
      }

      return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
   }

   private TellusElevationSource.ElevationDiagnostic sampleAutomaticDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleAutomaticDiagnostic(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters, true);
   }

   private TellusElevationSource.ElevationDiagnostic sampleAutomaticDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters, boolean allowJapanGsi
   ) {
      SwissAlti3dElevationSource.Sample swiss = this.swissAlti3d.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (swiss.usable()) {
         return diagnostic(swiss.elevation(), swiss.usage(), swiss.usage().bit(), swiss.resolutionMeters());
      }

      AhnElevationSource.Sample ahn = this.ahn.sample(blockX, blockZ, worldScale);
      if (ahn.usable()) {
         return diagnostic(ahn.elevation(), ahn.usage(), ahn.usage().bit(), ahn.resolutionMeters());
      }

      CanElevationSource.Sample canada = this.canElevation.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (canada.usable()) {
         return diagnostic(canada.elevation(), canada.usage(), canada.usage().bit(), canada.resolutionMeters());
      }

      NorwayDtm1ElevationSource.Sample norway = this.norwayDtm1.sample(blockX, blockZ, worldScale, previewResolutionMeters);
      if (norway.usable()) {
         return diagnostic(norway.elevation(), norway.usage(), norway.usage().bit(), norway.resolutionMeters());
      }

      if (allowJapanGsi) {
         JapanGsiElevationSource.Sample japan = this.japanGsi.sample(blockX, blockZ, worldScale, previewResolutionMeters);
         if (japan.usable()) {
            return diagnostic(japan.elevation(), japan.usage(), japan.usage().bit(), japan.resolutionMeters());
         }
      }

      TellusElevationSource.PolarDemSample polar = this.samplePolarDem(blockX, blockZ, worldScale);
      if (polar.usable()) {
         return diagnostic(polar.elevation(), polar.usage());
      }

      TellusElevationSource.AutoDecision decision = this.autoDecision(blockX, blockZ, worldScale);
      if (decision.preferUsgs()) {
         TellusElevationSource.ElevationDiagnostic usgsSample = this.sampleUsgsMetersWithFallbackDiagnostic(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample()
         );
         if (!Double.isNaN(usgsSample.elevation())) {
            return usgsSample;
         }
      }

      if (decision.preferTerrainTiles()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
      }

      if (decision.preferCopernicus()) {
         TellusElevationSource.ElevationDiagnostic copernicusSample = this.sampleCopernicusPreferredDiagnostic(
            blockX, blockZ, worldScale, highResOcean, decision.landMaskSample(), false
         );
         if (!Double.isNaN(copernicusSample.elevation())) {
            return copernicusSample;
         }
      }

      return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
   }

   private boolean shouldPreferSwissAlti3d(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.swissAlti3d.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferAhn(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.ahn.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferCanElevation(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.canElevation.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferNorwayDtm1(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.norwayDtm1.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferJapanGsi(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      return latLon != null && this.japanGsi.isLikelyInCoverage(latLon.lat(), latLon.lon());
   }

   private boolean shouldPreferTerrainTilesOverCopernicus(double lat, double lon) {
      double terrainResolutionMeters = this.terrainResolutionIndex.lookupResolutionMeters(lat, lon);
      return Double.isFinite(terrainResolutionMeters) && terrainResolutionMeters > 0.0 && terrainResolutionMeters < RESOLUTION_METERS;
   }

   private TellusElevationSource.PolarDemSample samplePolarDem(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() >= POLAR_NORTH_MIN_LAT) {
         double arctic = this.arcticDem.sampleElevationMeters(blockX, blockZ, worldScale);
         return !Double.isNaN(arctic)
            ? new TellusElevationSource.PolarDemSample(arctic, TellusElevationSource.DemUsage.ARCTICDEM)
            : TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() <= POLAR_SOUTH_MAX_LAT) {
         double rema = this.rema.sampleElevationMeters(blockX, blockZ, worldScale);
         return !Double.isNaN(rema)
            ? new TellusElevationSource.PolarDemSample(rema, TellusElevationSource.DemUsage.REMA)
            : TellusElevationSource.PolarDemSample.none();
      } else {
         return TellusElevationSource.PolarDemSample.none();
      }
   }

   private TellusElevationSource.PolarDemSample samplePolarDemLocalOnly(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() >= POLAR_NORTH_MIN_LAT) {
         double arctic = this.arcticDem.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         return !Double.isNaN(arctic)
            ? new TellusElevationSource.PolarDemSample(arctic, TellusElevationSource.DemUsage.ARCTICDEM)
            : TellusElevationSource.PolarDemSample.none();
      } else if (latLon.lat() <= POLAR_SOUTH_MAX_LAT) {
         double rema = this.rema.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         return !Double.isNaN(rema)
            ? new TellusElevationSource.PolarDemSample(rema, TellusElevationSource.DemUsage.REMA)
            : TellusElevationSource.PolarDemSample.none();
      } else {
         return TellusElevationSource.PolarDemSample.none();
      }
   }

   private void prefetchPolarDem(double blockX, double blockZ, double worldScale, int radius) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon != null) {
         if (latLon.lat() >= POLAR_NORTH_MIN_LAT) {
            this.arcticDem.prefetchTiles(blockX, blockZ, worldScale, radius);
         } else if (latLon.lat() <= POLAR_SOUTH_MAX_LAT) {
            this.rema.prefetchTiles(blockX, blockZ, worldScale, radius);
         }
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleUsgsMetersWithFallbackDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean
   ) {
      TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMask(blockX, blockZ, worldScale);
      return this.sampleUsgsMetersWithFallbackDiagnostic(blockX, blockZ, worldScale, highResOcean, landMaskSample);
   }

   private double sampleUsgsMetersWithFallback(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMask(blockX, blockZ, worldScale);
      return this.sampleUsgsMetersWithFallback(blockX, blockZ, worldScale, highResOcean, landMaskSample);
   }

   private double sampleUsgsMetersWithFallbackLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      TellusLandMaskSource.LandMaskSample landMaskSample = this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale);
      return this.sampleUsgsMetersWithFallbackLocalOnly(blockX, blockZ, worldScale, highResOcean, landMaskSample, previewResolutionMeters);
   }

   private double sampleUsgsMetersWithFallback(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      double sample = this.sampleUsgsPreferredMeters(blockX, blockZ, worldScale, highResOcean, landMaskSample);
      return !Double.isNaN(sample)
         ? sample
         : this.sampleCopernicusPreferredMeters(blockX, blockZ, worldScale, highResOcean, landMaskSample, true);
   }

   private double sampleUsgsMetersWithFallbackLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      double previewResolutionMeters
   ) {
      double sample = this.sampleUsgsPreferredMetersLocalOnly(
         blockX, blockZ, worldScale, highResOcean, landMaskSample, previewResolutionMeters
      );
      return !Double.isNaN(sample)
         ? sample
         : this.sampleCopernicusPreferredMetersLocalOnly(
            blockX, blockZ, worldScale, highResOcean, landMaskSample, true, previewResolutionMeters
         );
   }

   private TellusElevationSource.ElevationDiagnostic sampleUsgsMetersWithFallbackDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      TellusElevationSource.ElevationDiagnostic sample = this.sampleUsgsPreferredDiagnostic(blockX, blockZ, worldScale, highResOcean, landMaskSample);
      return !Double.isNaN(sample.elevation())
         ? sample
         : this.sampleCopernicusPreferredDiagnostic(blockX, blockZ, worldScale, highResOcean, landMaskSample, true);
   }

   private double sampleUsgsPreferredMeters(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.usgs.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
         } else {
            return sample;
         }
      }
   }

   private double sampleUsgsPreferredMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      double previewResolutionMeters
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      } else {
         double sample = this.usgs.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         } else {
            return sample;
         }
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleUsgsPreferredDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.usgs.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return diagnostic(Double.NaN, TellusElevationSource.DemUsage.USGS);
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
         } else {
            return diagnostic(sample, TellusElevationSource.DemUsage.USGS);
         }
      }
   }

   private double sampleCopernicusPreferredMeters(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      return this.sampleCopernicusPreferredMeters(
         blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), true
      );
   }

   private double sampleCopernicusPreferredMetersLocalOnly(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return this.sampleCopernicusPreferredMetersLocalOnly(
         blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale), true, previewResolutionMeters
      );
   }

   private TellusElevationSource.ElevationDiagnostic sampleCopernicusPreferredDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean
   ) {
      return this.sampleCopernicusPreferredDiagnostic(
         blockX, blockZ, worldScale, highResOcean, this.landMask.sampleLandMask(blockX, blockZ, worldScale), true
      );
   }

   private double sampleCopernicusPreferredMeters(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      boolean terrainLandFallback
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.copernicus.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return terrainLandFallback ? this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean) : Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean);
         } else {
            return sample;
         }
      }
   }

   private double sampleCopernicusPreferredMetersLocalOnly(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      boolean terrainLandFallback,
      double previewResolutionMeters
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
      } else {
         double sample = this.copernicus.sampleElevationMetersLocalOnly(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return terrainLandFallback ? this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters) : Double.NaN;
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.sampleTerrariumMetersLocalOnly(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters);
         } else {
            return sample;
         }
      }
   }

   private TellusElevationSource.ElevationDiagnostic sampleCopernicusPreferredDiagnostic(
      double blockX,
      double blockZ,
      double worldScale,
      boolean highResOcean,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      boolean terrainLandFallback
   ) {
      if (landMaskSample.known() && !landMaskSample.land()) {
         return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
      } else {
         double sample = this.copernicus.sampleElevationMeters(blockX, blockZ, worldScale);
         if (Double.isNaN(sample)) {
            return terrainLandFallback
               ? this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean)
               : diagnostic(Double.NaN, TellusElevationSource.DemUsage.COPERNICUS);
         } else if (sample <= 0.0 && highResOcean && !landMaskSample.known()) {
            return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean);
         } else {
            return diagnostic(sample, TellusElevationSource.DemUsage.COPERNICUS);
         }
      }
   }

   /**
    * An argument to result cache for autoDecision*, one entry per thread.
    *
    * <p>JFR shows that on the hot path every {@code autoDecision} call allocates a new {@code AutoDecision(...)} (197
    * allocations sampled). The same sample point is frequently asked about several times in a row within one flow, for
    * instance after line 518 computes a decision lines 529 and 537 want the decision for that same point, so caching
    * the result under "the previous arguments" is enough: it is invalidated the moment the arguments change and never
    *
    * reused across points. The cached value is an <b>immutable record</b>, so handing it out carries no aliasing risk,
    * and the one thing to watch is that a thread local cache never expires on its own.
    */
   private final ThreadLocal<TellusElevationSource.AutoDecisionCache> autoDecisionOnline =
      ThreadLocal.withInitial(TellusElevationSource.AutoDecisionCache::new);
   private final ThreadLocal<TellusElevationSource.AutoDecisionCache> autoDecisionLocal =
      ThreadLocal.withInitial(TellusElevationSource.AutoDecisionCache::new);

   private static final class AutoDecisionCache {
      private double blockX;
      private double blockZ;
      private double worldScale;
      private TellusElevationSource.AutoDecision decision;
      private boolean valid;
   }

   /**
    * The zoom and downsample step computed for every column are <b>pure functions</b> of
    * {@code (worldScale, previewResolutionMeters)}, and all 65536 columns of a chunk share the same arguments, yet the
    * old code reran {@code effectiveSampleResolutionMeters} (a max) plus {@code selectZoom} (a {@code Math.log} and one
    *
    * division) for every column. The same "previous arguments" cache is used here, exactly like {@code autoDecision*}.
    */
   private final ThreadLocal<TellusElevationSource.ZoomPlan> zoomPlanCache =
      ThreadLocal.withInitial(TellusElevationSource.ZoomPlan::new);

   private static final class ZoomPlan {
      private double worldScale;
      private double previewResolutionMeters;
      private int downsampleStep;
      private int zoom;
      private boolean valid;
   }

   private TellusElevationSource.ZoomPlan zoomPlan(double worldScale, double previewResolutionMeters) {
      TellusElevationSource.ZoomPlan plan = this.zoomPlanCache.get();
      if (plan.valid && plan.worldScale == worldScale && plan.previewResolutionMeters == previewResolutionMeters) {
         return plan;
      }

      plan.worldScale = worldScale;
      plan.previewResolutionMeters = previewResolutionMeters;
      plan.downsampleStep = downsampleStep(worldScale, RESOLUTION_METERS, previewResolutionMeters);
      plan.zoom = Mth.clamp(selectZoom(worldScale), MIN_ZOOM, LAND_MAX_ZOOM);
      plan.valid = true;
      return plan;
   }

   /**
    * The intermediate Mercator values recomputed for every column, {@code blocksPerDegree} / {@code lat} / {@code y},
    * are merely <b>pure functions</b> of {@code (worldScale)}, {@code (blockZ, worldScale)} and {@code (lat, zoom)}, and those
    * two {@code EarthProjection} methods only read static constants and carry no hidden state.
    *
    * <p>The height grid advances row by row with z on the outside and x on the inside (see {@code buildTerrainShellHeightGrid}),
    * and the 24 samples of one row all <b>share the same blockZ</b>. The old code reran {@code blockZToLat} ({@code sinh + atan})
    * plus {@code tan + cos + log} for every sample, which is five transcendental functions per sample. Caching them under the
    * "previous arguments" key removes all of that on a hit, invalidated as soon as the arguments change.
    */
   private final ThreadLocal<TellusElevationSource.GeoPlan> geoPlanCache =
      ThreadLocal.withInitial(TellusElevationSource.GeoPlan::new);

   private static final class GeoPlan {
      private double worldScale;
      private double blockZ;
      private double blocksPerDegree;
      private double lat;
      private boolean latValid;
      private int zoom;
      private double n;
      private double y;
      private boolean zoomValid;
   }

   private TellusElevationSource.GeoPlan geoPlan(double worldScale, int zoom, double blockZ) {
      TellusElevationSource.GeoPlan plan = this.geoPlanCache.get();
      if (plan.worldScale == worldScale && plan.blockZ == blockZ && plan.latValid) {
         if (plan.zoom != zoom || !plan.zoomValid) {
            plan.zoom = zoom;
            plan.n = (double)(1 << zoom);
            plan.y = mercatorY(plan.lat, plan.n);
            plan.zoomValid = true;
         }

         return plan;
      }

      plan.blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      plan.lat = EarthProjection.blockZToLat(blockZ, worldScale);
      plan.worldScale = worldScale;
      plan.blockZ = blockZ;
      plan.latValid = true;
      plan.zoom = zoom;
      plan.n = (double)(1 << zoom);
      plan.y = mercatorY(plan.lat, plan.n);
      plan.zoomValid = true;
      return plan;
   }

   /** Word for word isomorphic to the expression inlined in {@code sampleAtZoom*}, extracted only so the three caches cannot possibly compute a different y. */
   private static double mercatorY(double lat, double tileCount) {
      double latRad = Math.toRadians(lat);
      return (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * tileCount;
   }

   private TellusElevationSource.AutoDecision autoDecision(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.AutoDecisionCache cache = this.autoDecisionOnline.get();
      if (cache.valid && cache.blockX == blockX && cache.blockZ == blockZ && cache.worldScale == worldScale) {
         return cache.decision;
      }

      TellusElevationSource.AutoDecision computed = this.computeAutoDecision(blockX, blockZ, worldScale, false);
      cache.blockX = blockX;
      cache.blockZ = blockZ;
      cache.worldScale = worldScale;
      cache.decision = computed;
      cache.valid = true;
      return computed;
   }

   private TellusElevationSource.AutoDecision autoDecisionLocalOnly(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.AutoDecisionCache cache = this.autoDecisionLocal.get();
      if (cache.valid && cache.blockX == blockX && cache.blockZ == blockZ && cache.worldScale == worldScale) {
         return cache.decision;
      }

      TellusElevationSource.AutoDecision computed = this.computeAutoDecision(blockX, blockZ, worldScale, true);
      cache.blockX = blockX;
      cache.blockZ = blockZ;
      cache.worldScale = worldScale;
      cache.decision = computed;
      cache.valid = true;
      return computed;
   }

   /** The original autoDecision logic (localOnly only affects the step that fetches the land mask). */
   private TellusElevationSource.AutoDecision computeAutoDecision(
      double blockX, double blockZ, double worldScale, boolean localOnly
   ) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.AutoDecision.DO_NOT_PREFER;
      } else {
         boolean inUsgsRegion = isUsgsPreferredRegion(latLon.lat(), latLon.lon());
         boolean preferTerrainTiles = this.shouldPreferTerrainTilesOverCopernicus(latLon.lat(), latLon.lon());
         boolean preferUsgs = inUsgsRegion;
         boolean preferCopernicus = !preferUsgs && !preferTerrainTiles;

         TellusLandMaskSource.LandMaskSample landMaskSample = localOnly
            ? this.landMask.sampleLandMaskLocalOnly(blockX, blockZ, worldScale)
            : this.landMask.sampleLandMask(blockX, blockZ, worldScale);
         return !landMaskSample.known() || landMaskSample.land()
            ? new TellusElevationSource.AutoDecision(preferUsgs, preferTerrainTiles, preferCopernicus, landMaskSample)
            : TellusElevationSource.AutoDecision.DO_NOT_PREFER;
      }
   }

   private static boolean isUsgsPreferredRegion(double lat, double lon) {
      return isContiguousUsRegion(lat, lon) || isAlaskaRegion(lat, lon) || isHawaiiRegion(lat, lon) || isPuertoRicoRegion(lat, lon);
   }

   private static boolean isContiguousUsRegion(double lat, double lon) {
      return lat >= 24.0 && lat <= 49.8 && lon >= -125.0 && lon <= -66.0;
   }

   private static boolean isAlaskaRegion(double lat, double lon) {
      return lat >= 51.0 && lat <= 72.8 && lon >= -170.5 && lon <= -129.0;
   }

   private static boolean isHawaiiRegion(double lat, double lon) {
      return lat >= 18.5 && lat <= 22.7 && lon >= -160.8 && lon <= -154.2;
   }

   private static boolean isPuertoRicoRegion(double lat, double lon) {
      return lat >= 17.7 && lat <= 18.7 && lon >= -67.5 && lon <= -65.0;
   }

   private static boolean isPolarCoverage(double lat) {
      return lat >= POLAR_NORTH_MIN_LAT || lat <= POLAR_SOUTH_MAX_LAT;
   }

   private double sampleTerrariumMeters(double blockX, double blockZ, double worldScale, boolean highResOcean) {
      return this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, worldScale);
   }

   private double sampleTerrariumMetersLocalOnly(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      TellusElevationSource.ZoomPlan plan = this.zoomPlan(worldScale, previewResolutionMeters);
      if (plan.downsampleStep > 1) {
         blockX = downsampleBlock(blockX, plan.downsampleStep);
         blockZ = downsampleBlock(blockZ, plan.downsampleStep);
      }

      int zoom = plan.zoom;
      double sample = this.sampleAtZoomLocalOnly(blockX, blockZ, worldScale, zoom);
      if (!Double.isNaN(sample)) {
         if (sample <= 0.0 && highResOcean) {
            double oceanSample = this.sampleAtZoomLocalOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
            if (!Double.isNaN(oceanSample)) {
               return oceanSample;
            }
         }

         return sample;
      } else {
         double oceanSample = this.sampleAtZoomLocalOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
         return oceanSample;
      }
   }

   private double sampleTerrariumMetersMemoryOnly(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      TellusElevationSource.ZoomPlan plan = this.zoomPlan(worldScale, previewResolutionMeters);
      if (plan.downsampleStep > 1) {
         blockX = downsampleBlock(blockX, plan.downsampleStep);
         blockZ = downsampleBlock(blockZ, plan.downsampleStep);
      }

      int zoom = plan.zoom;
      double sample = this.sampleAtZoomMemoryOnly(blockX, blockZ, worldScale, zoom);
      if (!Double.isNaN(sample)) {
         if (sample <= 0.0 && highResOcean) {
            double oceanSample = this.sampleAtZoomMemoryOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
            if (!Double.isNaN(oceanSample)) {
               return oceanSample;
            }
         }

         return sample;
      } else {
         double oceanSample = this.sampleAtZoomMemoryOnly(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
         return oceanSample;
      }
   }

   private TellusElevationSource.ElevationDiagnostic terrainTilesDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean
   ) {
      return this.terrainTilesDiagnostic(blockX, blockZ, worldScale, highResOcean, worldScale);
   }

   private TellusElevationSource.ElevationDiagnostic terrainTilesDiagnostic(
      double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters
   ) {
      return diagnostic(
         this.sampleTerrariumMeters(blockX, blockZ, worldScale, highResOcean, previewResolutionMeters),
         TellusElevationSource.DemUsage.TERRAIN_TILES,
         TellusElevationSource.DemUsage.TERRAIN_TILES.bit(),
         this.terrainTilesResolutionMeters(blockX, blockZ, worldScale)
      );
   }

   private double terrainTilesResolutionMeters(double blockX, double blockZ, double worldScale) {
      TellusElevationSource.LatLon latLon = toLatLon(blockX, blockZ, worldScale);
      if (latLon == null) {
         return TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters();
      } else {
         double resolutionMeters = this.terrainResolutionIndex.lookupResolutionMeters(latLon.lat(), latLon.lon());
         return Double.isFinite(resolutionMeters) && resolutionMeters > 0.0
            ? resolutionMeters
            : TellusElevationSource.DemUsage.TERRAIN_TILES.nominalResolutionMeters();
      }
   }

   private double sampleTerrariumMeters(double blockX, double blockZ, double worldScale, boolean highResOcean, double previewResolutionMeters) {
      TellusElevationSource.ZoomPlan plan = this.zoomPlan(worldScale, previewResolutionMeters);
      if (plan.downsampleStep > 1) {
         blockX = downsampleBlock(blockX, plan.downsampleStep);
         blockZ = downsampleBlock(blockZ, plan.downsampleStep);
      }

      int zoom = plan.zoom;
      double sample = this.sampleAtZoom(blockX, blockZ, worldScale, zoom);
      if (!Double.isNaN(sample)) {
         if (sample <= 0.0 && highResOcean) {
            double oceanSample = this.sampleAtZoom(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
            if (!Double.isNaN(oceanSample)) {
               return oceanSample;
            }
         }

         return sample;
      } else {
         double oceanSample = this.sampleAtZoom(blockX, blockZ, worldScale, OCEAN_MAX_ZOOM);
         //  2026-10-05: a total miss now returns `NO_ELEVATION_METERS` (NaN) instead of `0.0`. This was the
         //  third "no data" spelling in this file, and it was the one that caused real damage: `0.0` is a real
         //  sea level elevation, so a column whose tiles are all absent came back as a **valid** 0 m reading.
         //  `EarthChunkGenerator.sampleSurfaceHeightForHeightGrid` only reports a miss on NaN, so with 0.0 it
         //  cached an invented sea level height as authoritative and `copyOverlaps` then stamped that fake
         //  height onto the neighbouring chunks -- the "square plateau across 3x3 chunks" that
         //  `sampleSurfaceHeightForHeightGrid` exists to prevent, reintroduced one layer below.
         //  The sibling methods `sampleTerrariumMetersLocalOnly` / `sampleTerrariumMetersMemoryOnly` already
         //  returned NaN here, so this also makes the three of them agree. `WaterSurfaceResolver` needs no
         //  change: its two call sites of this path test `Double.isNaN(elevation) || Math.abs(elevation) < 1.0E-6`.
         return !Double.isNaN(oceanSample) ? oceanSample : NO_ELEVATION_METERS;
      }
   }

   private double sampleAtZoom(double blockX, double blockZ, double worldScale, int zoom) {
      TellusElevationSource.GeoPlan plan = this.geoPlan(worldScale, zoom, blockZ);
      double lon = blockX / plan.blocksPerDegree;
      double lat = plan.lat;
      //  2026-10-05: `isInsideWebMercatorRange` starts with `Double.isFinite`, the plain range test that used
      //  to sit here (`!(lat < MIN_LAT) && !(lat > MAX_LAT) && ...`) accepts NaN. See that method for the whole
      //  reason and for what a NaN latitude does to the tile index.
      if (TellusElevationSource.isInsideWebMercatorRange(lat, lon)) {
         double n = plan.n;
         double x = (lon + 180.0) / 360.0 * n;
         double y = plan.y;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.cachedTileFor(zoom, tileX, tileY, 0);
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleBilinearAcrossTiles(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleAtZoomLocalOnly(double blockX, double blockZ, double worldScale, int zoom) {
      TellusElevationSource.GeoPlan plan = this.geoPlan(worldScale, zoom, blockZ);
      double lon = blockX / plan.blocksPerDegree;
      double lat = plan.lat;
      //  2026-10-05: `isInsideWebMercatorRange` starts with `Double.isFinite`, the plain range test that used
      //  to sit here (`!(lat < MIN_LAT) && !(lat > MAX_LAT) && ...`) accepts NaN. See that method for the whole
      //  reason and for what a NaN latitude does to the tile index.
      if (TellusElevationSource.isInsideWebMercatorRange(lat, lon)) {
         double n = plan.n;
         double x = (lon + 180.0) / 360.0 * n;
         double y = plan.y;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.cachedTileFor(zoom, tileX, tileY, 1);
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleBilinearAcrossTilesLocalOnly(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
      }
   }

   private double sampleAtZoomMemoryOnly(double blockX, double blockZ, double worldScale, int zoom) {
      TellusElevationSource.GeoPlan plan = this.geoPlan(worldScale, zoom, blockZ);
      double lon = blockX / plan.blocksPerDegree;
      double lat = plan.lat;
      //  2026-10-05: `isInsideWebMercatorRange` starts with `Double.isFinite`, the plain range test that used
      //  to sit here (`!(lat < MIN_LAT) && !(lat > MAX_LAT) && ...`) accepts NaN. See that method for the whole
      //  reason and for what a NaN latitude does to the tile index.
      if (TellusElevationSource.isInsideWebMercatorRange(lat, lon)) {
         double n = plan.n;
         double x = (lon + 180.0) / 360.0 * n;
         double y = plan.y;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            ShortRaster raster = this.cachedTileFor(zoom, tileX, tileY, 2);
            if (raster == null) {
               return Double.NaN;
            } else {
               double globalX = x * TILE_SIZE;
               double globalY = y * TILE_SIZE;
               return this.sampleBilinearAcrossTilesMemoryOnly(zoom, globalX, globalY, tileX, tileY, raster);
            }
         } else {
            return Double.NaN;
         }
      } else {
         return Double.NaN;
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

   private static TellusElevationSource.TileKey tileKeyForBlock(double blockX, double blockZ, double worldScale, int zoom) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      //  2026-10-05: `isInsideWebMercatorRange` starts with `Double.isFinite`, the plain range test that used
      //  to sit here (`!(lat < MIN_LAT) && !(lat > MAX_LAT) && ...`) accepts NaN. See that method for the whole
      //  reason and for what a NaN latitude does to the tile index.
      if (TellusElevationSource.isInsideWebMercatorRange(lat, lon)) {
         double latRad = Math.toRadians(lat);
         double n = (double)(1 << zoom);
         double x = (lon + 180.0) / 360.0 * n;
         double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
         if (!(x < 0.0) && !(y < 0.0) && !(x >= n) && !(y >= n)) {
            int tileX = Mth.floor(x);
            int tileY = Mth.floor(y);
            return new TellusElevationSource.TileKey(zoom, tileX, tileY);
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static double effectiveSampleResolutionMeters(double worldScale, double previewResolutionMeters) {
      return Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0 ? Math.max(worldScale, previewResolutionMeters) : worldScale;
   }

   private static TellusElevationSource.LatLon toLatLon(double blockX, double blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      //  2026-10-05: `isInsideWebMercatorRange` starts with `Double.isFinite`, so the plain range test that used
      //  to sit here (`!(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)`) is only
      //  reached with finite operands. In the negated form it **accepted** NaN: `!(NaN < MIN_LAT)` and
      //  `!(NaN > MAX_LAT)` are both true, so a NaN lat/lon came out of here as a valid coordinate and every
      //  provider coverage test below (`lat >= 17.7 && ...`, `lat >= 60.0`, ...) silently said "no".
      return TellusElevationSource.isInsideWebMercatorRange(lat, lon) ? new TellusElevationSource.LatLon(lat, lon) : null;
   }

   /**
    * 2026-10-05: the single lat/lon range test of this class, and the place where the NaN hole is closed.
    *
    * <p>It was previously written inline, in the negated form
    * {@code !(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON)}, at 5 sites. That form
    * <b>accepts NaN</b>, because every IEEE-754 comparison against NaN is false: {@code !(NaN < MIN_LAT)} is
    * {@code true} and {@code !(NaN > MAX_LAT)} is {@code true} as well. A NaN latitude is exactly what a NaN
    * {@code worldScale} produces, since {@link EarthProjection#blockZToLat} propagates it, and it also happens
    * for a NaN block coordinate. The "is this column on the map" test then passed, {@code x} and {@code y} were
    * NaN, {@code Mth.floor(NaN) == 0}, and the column was read from DEM tile {@code (0,0)}: the map's
    * north-west corner, for a coordinate that has nothing to do with it.
    *
    * <p>{@link Double#isFinite} has to run <b>before</b> the range test, so that the negated comparisons below
    * only ever see finite operands, which is what makes keeping their form safe. It rejects both infinities as
    * well, which the range test does not: {@code blocksPerDegree(+Infinity) == 0.0} makes {@code lon} infinite.
    *
    * <p>Keep it this way: writing this test as {@code lat >= MIN_LAT} / {@code lat <= MAX_LAT} would only work
    * because of the {@code isFinite} calls, and dropping them reintroduces the collapse onto tile (0,0).
    */
   private static boolean isInsideWebMercatorRange(double lat, double lon) {
      return Double.isFinite(lat) && Double.isFinite(lon)
         && !(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON);
   }

   /**
    * Queues one tile onto the background prefetch queue.
    *
    * <p><b>OPT-1</b>: upstream this was a synchronous {@code cache.get(key)}. Joining a world or picking a spawn
    * would sequentially pull (2r+1)^2 tiles on the <b>calling thread</b>, the server main thread or a chunk
    * generation thread. A radius of 2 is already 25 tiles at 200 to 400 ms each, so a few seconds of a fully stalled frame,
    *
    * <p>Instead: submit to a bounded daemon pool that fetches in parallel and de-duplicate with an in-flight set.
    * Sampling paths that genuinely need the data still call {@code cache.get(key)}, and Guava makes that join the
    * load already running instead of downloading twice. A full queue degrades to the synchronous behaviour, keeping the original semantics and losing no data.
    */
   private void prefetchTile( TellusElevationSource.TileKey key) {
      if (this.cache.getIfPresent(key) != null) {
         return;
      }
      if (!this.prefetchQueue.add(key)) {
         return; // already queued or in flight
      }
      try {
         this.prefetchExecutor.execute(() -> {
            try {
               if (this.cache.getIfPresent(key) == null) {
                  this.cache.get(key);
               }
            } catch (Exception e) {
               Tellus.LOGGER.debug("Prefetching elevation tile {} failed", key, e);
            } finally {
               this.prefetchQueue.remove(key);
            }
         });
      } catch (java.util.concurrent.RejectedExecutionException rejected) {
         // Offline-first gate or a full queue: fall back to the synchronous semantics and let the caller wait if needed
         this.prefetchQueue.remove(key);
         if (this.cache.getIfPresent(key) == null) {
            try {
               this.cache.get(key);
            } catch (Exception e) {
               Tellus.LOGGER.debug("Prefetching elevation tile {} failed", key, e);
            }
         }
      }
   }

   /** Concurrency of the prefetch thread pool: min(8, cores x 2) by default, overridable with -Dtellus.elevation.prefetchThreads=N. */
   private static int prefetchThreads() {
      int cores = Runtime.getRuntime().availableProcessors();
      return intProperty("tellus.elevation.prefetchThreads", Math.max(2, Math.min(8, cores * 2)));
   }

   /** Concurrency of the cache: max(8, cores x 4) by default, overridable with -Dtellus.elevation.cacheConcurrency=N. */
   private static int cacheConcurrency() {
      int cores = Runtime.getRuntime().availableProcessors();
      return intProperty("tellus.elevation.cacheConcurrency", Math.max(8, cores * 4));
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

   private static boolean booleanProperty(String key, boolean defaultValue) {
      String value = System.getProperty(key);
      return value == null ? defaultValue : Boolean.parseBoolean(value);
   }

   /**
    * Fetches one elevation tile.
    *
    * <p><b>It reads the local cache only and never downloads on the calling thread.</b>
    * That came out of a real incident: the chunk generation path calls this, and
    * {@code cache.get()} goes through the LoadingCache loader, which performs a synchronous HTTP request.
    * The result was "the server stalls a single tick for 4 to 24 seconds", the watchdog error in the log,
    * which in turn caused three cascading symptoms: collision queries hitting unloaded chunks while the player moves
    * and leaving them stuck in the air, lighting updates queueing up so mobs spawn in broad daylight, and overall jank.
    *
    * <p>To restore the original download while generating, which does stall, add
    *  {@code -Dtellus.elevation.localOnly=false}.
    * <p>The right approach is to <b>preload the region with the box selection first</b>, then generation hits the cache naturally.
    */
   private static final boolean LOCAL_ONLY =
      Boolean.parseBoolean(System.getProperty("tellus.elevation.localOnly", "true"));

   /**
    * The DEM tile used by the previous sampleAtZoom* call, one per thread.
    *
    * <p>JFR sampling shows {@code sampleAtZoom} allocates a new {@code TileKey} on the hot path for every point just
    * to look the cache up. Terrain is sampled column after column, so neighbouring points nearly always land on the
    * same tile. Caching "the previous one" here avoids building a TileKey on a hit, which is zero allocation, and saves a Caffeine lookup as well.
    *
    * <p>Only <b>non null</b> results are cached: null means this round did not get the tile, either because it is
    * missing or because it is not cached, and caching that would make every later retry miss too.
    */
   private ShortRaster cachedTileFor(int zoom, int tileX, int tileY, int source) {
      TellusElevationSource.LastDemTile cached = this.lastDemTile.get();
      if (cached.valid
         && cached.source == source
         && cached.zoom == zoom
         && cached.tileX == tileX
         && cached.tileY == tileY) {
         return cached.raster;
      }

      TellusElevationSource.TileKey key = new TellusElevationSource.TileKey(zoom, tileX, tileY);
      ShortRaster raster = switch (source) {
         case 1 -> this.getTileLocalOnly(key);
         case 2 -> this.getTileMemoryOnly(key);
         default -> this.getTile(key);
      };
      if (raster != null) {
         cached.zoom = zoom;
         cached.tileX = tileX;
         cached.tileY = tileY;
         cached.source = source;
         cached.raster = raster;
         cached.valid = true;
      }

      return raster;
   }

   private final ThreadLocal<TellusElevationSource.LastDemTile> lastDemTile =
      ThreadLocal.withInitial(TellusElevationSource.LastDemTile::new);

   /** source: 0 = online, 1 = local disk only, 2 = memory only, matching the three getTile* methods. */
   private static final class LastDemTile {
      private int zoom;
      private int tileX;
      private int tileY;
      private int source;
      private ShortRaster raster;
      private boolean valid;
   }

   private ShortRaster getTile( TellusElevationSource.TileKey key) {
      if (LOCAL_ONLY) {
         return this.getTileLocalOnly(key);
      }
      try {
         ShortRaster raster = (ShortRaster)this.cache.get(key);
         return raster == MISSING_RASTER ? null : raster;
      } catch (Exception var3) {
         Tellus.LOGGER.warn("Loading elevation tile {} failed", key, var3);
         return null;
      }
   }

   private ShortRaster getTileLocalOnly(TellusElevationSource.TileKey key) {
      ShortRaster cached = (ShortRaster)this.cache.getIfPresent(key);
      if (cached != null) {
         return cached == MISSING_RASTER ? null : cached;
      } else {
         Path cachePath = this.cachePath(key);
         if (!Files.exists(cachePath)) {
            return null;
         } else {
            try (InputStream input = Files.newInputStream(cachePath)) {
               ShortRaster raster = readPngRaster(input);
               this.cache.put(key, raster);
               return raster;
            } catch (IOException error) {
               this.handleInvalidTile(cachePath, key, error);
               return null;
            }
         }
      }
   }

   /**
    * A read of the elevation tile that lives only in memory.
    *
    * <p>2026-09-26: when memory has nothing, fall back to a disk-only read, which never goes online and never blocks.
    *
    * <h3>Symptom on a real server</h3>
    * <b>Squares or a checkerboard pattern</b> all over the map: sometimes a whole 16x16 region, or even a larger one, is raised into a single platform.
    *
    * <h3>Cause (the very same one as the cover class path)</h3>
    * The comment in {@code EarthChunkGenerator.sampleCoverClassForTerrainShell} already describes this trap: whether a
    * tile is in memory depends on <b>memory state</b>, and a chunk that hits memory and a chunk that
    * misses compute <b>different terrain heights</b>.
    * <ul>
    *   <li>memory hit, so the real elevation</li>
    *   <li>memory miss, so null is returned here, the sample is NaN, {@code sampleSurfaceHeightMemoryOnly} returns
    *       {@code Integer.MIN_VALUE}, {@code buildTerrainShellHeightGrid} records a miss and
    *       {@code fillMissingTerrainShellHeights} fills the whole chunk with {@code seaLevel}</li>
    * </ul>
    * So a chunk with a "missing tile" turns entirely into a <b>sea level platform</b>, it is flagged as approximate
    * in {@code heightGridCache}, and neighbouring chunks copy it over (see the put and copyOverlaps in EarthChunkGenerator), so the flat area keeps growing.
    *
    * <h3>Why the disk fallback is right</h3>
    * Tiles fetched by preload or in a previous session <b>are on disk already</b>, measured:
    * {@code tellus/cache/elevation-tellus} holds 456 tiles while the memory cache holds only
    * {@code tellus.elevation.cacheTiles}, so "not in memory" is not the same as "not in the data". A tile read from disk
    * is <b>identical</b> to what the neighbours use, and the difference between memory and disk is only how fast the
    * read is, usually microseconds when the OS page cache hits. This path <b>never issues a network request</b>,
    *
    * so "the critical path does not block" is preserved. Turn it off with {@code -Dtellus.elevation.memoryOnlyDiskFallback=false}, see the cost note on the field.
    */
   private ShortRaster getTileMemoryOnly(TellusElevationSource.TileKey key) {
      ShortRaster cached = (ShortRaster)this.cache.getIfPresent(key);
      if (cached != null) {
         return cached == MISSING_RASTER ? null : cached;
      }
      // Memory miss: fall back to a disk-only read (getTileLocalOnly = memory, then the .missing marker, then the local file, never the network)
      return MEMORY_ONLY_DISK_FALLBACK ? this.getTileLocalOnly(key) : null;
   }

   private ShortRaster loadTile( TellusElevationSource.TileKey key) throws IOException {
      Path cachePath = this.cachePath(key);
      if (Files.exists(cachePath)) {
         try {
            ShortRaster var15;
            try (InputStream input = Files.newInputStream(cachePath)) {
               var15 = readPngRaster(input);
            }

            return var15;
         } catch (IOException var13) {
            this.handleInvalidTile(cachePath, key, var13);
         }
      }

      byte[] data;
      try {
         data = this.downloadTile(key);
      } catch (IOException var10) {
         //  A timeout, a 5xx or a dropped connection are all <b>transient</b>, so they are thrown and Guava is
         //    <b>not allowed to record this entry in the cache</b>. The old code returned MISSING_RASTER here, so one
         //    network hiccup made the tile <b>permanently</b> "no data" for the whole session, because both the
         //    getIfPresent of `prefetchTile` and `getTileLocalOnly` short-circuit and nobody ever retried. From then on that area went through `fillMissingTerrainShellHeights` at sea level and the player could never walk out of the square.
         Tellus.LOGGER.debug("Downloading elevation tile {} failed", key, var10);
         throw var10;
      }

      if (data == null) {
         //  <b>Only a 404</b>, meaning the tile genuinely does not exist, caches a negative result, because retrying
         //    would never help and remembering it is correct. Anything else would retry an address that always 404s.
         return MISSING_RASTER;
      } else {
         this.cacheTile(cachePath, data);

         try {
            ShortRaster var5;
            try (InputStream input = new ByteArrayInputStream(data)) {
               var5 = readPngRaster(input);
            }

            return var5;
         } catch (IOException var9) {
            //  A freshly downloaded file that cannot be decoded was already deleted by handleInvalidTile, so it is retryable and no negative result is cached.
            this.handleInvalidTile(cachePath, key, var9);
            throw var9;
         }
      }
   }

   private byte[] downloadTile(TellusElevationSource.TileKey key) throws IOException {
      String endpoint = getEndpoint();
      String url = String.format("%s/%d/%d/%d.png", endpoint, key.zoom(), key.x(), key.y());
      Tellus.LOGGER.info("[TellusCN] downloading elevation tile: {}, source endpoint: {}", key, endpoint);
      URI uri = URI.create(url);
      // Offline-first gate: with -Dtellus.network.offlineFirst=true a local cache miss never goes online
      com.yucareux.tellus.world.data.source.TellusNetworkGate.requireOnline(uri, "elevation tile");
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      try {
         connection.setConnectTimeout(8000);
         connection.setReadTimeout(8000);
         connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
         int responseCode = connection.getResponseCode();
         Tellus.LOGGER.debug("[TellusCN] elevation tile response code: {}, request {}", responseCode, url);
         if (responseCode == 404) {
            return null;
         } else {
            DownloadProgressReporter.requestStarted(connection.getContentLengthLong());

            byte[] var5;
            try (InputStream input = Objects.requireNonNull(connection.getInputStream(), "elevationTileResponse")) {
               var5 = DownloadProgressReporter.readAllBytesWithProgress(input);
            } finally {
               DownloadProgressReporter.requestFinished();
            }

            Tellus.LOGGER.info("[TellusCN] downloaded elevation tile: {} ({} bytes)", key, var5.length);
            return var5;
         }
      } finally {
         connection.disconnect();
      }
   }

   private void cacheTile(Path cachePath, byte[] data) {
      try {
         Files.createDirectories(cachePath.getParent());
         Files.write(cachePath, data);
      } catch (IOException var4) {
         Tellus.LOGGER.warn("Caching elevation tile {} failed", cachePath, var4);
      }
   }

   private Path cachePath(TellusElevationSource.TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".png");
   }

   private void handleInvalidTile(Path cachePath, TellusElevationSource.TileKey key, IOException cause) {
      try {
         Files.deleteIfExists(cachePath);
      } catch (IOException var5) {
         Tellus.LOGGER.debug("Deleting the invalid elevation tile cache {} failed", cachePath, var5);
      }

      Tellus.LOGGER.debug("Ignored invalid elevation tile {} (path {})", new Object[]{key, cachePath, cause});
   }

   private double sampleBilinearAcrossTiles(int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      int baseStartX = baseTileX * TILE_SIZE;
      int baseStartY = baseTileY * TILE_SIZE;
      double v00;
      double v10;
      double v01;
      double v11;
      if (x0 >= baseStartX && x1 < baseStartX + TILE_SIZE && y0 >= baseStartY && y1 < baseStartY + TILE_SIZE) {
         v00 = baseRaster.get(x0 - baseStartX, y0 - baseStartY);
         v10 = baseRaster.get(x1 - baseStartX, y0 - baseStartY);
         v01 = baseRaster.get(x0 - baseStartX, y1 - baseStartY);
         v11 = baseRaster.get(x1 - baseStartX, y1 - baseStartY);
      } else {
         v00 = this.samplePixel(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
         v10 = this.samplePixel(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
         v01 = this.samplePixel(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
         v11 = this.samplePixel(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      }

      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         double localX = clampedX - baseTileX * TILE_SIZE;
         double localY = clampedY - baseTileY * TILE_SIZE;
         return sampleBilinearLocal(baseRaster, localX, localY);
      }
   }

   private double sampleBilinearAcrossTilesLocalOnly(int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      int baseStartX = baseTileX * TILE_SIZE;
      int baseStartY = baseTileY * TILE_SIZE;
      double v00;
      double v10;
      double v01;
      double v11;
      if (x0 >= baseStartX && x1 < baseStartX + TILE_SIZE && y0 >= baseStartY && y1 < baseStartY + TILE_SIZE) {
         v00 = baseRaster.get(x0 - baseStartX, y0 - baseStartY);
         v10 = baseRaster.get(x1 - baseStartX, y0 - baseStartY);
         v01 = baseRaster.get(x0 - baseStartX, y1 - baseStartY);
         v11 = baseRaster.get(x1 - baseStartX, y1 - baseStartY);
      } else {
         v00 = this.samplePixelLocalOnly(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
         v10 = this.samplePixelLocalOnly(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
         v01 = this.samplePixelLocalOnly(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
         v11 = this.samplePixelLocalOnly(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      }

      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         double localX = clampedX - baseTileX * TILE_SIZE;
         double localY = clampedY - baseTileY * TILE_SIZE;
         return sampleBilinearLocal(baseRaster, localX, localY);
      }
   }

   private double sampleBilinearAcrossTilesMemoryOnly(int zoom, double globalX, double globalY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tilesPerAxis = 1 << zoom;
      int maxPixel = tilesPerAxis * TILE_SIZE - 1;
      double clampedX = Mth.clamp(globalX, 0.0, maxPixel);
      double clampedY = Mth.clamp(globalY, 0.0, maxPixel);
      int x0 = Mth.floor(clampedX);
      int y0 = Mth.floor(clampedY);
      int x1 = Math.min(x0 + 1, maxPixel);
      int y1 = Math.min(y0 + 1, maxPixel);
      double dx = clampedX - x0;
      double dy = clampedY - y0;
      int baseStartX = baseTileX * TILE_SIZE;
      int baseStartY = baseTileY * TILE_SIZE;
      double v00;
      double v10;
      double v01;
      double v11;
      if (x0 >= baseStartX && x1 < baseStartX + TILE_SIZE && y0 >= baseStartY && y1 < baseStartY + TILE_SIZE) {
         v00 = baseRaster.get(x0 - baseStartX, y0 - baseStartY);
         v10 = baseRaster.get(x1 - baseStartX, y0 - baseStartY);
         v01 = baseRaster.get(x0 - baseStartX, y1 - baseStartY);
         v11 = baseRaster.get(x1 - baseStartX, y1 - baseStartY);
      } else {
         v00 = this.samplePixelMemoryOnly(zoom, x0, y0, baseTileX, baseTileY, baseRaster);
         v10 = this.samplePixelMemoryOnly(zoom, x1, y0, baseTileX, baseTileY, baseRaster);
         v01 = this.samplePixelMemoryOnly(zoom, x0, y1, baseTileX, baseTileY, baseRaster);
         v11 = this.samplePixelMemoryOnly(zoom, x1, y1, baseTileX, baseTileY, baseRaster);
      }

      if (!Double.isNaN(v00) && !Double.isNaN(v10) && !Double.isNaN(v01) && !Double.isNaN(v11)) {
         double lerpX0 = Mth.lerp(dx, v00, v10);
         double lerpX1 = Mth.lerp(dx, v01, v11);
         return Mth.lerp(dy, lerpX0, lerpX1);
      } else {
         double localX = clampedX - baseTileX * TILE_SIZE;
         double localY = clampedY - baseTileY * TILE_SIZE;
         return sampleBilinearLocal(baseRaster, localX, localY);
      }
   }

   private double samplePixel(int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY ? baseRaster : this.getTile(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private double samplePixelLocalOnly(int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY
         ? baseRaster
         : this.getTileLocalOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private double samplePixelMemoryOnly(int zoom, int pixelX, int pixelY, int baseTileX, int baseTileY, ShortRaster baseRaster) {
      int tileX = Math.floorDiv(pixelX, TILE_SIZE);
      int tileY = Math.floorDiv(pixelY, TILE_SIZE);
      ShortRaster raster = tileX == baseTileX && tileY == baseTileY
         ? baseRaster
         : this.getTileMemoryOnly(new TellusElevationSource.TileKey(zoom, tileX, tileY));
      if (raster == null) {
         return Double.NaN;
      } else {
         int localX = pixelX - tileX * TILE_SIZE;
         int localY = pixelY - tileY * TILE_SIZE;
         return raster.get(localX, localY);
      }
   }

   private static double sampleBilinearLocal(ShortRaster raster, double x, double y) {
      int maxX = raster.width() - 1;
      int maxY = raster.height() - 1;
      int x0 = Mth.clamp(Mth.floor(x), 0, maxX);
      int y0 = Mth.clamp(Mth.floor(y), 0, maxY);
      int x1 = Math.min(x0 + 1, maxX);
      int y1 = Math.min(y0 + 1, maxY);
      double dx = x - x0;
      double dy = y - y0;
      double v00 = raster.get(x0, y0);
      double v10 = raster.get(x1, y0);
      double v01 = raster.get(x0, y1);
      double v11 = raster.get(x1, y1);
      double lerpX0 = Mth.lerp(dx, v00, v10);
      double lerpX1 = Mth.lerp(dx, v01, v11);
      return Mth.lerp(dy, lerpX0, lerpX1);
   }

   private static int selectZoom(double worldScale) {
      double zoom = zoomForScale(worldScale);
      return Math.max((int)Math.round(zoom), 0);
   }

   private static TellusElevationSource.ElevationDiagnostic diagnostic(double elevation, TellusElevationSource.DemUsage provider) {
      return new TellusElevationSource.ElevationDiagnostic(elevation, provider, provider.bit(), provider.nominalResolutionMeters());
   }

   private static TellusElevationSource.ElevationDiagnostic diagnostic(
      double elevation, TellusElevationSource.DemUsage primaryProvider, int providerMask, double sourceResolutionMeters
   ) {
      double resolvedResolution = Double.isFinite(sourceResolutionMeters) && sourceResolutionMeters > 0.0
         ? sourceResolutionMeters
         : primaryProvider.nominalResolutionMeters();
      return new TellusElevationSource.ElevationDiagnostic(elevation, primaryProvider, providerMask, resolvedResolution);
   }

   private static double zoomForScale(double meters) {
      return Math.log(EQUATOR_CIRCUMFERENCE / (TILE_SIZE * meters)) / Math.log(2.0);
   }

   /**
    * 2026-09-29: read the bytes of PNG elevation tiles directly. Measured on a real server, heightGrid took 11.61 ms per call
    *
    * and 16.5 seconds in total, which is 19% of the whole run.
    * <p><b>The old bottleneck</b>: for every tile all {@code 256 x 256} pixels called {@code getRGB(x, y)} <b>one by one</b>,
    * and every {@code getRGB} walks the {@code ColorModel} to get the normalised sRGB value. Measured at 1 to 3 ms per
    *
    * tile, and a chunk reads 4 to 5 of them, so this was <b>almost the entire heightGrid time</b>.
    * <p><b>Now</b>: when the image type is {@link BufferedImage#TYPE_3BYTE_BGR}, which is what ImageIO produces for a
    *
    * 24 bit RGB PNG, the three bytes of a {@link java.awt.image.DataBufferByte} are read directly by scanline stride.
    * <p><b>Zero risk safeguard</b>: after the direct read, <b>24 points are sampled and compared against {@code getRGB}</b>.
    * If a single point disagrees on any R, G or B channel, the <b>whole tile falls back to the slow path</b>, so a wrong
    */
   private static ShortRaster readPngRaster(InputStream input) throws IOException {
      BufferedImage image = ImageIO.read(input);
      if (image == null) {
         throw new IOException("Invalid Tellus PNG tile");
      } else {
         int width = image.getWidth();
         int height = image.getHeight();
         //  2026-10-05: the tile geometry is now **checked** instead of assumed.
         //
         //  Why it has to be checked: every sampling path assumes this tile is exactly `TILE_SIZE` x `TILE_SIZE`
         //  pixels. `samplePixel*` derives the in-tile pixel as `pixelX - floorDiv(pixelX, TILE_SIZE) * TILE_SIZE`,
         //  which is in `[0, 256)` whatever the image really is, and then hands it to `ShortRaster.get`, which
         //  indexes `data[x + y * width]` with **no bounds check of its own**. So a larger tile (512x512, which is
         //  what several DEM mirrors ship) silently reads only its top-left quadrant and **stretches** the terrain,
         //  and a smaller one (128x128) throws `ArrayIndexOutOfBoundsException` from deep inside the height grid.
         //  Neither was reported against the tile that caused it.
         //
         //  Policy, matching `MapterhornElevationSource:251` (`image.getWidth() != TILE_PX || ...` -> log and
         //  treat the tile as absent) and this class's own convention for an unusable tile: **throw**, which is
         //  what the `image == null` case right above already does. Both callers (`getTileLocalOnly` and `loadTile`)
         //  catch that `IOException` and route it through `handleInvalidTile`, which deletes the cached file and
         //  reports the tile as no data (`null` raster -> `Double.NaN` sample -> an honest chunk miss).
         if (width != TILE_SIZE || height != TILE_SIZE) {
            throw new IOException(
               "Tellus elevation tile is " + width + "x" + height + " pixels, expected " + TILE_SIZE + "x" + TILE_SIZE
            );
         }

         ShortRaster raster = ShortRaster.create(width, height);
         if (readPngRasterFast(image, width, height, raster)) {
            return repairDecodedTile(raster, width, height);
         }

         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int argb = image.getRGB(x, y);
               int red = argb >> 16 & 0xFF;
               int green = argb >> 8 & 0xFF;
               int blue = argb & 0xFF;
               raster.set(x, y, decodeTerrariumPixel(red, green, blue));
            }
         }

         return repairDecodedTile(raster, width, height);
      }
   }

   // =========================================================================
   // 2026-10-05: the payload-validity gate + the repair pass, on the decode path.
   //
   // ★ THE ROOT CAUSE OF THE SQUARE PLATEAUS.
   //
   //   A download can succeed and still be worthless: HTTP 200, a well-formed 256x256
   //   PNG, and every pixel at the no-data floor. Our circuit breaker
   //   (TellusDownloadExecutor, FAIL_THRESHOLD=8 / COOLDOWN=120s) counts **request
   //   failures** — timeouts, cancellations, exceptions. It cannot see this case at all,
   //   so the tile is counted as completed, enters the cache, and samples out as NaN.
   //   Then EarthChunkGenerator.fillMissingTerrainShellHeights invents ONE height for all
   //   576 columns of the chunk, and the player walks into a flat square platform with a
   //   cliff ring around it.
   //
   //   arnis checks the same thing from the other side (elevation/mod.rs:368):
   //       if nan_ratio > 0.5 { warn; continue; }   // skip this provider, try the next
   //   — not "did the request work" but "**is the data we got any use**".
   //
   // ★ Why throwing IOException is exactly the right move and not a new mechanism.
   //   All three callers of readPngRaster already treat an IOException as "this tile is not
   //   usable", and that path is already correct:
   //     - getTileLocalOnly / loadTile(disk) -> handleInvalidTile -> **delete the file**
   //       -> report the tile as absent -> NaN sample -> an honest chunk miss -> retried later.
   //     - loadTile(freshly downloaded)     -> handleInvalidTile -> delete -> **rethrow**
   //       -> Guava's loader records no negative entry -> the tile stays retryable.
   //   The comment already at loadTile:2532 explains why negative-caching a transient failure
   //   was the original square-plateau bug; this extends the same treatment to "a success that
   //   carried no data", which is the same failure wearing a different mask.
   // =========================================================================

   private static final java.util.concurrent.atomic.AtomicLong REJECTED_TILES =
      new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong IMPOSSIBLE_CELLS =
      new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong FILLED_CELLS =
      new java.util.concurrent.atomic.AtomicLong();

   /**
    * One decoded tile, on its way into the cache.
    *
    * @return the same raster, holes filled and impossible readings removed
    * @throws IOException when the payload is too empty to be worth anything
    */
   private static ShortRaster repairDecodedTile(ShortRaster raster, int width, int height) throws IOException {
      final int cells = width * height;
      // Cheap first pass, in the raster's own units: count the two "no elevation" spellings that
      // storedElevationMeters and the sibling readers can leave behind, and only allocate the
      // double[] once a hole is actually seen. A complete tile — by far the common case — leaves
      // here without allocating anything at all, which matters because this sits on the hot path
      // (heightGrid measured 11.61 ms per call on a real server).
      int missing = 0;
      double[] grid = null;
      short[] raw = raster.data();
      for (int i = 0; i < cells; i++) {
         short value = raw[i];
         if (value <= EarthGeneratorSettings.DEM_NODATA_METERS || value == Short.MIN_VALUE) {
            missing++;
            if (grid == null) {
               // First hole: copy everything decoded so far, then keep filling alongside the scan.
               grid = new double[cells];
               for (int copy = 0; copy < i; copy++) {
                  grid[copy] = raw[copy];
               }
            }
            grid[i] = Double.NaN;
         } else if (grid != null) {
            grid[i] = value;
         }
      }

      if (grid == null) {
         return raster;                    // complete and physically in range: nothing to do
      }

      // More than half no-data is a failed fetch wearing a 200. Rejecting it here is what lets
      // the prefetch queue and the circuit breaker see the failure at all.
      if (missing * 2 > cells) {
         REJECTED_TILES.incrementAndGet();
         TellusElevationRepair.recordRejected(
            "elevation tile " + width + "x" + height, (double)missing / cells
         );
         throw new IOException(
            "Tellus elevation tile is " + (int)Math.round(100.0 * missing / cells) + "% no-data ("
               + missing + "/" + cells + "), above the "
               + (int)(TellusNative.NAN_RATIO_REJECT_THRESHOLD * 100.0) + "% limit: treated as a failed fetch"
         );
      }

      // Below the threshold it is a real tile with holes. Drop the physically impossible readings
      // (ported from arnis postprocess.rs:2538), then interpolate the rest shut (postprocess.rs:2530).
      int impossible = TellusElevationRepair.filterElevationOutliers(grid);
      int filled = TellusElevationRepair.fillNanValues(grid, width, height);
      IMPOSSIBLE_CELLS.addAndGet(impossible);
      FILLED_CELLS.addAndGet(filled);

      for (int i = 0; i < cells; i++) {
         double value = grid[i];
         if (Double.isFinite(value)) {
            raster.set(i % width, i / width, storedElevationMeters(value));
         }
      }
      return raster;
   }

   /** One line for the periodic report; the despike counter lives on the shell grid, see EarthChunkGenerator. */
   public static String elevationRepairSummary() {
      return TellusElevationRepair.describe(REJECTED_TILES.get(), IMPOSSIBLE_CELLS.get(), FILLED_CELLS.get(), 0L);
   }

   /**
    * Decodes <b>one</b> Terrarium pixel and clamps out of range or sentinel samples <b>at the decode site</b>.
    *
    * <p>Why clamp here rather than in {@code buildNormalizedTile}: that clamp only runs when
    * {@code tellus.elevation.normalized.enabled=true}, and that flag is <b>off by default</b>, so the 0.57% of
    * {@code -23070 m} noise seen across 527 real tiles flows out <b>unchanged</b>.
    * The real fallback is {@code EarthChunkGenerator.sanitizeElevationMeters}, but it only covers the 5 conversion
    * points on the <b>terrain</b> path, while the 3 in {@code WaterSurfaceResolver}, the colour min and max in {@code TerrainPreview}
    *
    * and the coordinate HUD all use the <b>raw value</b>.
    * <p>Normal data is <b>bit for bit unchanged</b>: the test is {@code < -12000} and the deepest point on Earth is only
    */
   private static short decodeTerrariumPixel(int red, int green, int blue) {
      double elevation = red * TILE_SIZE + green + blue / (double)TILE_SIZE - 32768.0;
      //  2026-10-05: the clamp moved into `storedElevationMeters`, and it now has an **upper** bound too.
      //
      //  The old line was `(short) Math.round(elevation < DEM_NODATA_METERS ? DEM_NODATA_METERS : elevation)`:
      //  it only bounded the value from below, and then cast. A white Terrarium pixel, the top of the encoding,
      //  is `255*256 + 255 + 255/256 - 32768 = 32767.996` m, so `Math.round` gives 32768 and
      //  `(short) 32768` wraps to `Short.MIN_VALUE` (-32768) -- which is *exactly* the NO_DATA sentinel of
      //  `MapterhornElevationSource` (`NO_DATA = Short.MIN_VALUE`), so the sample was aliased into the very value
      //  that means "this pixel has no elevation". The clamp is two-sided and happens before the cast now.
      //
      //  2026-10-05 (second round, from the arnis comparison): `storedElevationMeters` bounds the value at
      //  `Short.MAX_VALUE` (32767), which stops the `(short)` wrap but is **not a physical bound** -- a
      //  32 km column still gets through and, being finite and not NaN, sails past every `Double.isNaN` guard
      //  on every downstream path. The bound that is actually meaningful is 9000 m (Everest is 8848.86;
      //  the margin covers providers reporting ellipsoidal heights), taken from arnis
      //  `postprocess.rs:2540 filter_elevation_outliers`.
      //
      //  Outside the physical band the sample is **no data**, not a clamp: `storedElevationMeters` maps
      //  NaN onto `DEM_NODATA_METERS`, which is the documented "this cell has no elevation" spelling and
      //  is what `repairDecodedTile` counts and interpolates. Clamping instead would keep a fabricated
      //  9000 m tower standing, and clamping to the floor would keep a fabricated 32 km **trench**.
      if (elevation > TellusNative.MAX_REASONABLE_METERS || elevation < TellusNative.MIN_REASONABLE_METERS) {
         return storedElevationMeters(Double.NaN);
      }
      return storedElevationMeters(elevation);
   }

   /**
    * 2026-10-05: <b>the one</b> conversion from metres to a value stored in a {@code short} raster, and <b>the
    * one</b> statement of what "no elevation" means in this class. Every other spelling is routed through here.
    *
    * <p><b>Policy.</b>
    * <ul>
    *   <li><b>In memory, "no elevation" is always {@link #NO_ELEVATION_METERS}, which is NaN.</b> NaN is what
    *       every miss already returns ({@code sampleAtZoom*}, {@code samplePixel*}, {@code PolarDemSample.none()}),
    *       and it is the only value {@code EarthChunkGenerator.sampleSurfaceHeightForHeightGrid} tests in order
    *       to report a miss honestly instead of inventing a sea level height and caching it as authoritative.
    *       {@code 0.0} was the second representation in this file; it is a <b>real</b> elevation (sea level), and
    *       {@code WaterSurfaceResolver} has to work around it with an {@code Math.abs(elevation) < 1.0E-6} test
    *       precisely because "sea level" and "no data" looked the same.</li>
    *   <li><b>{@link EarthGeneratorSettings#DEM_NODATA_METERS} (-12000) is the noise floor of a real sample, not a
    *       miss marker.</b> Measured over 527 real tiles the deepest reading is -23070 m while the deepest point on
    *       Earth is -10994 m, so anything under the floor is decoder noise, and clamping it to the floor keeps it
    *       finite (hence inside every {@code Double.isNaN} guard) and inside the world.</li>
    *   <li><b>A stored raster therefore never carries a sentinel.</b> The floor -12000 is far above
    *       {@code Short.MIN_VALUE} (-32768), which is the NO_DATA value of the sibling readers, so a stored sample
    *       can never be mistaken for "this cell has no elevation". The value is clamped to
    *       {@code [DEM_NODATA_METERS, Short.MAX_VALUE]} and only then narrowed, which also removes the wrap-around:
    *       {@code Math.round} of a large finite double saturates at {@code Short.MAX_VALUE} instead of overflowing
    *       into a negative short.</li>
    * </ul>
    *
    * <p><b>What is deliberately not unified.</b> The normalized tile raster still stores
    * {@code DEM_NODATA_METERS} for a sample no provider could supply, because {@code ShortRaster} has no absence
    * channel at all and inventing one would change the on-disk format that
    * {@code NormalizedElevationCache.readTile} / {@code TellusRasterReader} read back. What this method does
    * guarantee is that such a cell is written as the documented no-data floor and never as
    * {@code Short.MIN_VALUE}; see the report for the follow-up that belongs to those two files.
    */
   private static short storedElevationMeters(double elevationMeters) {
      double rounded = Double.isFinite(elevationMeters) ? Math.round(elevationMeters) : EarthGeneratorSettings.DEM_NODATA_METERS;
      return (short)Mth.clamp(rounded, EarthGeneratorSettings.DEM_NODATA_METERS, (double)Short.MAX_VALUE);
   }

   /**
    * -10994 m, so no real pixel can trigger it and only bad samples are altered.
    * Goes through the direct byte read and <b>self-checks</b>. Returning false means "not trustworthy, the caller should take the slow path".
    */
   private static boolean readPngRasterFast(BufferedImage image, int width, int height, ShortRaster target) {
      if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
         return false;
      }

      java.awt.image.WritableRaster source = image.getRaster();
      if (source.getNumBands() != 3 || !(source.getDataBuffer() instanceof java.awt.image.DataBufferByte buffer)) {
         return false;
      }

      byte[] data = buffer.getData();
      // The layout follows the TYPE_3BYTE_BGR standard arrangement: 3 bytes per pixel and a scanline of width*3.
      //   On JDK 26 `SampleModel.getStride` and `Raster.getSampleModelStride` are no longer available, and a wrong
      //   guess does not matter: the sampled comparison below spots it immediately and sends the whole tile back to the slow path,
      int stride = width * 3;
      int offsetX = 0;
      int offsetY = 0;
      // The largest index accessed is height*stride-1. If the buffer is too small the direct read is abandoned
      // <b>before anything is written</b>, because otherwise that would be an ArrayIndexOutOfBoundsException, which would
      // lose the "fall back to the slow path on a wrong guess" guarantee.
      if (data.length < (long)height * stride) {
         return false;
      }

      //  Self-check: compare the samples against getRGB and send the whole tile back to the slow path on any mismatch.
      int probe = Math.min(24, width * height);

      for (int i = 0; i < probe; i++) {
         int x = (i * 977 + 13) % width;
         int y = (i * 613 + 7) % height;
         int p = (y + offsetY) * stride + offsetX + x * 3;
         int argb = image.getRGB(x, y);
         if ((data[p + 2] & 0xFF) != (argb >> 16 & 0xFF)
            || (data[p + 1] & 0xFF) != (argb >> 8 & 0xFF)
            || (data[p] & 0xFF) != (argb & 0xFF)) {
            return false;   // there is colour conversion or the layout does not match, abandon the direct read
         }
      }

      for (int y = 0; y < height; y++) {
         int rowBase = (y + offsetY) * stride + offsetX;

         for (int x = 0; x < width; x++) {
            int p = rowBase + x * 3;
            int blue = data[p] & 0xFF;
            int green = data[p + 1] & 0xFF;
            int red = data[p + 2] & 0xFF;
            target.set(x, y, decodeTerrariumPixel(red, green, blue));
         }
      }

      return true;
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.TERRAIN;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record LatLon(double lat, double lon) {
   }

   private record PolarDemSample(double elevation, TellusElevationSource.DemUsage usage) {
      private static TellusElevationSource.PolarDemSample none() {
         return new TellusElevationSource.PolarDemSample(Double.NaN, null);
      }

      private boolean usable() {
         return this.usage != null && !Double.isNaN(this.elevation);
      }
   }

   public static enum DemUsage {
      TERRAIN_TILES("terrarium", 1),
      SWISSALTI3D_05M("swissalti3d_05m", 1 << 1),
      SWISSALTI3D_2M("swissalti3d_2m", 1 << 2),
      AHN("ahn", 1 << 3),
      CANELEVATION_2M("canelevation_2m", 1 << 4),
      CANELEVATION_30M("canelevation_30m", 1 << 5),
      NORWAYDTM1("norwaydtm1", 1 << 6),
      USGS("usgs", 1 << 7),
      COPERNICUS("copernicus", 1 << 8),
      HMA("hma", 1 << 9),
      ARCTICDEM("arcticdem", 1 << 10),
      REMA("rema", 1 << 11),
      JAPANGSI("japangsi", 1 << 12);

      private final String providerId;
      private final int bit;

      private DemUsage(String providerId, int bit) {
         this.providerId = Objects.requireNonNull(providerId, "providerId");
         this.bit = bit;
      }

      public int bit() {
         return this.bit;
      }

      public String providerId() {
         return this.providerId;
      }

      public double nominalResolutionMeters() {
         return switch (this) {
            case TERRAIN_TILES -> 30.0;
            case SWISSALTI3D_05M -> 0.5;
            case SWISSALTI3D_2M -> 2.0;
            case AHN -> 0.5;
            case CANELEVATION_2M -> 2.0;
            case CANELEVATION_30M -> 30.0;
            case NORWAYDTM1 -> 1.0;
            case USGS -> 10.0;
            case COPERNICUS -> 30.0;
            case HMA -> 8.0;
            case ARCTICDEM -> 2.0;
            case REMA -> 8.0;
            case JAPANGSI -> Double.NaN;
         };
      }

      public Component label() {
         return Component.translatable("property.tellus.dem_provider.value." + this.providerId);
      }
   }

   public record ElevationDiagnostic(
      double elevation, TellusElevationSource.DemUsage primaryProvider, int providerMask, double sourceResolutionMeters
   ) {
      public ElevationDiagnostic(
         double elevation, TellusElevationSource.DemUsage primaryProvider, int providerMask, double sourceResolutionMeters
      ) {
         this.elevation = elevation;
         this.primaryProvider = Objects.requireNonNull(primaryProvider, "primaryProvider");
         this.providerMask = providerMask;
         this.sourceResolutionMeters = sourceResolutionMeters;
      }

      public boolean usesMultipleProviders() {
         return Integer.bitCount(this.providerMask) > 1;
      }

      public double displayResolutionMeters() {
         return Double.isFinite(this.sourceResolutionMeters) && this.sourceResolutionMeters > 0.0
            ? this.sourceResolutionMeters
            : this.primaryProvider.nominalResolutionMeters();
      }
   }

   private record AutoDecision(
      boolean preferUsgs,
      boolean preferTerrainTiles,
      boolean preferCopernicus,
      TellusLandMaskSource.LandMaskSample landMaskSample
   ) {
      private static final TellusElevationSource.AutoDecision DO_NOT_PREFER = new TellusElevationSource.AutoDecision(
         false, false, false, TellusLandMaskSource.LandMaskSample.unknown()
      );
   }
}
