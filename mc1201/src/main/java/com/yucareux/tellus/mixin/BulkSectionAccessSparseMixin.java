package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Closes the last "write-through" path for sparse sections: {@code BulkSectionAccess}.
 *
 * <p>{@code BulkSectionAccess.getSection(pos)} calls {@code ChunkAccess.getSection(index)}, takes the
 * <b>section object itself</b> and hands it to the caller to write into. Only {@link net.minecraft.class_3122}
 * uses it. In sparse mode that getSection may return the <b>shared empty instance</b>, so ore generation writing into it
 * is what produces `ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 0`
 * (a write into a "palette of length 0"), and it also triggers `Failed to load chunk` / a stuck chunk pipeline (which shows up as "too slow").
 *
 * <p>So here the section for that Y is replaced with a real one <b>before</b> it is handed out.
 */
@Mixin(BulkSectionAccess.class)
public abstract class BulkSectionAccessSparseMixin {

   @Shadow
   @Final
   private LevelAccessor level;

   @Inject(method = "getSection", at = @At("HEAD"))
   private void tellus$materializeBeforeHanding(BlockPos pos, CallbackInfoReturnable<LevelChunkSection> info) {
      if (!SparseSections.active()) {
         return;
      }
      // the chunk containing pos; the LevelAccessor field has no getChunk(int,int) on 1.20.1 (that
      // accessor lives on LevelReader), so the chunk source is the portable route. getChunkNow returns
      // null for a chunk that is not loaded, and is already typed LevelChunk, so no pattern is needed.
      net.minecraft.world.level.chunk.LevelChunk chunk =
         this.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
      if (chunk != null) {
         SparseSections.materialize(chunk, pos.getY());
      }
   }
}
