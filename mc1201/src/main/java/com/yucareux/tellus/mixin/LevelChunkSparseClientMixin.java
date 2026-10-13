package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
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
 *
 * <h2>1.20.1 port note — the descriptor changed, and the old one would have failed silently</h2>
 * On 1.21.9 the second parameter of {@code replaceWithPacketData} is
 * {@code Map<Heightmap.Types, long[]>}; on 1.20.1 it is a {@code CompoundTag} holding the block-entity
 * data (javap: {@code replaceWithPacketData(FriendlyByteBuf, CompoundTag, Consumer)}). The 1.21.9
 * descriptor was therefore corrected in both injections below. Left unfixed with
 * {@code defaultRequire: 1} this would have been a hard startup failure rather than a silent one,
 * but the point stands: on 1.20.1 the parameter list had to be re-derived from bytecode, not copied.
 */
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
      CompoundTag blockEntityData,
      Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer, CallbackInfo info) {
      SparseSections.detachShared(((ChunkAccess) (Object) this).getSections());
   }

   /**
    * `replaceBiomes(buf)` also reads in place with `LevelChunkSection.readBiomes`, and it is
    * <b>a different method from `replaceWithPacketData`</b> (vanilla sends biomes in a separate packet),
    * so the HEAD detach above does not cover it. It needs its own injection (found during the round 1 self-check).
    *
    * <p>1.20.1: this descriptor is unchanged.
    */
   @Inject(method = "replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("HEAD"))
   private void tellus$detachSharedBeforeBiomes(FriendlyByteBuf buffer, CallbackInfo info) {
      SparseSections.detachShared(((ChunkAccess) (Object) this).getSections());
   }

   @Inject(
      method = "replaceWithPacketData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)V",
      at = @At("TAIL")
   )
   private void tellus$dropEmptySections(FriendlyByteBuf buffer, CompoundTag blockEntityData,
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
