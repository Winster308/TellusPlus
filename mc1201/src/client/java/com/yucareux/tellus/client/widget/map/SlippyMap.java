package com.yucareux.tellus.client.widget.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

@Environment(EnvType.CLIENT)
public class SlippyMap {
   public static final int TILE_SIZE = 256;
   public static final int MIN_ZOOM = 3;
   public static final int MAX_ZOOM = 19;
   private final int width;
   private final int height;
   private final SlippyMap.Camera camera;
   private final SlippyMapTileCache cache = new SlippyMapTileCache();
   /**
    * Reusable output buffers for {@link #getVisibleTiles()} and {@link #cascadeTiles(List)}.
    *
    * <p>2026-10-05 performance fix: both used to hand out a <b>fresh {@link ArrayList} every
    * frame</b> (the map screen redraws every frame and there are typically 20 to 100 visible
    * tiles), so both lists were garbage immediately after being drawn. They are now allocated once
    * and only cleared, so the per frame list allocation drops from 2 to 0.
    *
    * <p>The contract is unchanged for the current callers (<b>the returned list is only valid until
    * the next call to the same method</b>): {@link SlippyMapWidget} consumes it before drawing
    * anything else and {@code PreloadOverlayMapComponent#redrawTiles} iterates it without keeping
    * it.
    *
    * <p>The {@link SlippyMapTilePos} instances themselves are deliberately <b>still allocated fresh
    * every time</b>: a position is retained by {@code SlippyMapTileCache.TileKey} (it is the
    * Guava cache key) and by the {@link SlippyMapTile} built from it (it names the GPU texture),
    * so reusing one mutable instance would alias unrelated keys.
    */
   private final List<SlippyMapTilePos> visibleTileBuffer = new ArrayList<>();
   private final List<SlippyMap.DrawTile> drawTileBuffer = new ArrayList<>();
   /**
    * Whether {@link #focus} has already been called.
    *
    * <p>Performance fix (2026-09-20): the camera starts at {@code (0.0, 0.0)}, that is longitude
    * 0 / latitude 0, so during the window where tiles are fetched before the map was focused it
    * fired a batch of requests for the <b>3x3 tiles around the world origin</b>
    * (observed in the log: {@code zoom=15, x=16382~16385, y=16382~16385}). Those tiles come back
    * immediately (empty ocean tiles), but they waste bandwidth and occupy the download pool.
    * An unfocused map returns an empty list instead - normal screens call focus in their
    * constructor, so only the "not initialised yet" transient ever reaches this branch.
    */
   private boolean focused;

   public SlippyMap(int width, int height) {
      this.width = width;
      this.height = height;
      int scale = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
      this.camera = new SlippyMap.Camera(new SlippyMapPoint(0.0, 0.0), width * scale, height * scale);
   }

   public SlippyMapTile getTile(SlippyMapTilePos pos) {
      return this.cache.getTile(pos);
   }

   public void focus(double latitude, double longitude, int zoom) {
      int scale = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
      SlippyMapPoint point = new SlippyMapPoint(latitude, longitude);
      this.camera.focus(point.getX(zoom), point.getY(zoom), zoom, this.width * scale, this.height * scale);
      this.focused = true;
   }

   public void zoom(int step, int pivotX, int pivotY) {
      int scale = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
      this.camera.zoom(step, pivotX * scale, pivotY * scale);
   }

   public void drag(int deltaX, int deltaY) {
      int scale = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
      this.camera.pan(deltaX * scale, deltaY * scale);
   }

   public List<SlippyMapTilePos> getVisibleTiles() {
      if (!this.focused) {
         return List.of();   // not focused yet: do not ask for tiles at the default (0, 0)
      }
      int scale = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
      int cameraX = this.camera.getX();
      int cameraY = this.camera.getY();
      int cameraZoom = this.camera.getZoom();
      int minX = Mth.floor(cameraX / 256.0);
      int minY = Mth.floor(cameraY / 256.0);
      int maxX = Mth.ceil((cameraX + this.width * scale) / 256.0);
      int maxY = Mth.ceil((cameraY + this.height * scale) / 256.0);
      List<SlippyMapTilePos> visibleTiles = this.visibleTileBuffer;
      // 2026-10-05 performance fix: reused buffer instead of a new ArrayList every frame.
      visibleTiles.clear();

      for (int tileY = minY; tileY < maxY; tileY++) {
         for (int tileX = minX; tileX < maxX; tileX++) {
            visibleTiles.add(new SlippyMapTilePos(tileX, tileY, cameraZoom));
         }
      }

      return visibleTiles;
   }

   public List<SlippyMap.DrawTile> cascadeTiles(List<SlippyMapTilePos> tiles) {
      List<SlippyMap.DrawTile> cascaded = this.drawTileBuffer;
      // 2026-10-05 performance fix: reused buffer instead of a new ArrayList every frame.
      cascaded.clear();

      for (SlippyMapTilePos pos : tiles) {
         this.cascadeTile(cascaded, pos);
      }

      return cascaded;
   }

   private void cascadeTile(List<SlippyMap.DrawTile> tiles, SlippyMapTilePos pos) {
      int size = 1 << pos.getZoom();
      if (pos.getX() >= 0 && pos.getY() >= 0 && pos.getX() < size && pos.getY() < size) {
         SlippyMapTile image = this.cache.getTile(pos);
         // 2026-10-05 performance fix: isReady() goes through getLocation(), which can upload a
         // finished NativeImage to the GPU, so it is called once here instead of twice (once to
         // decide whether to draw, once to decide whether to cascade to the parent).
         boolean ready = image != null && image.isReady();
         if (ready) {
            // 2026-10-05 performance fix: this is the ONLY cache lookup for the tile. It used to be
            // repeated a second time by SlippyMapWidget's draw loop (`map.getTile(pos)`), which
            // rebuilt the Guava TileKey and walked the LoadingCache again for every tile on
            // screen every frame. The resolved tile travels with the position instead.
            tiles.add(new SlippyMap.DrawTile(pos, image));
         }

         if (pos.getZoom() >= 3 && (!ready || image.getTransition() < 1.0F)) {
            this.cascadeTile(tiles, new SlippyMapTilePos(pos.getX() >> 1, pos.getY() >> 1, pos.getZoom() - 1));
         }
      }
   }

   public int getCameraX() {
      return this.camera.getX();
   }

   public int getCameraY() {
      return this.camera.getY();
   }

   public int getCameraZoom() {
      return this.camera.getZoom();
   }

   public void shutdown() {
      this.cache.shutdown();
   }

   /**
    * A tile that is ready to be drawn: the position plus the tile <b>already resolved for it</b>.
    *
    * <p>2026-10-05 performance fix: {@link SlippyMapWidget} used to call {@link #getTile} for every
    * position {@link #cascadeTiles} had already resolved, i.e. a second Guava
    * {@code LoadingCache.get()} (with a freshly allocated {@code TileKey}) per tile per frame. The
    * resolved tile is carried here instead, so each position costs exactly one lookup.
    */
   @Environment(EnvType.CLIENT)
   public record DrawTile(SlippyMapTilePos pos, SlippyMapTile tile) {
      public DrawTile(SlippyMapTilePos pos, SlippyMapTile tile) {
         this.pos = Objects.requireNonNull(pos, "pos");
         this.tile = Objects.requireNonNull(tile, "tile");
      }
   }

   @Environment(EnvType.CLIENT)
   private static class Camera {
      private SlippyMapPoint origin;
      private int zoom = 3;

      private Camera(SlippyMapPoint origin, int width, int height) {
         this.origin = origin.translate(-width / 2, -height / 2, this.zoom);
      }

      public void focus(int x, int y, int zoom, int width, int height) {
         int clampedZoom = Mth.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
         this.origin = new SlippyMapPoint(x, y, clampedZoom).translate(-width / 2, -height / 2, clampedZoom);
         this.zoom = clampedZoom;
      }

      public void pan(int deltaX, int deltaY) {
         this.origin = this.origin.translate(deltaX, deltaY, this.zoom);
      }

      public void zoom(int steps, int pivotX, int pivotY) {
         if (steps != 0) {
            int originX = this.origin.getX(this.zoom);
            int originY = this.origin.getY(this.zoom);
            int nextZoom = Mth.clamp(this.zoom + steps, MIN_ZOOM, MAX_ZOOM);
            if (nextZoom == this.zoom) {
               return;
            }

            this.zoom = nextZoom;
            if (steps > 0) {
               int newX = originX * 2 + pivotX;
               int newY = originY * 2 + pivotY;
               this.origin = new SlippyMapPoint(newX, newY, this.zoom);
            } else if (steps < 0) {
               int newX = (originX - pivotX) / 2;
               int newY = (originY - pivotY) / 2;
               this.origin = new SlippyMapPoint(newX, newY, this.zoom);
            }
         }
      }

      public int getX() {
         return this.origin.getX(this.zoom);
      }

      public int getY() {
         return this.origin.getY(this.zoom);
      }

      public int getZoom() {
         return this.zoom;
      }
   }
}
