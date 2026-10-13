package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block writes on a server-side chunk: instantiate the section for that Y on demand before writing. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSparseSectionsMixin {

   @Inject(
      method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("HEAD")
   )
   private void tellus$materialize(BlockPos pos, BlockState state, boolean flags, CallbackInfoReturnable<BlockState> info) {
      SparseSections.materialize((ChunkAccess) (Object) this, pos.getY());
   }
}
