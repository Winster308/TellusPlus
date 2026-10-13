package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.TellusLayout;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Widen the accepted range of {@code min_y} in the {@code DimensionType} codec (vanilla is -2032 to 2031).
 *
 * <p>Why it must exist as a pair: min_y is <b>written into level.dat</b>, and the next load validates it with the vanilla
 * range, so an illegal value means <b>the world will not open</b>. Widening only the MAX_Y check in the constructor is
 * not enough.
 *
 * <p>Note: {@code -64} is already inside the vanilla range (so changing it is optional), but to support gameplay such as
 * {@code -Dtellus.world.minY=-131072} the range is widened here according to the layout.
 *
 * <p><b>26.2:</b> the class is deobfuscated, so the old intermediary name {@code method_28522}
 * cannot resolve. {@code javap -p -c} on 26.2 shows the three {@code Codec.intRange} calls live in
 * the synthetic body of the record codec:
 * <pre>
 *   private static App lambda$createDirectCodec$0(Codec, RecordCodecBuilder$Instance)
 * </pre>
 * (reached from {@code createDirectCodec}), in the order min_y (ordinal 0), height (ordinal 1),
 * logical_height (ordinal 2). 1.21.9 had four including cloud_height; 26.2 moved cloud_height
 * into {@code EnvironmentAttributeMap}, so only three remain.
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeMinYMixin {

   @ModifyArg(
      method = "lambda$createDirectCodec$0",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/serialization/Codec;intRange(II)Lcom/mojang/serialization/Codec;",
         ordinal = 0
      ),
      index = 0
   )
   private static int tellus$widenMinYLower(int lowerBound) {
      // Only widen, never narrow: the bundled earth.json has min_y = -368,
      // so narrowing the lower bound to -64 would fail registry loading and the game would not start at all (hit on a real server).
      if (!TellusLayout.TALL) {
         return lowerBound;
      }
      return Math.min(lowerBound, TellusLayout.WORLD_MIN_Y);
   }

   @ModifyArg(
      method = "lambda$createDirectCodec$0",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/serialization/Codec;intRange(II)Lcom/mojang/serialization/Codec;",
         ordinal = 0
      ),
      index = 1
   )
   private static int tellus$widenMinYUpper(int upperBound) {
      // same rule: only widen
      if (!TellusLayout.TALL) {
         return upperBound;
      }
      return Math.max(upperBound, TellusLayout.WORLD_TOP_Y);
   }
}
