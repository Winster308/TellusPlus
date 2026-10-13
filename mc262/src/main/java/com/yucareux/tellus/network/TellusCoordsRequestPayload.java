package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Client -> Server: asks "where am I on Earth right now".
 *
 * <p>Why go through the server: the lat/lon conversion needs the world's {@code world_scale} and
 * {@link com.yucareux.tellus.worldgen.EarthChunkGenerator}, and the client cannot get hold of a generator instance,
 * so the server computes the result and sends it back (requested on key press only, not every frame; the overhead is negligible).
 */
public record TellusCoordsRequestPayload(String nonce) implements CustomPacketPayload {

   public static final CustomPacketPayload.Type<TellusCoordsRequestPayload> TYPE =
      new CustomPacketPayload.Type<>(Tellus.id("coords_request"));

   public static final StreamCodec<FriendlyByteBuf, TellusCoordsRequestPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.STRING_UTF8, TellusCoordsRequestPayload::nonce, TellusCoordsRequestPayload::new
   );

   public TellusCoordsRequestPayload() {
      this("");
   }

   @Override
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
