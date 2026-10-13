package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Client -> Server: deletes the downloaded regions that intersect this rectangle (used by "box select to delete" on the map).
 *
 * <p>An empty {@code source} means no data source is restricted; {@code tellus}/{@code arnis} processes that source only.
 * A <b>degenerate rectangle</b> (maxLat ≤ minLat) is a special case: it means "clear all cache and ledger entries of that source".
 */
public record TellusPreloadDeletePayload(double lat1, double lon1, double lat2, double lon2, String source)
   implements CustomPacketPayload {

   public static final CustomPacketPayload.Type<TellusPreloadDeletePayload> TYPE =
      new CustomPacketPayload.Type<>(Tellus.id("preload_delete"));

   public static final StreamCodec<FriendlyByteBuf, TellusPreloadDeletePayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.DOUBLE, TellusPreloadDeletePayload::lat1,
      ByteBufCodecs.DOUBLE, TellusPreloadDeletePayload::lon1,
      ByteBufCodecs.DOUBLE, TellusPreloadDeletePayload::lat2,
      ByteBufCodecs.DOUBLE, TellusPreloadDeletePayload::lon2,
      ByteBufCodecs.STRING_UTF8, TellusPreloadDeletePayload::source,
      TellusPreloadDeletePayload::new
   );

   public TellusPreloadDeletePayload(double lat1, double lon1, double lat2, double lon2) {
      this(lat1, lon1, lat2, lon2, "");
   }

   @Override
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
