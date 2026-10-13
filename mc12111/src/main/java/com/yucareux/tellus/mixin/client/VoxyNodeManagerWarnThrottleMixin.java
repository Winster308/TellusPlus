package com.yucareux.tellus.mixin.client;

import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Voxy compatibility: throttle the LOD empty-node warnings (measured: a 1:1 world prints dozens of them on every join).
 *
 * NodeManager.finishRequest warns every time a child node is missing (an empty area):
 * a 1:1 world has no LOD data for most chunks, so the log floods and the string concatenation costs. This mixin changes
 * only the log rate: the first 3 warnings plus every 200th, routed through TellusCN's own logger.
 * Voxy's internal logic is untouched. Pseudo: silently skipped when Voxy is not present in the environment.
 *
 * <p>2026-10-05: {@code @Environment(EnvType.CLIENT)} added and the entry moved from the {@code mixins} array
 * to the {@code client} array of {@code tellus.client.mixins.json}.
 * Trigger: the target {@code me.cortex.voxy.client.core.rendering.hierachical.NodeManager} is a <b>client-only</b>
 * class (it sits in Voxy's client package), so on a dedicated server it can never be found; the class survived
 * only through {@code @Pseudo} + {@code require = 0}, and on a client it was declared as if it were common code.
 * Consequence avoided: a mixin that can only ever resolve on the client being offered to the common classpath
 * (and, once the config is loaded server-side, a per-start "target not found" warning).
 * This matches the declaration style of the other client-only Voxy mixin in this project,
 * {@code VoxyRenderDistanceTrackerMixin} ({@code @Environment(EnvType.CLIENT)} + the {@code client} array).
 * Note: {@code tellus.client.mixins.json} is itself registered with {@code "environment": "client"}, so this
 * class is not even loaded on a dedicated server; the annotation only makes the intent explicit and stops the
 * class from being dragged onto the server classpath by the mixin package layout.
 */
@Environment(EnvType.CLIENT)
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager", remap = false)
public abstract class VoxyNodeManagerWarnThrottleMixin {
   @Unique
   private static final Logger TELLUS_LOG = LoggerFactory.getLogger("TellusCN/VoxyThrottle");

   @Unique
   private static final AtomicInteger tellus$zeroExistenceWarns = new AtomicInteger();

   // 2026-10-05: `ordinal = 0` was added to the @At.
   //   Trigger: an @At with no ordinal must match exactly one call site; if Voxy ever logs a second
   //   `Logger.warn(String)` inside `finishRequest`, this mixin's reference becomes ambiguous
   //   (`expect` defaults to 1) and Mixin rejects the mixin while applying it.
   //   Consequence avoided: a hard mixin-application failure (mod load crash) instead of a throttled log.
   //   `finishRequest` is the "child node missing" handler, so its first single-argument warn is the
   //   zero-existence one this mixin exists to throttle; `require = 0` below still keeps it degrading
   //   gracefully if Voxy renames or reshapes that method.
   //   (`ordinal` is an @At attribute, not a @Redirect one, same as DimensionTypeMinYMixin.)
   @Redirect(
      method = "finishRequest",
      at = @At(value = "INVOKE",
         target = "Lme/cortex/voxy/common/Logger;warn(Ljava/lang/String;)V",
         remap = false,
         ordinal = 0),
      remap = false,
      require = 0
   )
   private void tellus$throttleZeroExistenceWarn(String message) {
      int n = tellus$zeroExistenceWarns.incrementAndGet();
      if (n <= 3 || n % 200 == 0) {
         TELLUS_LOG.warn("[Voxy] {} (occurrence #{})", message, n);
      }
   }
}
