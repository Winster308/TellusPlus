package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Server -> Client: a snapshot of the downloaded regions (a JSON array); the client uses it to color the map and display the area.
 *
 * <p>Passed as a whole JSON string: the snapshot may contain many rectangles, and encoding them field by field into a payload structure would be more troublesome,
 * and this data is only used for display, so if it is broken nothing gets colored (the client tolerates parse failures).
 */
public record TellusPreloadStatePayload(String regionsJson) implements CustomPacketPayload {

   public static final CustomPacketPayload.Type<TellusPreloadStatePayload> TYPE =
      new CustomPacketPayload.Type<>(Tellus.id("preload_state"));

   public static final StreamCodec<FriendlyByteBuf, TellusPreloadStatePayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.STRING_UTF8, TellusPreloadStatePayload::regionsJson, TellusPreloadStatePayload::new
   );

   public TellusPreloadStatePayload {
      Objects.requireNonNull(regionsJson, "regionsJson");
   }

   @Override
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
