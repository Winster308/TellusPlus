package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.TellusCnOptions;
import com.yucareux.tellus.world.data.elevation.MapterhornElevationSource;
import com.yucareux.tellus.world.data.elevation.PlanetaryDemSource;

import java.util.concurrent.atomic.AtomicBoolean;
import com.yucareux.tellus.worldgen.EarthProjection;

/**
 * Elevation data source dispatcher: it routes the generation side elevation requests to the
 * **arnis** sources according to the configuration.
 *
 * <p>The call site is {@code TellusElevationSource#sampleElevationMeters}, the point where every real
 * generation path converges: it asks here first and only falls back to the original tellus chain when the
 * answer is {@code null}, so the behaviour when nothing can be fetched stays predictable instead of
 * silently switching to another dataset.
 *
 * <h2>Dispatch rules (aligned with arnis {@code SourceMode})</h2>
 * <ul>
 *   <li>{@code celestialBody != EARTH} goes to the **planetary DEM** (Moon LOLA, Mars MOLA).
 *       Planets do <b>not</b> fall back to the Earth sources: the Earth tiles only produce sea level noise
 *       at those coordinates (arnis {@code SourceMode::Planetary} is designed the same way). A failed read
 *       yields 0, the reference plane, so the world still generates without crashing;</li>
 *   <li>{@code dataSource == ARNIS} goes to **Mapterhorn** global elevation, falling back to the tellus
 *       chain when nothing is available (for example an all ocean tile 404);</li>
 *   <li>anything else ({@code TELLUS}) returns {@code null} and stays entirely on the original chain.</li>
 * </ul>
 */
public final class TellusArnisElevation {

   /** Chunk to "uses Mapterhorn", decided once when the chunk is first sampled. */
   private static final com.google.common.cache.LoadingCache<Long, Boolean> CHUNK_SOURCE =
      com.google.common.cache.CacheBuilder.newBuilder()
         .maximumSize(20000)
         .build(new com.google.common.cache.CacheLoader<Long, Boolean>() {
            @Override
            public Boolean load(Long key) {
               return Boolean.FALSE;
            }
         });

   private static final AtomicBoolean OUT_OF_RANGE_WARNED = new AtomicBoolean();

   private static final AtomicBoolean PLANET_FALLBACK_WARNED = new AtomicBoolean();
   private static final AtomicBoolean MAPTERHORN_WARNED = new AtomicBoolean();

   private TellusArnisElevation() {
   }

   /**
    * Sample an elevation in metres; returning {@code null} means "this dispatcher does not take over,
    * use the original chain".
    *
    * @param blockX     world X in blocks
    * @param blockZ     world Z in blocks
    * @param worldScale metres per block (this decides the Mapterhorn zoom level)
    */
   public static Double sampleMeters(double blockX, double blockZ, double worldScale) {
      TellusCnOptions.CelestialBody body = TellusCnOptions.celestialBody();
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      if (blocksPerDegree <= 0.0) {
         return null;
      }
      // Safe coordinate guard: generate nothing once it leaves the 26 bit BlockPos packing range
      // (the coordinates there overflow anyway, so any elevation sampled is garbage; better to leave it empty or ocean)
      if (Math.abs(blockX) > EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE
            || Math.abs(blockZ) > EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE) {
         if (OUT_OF_RANGE_WARNED.compareAndSet(false, true)) {
            Tellus.LOGGER.warn("Coordinates are outside the safe range (|x| or |z| > {}); no terrain is generated there."
                  + "To make the whole world walkable, set the world scale to >= {} m per block",
                  EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE,
                  String.format(java.util.Locale.ROOT, "%.2f", EarthGeneratorSettings.MIN_SAFE_WORLD_SCALE));
         }
         return null;
      }
      double latitude = EarthProjection.blockZToLat(blockZ, worldScale);
      double longitude = EarthProjection.wrapLongitude(blockX / blocksPerDegree);

      if (body != TellusCnOptions.CelestialBody.EARTH) {
         if (!PlanetaryDemSource.supported(body)) {
            return null;
         }
         Double value = PlanetaryDemSource.sample(body, latitude, longitude);
         if (value != null) {
            return value;
         }
         if (PLANET_FALLBACK_WARNED.compareAndSet(false, true)) {
            Tellus.LOGGER.warn("The planetary DEM has no data for this point ({} @ {},{}); this sample is treated as the 0 m reference plane;"
                  + "it does not fall back to the Earth elevation sources, since that would produce sea level noise", body, latitude, longitude);
         }
         return 0.0;
      }

      if (TellusCnOptions.dataSource() != TellusCnOptions.DataSource.ARNIS) {
         return null;
      }

      // **Lock the elevation source per chunk**: a chunk either uses Mapterhorn entirely or falls back to the
      // upstream chain entirely. The earlier version fell back "per point", which mixed two data sources
      // inside one chunk, so adjacent blocks had inconsistent heights. That showed up as buildings missing
      // limbs, or even sprouting towers hundreds of blocks tall (confirmed with real screenshots).
      long chunkKey = (((long) Math.floor(blockX) >> 4) << 32) ^ ((int) Math.floor(blockZ) >> 4);
      Boolean useArnis = CHUNK_SOURCE.getIfPresent(chunkKey);
      if (useArnis == null) {
         useArnis = MapterhornElevationSource.hasCachedCoverage(latitude, longitude, worldScale);
         CHUNK_SOURCE.put(chunkKey, useArnis);
      }
      if (!useArnis) {
         return null;
      }
      Double value = MapterhornElevationSource.sample(latitude, longitude, worldScale);
      if (value == null && MAPTERHORN_WARNED.compareAndSet(false, true)) {
         Tellus.LOGGER.info("Mapterhorn has no tile at ({},{}) (open ocean tiles are 404 by design),"
               + "falling back to the original elevation chain", latitude, longitude);
      }
      return value;
   }

   /** Clear the decode caches after the data source or celestial body changes. */
   public static void invalidate() {
      MapterhornElevationSource.invalidate();
      PlanetaryDemSource.invalidate();
      CHUNK_SOURCE.invalidateAll();
   }
}
