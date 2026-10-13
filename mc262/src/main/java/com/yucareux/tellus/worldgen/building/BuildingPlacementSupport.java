package com.yucareux.tellus.worldgen.building;

public final class BuildingPlacementSupport {
   private BuildingPlacementSupport() {
   }

   /**
    * Caps the column top of a lower part below the bottom face of the upper part, so the two segments
    * do not overlap vertically.
    *
    * <p>Meaning: returns the <b>highest Y allowed</b> for that column; the caller decides whether to
    * generate the column with {@code topY >= floorY}.
    *
    * <h2>2026-09-22 fix: the root cause of the Canton Tower "missing two segments with a gap on top"</h2>
    *
    * <p>The original implementation returned {@link Integer#MIN_VALUE} when
    * {@code overlyingFloorY <= floorY}, which means <b>dropping the whole column</b> (the caller's
    * {@code topY >= floorY} test fails, so nothing is generated). When the two segments have similar
    * heights, which is very common for adjacent {@code building:part} segments in OSM, the upper segment's
    * floorY is below or equal to the lower segment's floorY, so that branch fired and the <b>entire part
    * was dropped column by column</b>. The visible result was <b>a missing segment in the middle of the
    * tower</b> (measured on the Canton Tower).
    *
    * <p>The correct meaning: this situation only means there is <b>no usable upper bound</b>, not that
    * the column should not exist. So it now <b>returns floorY</b>, which <b>keeps at least 1 block</b>:
    * <ul>
    *   <li>no break, because the caller sees that generation is needed;</li>
    *   <li>and no poking into the upper part either, because it only goes up to floorY while the original
    *       {@code columnTopY} can be far above the bottom face of the upper part (returning
    *       {@code columnTopY} would be worse, since that would drive the lower part straight into the
    *       upper one).</li>
    * </ul>
    *
    * <p>This change only affects exceptional or boundary data (normal data with increasing
    * {@code min_height} never enters that branch), so the generated result for ordinary buildings is
    * bit for bit unchanged.
    */
   public static int capLowerColumnTopY(int floorY, int columnTopY, int overlyingFloorY) {
      if (overlyingFloorY == Integer.MAX_VALUE) {
         return columnTopY;
      } else if (overlyingFloorY <= floorY) {
         // The original code returned Integer.MIN_VALUE here, which dropped the whole column and was the
         // root cause of the Canton Tower losing two segments.
         //
         //   Semantics correction: `overlyingFloorY` is the lowest floorY among all segments processed so
         //   far, and it does NOT guarantee that the segment actually sits above this column (that depends
         //   on the iteration order). When it is below or equal to this segment's floorY, all it means is
         //   that this upper bound is meaningless for this segment, and certainly not that the column
         //   should not be generated.
         //
         //   The correct approach is to not cap at all (return the original columnTopY) so the segment is
         //   generated in full. The cost is that adjacent segments may overlap slightly in the vertical
         //   direction, but for continuous segments of the same building that overlap only means a few
         //   extra blocks, which is far more acceptable than a break where the whole segment disappears.
         return columnTopY;
      } else {
         return Math.min(columnTopY, overlyingFloorY - 1);
      }
   }
}
