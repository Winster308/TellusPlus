package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Client -> Server: requests preloading of a lat/lon rectangle.
 *
 * <p>The four corner points are passed as doubles (enough precision, no JSON parsing risk), and the layers as a comma-separated string;
 * an empty string means the default set (buildings + roads).
 */
public record TellusPreloadRequestPayload(double minLat, double minLon, double maxLat, double maxLon, String layers)
   implements CustomPacketPayload {

   public static final CustomPacketPayload.Type<TellusPreloadRequestPayload> TYPE =
      new CustomPacketPayload.Type<>(Tellus.id("preload_request"));

   public static final StreamCodec<FriendlyByteBuf, TellusPreloadRequestPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.DOUBLE, TellusPreloadRequestPayload::minLat,
      ByteBufCodecs.DOUBLE, TellusPreloadRequestPayload::minLon,
      ByteBufCodecs.DOUBLE, TellusPreloadRequestPayload::maxLat,
      ByteBufCodecs.DOUBLE, TellusPreloadRequestPayload::maxLon,
      ByteBufCodecs.STRING_UTF8, TellusPreloadRequestPayload::layers,
      TellusPreloadRequestPayload::new
   );

   public TellusPreloadRequestPayload {
      Objects.requireNonNull(layers, "layers");
   }

   @Override
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
