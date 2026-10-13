package com.yucareux.tellus.worldgen;

/**
 * <b>Region sharding, the foundation of the long term plan for breaking past the coordinate limit.</b>
 *
 * <h2>Why it is needed</h2>
 * MC packs block coordinates into a long: {@code X(26 bits) + Y(12 bits) + Z(26 bits) = 64 bits} (an exact fit).
 * The hard limit on world coordinates is therefore {@code +/-33,554,431} (Y is {@code +/-2048}), which is the
 * physical origin of the "distance phenomenon" and of the 30 million block world border. Widening the fields
 * does not fit (it would need 76 bits) and changing the key type conflicts with every other mod.
 *
 * <h2>The region sharding approach</h2>
 * Cut "Earth" into a number of <b>Regions</b> (see {@link #REGION_SIZE}), each carried by its own dimension
 * and using <b>local coordinates</b> inside (all inside a safe range). When a player crosses a region border,
 * the mod teleports them seamlessly to the matching local coordinate of the neighbouring region.
 *
 * <p>The result: it looks like an infinite world on the outside, while every coordinate stays legal on the
 * inside, so the engine coordinate format is untouched (which means <b>every mod keeps working</b>) and Earth
 * does not have to be squeezed into one coordinate range.
 *
 * <h2>Coordinate conversion (this class only does the mapping; carrying the dimensions comes in later stages)</h2>
 * <pre>
 * regionIndex(worldX) = floorDiv(worldX - CENTER, REGION_SIZE)
 * localX(worldX)      = worldX - regionIndex * REGION_SIZE      in [-REGION_SIZE/2, +REGION_SIZE/2)
 * </pre>
 * {@code REGION_SIZE} is 2^24 = 16,777,216 blocks: local coordinates reach at most +/-8.388 million,
 * leaving a factor of 4 of headroom below the +/-33.55 million hard limit, plenty for buildings and structures.
 */
public final class TellusRegions {

   /** Edge length of a single region in blocks. 2^24, so local coordinates reach at most +/-8,388,608. */
   public static final int REGION_SIZE =
      Integer.highestOneBit(EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE);

   /** Half the local coordinate width, to make the conversion easy. */
   public static final int REGION_HALF = REGION_SIZE / 2;

   private TellusRegions() {
   }

   /**
    * World coordinate to region index.
    *
    * <p>Deliberately uses {@code floorDiv} instead of {@code /}: negative values must also round towards
    * negative infinity, otherwise -1 and +1 land in the same region (a bug at the border).
    */
   public static int indexOf(double worldCoordinate) {
      return (int) Math.floor((worldCoordinate - REGION_HALF) / REGION_SIZE + 1.0);
   }

   /** World coordinate to the local coordinate inside that region (always within +/-REGION_SIZE/2). */
   public static double localOf(double worldCoordinate) {
      return worldCoordinate - (double) indexOf(worldCoordinate) * REGION_SIZE;
   }

   /** Local coordinate plus region index, back to a world coordinate. */
   public static double worldOf(int regionIndex, double localCoordinate) {
      return localCoordinate + (double) regionIndex * REGION_SIZE;
   }

   /** Whether the local coordinate really is inside the safe range (should always be true, used as a self check). */
   public static boolean localIsSafe(double localCoordinate) {
      return Math.abs(localCoordinate) <= EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE;
   }

   /** Stable identifier of a region (used for the dimension name, the save directory and the map cell): {@code tellus_region_<x>_<z>}. */
   public static String regionId(int regionX, int regionZ) {
      return "tellus_region_" + regionX + "_" + regionZ;
   }

   /** A one line description for commands and logs. */
   public static String describe(int blockX, int blockZ) {
      int rx = indexOf(blockX);
      int rz = indexOf(blockZ);
      double lx = localOf(blockX);
      double lz = localOf(blockZ);
      return String.format(java.util.Locale.ROOT,
            "%s  world(%d,%d) -> region(%d,%d) local(%.0f,%.0f)%s",
            regionId(rx, rz), blockX, blockZ, rx, rz, lx, lz,
            localIsSafe(lx) && localIsSafe(lz) ? "" : "  <- local coordinates out of the safe range (should not happen)");
   }
}
