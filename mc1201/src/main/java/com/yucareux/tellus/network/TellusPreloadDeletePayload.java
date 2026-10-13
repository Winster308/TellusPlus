package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Client -&gt; Server: deletes the downloaded regions that intersect this rectangle (used by "box select to delete" on the map).
 *
 * <p>An empty {@code source} means no data source is restricted; {@code tellus}/{@code arnis} processes that source only.
 * A <b>degenerate rectangle</b> (maxLat ≤ minLat) is a special case: it means "clear all cache and ledger entries of that source".
 *
 * <p>1.20.1 port note: {@code CustomPacketPayload} / {@code StreamCodec} / {@code ByteBufCodecs}
 * are 1.20.5+; this uses the 1.20.1-era {@link FabricPacket} + {@link PacketType} API. Four doubles
 * then a UTF-8 string, same order as the 1.21.9 encoder.
 */
public record TellusPreloadDeletePayload(double lat1, double lon1, double lat2, double lon2, String source)
   implements FabricPacket {

   public static final PacketType<TellusPreloadDeletePayload> TYPE =
      PacketType.create(Tellus.id("preload_delete"), TellusPreloadDeletePayload::fromBuf);

   @Override
   public void write(FriendlyByteBuf buf) {
      buf.writeDouble(this.lat1);
      buf.writeDouble(this.lon1);
      buf.writeDouble(this.lat2);
      buf.writeDouble(this.lon2);
      buf.writeUtf(this.source);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static TellusPreloadDeletePayload fromBuf(FriendlyByteBuf buf) {
      double lat1 = buf.readDouble();
      double lon1 = buf.readDouble();
      double lat2 = buf.readDouble();
      double lon2 = buf.readDouble();
      String source = buf.readUtf();
      return new TellusPreloadDeletePayload(lat1, lon1, lat2, lon2, source);
   }

   public TellusPreloadDeletePayload(double lat1, double lon1, double lat2, double lon2) {
      this(lat1, lon1, lat2, lon2, "");
   }
}
