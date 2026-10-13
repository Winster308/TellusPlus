package com.yucareux.tellus.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <h2>Light layer missing fallback (2026-09-24): the root fix for "cannot enter the world"</h2>
 *
 * <h3>Symptom (proven twice over, by log and by thread dump)</h3>
 * <pre>
 * [Worker-Main-2/ERROR] Caught exception
 *   NullPointerException: class_2804.method_12144()
 *     because Long2ObjectOpenHashMap.get(long) returned null
 *   at **class_3556.method_15502 -> copyDataLayer**      &lt;-  what this mixin fixes
 *   &lt;- class_3560.method_15525 -> setStoredLevel
 *   &lt;- class_3558.method_51567 -> LightEngine.propagateIncreases
 *   &lt;- class_3558.method_15516 &lt;- class_3568.method_15516
 *   &lt;- **class_3227.method_14277 &lt;- ChunkStatus (during chunk generation!)**
 * </pre>
 * => the NPE **breaks the chunk generation thread**, so that chunk **never finishes**, and
 *   `prepareLevels` stalls inside `getChunkBlocking`, stuck at "16% of the region is ready".
 *
 * <h3>Root cause (javap plus the Yarn mapping table)</h3>
 * <pre>
 * DataLayerStorageMap(class_3556):
 *    copyDataLayer(long) = method_15502   <-  copy on write: map.get -> .copy() -> map.put
 *        var = map.get(key)                <-  missing layer -> null
 *        var = var.method_12144()          <-  null.copy() -> NPE
 *    map -> field_15791 (its Yarn name really is `map`)
 * LayerLightSectionStorage(class_3560):
 *    createDataLayer(long) = method_15529  <-  "if missing, new DataLayer()", which should run first
 *    setStoredLevel         = method_15525 -> calls copyDataLayer
 * </pre>
 * => `copyDataLayer` **assumes the layer already exists**, but here **it has not been created yet**, so NPE.
 *
 * <h3>The fix (fill the gap at HEAD)</h3>
 * At the **HEAD** of `copyDataLayer`: **first make sure that key has a layer**, and if it does not,
 * put a brand new empty layer there, which is exactly what `createDataLayer` would have done.
 * The original logic then runs unchanged: `get` succeeds, copies, writes back and returns,
 * so **the semantics stay fully self-consistent**
 * (an empty layer means all dark, and the lighting engine then propagates the light itself).
 *
 * <h3>Safety boundary</h3>
 * <ul>
 *   <li>It only acts **when that key really has no layer**; when a layer is present not one byte is touched</li>
 *   <li>What goes in is a brand new empty layer, and no existing data is modified</li>
 *   <li>`require = 0`, so a missing injection point is skipped silently instead of failing mod load</li>
 *   <li>Switch: `-Dtellus.light.missingLayerGuard=false` rolls it all back</li>
 * </ul>
 *
 * <h3>Pitfalls hit along the way (two, both worth recording)</h3>
 * <ol>
 *   <li>**A string `targets` is not remapped.**
 *       My first version wrote {@code @Mixin(targets = "net.minecraft.world.level.lighting.LayerLightSectionStorage")},
 *       but MC classes **use intermediary names at runtime**, so **the mixin found no target** and
 *       {@code require = 0} **skipped it silently** (no error and no effect, the hardest kind of bug).
 *       => The correct answer is to **use `@Mixin(Class object)`** (what this version does) or to write
 *       `net.minecraft.class_XXXX`.
 *        By contrast {@code VoxyMapperGuardMixin} uses `me.cortex.voxy...` and works,
 *        because those are **real third-party mod names that never needed remapping**.</li>
 *   <li>**The previous version fixed `getStoredLevel`, which was not enough.**
 *       It blocked the old NPE site, but the engine kept going and **hit a new site, `copyDataLayer`**.
 *       => So this time the fix sits deeper and further upstream, in `copyDataLayer`.</li>
 * </ol>
 */
@Mixin(DataLayerStorageMap.class)
public abstract class LightMissingLayerGuardMixin {

   /** Whether the fallback is enabled (on by default). */
   private static final boolean TELLUS_LIGHT_GUARD =
      !"false".equalsIgnoreCase(System.getProperty("tellus.light.missingLayerGuard", "true"));

   /**
    * The layer storage itself (its Yarn name really is `map`, `field_15791`).
    *
    * <p>{@code @Shadow} is used instead of reflection: automatic remap, type safe, zero runtime overhead.
    */
   @Shadow
   protected Long2ObjectOpenHashMap<DataLayer> map;

   /**
    * The fallback site: `copyDataLayer(long)`
    *
    * <p>Internally it is `map.get -> .copy() -> map.put` and does **not** null-check, so a missing layer always NPEs.
    * Here at **HEAD** the missing layer is filled in, after which the original logic runs to completion.
    */
   // 26.2: DataLayerStorageMap.copyDataLayer(long) exists verbatim (javap-verified), so this
   // is a hard requirement -- a silent no-op here would reintroduce the NPE it guards against.
   @Inject(method = "copyDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;",
         at = @At("HEAD"))
   private void tellus$ensureLayerBeforeCopy(long layerKey, CallbackInfoReturnable<DataLayer> info) {
      if (!TELLUS_LIGHT_GUARD) {
         return;   // switch off -> run the original logic untouched
      }

      try {
         if (this.map == null) {
            return;   // field not obtained -> do nothing (conservative)
         }

         if (this.map.get(layerKey) != null) {
            return;   // a layer exists -> not one byte is touched
         }

         // no layer -> put a brand new empty layer (equivalent to what createDataLayer does)
         this.map.put(layerKey, new DataLayer());
      } catch (RuntimeException | LinkageError error) {
         // anything goes wrong while filling the layer -> keep it as is (prefer the old behaviour over changing semantics)
      }
   }
}
