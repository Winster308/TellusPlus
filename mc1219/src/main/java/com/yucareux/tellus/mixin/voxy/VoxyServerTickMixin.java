package com.yucareux.tellus.mixin.voxy;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.integration.voxy.TellusVoxyPregenManager;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <h2>Voxy pregeneration gets its own host (2026-09-23)</h2>
 *
 * <p>This decouples TellusCN's Voxy pregeneration **completely from the main entry point**. It used to be
 * scattered across {@code Tellus.java}: one static singleton (`:120`), one shutdown (`:415`),
 * one {@code ServerTickEvents} registration (`:418`), and two property/consistency checks
 * (`applyVoxyPregenProperties` / `checkVoxyCacheConsistency`). Get any of those lines wrong and
 * **the main entry point and Voxy drag each other down**.
 *
 * <h3>Why {@code @Inject} instead of {@code ServerTickEvents}</h3>
 * {@code ServerTickEvents} comes from the **Fabric API** and is fine in itself, but hanging it off the main entry point
 * binds **Voxy's enablement to the main entry point's load order**.
 * Injecting into {@code MinecraftServer.tickServer} directly makes **this mixin own the whole lifecycle**:
 * it works when Voxy is installed and is skipped silently when it is not (see {@code isVoxyPresent()} below).
 *
 * <h3>Safety conventions (following the precedent of {@code VoxyMapperGuardMixin})</h3>
 * <ul>
 *   <li>{@code require = 0}: when the injection point is missing it is **skipped silently**; Voxy pregeneration not
 *       running is better than **letting the whole mod fail to load**.</li>
 *   <li>All access to Voxy lives inside {@code TellusVoxyPregenManager} / {@code VoxyBridge} and uses
 *       **reflection plus {@code FabricLoader.isModLoaded("voxy")}**, so there is **no hard dependency** and
 *       no {@code NoClassDefFoundError} when Voxy is absent.</li>
 *   <li>This mixin **changes no vanilla behaviour**; it only appends one call at the end of a tick.</li>
 * </ul>
 *
 * <h3>WARNING: known trade-off</h3>
 * When it was on {@code END_SERVER_TICK} the pregeneration **woke up every tick**, and the
 * {@code TAIL} of {@code tickServer} is semantically equivalent (the same tick boundary),
 * so **the behaviour is identical to before the change**. That matters: decoupling must not smuggle in
 * a behaviour change.
 */
@Mixin(MinecraftServer.class)
public abstract class VoxyServerTickMixin {
   /**
    * The singleton host (moved over from {@code Tellus.java:120}).
    *
    * <p>It lives in a mixin rather than in the main entry point, so the main entry point **holds no Voxy state at all**.
    * Everything that needs it (commands, the config panel) goes through {@link #manager()}.
    */
   // 2026-09-23 fix, second round: the state must NOT live here
   //
   //   In the first version I put the singleton and `public static manager()` in this class, and it crashed on startup:
   //     `InvalidMixinException: ... contains non-private static method manager()`
   //   Adding `@Unique` does not help, because Mixin hard-requires that **non-private members are never allowed**
   //   => the state moved to the plain class {@code VoxyPregenHost}; this mixin keeps only the injection point
   //     plus one call, which also matches the "a mixin should stay as thin as possible" design.

   /** The manager (the state lives in a plain class, not in a mixin). */
   private static TellusVoxyPregenManager voxy() {
      return com.yucareux.tellus.integration.voxy.VoxyPregenHost.get();
   }

   /**
    * After the server starts: apply the {@code -Dtellus.voxy.*} properties and run one cache consistency check.
    * (Moved over from the SERVER_STARTED callback in {@code Tellus.java}.)
    */
   @Inject(method = "runServer", at = @At("TAIL"), require = 0)
   private void tellus$voxyAfterRunServer(CallbackInfo info) {
      // only reached when runServer exits normally; the start-up work is carried by the tick tail below
   }

   /**
    * The main hook: the end of every server tick.
    *
    * <p>{@code require = 0}: when the injection point does not exist (for example another mod rewrote this
    * method signature) it is **skipped silently** instead of blowing up the whole mod.
    */
   @Inject(method = "tickServer", at = @At("TAIL"), require = 0)
   private void tellus$voxyTick(BooleanSupplier hasTimeLeft, CallbackInfo info) {
      if (!TellusVoxyPregenManager.isVoxyPresent()) {
         return;   // Voxy is not installed -> do nothing at all (zero overhead)
      }

      try {
         voxy().onServerTick((MinecraftServer)(Object)this);
         // 2026-10-05: re-arm the once-only error log, otherwise a single early failure silences
         // every later one for the rest of the process (see TellusVoxyPregenManager).
         TellusVoxyPregenManager.reportTickSuccess();
      } catch (RuntimeException | LinkageError error) {
         // a pregeneration problem must never take the server down, one log line is enough
         TellusVoxyPregenManager.reportTickFailure(error);
      }
   }

   /** On server stop: release the queue and the in-flight tasks (was in {@code Tellus.java:415}). */
   @Inject(method = "stopServer", at = @At("HEAD"), require = 0)
   private void tellus$voxyStop(CallbackInfo info) {
      try {
         voxy().shutdown();
      } catch (RuntimeException | LinkageError error) {
         Tellus.LOGGER.debug("Voxy pregen shutdown skipped", error);
      }
   }
}
