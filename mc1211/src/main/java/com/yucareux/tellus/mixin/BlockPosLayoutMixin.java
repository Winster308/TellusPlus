package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.TellusLayout;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps {@code BlockPos}'s packing layout for the one in {@link TellusLayout}.
 *
 * <h2>Why HEAD + cancellable instead of @Overwrite</h2>
 * {@code @Overwrite} replaces the whole method, so any deviation from vanilla, however small, would also
 * pollute the default (non-repacked) path. Here the original method is cancelled and our value returned only when
 * {@code TellusLayout.PACKING_REPACKED} is true; when it is false the original method is not touched by even one bit and
 * the behaviour is bit-for-bit vanilla.
 *
 * <h2>Why only these 4 methods</h2>
 * {@code asLong/getX/getY/getZ} are the layout's <b>only entry and exit points</b>:
 * {@code BlockPos.of(long)}, the instance method {@code asLong()}, and every long-keyed collection go through them.
 * So code that uses the API (which is the vast majority of mods) follows the new layout automatically; only code that
 * does its own shift-and-mask to split up {@code asLong} will read it wrong.
 */
@Mixin(BlockPos.class)
public abstract class BlockPosLayoutMixin {

   @Inject(method = "asLong(III)J", at = @At("HEAD"), cancellable = true)
   private static void tellus$pack(int x, int y, int z, CallbackInfoReturnable<Long> info) {
      if (TellusLayout.PACKING_REPACKED) {
         info.setReturnValue(TellusLayout.pack(x, y, z));
      }
   }

   @Inject(method = "getX(J)I", at = @At("HEAD"), cancellable = true)
   private static void tellus$unpackX(long packed, CallbackInfoReturnable<Integer> info) {
      if (TellusLayout.PACKING_REPACKED) {
         info.setReturnValue(TellusLayout.unpackX(packed));
      }
   }

   @Inject(method = "getY(J)I", at = @At("HEAD"), cancellable = true)
   private static void tellus$unpackY(long packed, CallbackInfoReturnable<Integer> info) {
      if (TellusLayout.PACKING_REPACKED) {
         info.setReturnValue(TellusLayout.unpackY(packed));
      }
   }

   @Inject(method = "getZ(J)I", at = @At("HEAD"), cancellable = true)
   private static void tellus$unpackZ(long packed, CallbackInfoReturnable<Integer> info) {
      if (TellusLayout.PACKING_REPACKED) {
         info.setReturnValue(TellusLayout.unpackZ(packed));
      }
   }
}
