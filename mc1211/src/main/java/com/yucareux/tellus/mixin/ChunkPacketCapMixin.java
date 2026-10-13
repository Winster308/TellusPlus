package com.yucareux.tellus.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raises the client-side "chunk packet at most 2 MB" ceiling to 32 MB.
 *
 * <p>Vanilla checks {@code size <= 2097152} while reading a packet and throws
 * {@code Chunk Packet trying to allocate too much memory on read} when that is exceeded
 * (measured: a world 65536 blocks tall has 4100 sections, so sky light is about 8 MB, and the world would not load at all).
 *
 * <p>So for vanilla a "tall world" and "normal full lighting" are mutually exclusive. Raising the ceiling to 32 MB lets both
 * coexist (a one-off 8 MB read buffer is acceptable on a local or trusted server).
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketCapMixin {

   @ModifyConstant(
      method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;II)V",
      constant = @Constant(intValue = 2097152)
   )
   private static int tellus$raiseChunkPacketCap(int original) {
      return 32 * 1024 * 1024;
   }
}
