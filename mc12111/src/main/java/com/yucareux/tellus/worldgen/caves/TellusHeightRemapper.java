package com.yucareux.tellus.worldgen.caves;

import net.minecraft.util.Mth;

/**
 * Converts a "vanilla Y" into a Tellus world Y.
 *
 * <p>Reference frame: the vanilla overworld {@code minY=-64, height=384, sea level y=64}.
 *
 * <h2>Why it must be anchored to the sea level instead of scaled proportionally to the world height</h2>
 *
 * <p>The old implementation was the proportional map {@code -64 + (y+64)/384 * tellusHeight}.
 * But <b>Tellus' original world height was 384</b> (see {@code EarthGeneratorSettings.DEFAULT}:
 * {@code worldScale=30, heightScale=1, heightOffset=64}, which puts Everest 8848 m at Y=359),
 * so the proportional map degenerated into the <b>identity map</b> (y to y). That is why the bug never
 * showed up.
 *
 * <p>Everything breaks as soon as the world gets taller: at height 65600, the vanilla cave ceiling y=220
 * is sent to {@code -64 + 284/384*65600, about 48454}, while Everest at scale 2.09 is only Y 4298.
 * The consequences are twofold: first, all caves end up hanging in the <b>empty high sky</b>, so there is not
 * a single hole in the terrain (the feature stops working); second, the carver is forced to work over a
 * 70,000 block vertical span, which wastes performance.
 *
 * <h2>The correct anchoring</h2>
 *
 * <p>Caves sit just above and below the sea level: the vanilla sea level is y=64 and the cave ceiling 220,
 * that is +156. Converted to Tellus that is {@code seaLevel + 156}, so no matter how tall the world is,
 * the caves land in the real terrain.
 *
 * <p><b>Equivalence proof</b>: with {@code height=384, minY=-64, seaLevel=64} the formula becomes
 * {@code 64 + (y-64) = y}, which is bit for bit identical to the old implementation
 * (y=-64 gives -64, y=220 gives 220, y=319 gives 319), so there is zero behaviour change for the original
 * configuration.
 *
 * <p>{@code -Dtellus.caves.stretch=F} scales the offset globally (the default 1.0 keeps the vanilla ratio).
 */
final class TellusHeightRemapper {
   private static final int VANILLA_MIN_Y = -64;
   private static final int VANILLA_HEIGHT = 384;
   private static final int VANILLA_SEA_LEVEL = 64;

   private TellusHeightRemapper() {
   }

   /**
    * Legacy signature entry point (other branch modules still use it): the sea level is derived as
    * {@code minY + 128}, which yields exactly 64 for {@code minY=-64}, matching the result of passing the
    * sea level explicitly.
    */
   static int remapVanillaAbsolute(int vanillaAbsoluteY, int tellusMinY, int tellusHeight) {
      return remapVanillaAbsolute(
         vanillaAbsoluteY, tellusMinY, tellusHeight,
         tellusMinY + (VANILLA_SEA_LEVEL - VANILLA_MIN_Y)
      );
   }

   /**
    * Converts a vanilla absolute Y into a Tellus Y.
    *
    * @param vanillaAbsoluteY vanilla overworld coordinate (sea level 64)
    * @param tellusMinY       lowest Y of the Tellus world (default -64)
    * @param tellusHeight     total height of the Tellus world (default 65600)
    * @param tellusSeaLevel   sea level of the Tellus world (default 64)
    * @return the Tellus Y after the sea level anchored conversion, clamped into the world bounds
    */
   static int remapVanillaAbsolute(int vanillaAbsoluteY, int tellusMinY, int tellusHeight, int tellusSeaLevel) {
      int tellusMaxY = tellusMinY + tellusHeight - 1;
      double stretch = stretch();
      int remapped = tellusSeaLevel + Mth.floor((vanillaAbsoluteY - VANILLA_SEA_LEVEL) * stretch);
      return Mth.clamp(remapped, tellusMinY, tellusMaxY);
   }

   /**
    * Stretch factor for the offset, {@code -Dtellus.caves.stretch=F}, default 1.0 (the vanilla ratio).
    *
    * <p>Also changed to {@code static final}: the property lookup and parsing now happen once at class load
    * time instead of on every conversion, since this method is called several times while a carver is being
    * instantiated.
    */
   private static final double STRETCH = readStretch();

   private static double readStretch() {
      String raw = System.getProperty("tellus.caves.stretch");
      if (raw == null) {
         return 1.0;
      }
      try {
         double value = Double.parseDouble(raw.trim());
         return value > 0.0 && Double.isFinite(value) ? value : 1.0;
      } catch (NumberFormatException ignored) {
         return 1.0;
      }
   }

   private static double stretch() {
      return STRETCH;
   }
}
