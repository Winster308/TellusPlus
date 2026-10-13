package com.yucareux.tellus.worldgen;

/**
 * TellusCN native kernel (Rust) loader.
 *
 * <p>Kernel 1: dem.sample_bilinear_grid, batch bilinear sampling over decoded DEM tiles
 * (implemented in Rust, source in tellus-native/src/lib.rs, artifact tellus_native.dll).
 *
 * <p>Design: the DLL lives in {@code config/tellus/natives/}, is loaded on demand at startup and gets one
 * smoke check; any load failure or mismatching check falls back to the Java path and never breaks the game.
 */
public final class TellusNative {
   private static final org.slf4j.Logger LOGGER = com.yucareux.tellus.Tellus.LOGGER;
   private static boolean available;
   private static boolean checked;

   private TellusNative() {
   }

   public static synchronized boolean isAvailable() {
      if (checked) {
         return available;
      }
      checked = true;
      try {
         boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
         String name = windows ? "tellus_native.dll" : "libtellus_native.so";
         java.nio.file.Path dll = java.nio.file.Paths.get("config", "tellus", "natives", name);
         if (!java.nio.file.Files.isRegularFile(dll)) {
            LOGGER.info("[TellusCN] native kernel not installed (missing {}), using the Java sampler", dll);
            return available = false;
         }
         System.load(dll.toAbsolutePath().toString());
         double smoke = smoke();
         if (Math.abs(smoke - 3.0) < 1.0E-9) {
            available = true;
            LOGGER.info("[TellusCN] native kernel loaded: smoke value {} matches the expectation (the Rust bilinear sampling kernel is ready)", smoke);
         } else {
            LOGGER.warn("[TellusCN] native kernel smoke value mismatch: got {}, expected 3.0; using the Java sampler", smoke);
            available = false;
         }
      } catch (Throwable t) {
         LOGGER.warn("[TellusCN] native kernel failed to load (using the Java sampler): {}", t.toString());
         available = false;
      }
      return available;
   }

   private static native double smoke();

   /**
    * Kernel 2: dem.sample_copernicus_tile_batch, batch bilinear sampling of one Copernicus tile
    * (a 1 degree by 1 degree f32 grid) addressed by global pixel coordinates. The meaning matches
    * CopernicusDemElevationSource.sampleAtLevel: blendFiniteSamples (NaN corner removal with
    * renormalisation) and out[i]=NaN for points whose four corners fall outside this tile,
    * letting the caller fall back.
    *
    * @param tile        tile pixel data (row major f32[height*width])
    * @param originPxX   global pixel X of the tile pixel (0,0)
    * @param originPxY   global pixel Y of the tile pixel (0,0)
    * @param gx/gy       global pixel coordinates of each point (precomputed by the caller with the
    *                    sampleAtLevel formula)
    * @return            the number of points that were filled in (uncovered points get out[i]=NaN)
    */
   public static native int sampleCopernicusTileBatch(float[] tile, int tileW, int tileH,
      int originPxX, int originPxY, double[] gx, double[] gy, double[] out);

   public static native int sampleBilinearGrid(float[] tile, int width, int height,
      double minLon, double maxLat, double lonPerPx, double latPerPx,
      double[] lons, double[] lats, double[] out, int count);

   /**
    * Kernel 3 (resident style): a tile is uploaded once and stays resident on the Rust side, after which
    * each sampling batch only transfers coordinates.
    *
    * <p>Why this version exists: the two batch interfaces above copy the entire tile from the Java heap
    * into Rust through {@code env.get_float_array_region()} on every call
    * (1024x1024 f32 = 4 MB), and a measured 1024 points per batch still costs 2931 ns per point, so the
    * bottleneck is the copy rather than the sampling arithmetic. The resident version moves those 4 MB
    * once and then transfers only coordinates (16 bytes per point) per batch.
    *
    * <p><b>However</b>: the measurements in {@code tools/bench/bench-result*.txt} and
    * {@code ffm-result*.txt} show that the native path does not beat pure Java for per point sampling:
    * <pre>
    * Java today (one table lookup per corner)  ~40 ns/point   1.00x
    * Java improved (tile in hand, arithmetic) ~15 ns/point   2.4~3.1x   best
    * Rust resident batch (JNI)               ~50 ns/point   0.7~0.8x
    * FFM resident tile                       ~35 ns/point   1.1~1.2x
    * Rust batch (tile sent every call)       ~2931 ns/point 0.01x
    * </pre>
    * So these two interfaces are <b>kept for the case where the data stays on the native side and only the
    * final results are read back</b> (for example computing the terrain or water algorithms of a whole region
    * in one go); do not wire them blindly into the per point sampling path.
    *
    * @param tile tile pixels (row major f32)
    * @return 1 on success, 0 on failure
    */
   public static native int uploadTile(int id, float[] tile, int width, int height);

   /** Batch sample a resident tile by global pixel coordinates; points whose corners leave the tile get out[i]=NaN, returns the number covered. */
   public static native int sampleUploadedTile(int id, int originPxX, int originPxY,
      double[] gx, double[] gy, double[] out, int count);

   /**
    * Kernel 4 (the place where native really pays off): the weighted distance transform for
    * water/land, a Dial bucket Dijkstra.
    *
    * <p>The meaning matches {@code WaterSurfaceResolver.computeWeightedDistanceInternal} exactly:
    * 8 neighbours, straight edge 10 and diagonal edge 14, cost cap
    * {@code max(0, maxDistanceBlocks) * 10}, lazy deletion on bucket exit via
    * {@code cost == distances[index]}, and propagating the source height along with the distance
    * whenever {@code nearestSurface} is present.
    *
    * <p>Unlike the "per point sampling" interfaces above, this one is a bulk computation: a whole region
    * (180x180 = 32400 blocks by default) goes into native in one call and only two arrays come back, so the
    * boundary cost is amortised to nothing. It is the most expensive part of building a region:
    * an ocean region takes 1 to 3 seconds, while land takes the {@code buildDryRegionData} fast path at
    * only 65 to 180 ms.
    *
    * @param nearestSurface may be null, which means "do not propagate the source height"
    * @return 1 if both arrays were rewritten as specified; 0 to give up, letting the caller fall back
    *         to the pure Java implementation
    */
   public static native int weightedDistance(int[] distances, int[] nearestSurface, boolean[] allowed,
      int[] sources, int sourceCount, int gridSize, int maxDistanceBlocks, int initialCost);

   // =========================================================================
   // 2026-10-05: kernels 5..8, the elevation-repair pipeline.
   //
   // ★ Provenance: ported from arnis (github.com/louis-e/arnis, Apache-2.0),
   //   `elevation/postprocess.rs` + `elevation/mod.rs`. See THIRD_PARTY_NOTICES.md.
   //   Each of the four has a **pure Java mirror** (`TellusElevationRepair`), so the mod
   //   behaves identically when the native kernel is absent — the DLL is an accelerator,
   //   never a dependency.
   //
   // ★ Why these four and not the rest of arnis's postprocess: we grep the whole elevation
   //   package for outlier/spike/MAD/IQR/despeckle and get exactly **one comment hit** — we
   //   have **no denoising at all** — and `DEM_NODATA_METERS` is **one-sided**. These two gaps
   //   are what make a bad tile turn into a visible artefact instead of a missing sample.
   // =========================================================================

   /** NaN out physically impossible elevations, return the number of cells rewritten. Ported from arnis postprocess.rs:2538. */
   public static native int filterElevationOutliers(double[] grid, int count);

   /**
    * 5x5 median + MAD despike, return the number of cells repaired. Ported from arnis postprocess.rs:56.
    *
    * @param mPerCell how many metres one grid cell spans, i.e. {@code step * worldScale} on our side
    */
   public static native int repairTerrainAnomalies(double[] grid, int width, int height, double mPerCell);

   /** 3x3 neighbourhood-mean iterative hole filling (Jacobi), return the number of cells filled. Ported from arnis postprocess.rs:2530. */
   public static native int fillNanValues(double[] grid, int width, int height);

   /** Fraction of non-finite cells, used by arnis's provider-validity gate (elevation/mod.rs:369, threshold 0.5). */
   public static native double nanRatio(double[] grid, int count);

   /** Overload that reads {@code grid.length} itself, for the call sites that pass a whole raster. */
   public static double nanRatio(double[] grid) {
      return grid == null ? 1.0 : nanRatio(grid, grid.length);
   }

   /** arnis's provider-validity threshold: more than half the samples missing means "this source failed". */
   public static final double NAN_RATIO_REJECT_THRESHOLD = 0.5;

   /**
    * 2026-10-05: physically possible elevation bounds, in metres.
    *
    * <p>The upper bound is arnis's {@code MAX_REASONABLE_M}. Everest is 8848.86 m; the margin
    * covers providers that report ellipsoidal rather than orthometric heights.
    *
    * <p>★ The lower bound is **deliberately not arnis's {@code -500.0}**. arnis does not model
    * ocean depth at all — {@code whole_earth()} uses {@code LOWEST_LAND_M = -430.0}, the Dead Sea
    * shore, as the lowest point on the planet, so a {@code -500} gate is consistent there. We **do**
    * have a real sea floor ({@code DEM_NODATA_METERS = -12000} is a deliberate ocean floor; the
    * deepest point is -10994 m), and copying -500 would classify **the entire ocean** as missing
    * and then fill it with neighbourhood means — a fabricated abyssal plain.
    */
   public static final double MIN_REASONABLE_METERS = -11000.0;
   public static final double MAX_REASONABLE_METERS = 9000.0;
}
