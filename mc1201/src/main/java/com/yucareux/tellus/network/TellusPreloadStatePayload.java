package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server -&gt; Client: a snapshot of the downloaded regions (a JSON array); the client uses it to color the map and display the area.
 *
 * <p>Passed as a whole JSON string: the snapshot may contain many rectangles, and encoding them field by field into a payload structure would be more troublesome,
 * and this data is only used for display, so if it is broken nothing gets colored (the client tolerates parse failures).
 *
 * <p>1.20.1 port note: {@code CustomPacketPayload} / {@code StreamCodec} / {@code ByteBufCodecs}
 * are 1.20.5+; this uses the 1.20.1-era {@link FabricPacket} + {@link PacketType} API. Wire format
 * is a single UTF-8 string.
 */
public record TellusPreloadStatePayload(String regionsJson) implements FabricPacket {

   public static final PacketType<TellusPreloadStatePayload> TYPE =
      PacketType.create(Tellus.id("preload_state"), TellusPreloadStatePayload::fromBuf);

   @Override
   public void write(FriendlyByteBuf buf) {
      buf.writeUtf(this.regionsJson);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static TellusPreloadStatePayload fromBuf(FriendlyByteBuf buf) {
      return new TellusPreloadStatePayload(buf.readUtf());
   }

   public TellusPreloadStatePayload {
      Objects.requireNonNull(regionsJson, "regionsJson");
   }
}
