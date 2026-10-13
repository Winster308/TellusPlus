package com.yucareux.tellus;

import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Objects;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.yucareux.tellus.client.TellusMiniMapHud;
import com.yucareux.tellus.client.preload.TellusPreloadClientState;
import com.yucareux.tellus.network.TellusCoordsPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.yucareux.tellus.network.TellusPreloadStatePayload;

@Environment(EnvType.CLIENT)
public class TellusClient implements ClientModInitializer {
   @Override
   public void onInitializeClient() {
      ClientPlayNetworking.registerGlobalReceiver(Objects.requireNonNull(GeoTpOpenMapPayload.TYPE, "GeoTpOpenMapPayload.TYPE"), (payload, context) -> context.client().execute(() -> {
         Minecraft minecraft = context.client();
         Screen parent = minecraft.screen;
         minecraft.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude()));
      }));
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusWeatherPayload.TYPE, "TellusWeatherPayload.TYPE"),
         (payload, context) -> context.client()
            .execute(
               () -> {
                  SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
                     ? new SnowGrid(payload.centerX(), payload.centerZ(), payload.spacingBlocks(), payload.snowIndex())
                     : SnowGrid.empty();
                  TellusRealtimeState.updateWeatherState(payload.weatherEnabled(), payload.precipitationMode(), payload.historicalSnowEnabled(), grid);
               }
            )
      );
      // "Where am I": the P key shows the current position (coexists with the Xaero minimap)
      TellusMiniMapHud.init();
      ClientTickEvents.END_CLIENT_TICK.register(TellusMiniMapHud::tick);
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusCoordsPayload.TYPE, "TellusCoordsPayload.TYPE"),
         (payload, context) -> context.client().execute(() -> TellusMiniMapHud.accept(payload))
      );

      // Downloaded-extent snapshot (pushed by the server on join / when the map screen opens): used for map shading and area stats
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusPreloadStatePayload.TYPE, "TellusPreloadStatePayload.TYPE"),
         (payload, context) -> context.client()
            .execute(() -> TellusPreloadClientState.acceptFromServer(payload.regionsJson()))
      );
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TellusRealtimeState.clearRealtimeWeather());
   }
}
