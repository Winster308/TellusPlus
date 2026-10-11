package com.yucareux.tellus.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yucareux.tellus.Tellus;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * TellusCN's own runtime options ({@code config/telluscn.json}).
 *
 * <p>Why they are not stuffed into {@code EarthGeneratorSettings}: that is part of the **world data** (it goes into level.dat and
 * the codec has to be re-aligned every time a world is opened), whereas these are **client-side and runtime** choices such as
 * "which data source / which base map to use" -- changing them does not require rebuilding the world, and old saves should not fail to load just because of one extra field.
 *
 * <p>The data source design references the arnis project ({@code SourceMode} in {@code src/elevation/selector.rs} and
 * {@code CelestialBody} in {@code src/celestial.rs}):
 * <ul>
 *   <li>{@link DataSource#TELLUS} -- the original Tellus data chain (AWS terrarium tiles +
 *       high-precision DEMs of various countries + ESA WorldCover + Overture);</li>
 *   <li>{@link DataSource#ARNIS} -- the arnis data chain (**Mapterhorn global elevation** first,
 *       falling back to AWS; NASA PDS rasters for planets).</li>
 * </ul>
 *
 * <p>{@link CelestialBody} decides whether it runs on Earth, the Moon or Mars; off Earth it is switched to the
 * planetary imagery base map used by arnis (OpenPlanetaryMap for the Moon, Viking MDIM21 for Mars).
 */
public final class TellusCnOptions {

   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("telluscn.json");

   /** Terrain / data source. */
   public enum DataSource {
      TELLUS, ARNIS;

      public static DataSource parse(String raw) {
         if (raw == null) {
            return TELLUS;
         }
         return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "arnis" -> ARNIS;
            default -> TELLUS;
         };
      }
   }

   /** Celestial body (aligned with arnis's CelestialBody). */
   public enum CelestialBody {
      EARTH, MOON, MARS;

      public static CelestialBody parse(String raw) {
         if (raw == null) {
            return EARTH;
         }
         return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "moon", "luna" -> MOON;
            case "mars" -> MARS;
            default -> EARTH;
         };
      }

      public String id() {
         return name().toLowerCase(Locale.ROOT);
      }

      /** Display name in the UI (aligned with arnis's CelestialBody::display_name). */
      public String displayName() {
         return switch (this) {
            case EARTH -> "地球";
            case MOON -> "月球";
            case MARS -> "火星";
         };
      }
   }

   /** On-disk structure. */
   private static final class Data {
      String dataSource = DataSource.TELLUS.name();
      String celestialBody = CelestialBody.EARTH.name();
      /**
       * Map theme. Defaults to {@code openfreemap-liberty}: **no key is needed**,
       * the data source explicitly allows proxying and caching, and it carries Chinese labels -- unlike Carto/Stadia, which
       * suddenly return watermark placeholder images in proxy scenarios.
       */
      String mapTheme = "openfreemap-liberty";
      /** Whether preloading is allowed to land in the disk cache (turned off, it only uses memory). */
      boolean preloadToDisk = true;
   }

   private static Data data = new Data();
   private static volatile boolean loaded;

   private TellusCnOptions() {
   }

   // ---------------------------------------------------------------- reading

   public static DataSource dataSource() {
      ensureLoaded();
      return DataSource.parse(data.dataSource);
   }

   public static void setDataSource(DataSource source) {
      ensureLoaded();
      data.dataSource = source.name();
      save();
   }

   public static CelestialBody celestialBody() {
      ensureLoaded();
      return CelestialBody.parse(data.celestialBody);
   }

   public static void setCelestialBody(CelestialBody body) {
      ensureLoaded();
      data.celestialBody = body.name();
      save();
   }

   public static String mapTheme() {
      ensureLoaded();
      return data.mapTheme;
   }

   public static void setMapTheme(String theme) {
      ensureLoaded();
      data.mapTheme = theme == null || theme.isBlank() ? "osm" : theme.trim();
      save();
   }

   public static boolean preloadToDisk() {
      ensureLoaded();
      return data.preloadToDisk;
   }

   public static void setPreloadToDisk(boolean value) {
      ensureLoaded();
      data.preloadToDisk = value;
      save();
   }

   // ---------------------------------------------------------------- writing to disk

   private static void ensureLoaded() {
      if (loaded) {
         return;
      }
      synchronized (TellusCnOptions.class) {
         if (loaded) {
            return;
         }
         if (Files.exists(FILE)) {
            try {
               Data parsed = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Data.class);
               if (parsed != null) {
                  data = parsed;
               }
            } catch (IOException | RuntimeException e) {
               Tellus.LOGGER.warn("Failed to read telluscn.json, falling back to default values: {}", e.toString());
            }
         }
         loaded = true;
      }
   }

   private static void save() {
      try {
         Path parent = FILE.getParent();
         if (parent != null) {
            Files.createDirectories(parent);
         }
         Files.writeString(FILE, GSON.toJson(data), StandardCharsets.UTF_8);
      } catch (IOException e) {
         Tellus.LOGGER.warn("Failed to write telluscn.json: {}", e.toString());
      }
   }
}
