package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.preload.PreloadLayers;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.preload.TellusPreloadRegistry;

import java.util.Locale;
import java.util.Set;

/**
 * Preloads data for a latitude/longitude rectangle (the area a player selects on the map).
 *
 * <p>The approach is to **reuse the prefetch entry point each data source already has** instead of writing
 * a separate download path:
 * <ul>
 *   <li>{@link TellusElevationSource#prefetchTiles}, the terrain elevation tiles;</li>
 *   <li>{@link TellusLandCoverSource#prefetchTiles}, the ESA WorldCover land cover;</li>
 *   <li>{@link TellusOsmBuildingSource#prefetchTiles} and {@link TellusOsmRoadSource#prefetchTiles},
 *       the Overture buildings and roads (PMTiles, pulled with Range requests).</li>
 * </ul>
 *
 * <p>These prefetches were already wired to the shared multi threaded download pool in an earlier round
 * ({@code TellusDownloadExecutor}), so all this class does is lay the area out as a batch of call sites while
 * the actual downloads run in parallel and each tile is still fetched only once. Selecting an area a few
 * hundred kilometres across does not block the calling thread.
 *
 * <p>Areas that were already downloaded are looked up in {@link TellusPreloadRegistry} and skipped; only
 * the missing parts are queued for download.
 */
public final class TellusAreaPreloader {

   /** Spacing between adjacent call sites in blocks, roughly the ground width covered by one DEM tile: dense enough to not miss anything. */
   private static final int STEP_BLOCKS = intProperty("tellus.preload.stepBlocks", 768);
   /** Upper bound on the call sites one selection may lay out, so selecting "all of Earth" cannot flood the queue. */
   private static final int MAX_POINTS = intProperty("tellus.preload.maxPoints", 20000);

   private TellusAreaPreloader() {
   }

   /**
    * Preload result.
    *
    * @param points    number of prefetch call sites actually issued
    * @param layerCalls number of download requests after expanding each call site by its layer count
    * @param areaKm2   area of the downloaded range
    * @param skipped   the whole range was already downloaded and was skipped
    * @param capped    whether the call site cap was hit (the range was too big, only part was laid out)
    */
   public record Result(int points, int layerCalls, double areaKm2, boolean skipped, boolean capped, long millis) {

      public String describe() {
         if (skipped) {
            return "Region already downloaded, skipped (no new downloads)";
         }
         StringBuilder sb = new StringBuilder();
         sb.append(String.format(Locale.ROOT, "Download started: %.1f km^2, %d sample points x %d layers = %d requests, took %d ms",
               areaKm2, points, layerCalls / Math.max(1, points), layerCalls, millis));
         if (capped) {
            sb.append(" (region too large, truncated to the limit; select in batches)");
         }
         return sb.toString();
      }
   }

   /**
    * Download the given latitude/longitude rectangle.
    *
    * @param layers the layers to download; null uses the defaults (buildings and roads)
    */
   public static Result preload(double lat1, double lon1, double lat2, double lon2,
                                Set<String> layers, EarthGeneratorSettings settings) {
      long start = System.nanoTime();
      double minLat = Math.max(-85.0, Math.min(lat1, lat2));
      double maxLat = Math.min(85.0, Math.max(lat1, lat2));
      double minLon = Math.max(-180.0, Math.min(lon1, lon2));
      double maxLon = Math.min(180.0, Math.max(lon1, lon2));
      Set<String> effective = layers == null || layers.isEmpty() ? PreloadLayers.defaults() : layers;

      double areaKm2 = areaKm2(minLat, minLon, maxLat, maxLon);
      if (areaKm2 <= 0.0) {
         return new Result(0, 0, 0.0, false, false, 0L);
      }

      // The whole area was already downloaded (including the layers we need), so do not download it again
      if (TellusPreloadRegistry.covers(minLat, minLon, maxLat, maxLon, effective)) {
         return new Result(0, 0, areaKm2, true, false, (System.nanoTime() - start) / 1_000_000L);
      }

      double worldScale = settings == null ? EarthGeneratorSettings.DEFAULT.worldScale() : settings.worldScale();
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);

      double x1 = minLon * blocksPerDegree;
      double x2 = maxLon * blocksPerDegree;
      double z1 = EarthProjection.latToBlockZ(maxLat, worldScale);
      double z2 = EarthProjection.latToBlockZ(minLat, worldScale);

      double minX = Math.min(x1, x2);
      double maxX = Math.max(x1, x2);
      double minZ = Math.min(z1, z2);
      double maxZ = Math.max(z1, z2);

      TellusElevationSource elevation = TellusWorldgenSources.elevation();
      TellusLandCoverSource landCover = TellusWorldgenSources.landCover();
      TellusOsmBuildingSource buildings = TellusWorldgenSources.osmBuildings();
      TellusOsmRoadSource roads = TellusWorldgenSources.osmRoads();

      boolean wantElevation = effective.contains(PreloadLayers.ELEVATION);
      boolean wantLandCover = effective.contains(PreloadLayers.LANDCOVER);
      boolean wantBuildings = effective.contains(PreloadLayers.BUILDINGS) && buildings.available();
      boolean wantRoads = effective.contains(PreloadLayers.ROADS) && roads.available();

      int perLayer = (wantElevation ? 1 : 0) + (wantLandCover ? 1 : 0)
            + (wantBuildings ? 1 : 0) + (wantRoads ? 1 : 0);
      if (perLayer == 0) {
         Tellus.LOGGER.warn("Preload: none of the selected layers are available (buildings and roads need Overture), nothing was started");
         return new Result(0, 0, areaKm2, false, false, (System.nanoTime() - start) / 1_000_000L);
      }

      int points = 0;
      boolean capped = false;
      outer:
      for (double bx = minX; bx <= maxX + STEP_BLOCKS; bx += STEP_BLOCKS) {
         for (double bz = minZ; bz <= maxZ + STEP_BLOCKS; bz += STEP_BLOCKS) {
            if (points >= MAX_POINTS) {
               capped = true;
               break outer;
            }
            if (wantElevation) {
               elevation.prefetchTiles(bx, bz, worldScale, 1);
            }
            if (wantLandCover) {
               landCover.prefetchTiles(bx, bz, worldScale, 1);
            }
            if (wantBuildings) {
               buildings.prefetchTiles(bx, bz, worldScale, 1);
            }
            if (wantRoads) {
               roads.prefetchTiles(bx, bz, worldScale, 1);
            }
            points++;
         }
      }

      TellusPreloadRegistry.mark(minLat, minLon, maxLat, maxLon, effective);
      Tellus.LOGGER.info("Preload: area {},{},{},{} layers={} -> {} sample points", minLat, minLon, maxLat, maxLon,
            PreloadLayers.format(effective), points);

      return new Result(points, points * perLayer, areaKm2, false, capped,
            (System.nanoTime() - start) / 1_000_000L);
   }

   /** Spherical approximation of the area in km^2. */
   public static double areaKm2(double minLat, double minLon, double maxLat, double maxLon) {
      double dLatKm = Math.abs(maxLat - minLat) * 111.32;
      double midLat = Math.toRadians((minLat + maxLat) / 2.0);
      double dLonKm = Math.abs(maxLon - minLon) * 111.32 * Math.cos(midLat);
      return dLatKm * dLonKm;
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         return Math.max(1, Integer.parseInt(value));
      } catch (NumberFormatException ignored) {
         return defaultValue;
      }
   }
}
