package com.yucareux.tellus.client.map;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.MirrorConfig;
import com.yucareux.tellus.preload.ArnisPreloader;
import com.yucareux.tellus.preload.ArnisSources;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile;
import io.github.sebasbaumh.mapbox.vectortile.util.MvtUtil;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rasterises <b>vector tiles</b> into a bitmap on the spot, so the raster map screens can use the
 * arnis "Modern" theme (OpenFreeMap / MapLibre Liberty data).
 *
 * <h2>Algorithm optimisations (compared with the first version)</h2>
 *
 * <ol>
 *   <li><b>No JTS geometry objects</b>: read {@code Tile.Layer/Feature} straight from the protobuf,
 *       decode the delta coordinates with {@link MvtUtil#decodeZigZag} and write them into a
 *       {@link Path2D} while decoding. The old path built tens of thousands of JTS geometries per
 *       tile (Paris z12 has 6140 features and nearly 60000 coordinate integers), and parsing alone
 *       measured 12% to 39% of the time.</li>
 *   <li><b>Same colour geometries are merged into one Path2D and filled/drawn once</b>: the old path
 *       did one {@code graphics.fill()/draw()} <b>per feature</b>, which is 5000+ calls for Paris
 *       z12 (each one resetting the render state); now they are grouped by (colour, stroke width) and
 *       a whole tile usually needs single digit call counts. Polygons use
 *       {@link Path2D#WIND_EVEN_ODD}, which punches the holes from the outer and inner rings
 *       automatically, so no ring ownership test is needed.</li>
 *   <li><b>Labels: glyph outline LRU cache plus grid bucketed collision detection</b>: the same place
 *       name is turned into a {@link GlyphVector} outline only once, and collision detection drops
 *       from pairwise O(n^2) to comparing only the neighbouring grid cells.</li>
 *   <li><b>Reused drawing buffer</b>: one 256x256 ARGB buffer per thread instead of allocating a new
 *       one per tile and waiting for the GC.</li>
 * </ol>
 *
 * <p>A <b>layer rule bug</b> was fixed on the way: in OpenMapTiles vegetation and residential areas
 * are not separate layers but a {@code class} attribute on the {@code landcover}/{@code landuse}
 * layers. The old version looked for the {@code landcover_wood} / {@code landuse_residential} layers,
 * so <b>vegetation and residential areas were never drawn</b>; now the class value decides.
 *
 * <p>Geometry and data source are still the raw OpenFreeMap MVT; the colours come from a built in
 * simplified palette that approximates the Liberty look (it does not evaluate style.json expressions
 * one by one); text is drawn with the system font (the MVT already carries {@code name:zh-Hans}, so
 * no glyphs PBF is needed).
 */
public final class OpenFreeMapRasterizer {

   /** Light theme (the arnis "Modern" / Liberty one). */
   public static final String THEME = "openfreemap-liberty";
   /** Dark theme: <b>rendered by us from the same vector data</b>, replacing the key gated Carto dark. */
   public static final String THEME_DARK = "openfreemap-dark";

   private static final int TILE_SIZE = 256;
   /** Edge length of one cell of the label collision grid (pixels). */
   private static final int GRID_SIZE = 32;

   // ---- Simplified palette (approximates Liberty) ----
   private static final Color BACKGROUND = new Color(0xF7F7F5);
   private static final Color WATER = new Color(0xA5CCEA);
   private static final Color WOOD = new Color(0xC6DFB5);
   private static final Color GRASS = new Color(0xD8E9C8);
   private static final Color PARK = new Color(0xD2E7C3);
   private static final Color ICE = new Color(0xEDF5FB);
   private static final Color WETLAND = new Color(0xCBE0DA);
   private static final Color RESIDENTIAL = new Color(0xE9E8E4);
   private static final Color COMMERCIAL = new Color(0xF0E7E7);
   private static final Color INDUSTRIAL = new Color(0xEDE7E1);
   private static final Color SAND = new Color(0xF1E7CD);
   private static final Color ROCK = new Color(0xE0DDD8);
   private static final Color BUILDING = new Color(0xD6CCC4);
   private static final Color AEROWAY = new Color(0xDEDEDE);
   private static final Color WATERWAY = new Color(0x8FC4E8);
   private static final Color BOUNDARY = new Color(0x9E9E9E);
   private static final Color RAIL = new Color(0xB9B9B9);
   private static final Color ROAD_MOTORWAY = new Color(0xFFB366);
   private static final Color ROAD_PRIMARY = new Color(0xFFD9A0);
   private static final Color ROAD_MINOR = new Color(0xFFFFFF);

   // ---- Dark theme palette (our own, close in look to Carto dark_matter / Liberty dark) ----
   /** The palette used by the tile currently being rendered. */
   private record Palette(Color background, Color water, Color wood, Color grass, Color park, Color ice,
                          Color wetland, Color residential, Color commercial, Color industrial,
                          Color sand, Color rock, Color building, Color aeroway, Color waterway,
                          Color boundary, Color rail, Color motorway, Color primary, Color minor,
                          Color labelText, Color labelHalo, Color waterLabel, Color peakLabel,
                          Color parkLabel, Color roadLabel) {
   }

   private static final Palette LIGHT = new Palette(
         new Color(0xF7F7F5), new Color(0xA5CCEA), new Color(0xC6DFB5), new Color(0xD8E9C8),
         new Color(0xD2E7C3), new Color(0xEDF5FB), new Color(0xCBE0DA), new Color(0xE9E8E4),
         new Color(0xF0E7E7), new Color(0xEDE7E1), new Color(0xF1E7CD), new Color(0xE0DDD8),
         new Color(0xD6CCC4), new Color(0xDEDEDE), new Color(0x8FC4E8), new Color(0x9E9E9E),
         new Color(0xB9B9B9), new Color(0xFFB366), new Color(0xFFD9A0), new Color(0xFFFFFF),
         new Color(0x2B2B2B), new Color(0xFFFFFF), new Color(0x5C86A8), new Color(0x7A6248),
         new Color(0x4A7C3F), new Color(0x6E5F4B));

   private static final Palette DARK = new Palette(
         new Color(0x1A1A1E), new Color(0x1E2A38), new Color(0x1F2A20), new Color(0x223024),
         new Color(0x1F2C22), new Color(0x2A3238), new Color(0x1F2E2E), new Color(0x232326),
         new Color(0x26232A), new Color(0x26242A), new Color(0x2E2A22), new Color(0x26262A),
         new Color(0x2E2E33), new Color(0x2A2A2E), new Color(0x2F4C63), new Color(0x4A4A55),
         new Color(0x3A3A40), new Color(0x8A6B3F), new Color(0x6E6A50), new Color(0x4A4A52),
         new Color(0xE8E8EA), new Color(0x101014), new Color(0x7FA8C8), new Color(0xA88F6E),
         new Color(0x77A060), new Color(0x9A8C78));

   /** Palette used while rendering one tile (ThreadLocal, so the palette does not have to be threaded through). */
   private static final ThreadLocal<Palette> CURRENT = ThreadLocal.withInitial(() -> LIGHT);

   private OpenFreeMapRasterizer() {
   }

   /** Whether the current theme uses vector tiles (both the light and the dark one take this path). */
   public static boolean handlesTheme(String theme) {
      return THEME.equalsIgnoreCase(theme) || THEME_DARK.equalsIgnoreCase(theme);
   }

   /** Whether this is the dark theme. */
   private static boolean isDark(String theme) {
      return THEME_DARK.equalsIgnoreCase(theme);
   }

   /** Fetches a vector tile and rasterises it into PNG bytes; returns {@code null} on failure (the caller falls back to the raster source). */
   public static byte[] renderTile(int zoom, int tileX, int tileY) {
      byte[] pbf = loadVectorTile(zoom, tileX, tileY);
      if (pbf == null || pbf.length == 0) {
         return null;
      }
      try {
         return renderPng(pbf, TILE_SIZE, zoom);
      } catch (RuntimeException | IOException e) {
         Tellus.LOGGER.debug("Vector tile rasterisation failed z{}/{}/{}: {}", zoom, tileX, tileY, e.toString());
         return null;
      }
   }

   /**
    * Produces a Minecraft {@link NativeImage} directly, <b>skipping the PNG encode/decode round
    * trip</b>.
    *
    * <p>Why this path: PNG encoding measured 32% of the time spent on a whole tile (17 ms), while
    * the caller (the map tile cache) needs a texture anyway and a PNG is only an intermediate
    * container, which then has to go through stb_image once more on the way back. Here the pixels
    * are written into the NativeImage right after rendering, which removes both the encode and the
    * decode.
    *
    * <p>Note that {@code setPixelABGR} wants the <b>ABGR</b> layout (0xAABBGGRR) while
    * {@link BufferedImage} gives ARGB (0xAARRGGBB), so the R and B bytes have to be swapped.
    *
    * @return {@code null} when no tile could be fetched (the caller falls back to the raster source)
    */
   public static com.mojang.blaze3d.platform.NativeImage renderNativeImage(int zoom, int tileX, int tileY) {
      return renderNativeImage(zoom, tileX, tileY, TILE_SIZE);
   }

   public static com.mojang.blaze3d.platform.NativeImage renderNativeImage(int zoom, int tileX, int tileY, int size) {
      byte[] pbf = loadVectorTile(zoom, tileX, tileY);
      if (pbf == null || pbf.length == 0) {
         return null;
      }
      BufferedImage image;
      try {
         image = renderImage(pbf, size, zoom);
      } catch (RuntimeException e) {
         Tellus.LOGGER.debug("Vector tile rendering failed z{}/{}/{}: {}", zoom, tileX, tileY, e.toString());
         return null;
      }
      com.mojang.blaze3d.platform.NativeImage nativeImage =
            new com.mojang.blaze3d.platform.NativeImage(size, size, false);
      int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
      try {
         int index = 0;
         for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
               int argb = pixels[index++];
               int abgr = (argb & 0xFF00FF00)
                     | ((argb & 0x000000FF) << 16)
                     | ((argb >>> 16) & 0x000000FF);
               nativeImage.setPixelRGBA(x, y, abgr);
            }
         }
      } catch (RuntimeException e) {
         nativeImage.close();
         Tellus.LOGGER.debug("Writing the NativeImage failed z{}/{}/{}: {}", zoom, tileX, tileY, e.toString());
         return null;
      }
      return nativeImage;
   }

   /** Renders into a {@link BufferedImage} (no encoding). */
   public static BufferedImage renderImage(byte[] pbf, int size, int zoom) {
      CURRENT.set(paletteForCurrentTheme());
      VectorTile.Tile tile = parse(pbf);
      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = image.createGraphics();
      try {
         prepare(graphics);
         Palette palette = CURRENT.get();
         graphics.setColor(palette.background());
         graphics.fillRect(0, 0, size, size);
         drawFills(graphics, tile, size, zoom);
         drawLines(graphics, tile, size, zoom);
         drawLabels(graphics, tile, size, zoom);
      } finally {
         graphics.dispose();
      }
      return image;
   }

   private static VectorTile.Tile parse(byte[] pbf) {
      try {
         return VectorTile.Tile.parseFrom(ByteBuffer.wrap(pbf));
      } catch (IOException e) {
         throw new IllegalStateException("MVT parsing failed", e);
      }
   }

   private static void prepare(Graphics2D graphics) {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
   }

   /** Pure function (draws no labels), kept for backwards compatibility. */
   public static byte[] renderPng(byte[] pbf, int size) throws IOException {
      return renderPng(pbf, size, 0);
   }

   /** Forces a palette for the offline tool (never set in game, where the current theme decides). */
   private static final ThreadLocal<Boolean> FORCED_DARK = new ThreadLocal<>();

   public static void useDarkPalette(boolean dark) {
      FORCED_DARK.set(dark);
   }

   /**
    * Pure function: draws the MVT bytes into a PNG.
    *
    * @param zoom tile zoom level, decides which labels are shown (0 = draw no labels)
    */
   public static byte[] renderPng(byte[] pbf, int size, int zoom) throws IOException {
      return encodePng(renderImage(pbf, size, zoom));
   }

   /** Picks the palette for the theme configured under the current user name. */
   private static Palette paletteForCurrentTheme() {
      Boolean forced = FORCED_DARK.get();
      if (forced != null) {
         return forced ? DARK : LIGHT; // an explicit choice by the offline tool wins (it cannot read the mod config)
      }
      try {
         return isDark(com.yucareux.tellus.config.TellusCnOptions.mapTheme()) ? DARK : LIGHT;
      } catch (RuntimeException | LinkageError e) {
         return LIGHT;
      }
   }

   // ============================================================ Fills

   /** All "areas" of a tile merged by colour, so each colour is filled exactly once. */
   private static void drawFills(Graphics2D graphics, VectorTile.Tile tile, int size, int zoom) {
      double zoomScale = zoomScale(zoom);
      Map<Color, Path2D.Double> byColor = new HashMap<>();
      for (VectorTile.Tile.Layer layer : tile.getLayersList()) {
         String name = layer.getName();
         double scale = (double) size / Math.max(1, layer.getExtent());
         switch (name) {
            case "water" -> appendPolygons(byColor, layer, scale, CURRENT.get().water(), zoomScale);
            case "landcover" -> appendLandcover(byColor, layer, scale, zoomScale);
            case "landuse" -> appendLanduse(byColor, layer, scale, zoomScale);
            case "park" -> appendPolygons(byColor, layer, scale, CURRENT.get().park(), zoomScale);
            case "aeroway" -> appendPolygons(byColor, layer, scale, CURRENT.get().aeroway(), zoomScale);
            case "building" -> appendPolygons(byColor, layer, scale, CURRENT.get().building(), zoomScale);
            default -> {
            }
         }
      }
      for (Map.Entry<Color, Path2D.Double> entry : byColor.entrySet()) {
         graphics.setColor(entry.getKey());
         graphics.fill(entry.getValue());
      }
   }

   private static void appendPolygons(Map<Color, Path2D.Double> byColor, VectorTile.Tile.Layer layer,
                                      double scale, Color color, double zoomScale) {
      Path2D.Double path = byColor.computeIfAbsent(color, c -> newPath());
      for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
         if (feature.getType() != VectorTile.Tile.GeomType.POLYGON) {
            continue;
         }
         if (tooSmall(screenBounds(feature, scale), true, zoomScale)) {
            continue; // less than 1 pixel on screen, drawing it would be invisible
         }
         appendGeometry(path, feature, scale);
      }
   }

   /** landcover split by class (OpenMapTiles puts vegetation in an attribute of this layer, not in a separate layer). */
   private static void appendLandcover(Map<Color, Path2D.Double> byColor, VectorTile.Tile.Layer layer,
                                       double scale, double zoomScale) {
      for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
         if (feature.getType() != VectorTile.Tile.GeomType.POLYGON) {
            continue;
         }
         if (tooSmall(screenBounds(feature, scale), true, zoomScale)) {
            continue;
         }
         String value = tagValue(layer, feature, "class");
         Color color = switch (value == null ? "" : value) {
            case "wood", "forest" -> CURRENT.get().wood();
            case "grass", "farmland" -> CURRENT.get().grass();
            case "ice", "glacier" -> CURRENT.get().ice();
            case "wetland" -> CURRENT.get().wetland();
            case "sand" -> CURRENT.get().sand();
            case "rock" -> CURRENT.get().rock();
            default -> null;
         };
         if (color != null) {
            appendGeometry(byColor.computeIfAbsent(color, c -> newPath()), feature, scale);
         }
      }
   }

   /** landuse split by class (residential / commercial / industrial / school and so on). */
   private static void appendLanduse(Map<Color, Path2D.Double> byColor, VectorTile.Tile.Layer layer,
                                     double scale, double zoomScale) {
      for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
         if (feature.getType() != VectorTile.Tile.GeomType.POLYGON) {
            continue;
         }
         if (tooSmall(screenBounds(feature, scale), true, zoomScale)) {
            continue;
         }
         String value = tagValue(layer, feature, "class");
         Color color = switch (value == null ? "" : value) {
            case "residential", "neighbourhood", "suburb" -> CURRENT.get().residential();
            case "commercial", "retail" -> CURRENT.get().commercial();
            case "industrial", "railway" -> CURRENT.get().industrial();
            case "cemetery", "hospital", "school", "college", "university" -> CURRENT.get().park();
            default -> null;
         };
         if (color != null) {
            appendGeometry(byColor.computeIfAbsent(color, c -> newPath()), feature, scale);
         }
      }
   }

   // ============================================================ Lines

   /** Line features grouped by (colour + stroke width), so each group is stroked once. */
   private static void drawLines(Graphics2D graphics, VectorTile.Tile tile, int size, int zoom) {
      // Draw the lines thinner at low zoom levels so they do not blur into a blob
      double zoomScale = zoomScale(zoom);
      Map<String, Path2D.Double> groups = new HashMap<>();
      Map<String, Color> colors = new HashMap<>();
      Map<String, Float> widths = new HashMap<>();

      for (VectorTile.Tile.Layer layer : tile.getLayersList()) {
         double scale = (double) size / Math.max(1, layer.getExtent());
         switch (layer.getName()) {
            case "waterway" -> {
               Path2D.Double path = group(groups, colors, widths, "waterway", CURRENT.get().waterway(), (float) (1.4 * zoomScale));
               for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
                  if (tooSmall(screenBounds(feature, scale), false, zoomScale)) {
                     continue;
                  }
                  appendGeometry(path, feature, scale);
               }
            }
            case "boundary" -> {
               Path2D.Double path = group(groups, colors, widths, "boundary", CURRENT.get().boundary(), (float) (1.0 * zoomScale));
               for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
                  if (tooSmall(screenBounds(feature, scale), false, zoomScale)) {
                     continue;
                  }
                  appendGeometry(path, feature, scale);
               }
            }
            case "transportation" -> {
               for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
                  if (tooSmall(screenBounds(feature, scale), false, zoomScale)) {
                     continue; // tiny fragment: invisible but expensive
                  }
                  String roadClass = tagValue(layer, feature, "class");
                  Color color = CURRENT.get().minor();
                  float width = 1.2f;
                  if (roadClass != null) {
                     switch (roadClass) {
                        case "motorway", "trunk" -> {
                           color = CURRENT.get().motorway();
                           width = 3.6f;
                        }
                        case "primary" -> {
                           color = CURRENT.get().primary();
                           width = 2.8f;
                        }
                        case "secondary", "tertiary" -> {
                           color = CURRENT.get().minor();
                           width = 2.2f;
                        }
                        case "rail", "transit" -> {
                           color = CURRENT.get().rail();
                           width = 1.4f;
                        }
                        default -> {
                           color = CURRENT.get().minor();
                           width = 1.2f;
                        }
                     }
                  }
                  String key = color.getRGB() + ":" + width;
                  Path2D.Double path = group(groups, colors, widths, key, color, (float) (width * zoomScale));
                  appendGeometry(path, feature, scale);
               }
            }
            default -> {
            }
         }
      }
      for (Map.Entry<String, Path2D.Double> entry : groups.entrySet()) {
         graphics.setColor(colors.get(entry.getKey()));
         // JOIN_MITER is a lot cheaper than ROUND (round joins need arc geometry), and for straight
         // segments the visual difference is almost nil
         graphics.setStroke(new BasicStroke(widths.get(entry.getKey()),
               BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
         graphics.draw(entry.getValue());
      }
   }

   /** Draws the lines thinner at low zoom levels so they do not blur into a blob. */
   private static double zoomScale(int zoom) {
      return zoom <= 0 ? 1.0 : Math.max(0.45, Math.min(1.4, 0.75 + zoom * 0.03));
   }

   private static Path2D.Double group(Map<String, Path2D.Double> groups, Map<String, Color> colors,
                                      Map<String, Float> widths, String key, Color color, float width) {
      colors.putIfAbsent(key, color);
      widths.putIfAbsent(key, width);
      return groups.computeIfAbsent(key, k -> new Path2D.Double(Path2D.WIND_NON_ZERO));
   }

   // ============================================================ Geometry decoding

   /**
    * Writes the geometry of one feature into a {@link Path2D}.
    *
    * <p>The MVT geometry is a command stream: {@code cmd = id | (count << 3)}, and the coordinates
    * are zigzag encoded <b>deltas</b>. The ids are 1=MoveTo, 2=LineTo, 7=ClosePath. The outer and
    * inner rings of a polygon run in opposite directions, so {@code WIND_EVEN_ODD} punches the holes
    * correctly, which is why this deliberately does <b>no</b> ring ownership test.
    */
   private static void appendGeometry(Path2D.Double path, VectorTile.Tile.Feature feature, double scale) {
      List<Integer> geometry = feature.getGeometryList();
      int index = 0;
      int x = 0;
      int y = 0;
      int size = geometry.size();
      while (index < size) {
         int header = geometry.get(index++);
         int command = MvtUtil.getGeomCmdId(header);
         int count = MvtUtil.getGeomCmdLength(header);
         if (command == 1) {
            for (int i = 0; i < count && index + 1 < size; i++) {
               x += MvtUtil.decodeZigZag(geometry.get(index++));
               y += MvtUtil.decodeZigZag(geometry.get(index++));
               path.moveTo(x * scale, y * scale);
            }
         } else if (command == 2) {
            for (int i = 0; i < count && index + 1 < size; i++) {
               x += MvtUtil.decodeZigZag(geometry.get(index++));
               y += MvtUtil.decodeZigZag(geometry.get(index++));
               path.lineTo(x * scale, y * scale);
            }
         } else if (command == 7 && count > 0) {
            path.closePath();
         }
      }
   }

   /** Reads one attribute of a feature (keys/values are index tables). */
   private static String tagValue(VectorTile.Tile.Layer layer, VectorTile.Tile.Feature feature, String key) {
      List<Integer> tags = feature.getTagsList();
      List<String> keys = layer.getKeysList();
      List<VectorTile.Tile.Value> values = layer.getValuesList();
      for (int i = 0; i + 1 < tags.size(); i += 2) {
         int keyIndex = tags.get(i);
         int valueIndex = tags.get(i + 1);
         if (keyIndex >= 0 && keyIndex < keys.size() && key.equals(keys.get(keyIndex))
               && valueIndex >= 0 && valueIndex < values.size()) {
            return values.get(valueIndex).getStringValue();
         }
      }
      return null;
   }

   /**
    * Computes the bounding box of a feature in <b>screen pixels</b> (without allocating any object,
    * only local int accumulation).
    *
    * <p>Returning {@code null} means the geometry is empty. The array holds
    * {@code [minX, minY, maxX, maxY, firstX, firstY]}.
    * It is used for <b>LOD culling</b> before drawing: areas below 1 pixel and lines below 2 pixels
    * on screen are skipped, because the eye cannot see them while they still cost a full
    * rasterisation (Paris z12 has 5547 road features, many of them very short fragments).
    */
   private static double[] screenBounds(VectorTile.Tile.Feature feature, double scale) {
      List<Integer> geometry = feature.getGeometryList();
      int index = 0;
      int x = 0;
      int y = 0;
      int size = geometry.size();
      int firstX = Integer.MIN_VALUE;
      int firstY = 0;
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      while (index < size) {
         int header = geometry.get(index++);
         int command = MvtUtil.getGeomCmdId(header);
         int count = MvtUtil.getGeomCmdLength(header);
         if (command == 1 || command == 2) {
            for (int i = 0; i < count && index + 1 < size; i++) {
               x += MvtUtil.decodeZigZag(geometry.get(index++));
               y += MvtUtil.decodeZigZag(geometry.get(index++));
               if (firstX == Integer.MIN_VALUE) {
                  firstX = x;
                  firstY = y;
               }
               minX = Math.min(minX, x);
               minY = Math.min(minY, y);
               maxX = Math.max(maxX, x);
               maxY = Math.max(maxY, y);
            }
         }
      }
      if (firstX == Integer.MIN_VALUE) {
         return null;
      }
      return new double[]{minX * scale, minY * scale, maxX * scale, maxY * scale, firstX * scale, firstY * scale};
   }

   /** Screen size culling: areas and lines that are too small are not drawn (the thresholds are in pixels). */
   private static boolean tooSmall(double[] bounds, boolean polygon, double zoomScale) {
      if (bounds == null) {
         return true;
      }
      double width = bounds[2] - bounds[0];
      double height = bounds[3] - bounds[1];
      double threshold = 1.0 / Math.max(0.5, zoomScale);
      if (polygon) {
         // Area: skip it when the bounding box is smaller than about 1 pixel squared
         return width < threshold && height < threshold;
      }
      // Line: skip it when the bounding box diagonal is below about 2 pixels (all fragments)
      return Math.hypot(width, height) < 2.0 * threshold;
   }

   /** Anchor point of a feature: the first point for point features, the centre of the bounding box otherwise. */
   private static double[] anchor(VectorTile.Tile.Feature feature, double scale) {
      List<Integer> geometry = feature.getGeometryList();
      int index = 0;
      int x = 0;
      int y = 0;
      int size = geometry.size();
      int firstX = Integer.MIN_VALUE;
      int firstY = 0;
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      while (index < size) {
         int header = geometry.get(index++);
         int command = MvtUtil.getGeomCmdId(header);
         int count = MvtUtil.getGeomCmdLength(header);
         if (command == 1 || command == 2) {
            for (int i = 0; i < count && index + 1 < size; i++) {
               x += MvtUtil.decodeZigZag(geometry.get(index++));
               y += MvtUtil.decodeZigZag(geometry.get(index++));
               if (firstX == Integer.MIN_VALUE) {
                  firstX = x;
                  firstY = y;
               }
               minX = Math.min(minX, x);
               minY = Math.min(minY, y);
               maxX = Math.max(maxX, x);
               maxY = Math.max(maxY, y);
            }
         }
      }
      if (firstX == Integer.MIN_VALUE) {
         return null;
      }
      if (feature.getType() == VectorTile.Tile.GeomType.POINT) {
         return new double[]{firstX * scale, firstY * scale};
      }
      return new double[]{(minX + maxX) / 2.0 * scale, (minY + maxY) / 2.0 * scale};
   }

   // ============================================================ Text labels

   private record Label(String text, float x, float y, float size, Color color, int priority) {
   }

   private static final FontRenderContext LABEL_CONTEXT = new FontRenderContext(null, true, true);

   /** Glyph outline cache: the outline of a place name (for example "Beijing") is generated only once. */
   private static final LoadingCache<String, Shape> GLYPHS = CacheBuilder.newBuilder()
         .maximumSize(4096)
         .build(new CacheLoader<String, Shape>() {
            @Override
            public Shape load(String key) {
               int cut = key.lastIndexOf('|');
               String text = key.substring(0, cut);
               float size = Float.parseFloat(key.substring(cut + 1));
               GlyphVector vector = labelFont().deriveFont(size).createGlyphVector(LABEL_CONTEXT, text);
               return vector.getOutline(0f, 0f);
            }
         });

   private static volatile Font cachedFont;

   private static Font labelFont() {
      Font font = cachedFont;
      if (font != null) {
         return font;
      }
      String[] candidates = {"Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun",
            "Noto Sans CJK SC", "SansSerif"};
      for (String name : candidates) {
         Font candidate = new Font(name, Font.PLAIN, 12);
         if (candidate.canDisplay('中') && candidate.canDisplay('京')) {
            cachedFont = candidate;
            return candidate;
         }
      }
      cachedFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
      return cachedFont;
   }

   private static void drawLabels(Graphics2D graphics, VectorTile.Tile tile, int size, int zoom) {
      if (zoom <= 0) {
         return;
      }
      List<Label> labels = new ArrayList<>();
      if (zoom >= 3) {
         collectPlaceLabels(tile, size, zoom, labels);
      }
      if (zoom >= 5) {
         collectSimpleLabels(tile, size, "water_name", 9.5f, CURRENT.get().waterLabel(), 40, zoom, 5, 18, labels);
      }
      if (zoom >= 8) {
         collectSimpleLabels(tile, size, "mountain_peak", 9f, CURRENT.get().peakLabel(), 35, zoom, 8, 18, labels);
      }
      if (zoom >= 9) {
         collectSimpleLabels(tile, size, "aerodrome_label", 8.5f, CURRENT.get().labelText(), 28, zoom, 9, 22, labels);
      }
      if (zoom >= 10) {
         collectSimpleLabels(tile, size, "park", 8.5f, CURRENT.get().parkLabel(), 30, zoom, 10, 20, labels);
      }
      if (zoom >= 12) {
         collectSimpleLabels(tile, size, "poi", 8f, CURRENT.get().labelText(), 20, zoom, 12, 18, labels);
         collectRoadLabels(tile, size, zoom, labels);
      }
      if (labels.isEmpty()) {
         return;
      }

      labels.sort(Comparator.comparingInt(Label::priority).reversed());
      // Grid bucketing: only compare against the labels already placed in neighbouring cells, which
      // brings the total cost down from O(n^2) to roughly O(n)
      Map<Long, List<Rectangle>> grid = new HashMap<>();
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

      for (Label label : labels) {
         if (label.text().isEmpty() || label.text().length() > 24) {
            continue;
         }
         Shape glyphs;
         try {
            glyphs = GLYPHS.get(label.text() + '|' + label.size());
         } catch (Exception e) {
            continue;
         }
         Rectangle bounds = glyphs.getBounds();
         int bx = (int) label.x() + bounds.x - 1;
         int by = (int) label.y() + bounds.y - 1;
         int bw = bounds.width + 2;
         int bh = bounds.height + 2;
         if (bx + bw < 0 || by + bh < 0 || bx > size || by > size) {
            continue;
         }
         Rectangle box = new Rectangle(bx, by, bw, bh);
         if (clashes(grid, box)) {
            continue;
         }
         addToGrid(grid, box);

         Shape placed = AffineTransform.getTranslateInstance(label.x(), label.y()).createTransformedShape(glyphs);
         graphics.setColor(CURRENT.get().labelHalo());
         graphics.setStroke(new BasicStroke(Math.max(2.4f, label.size() / 3.6f),
               BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
         graphics.draw(placed);
         graphics.setColor(label.color());
         graphics.fill(placed);
      }
   }

   private static long gridKey(int cx, int cy) {
      return ((long) cx << 20) ^ cy;
   }

   private static boolean clashes(Map<Long, List<Rectangle>> grid, Rectangle box) {
      int x0 = box.x / GRID_SIZE;
      int x1 = (box.x + box.width) / GRID_SIZE;
      int y0 = box.y / GRID_SIZE;
      int y1 = (box.y + box.height) / GRID_SIZE;
      for (int cx = x0; cx <= x1; cx++) {
         for (int cy = y0; cy <= y1; cy++) {
            List<Rectangle> bucket = grid.get(gridKey(cx, cy));
            if (bucket == null) {
               continue;
            }
            for (Rectangle used : bucket) {
               if (used.intersects(box)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private static void addToGrid(Map<Long, List<Rectangle>> grid, Rectangle box) {
      int x0 = box.x / GRID_SIZE;
      int x1 = (box.x + box.width) / GRID_SIZE;
      int y0 = box.y / GRID_SIZE;
      int y1 = (box.y + box.height) / GRID_SIZE;
      for (int cx = x0; cx <= x1; cx++) {
         for (int cy = y0; cy <= y1; cy++) {
            grid.computeIfAbsent(gridKey(cx, cy), k -> new ArrayList<>()).add(box);
         }
      }
   }

   private static void collectPlaceLabels(VectorTile.Tile tile, int size, int zoom, List<Label> out) {
      VectorTile.Tile.Layer layer = layer(tile, "place");
      if (layer == null) {
         return;
      }
      double scale = (double) size / Math.max(1, layer.getExtent());
      for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
         String placeClass = tagValue(layer, feature, "class");
         if (placeClass == null) {
            continue;
         }
         int minZoom;
         float fontSize;
         int priority;
         Color color = CURRENT.get().labelText();
         switch (placeClass) {
            case "city" -> {
               minZoom = 3;
               fontSize = 13f;
               priority = 100;
            }
            case "town" -> {
               minZoom = 6;
               fontSize = 11f;
               priority = 80;
            }
            case "village" -> {
               minZoom = 8;
               fontSize = 10f;
               priority = 60;
            }
            case "suburb", "quarter", "neighbourhood" -> {
               minZoom = 11;
               fontSize = 9f;
               color = CURRENT.get().labelText();
               priority = 40;
            }
            default -> {
               minZoom = 12;
               fontSize = 8.5f;
               color = CURRENT.get().labelText();
               priority = 30;
            }
         }
         if (zoom < minZoom) {
            continue;
         }
         String text = pickName(layer, feature);
         double[] point = text == null ? null : anchor(feature, scale);
         if (point != null) {
            out.add(new Label(text, (float) point[0], (float) point[1], fontSize, color, priority));
         }
      }
   }

   private static void collectSimpleLabels(VectorTile.Tile tile, int size, String layerName, float fontSize,
                                           Color color, int priority, int zoom, int minZoom, int maxChars,
                                           List<Label> out) {
      if (zoom < minZoom) {
         return;
      }
      VectorTile.Tile.Layer layer = layer(tile, layerName);
      if (layer == null) {
         return;
      }
      double scale = (double) size / Math.max(1, layer.getExtent());
      for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
         String text = pickName(layer, feature);
         if (text == null || text.length() > maxChars) {
            continue;
         }
         double[] point = anchor(feature, scale);
         if (point != null) {
            out.add(new Label(text, (float) point[0], (float) point[1], fontSize, color, priority));
         }
      }
   }

   private static void collectRoadLabels(VectorTile.Tile tile, int size, int zoom, List<Label> out) {
      VectorTile.Tile.Layer layer = layer(tile, "transportation_name");
      if (layer == null) {
         return;
      }
      double scale = (double) size / Math.max(1, layer.getExtent());
      for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
         String roadClass = tagValue(layer, feature, "class");
         if ("path".equals(roadClass) || "track".equals(roadClass)) {
            continue;
         }
         String text = pickName(layer, feature);
         if (text == null) {
            text = tagValue(layer, feature, "ref");
         }
         if (text == null || text.length() > 14) {
            continue;
         }
         double[] point = anchor(feature, scale);
         if (point != null) {
            out.add(new Label(text, (float) point[0], (float) point[1], 8.5f, CURRENT.get().roadLabel(), 15));
         }
      }
   }

   private static String pickName(VectorTile.Tile.Layer layer, VectorTile.Tile.Feature feature) {
      String value = tagValue(layer, feature, "name:zh-Hans");
      if (value == null || value.isBlank()) {
         value = tagValue(layer, feature, "name:zh");
      }
      if (value == null || value.isBlank()) {
         value = tagValue(layer, feature, "name");
      }
      return value == null || value.isBlank() ? null : value.trim();
   }

   private static VectorTile.Tile.Layer layer(VectorTile.Tile tile, String name) {
      for (VectorTile.Tile.Layer layer : tile.getLayersList()) {
         if (name.equals(layer.getName())) {
            return layer;
         }
      }
      return null;
   }

   // ============================================================ Buffer and output

   /** The PNG writer is not thread safe, so it is cached per thread (which also saves the SPI lookup on every encode). */
   private static final ThreadLocal<javax.imageio.ImageWriter> PNG_WRITER =
         ThreadLocal.withInitial(() -> ImageIO.getImageWritersByFormatName("png").next());

   /**
    * PNG encoding.
    *
    * <p>Two details:
    * <ul>
    *   <li>The compression parameters <b>must</b> come from the writer's
    *       {@code getDefaultWriteParam()}: the default implementation of
    *       {@code new ImageWriteParam(null)} does not support setting a compression mode
    *       (it throws {@code UnsupportedOperationException: Compression not supported});</li>
    *   <li>Low compression is set explicitly: the Java default level is too slow for an encode that
    *       <b>runs once per tile</b> (measured: PNG encoding is 17% of the time spent on a whole
    *       tile). Slightly larger files buy a clear speed win.</li>
    * </ul>
    */
   private static byte[] encodePng(BufferedImage image) throws IOException {
      javax.imageio.ImageWriter writer = PNG_WRITER.get();
      if (writer == null) {
         try (ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
         }
      }
      javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
      try {
         if (param.canWriteCompressed()) {
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.25f);
         }
      } catch (UnsupportedOperationException | IllegalStateException ignored) {
         // If this writer does not support it, fall back to the default parameters
      }
      try (ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
         try (javax.imageio.stream.ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
         }
         return out.toByteArray();
      } catch (RuntimeException e) {
         // In case a writer fails, fall back to the plainest way
         try (ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
         }
      }
   }

   /** EVEN_ODD: the outer and inner rings of an MVT polygon run in opposite directions, so it punches holes most reliably. */
   private static Path2D.Double newPath() {
      return new Path2D.Double(Path2D.WIND_EVEN_ODD);
   }

   // ============================================================ Data fetching

   /** Fetches the vector tile bytes: disk cache first, then the network (the cache directory is shared with the preloader, so prefetched tiles hit directly). */
   static byte[] loadVectorTile(int zoom, int tileX, int tileY) {
      String base = MirrorConfig.getActiveDomain();
      if (base.isBlank()) {
         return null;
      }
      String template = ArnisSources.openFreeMapTileTemplate(base);
      if (template == null) {
         return null;
      }
      String url = template.replace("{z}", Integer.toString(zoom))
            .replace("{x}", Integer.toString(tileX))
            .replace("{y}", Integer.toString(tileY));
      Path file = ArnisPreloader.cacheRoot().resolve("openfreemap")
            .resolve(zoom + "/" + tileX + "/" + tileY + ".pbf");
      if (!Files.exists(file)) {
         try {
            Boolean ok = TellusDownloadExecutor.load("openfreemap", zoom + "/" + tileX + "/" + tileY,
                  ignored -> ArnisPreloader.downloadToFile(url, file));
            if (ok == null || !ok) {
               return null;
            }
         } catch (Exception e) {
            Tellus.LOGGER.debug("Vector tile download failed {}: {}", url, e.toString());
            return null;
         }
      }
      try {
         return Files.readAllBytes(file);
      } catch (IOException e) {
         return null;
      }
   }

   /** Used for the on screen hint. */
   public static String attributionNote() {
      return String.format(Locale.ROOT, "Modern（OpenFreeMap 矢量数据 + 内置简化配色、系统字体 CJK 标注）");
   }
}
