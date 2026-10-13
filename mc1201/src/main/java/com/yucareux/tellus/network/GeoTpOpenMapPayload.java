package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server -&gt; client: opens the GeoTP map at the given point.
 *
 * <p>1.20.1 port note: Minecraft only gained {@code CustomPacketPayload} / {@code StreamCodec} /
 * {@code ByteBufCodecs} in 1.20.5, so this class cannot use them. On 1.20.1 the Fabric networking
 * API v1 uses {@link FabricPacket} + {@link PacketType} instead, where a packet writes itself into
 * a {@link FriendlyByteBuf} and the type carries the reader. The wire format is unchanged: two
 * doubles, in the same order the old {@code StreamCodec.composite} emitted them.
 */
public record GeoTpOpenMapPayload(double latitude, double longitude) implements FabricPacket {

   public static final PacketType<GeoTpOpenMapPayload> TYPE =
      PacketType.create(Tellus.id("geotp_open_map"), GeoTpOpenMapPayload::fromBuf);

   @Override
   public void write(FriendlyByteBuf buf) {
      buf.writeDouble(this.latitude);
      buf.writeDouble(this.longitude);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static GeoTpOpenMapPayload fromBuf(FriendlyByteBuf buf) {
      return new GeoTpOpenMapPayload(buf.readDouble(), buf.readDouble());
   }

   public GeoTpOpenMapPayload {
      Objects.requireNonNull(latitude, "latitude");
      Objects.requireNonNull(longitude, "longitude");
   }
}
