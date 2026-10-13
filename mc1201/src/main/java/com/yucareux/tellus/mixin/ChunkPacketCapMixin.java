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
 *
 * <h2>1.20.1 port note — the constructor descriptor changed</h2>
 * The 1.21.9 target was {@code <init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;II)V}.
 * {@code RegistryFriendlyByteBuf} <b>does not exist on 1.20.1</b> (it arrives with the 1.20.5 stream-codec
 * rework; verified with javap against the 1.20.1 jar -- the class is absent). On 1.20.1 the reader
 * constructor takes a plain {@code FriendlyByteBuf}:
 * {@code <init>(Lnet/minecraft/network/FriendlyByteBuf;II)V}.
 *
 * <p>The constant itself is unchanged and still present: javap shows {@code ldc int 2097152} at offset
 * 42 of that constructor, immediately followed by the message
 * {@code "Chunk Packet trying to allocate too much memory on read."} at offset 51. So only the
 * descriptor needed correcting -- the {@code @ModifyConstant} target and value are the same.
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketCapMixin {

   @ModifyConstant(
      method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;II)V",
      constant = @Constant(intValue = 2097152)
   )
   private static int tellus$raiseChunkPacketCap(int original) {
      return 32 * 1024 * 1024;
   }
}
