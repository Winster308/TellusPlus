package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the "materialise an empty section for every slot" tail of the
 *
 * {@code ChunkAccess} constructor with "share a single empty instance".
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessSparseSectionsMixin {

   @Shadow
   private static void replaceMissingSections(PalettedContainerFactory factory, LevelChunkSection[] sections) {
      throw new AssertionError("shadow");
   }

   @Redirect(
      method = "<init>",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/chunk/ChunkAccess;replaceMissingSections(Lnet/minecraft/world/level/chunk/PalettedContainerFactory;[Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"
      )
   )
   private static void tellus$fillSparse(PalettedContainerFactory factory, LevelChunkSection[] sections) {
      if (SparseSections.active()) {
         SparseSections.fillWithShared(factory, sections);
      } else {
         replaceMissingSections(factory, sections);
      }
   }

   @Inject(
      method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("HEAD")
   )
   private void tellus$materialize(BlockPos pos, BlockState state, CallbackInfoReturnable<BlockState> info) {
      SparseSections.materialize((ChunkAccess) (Object) this, pos.getY());
   }

   // 2026-10-05: `postProcessing` is deliberately NOT shrunk any more; the two grow hooks that used to
   //   accompany that plan are gone.
   //
   //   Trigger: the previous version had a `@Mutable @Shadow postProcessing`, plus HEAD injects on
   //   `addPackedPostProcess` / `markPosForPostprocessing` that grew the array again "only when post-processing
   //   is really needed", guarded by `postProcessing.length == 0`. Nothing ever put that array at length 0:
   //   `SparseSections.shrinkPostProcessing` has **zero callers** (verified: the only occurrence in the whole
   //   live tree is its own declaration in SparseSections.java; every other hit is a quarantine/backup copy),
   //   so the `length == 0` branch was unreachable, the two injects were dead weight, and the vanilla
   //   `new ShortList[getSectionsCount()]` (~32 KB per chunk at 4100 sections) simply stayed resident forever.
   //
   //   Why the shrink is not simply wired up (option (a) was considered and rejected):
   //   the only primitive that writes the array is the vanilla `public static
   //   ChunkAccess.getOrCreateOffsetList(ShortList[], int)`, which indexes it with no bounds check. A byte scan
   //   of both Minecraft jars shows exactly three writers: `ChunkAccess.addPackedPostProcess` (covered by a
   //   growth hook) **plus `ProtoChunk.markPosForPostprocessing` and `ProtoChunk.addPackedPostProcess`**, which
   //   ProtoChunk *overrides* and which index the inherited array directly. The second one runs on **every
   //   chunk load** (`SerializableChunkData` replays the saved post-processing lists through it) and the first
   //   runs from `Feature` / `StructurePiece` / `WorldCarver` / `NoiseBasedChunkGenerator` on every generated
   //   chunk. A zero-length array that missed either of them throws ArrayIndexOutOfBoundsException in the
   //   middle of world generation. Doing it safely means three new injection points (ChunkAccess `<init>`
   //   RETURN to shrink + those two ProtoChunk hooks) that must all hold at all times, and third-party mods
   //   subclassing ProtoChunk/LevelChunk can index the same protected field.
   //   Trading a guaranteed ~1.2 GB at 37,000 live chunks for an unguarded AIOOBE in worldgen is a bad deal,
   //   so the array is left at its vanilla size and this comment records what the safe route looks like.
   //   `SparseSections.shrinkPostProcessing` stays in place for whoever wants to finish that work.
}
