package com.yucareux.tellus.network;

import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

public record TellusWeatherPayload(
   boolean weatherEnabled,
   TellusRealtimeState.PrecipitationMode precipitationMode,
   boolean historicalSnowEnabled,
   int centerX,
   int centerZ,
   int spacingBlocks,
   float[] snowIndex
) implements CustomPacketPayload {
   
   public static final CustomPacketPayload.Type<TellusWeatherPayload> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath("tellus", "realtime_weather")
   );
   private static final int GRID_POINTS = 9;
   public static final StreamCodec<FriendlyByteBuf, TellusWeatherPayload> CODEC = new StreamCodec<FriendlyByteBuf, TellusWeatherPayload>() {
      public TellusWeatherPayload decode(FriendlyByteBuf buffer) {
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

         TellusRealtimeState.PrecipitationMode mode = TellusWeatherPayload.decodePrecipitation(precipitationId);
         return new TellusWeatherPayload(weatherEnabled, mode, historicalSnowEnabled, centerX, centerZ, spacingBlocks, snowIndex);
      }

      public void encode(FriendlyByteBuf buffer, TellusWeatherPayload value) {
         buffer.writeBoolean(value.weatherEnabled());
         buffer.writeByte(TellusWeatherPayload.encodePrecipitation(value.precipitationMode()));
         buffer.writeBoolean(value.historicalSnowEnabled());
         buffer.writeVarInt(value.centerX());
         buffer.writeVarInt(value.centerZ());
         buffer.writeVarInt(value.spacingBlocks());
         float[] snowIndex = value.snowIndex();
         if (snowIndex == null || snowIndex.length < GRID_POINTS) {
            snowIndex = new float[GRID_POINTS];
         }

         for (int i = 0; i < GRID_POINTS; i++) {
            buffer.writeFloat(snowIndex[i]);
         }
      }
   };

   public TellusWeatherPayload(
      boolean weatherEnabled,
      TellusRealtimeState.PrecipitationMode precipitationMode,
      boolean historicalSnowEnabled,
      int centerX,
      int centerZ,
      int spacingBlocks,
      float[] snowIndex
   ) {
      if (snowIndex != null && snowIndex.length == GRID_POINTS) {
         snowIndex = Arrays.copyOf(snowIndex, GRID_POINTS);
      } else {
         snowIndex = new float[GRID_POINTS];
      }

      this.weatherEnabled = weatherEnabled;
      this.precipitationMode = precipitationMode;
      this.historicalSnowEnabled = historicalSnowEnabled;
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.spacingBlocks = spacingBlocks;
      this.snowIndex = snowIndex;
   }

   
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
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
