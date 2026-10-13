package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.BiomeResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Safety-net probes: intercept the paths that write straight into a shared empty section.
 *
 * <ul>
 *   <li>block write: record one ERROR (a block was lost, so some entry point slipped through), drop the write, and never pollute other chunks;</li>
 *   <li>biome write: silently dropped (expected: {@code fillBiomesFromNoise} walks every section, and empty areas must not consume biome data);</li>
 *   <li>network biome read: dropped the same way.</li>
 * </ul>
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionSparseGuardMixin {

   @Inject(
      method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("HEAD"), cancellable = true
   )
   private void tellus$guardSet4(int x, int y, int z, BlockState state, CallbackInfoReturnable<BlockState> info) {
      if (SparseSections.isShared((LevelChunkSection) (Object) this) && SparseSections.blockWriteOnShared()) {
         info.setReturnValue(null);
      }
   }

   @Inject(
      method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("HEAD"), cancellable = true
   )
   private void tellus$guardSet5(int x, int y, int z, BlockState state, boolean movedByPiston,
      CallbackInfoReturnable<BlockState> info) {
      if (SparseSections.isShared((LevelChunkSection) (Object) this) && SparseSections.blockWriteOnShared()) {
         info.setReturnValue(null);
      }
   }

   @Inject(
      method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;III)V",
      at = @At("HEAD"), cancellable = true
   )
   private void tellus$guardFillBiomes(BiomeResolver resolver, Climate.Sampler sampler, int x, int y, int z,
      CallbackInfo info) {
      if (SparseSections.isShared((LevelChunkSection) (Object) this) && SparseSections.biomeWriteOnShared()) {
         info.cancel();
      }
   }

   @Inject(method = "readBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("HEAD"), cancellable = true)
   private void tellus$guardReadBiomes(FriendlyByteBuf buffer, CallbackInfo info) {
      if (SparseSections.isShared((LevelChunkSection) (Object) this) && SparseSections.biomeWriteOnShared()) {
         info.cancel();
      }
   }
}
