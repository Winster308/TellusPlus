package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block writes during world generation (noise, carving and structures all land here): instantiate the section for that Y on demand before writing. */
@Mixin(ProtoChunk.class)
public abstract class ProtoChunkSparseSectionsMixin {

   @Inject(
      method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("HEAD")
   )
   private void tellus$materialize(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> info) {
      SparseSections.materialize((ChunkAccess) (Object) this, pos.getY());
   }
}
