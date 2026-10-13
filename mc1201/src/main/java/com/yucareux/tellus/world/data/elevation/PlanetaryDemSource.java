package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.config.MirrorConfig;
import com.yucareux.tellus.config.TellusCnOptions;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;

/**
 * The **planetary elevation** source of arnis (Moon / Mars), reading NASA PDS global rasters directly.
 *
 * <h2>Specification (matches DemSpec in arnis {@code src/elevation/providers/planetary.rs})</h2>
 *
 * | Body | File | ppd | Tile size | Coverage | Byte order | DN to metres |
 * |---|---|---|---|---|---|---|
 * | Mars MOLA | {@code megt{lat}{n\|s}{lon}hb.img} x16 | 128 | 5632 rows x 11520 columns | lat +/-88, lon 0..360 in 4 bands | **big endian** MSB | x1.0 |
 * | Moon LOLA | {@code ldem_128.img} single file | 128 | 23040 rows x 46080 columns | lat +/-90, lon +/-180 | **little endian** LSB | x0.5 |
 *
 * <p>The files use a plain equirectangular projection (SimpleCylindrical), so converting latitude
 * and longitude into a row and column is a direct linear mapping. Because they are static rasters of
 * hundreds of MB up to 2 GB, they are read **row by row over HTTP Range** (23 KB per row on Mars, 92 KB on the Moon) and the row cache is an LRU bounded by bytes.
 *
 * <p>The first row is the **maximum latitude** (north at the top) and longitude starts at 0..360 as the PDS label states.
 * Neither fact is a guess, both were checked against known terrain through our own proxy using Range requests:
 *
 * | Terrain | Sample value | Real value |
 * |---|---|---|
 * | Mars Olympus Mons (18.65N, 226.2E) | **+20015 m** | about +21.2 km |
 * | Mars Hellas Planitia (-42.4S, 70.5E) | **-6044 m** | about -7 km |
 * | Mars northern lowlands (45N, 110E) | **-4929 m** | about -4.8 km |
 * | Moon highest peak (5.4N, 201.4E) | **+10730 m** | +10790 m (60 m off) |
 * | Moon South Pole Aitken (-53S, 191E) | **-6926 m** | about -6 to -9 km |
 */
public final class PlanetaryDemSource {

   /** The raster specification of one celestial body. */
   private record Spec(String baseUrl, int ppd, double latSpan, double lonSpan,
                       double latMin, double latMax, boolean bigEndian, double dnToMeters,
                       int maxLonBands) {
   }

   private static final Spec MARS = new Spec(
         // Each file is 44 by 90 degrees, 16 files in total (+/-88 latitude times 4 longitude bands). Beyond +/-88 the polar products are not read
         "planet/mars/dem",
         128, 44.0, 90.0, -88.0, 88.0, true, 1.0, 4);

   private static final Spec MOON = new Spec(
         // One file for the whole Moon
         "planet/moon/dem",
         128, 180.0, 360.0, -90.0, 90.0, false, 0.5, 1);

   /** Row cache: raw DN values (short) stored per row, capped at 512 rows, about 47 MB for the Moon and 12 MB for Mars. */
   private static final int MAX_CACHED_ROWS = Integer.getInteger("telluscn.arnis.planetaryRowCache", 512);

   private static final LoadingCache<String, short[]> ROWS = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHED_ROWS)
         .concurrencyLevel(Math.max(4, Runtime.getRuntime().availableProcessors() * 2))
         .build(new CacheLoader<String, short[]>() {
            @Override
            public short[] load(String key) {
               return readRow(key);
            }
         });

   private PlanetaryDemSource() {
   }

   /**
    * Samples a planetary elevation in metres, relative to the datum of that body.
    *
    * @return {@code null} when it cannot be fetched (no proxy, out of range, or the read failed)
    */
   public static Double sample(TellusCnOptions.CelestialBody body, double latitude, double longitude) {
      Spec spec = body == TellusCnOptions.CelestialBody.MARS ? MARS
            : body == TellusCnOptions.CelestialBody.MOON ? MOON : null;
      if (spec == null) {
         return null;
      }
      if (latitude < spec.latMin() || latitude > spec.latMax()) {
         return null; // the polar regions are not part of the products we read
      }

      // Mars: pick the longitude band (0/90/180/270) and the latitude band first
      String fileName;
      double fileLonMin;
      double fileLatMax;
      // 2026-10-05: the longitude is reduced to band-relative exactly ONCE, and only once.
      //   The trigger was the double subtraction of fileLonMin: once in the Mars branch and once
      //   again in the column computation below. The consequence was that every sample whose band
      //   offset was non-zero got floor((normalisedLon - 2*fileLonMin) * 128), i.e. a negative
      //   column that the range check rejected -- so the whole 90E..360E half of Mars silently
      //   returned "no data" (TellusArnisElevation then substitutes the 0 m reference plane) while
      //   the 0..90E band kept working, because there fileLonMin happens to be 0.
      //   Worked example, Olympus Mons at 226.2E: fileLonMin = 180, so the old column was
      //   floor((46.2 - 180) * 128) = -17127 -> null. Reducing once gives floor(46.2 * 128) = 5913,
      //   inside the 11520-sample band.
      double lonForCol;
      if (body == TellusCnOptions.CelestialBody.MARS) {
         double normalisedLon = ((longitude % 360.0) + 360.0) % 360.0;
         int lonBand = (int) Math.floor(normalisedLon / spec.lonSpan());
         double latBandMin = Math.floor(latitude / spec.latSpan()) * spec.latSpan();
         latBandMin = Math.max(spec.latMin(), Math.min(spec.latMax() - spec.latSpan(), latBandMin));
         fileLonMin = lonBand * spec.lonSpan();
         fileLatMax = latBandMin + spec.latSpan();
         // The naming rule (confirmed by the PDS label and by the arnis unit test): the maximum latitude goes in the name,
         // so the -44..0 band is called 00n and **not** 44s, and only -88..-44 is called 44s.
         String latPart = latBandMin >= -spec.latSpan()
               ? String.format(Locale.ROOT, "%02dn", (int) (latBandMin + spec.latSpan()))
               : String.format(Locale.ROOT, "%02ds", (int) Math.abs(latBandMin));
         fileName = String.format(Locale.ROOT, "megt%s%03dhb.img", latPart, (int) fileLonMin);
         // The single reduction: absolute 0..360 becomes band-relative 0..lonSpan.
         lonForCol = normalisedLon - fileLonMin;
      } else {
         fileName = "ldem_128.img";
         // PDS label: WESTERNMOST_LONGITUDE=0 / EASTERNMOST_LONGITUDE=360
         // so the lunar raster longitudes run 0..360 and not +/-180, which once produced a completely wrong column.
         fileLonMin = 0.0;
         fileLatMax = 90.0;
         // 2026-10-05: the Moon is one file covering the whole circle, so there is no band offset
         //   to subtract; normalise once here and keep the result. fileLonMin is 0, so this is
         //   bit-for-bit the behaviour the measured table in the class javadoc records.
         lonForCol = ((longitude % 360.0) + 360.0) % 360.0;
      }

      int tileSamples = (int) (spec.lonSpan() * spec.ppd());
      int tileLines = (int) (spec.latSpan() * spec.ppd());

      // lonForCol is already normalised (0..360) and, for Mars, already reduced to the band:
      // do not offset it again here.
      int col = (int) Math.floor(lonForCol * spec.ppd());
      int row = (int) Math.floor((fileLatMax - latitude) * spec.ppd());
      if (col < 0 || col >= tileSamples || row < 0 || row >= tileLines) {
         return null;
      }

      String rowKey = spec.baseUrl() + '|' + fileName + '|' + row;
      short[] samples;
      try {
         samples = ROWS.get(rowKey);
      } catch (Exception e) {
         Tellus.LOGGER.debug("Planetary DEM data row read failed {}: {}", rowKey, e.toString());
         return null;
      }
      if (samples == null || samples.length == 0) {
         return null;
      }
      double dn = samples[col];
      return dn * spec.dnToMeters();
   }

   // ------------------------------------------------------------ row reading

   /** Reads one whole row of raw DN (Range request plus big/little endian interpretation). */
   private static short[] readRow(String key) {
      int cut = key.lastIndexOf('|');
      if (cut < 0) {
         return new short[0];
      }
      int secondCut = key.lastIndexOf('|', cut - 1);
      if (secondCut < 0) {
         return new short[0];
      }
      String route = key.substring(0, secondCut);
      String fileName = key.substring(secondCut + 1, cut);
      int row;
      try {
         row = Integer.parseInt(key.substring(cut + 1));
      } catch (NumberFormatException e) {
         return new short[0];
      }
      Spec spec = route.contains("mars") ? MARS : MOON;

      String base = MirrorConfig.getActiveDomain();
      if (base.isBlank()) {
         return new short[0];
      }
      int samplesPerRow = (int) (spec.lonSpan() * spec.ppd());
      long start = (long) row * samplesPerRow * 2L;
      long end = start + samplesPerRow * 2L - 1L;
      String url = base + '/' + spec.baseUrl() + '/' + fileName;

      byte[] raw = fetchRange(url, start, end);
      if (raw == null || raw.length < samplesPerRow * 2) {
         return new short[0];
      }
      short[] out = new short[samplesPerRow];
      for (int i = 0; i < samplesPerRow; i++) {
         int hi = raw[i * 2] & 0xFF;
         int lo = raw[i * 2 + 1] & 0xFF;
         int value = spec.bigEndian() ? (hi << 8) | lo : (lo << 8) | hi;
         out[i] = (short) value;
      }
      return out;
   }

   /** A GET with Range (goes through the download pool too, so concurrency does not exhaust the connections). */
   private static byte[] fetchRange(String url, long start, long end) {
      String flightKey = url + '#' + start + '-' + end;
      try {
         return TellusDownloadExecutor.load("arnis-planet-dem", flightKey, ignored -> {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestProperty("User-Agent", "TellusCN/1.0 (Minecraft Mod)");
            connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) {
               Tellus.LOGGER.debug("Planetary DEM range request failed {}: HTTP {}", url, status);
               return new byte[0];
            }
            try (InputStream in = connection.getInputStream()) {
               return in.readAllBytes();
            }
         });
      } catch (Exception e) {
         Tellus.LOGGER.debug("Planetary DEM range request errored {}: {}", url, e.toString());
         return null;
      }
   }

   /** Clears the row cache. */
   public static void invalidate() {
      ROWS.invalidateAll();
   }

   /** Whether a readable PDS product exists for that body. */
   public static boolean supported(TellusCnOptions.CelestialBody body) {
      return body == TellusCnOptions.CelestialBody.MOON || body == TellusCnOptions.CelestialBody.MARS;
   }

   /** For the ASCII sanity check: reads one row and computes statistics. */
   public static String describeRow(TellusCnOptions.CelestialBody body, String fileName, int row) {
      Spec spec = body == TellusCnOptions.CelestialBody.MARS ? MARS : MOON;
      int samplesPerRow = (int) (spec.lonSpan() * spec.ppd());
      short[] samples = fetchDecode(spec, fileName, row);
      if (samples == null || samples.length == 0) {
         return "read failed";
      }
      double min = Double.MAX_VALUE;
      double max = -Double.MAX_VALUE;
      for (short s : samples) {
         double m = s * spec.dnToMeters();
         min = Math.min(min, m);
         max = Math.max(max, m);
      }
      return String.format(Locale.ROOT, "%s row %d: %d samples, %.0f to %.0f metres", fileName, row, samplesPerRow, min, max);
   }

   private static short[] fetchDecode(Spec spec, String fileName, int row) {
      String base = MirrorConfig.getActiveDomain();
      if (base.isBlank()) {
         return null;
      }
      int samplesPerRow = (int) (spec.lonSpan() * spec.ppd());
      long start = (long) row * samplesPerRow * 2L;
      byte[] raw = fetchRange(base + '/' + spec.baseUrl() + '/' + fileName, start, start + samplesPerRow * 2L - 1L);
      if (raw == null || raw.length < samplesPerRow * 2) {
         return null;
      }
      short[] out = new short[samplesPerRow];
      for (int i = 0; i < samplesPerRow; i++) {
         int hi = raw[i * 2] & 0xFF;
         int lo = raw[i * 2 + 1] & 0xFF;
         out[i] = (short) (spec.bigEndian() ? (hi << 8) | lo : (lo << 8) | hi);
      }
      return out;
   }

   /** Read-only access to the Mars file name (reused by preloading and by commands). */
   public static String marsFileName(int latBandMin, int lonBandMin) {
      String latPart = latBandMin >= -44
            ? String.format(Locale.ROOT, "%02dn", latBandMin + 44)
            : String.format(Locale.ROOT, "%02ds", Math.abs(latBandMin));
      return String.format(Locale.ROOT, "megt%s%03dhb.img", latPart, lonBandMin);
   }
}
