package com.yucareux.tellus.network;

import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Arrays;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -&gt; Client: the realtime weather / snow-index snapshot for the area around the player.
 *
 * <p>1.20.1 port note: {@code CustomPacketPayload} and {@code StreamCodec} are 1.20.5+, so this
 * implements {@link FabricPacket} instead. The old anonymous {@code StreamCodec} had a
 * {@code decode} half and an {@code encode} half; those became the {@code fromBuf} static reader
 * and {@link #write(FriendlyByteBuf)}. The bytes on the wire are identical and in the same order,
 * so nothing else has to change.
 */
public record TellusWeatherPayload(
   boolean weatherEnabled,
   TellusRealtimeState.PrecipitationMode precipitationMode,
   boolean historicalSnowEnabled,
   int centerX,
   int centerZ,
   int spacingBlocks,
   float[] snowIndex
) implements FabricPacket {

   public static final PacketType<TellusWeatherPayload> TYPE = PacketType.create(
      new ResourceLocation("tellus", "realtime_weather"), TellusWeatherPayload::fromBuf
   );

   private static final int GRID_POINTS = 9;

   public TellusWeatherPayload {
      if (snowIndex != null && snowIndex.length == GRID_POINTS) {
         snowIndex = Arrays.copyOf(snowIndex, GRID_POINTS);
      } else {
         snowIndex = new float[GRID_POINTS];
      }
   }

   /** Defensive copy: the grid is fixed length, but the record must stay immutable. */
   @Override
   public float[] snowIndex() {
      return Arrays.copyOf(this.snowIndex, this.snowIndex.length);
   }

   @Override
   public void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.weatherEnabled);
      buffer.writeByte(encodePrecipitation(this.precipitationMode));
      buffer.writeBoolean(this.historicalSnowEnabled);
      buffer.writeVarInt(this.centerX);
      buffer.writeVarInt(this.centerZ);
      buffer.writeVarInt(this.spacingBlocks);

      for (int i = 0; i < GRID_POINTS; i++) {
         buffer.writeFloat(this.snowIndex[i]);
      }
   }

   private static TellusWeatherPayload fromBuf(FriendlyByteBuf buffer) {
      boolean weatherEnabled = buffer.readBoolean();
      byte precipitationId = buffer.readByte();
      boolean historicalSnowEnabled = buffer.readBoolean();
      int centerX = buffer.readVarInt();
      int centerZ = buffer.readVarInt();
      int spacingBlocks = buffer.readVarInt();
      float[] snowIndex = new float[GRID_POINTS];

      for (int i = 0; i < GRID_POINTS; i++) {
         snowIndex[i] = buffer.readFloat();
      }

      TellusRealtimeState.PrecipitationMode mode = decodePrecipitation(precipitationId);
      return new TellusWeatherPayload(weatherEnabled, mode, historicalSnowEnabled, centerX, centerZ, spacingBlocks, snowIndex);
   }

   @Override
   public PacketType<?> getType() {
      return TYPE;
   }

   private static byte encodePrecipitation(TellusRealtimeState.PrecipitationMode mode) {
      return mode == null ? 0 : (byte)mode.ordinal();
   }

   private static TellusRealtimeState.PrecipitationMode decodePrecipitation(byte id) {
      TellusRealtimeState.PrecipitationMode[] values = TellusRealtimeState.PrecipitationMode.values();
      int index = id < 0 ? 0 : Math.min(id, values.length - 1);
      return values[index];
   }
}
