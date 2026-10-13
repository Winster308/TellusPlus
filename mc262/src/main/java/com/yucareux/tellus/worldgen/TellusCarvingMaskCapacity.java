package com.yucareux.tellus.worldgen;

/**
 * The carving-mask capacity the mod allows, in one place.
 *
 * <h2>Why this is not inside the mixin</h2>
 * {@code CarvingMaskCapacityMixin} needs this number to cap the {@code BitSet} that
 * {@code CarvingMask} allocates, and {@code Tellus.probeCarvingMaskCapacity()} needs the same number
 * to verify the cap actually took effect. It used to live in the mixin as a
 * {@code public static} accessor, which is illegal: the Mixin framework rejects any mixin carrying a
 * non-private static method
 * ({@code InvalidMixinException: Mixin ... contains non-private static method}, raised from
 * {@code MixinApplicatorStandard.checkMethodVisibility}). The whole mixin was therefore refused at
 * runtime, so the cap never applied at all -- the opposite of what the accessor was added to prove.
 *
 * <p>Holding the value in a plain class keeps a single source of truth without asking the mixin to
 * expose anything.
 *
 * <h2>What the numbers mean</h2>
 * {@code CarvingMask} allocates a {@code BitSet} sized from the world's section count. With the
 * default layout that is 16,793,600 bits, about 2 MB per chunk, for a carving band that only ever
 * covers the noise window. Capping it to the sections below {@link #NOISE_WINDOW_TOP_Y} is what keeps
 * carving masks from dominating memory.
 */
public final class TellusCarvingMaskCapacity {

   /** Bits in one 16-block section: 16 x 16 x 16. */
   public static final int BITS_PER_SECTION = 256;

   /**
    * Top of the carving noise window.
    *
    * <p>Kept in step with the adapter that builds the carving noise; carving above this line does not
    * happen, so a mask sized past it is pure waste.
    */
   public static final int NOISE_WINDOW_TOP_Y = 2032;

   /** Sections from the real world bottom up to {@link #NOISE_WINDOW_TOP_Y}. */
   public static final int LAYOUT_SECTIONS = (NOISE_WINDOW_TOP_Y - TellusLayout.WORLD_MIN_Y + 15) / 16;

   /** The configured window height, same property and default as the adapter reads. */
   public static final int CONFIGURED_NOISE_HEIGHT =
      Math.max(16, Integer.getInteger("tellus.caves.noiseHeight", 1024) / 16 * 16);

   /**
    * Sections this mod will actually allow: the operator override if given, and never smaller than the
    * configured noise window. 817 with the 1:1 layout ({@code WORLD_MIN_Y = -11040}), giving
    * {@code 256 * 817 = 209,152} bit, about 26 KB per chunk.
    */
   public static final int MASK_SECTIONS = Math.max(
      Integer.getInteger("tellus.caves.maskSections", LAYOUT_SECTIONS),
      CONFIGURED_NOISE_HEIGHT / 16);

   /** The bit count the mixin will cap to, so a self-check can compare against the same expression. */
   public static int expectedBits() {
      return BITS_PER_SECTION * MASK_SECTIONS;
   }

   private TellusCarvingMaskCapacity() {
   }
}
