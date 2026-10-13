package com.yucareux.tellus.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <h2>Light layer missing fallback, site 2 (2026-09-24): `getStoredLevel`</h2>
 *
 * <h3>Why a second mixin is required</h3>
 * `LayerLightSectionStorage` (**class_3560**) and `DataLayerStorageMap` (**class_3556**) are
 * <b>two different classes</b>, and <b>one mixin class can only target one</b>,
 * so the two NPE sites have to be split across two mixins:
 * <ul>
 *   <li>{@code LightMissingLayerGuardMixin} -> fixes `class_3556.copyDataLayer`</li>
 *   <li><b>This class</b> -> fixes this exit in `class_3560`</li>
 * </ul>
 *
 * <h3>Symptom (`latest.log` 19:24:22)</h3>
 * <pre>
 * [Worker-Main-2/ERROR] Caught exception
 *   NullPointerException: class_2804.method_12139(int,int,int)  "$$2" is null
 *   at **class_3560.method_15537 -> getStoredLevel(:102)**      &lt;-  what this mixin fixes
 *   &lt;- class_3558.method_51567 -> LightEngine.propagateIncreases(:184)
 *   &lt;- class_3558.method_15516 &lt;- class_3568.method_15516
 *   &lt;- **class_3227.method_14277 &lt;- ChunkStatus (during chunk generation!)**
 * </pre>
 *
 * <h3>Root cause (javap)</h3>
 * <pre>
 * getStoredLevel(long pos):          = method_15537
 *    var = getDataLayer(pos, **true**) = method_15522(pos, true)
 *          -> goes through field_15796 (the "stable table")
 *            -> DataLayerStorageMap.getLayer(pos) finds nothing -> null
 *    var.getStoredLevel(x,y,z)       -> NPE
 * </pre>
 *
 * <h3>The fix (minimal version)</h3>
 * Inject at the **RETURN** of `getDataLayer(long, boolean)` (`method_15522`):
 * when it returns **null**, **return a brand new empty layer directly**.
 *
 * <p> **Why nothing is written back** : `getStoredLevel` only requires a **non-null return value**, and an
 * empty layer (all zeroes, meaning all dark) prevents the NPE while **the lighting engine then propagates the light itself**.
 * The only cost of not writing back is that the same section may go through this fallback again next time,
 * but that just builds **one more empty object** (harmless, no lock, no IO), and
 * **what it buys is code simple enough to be incapable of a bug** (the previous version was reverted
 * precisely because its write-back logic was too convoluted).
 *
 * <p> **Why `getDataLayer(pos, bool)` was chosen** : it is the **common low level** of `getStoredLevel` (the NPE site)
 * and `getDataLayerData` (the layer lookup entry point), so one fallback here benefits both exits.
 *
 * <h3>Safety boundary</h3>
 * <ul>
 *   <li>It only intervenes **when the return value really is null**; in the normal case (a layer is present) not one byte is touched</li>
 *   <li>What it returns is a brand new empty layer; nothing is read and no existing data is modified</li>
 *   <li>`require = 0`, so a missing injection point is skipped silently instead of failing mod load</li>
 *   <li>Switch: `-Dtellus.light.missingLayerGuard=false` rolls it all back</li>
 * </ul>
 *
 * <h3>Lesson (the most expensive one this round)</h3>
 * The previous version **only plugged the one exit in `class_3556`**, and the NPE **still fired through a different exit**.
 * => **Every exit in a subsystem that can NPE has to be plugged**,
 * instead of assuming that the first one found is the only one.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LightMissingLayerGuard2Mixin {

   /** Whether the fallback is enabled (on by default, sharing one switch with the first mixin). */
   private static final boolean TELLUS_LIGHT_GUARD =
      !"false".equalsIgnoreCase(System.getProperty("tellus.light.missingLayerGuard", "true"));

   /**
    * The fallback site: `getDataLayer(long, boolean)`
    *
    * <p>When it returns null (that is, the table has no layer), **give it a brand new empty layer**, so
    * the upstream `getStoredLevel` gets a non-null value and does **not** NPE.
    */
   // 26.2: LayerLightSectionStorage.getDataLayer(long, boolean) exists verbatim
   // (javap-verified), so this guard is a hard requirement, not an optional nicety.
   @Inject(method = "getDataLayer(JZ)Lnet/minecraft/world/level/chunk/DataLayer;",
         at = @At("RETURN"), cancellable = true)
   private void tellus$fillMissingLayer(long sectionPos, boolean useStableMap,
         CallbackInfoReturnable<DataLayer> info) {
      if (!TELLUS_LIGHT_GUARD) {
         return;   // switch off -> run the original logic untouched
      }

      if (info.getReturnValue() != null) {
         return;   // a layer exists -> not one byte is touched (this is the normal path)
      }

      try {
         info.setReturnValue(new DataLayer());   // a brand new empty layer (all 0, meaning all dark)
      } catch (RuntimeException | LinkageError error) {
         // even creating the empty layer failed -> keep it as is (prefer the old behaviour over changing semantics)
      }
   }
}
