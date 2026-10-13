package com.yucareux.tellus.mixin;

import com.mojang.serialization.Codec;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * <b>Breaks the dimension height ceiling</b> (lets a world reach 65536 blocks tall or more).
 *
 * <h2>Where the limit lives</h2>
 * In 1.21.9 the dimension height is <b>capped in exactly one place</b>: the {@code DimensionType} codec.
 * Its {@code height} field is
 * {@code Codec.intRange(16, DimensionType.Y_SIZE)}, and {@code Y_SIZE} is 4064,
 * so any greater height is rejected during data loading (actual error text):
 *
 * <pre>
 * IllegalStateException: Value 65536 outside of range [0:4064]; Value 65536 outside of range [16:4064]
 * </pre>
 *
 * <p>The good news is that <b>the real runtime storage stopped caring about height long ago</b>: in 1.21.9 the lighting
 * storage ({@code DataLayerStorageMap}) is a {@code Long2ObjectOpenHashMap} (sparse, allocated on demand), and the
 * chunk section array is just 4096 references (empty sections share a singleton), so the height itself cannot blow up
 * lighting or memory. That is also why <b>there is no need to port the CubicChunks-style "cubic storage" rewrite</b>.
 *
 * <h2>How it is changed</h2>
 * The upper bound argument of that codec is swapped out. Here {@link ModifyArg} changes only
 * the <b>second argument (the upper bound)</b> of {@code intRange}, and <b>only for the call whose bound is exactly 4064</b>:
 * the same method has other {@code intRange} calls (for example spawn light level 0 to 15) that must not be hit.
 * Other ranges (for example {@code min_y}'s {@code [-2032, 2031]}) are left as they are,
 * because {@code min_y = -2032} is enough for a world 65536 tall (that reaches 63503 at the top).
 *
 * <p>The target method has no readable name in the mappings (a private codec builder), so only its intermediary name
 * {@code method_28522} can be used, and {@code remap = false} must stay set: at runtime (intermediary) and in a dev
 * environment (the layered mappings keep that name too) the name is the same either way.
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeHeightMixin {

   @ModifyArg(
      // 26.2 is shipped deobfuscated, so the old intermediary name method_28522 cannot
      // resolve. javap -p -c pins the Codec.intRange calls to the synthetic codec body:
      //   private static App lambda$createDirectCodec$0(Codec, RecordCodecBuilder$Instance)
      // Ordinals there are min_y = 0, height = 1, logical_height = 2 (cloud_height moved
      // into EnvironmentAttributeMap in 26.2), so height is ordinal 1.
      method = "lambda$createDirectCodec$0",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/serialization/Codec;intRange(II)Lcom/mojang/serialization/Codec;",
         ordinal = 1
      ),
      index = 1
   )
   private static int tellus$widenHeightLimit(int upperBound) {
      // Y_SIZE = 4064 is the ceiling of the "height/logical_height/cloud_height" call; every other intRange is returned as is.
      //
      // Only widen, never narrow: under the default layout MAX_DIMENSION_HEIGHT is just 2112,
      //    so returning it directly would narrow the vanilla ceiling from 4064 to 2112 and break dimension registry
      //    validation (`WorldGenSettings: Element ... is not valid in current registry set`, hit during testing).
      if (upperBound != 4064) {
         return upperBound;
      }
      return Math.max(4064, EarthGeneratorSettings.MAX_DIMENSION_HEIGHT);
   }
}
