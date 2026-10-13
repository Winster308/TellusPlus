package com.yucareux.tellus.preload;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Identifiers and parsing of preload layers.
 *
 * <p>The default set is **buildings + roads**: these two are the "visible" Overture data (PMTiles are fetched by Range),
 * downloading them all at once is the most cost-effective; elevation / landcover can be ticked separately on demand.
 */
public final class PreloadLayers {

   public static final String ELEVATION = "elevation";
   public static final String LANDCOVER = "landcover";
   public static final String BUILDINGS = "buildings";
   public static final String ROADS = "roads";
   // ---- arnis source (see ArnisSources)----
   /** Mapterhorn global elevation tiles. */
   public static final String MAPTERHORN = "mapterhorn";
   /** Planet imagery (Moon / Mars). */
   public static final String PLANET_IMAGERY = "planet_imagery";
   /** Planet DEM (NASA PDS raster). */
   public static final String PLANET_DEM = "planet_dem";
   /** Map base-layer tiles. */
   public static final String MAP_TILES = "map_tiles";

   /** All available layers (the order is the display order in the UI). */
   public static final Set<String> ALL = Set.of(ELEVATION, LANDCOVER, BUILDINGS, ROADS,
         MAPTERHORN, PLANET_IMAGERY, PLANET_DEM, MAP_TILES);

   private PreloadLayers() {
   }

   /** Default layers for a given data source. */
   public static Set<String> defaultsFor(String source) {
      return "arnis".equals(source) ? Set.of(MAPTERHORN) : defaults();
   }

   /** Default download contents: buildings + roads. */
   public static Set<String> defaults() {
      return Set.of(BUILDINGS, ROADS);
   }

   /** Parse a comma-separated layer string; empty string/null/{@code default} all return the default set. */
   public static Set<String> parse(String csv) {
      if (csv == null || csv.isBlank() || "default".equalsIgnoreCase(csv.trim())) {
         return defaults();
      }
      String normalized = csv.trim().toLowerCase(Locale.ROOT).replace(' ', ',');
      if ("all".equals(normalized) || "*".equals(normalized)) {
         return ALL;
      }
      Set<String> result = new LinkedHashSet<>();
      for (String part : normalized.split(",")) {
         String id = part.trim();
         if (ALL.contains(id)) {
            result.add(id);
         }
      }
      return result.isEmpty() ? defaults() : Set.copyOf(result);
   }

   public static String format(Set<String> layers) {
      return String.join(",", layers);
   }

   /**
    * Translation key for a layer. The caller resolves it through the client
    * language file (see {@code assets/tellus/lang/*.json}, keys {@code tellus.layer.*}).
    *
    * <p>Returns the raw id unchanged for unknown layers so callers can still show something.
    */
   public static String label(String layer) {
      return switch (layer) {
         case ELEVATION -> "tellus.layer.elevation";
         case LANDCOVER -> "tellus.layer.landcover";
         case BUILDINGS -> "tellus.layer.buildings";
         case ROADS -> "tellus.layer.roads";
         case MAPTERHORN -> "tellus.layer.mapterhorn";
         case PLANET_IMAGERY -> "tellus.layer.planet_imagery";
         case PLANET_DEM -> "tellus.layer.planet_dem";
         case MAP_TILES -> "tellus.layer.map_tiles";
         default -> layer;
      };
   }
}
