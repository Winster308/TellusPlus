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
 * {@code method_75747} is the codec building method (it has no readable name in the mappings; it was
 * {@code method_28522} up to 1.21.9 - verified with javap against the 1.21.11 intermediary jar, where
 * {@code net.minecraft.class_2874} contains {@code method_75747} and no longer contains
 * {@code method_28522} at all, and 1.21.11 also gave it a leading {@code Codec} parameter, which is
 * irrelevant here because this {@code @ModifyArg} is keyed by name only);
 * inside it, {@code Codec.intRange(min, max)} is called 4 times, in the order
 * min_y / height / logical_height / cloud_height. Only the first call is touched (ordinal = 0),
 * whose bounds are the {@code MIN_Y} / {@code MAX_Y} static fields.
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeMinYMixin {

   @ModifyArg(
      method = "method_75747",
      remap = false,
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/serialization/Codec;intRange(II)Lcom/mojang/serialization/Codec;",
         remap = false,
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
      method = "method_75747",
      remap = false,
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/serialization/Codec;intRange(II)Lcom/mojang/serialization/Codec;",
         remap = false,
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
