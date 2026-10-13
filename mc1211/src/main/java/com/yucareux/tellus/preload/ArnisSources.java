package com.yucareux.tellus.preload;

import java.net.URI;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.TellusCnOptions;

import java.util.Locale;

/**
 * Arnis project data source addresses (aligned with {@code louis-e/arnis}, see its
 * {@code src/elevation/providers/*} and {@code src/gui/js/bbox.js}).
 *
 * <p>This mod does not hardcode these addresses directly in requests — everything goes through our own Cloudflare Worker proxy
 * ({@code mirror_base} domain), so it can be directly accessed in China and has edge caching. The constants here only describe
 * "which route on the proxy".
 *
 * <h2>Obtained arnis sources (one-to-one with project source code)</h2>
 * <ul>
 *   <li><b>Mapterhorn</b> — {@code tiles.mapterhorn.com/{z}/{x}/{y}.webp},
 *       the first global source after regional high-precision sources in arnis's {@code SourceMode::Auto}
 *       (newer than the old AWS terrarium);</li>
 *   <li><b>Planetary Imagery</b> — Moon OpenPlanetaryMap basemap, Mars Viking MDIM21
 *       (arnis only uses the celestial body's own imagery as basemap beyond Earth);</li>
 *   <li><b>Planetary DEM</b> — Moon LRO LOLA ({@code ldem_128.img}),
 *       Mars MGS MOLA ({@code megt{latitude}{N/S}{longitude}hb.img}, 88×4 band files),
 *       all PDS rasters read via HTTP Range.</li>
 * </ul>
 */
public final class ArnisSources {

   /** Elevation layer (Mapterhorn). */
   public static final String LAYER_MAPTERHORN = "mapterhorn";
   /** Planetary imagery layer. */
   public static final String LAYER_PLANET_IMAGERY = "planet_imagery";
   /** Planetary DEM layer (PDS raster, whole file). */
   public static final String LAYER_PLANET_DEM = "planet_dem";
   /** Map basemap (arnis's Map Theme). */
   public static final String LAYER_MAP_TILES = "map_tiles";

   /**
    * Arnis's Map Theme list (key names exactly match arnis's {@code tile-theme-select}).
    *
    * <p>{@code stadia-*} is available in arnis, but Stadia's terms **prohibit proxy/caching**, and going through our Worker returns 403
    * — so here we use Carto's light/dark as corresponding substitutes.
    * {@code openfreemap-liberty} (Modern) is a MapLibre vector style: the mod side fetches it and **rasters on the fly**
    * (see {@code client.map.OpenFreeMapRasterizer}), so it also works; the color scheme is a built-in simplified version,
    * without text labels.
    */
   public static final String[][] MAP_THEMES = {
      {"osm", "标准（OSM）"},
      {"esri-imagery", "卫星影像（Esri）"},
      {"opentopomap", "地形（OpenTopoMap）"},
      // Carto's basemaps now return watermarked placeholder images for proxy access without a key
      // (actually tested on tiles: "API KEY REQUIRED / carto.com/attribution"), so not recommended by default;
      // route is still kept, those who bring their own key can use it.
      {"carto", "浅色简约（Carto；可能返回带水印的图，需要自备 key）"},
      // arnis's "Modern" (OpenFreeMap vector style): mod side rasterizes on the fly with built-in simplified palette
      // The next two are **rasterized on the fly by us using OpenFreeMap vector data**:
      // no key required, terms explicitly allow proxy and caching, and include Chinese labels.
      {"openfreemap-liberty", "现代浅色（本地渲染矢量 + 中文标注）"},
      {"openfreemap-dark", "现代深色（本地渲染矢量 + 中文标注）"},
      {"moon", "月球影像"},
      {"mars", "火星影像"},
   };

   private ArnisSources() {
   }

   /**
    * OpenFreeMap's vector tile URL template (of the form
    * {@code https://host/openfreemap/planet/<release_version>/{z}/{x}/{y}.pbf}).
    *
    * <p>Release versions change, so it must be read from tilejson once; cached here for 10 minutes.
    * Both the rasterizer (client-side) and the preloader use this single entry point, avoiding duplication.
    */
   public static String openFreeMapTileTemplate(String base) {
      String cached = TILE_TEMPLATE;
      if (cached != null && System.currentTimeMillis() - TILE_TEMPLATE_AT < 600_000L) {
         return cached;
      }
      if (base == null || base.isBlank()) {
         return cached;
      }

      // 2026-10-05: single-flight. This is a synchronous HTTP call with 10s connect + 20s read
      // timeouts, and the fields above are only a 10-minute cache. When the cache was cold, every
      // one of the 16 loading threads missed it and fired its own identical request to the same
      // endpoint -- 16x the traffic and 16 threads parked for up to 30s each. Double-checked
      // locking: the losers block on the monitor, then find the cached value and return at once.
      synchronized (TEMPLATE_LOCK) {
         cached = TILE_TEMPLATE;
         if (cached != null && System.currentTimeMillis() - TILE_TEMPLATE_AT < 600_000L) {
            return cached;
         }
      }

      synchronized (TEMPLATE_LOCK) {
         cached = TILE_TEMPLATE;
         if (cached != null && System.currentTimeMillis() - TILE_TEMPLATE_AT < 600_000L) {
            return cached;
         }

         return fetchTileTemplate(base, cached);
      }
   }

   private static String fetchTileTemplate(String base, String fallback) {
      try {
         java.net.HttpURLConnection connection =
               (java.net.HttpURLConnection) URI.create(base + "/openfreemap/planet").toURL().openConnection();
         connection.setRequestProperty("User-Agent", "TellusCN/1.0 (Minecraft Mod)");
         connection.setConnectTimeout(10000);
         connection.setReadTimeout(20000);
         if (connection.getResponseCode() != 200) {
            return fallback;
         }
         String body;
         try (java.io.InputStream in = connection.getInputStream()) {
            body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
         }
         int mark = body.indexOf("\"tiles\"");
         if (mark < 0) {
            return fallback;
         }
         int quote = body.indexOf('"', body.indexOf('[', mark));
         int end = quote < 0 ? -1 : body.indexOf('"', quote + 1);
         if (quote < 0 || end < 0) {
            return fallback;
         }
         String first = body.substring(quote + 1, end);
         if (!first.contains("{z}")) {
            return fallback;
         }
         TILE_TEMPLATE = first;
         TILE_TEMPLATE_AT = System.currentTimeMillis();
         return first;
      } catch (java.io.IOException | RuntimeException e) {
         Tellus.LOGGER.debug("Failed to fetch vector tile template: {}", e.toString());
         return fallback;
      }
   }

   /** Guards the tile-template refresh so a cold cache produces one request, not sixteen. */
   private static final Object TEMPLATE_LOCK = new Object();
   private static volatile String TILE_TEMPLATE;
   private static volatile long TILE_TEMPLATE_AT;

   /** Zoom for map tile preload (default 11). */
   public static int mapTileZoom() {
      return Integer.getInteger("telluscn.arnis.mapTileZoom", 11);
   }

   /** Which zoom to use for arnis elevation under current config (Mapterhorn's available levels). */
   public static int mapterhornZoom() {
      return Integer.getInteger("telluscn.arnis.mapterhornZoom", 12);
   }

   /** Planetary imagery zoom. */
   public static int planetImageryZoom() {
      return Integer.getInteger("telluscn.arnis.planetImageryZoom", 5);
   }

   /** Moon PDS raster filename (single global file). */
   public static final String MOON_DEM_FILE = "ldem_128.img";

   /** Moon DEM size in bytes (used to report volume before preload). */
   public static final long MOON_DEM_BYTES = 2_123_366_400L;

   /**
    * Mars PDS raster filename: {@code megt{max_lat:02}{n|s}{start_lon:03}hb.img}.
    * (Consistent with arnis's {@code DemSpec::tile_name}, which has unit tests locking these names.)
    */
   public static String marsDemFile(int maxLatBand, boolean north, int lonBand) {
      return String.format(Locale.ROOT, "megt%02d%s%03dhb.img", Math.abs(maxLatBand),
            north ? "n" : "s", lonBand);
   }

   /** Bytes per Mars band file. */
   public static final long MARS_DEM_BYTES_PER_FILE = 129_761_280L;

   /** Whether this planet has an available PDS DEM. */
   public static boolean hasPlanetaryDem(TellusCnOptions.CelestialBody body) {
      return body == TellusCnOptions.CelestialBody.MOON || body == TellusCnOptions.CelestialBody.MARS;
   }

   /** Planetary imagery route name on the proxy (matches Worker's TILE_SOURCES key). */
   public static String planetImagerySource(TellusCnOptions.CelestialBody body) {
      return body == TellusCnOptions.CelestialBody.MARS ? "mars" : "moon";
   }
}
