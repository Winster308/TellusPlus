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

   /**
    * 1.20.1: the third parameter of this overload is a {@code boolean isMoving}, not the {@code int}
    * flags of 1.21.9 (javap: {@code setBlockState(BlockPos, BlockState, Z)} on LevelChunk). The
    * descriptor was rewritten; the 1.21.9 {@code ...)I} form would not have matched.
    */
   @Inject(
      method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("HEAD")
   )
   private void tellus$materialize(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> info) {
      SparseSections.materialize((ChunkAccess) (Object) this, pos.getY());
   }
}
