package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -&gt; Server: requests preloading of a lat/lon rectangle.
 *
 * <p>The four corner points are passed as doubles (enough precision, no JSON parsing risk), and the layers as a comma-separated string;
 * an empty string means the default set (buildings + roads).
 *
 * <p>1.20.1 port note: {@code CustomPacketPayload} / {@code StreamCodec} / {@code ByteBufCodecs}
 * are 1.20.5+; this uses the 1.20.1-era {@link FabricPacket} + {@link PacketType} API. Four doubles
 * then a UTF-8 string, same order as the 1.21.9 encoder.
 */
public record TellusPreloadRequestPayload(double minLat, double minLon, double maxLat, double maxLon, String layers)
   implements FabricPacket {

   public static final PacketType<TellusPreloadRequestPayload> TYPE =
      PacketType.create(Tellus.id("preload_request"), TellusPreloadRequestPayload::fromBuf);

   @Override
   public void write(FriendlyByteBuf buf) {
      buf.writeDouble(this.minLat);
      buf.writeDouble(this.minLon);
      buf.writeDouble(this.maxLat);
      buf.writeDouble(this.maxLon);
      buf.writeUtf(this.layers);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static TellusPreloadRequestPayload fromBuf(FriendlyByteBuf buf) {
      double minLat = buf.readDouble();
      double minLon = buf.readDouble();
      double maxLat = buf.readDouble();
      double maxLon = buf.readDouble();
      String layers = buf.readUtf();
      return new TellusPreloadRequestPayload(minLat, minLon, maxLat, maxLon, layers);
   }

   public TellusPreloadRequestPayload {
      Objects.requireNonNull(layers, "layers");
   }
}
