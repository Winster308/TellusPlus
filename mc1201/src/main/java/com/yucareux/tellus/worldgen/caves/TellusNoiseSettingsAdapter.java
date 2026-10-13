package com.yucareux.tellus.worldgen.caves;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.core.Holder;

public final class TellusNoiseSettingsAdapter {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("TellusCN");

   private TellusNoiseSettingsAdapter() {
   }

   public static Holder<NoiseGeneratorSettings> adaptToTellusHeight(Holder<NoiseGeneratorSettings> baseHolder, int tellusMinY, int tellusHeight, int seaLevel) {
      NoiseGeneratorSettings base = (NoiseGeneratorSettings)baseHolder.value();
      NoiseSettings baseNoise = base.noiseSettings();
      // Only adapt to a **narrow range**, not the full world height.
      //
      // These noise settings serve the **carving context only** (the `NoiseChunk.forChunk(...)` inside
      // `TellusVanillaCarverRunner`), and `NoiseChunk.forChunk` internally calls
      // `NoiseSettings.clampToHeightAccessor(chunk)`, which clamps the range to
      // `[max(setting.minY, chunk.minY), min(setting.minY+height, chunk.maxY+1))`.
      // So **the height here is the actual height of the NoiseChunk**, which in turn determines the
      // number of vertical cells of the `Aquifer$NoiseBasedAquifer` array.
      //
      // Why it must be narrow (two hard constraints):
      //  1) the aquifer optimisation in c2me (`MixinAquiferSamplerImpl` of `c2me-opts-worldgen-vanilla`)
      //     contains an `AssertionError("Array initialization")`: it reuses and pre-fills a long[] and
      //     requires every entry to still hold the sentinel value, while its sizing assumption is a
      //     **vertical span of at most 65535** (the bytecode holds `ldc int 65535`). Our 65600 does not
      //     match, so the array sizes disagree and the assertion blows up.
      //  2) the carver (after the fix) only works near the sea level: cave ceiling y=220 gives Y=220 and
      //     ravine floor y=24 gives Y=24, so **Y in [24, about 300] is enough**. (Before the fix it was
      //     computed as Y around 48452, which is what made the full height necessary; that bug is gone.)
      //
      // So it is squeezed to the default 1024 blocks (Y in [-64, 959]):
      //  * that covers the carving band [24, about 300] with more than 3 times the headroom
      //  * the aquifer cell count drops from 16400 to 256, a 64 times reduction, which is an
      //    optimisation for free
      //  * `-Dtellus.caves.noiseHeight=N` can tune it, and it must be raised together with
      //    `-Dtellus.caves.stretch`
      int noiseHeightRequested = Math.max(16, Integer.getInteger("tellus.caves.noiseHeight", 1024) / 16 * 16);
      // Two hard ceilings: the 2032 code check line and the 65535 of c2me (both are multiples of 16)
      int noiseCeiling = Math.min(2032, 65535);
      int noiseHeight = Math.max(16, Math.min(noiseHeightRequested, noiseCeiling - tellusMinY));
      // 2026-10 fix: the window used to start at the **world bottom**
      //   `noiseMinY = tellusMinY - floorMod(tellusMinY,16)` only "looked right" when minY=-64, while
      //   TellusLayout.WORLD_MIN_Y now defaults to -2032 (so the deep sea has room), and the window then
      //   became Y in [-2032, -1008] while the carving band sits **near the sea level** (Y about [24, 300]),
      //   so `NoiseSettings.clampToHeightAccessor` clamped the whole carving out of the noise range and
      //   **not a single hole could be carved**. It is now **placed centred on the sea level**. Worlds with
      //   minY=-64 stay bit for bit unchanged (still -64).
      int alignedSeaLevel = seaLevel - Math.floorMod(seaLevel, 16);
      // Round the half window down to a multiple of 16 (the NoiseSettings guard requires min_y % 16 == 0)
      int noiseMinY = Math.max(tellusMinY - Math.floorMod(tellusMinY, 16),
         Math.min(alignedSeaLevel - (noiseHeight / 32 * 16), noiseCeiling - noiseHeight));
      // Must use the **bare constructor**, not NoiseSettings.create(...)
      //
      // create() in the bytecode is literally "bare constructor plus guardY(...).error().ifPresent(-> throw IllegalStateException)",
      // and guardY validates the following:
      //   * min_y + height <= 2032, which our -64 + 65600 = 65536 throws on immediately
      //   * height % 16 == 0
      //   * min_y % 16 == 0
      // That 2032 is a guard for the **datapack JSON**, meant to stop someone writing an absurd noise range.
      // It is not a runtime invariant. The proof is that the only runtime entry point in vanilla,
      // `NoiseSettings.clampToHeightAccessor(...)` called from `NoiseChunk.forChunk`, uses the **bare
      // constructor** and never goes through guardY.
      //
      // So this replicates the vanilla runtime path: the same min_y/height combination and the same call
      // sequence, which vanilla already treats as legal at runtime.
      //
      // Why it never blew up before: caveGeneration stayed false in the world settings, so
      // EarthChunkGenerator.getTellusCarverRunner() was never called and this adapter was **dead code that
      // had never executed**. Turning caves on detonated it.
      //
      // Note: after narrowing, min_y + height is far below 2032, so create() would also pass. The bare
      // constructor is kept on purpose, so that the code does **not depend on that guard** (in case someone
      // sets `-Dtellus.caves.noiseHeight` very high, it will not throw immediately).
      NoiseSettings adaptedNoise = new NoiseSettings(
         noiseMinY, noiseHeight, baseNoise.noiseSizeHorizontal(), baseNoise.noiseSizeVertical()
      );
      int maxY = tellusMinY + tellusHeight - 1;
      int adaptedSeaLevel = Mth.clamp(seaLevel, tellusMinY, maxY);
      NoiseGeneratorSettings adapted = new NoiseGeneratorSettings(
         adaptedNoise,
         base.defaultBlock(),
         base.defaultFluid(),
         base.noiseRouter(),
         base.surfaceRule(),
         base.spawnTarget(),
         adaptedSeaLevel,
         readDisableMobGeneration(base),
         base.aquifersEnabled(),
         base.oreVeinsEnabled(),
         base.useLegacyRandomSource()
      );
      LOGGER.info(
         "[TellusCN] Carving noise has been narrowed to one band: minY={} height={} -> Y range [{}, {}]"
            + " (the full world height {} cannot be used: the c2me aquifer requires a vertical span <= 65535, while carving only needs this band) sea level={}",
         adaptedNoise.minY(), adaptedNoise.height(),
         adaptedNoise.minY(), adaptedNoise.minY() + adaptedNoise.height() - 1,
         tellusHeight, adaptedSeaLevel
      );
      return Holder.direct(adapted);
   }

   @SuppressWarnings("deprecation")
   private static boolean readDisableMobGeneration(NoiseGeneratorSettings settings) {
      return settings.disableMobGeneration();
   }
}
