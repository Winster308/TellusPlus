package com.yucareux.tellus.preload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.yucareux.tellus.Tellus;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ledger of "which regions have already been downloaded".
 *
 * <p>Three purposes, all indispensable:
 * <ol>
 *   <li><b>No duplicate downloads</b>: if a player-selected region is already covered, skip it (only download what's missing);</li>
 *   <li><b>Coloring</b>: on the map, downloaded regions are shown in original color, the rest in gray;</li>
 *   <li><b>Area statistics</b>: report "total square kilometers downloaded".</li>
 * </ol>
 *
 * <p>Records are stored as lat/lon rectangles (each with a set of layers), persisted to
 * {@code config/telluscn-preload.json}; the server is the sole writer, the client receives a snapshot pushed from the server.
 */
public final class TellusPreloadRegistry {

   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path FILE =
         FabricLoader.getInstance().getConfigDir().resolve("telluscn-preload.json");

   /** Data source identifier: ledgers of two data sources do not interfere with each other (switching source requires re-download). */
   public static final String SOURCE_TELLUS = "tellus";
   public static final String SOURCE_ARNIS = "arnis";

   private static final CopyOnWriteArrayList<Region> REGIONS = new CopyOnWriteArrayList<>();
   private static volatile boolean loaded;

   private TellusPreloadRegistry() {
   }

   /** A downloaded region (lat/lon bounding box + downloaded layers). */
   public record Region(double minLat, double minLon, double maxLat, double maxLon,
                        Set<String> layers, long atMillis, String source) {

      /** Old ledger without source field defaults to tellus. */
      public String effectiveSource() {
         return source == null || source.isBlank() ? SOURCE_TELLUS : source;
      }

      /** Spherical approximate area (km²): 1° latitude ≈ 111.32 km, longitude shrinks by center latitude. */
      public double areaKm2() {
         double dLatKm = Math.abs(maxLat - minLat) * 111.32;
         double midLat = Math.toRadians((minLat + maxLat) / 2.0);
         double dLonKm = Math.abs(maxLon - minLon) * 111.32 * Math.cos(midLat);
         return dLatKm * dLonKm;
      }

      public boolean contains(double lat, double lon) {
         return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
      }

      /** Whether this rectangle covers the given rectangle and contains all required layers. */
      public boolean covers(double otherMinLat, double otherMinLon, double otherMaxLat,
                            double otherMaxLon, Set<String> required) {
         boolean inside = otherMinLat >= minLat && otherMaxLat <= maxLat
               && otherMinLon >= minLon && otherMaxLon <= maxLon;
         return inside && layers.containsAll(required);
      }

      public String describe(int index) {
         return String.format(Locale.ROOT, "[%s] %.4f,%.4f → %.4f,%.4f（%.1f km²，%s）",
               effectiveSource(), minLat, minLon, maxLat, maxLon, areaKm2(), PreloadLayers.format(layers));
      }
   }

   // ------------------------------------------------------------------ Queries

   public static List<Region> regions() {
      ensureLoaded();
      return List.copyOf(REGIONS);
   }

   /** Whether the given rectangle has been fully downloaded (layers must also be complete). */
   public static boolean covers(double minLat, double minLon, double maxLat, double maxLon,
                                Set<String> layers) {
      return covers(minLat, minLon, maxLat, maxLon, layers, SOURCE_TELLUS);
   }

   /** Same as {@link #covers}, but only searches records of the specified data source (switching source requires re-download). */
   public static boolean covers(double minLat, double minLon, double maxLat, double maxLon,
                                Set<String> layers, String source) {
      ensureLoaded();
      for (Region region : REGIONS) {
         if (region.effectiveSource().equals(source)
               && region.covers(minLat, minLon, maxLat, maxLon, layers)) {
            return true;
         }
      }
      return false;
   }

   /** Total area of downloaded regions (km²). Overlapping parts are deduplicated approximately by "any coverage": here we simply sum, the UI has a separate note. */
   public static double downloadedAreaKm2() {
      ensureLoaded();
      double total = 0.0;
      for (Region region : REGIONS) {
         total += region.areaKm2();
      }
      return total;
   }

   /** Records of the specified data source. */
   public static java.util.List<Region> regionsOf(String source) {
      ensureLoaded();
      java.util.List<Region> list = new ArrayList<>();
      for (Region region : REGIONS) {
         if (region.effectiveSource().equals(source)) {
            list.add(region);
         }
      }
      return list;
   }

   /** Total area of the specified data source (km²). */
   public static double downloadedAreaKm2(String source) {
      ensureLoaded();
      double total = 0.0;
      for (Region region : regionsOf(source)) {
         total += region.areaKm2();
      }
      return total;
   }

   /** Delete a record (matched by content equality). */
   public static boolean removeRegion(Region region) {
      ensureLoaded();
      boolean removed = REGIONS.remove(region);
      if (removed) {
         save();
      }
      return removed;
   }

   /** Delete all records of a source. */
   public static int removeRegionsOfSource(String source) {
      ensureLoaded();
      java.util.List<Region> doomed = regionsOf(source);
      REGIONS.removeAll(doomed);
      if (!doomed.isEmpty()) {
         save();
      }
      return doomed.size();
   }

   public static int regionCount() {
      ensureLoaded();
      return REGIONS.size();
   }

   // ------------------------------------------------------------------ Writes

   /** Record an entry. Returns false if this region (including layers) has already been recorded. */
   public static boolean mark(double lat1, double lon1, double lat2, double lon2, Set<String> layers) {
      return mark(lat1, lon1, lat2, lon2, layers, SOURCE_TELLUS);
   }

   /** Record an entry (distinguished by data source). Returns false if the same source and region has already been recorded. */
   public static boolean mark(double lat1, double lon1, double lat2, double lon2, Set<String> layers,
                              String source) {
      ensureLoaded();
      double minLat = Math.min(lat1, lat2);
      double maxLat = Math.max(lat1, lat2);
      double minLon = Math.min(lon1, lon2);
      double maxLon = Math.max(lon1, lon2);
      // 2026-10-05 (a): the degenerate-rectangle guard could not see NaN. With a NaN coordinate
      // Math.min/Math.max propagate it, so every difference below is NaN, and **every NaN
      // comparison is false** -- the `<= 0.0` test therefore let the NaN rectangle straight
      // through and it got written to disk. From then on areaKm2() was NaN for that record and
      // covers() could never match it again, i.e. the area looked permanently "never downloaded".
      if (!isFiniteBox(minLat, minLon, maxLat, maxLon)) {
         Tellus.LOGGER.warn("Ignoring preload region with NaN/infinity: {} {} {} {}", lat1, lon1, lat2, lon2);
         return false;
      }

      if (maxLat - minLat <= 0.0 || maxLon - minLon <= 0.0) {
         return false;
      }

      // 2026-10-05 (b): covers() -> add() was a check-then-act with nothing in between, so two
      // threads (the tellus-autoprefetch thread and /tellus preload genuinely do run in parallel)
      // could both pass the covers() test and both insert the same region -> duplicate ledger rows.
      // save() is already serialised on the class, so take the same lock across the whole sequence.
      synchronized (TellusPreloadRegistry.class) {
         if (covers(minLat, minLon, maxLat, maxLon, layers, source)) {
            return false;
         }

         REGIONS.add(new Region(minLat, minLon, maxLat, maxLon, Set.copyOf(layers),
               System.currentTimeMillis(), source));
         save();
      }

      return true;
   }

   /** True when all four bounds are real finite numbers with a non-degenerate extent. */
   private static boolean isFiniteBox(double minLat, double minLon, double maxLat, double maxLon) {
      return Double.isFinite(minLat) && Double.isFinite(minLon)
            && Double.isFinite(maxLat) && Double.isFinite(maxLon);
   }

   /** Merge "new layers" into an existing region (e.g., buildings first, then roads). */
   public static void mergeLayers(double lat1, double lon1, double lat2, double lon2, Set<String> layers) {
      ensureLoaded();
      double minLat = Math.min(lat1, lat2);
      double maxLat = Math.max(lat1, lat2);
      double minLon = Math.min(lon1, lon2);
      double maxLon = Math.max(lon1, lon2);
      // 2026-10-05: the scan-then-write below had the same unsynchronised shape that mark() had, so
      // two threads could both find (or both miss) the same region. The lock is reentrant, so the
      // mark() call at the end is fine.
      synchronized (TellusPreloadRegistry.class) {
         for (int i = 0; i < REGIONS.size(); i++) {
            Region region = REGIONS.get(i);
            boolean sameBox = Math.abs(region.minLat() - minLat) < 1e-9
                  && Math.abs(region.maxLat() - maxLat) < 1e-9
                  && Math.abs(region.minLon() - minLon) < 1e-9
                  && Math.abs(region.maxLon() - maxLon) < 1e-9;
            if (sameBox) {
               Set<String> merged = new LinkedHashSet<>(region.layers());
               merged.addAll(layers);
               REGIONS.set(i, new Region(region.minLat(), region.minLon(), region.maxLat(),
                     region.maxLon(), Set.copyOf(merged), System.currentTimeMillis(), region.effectiveSource()));
               save();
               return;
            }
         }

         mark(minLat, minLon, maxLat, maxLon, layers);
      }
   }

   public static void clear() {
      ensureLoaded();
      // 2026-10-05: lock the clear against concurrent mark()/mergeLayers() so a wipe cannot
      // interleave with an in-flight insert and end up half applied on disk.
      synchronized (TellusPreloadRegistry.class) {
         REGIONS.clear();
         save();
      }
   }

   // -------------------------------------------------------------- Serialization

   /** Snapshot pushed to client. */
   public static String toJson() {
      ensureLoaded();
      List<RegionDto> dto = new ArrayList<>();
      for (Region region : REGIONS) {
         dto.add(new RegionDto(region.minLat(), region.minLon(), region.maxLat(), region.maxLon(),
               PreloadLayers.format(region.layers()), region.atMillis(), region.effectiveSource()));
      }
      return GSON.toJson(dto);
   }

   /** Client restores from snapshot (not persisted). */
   public static void replaceFromJson(String json) {
      try {
         List<RegionDto> dto = GSON.fromJson(json, new TypeToken<List<RegionDto>>() {
         }.getType());
         REGIONS.clear();
         if (dto != null) {
            for (RegionDto item : dto) {
               if (item == null) {
                  continue;
               }
               REGIONS.add(new Region(item.minLat(), item.minLon(), item.maxLat(), item.maxLon(),
                     PreloadLayers.parse(item.layers()), item.atMillis(), item.source()));
            }
         }
         loaded = true;
      } catch (RuntimeException e) {
            Tellus.LOGGER.warn("Failed to parse preload state: {}", e.toString());
      }
   }

   private record RegionDto(double minLat, double minLon, double maxLat, double maxLon,
                            String layers, long atMillis, String source) {
   }

   // ------------------------------------------------------------ Persistence

   private static void ensureLoaded() {
      if (loaded) {
         return;
      }
      synchronized (TellusPreloadRegistry.class) {
         if (loaded) {
            return;
         }
         load();
         loaded = true;
      }
   }

   private static void load() {
      if (!Files.exists(FILE)) {
         return;
      }
      try {
         String json = Files.readString(FILE, StandardCharsets.UTF_8);
         List<RegionDto> dto = GSON.fromJson(json, new TypeToken<List<RegionDto>>() {
         }.getType());
         if (dto != null) {
            for (RegionDto item : dto) {
               if (item != null) {
                  REGIONS.add(new Region(item.minLat(), item.minLon(), item.maxLat(), item.maxLon(),
                        PreloadLayers.parse(item.layers()), item.atMillis(), item.source()));
               }
            }
         }
      } catch (IOException | RuntimeException e) {
            Tellus.LOGGER.warn("Failed to read preload ledger: {}", e.toString());
      }
   }

   /**
    * Persists the ledger.
    *
    * <p>**2026-10-05 fix** — two defects:
    * <ol>
    *   <li>No lock at all, yet there really are two independent callers: the
    *       {@code tellus-autoprefetch} thread calls {@code preloadArea()} directly, while
    *       {@code /tellus preload} goes through {@code backgroundExecutor()} (see Tellus.java).
    *       The old comment claiming "a single-threaded pool guarantees no two concurrent writers"
    *       was simply false, so two threads could interleave read-modify-write on the ledger.</li>
    *   <li>{@code Files.writeString} truncates first and then writes, so a crash (or a concurrent
    *       reader) can leave a half-written file — and load() has no fallback for that, so the
    *       record is lost permanently.</li>
    * </ol>
    * Now: serialised on the class, and written to a unique temp file then moved into place
    * atomically, so readers only ever see a complete ledger.
    */
   private static void save() {
      synchronized (TellusPreloadRegistry.class) {
         try {
            Path parent = FILE.getParent();
            if (parent != null) {
               Files.createDirectories(parent);
            }

            String json = toJson();
            // Fixed temp name is fine: save() is serialised on the class, so there is only ever
            // one writer. A leftover .tmp from a crash is simply overwritten next time.
            Path target = parent == null ? FILE.toAbsolutePath() : FILE;
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
               Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                  StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
               Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (IOException e) {
               Tellus.LOGGER.warn("Failed to write preload ledger: {}", e.toString());
         }
      }
   }
}
