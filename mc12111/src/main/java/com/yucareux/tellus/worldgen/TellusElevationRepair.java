package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;

/**
 * 2026-10-05: the elevation-repair pipeline, with the native kernel as an optional accelerator.
 *
 * <p><b>Provenance.</b> Ported from arnis (github.com/louis-e/arnis, Apache-2.0):
 * {@code elevation/postprocess.rs} and {@code elevation/mod.rs}. See {@code THIRD_PARTY_NOTICES.md}.
 * The four algorithms are transcriptions, not rewrites; the constants and the acceptance criteria
 * are copied verbatim, and every place we deliberately deviate is written out at the deviation.
 *
 * <p><b>Why these four.</b> Two gaps in what we already had:
 * <ol>
 *   <li>Grepping the entire {@code world/data/elevation} package for
 *       {@code outlier|spike|MAD|IQR|despeckle} returns exactly <b>one comment hit</b>. We have no
 *       denoising at all, so a single bad DEM pixel becomes a single-column spike in the world.</li>
 *   <li>{@code DEM_NODATA_METERS = -12000} bounds elevation from <b>below only</b>. A white Terrarium
 *       pixel is {@code 255*256 + 255 + 255/256 - 32768 = 32767.996} m, so {@code Math.round} gives
 *       32768 and {@code (short) 32768} wraps to {@code Short.MIN_VALUE} — which is <b>exactly</b>
 *       {@code MapterhornElevationSource}'s {@code NO_DATA} sentinel. One corrupt pixel gets aliased
 *       into the very value that means "no elevation here", and is then filled in as if it were
 *       missing data.</li>
 * </ol>
 *
 * <p><b>Why the Java mirror exists.</b> The DLL is loaded opportunistically and may be absent, stale
 * or a different version than this jar; every method here therefore has a complete Java
 * implementation and the native path is only ever a speed-up. The two must agree — the pure-Java
 * path is written to the same arithmetic (same thresholds, same Jacobi snapshot, same
 * "fewer than 8 finite neighbours means no evidence" rule), not to a different interpretation.
 */
public final class TellusElevationRepair {
   private static final String PERF = "[TellusCN] elevation repair";

   private TellusElevationRepair() {
   }

   /**
    * Fraction of non-finite cells; every non-finite value counts as missing.
    *
    * <p>This is the gate that treats "HTTP 200 but the payload is an empty tile" as a
    * <b>failure</b> instead of a success. Our circuit breaker counts timeouts, cancellations and
    * exceptions ({@code TellusDownloadExecutor}); a well-formed response carrying nothing but
    * no-data is invisible to it, so it is counted as completed, cached, and then sampled into a
    * field of NaN — which {@code fillMissingTerrainShellHeights} then turns into one invented
    * height for the whole chunk. That is the square plateau.
    */
   public static double nanRatio(double[] grid) {
      if (grid == null || grid.length == 0) {
         return 1.0;
      }
      if (TellusNative.isAvailable()) {
         double ratio = TellusNative.nanRatio(grid, grid.length);
         // Negative is the JNI "give up" return; fall through to Java rather than trusting it.
         if (ratio >= 0.0) {
            return ratio;
         }
      }
      int missing = 0;
      for (double value : grid) {
         if (!Double.isFinite(value)) {
            missing++;
         }
      }
      return (double)missing / grid.length;
   }

   /** Whether a payload this bad should be treated as a failed fetch and routed to the next source. */
   public static boolean isUnusablePayload(double[] grid) {
      return nanRatio(grid) > TellusNative.NAN_RATIO_REJECT_THRESHOLD;
   }

   /**
    * NaN out physically impossible elevations.
    *
    * @return the number of cells rewritten
    */
   public static int filterElevationOutliers(double[] grid) {
      if (grid == null || grid.length == 0) {
         return 0;
      }
      if (TellusNative.isAvailable()) {
         int filtered = TellusNative.filterElevationOutliers(grid, grid.length);
         if (filtered >= 0) {
            return filtered;
         }
      }
      int filtered = 0;
      for (int i = 0; i < grid.length; i++) {
         double h = grid[i];
         if (!Double.isNaN(h) && (h < TellusNative.MIN_REASONABLE_METERS || h > TellusNative.MAX_REASONABLE_METERS)) {
            grid[i] = Double.NaN;
            filtered++;
         }
      }
      return filtered;
   }

   /**
    * 5x5 median + MAD despike, ported from arnis {@code postprocess.rs:56}.
    *
    * <p>Constants copied verbatim:
    * <pre>
    * RADIUS          = 2                (5x5 window, 24 neighbours)
    * RELATIVE_FACTOR = 3.0              (deviation must exceed 3 x MAD)
    * abs_threshold   = max(6.0, 0.25 * mPerCell)
    * passes          = if mPerCell &gt; 4.0 { 2 } else { 10 }
    * MIN_NEIGHBORS   = 8
    * </pre>
    *
    * <p>Each pass reads a snapshot taken at the top of the pass and writes only the inner cells
    * ({@code RADIUS .. size - RADIUS}). Two consequences that matter here:
    * <ul>
    *   <li>The border ring is <b>never</b> written. Under streaming generation that is required:
    *       a cell on the edge is another chunk's interior, and two chunks writing the same cell
    *       with different data is how seams appear.</li>
    *   <li>Multi-cell artefact clusters are eroded from the outside in — a later pass repairs
    *       boundary pixels that by then have enough normal neighbours.</li>
    * </ul>
    *
    * @param mPerCell metres per grid cell; keeps the erosion reach at a constant physical scale
    * @return the number of cells repaired
    */
   public static int repairTerrainAnomalies(double[] grid, int width, int height, double mPerCell) {
      if (grid == null || width < 5 || height < 5 || grid.length < width * height) {
         return 0;
      }
      if (TellusNative.isAvailable()) {
         int repaired = TellusNative.repairTerrainAnomalies(grid, width, height, mPerCell);
         if (repaired >= 0) {
            return repaired;
         }
      }
      final int radius = 2;
      final double relativeFactor = 3.0;
      final int minNeighbors = 8;
      final double absThreshold = Math.max(6.0, 0.25 * mPerCell);
      final int passes = mPerCell > 4.0 ? 2 : 10;

      double[] snapshot = grid.clone();
      int totalRepaired = 0;
      double[] neighbors = new double[24];
      double[] absDevs = new double[24];
      for (int pass = 0; pass < passes; pass++) {
         if (pass > 0) {
            System.arraycopy(grid, 0, snapshot, 0, width * height);
         }
         int repairedThisPass = 0;
         for (int y = radius; y < height - radius; y++) {
            for (int x = radius; x < width - radius; x++) {
               int cell = y * width + x;
               double center = snapshot[cell];
               if (!Double.isFinite(center)) {
                  continue;
               }
               int count = 0;
               double lo = Double.POSITIVE_INFINITY;
               double hi = Double.NEGATIVE_INFINITY;
               for (int dy = -radius; dy <= radius; dy++) {
                  int ny = y + dy;
                  int rowBase = ny * width;
                  for (int dx = -radius; dx <= radius; dx++) {
                     if (dy == 0 && dx == 0) {
                        continue;
                     }
                     double v = snapshot[rowBase + x + dx];
                     if (Double.isFinite(v)) {
                        neighbors[count++] = v;
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                     }
                  }
               }
               if (count < minNeighbors) {
                  continue;
               }
               // The median lies inside the neighbours' range, so a centre within the threshold
               // of both ends cannot deviate past it. Most terrain is that smooth, and this skips
               // both sorts there.
               if (center - lo <= absThreshold && hi - center <= absThreshold) {
                  continue;
               }
               double median = medianOf(neighbors, count);
               double mad = 0.0;
               for (int i = 0; i < count; i++) {
                  absDevs[i] = Math.abs(neighbors[i] - median);
               }
               mad = medianOf(absDevs, count);
               double deviation = Math.abs(center - median);
               if (deviation > absThreshold && deviation > relativeFactor * Math.max(mad, 1.0)) {
                  grid[cell] = median;
                  repairedThisPass++;
               }
            }
         }
         if (repairedThisPass == 0) {
            break;
         }
         totalRepaired += repairedThisPass;
      }
      return totalRepaired;
   }

   /** Partially sorts {@code values[0..count)} and returns the middle value. */
   private static double medianOf(double[] values, int count) {
      java.util.Arrays.sort(values, 0, count);
      return values[count / 2];
   }

   /**
    * 3x3 neighbourhood-mean iterative hole filling, ported from arnis {@code postprocess.rs:2530}.
    *
    * <p>Three things copied verbatim:
    * <ol>
    *   <li><b>A snapshot per iteration.</b> Without it, scan order leaks into the result — the
    *       first row of a hole is filled from real data and the last row then reads that fill, so
    *       the filled surface tilts. With the snapshot every cell of the hole sees the same
    *       inputs and comes out equal.</li>
    *   <li><b>Only fill cells with {@code count > 0}.</b> A cell whose 8 neighbours are all
    *       missing stays missing.</li>
    *   <li><b>Stop when nothing changed.</b> No fixed iteration cap, so no infinite loop.</li>
    * </ol>
    *
    * <p>★ <b>It cannot rescue a wholly empty block.</b> There every cell has zero finite
    * neighbours, the first iteration changes nothing and the loop exits with the grid untouched.
    * That is why {@link #isUnusablePayload} has to run first: decide whether the data is worth
    * anything <em>before</em> trying to repair it.
    *
    * @return the number of cells filled
    */
   public static int fillNanValues(double[] grid, int width, int height) {
      if (grid == null || width <= 0 || height <= 0 || grid.length < width * height) {
         return 0;
      }
      int area = width * height;
      int missingBefore = 0;
      for (int i = 0; i < area; i++) {
         if (!Double.isFinite(grid[i])) {
            missingBefore++;
         }
      }
      if (missingBefore == 0) {
         return 0;
      }
      if (TellusNative.isAvailable()) {
         int filled = TellusNative.fillNanValues(grid, width, height);
         if (filled >= 0) {
            return filled;
         }
      }
      double[] snapshot = grid.clone();
      for (int iteration = 0; iteration < area; iteration++) {
         boolean anyChanged = false;
         for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
               int cell = row + x;
               if (Double.isFinite(grid[cell])) {
                  continue;
               }
               double sum = 0.0;
               int count = 0;
               for (int dy = -1; dy <= 1; dy++) {
                  int ny = y + dy;
                  if (ny < 0 || ny >= height) {
                     continue;
                  }
                  int nRow = ny * width;
                  for (int dx = -1; dx <= 1; dx++) {
                     int nx = x + dx;
                     if (nx < 0 || nx >= width) {
                        continue;
                     }
                     double v = snapshot[nRow + nx];
                     if (Double.isFinite(v)) {
                        sum += v;
                        count++;
                     }
                  }
               }
               if (count > 0) {
                  grid[cell] = sum / count;
                  anyChanged = true;
               }
            }
         }
         if (!anyChanged) {
            break;
         }
         System.arraycopy(grid, 0, snapshot, 0, area);
      }
      int missingAfter = 0;
      for (int i = 0; i < area; i++) {
         if (!Double.isFinite(grid[i])) {
            missingAfter++;
         }
      }
      return missingBefore - missingAfter;
   }

   /** One-line summary for the periodic report; never logs on its own. */
   public static String describe(long rejected, long filtered, long filled, long despiked) {
      return PERF + ": rejectedTiles=" + rejected + " impossibleCells=" + filtered
         + " filledCells=" + filled + " despikedCells=" + despiked;
   }

   /** Records a rejected payload once, at debug level, so a bad source is traceable after the fact. */
   public static void recordRejected(String what, double ratio) {
      Tellus.LOGGER.warn(
         "[TellusCN] elevation payload rejected: {} has {:.0}% missing data (> {:.0}%), treating it as a failed fetch",
         what, ratio * 100.0, TellusNative.NAN_RATIO_REJECT_THRESHOLD * 100.0
      );
   }
}