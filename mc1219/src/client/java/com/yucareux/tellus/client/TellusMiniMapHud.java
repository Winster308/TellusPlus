package com.yucareux.tellus.client;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.MirrorConfig;
import com.yucareux.tellus.config.TellusCnOptions;
import com.yucareux.tellus.network.TellusCoordsPayload;
import com.yucareux.tellus.network.TellusCoordsRequestPayload;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;
import com.yucareux.tellus.worldgen.EarthProjection;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>P key map</b>: shows the <b>8x8 chunks (128x128 blocks)</b> around the player.
 *
 * <h2>How it differs from the "text coordinate panel"</h2>
 * This shows a <b>real map</b> (assembled straight from map tile pixels), not a schematic drawn
 * from terrain data. It sits by default where the Xaero minimap is (top left corner), and the
 * coordinates plus the <b>latitude/longitude</b> are shown below the image.
 *
 * <h2>Why it does not stutter</h2>
 * <ul>
 *   <li>The map is rebuilt <b>only when P is pressed and the player crossed a chunk or more than
 *       2 seconds passed</b> (never per frame);</li>
 *   <li>Tiles are read from the <b>local cache</b> only (in memory LRU, then disk); on a miss the
 *       download is <b>queued asynchronously</b> and this pass uses the parent zoom instead
 *       - the render and generation threads never do network work, a rule bought with the earlier
 *       stutter incident;</li>
   *   <li>The texture is built at <b>physical pixels</b> (96 GUI units times the GUI scale), so one
   *       texel is about one physical pixel and is not blown up into a mosaic by nearest neighbour;
   *       the latitude/longitude and the Mercator projection are computed once per row and once per
   *       column (precomputed tables), so the inner loop only does a few array reads plus the 2x2
   *       area average.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class TellusMiniMapHud {

   /** Ground area covered by the map: 8x8 chunks = 128 blocks. */
   private static final int BLOCKS = 128;
   /** Edge length of the map on screen (GUI units). It used to be 128 and was reduced to 96 (-25%) so it blocks less of the view. */
   private static final int GUI_SIZE = 96;
   /** Upper bound on the texture edge length (keeps memory sane at very large GUI scales). */
   private static final int MAX_TEXELS = 512;
   /** The endpoint was measured to support up to z=19 (all levels exist in the cache); clamping it hard to 12 wasted detail for nothing. */
   private static final int MAX_TILE_ZOOM = 19;
   /** Tile pixel size (fixed at 256 in the OSM scheme). */
   private static final int TILE_PX = 256;
   /** How many download requests one rebuild may queue at most (do not flood the download queue in one go). */
   private static final int MAX_PREFETCH_PER_REBUILD = 96;
   /**
    * <b>Minimum interval between two rebuilds</b> (a hard throttle).
    * One rebuild equals 384x384 texels with 2x supersampling, about 590000 pixel operations, plus
    * tile decoding and compositing, plus up to 96 tile requests. Rebuilding on "crossed one chunk
    * (16 blocks)" would stutter every 1 to 2 seconds while walking.
    */
   private static final long MIN_REBUILD_INTERVAL_MS = 1200L;
   /** The player has to walk at least this many chunks (Manhattan distance) before a "movement" rebuild happens. */
   private static final int MIN_REBUILD_CHUNK_STEP = 2;
   /** Retry interval while the high detail tiles are not ready (the previous 1.5 s was too dense). */
   private static final long TILES_RETRY_MS = 3000L;
   private static boolean rebuildForced;
   /** Base colour used when no tile can be fetched. */
   private static final int EMPTY_PIXEL = 0x223040;
   /** Position of the map frame on screen (covers the default position of the Xaero minimap). */
   private static final int MARGIN_X = 4;
   private static final int MARGIN_Y = 4;
   private static final int INFO_LINES = 3;
   private static final ResourceLocation TEXTURE = Tellus.id("minimap");
   private static final org.slf4j.Logger LOGGER = Tellus.LOGGER;

   private static KeyMapping toggleKey;
   private static boolean visible;

   private static TellusCoordsPayload coords;
   private static NativeImage image;
   private static DynamicTexture texture;
   private static long lastBuildAt;
   private static int lastChunkX = Integer.MIN_VALUE;
   private static int lastChunkZ = Integer.MIN_VALUE;

   private TellusMiniMapHud() {
   }

   /**
    * Own Controls category, so Tellus Plus' bindings are grouped under "Tellus Plus" instead of being
    * buried inside vanilla "Gameplay".
    *
    * <p>1.21.9 turned {@code KeyMapping.Category} from a string into a record holding a
    * {@link net.minecraft.resources.ResourceLocation}, and exposes
    * {@code Category.register(ResourceLocation)} for mods. The label is derived from the id, so the
    * translation keys must be exactly {@code key.categories.<namespace>.<path>} — hence the
    * {@code key.categories.tellusplus.main} entry in both lang files.
    */
   private static final KeyMapping.Category TELLUSPLUS_CATEGORY = KeyMapping.Category.register(
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tellusplus", "main"));

   public static void init() {
      if (toggleKey == null) {
         // 2026-10-10: renamed key.telluscn.* -> key.tellusplus.* together with the mod rename, and the
         // category switched from vanilla GAMEPLAY to our own so the binding is grouped under "Tellus Plus"
         // in Controls (a mod-specific key sitting under "Gameplay" was hard to find). The translation for
         // both keys lives in lang/en_us.json + lang/zh_cn.json, so the label follows the game language.
         toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
               "key.tellusplus.where_am_i", GLFW.GLFW_KEY_P, TELLUSPLUS_CATEGORY));
      }
      HudRenderCallback.EVENT.register((graphics, delta) -> render(graphics));
   }

   /** Runs every client tick: handles the P key. */
   public static void tick(Minecraft minecraft) {
      if (toggleKey == null || !toggleKey.consumeClick()) {
         return;
      }
      Minecraft mc = minecraft == null ? Minecraft.getInstance() : minecraft;
      if (!canAskServer(mc)) {
         if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("tellus.coords.need_world"), true);
         }
         return;
      }
      if (visible) {
         visible = false;
         return;
      }
      // Ask once for the coordinates and the scale (the server computes it, the client has no generator)
      ClientPlayNetworking.send(new TellusCoordsRequestPayload());
   }

   /** Called when the server answer arrives. */
   public static void accept(TellusCoordsPayload payload) {
      coords = payload;
      visible = payload.inTellusWorld();
      forceRebuild();
      if (visible) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null) {
            // 2026-10-10: translatable, with the two numbers passed as arguments so the translated string
            // controls the ordering and punctuation (the old version hard-coded "lat, lon  （已复制）").
            mc.player.displayClientMessage(Component.translatable("tellusplus.hud.where_am_i.copied",
                  String.format(Locale.ROOT, "%.6f", payload.latitude()),
                  String.format(Locale.ROOT, "%.6f", payload.longitude())), true);
            mc.keyboardHandler.setClipboard(String.format(Locale.ROOT, "%.6f %.6f",
                  payload.latitude(), payload.longitude()));
         }
      }
   }

   private static boolean canAskServer(Minecraft mc) {
      return mc != null && mc.level != null && mc.player != null
            && ClientPlayNetworking.canSend(TellusCoordsRequestPayload.TYPE);
   }

   public static void forceRebuild() {
      rebuildForced = true;   // MIN_VALUE is no longer used as a sentinel (it overflows once it is summed with the movement distance)
   }

   // ------------------------------------------------------------ HUD

   private static void render(GuiGraphics graphics) {
      Minecraft mc = Minecraft.getInstance();
      if (!visible || coords == null || mc.player == null || mc.level == null || mc.options.hideGui) {
         return;
      }
      // The map (rebuilt on demand)
      int cx = mc.player.getBlockX();
      int cz = mc.player.getBlockZ();
      int chunkX = cx >> 4;
      int chunkZ = cz >> 4;
      long now = System.currentTimeMillis();
      int texels = targetTexels();
      // When to rebuild (changed in the third self check round):
      //   (1) the texture size changed (must happen immediately);
      //   (2) after the 1.2 s cooldown and (walked at least 2 chunks or the high detail tiles
      //       arrived and 3 s passed).
      // The old "rebuild after crossing one chunk" caused a full rasterisation every 1 to 2
      // seconds while walking, which was the source of the stutter.
      boolean sizeChanged = image == null || image.getWidth() != texels;
      int movedChunks = Math.abs(chunkX - lastChunkX) + Math.abs(chunkZ - lastChunkZ);
      boolean movedEnough = rebuildForced || lastChunkX == Integer.MIN_VALUE || movedChunks >= MIN_REBUILD_CHUNK_STEP;
      boolean tilesReady = lastPaintedRatio < 0.6 && now - lastBuildAt > TILES_RETRY_MS;
      boolean cooledDown = now - lastBuildAt >= MIN_REBUILD_INTERVAL_MS;
      if (sizeChanged || (cooledDown && (movedEnough || tilesReady))) {
         buildMap(cx, cz, texels);
         lastChunkX = chunkX;
         lastChunkZ = chunkZ;
         lastBuildAt = now;
         rebuildForced = false;
      }

      int px = MARGIN_X;
      int py = MARGIN_Y;
      int w = GUI_SIZE;
      int h = GUI_SIZE;

      // Base and border
      graphics.fill(px - 2, py - 2, px + w + 2, py + h + 2, 0xCC101418);
      graphics.fill(px - 2, py - 2, px + w + 2, py - 1, 0xFF3C6E9C);

      if (image != null && texture != null) {
         // The 1.21.9 signature: (texture, x, y, width, height, u0, u1, v0, v1), where the last four are texture coordinates from 0 to 1
         graphics.blit(TEXTURE, px, py, w, h, 0.0F, 1.0F, 0.0F, 1.0F);
      }
      // Centre crosshair (player position)
      int mid = px + w / 2;
      int midY = py + h / 2;
      graphics.fill(mid - 4, midY, mid + 5, midY + 1, 0xFFFF4D4D);
      graphics.fill(mid, midY - 4, mid + 1, midY + 5, 0xFFFF4D4D);

      // Info below the frame: coordinates plus latitude/longitude
      int lineY = py + h + 4;
      String coordText = String.format(Locale.ROOT, "XYZ  %d  %d  %d", cx, mc.player.getBlockY(), cz);
      graphics.drawString(mc.font, coordText, px, lineY, 0xFFFFFFE0, true);
      // 2026-10-10: these three lines used to be String.format with Chinese labels baked in, so they stayed
      // Chinese under an English client. They are translatable now; the numbers are passed as arguments so
      // each language controls its own wording and spacing.
      String ll = Component.translatable("tellusplus.hud.where_am_i.latlon",
            String.format(Locale.ROOT, "%.5f", coords.latitude()),
            String.format(Locale.ROOT, "%.5f", coords.longitude())).getString();
      graphics.drawString(mc.font, ll, px, lineY + 10, 0xFFBFE3FF, true);
      String scaleText = Component.translatable("tellusplus.hud.where_am_i.scale",
            String.format(Locale.ROOT, "%.3g", coords.worldScale()),
            String.format(Locale.ROOT, "%.0f", coords.elevationMeters()),
            lastZoomUsed).getString();
      graphics.drawString(mc.font, scaleText, px, lineY + 20, 0xFF9FB6C8, true);
      if (lastPaintedRatio < 0.6) {
         graphics.drawString(mc.font,
               Component.translatable("tellusplus.hud.tiles_loading").getString(),
               px, lineY + 30, 0xFFFFD479, true);
      }
   }

   // ------------------------------------------------------------ Mosaicking

   /**
    * Assembles the map <b>centred on the player and covering 8x8 chunks (128 blocks)</b>.
    *
    * <h2>Sharpness comes from these two rules (the core change of this round)</h2>
    * <ol>
    *   <li><b>The texture is built at physical pixels</b>: screen edge length 96 GUI units times
    *       the GUI scale (4 with the automatic setting on 1080p) = 384 texels, so one texel is
    *       about one physical pixel and is not blown up into a mosaic by nearest neighbour.
    *       Previously a fixed 128 texels was scaled 4x onto 512 physical pixels, which is the root
    *       cause of "big and blurry".</li>
    *   <li><b>The tile zoom level is derived from the texel resolution</b>: the endpoint was
    *       measured to serve z=19, while it used to be clamped to 12 (about 33 m per pixel). Now the
    *       metres per texel are computed, one finer level is picked for a 2x supersample, and the
    *       result is downsampled with an <b>area average</b>, which keeps the lines clean and free
    *       of moire patterns.</li>
    * </ol>
    *
    * <p>Performance: the latitude/longitude and the Mercator projection are computed <b>once per
    * row and once per column</b> (precomputed tables), so the inner loop only does 4 int[] reads
    * plus the 2x2 area average; a decoded tile enters the in memory LRU once. Tiles are
    * <b>fetched from the cache only, with asynchronous queueing</b>; the render thread never does
    * network work, a rule bought with the earlier stutter incident.
    */
   private static void buildMap(int centerBlockX, int centerBlockZ, int texels) {
      double scale = coords.worldScale() <= 0 ? 30.0 : coords.worldScale();
      double blocksPerDegree = Math.max(1.0, EarthProjection.blocksPerDegree(scale));
      String theme = TellusCnOptions.mapTheme();

      NativeImage target = image;
      if (target == null || target.getWidth() != texels) {
         NativeImage fresh = new NativeImage(texels, texels, false);
         TextureManager textures = Minecraft.getInstance().getTextureManager();
         if (texture != null) {
            textures.release(TEXTURE);          // GUI scale changed, so swap in a texture with the right size
         }
         image = fresh;
         texture = new DynamicTexture(() -> "tellus-minimap", fresh);
         texture.setFilter(true, false);          // even if the scale is not an integer multiple it only gets softer, never blocky
         textures.register(TEXTURE, texture);
         target = fresh;
      }

      double blocksPerTexel = BLOCKS / (double) texels;
      double metersPerTexel = blocksPerTexel * scale;
      int z = pickZoom(coords.latitude(), metersPerTexel);
      lastZoomUsed = z;

      // ---- Prefetch 4 times the area (128 blocks are shown, 256 blocks square are prefetched), with a
      // step of half a tile so that no block can be missed.
      int tileBlocks = tileBlocks(z, coords.latitude(), scale);
      int step = Math.max(8, tileBlocks / 2);
      Set<Long> needed = new LinkedHashSet<>();
      for (int py = -BLOCKS - step; py <= BLOCKS + step; py += step) {
         for (int px = -BLOCKS - step; px <= BLOCKS + step; px += step) {
            double latitude = EarthProjection.blockZToLat(centerBlockZ + py, scale);
            double longitude = (centerBlockX + px) / blocksPerDegree;
            int[] tile = tileOf(z, latitude, longitude);
            if (tile != null) {
               needed.add(((long) tile[0] << 40) | ((long) tile[1] << 20) | tile[2]);
            }
         }
      }
      int hits = 0;
      int requests = 0;
      for (long key : needed) {
         int tz = (int) (key >>> 40);
         int tx = (int) ((key >>> 20) & 0xFFFFFL);
         int ty = (int) (key & 0xFFFFFL);
         if (known(theme, tz, tx, ty)) {
            hits++;
         } else if (requests < MAX_PREFETCH_PER_REBUILD) {
            requests++;
            request(theme, tz, tx, ty);
         }
      }
      if (needed.size() != lastNeeded || hits != lastHits) {
         lastNeeded = needed.size();
         lastHits = hits;
         LOGGER.debug("[Minimap tiles] z={} each covers {} blocks, needed {}, ready {}, newly queued {}",
               z, tileBlocks, needed.size(), hits, requests);
      }

      // ---- Row and column precomputation: the latitude only depends on the row, the longitude and the
      // pixel position only depend on the column
      int n = 1 << z;
      double half = blocksPerTexel / 2.0;
      double[] rowPy = new double[texels];
      double[] rowDy = new double[texels];
      for (int py = 0; py < texels; py++) {
         double bz = centerBlockZ + (py + 0.5 - texels / 2.0) * blocksPerTexel;
         double latitude = EarthProjection.blockZToLat(bz, scale);
         double latSpan = Math.abs(EarthProjection.blockZToLat(bz + half, scale)
               - EarthProjection.blockZToLat(bz - half, scale));
         rowPy[py] = mercatorY(latitude) * n * TILE_PX;
         rowDy[py] = Math.max(1.0, Math.abs(mercatorY(latitude + latSpan / 2.0)
               - mercatorY(latitude - latSpan / 2.0)) * n * TILE_PX);
      }
      double lonSpan = 2.0 * half / blocksPerDegree;
      double[] colPx = new double[texels];
      double[] colDx = new double[texels];
      for (int px = 0; px < texels; px++) {
         double bx = centerBlockX + (px + 0.5 - texels / 2.0) * blocksPerTexel;
         double longitude = bx / blocksPerDegree;
         colPx[px] = (longitude + 180.0) / 360.0 * n * TILE_PX;
         colDx[px] = Math.max(1.0, lonSpan / 360.0 * n * TILE_PX);
      }

      // ---- Per texel sampling (the inner loop only reads int[] plus the area average)
      int painted = 0;
      for (int py = 0; py < texels; py++) {
         double pyF = rowPy[py];
         int ty = (int) Math.floor(pyF / TILE_PX);
         if (ty < 0 || ty >= n) {
            for (int px = 0; px < texels; px++) {
               target.setPixelABGR(px, py, pack(EMPTY_PIXEL));
            }
            continue;
         }
         for (int px = 0; px < texels; px++) {
            double pxF = colPx[px];
            int tx = (int) Math.floor(pxF / TILE_PX);
            int rgb = tx < 0 || tx >= n ? -1
                  : area(theme, z, tx, ty, pxF - tx * TILE_PX, pyF - ty * TILE_PX,
                        colDx[px], rowDy[py]);
            if (rgb < 0) {
               rgb = EMPTY_PIXEL;
            } else {
               painted++;
            }
            target.setPixelABGR(px, py, pack(rgb));
         }
      }
      texture.upload();
      lastPaintedRatio = painted / (double) (texels * texels);
   }

   /** Packs RGB(0xRRGGBB) into the ABGR layout that NativeImage.setPixelABGR expects. */
   private static int pack(int rgb) {
      return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
   }

   /**
    * Texture edge length = screen edge length (GUI units) times the GUI scale, capped at
    * {@link #MAX_TEXELS}. That way one texel is about one physical pixel and is not blown up into
    * a mosaic by nearest neighbour.
    */
   private static int targetTexels() {
      int gui = 1;
      try {
         gui = Math.max(1, Math.min(4, Minecraft.getInstance().getWindow().getGuiScale()));
      } catch (Throwable ignored) {
         // The window is not ready yet, so use a scale of 1
      }
      return Math.min(MAX_TEXELS, GUI_SIZE * gui);
   }

   /**
    * Picks the tile zoom level: the <b>source tile is one level finer than a texel</b> (about 2x
    * supersampling), so the lines stay clean after downsampling. This is one of the sources of
    * sharpness - previously z was fixed at about 12 (about 33 m per pixel), which wasted the detail
    * the endpoint offers up to z=19.
    */
   private static int pickZoom(double latitude, double metersPerTexel) {
      double cos = Math.max(0.05, Math.cos(Math.toRadians(clampLatitude(latitude))));
      double metersPerTilePixelAtZero = 40075016.686 * cos / TILE_PX;
      double want = metersPerTilePixelAtZero / Math.max(0.5, metersPerTexel / 2.0);
      int zoom = (int) Math.ceil(Math.log(Math.max(1.0, want)) / Math.log(2.0));
      return Math.max(0, Math.min(MAX_TILE_ZOOM, zoom));
   }

   /** How many blocks one tile covers at this latitude and this scale (used for the prefetch step). */
   private static int tileBlocks(int z, double latitude, double scale) {
      double cos = Math.max(0.05, Math.cos(Math.toRadians(clampLatitude(latitude))));
      double meters = 40075016.686 * cos / (double) (1 << z);
      return Math.max(8, (int) Math.round(meters / Math.max(1.0, scale)));
   }

   private static double clampLatitude(double latitude) {
      return Math.max(-85.0, Math.min(85.0, latitude));
   }

   /** Mercator Y in tile pixel space (from 0 to 1). */
   private static double mercatorY(double latitude) {
      double lat = Math.max(-EarthProjection.MAX_MERCATOR_LATITUDE,
            Math.min(EarthProjection.MAX_MERCATOR_LATITUDE, latitude));
      double rad = Math.toRadians(lat);
      return (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0;
   }

   /** Latitude/longitude to tile coordinates [z, x, y]; returns null when out of range. */
   private static int[] tileOf(int z, double latitude, double longitude) {
      int n = 1 << z;
      int tx = (int) Math.floor((longitude + 180.0) / 360.0 * n);
      int ty = (int) Math.floor(mercatorY(latitude) * n);
      if (tx < 0 || ty < 0 || tx >= n || ty >= n) {
         return null;
      }
      return new int[]{z, tx, ty};
   }

   /**
    * <b>Area average</b> inside the tile: averages the pixel rectangle this texel covers (at most
    * 3x3 sample points, usually 2x2). If the tile of the target level is not downloaded yet it
    * automatically <b>falls back to the parent zoom</b>, so the map never suddenly turns black.
    */
   private static int area(String theme, int z, int tx, int ty, double fx, double fy, double dx, double dy) {
      Tile tile = cached(theme, z, tx, ty);
      if (tile == null) {
         return coarse(theme, z, tx, ty, fx, fy);
      }
      int nx = Math.max(1, Math.min(3, (int) Math.round(dx)));
      int ny = Math.max(1, Math.min(3, (int) Math.round(dy)));
      long r = 0L;
      long g = 0L;
      long b = 0L;
      int count = 0;
      for (int iy = 0; iy < ny; iy++) {
         int y = (int) Math.floor(fy - dy / 2.0 + (iy + 0.5) * dy / ny);
         y = Math.max(0, Math.min(tile.size - 1, y));
         int base = y * tile.size;
         for (int ix = 0; ix < nx; ix++) {
            int x = (int) Math.floor(fx - dx / 2.0 + (ix + 0.5) * dx / nx);
            x = Math.max(0, Math.min(tile.size - 1, x));
            int rgb = tile.pixels[base + x];
            r += (rgb >> 16) & 0xFF;
            g += (rgb >> 8) & 0xFF;
            b += rgb & 0xFF;
            count++;
         }
      }
      if (count == 0) {
         return -1;
      }
      return ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
   }

   /**
    * While the tile of the target level is not there yet, stand in with the <b>parent zoom that is
    * already available</b> (nearest neighbour sample). Tile subdivision is a quadtree, so the
    * pixel (fx, fy) of (z, X, Y) is exactly (fx/2, fy/2) of (X>>1, Y>>1) at level z-1; the
    * conversion is exact and never misaligned.
    */
   private static int coarse(String theme, int z, int tx, int ty, double fx, double fy) {
      for (int k = 1; k <= 4; k++) {
         int cz = z - k;
         if (cz < 0) {
            break;
         }
         Tile tile = cached(theme, cz, tx >> k, ty >> k);
         if (tile != null) {
            int x = Math.max(0, Math.min(tile.size - 1, (int) (fx / (1 << k))));
            int y = Math.max(0, Math.min(tile.size - 1, (int) (fy / (1 << k))));
            return tile.pixels[y * tile.size + x];
         }
      }
      return -1;
   }

   /** A decoded tile: only the pixel array is kept, so sampling never has to go through BufferedImage again. */
   private static final class Tile {
      final int size;
      final int[] pixels;

      Tile(BufferedImage image) {
         this.size = image.getWidth();
         this.pixels = new int[this.size * image.getHeight()];
         image.getRGB(0, 0, this.size, image.getHeight(), this.pixels, 0, this.size);
      }
   }

   /** In memory LRU of decoded tiles (one mosaic needs a dozen, so 64 is plenty). */
   private static final Map<String, Tile> TILE_CACHE = new LinkedHashMap<>(256, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Tile> eldest) {
         return size() > 64;
      }
   };

   /** Location of the tile file on disk (the same cache directory the map screens use). */
   private static Path cacheFile(String theme, int z, int x, int y) {
      return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("tellus/cache/map").resolve(theme).resolve(z + "_" + x + "_" + y + ".png");
   }

   /** Whether it is in memory or on disk (<b>no decoding, no download</b>, the check only). */
   private static boolean known(String theme, int z, int x, int y) {
      return TILE_CACHE.containsKey(theme + "/" + z + "/" + x + "/" + y)
            || Files.exists(cacheFile(theme, z, x, y));
   }

   /** Returns the decoded tile: memory first, then disk; <b>never uses the network</b>. */
   private static Tile cached(String theme, int z, int x, int y) {
      String key = theme + "/" + z + "/" + x + "/" + y;
      Tile hit = TILE_CACHE.get(key);
      if (hit != null) {
         return hit;
      }
      Path file = cacheFile(theme, z, x, y);
      if (!Files.exists(file)) {
         return null;
      }
      try {
         BufferedImage image = ImageIO.read(file.toFile());
         if (image == null) {
            return null;
         }
         Tile tile = new Tile(image);
         TILE_CACHE.put(key, tile);
         return tile;
      } catch (IOException e) {
         LOGGER.debug("Minimap tile decode failed {}: {}", file, e.toString());
         return null;
      }
   }

   /**
    * Queues the download asynchronously (<b>never waits and never blocks the render thread</b>) and
    * stores it in the <b>same cache directory the map screens use</b>. The URL goes through the same
    * endpoint configuration ({@code tellus.map.tiles.endpoint}), so both sides fetch the very same
    * data; when the endpoint is empty it falls back to the official OSM source (same as the map
    * screens).
    */
   private static void request(String theme, int z, int x, int y) {
      Path file = cacheFile(theme, z, x, y);
      String endpoint = com.yucareux.tellus.config.TellusEndpointConfig.getMapTilesEndpoint("");
      String url = endpoint.isBlank()
            ? String.format(Locale.ROOT, "https://tile.openstreetmap.org/%d/%d/%d.png", z, x, y)
            : String.format(Locale.ROOT, "%s/%d/%d/%d.png", endpoint, z, x, y);
      TellusDownloadExecutor.prefetch("minimap", theme + "/" + z + "/" + x + "/" + y, ignored -> {
         try {
            Files.createDirectories(file.getParent());
         } catch (IOException e) {
            return Boolean.FALSE;
         }
         return com.yucareux.tellus.preload.ArnisPreloader.downloadToFile(url, file);
      });
   }

   /** Fraction of texels that hit a tile of the <b>high detail level</b> in the last mosaic (used for the "still downloading" hint). */
   private static volatile double lastPaintedRatio = 1.0;
   /** The tile zoom level actually used last time (shown on the info line, which makes verification easy). */
   private static int lastZoomUsed = -1;
   /** Tile statistics of the previous round (logged only on change, to avoid flooding the log). */
   private static int lastNeeded = -1;
   private static int lastHits = -1;
}
