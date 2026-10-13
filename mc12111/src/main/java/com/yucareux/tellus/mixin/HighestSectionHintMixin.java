package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.HighestSectionHintState;
import com.yucareux.tellus.worldgen.SectionHint;
import com.yucareux.tellus.worldgen.SparseSections;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimises {@code getHighestFilledSectionIndex}: stop scanning down from the very top on every call.
 *
 * <p>The vanilla implementation starts at {@code sections.length - 1} and walks down to the first non-empty section.
 * A world 65536 tall has 4100 sections while all the content sits at the bottom, so every call wastes about
 * 4000 empty shells. A 1:1 scale has tens of thousands of chunks to load, and that waste is multiplied
 * (both Voxy and lighting initialisation call it).
 *
 * <p>Approach: keep a "highest section ever instantiated" hint on the chunk.
 * * The first call still does a <b>full scan</b>, which guarantees correctness
 * * after that the scan starts at the hint, and since the hint only grows it is always at or above the real
 *   highest, so <b>the result matches vanilla bit for bit</b>
 * * It only takes over in sparse mode ({@link SparseSections#active()}); otherwise vanilla runs
 *
 * <p><b>2026-10-05: the hint is now {@code volatile}, genuinely monotonic, and the "fast fill" path takes part.</b>
 * <ol>
 *   <li><b>Visibility.</b> The field is written by the chunk generation workers (c2me generates chunks
 *       multi-threaded, and {@code fillSolidSections} even fills one chunk from a pool of threads) and read by
 *       the lighting / spawn paths. A plain non-volatile int let a reader on another core keep a stale value,
 *       which is precisely the case where the scan starts too low.</li>
 *   <li><b>Monotonicity.</b> The old code <i>lowered</i> the hint while scanning ({@code tellus$highestHint = i}
 *       and {@code = -1}), although the contract says it only ever grows. A lowered hint made the next scan start
 *       below the real highest section.</li>
 *   <li><b>The hot path did not take part.</b> The raise only happened in {@code SparseSections.materialize(...)},
 *       but the terrain itself is written through {@code SparseSections.materializeSection(sections, index)}
 *       (5 call sites in {@code EarthChunkGenerator}: {@code fillSolidSections}, {@code fillStoneColumnSpan},
 *       {@code fillColumnConstant}, and two more), which receives no {@code ChunkAccess} and therefore never
 *       raised anything. Result: the hint could read far too low and corrupt the heightmap / lighting / spawn
 *       decisions. {@link HighestSectionHintState} now records those materialisations too and the scan starts at
 *       {@code max(hint, mark)}.</li>
 * </ol>
 */
@Mixin(ChunkAccess.class)
public abstract class HighestSectionHintMixin implements SectionHint {

   // 2026-10-05: volatile. The update below is a plain "compare, then write the larger value", so the field is
   //   only ever written upwards; making the write itself atomic is not needed because the scan tolerates a
   //   lost concurrent update (it only ever makes the scan start lower, and every path that materialises a
   //   section also raises HighestSectionHintState, which is the AtomicInteger that carries the guarantee).
   @Unique
   private volatile int tellus$highestHint = -2;   // -2 = not fully scanned yet

   @Override
   public void tellus$raiseHighestHint(int sectionIndex) {
      HighestSectionHintState.raise(sectionIndex);
      int current = this.tellus$highestHint;
      if (sectionIndex > current) {
         this.tellus$highestHint = sectionIndex;   // monotonic: this value is never lowered
      }
   }

   // 2026-10-10: the @Inject(method = "materializeSection(...)") that used to live here is GONE, and the
   //   coverage now happens at the call site instead -- see the note inside SparseSections.materializeSection.
   //
   //   Why it had to go rather than be fixed in place: this mixin targets @Mixin(ChunkAccess.class), and
   //   ChunkAccess has no materializeSection method -- that method belongs to OUR class SparseSections.
   //   The injector therefore never found its target; `require = 0` made that silent, and the build only
   //   hinted at it with:
   //       Cannot remap materializeSection([Lnet/minecraft/world/level/chunk/LevelChunkSection;I)V
   //       because it does not exist in any of the targets [net/minecraft/world/level/chunk/ChunkAccess]
   //   So the "the fast-fill path takes part in the hint" promise in the class doc above was never true.
   //
   //   Injecting into one's own class through a mixin aimed at a Minecraft class cannot be made to work at
   //   all; pointing a second @Mixin at SparseSections would work but is strictly worse than the direct
   //   call (no mapping, no injector, no optional-failure mode). The direct call is where it lives now.

   @Inject(method = "getHighestFilledSectionIndex", at = @At("HEAD"), cancellable = true)
   private void tellus$scanFromHint(CallbackInfoReturnable<Integer> info) {
      if (!SparseSections.active()) {
         return;   // let vanilla handle it
      }
      LevelChunkSection[] sections = ((ChunkAccess) (Object) this).getSections();
      int start = this.tellus$highestHint;
      if (start < -1) {
         start = sections.length - 1;   // never scanned yet: a full scan is the only correct option
      } else {
         // Both values are upper bounds of this chunk's highest non-air section, so the larger one is safe
         // (and scanning from higher up is only cheaper). The mark is what covers the fast-fill path.
         start = Math.max(start, HighestSectionHintState.highest());
         if (start >= sections.length) {
            start = sections.length - 1;
         }
      }
      for (int i = start; i >= 0; i--) {
         if (!sections[i].hasOnlyAir()) {
            if (i > this.tellus$highestHint) {
               this.tellus$highestHint = i;   // 2026-10-05: raise only, never lower (the old code did both)
            }
            info.setReturnValue(i);
            return;
         }
      }
      // Nothing below the upper bound holds content: -1 is the vanilla answer for an empty chunk.
      // The hint is deliberately left alone. Lowering it to -1 would cache "this chunk is empty", and a
      // fast fill landing above the hint later on would then never be seen again.
      info.setReturnValue(-1);
   }
}
