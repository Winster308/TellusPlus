package com.yucareux.tellus.client.widget.map;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.TellusEndpointConfig;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import com.yucareux.tellus.client.map.OpenFreeMapRasterizer;
import com.yucareux.tellus.config.TellusCnOptions;

@Environment(EnvType.CLIENT)
public class SlippyMapTileCache {
   private static final int CACHE_SIZE = 1024;

   /**
    * Tile download pool size.
    *
    * <p>2026-09-23 raised it from <b>4 to 16</b> (observed symptom: the map "fills in cell by cell,
    * like a snail"). One screen often needs dozens to hundreds of tiles, and downloading 4 at a
    * time in sequence looks exactly like that slow fill. Browser map widgets (the kind arnis uses)
    * default to 6 to 8 concurrent requests, so 16 is comfortable here.
    * Tune it with {@code -Dtellus.map.tileThreads=N} (1 to 64).
    */
   private static final int TILE_THREADS = resolveTileThreads();

   private final ExecutorService loadingService = Executors.newFixedThreadPool(
      TILE_THREADS, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("tellus-map-load-%d").build()
   );
   private final Queue<InputStream> loadingStreams = new LinkedBlockingQueue<>();
   private final Path cacheRoot = Minecraft.getInstance().gameDirectory.toPath().resolve("tellus/cache/map");
   private volatile boolean shuttingDown;

   private static int resolveTileThreads() {
      try {
         return Math.max(1, Math.min(64, Integer.getInteger("tellus.map.tileThreads", 16)));
      } catch (RuntimeException | LinkageError error) {
         return 16;
      }
   }

   /**
    * Cache key: <b>must carry the data source (theme)</b>.
    *
    * <p>The trap that was hit here: the key only held z/x/y, so <b>after switching the theme the
    * old theme tiles were still treated as a valid cache hit</b>, leaving half the screen in the
    * new theme and half in the old one (observed as "half OpenTopoMap plus half Carto watermark").
    * The disk cache works the same way and is now split into per theme directories.
    */
   private record TileKey(String source, SlippyMapTilePos pos) {
   }

   /** Current data source identifier (the theme name). Changing it swaps the whole set of tiles. */
   private static String currentSource() {
      try {
         String theme = TellusCnOptions.mapTheme();
         return theme == null || theme.isBlank() ? "osm" : theme;
      } catch (RuntimeException | LinkageError e) {
         return "osm";
      }
   }

   private final LoadingCache<SlippyMapTileCache.TileKey, SlippyMapTile> tileCache = CacheBuilder.newBuilder()
      .maximumSize(CACHE_SIZE)
      .removalListener(notification -> {
         SlippyMapTile tile = (SlippyMapTile)notification.getValue();
         if (tile != null) {
            tile.delete();
         }
      })
      .build(new CacheLoader<SlippyMapTileCache.TileKey, SlippyMapTile>() {
         public SlippyMapTile load(SlippyMapTileCache.TileKey key) {
            SlippyMapTilePos pos = key.pos();
            // **2026-10-05 fix**: the theme is taken from the cache key and threaded all the way down.
            // Previously only `pos` was passed on and downloadImage()/readTileData() re-read the
            // *live* theme via currentSource()/mapTheme() in the middle of the download. Switching
            // theme while a tile was in flight therefore keyed the memory cache on the old theme while
            // the disk path and the rasterizer branch used the new one, so the old theme's bytes were
            // written into the new theme's directory -- permanent disk-cache pollution, i.e. exactly
            // the trap this file's own header warns about.
            String theme = key.source();
            SlippyMapTile tile = new SlippyMapTile(pos);

            try {
               SlippyMapTileCache.this.loadingService.submit(() -> {
                  NativeImage image = SlippyMapTileCache.this.downloadImage(pos, theme);
                  if (image == null) {
                     return;
                  }

                  if (SlippyMapTileCache.this.shuttingDown) {
                     image.close();
                  } else {
                     tile.supplyImage(image);
                  }
               });
            } catch (RejectedExecutionException ignored) {
               tile.supplyImage(SlippyMapTileCache.this.createErrorImage());
            }

            return tile;
         }
      });

   public SlippyMapTile getTile(SlippyMapTilePos pos) {
      try {
         return (SlippyMapTile)this.tileCache.get(new SlippyMapTileCache.TileKey(currentSource(), pos));
      } catch (Exception var4) {
         SlippyMapTile tile = new SlippyMapTile(pos);
         tile.supplyImage(this.createErrorImage());
         return tile;
      }
   }

   public void shutdown() {
      this.shuttingDown = true;
      this.loadingService.shutdownNow();
      this.tileCache.invalidateAll();
      this.tileCache.cleanUp();

      while (!this.loadingStreams.isEmpty()) {
         try {
            InputStream poll = this.loadingStreams.poll();
            if (poll != null) {
               poll.close();
            }
         } catch (IOException var3) {
            Tellus.LOGGER.warn("Failed to close a map loading stream", var3);
         }
      }
   }

   private NativeImage downloadImage(SlippyMapTilePos pos, String theme) {
      try {
         // Modern (OpenFreeMap vector) theme: rasterize the MVT on the spot.
         // Produces the NativeImage directly (no PNG encode/decode round trip) - measured, PNG
         // encoding accounted for 32% of the time spent on such a tile.
         if (OpenFreeMapRasterizer.handlesTheme(theme)) {
            NativeImage vectorTile = OpenFreeMapRasterizer.renderNativeImage(
               pos.getZoom(), pos.getX(), pos.getY()
            );
            if (vectorTile != null) {
               return vectorTile;
            }
            // No vector tile available, fall back to the raster source so the map is not blank
         }

         byte[] data = this.readTileData(pos, theme);
         return data == null ? null : NativeImage.read(new ByteArrayInputStream(data));
      } catch (IOException var4) {
         if (this.isCancelledLoad(var4)) {
            return null;
         }

         Tellus.LOGGER.error("Failed to load map tile {}", pos, var4);
         return this.createErrorImage();
      }
   }

   private byte[] readTileData(SlippyMapTilePos pos, String theme) throws IOException {
      // The disk cache is split per data source too, so tiles of the old theme expire naturally.
      // **2026-10-05 fix**: use the theme pinned by the cache key, NOT currentSource() -- reading
      // the live setting here is what allowed a mid-download theme switch to write one theme's
      // bytes into another theme's directory.
      Path cachePath = this.cacheRoot.resolve(theme).resolve(pos.getCacheName());
      if (Files.exists(cachePath)) {
         return Files.readAllBytes(cachePath);
      }

      String tileUrl = getTileUrl(pos);
      Tellus.LOGGER.debug("[TellusCN] Downloading map tile: {}, url: {}", pos, tileUrl);
      URI uri = URI.create(tileUrl);
      URL url = uri.toURL();
      HttpURLConnection connection = (HttpURLConnection)url.openConnection();
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setRequestProperty("User-Agent", "Tellus/2.0.0 (Minecraft Mod)");
      int responseCode = connection.getResponseCode();
      Tellus.LOGGER.debug("[TellusCN] Map tile response: {}, request: {}", responseCode, tileUrl);
      if (responseCode != 200) {
         throw new IOException("OpenStreetMap tile request failed, HTTP status " + responseCode + ", " + pos);
      }

      // **2026-10-05 fix**: getInputStream() may return null (e.g. an error response with no body).
      // Objects.requireNonNull threw a NullPointerException, which is unchecked and therefore
      // escaped the caller's `catch (IOException)`, so the tile never got an image *and* never got
      // the error tile either -> that grid cell stayed blank forever. Convert it to IOException.
      InputStream stream = connection.getInputStream();
      if (stream == null) {
         throw new IOException("Map tile response had no body, HTTP status " + responseCode + ", " + pos);
      }
      this.loadingStreams.add(stream);

      //  2026-09-23 performance fix (<b>this was the real cause of the snail-like tile map</b>):
      //
      //   The old implementation called `connection.disconnect()` in a finally block -
      //   `HttpURLConnection` <b>reuses the underlying TCP/TLS connection</b> (HTTP keep-alive),
      //   while `disconnect()` <b>closes it on purpose and makes the pool drop it</b> ->
      //   <b>every tile had to redo a full TCP + TLS handshake</b> (100 to 200 ms,
      //   often slower than fetching the tile itself).
      //   => hundreds of tiles on one screen -> over ten seconds of handshakes alone -> the map
      //      "fills in cell by cell".
      //
      //    Correct approach: <b>close the stream only, never the connection</b> -
      //     drain the InputStream and close it, then the underlying connection returns to the pool
      //     for reuse.
      //    disconnect() is only used <b>when something really went wrong</b>, to avoid handing a
      //    half-read connection back to the pool.
      try (InputStream input = new BufferedInputStream(stream)) {
         byte[] data = input.readAllBytes();
         Tellus.LOGGER.debug("[TellusCN] Map tile downloaded: {} ({} bytes)", pos, data.length);
         if (!this.shuttingDown && !Thread.currentThread().isInterrupted()) {
            this.cacheData(cachePath, data);
         }

         return data;
      } catch (IOException error) {
         // On error: disconnect only here, so a half-read connection never goes back to the pool
         connection.disconnect();
         throw error;
      } finally {
         this.loadingStreams.remove(stream);
      }
   }

   private boolean isCancelledLoad(IOException error) {
      return this.shuttingDown
         || Thread.currentThread().isInterrupted()
         || error instanceof InterruptedIOException
         || error instanceof ClosedByInterruptException;
   }

   private void cacheData(Path cachePath, byte[] data) {
      try {
         Files.createDirectories(Objects.requireNonNull(cachePath.getParent(), "cachePathParent"));
      } catch (IOException var7) {
         Tellus.LOGGER.error("Failed to create the cache root directory", var7);
      }

      try (OutputStream output = Files.newOutputStream(cachePath)) {
         output.write(data);
      } catch (IOException var9) {
         Tellus.LOGGER.error("Failed to write the map tile cache", var9);
      }
   }

   private NativeImage createErrorImage() {
      NativeImage result = new NativeImage(256, 256, false);

      for (int x = 0; x < 256; x++) {
         for (int y = 0; y < 256; y++) {
            result.setPixelABGR(x, y, -16776961);
         }
      }

      return result;
   }

   /**
    * Resolves the map tile URL.
    * Priority: mirror config, then the JVM property, then the default official source.
    */
   private String getTileUrl(SlippyMapTilePos pos) {
      String mirrorEndpoint = TellusEndpointConfig.getMapTilesEndpoint("");
      if (!mirrorEndpoint.isBlank()) {
         // Use the mirror endpoint
         return String.format("%s/%s/%s/%s.png", mirrorEndpoint, pos.getZoom(), pos.getX(), pos.getY());
      }

      // Check the JVM property
      String jvmEndpoint = System.getProperty("tellus.map.tiles.endpoint");
      if (jvmEndpoint != null && !jvmEndpoint.isBlank()) {
         return String.format("%s/%s/%s/%s.png", jvmEndpoint, pos.getZoom(), pos.getX(), pos.getY());
      }

      // Use the default official source
      return String.format("https://tile.openstreetmap.org/%s/%s/%s.png", pos.getZoom(), pos.getX(), pos.getY());
   }
}
