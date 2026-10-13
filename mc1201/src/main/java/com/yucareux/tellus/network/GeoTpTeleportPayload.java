package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -&gt; server: teleport me to this latitude/longitude.
 *
 * <p>1.20.1 port note: see {@link GeoTpOpenMapPayload} — {@code CustomPacketPayload} /
 * {@code StreamCodec} / {@code ByteBufCodecs} are 1.20.5+, so this uses the 1.20.1-era
 * {@link FabricPacket} + {@link PacketType} API. Two doubles on the wire, same order as before.
 */
public record GeoTpTeleportPayload(double latitude, double longitude) implements FabricPacket {

   public static final PacketType<GeoTpTeleportPayload> TYPE =
      PacketType.create(Tellus.id("geotp_teleport"), GeoTpTeleportPayload::fromBuf);

   @Override
   public void write(FriendlyByteBuf buf) {
      buf.writeDouble(this.latitude);
      buf.writeDouble(this.longitude);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static GeoTpTeleportPayload fromBuf(FriendlyByteBuf buf) {
      return new GeoTpTeleportPayload(buf.readDouble(), buf.readDouble());
   }

   public GeoTpTeleportPayload {
      Objects.requireNonNull(latitude, "latitude");
      Objects.requireNonNull(longitude, "longitude");
   }
}
