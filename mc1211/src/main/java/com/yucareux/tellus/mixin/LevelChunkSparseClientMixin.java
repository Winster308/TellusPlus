package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On the client, after a chunk packet has been parsed, replaces the sections that are "still empty"
 * with the shared empty instance.
 *
 * <p>Vanilla {@code LevelChunk.replaceWithPacketData} calls {@code read(buf)} <b>in place</b> on every
 * section already present in {@code this.sections}, so a world 65536 tall costs 4096 real objects
 * (about 1 MB) per chunk for nothing on the client. Here they are swapped for the shared instance at the end
 * of the method: the <b>packet format is not changed</b>, so an unmodded client is unaffected.
 */
// 1.21.1 PORT NOTE: 1.21.9 widened the second parameter of LevelChunk.replaceWithPacketData from a
// CompoundTag to Map<Heightmap.Types, long[]>. Verified with javap against the 1.21.1 merged jar:
//   public void replaceWithPacketData(FriendlyByteBuf, CompoundTag, Consumer<ClientboundLevelChunkPacketData$BlockEntityTagOutput>);
//     descriptor: (Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)V
// Loom's remapper had flagged the 1.21.9 descriptor as unmappable:
//   "Cannot remap replaceWithPacketData(...Ljava/util/Map...)V because it does not exist in any of the
//    targets [net/minecraft/world/level/chunk/LevelChunk]"
// which is exactly the silent-failure mode this port must not ship. Both @Inject descriptors and both
// handler parameter lists now use CompoundTag.
@Mixin(LevelChunk.class)
public abstract class LevelChunkSparseClientMixin {

   /**
    * Before the packet is read: first replace the shared empty instance with a real one per slot (off-key fix).
    *
    * <p>Vanilla reads in place with `LevelChunkSection.read(buf)`, so if a slot holds that global shared instance,
    * one received packet rewrites it with that chunk's data, and afterwards the empty sections of every chunk have
    * the wrong palette, which produces `Missing Palette entry for index N` and throws in the server main loop
    * (confirmed by testing).
    */
   @Inject(
      method = "replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)V",
      at = @At("HEAD")
   )
   private void tellus$detachSharedBeforeRead(FriendlyByteBuf buffer,
      CompoundTag chunkData,
      Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer, CallbackInfo info) {
      SparseSections.detachShared(((ChunkAccess) (Object) this).getSections());
   }

   /**
    * `replaceBiomes(buf)` also reads in place with `LevelChunkSection.readBiomes`, and it is
    * <b>a different method from `replaceWithPacketData`</b> (vanilla sends biomes in a separate packet),
    * so the HEAD detach above does not cover it. It needs its own injection (found during the round 1 self-check).
    */
   @Inject(method = "replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("HEAD"))
   private void tellus$detachSharedBeforeBiomes(FriendlyByteBuf buffer, CallbackInfo info) {
      SparseSections.detachShared(((ChunkAccess) (Object) this).getSections());
   }

   @Inject(
      method = "replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)V",
      at = @At("TAIL")
   )
   private void tellus$dropEmptySections(FriendlyByteBuf buffer, CompoundTag chunkData,
      Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer, CallbackInfo info) {
      // 2026-09-22: do not replace by default (crash fix).
      //   Swapping the client empty sections for the "global shared instance" lets the render thread read a section
      //   that belongs to no chunk at all, so the surface rules get a null NoiseChunk on the render thread and the game
      //   crashes with an NPE (measured). The sparse server side is unaffected. For the risky version try
      //   -Dtellus.sections.clientReplace=true
      if (SparseSections.clientReplaceEnabled()) {
         SparseSections.replaceEmptiesWithShared((ChunkAccess) (Object) this);
      }
   }
}
