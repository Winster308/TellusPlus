package com.yucareux.tellus.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * World height and coordinate layout: the single on/off switch for "how tall, how far".
 *
 * <h2>Conclusion first (this judgement was corrected once)</h2>
 * I first assumed that "{@code BlockPos.asLong} squeezes Y into 12 bits" was the wall for the whole
 * engine, so I repacked the bit layout, paying with a horizontal range cut to 1/4~1/8.
 * The audit showed that judgement was exaggerated:
 *
 * <ul>
 *   <li>{@code BlockPos.hashCode/equals} are inherited from {@code Vec3i} and computed per component,
 *       so every {@code Map<BlockPos,...>} / {@code Set<BlockPos>} (block entities, block entity tickers,
 *       and so on) was never affected by packing;</li>
 *   <li>scheduled ticks use {@code Set<ScheduledTick>} keyed by chunk (ChunkPos, 32 bits each for X and Z)
 *       and never involve Y;</li>
 *   <li>light and sections use {@link net.minecraft.class_4076} with 22/20/22 bits, so Y has 20 bits
 *       = +/-524,288 sections = +/-8.38 million blocks;</li>
 *   <li>only 5 to 6 client-side cache classes in the whole engine actually compress block coordinates
 *       into long keys ({@code BlockTintCache}, {@code BlockStatePredictionHandler}, {@code LevelRenderer},
 *        {@code SectionOcclusionGraph}, {@code LightDebugRenderer}).</li>
 * </ul>
 *
 * <h2>So the correct approach</h2>
 * <b>Do not touch the packing format, only raise the dimension</b>:
 * <ul>
 *   <li>height: decided by {@code -Dtellus.world.top=NNNN} (default 2047 = vanilla);</li>
 *   <li>horizontal: keep vanilla +/-33,554,431 (no repacking);</li>
 *   <li>side effect: once Y passes {@link #FIRST_ALIASING_Y} ({@code WORLD_MIN_Y} + 4096, i.e.
 *       2064 with the default {@code minY = -2032}) those 5 to 6 client caches alias positions,
 *       producing occasional minor visual artifacts up high (tinting or occlusion), which does not
 *       affect world data; if you want "absolutely no aliasing" turn on
 *       {@code -Dtellus.layout.repack=true} (cost: the horizontal range shrinks to 1/4~1/8).</li>
 * </ul>
 *
 * <h2>Where the height cap comes from</h2>
 * <ol>
 *   <li>the explicit {@code DimensionType} check ({@code min_y + height <= MAX_Y + 1}), relaxed by
 *       {@code DimensionTypeMaxYMixin};</li>
 *   <li>{@code SectionPos} gives Y 20 bits, so the top reaches at most +/-8.38 million blocks
 *       (far beyond any real need);</li>
 *   <li>sections per chunk = height/16 (65536 tall gives 4096), sparsified by {@link SparseSections}.</li>
 * </ol>
 */
public final class TellusLayout {

   private static final Logger LOGGER = LoggerFactory.getLogger("TellusCN");
   private static final int ALIGN = 16;

   /**
    * Requested top Y (not snapped). Default is 65535 (Y in [-64, 65535], total height 65600, as the
    * user asked for).
    *
    * <p>How to solve the 2 MB chunk packet cap is described in {@link EarthGeneratorSettings#LOGICAL_HEIGHT_CAP}:
    * squeeze the sky light computation range down to 512 sections (packet is about 1 MB) instead of cutting the height.
    * If the client still reports `Chunk Packet trying to allocate too much memory on read`,
    * fall back to {@code -Dtellus.skylight.off=true}; only lower the height as a last resort
    * ({@code -Dtellus.world.top=4095}).
    *
    * <p>Meaning: without any parameter, a newly created world spans Y in [-64, 65535], total height 65600
    * blocks (4100 sections). Pass 2047 to go back to vanilla height: {@code -Dtellus.world.top=2047}.
    * <p>Note: this makes {@link #TALL} default to true, so sparse sections and the matching optimisations
    * are active by default.
    */
   private static final int REQ_TOP_Y = Integer.getInteger("tellus.world.top", 65535);

   /** The world is taller than vanilla (top &gt; 2047), so sparse sections and friends are needed. Unrelated to repacking. */
   public static final boolean TALL = REQ_TOP_Y > 2047;

   /**
    * World bottom Y.
    *
    * <p><b>2026-09-28: the default changed to -2032</b> (it used to be -64 when TALL).
    *
    * <p>Why: the Mariana Trench at -11034 m needs -11034 blocks at a 1:1 scale, but with a world bottom of
    * only -64 the whole deep sea area is clamped to the same Y, which gives a flat world bottom plane,
    * which forms square pits at chunk boundaries, which is exactly the square holes players reported.
    * Using the full -2032 (also the Minecraft dimension floor and also the baseline the repack mode requires)
    * gives the deep sea room to exist.
    *
    * <p>Note: once minY changes, the world total height and the sections per chunk change too, so a
    * new world must be created.
    *
    * <p>To restore the old behaviour: {@code -Dtellus.world.minY=-64}.
    *
    * <p>History: the default once became -64, which made "automatically inferred height" jump from 480 to 2112
    * (the world mysteriously got 4 times taller, 132 sections per chunk, and the client could not join).
    * That happened together with the old Everest warning; now the ocean depth adapts on its own, so raising
    * minY as a fallback is no longer needed.
    */
   public static final int WORLD_MIN_Y = snapBottom(
      Integer.getInteger("tellus.world.minY", -2032));

   /**
    * Whether the {@code BlockPos} bit layout is repacked (widening the Y field beyond 12 bits).
    * Off by default.
    *
    * <p>See the class doc: a tall world works without it and the horizontal range stays at
    * +/-33.55 million; turning it on buys exactly one thing, "positions never alias up high".
    */
   public static final boolean PACKING_REPACKED = Boolean.getBoolean("tellus.layout.repack");

   /** Y field width when repacking (12 to 24, chosen for the needed span). */
   private static final int REPACK_BITS_Y = resolveBits(REQ_TOP_Y, WORLD_MIN_Y);
   /** How many bits Y takes (12 when not repacking, same as vanilla). */
   public static final int BITS_Y = PACKING_REPACKED ? REPACK_BITS_Y : 12;

   /**
    * Horizontal field widths, one per axis.
    *
    * <h2>2026-10-10: asymmetric X/Z, and odd widths are now allowed</h2>
    * This used to be a single {@code (64 - BITS_Y) / 2} shared by both axes. That forced two
    * constraints that turn out to be unnecessary, and together they made true 1:1 impossible:
    * <ul>
    *   <li><b>even</b> widths only — {@code (64 - BITS_Y)} has to divide by 2, and {@code evenBits()}
    *       also rounded up, so Y could only be 12/14/16/18…;</li>
    *   <li><b>X and Z equal</b> — even though latitude only spans ±90° ({@code z} max 10,018,754) while
    *       longitude spans ±180° ({@code x} max 20,037,508). Z was handed the same width as X and simply
    *       wasted it.</li>
    * </ul>
    * The measured consequence at {@code minY = -2032}:
    * <pre>
    *   Y=14 -> span 16384 &lt; 19882  (trench -11034 .. Everest +8848) -> the deep sea cannot fit
    *   Y=16 -> horizontal ±75.4°   -> Guangzhou (113.7°E) is unreachable
    * </pre>
    * i.e. under the old rule there was <b>no</b> setting that gives both a 1:1 vertical span and a
    * usable longitude. Dropping the two constraints gives exactly the 64 bits:
    * <pre>
    *   X 25 + Z 24 + Y 15 = 64
    *   X 25 -> ±150.7°  (Guangzhou, Everest, Mariana, New York, London all inside)
    *   Z 24 -> ±75.4°   (every continent; only the polar caps are out)
    *   Y 15 -> 32768 blocks -> with minY = -11040: trench -11034 (6 spare) and Everest 8848 (12879 spare)
    * </pre>
    * <b>True 1:1 both ways</b>: one block is one metre vertically, and the whole ocean fits.
    *
    * <p>Still budgeted strictly against 64 bits: {@code BITS_X + BITS_Y + BITS_Z <= 64}; if the requested
    * span needs more Y than leaves room, the horizontal widths are trimmed (see {@link #trimHorizontal}).
    */
   public static final int BITS_X = PACKING_REPACKED ? resolveBitsX(REPACK_BITS_Y) : 26;
   /** Z holds latitude only (±90°), so it can safely be one bit narrower than X where X is tight. */
   public static final int BITS_Z = PACKING_REPACKED ? Math.min(BITS_X, resolveBitsZ(REPACK_BITS_Y)) : 26;

   /** Top cap: without repacking it is bounded by {@code SectionPos} Y (20 bits, +/-8.38 million blocks). */
   private static final int TOP_CAP = PACKING_REPACKED ? Integer.MAX_VALUE : 8_388_607;
   /** World top: snapped to 16n+15 (both min_y and height must be 16 aligned, so the top is always 15 mod 16). */
   public static final int WORLD_TOP_Y = snapTop(Math.min(REQ_TOP_Y, TOP_CAP));

   /** Maximum total height = top - bottom + 1 (both ends 16 aligned, so it is always a multiple of 16). */
   public static final int MAX_HEIGHT = WORLD_TOP_Y + 1 - WORLD_MIN_Y;
   /** Hard horizontal coordinate limit (16 blocks kept for chunk alignment). */
   public static final int MAX_BLOCK_COORDINATE = (1 << (BITS_X - 1)) - 16;
   /** Latitude limit implied by {@link #BITS_Z}; may be narrower than {@link #MAX_BLOCK_COORDINATE}. */
   public static final int MAX_BLOCK_COORDINATE_Z = (1 << (BITS_Z - 1)) - 16;

   private static final long MASK_X = (1L << BITS_X) - 1L;
   private static final long MASK_Z = (1L << BITS_Z) - 1L;
   private static final long MASK_Y = (1L << BITS_Y) - 1L;
   /**
    * 2026-10-05: the first world Y whose position cannot be packed uniquely.
    *
    * <p>{@link #packY} stores {@code (y - WORLD_MIN_Y) & MASK_Y} and {@link #unpackY} adds
    * {@code WORLD_MIN_Y} back, so a 12-bit field spans exactly 4096 blocks measured from
    * {@link #WORLD_MIN_Y}. With the default {@code minY = -2032} that is {@code -2032 + 4096 = 2064},
    * <b>not</b> the 2047 the docs and the start-up log used to claim (2047 is only correct for
    * {@code minY = -2048}). Computed here so the message can never drift from the actual packing
    * again, and so it stays right if {@code -Dtellus.world.minY} changes.
    */
   public static final int FIRST_ALIASING_Y = (int) (WORLD_MIN_Y + MASK_Y + 1L);
   /** Field order matches vanilla (X highest, Z in the middle, Y lowest). Only used when repacking. */
   private static final int SHIFT_Z = PACKING_REPACKED ? BITS_Y : 12;
   private static final int SHIFT_X = PACKING_REPACKED ? BITS_Y + BITS_Z : 38;

   static {
      LOGGER.info("[TellusCN] layout limits: world height {} blocks (Y range [{}, {}]), horizontal +/-{} (X) / +/-{} (Z), {} sections per chunk, packing {}"
            + " (the height actually in effect for this save is what the world creation screen and the world settings line below say)",
            MAX_HEIGHT, WORLD_MIN_Y, WORLD_TOP_Y, MAX_BLOCK_COORDINATE, MAX_BLOCK_COORDINATE_Z, MAX_HEIGHT / ALIGN,
            PACKING_REPACKED
                  ? ("repacked X" + BITS_X + "/Y" + BITS_Y + "/Z" + BITS_Z + " = " + (BITS_X + BITS_Y + BITS_Z) + "/64 bits")
                  : "vanilla 26/12/26");
      if (PACKING_REPACKED && BITS_X + BITS_Y + BITS_Z > 64) {
         // Should be impossible (the widths are trimmed against 64 in trimHorizontal), but a silent
         // over-budget layout would alias coordinates, so make any future regression loud.
         LOGGER.error("[TellusCN] repacked layout over budget: X{} + Y{} + Z{} = {} > 64 bits; positions WILL alias.",
               BITS_X, BITS_Y, BITS_Z, BITS_X + BITS_Y + BITS_Z);
      }
      if (TALL) {
         LOGGER.warn("[TellusCN] super tall world is enabled: please create the world with this mod's world creation screen"
                     + " (on a dedicated server the dimension height cannot be changed, this is only a log hint)."
                     + "Above Y=" + FIRST_ALIASING_Y + " a few client caches alias positions, which causes occasional visual artifacts at high altitude;"
                     + " if you need strictly unique positions, use -Dtellus.layout.repack=true (the horizontal range shrinks accordingly)."
                     + " Sections per chunk grow linearly with height; watch memory at large view distances (sparsification is on).");
      }
   }

   private TellusLayout() {
   }

   /** Snap the top down to 16n+15. */
   private static int snapTop(int top) {
      return top - Math.floorMod(top + 1, ALIGN);
   }

   /** Snap the bottom down to a multiple of 16 (downward only, so it can never cut away a bottom you asked for). */
   private static int snapBottom(int bottom) {
      return bottom - Math.floorMod(bottom, ALIGN);
   }

   /** Clamp to 12 to 24, **without** forcing even. Odd widths are legal now (15 is the 1:1 case). */
   private static int clampBits(int bits) {
      return Math.max(12, Math.min(24, bits));
   }

   /**
    * Legacy helper kept for compatibility: clamp to 12..24 and round **up** to even.
    *
    * <p>Only used by {@code -Dtellus.layout.bitsY=N}; the automatic path no longer needs even widths
    * (see {@link #BITS_X}). Kept because a user-supplied odd value is still rounded to the even number
    * that covers the span, which is what "N bits" meant historically.
    */
   private static int evenBits(int bits) {
      return (clampBits(bits) + 1) & ~1;
   }

   /**
    * Choose the repacked Y field width (only used when {@link #PACKING_REPACKED} is on).
    * Default strategy: let the top give up a little to save bits (horizontal widens);
    * {@code -Dtellus.layout.strictTop=true} switches to "the top must be reached",
    * and {@code -Dtellus.layout.bitsY=N} sets it directly.
    */
   private static int resolveBits(int reqTopY, int minY) {
      Integer explicit = Integer.getInteger("tellus.layout.bitsY");
      if (explicit != null) {
         return evenBits(explicit);
      }
      boolean strict = Boolean.getBoolean("tellus.layout.strictTop");
      int tolerance = strict ? 0 : 2048;
      for (int bits = 12; bits <= 24; bits++) {
         if (reqTopY - tolerance <= snapTop(minY + (1 << bits) - 1)) {
            return bits;
         }
      }
      return 24;
   }

   /**
    * Bits for the X (longitude) field: whatever is left after Y and Z, capped at 26.
    *
    * <p>Longitude spans ±180°, so X is the widest axis and the one worth protecting
    * ({@code blocksPerDegree(1.0) ≈ 111319.5} → ±180° needs 20,037,508 blocks ≈ 25 bits).
    */
   private static int resolveBitsX(int bitsY) {
      Integer explicit = Integer.getInteger("tellus.layout.bitsX");
      int wanted = explicit != null ? clampBits(explicit) : 64 - bitsY - 24;
      return trimHorizontal(Math.max(12, wanted), bitsY);
   }

   /**
    * Bits for the Z (latitude) field: latitude only spans ±90°, i.e. 10,018,754 blocks ≈ 24 bits,
    * so Z is capped at 24 even when X has more room. That saved bit is exactly what pays for Y=15.
    */
   private static int resolveBitsZ(int bitsY) {
      Integer explicit = Integer.getInteger("tellus.layout.bitsZ");
      int wanted = explicit != null ? clampBits(explicit) : 24;
      return trimHorizontal(Math.max(12, wanted), bitsY);
   }

   /**
    * Keep {@code bitsX + bitsY + bitsZ <= 64} by trimming the widest horizontal axis first.
    *
    * <p>The budget is what makes this layout correct: all three fields live in one {@code long}.
    * Y is never trimmed here (the caller derived it from the requested world top, and cutting it would
    * silently clamp the world height); the horizontal axes give up bits instead, which only narrows
    * how far from the origin the world reaches.
    */
   private static int trimHorizontal(int wanted, int bitsY) {
      int room = 64 - bitsY;
      // Leave at least 12 bits for the other horizontal axis (the clamp floor).
      int maxThis = Math.max(12, room - 12);
      return Math.min(Math.max(12, wanted), Math.min(26, maxThis));
   }

   /** Packing (same meaning as {@code BlockPos.asLong}, but using the current layout). Only called when repacking. */
   public static long pack(int x, int y, int z) {
      return ((long) x & MASK_X) << SHIFT_X
            | ((long) z & MASK_Z) << SHIFT_Z
            | (((long) y - WORLD_MIN_Y) & MASK_Y);
   }

   public static int unpackX(long packed) {
      // X sits at the very top, so an arithmetic shift right already yields the 25-bit signed value.
      return (int) (packed >> SHIFT_X);
   }

   /**
    * Unpack the Z (latitude) field.
    *
    * <h2>2026-10-10: the old formula was wrong and is replaced</h2>
    * It used to read
    * <pre>
    *   int drop = 64 - SHIFT_Z - BITS_Z;
    *   return (int) (packed &lt;&lt; drop &gt;&gt; drop);
    * </pre>
    * which keeps bits {@code [drop, 63]} — i.e. the Z field <b>plus every bit of the X field above it</b>.
    * The trailing bits of X therefore leaked into Z. With the old 25/14/25 layout the error measured
    * as much as tens of millions of blocks: a round-trip of {@code z = -4567} came back as
    * {@code -74823632}, and even {@code z = 0} came back as {@code 2032}.
    *
    * <p>It went unnoticed because {@code PACKING_REPACKED} is off by default and the repacked path only
    * feeds a handful of client-side caches, so it surfaced as occasional visual artifacts rather than
    * as corrupt world data — which is exactly what the class doc warned about.
    *
    * <p>The correct read is: shift the field down to bit 0, mask it to its own width, then sign-extend.
    */
   public static int unpackZ(long packed) {
      long field = (packed >> SHIFT_Z) & MASK_Z;
      int shift = 64 - BITS_Z;
      return (int) (field << shift >> shift);
   }

   public static int unpackY(long packed) {
      return (int) (packed & MASK_Y) + WORLD_MIN_Y;
   }
}
