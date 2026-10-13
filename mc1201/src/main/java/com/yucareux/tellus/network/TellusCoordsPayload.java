package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server -&gt; client: where on the Earth you are standing right now.
 *
 * <p>1.20.1 port note: {@code CustomPacketPayload} / {@code StreamCodec} / {@code ByteBufCodecs}
 * are 1.20.5+; this uses the 1.20.1-era {@link FabricPacket} + {@link PacketType} API. Field order
 * and types on the wire are unchanged from the 1.21.9 encoder.
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
   implements FabricPacket {

   public static final PacketType<TellusCoordsPayload> TYPE =
      PacketType.create(Tellus.id("coords"), TellusCoordsPayload::fromBuf);

   @Override
   public void write(FriendlyByteBuf buf) {
      buf.writeDouble(this.latitude);
      buf.writeDouble(this.longitude);
      buf.writeVarInt(this.surfaceY);
      buf.writeDouble(this.elevationMeters);
      buf.writeDouble(this.worldScale);
      buf.writeBoolean(this.inTellusWorld);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static TellusCoordsPayload fromBuf(FriendlyByteBuf buf) {
      double latitude = buf.readDouble();
      double longitude = buf.readDouble();
      int surfaceY = buf.readVarInt();
      double elevationMeters = buf.readDouble();
      double worldScale = buf.readDouble();
      boolean inTellusWorld = buf.readBoolean();
      return new TellusCoordsPayload(latitude, longitude, surfaceY, elevationMeters, worldScale, inTellusWorld);
   }

   public TellusCoordsPayload {
      if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
         throw new IllegalArgumentException("latitude/longitude must be finite");
      }
   }
}
