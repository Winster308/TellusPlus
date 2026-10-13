/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 * 
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.config;

import com.yucareux.tellus.Tellus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * TellusCN mirror configuration manager
 * 
 * Manages mirror settings for all data sources, supporting:
 * 1. Official preset Workers domains
 * 2. Custom Workers domains
 * 3. Disabling the mirror to use the original source
 * 
 * Priority: in-game settings > JVM launch arguments > default official source
 */
public final class MirrorConfig {
   
    private static final String CONFIG_FILE = "telluscn-mirror.properties";
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
    private static final Object LOCK = new Object();
    
    // Configuration keys
    private static final String KEY_ENABLED = "mirror.enabled";
    private static final String KEY_CUSTOM_DOMAIN = "mirror.custom_domain";
    private static final String KEY_SELECTED_PRESET = "mirror.selected_preset";
    private static final String KEY_ROUTES = "mirror.routes";
    
    // Preset Workers domains (domestic acceleration)
    // Item 0 is the main site: tested 2026-09-25 /health 200, /elevation and /tiles return image/png,
    // /geocoding returns real Nominatim JSON, /mapterhorn returns webp.
    // The original item 0 telluscn.winster.top was a backup node whose registrar stopped resolving
    // (DNS lookup fails, all requests fail directly). On 2026-09-25 it was replaced by the main site
    // telluscn.site — so it was removed from the presets. This note is left here to avoid switching
    // to a dead node in-game.
    // Item 1 is the upstream original official node — tested 2026-09 /elevation returns 500
    // (`event is not defined` in the Worker; the modular Worker used service-worker syntax).
    // It is kept only so it can be switched back if other deployments are still using the same backend.
    public static final String[] OFFICIAL_PRESETS = {
       "https://telluscn.site",         // Main site (tested /elevation /tiles /geocoding /mapterhorn available)
       "https://telluscn.ggff.net",     // Upstream official node (currently /elevation 500)
    };
    
    /**
     * Which routes the mirror actually implements.
     *
     * <p><b>Why this layer exists</b>: the mirror is just a Worker reverse proxy; not every data source
     * has a corresponding backend. Tested route-by-route on the main site {@code https://telluscn.site}
     * on 2026-09-25:
     * <pre>
     * /elevation/{z}/{x}/{y}.png         206 image/png                  -> proxy
     * /tiles/{src}/{z}/{x}/{y}.png       206 image/png                  -> proxy
     * /geocoding?q=...                   200 real Nominatim JSON        -> proxy
     * /landcover/...tif                  206 (40MB shard tested at 3.5 MB/s) -> on demand
     * /overture/{roads|buildings|water}  206 PMTiles (Range supported)  -> on demand
     * /mapterhorn/...                    200 image/webp                 -> own route
     * /s3/{whitelist bucket}/...         206 binary/octet-stream        -> own route
     * /usgs?...                          200 but text/html error page  -> no proxy
     * /overpass                          521 (CF cannot reach api.arnismc.com) -> no proxy
     * </pre>
     *
     * <p>Large-file routes used to return 500 across the board {@code error code: 1101} (Worker runtime
     * error, not an origin error). The root cause was in the Worker's {@code proxy()}: it used
     * {@code await upstreamResponse.arrayBuffer()} to read the entire upstream response into memory,
     * while landcover's TIFF shards are 150 MB each and overture's PMTiles are even larger,
     * directly exceeding the Workers 128 MB limit. On 2026-09-25 this was changed to streaming
     * passthrough (passing {@code upstreamResponse.body} directly to {@code new Response(...)}),
     * and the large-file routes recovered.
     *
     * <p><b>Why the default still only proxies these three</b>: because testing showed large files
     * through the mirror are actually slower — landcover 40 MB shards through the mirror run at
     * 3.5 MB/s, while direct connection to the AWS origin is about 7.4 MB/s (the mirror adds an
     * extra hop back to the origin), and these tiles are cached long-term after download, unlike
     * the base map which is fetched repeatedly. elevation / tiles / geocoding are small responses
     * that are "requested every time the map is viewed", so the mirror's edge caching is worthwhile.
     * To enable more: {@code -Dtellus.mirror.routes=elevation,tiles,geocoding,landcover} or in-game settings.
     *
     * <p>landcover must be available — if it goes down, the spawn position algorithm
     * ({@code EarthChunkGenerator.getSurfacePosition}) throws an exception, the spawn chunk can
     * never be generated, and you get "stuck preparing to generate the area / cannot enter the world".
     */
    private static final java.util.Set<String> DEFAULT_MIRRORED_ROUTES = java.util.Set.of(
       "elevation",
       "tiles",
       "geocoding"
    );

    /**
     * Map base tile source (the Worker's /tiles/{src} segment).
     *
     * <p>Default {@code carto}: WGS-84 base map, clean style, edge-cache friendly, fully aligned
     * with the latitude/longitude used by this mod. Options: {@code osm} / {@code osmde} /
     * {@code osmfr} / {@code topo} / {@code cartodark} / {@code esri} (satellite imagery) /
     * {@code amap}, {@code amapimg} (Amap, fastest, but uses **GCJ-02 Mars coordinates**,
     * which will have about a 500 m offset from the mod's WGS-84 latitude/longitude —
     * do not use it for coordinate matching).
     */
    private static final String KEY_TILE_SOURCE = "mirror.tile_source";
    /** Empty = determined by mapTheme in config/telluscn.json (default arnis's osm). */
    private static volatile String tileSource = "";
    
    // Current configuration
    private static volatile boolean enabled = true;
    private static volatile String customDomain = "";
    private static volatile int selectedPreset = 0;
    private static volatile java.util.Set<String> mirroredRoutes = DEFAULT_MIRRORED_ROUTES;
    private static volatile boolean loaded = false;
    
    private MirrorConfig() {
    }
    
    /**
     * Get the currently active Workers base domain
     * Priority: custom > preset > empty (use original)
     */
    public static String getActiveDomain() {
       if (!isEnabled()) {
          return "";
       }
       
       String custom = getCustomDomain();
       if (!custom.isBlank()) {
          return normalizeDomain(custom);
       }
       
       int presetIndex = getSelectedPreset();
       if (presetIndex >= 0 && presetIndex < OFFICIAL_PRESETS.length) {
          return OFFICIAL_PRESETS[presetIndex];
       }
       
       return "";
    }
    
    /**
     * Get the full endpoint URL for a given data source
     */
    public static String getEndpoint(String source) {
       String domain = getActiveDomain();
       if (domain.isBlank()) {
          return ""; // Returning empty means using the original default source
       }
       return domain + "/" + source;
    }
    
    /**
     * Get endpoint configuration for all data sources (for JVM argument format)
     */
    public static String[] getAllEndpointArgs() {
       String domain = getActiveDomain();
       if (domain.isBlank()) {
          return new String[0];
       }
       
       return new String[] {
          "-Dtellus.elevation.endpoint=" + domain + "/elevation",
          "-Dtellus.copernicus30.endpoint=" + domain + "/copernicus30",
          "-Dtellus.copernicus90.endpoint=" + domain + "/copernicus90",
          "-Dtellus.usgs.endpoint=" + domain + "/usgs",
          "-Dtellus.japangsi.endpoint=" + domain + "/japangsi",
          "-Dtellus.arcticdem.endpoint=" + domain + "/arcticdem",
          "-Dtellus.rema.endpoint=" + domain + "/rema",
          "-Dtellus.landcover.endpoint=" + domain + "/landcover",
          "-Dtellus.weather.endpoint=" + domain + "/weather",
          "-Dtellus.geocoding.endpoint=" + domain + "/geocoding",
          "-Dtellus.osm.overpass.endpoints=" + domain + "/overpass",
          "-Dtellus.landmask.baseUrl=" + domain + "/landmask/",
          "-Dtellus.s3proxy.endpoint=" + domain + "/s3",
          "-Dtellus.tiles.endpoint=" + domain + "/tiles",
          "-Dtellus.overture.roads.endpoint=" + domain + "/overture/roads",
          "-Dtellus.overture.buildings.endpoint=" + domain + "/overture/buildings",
          "-Dtellus.overture.water.endpoint=" + domain + "/overture/water",
          "-Dtellus.overture.sand.endpoint=" + domain + "/overture/sand",
       };
    }
    
    /**
     * Whether a given route is proxied through the mirror.
     *
     * @param route route name, e.g. {@code elevation} / {@code landcover} / {@code overture/roads}
     */
    public static boolean isRouteMirrored(String route) {
       ensureLoaded();
       java.util.Set<String> routes = mirroredRoutes;
       return routes.contains("*") || routes.contains(route);
    }
    
    /** Routes currently mirrored ( {@code *} means all). */
    /** Current map base tile source. */
    public static String getTileSource() {
       ensureLoaded();
       if (tileSource == null || tileSource.isBlank()) {
          // Prefer mapTheme from telluscn.json (arnis's theme name), then fall back to arnis's default theme
          String theme = com.yucareux.tellus.config.TellusCnOptions.mapTheme();
          return theme == null || theme.isBlank() ? "osm" : theme;
       }
       return tileSource;
    }

    public static void setTileSource(String source) {
       // 2026-10-05: the field write used to happen before the lock was taken, so two threads
       // changing different settings could each have save() observe a mix of both changes.
       // The mutation and its persistence must be one atomic step.
       synchronized (LOCK) {
          tileSource = source == null || source.isBlank() ? "carto" : source.trim();
          save();
       }
    }

   public static java.util.Set<String> getMirroredRoutes() {
       ensureLoaded();
       return java.util.Set.copyOf(mirroredRoutes);
    }
    
    /** Override the mirrored routes; pass {@code *} for full proxy (requires a complete CDN deployment). */
    public static void setMirroredRoutes(java.util.Collection<String> routes) {
       java.util.Set<String> next = new java.util.LinkedHashSet<>();
       for (String r : routes) {
          String v = normalize(r).toLowerCase(java.util.Locale.ROOT);
          if (!v.isBlank()) {
             next.add(v);
          }
       }
       // 2026-10-05: assignment happened outside the lock, so save() could persist a mixture of
       // two threads' concurrent changes. Mutation + persistence are now one atomic step.
       synchronized (LOCK) {
          mirroredRoutes = next.isEmpty() ? DEFAULT_MIRRORED_ROUTES : java.util.Set.copyOf(next);
          save();
       }
    }
    
    // ============ Getter / Setter ============
    
    public static boolean isEnabled() {
       ensureLoaded();
       return enabled;
    }
    
    public static void setEnabled(boolean value) {
       // 2026-10-05: assignment was outside the lock, so save() could persist a mixture of
       // two threads' concurrent changes. Mutation + persistence are now one atomic step.
       synchronized (LOCK) {
          enabled = value;
          save();
       }
    }
    
    public static String getCustomDomain() {
       ensureLoaded();
       return customDomain;
    }
    
    public static void setCustomDomain(String domain) {
       // 2026-10-05: assignment was outside the lock, so save() could persist a mixture of
       // two threads' concurrent changes. Mutation + persistence are now one atomic step.
       synchronized (LOCK) {
          customDomain = normalize(domain);
          save();
       }
    }
    
    public static int getSelectedPreset() {
       ensureLoaded();
       return selectedPreset;
    }
    
    public static void setSelectedPreset(int index) {
       // 2026-10-05: assignment was outside the lock, so save() could persist a mixture of
       // two threads' concurrent changes. Mutation + persistence are now one atomic step.
       synchronized (LOCK) {
          selectedPreset = Math.max(0, Math.min(index, OFFICIAL_PRESETS.length - 1));
          save();
       }
    }
    
    public static String[] getOfficialPresets() {
       return OFFICIAL_PRESETS.clone();
    }
    
    // ============ Persistence ============
    
    private static void ensureLoaded() {
       if (!loaded) {
          synchronized (LOCK) {
             if (!loaded) {
                load();
                loaded = true;
             }
          }
       }
    }
    
    private static void load() {
       if (!Files.exists(CONFIG_PATH)) {
          return;
       }
       
       Properties props = new Properties();
       try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
          props.load(input);
          
          enabled = Boolean.parseBoolean(props.getProperty(KEY_ENABLED, "true"));
          customDomain = normalize(props.getProperty(KEY_CUSTOM_DOMAIN, ""));
          // 2026-10-05 fix: a bad number used to abort the whole load, which meant KEY_TILE_SOURCE and
          // KEY_ROUTES below were NEVER read -> one typo in selectedPreset silently dropped the whole
          // mirror route configuration and tile source. Parse leniently instead.
          selectedPreset = parseIntOrDefault(props.getProperty(KEY_SELECTED_PRESET, "0"), 0);
          tileSource = props.getProperty(KEY_TILE_SOURCE, "");
          String routes = props.getProperty(KEY_ROUTES, "");
          if (!routes.isBlank()) {
             java.util.Set<String> parsed = new java.util.LinkedHashSet<>();
             for (String r : routes.split(",")) {
                String v = r.trim().toLowerCase(java.util.Locale.ROOT);
                if (!v.isEmpty()) {
                   parsed.add(v);
                }
             }
             if (!parsed.isEmpty()) {
                mirroredRoutes = java.util.Set.copyOf(parsed);
             }
          }
          
       } catch (IOException e) {
          Tellus.LOGGER.error("Failed to read mirror configuration", e);
       }
    }

    /**
     * Lenient int parse: a malformed value falls back instead of aborting the whole config load.
     *
     * <p>2026-10-05: {@code selectedPreset} used to be parsed with a bare
     * {@link Integer#parseInt} while sharing one catch with the {@link IOException} above, so a
     * single typo in that one number aborted the rest of the method and silently dropped
     * {@code tileSource} and {@code mirroredRoutes}.
     */
    private static int parseIntOrDefault(String raw, int fallback) {
       try {
          return Integer.parseInt(raw.trim());
       } catch (NumberFormatException | NullPointerException malformed) {
          Tellus.LOGGER.warn("Invalid number in mirror configuration ({}), ignored", raw);
          return fallback;
       }
    }
    
    // LOCK is reentrant, so the setters that now wrap `field = ...; save();` in synchronized (LOCK)
    // still end up here with the whole mutation+write already atomic. save() itself remains the
    // single entry point so callers outside a lock (e.g. load-time normalisation) keep working.
    private static void save() {
       synchronized (LOCK) {
          try {
             saveLocked();
          } catch (IOException e) {
             Tellus.LOGGER.error("Failed to save mirror configuration", e);
          }
       }
    }
    
    private static void saveLocked() throws IOException {
       Files.createDirectories(Objects.requireNonNull(CONFIG_PATH.getParent(), "configParent"));
       
       Properties props = new Properties();
       props.setProperty(KEY_ENABLED, String.valueOf(enabled));
       props.setProperty(KEY_CUSTOM_DOMAIN, customDomain);
       props.setProperty(KEY_SELECTED_PRESET, String.valueOf(selectedPreset));
       props.setProperty(KEY_ROUTES, String.join(",", mirroredRoutes));
       props.setProperty(KEY_TILE_SOURCE, tileSource);
       
       // 2026-10-05: Properties.store to the live path truncates first, so a crash (or another
       // reader) mid-write left a truncated file behind. load() has no fallback for that, so the
       // whole mirror config was silently reset to defaults. Write a temp file and move it into
       // place atomically instead — same pattern as TellusPreloadRegistry / ArnisPreloader.
       Path temp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
       try (OutputStream output = Files.newOutputStream(temp)) {
          props.store(output, "TellusCN 镜像配置");
       }
       try {
          Files.move(temp, CONFIG_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
       } catch (AtomicMoveNotSupportedException notAtomic) {
          Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
       }
    }
    
    // ============ Utility methods ============
    
    private static String normalize(String value) {
       return value == null ? "" : value.trim();
    }
    
    private static String normalizeDomain(String domain) {
       String normalized = normalize(domain);
       // Remove trailing slashes
       while (normalized.endsWith("/")) {
          normalized = normalized.substring(0, normalized.length() - 1);
       }
       // Ensure it starts with https://
       if (!normalized.isBlank() && !normalized.startsWith("http://") && !normalized.startsWith("https://")) {
          normalized = "https://" + normalized;
       }
       return normalized;
    }
    
    /**
     * Reset all configuration
     */
    public static void reset() {
       // 2026-10-05: same cross-thread mixture as the single-property setters; also note this
       // never reset tileSource / mirroredRoutes, so a "reset" left stale routing on disk.
       synchronized (LOCK) {
          enabled = false;
          customDomain = "";
          selectedPreset = 0;
          tileSource = "";
          mirroredRoutes = DEFAULT_MIRRORED_ROUTES;
          save();
       }
    }
}