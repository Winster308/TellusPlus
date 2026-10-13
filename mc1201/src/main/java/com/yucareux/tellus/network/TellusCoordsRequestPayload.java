package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -&gt; Server: asks "where am I on Earth right now".
 *
 * <p>Why go through the server: the lat/lon conversion needs the world's {@code world_scale} and
 * {@link com.yucareux.tellus.worldgen.EarthChunkGenerator}, and the client cannot get hold of a generator instance,
 * so the server computes the result and sends it back (requested on key press only, not every frame; the overhead is negligible).
 *
 * <p>1.20.1 port note: {@code CustomPacketPayload} / {@code StreamCodec} / {@code ByteBufCodecs}
 * are 1.20.5+; this uses the 1.20.1-era {@link FabricPacket} + {@link PacketType} API. Wire format
 * is a single UTF-8 string.
 */
public record TellusCoordsRequestPayload(String nonce) implements FabricPacket {

   public static final PacketType<TellusCoordsRequestPayload> TYPE =
      PacketType.create(Tellus.id("coords_request"), TellusCoordsRequestPayload::fromBuf);

   @Override
   public void write(FriendlyByteBuf buf) {
      buf.writeUtf(this.nonce);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static TellusCoordsRequestPayload fromBuf(FriendlyByteBuf buf) {
      return new TellusCoordsRequestPayload(buf.readUtf());
   }

   public TellusCoordsRequestPayload() {
      this("");
   }
}
