package com.yucareux.tellus.mixin.client;

import java.util.function.LongConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.RenderDistanceTracker", remap = false)
public abstract class VoxyRenderDistanceTrackerMixin {
   private static final int VOXY_TOP_LEVEL_Y_BITS = 8;
   private static final int VOXY_TOP_LEVEL_LOD = 4;
   @Unique
   private static final int TELLUS$MIN_SAFE_TOP_LEVEL_Y = -(1 << (VOXY_TOP_LEVEL_Y_BITS - VOXY_TOP_LEVEL_LOD - 1));
   @Unique
   private static final int TELLUS$MAX_SAFE_TOP_LEVEL_Y = (1 << (VOXY_TOP_LEVEL_Y_BITS - VOXY_TOP_LEVEL_LOD - 1)) - 1;
   // 2026-10-05: `require = 0` added to both @Inject methods below.
   //   Trigger: this class is @Pseudo (Voxy may be absent or reshaped) but the config declares
   //   `defaultRequire: 1`, so a Voxy update that renames `add`/`rem` was enough to fail this mixin hard.
   //   Consequence avoided: a crash at start-up on a Voxy update instead of degrading to "Voxy renders
   //   unclamped", which is what @Pseudo promises. Same convention as the two LightMissingLayerGuard*
   //   mixins in this project (`require = 0` plus a conservative null check, so a missing member means
   //   "do nothing" rather than "crash").
   //   Note: `@Shadow` has **no** `require` attribute in Mixin (only @Inject/@Redirect/@ModifyArg and friends
   //   have one), so the two LongConsumer shadows cannot be made optional that way. Their null check below
   //   is what keeps a missing/renamed consumer from turning the skip into an NPE, and returning early
   //   leaves Voxy's own add/rem running instead of cancelling it with no consumer to call.
   @Shadow
   @Final
   private LongConsumer addTopLevelNode;
   @Shadow
   @Final
   private LongConsumer removeTopLevelNode;
   @Shadow
   @Final
   private int minSec;
   @Shadow
   @Final
   private int maxSec;

   @Inject(
      method = {"add"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false,
      require = 0
   )
   private void tellus$clampTopLevelYOnAdd(int x, int z, CallbackInfo ci) {
      if (this.addTopLevelNode == null) {
         return;   // field not obtained -> let Voxy run untouched (conservative)
      }
      this.tellus$emitTopLevelNodes(this.addTopLevelNode, x, z);
      ci.cancel();
   }

   @Inject(
      method = {"rem"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false,
      require = 0
   )
   private void tellus$clampTopLevelYOnRemove(int x, int z, CallbackInfo ci) {
      if (this.removeTopLevelNode == null) {
         return;   // field not obtained -> let Voxy run untouched (conservative)
      }
      this.tellus$emitTopLevelNodes(this.removeTopLevelNode, x, z);
      ci.cancel();
   }

   @Unique
   private void tellus$emitTopLevelNodes(LongConsumer consumer, int x, int z) {
      int minY = Math.max(this.minSec, TELLUS$MIN_SAFE_TOP_LEVEL_Y);
      int maxY = Math.min(this.maxSec, TELLUS$MAX_SAFE_TOP_LEVEL_Y);

      for (int y = minY; y <= maxY; y++) {
         consumer.accept(tellus$packWorldSectionId(VOXY_TOP_LEVEL_LOD, x, y, z));
      }
   }

   @Unique
   private static long tellus$packWorldSectionId(int level, int x, int y, int z) {
      return ((long)level << 60)
         | ((long)(y & 0xFF) << 52)
         | ((long)(z & ((1 << 24) - 1)) << 28)
         | ((long)(x & ((1 << 24) - 1)) << 4);
   }
}
