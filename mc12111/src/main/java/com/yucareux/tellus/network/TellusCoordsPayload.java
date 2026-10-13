package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Server to client: where on the Earth you are standing right now.
 *
 * @param latitude        latitude in degrees, north positive
 * @param longitude       longitude in degrees, east positive
 * @param surfaceY        surface block Y
 * @param elevationMeters the real elevation of that point in metres, taken from the DEM
 * @param worldScale      the current world "metres per block"
 * @param inTellusWorld   whether this really is a Tellus world (otherwise lat/lon is meaningless)
 */
public record TellusCoordsPayload(double latitude, double longitude, int surfaceY,
                                 double elevationMeters, double worldScale, boolean inTellusWorld)
   implements CustomPacketPayload {

   public static final CustomPacketPayload.Type<TellusCoordsPayload> TYPE =
      new CustomPacketPayload.Type<>(Tellus.id("coords"));

   public static final StreamCodec<FriendlyByteBuf, TellusCoordsPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.DOUBLE, TellusCoordsPayload::latitude,
      ByteBufCodecs.DOUBLE, TellusCoordsPayload::longitude,
      ByteBufCodecs.VAR_INT, TellusCoordsPayload::surfaceY,
      ByteBufCodecs.DOUBLE, TellusCoordsPayload::elevationMeters,
      ByteBufCodecs.DOUBLE, TellusCoordsPayload::worldScale,
      ByteBufCodecs.BOOL, TellusCoordsPayload::inTellusWorld,
      TellusCoordsPayload::new
   );

   public TellusCoordsPayload {
      Objects.requireNonNull(latitude, "latitude");
   }

   @Override
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
