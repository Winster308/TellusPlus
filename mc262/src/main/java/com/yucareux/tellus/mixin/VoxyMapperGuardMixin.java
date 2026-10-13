package com.yucareux.tellus.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy compatibility guard (round 68).
 *
 * <p>Voxy's {@code Mapper.getBlockStateOpacity(blockId)} does not bounds-check the mapping id:
 * when stale mapping ids leak into the LOD data (a leftover {@code UNKNOWN_MAPPING} after a
 * layout change or a cache wipe), {@code ObjectArrayList.get} throws
 * {@code IndexOutOfBoundsException} straight away and kills the ingest/lighting thread (observed: Index 1048575 >= 38).
 *
 * <p>This mixin intercepts at the head of the method: an out-of-range id is treated as
 * transparent air and returns normally. {@code @Pseudo} keeps this silently skipped when Voxy is
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.other.Mapper", remap = false)
public abstract class VoxyMapperGuardMixin {
   @Shadow(remap = false)
   private ObjectArrayList blockId2stateEntry;

   @Inject(method = "getBlockStateOpacity(I)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private void tellus$guardOpacity(int blockId, CallbackInfoReturnable<Integer> cir) {
      if (blockId < 0 || blockId >= this.blockId2stateEntry.size()) {
         cir.setReturnValue(0);
      }
   }
}
