package com.yucareux.tellus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.integration.distant_horizons.DistantHorizonsIntegration;
import com.yucareux.tellus.integration.voxy.TellusVoxyPregenManager;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.TellusCoordsPayload;
import com.yucareux.tellus.network.TellusCoordsRequestPayload;
import com.yucareux.tellus.network.TellusPreloadRequestPayload;
import com.yucareux.tellus.network.TellusPreloadStatePayload;
import com.yucareux.tellus.preload.ArnisPreloader;
import com.yucareux.tellus.preload.PreloadLayers;
import com.yucareux.tellus.preload.PreloadStorage;
import com.yucareux.tellus.network.TellusPreloadDeletePayload;
import java.util.LinkedHashSet;
import com.yucareux.tellus.preload.TellusPreloadRegistry;
import com.yucareux.tellus.worldgen.TellusAreaPreloader;
import java.util.Set;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.realtime.TellusRealtimeManager;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.core.Holder;
import net.minecraft.core.Holder.Reference;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yucareux.tellus.world.data.source.TellusDownloadExecutor;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.TellusRegions;

public class Tellus implements ModInitializer {

   public static final String MOD_ID = "tellus";
   private static final String DYNAMIC_DIMENSION_PACK_NAME = "tellus_dynamic_dimension";
   private static final String DYNAMIC_DIMENSION_PACK_ID = "file/tellus_dynamic_dimension";

   private static final ResourceLocation EARTH_DIMENSION_ID = Objects.requireNonNull(ResourceLocation.fromNamespaceAndPath("tellus", "earth"), "earthDimensionId");

   private static final ResourceLocation DYNAMIC_DIMENSION_ID = Objects.requireNonNull(
      ResourceLocation.fromNamespaceAndPath("tellus", "earth_dynamic"), "dynamicDimensionId"
   );

   private static final ResourceKey<DimensionType> EARTH_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, EARTH_DIMENSION_ID), "earthDimensionKey"
   );

   private static final ResourceKey<DimensionType> DYNAMIC_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, DYNAMIC_DIMENSION_ID), "dynamicDimensionKey"
   );
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final TellusRealtimeManager REALTIME_MANAGER = new TellusRealtimeManager();
   /**
    *  2026-09-23: Voxy pre-generation's **lifecycle has moved to its own mixin**
    *   ({@code com.yucareux.tellus.mixin.voxy.VoxyServerTickMixin} )
    *
    * <p>It used to construct `new TellusVoxyPregenManager()` right here and register its tick in this class,
    * shutting it down in SERVER_STOPPING -- Voxy's state was welded to the main entry point.
    * Now **the instance is held by the mixin**; this class only borrows an accessor for the commands / config screen
    * (they read state and set overrides; they take no part in the lifecycle).
    */
   private static TellusVoxyPregenManager voxyPregen() {
      return com.yucareux.tellus.integration.voxy.VoxyPregenHost.get();
   }
   public static final Logger LOGGER = LoggerFactory.getLogger("tellus");


   public static ResourceLocation id( String path) {
      return Objects.requireNonNull(ResourceLocation.fromNamespaceAndPath("tellus", path), "identifier");
   }

   public void onInitialize() {
      Registry.register(BuiltInRegistries.BIOME_SOURCE, id("earth"), EarthBiomeSource.CODEC);
      Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
      PayloadTypeRegistry.playC2S().register(GeoTpTeleportPayload.TYPE, GeoTpTeleportPayload.CODEC);
      PayloadTypeRegistry.playS2C().register(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC);
      PayloadTypeRegistry.playS2C().register(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC);
      PayloadTypeRegistry.playC2S().register(TellusPreloadRequestPayload.TYPE, TellusPreloadRequestPayload.CODEC);
      PayloadTypeRegistry.playC2S().register(TellusCoordsRequestPayload.TYPE, TellusCoordsRequestPayload.CODEC);
      PayloadTypeRegistry.playS2C().register(TellusCoordsPayload.TYPE, TellusCoordsPayload.CODEC);
      PayloadTypeRegistry.playS2C().register(TellusPreloadStatePayload.TYPE, TellusPreloadStatePayload.CODEC);
      PayloadTypeRegistry.playC2S().register(TellusPreloadDeletePayload.TYPE, TellusPreloadDeletePayload.CODEC);
      ServerPlayNetworking.registerGlobalReceiver(GeoTpTeleportPayload.TYPE, Tellus::handleGeoTeleport);
      ServerPlayNetworking.registerGlobalReceiver(TellusPreloadRequestPayload.TYPE, Tellus::handlePreloadRequest);
      ServerPlayNetworking.registerGlobalReceiver(TellusCoordsRequestPayload.TYPE, Tellus::handleCoordsRequest);
      ServerPlayNetworking.registerGlobalReceiver(TellusPreloadDeletePayload.TYPE, Tellus::handlePreloadDelete);
      CommandRegistrationCallback.EVENT
         .register(
            (dispatcher, registryAccess, environment) -> dispatcher.register(
               ((Commands.literal("tellus")
                        .then(
                           (Commands.literal("map")
                                 .requires(source -> source.hasPermission(2)))
                              .executes(context -> openGeoTpMap((CommandSourceStack)context.getSource(), null, null))
                              .then(
                                 Commands.argument("latitude", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                    .then(
                                       Commands.argument("longitude", DoubleArgumentType.doubleArg(-180.0, 180.0))
                                          .executes(
                                             context -> openGeoTpMap(
                                                (CommandSourceStack)context.getSource(),
                                                DoubleArgumentType.getDouble(context, "latitude"),
                                                DoubleArgumentType.getDouble(context, "longitude")
                                             )
                                          )
                                    )
                              )
                        )
                     .then(
                        Commands.literal("region")
                           .executes(context -> describeRegion((CommandSourceStack)context.getSource()))
                     )
                     .then(
                        (Commands.literal("tp")
                              .requires(source -> source.hasPermission(2)))
                           .then(
                              Commands.argument("latitude", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                 .then(
                                    Commands.argument("longitude", DoubleArgumentType.doubleArg(-180.0, 180.0))
                                       .executes(
                                          context -> teleportToCoordinates(
                                             (CommandSourceStack)context.getSource(),
                                             DoubleArgumentType.getDouble(context, "latitude"),
                                             DoubleArgumentType.getDouble(context, "longitude")
                                          )
                                       )
                                 )
                           )
                     ))
                     .then(
                        // 2026-10-06: re-runs the material-pool self-check on demand. The same
                        // assertions run once at startup; this exists so a failure can be investigated
                        // without a restart and read from a live session.
                        (Commands.literal("verify")
                              .requires(source -> source.hasPermission(2)))
                           .executes(context -> runMaterialSelfCheck((CommandSourceStack)context.getSource()))
                     )
                     .then(
                        (Commands.literal("storage")
                              .requires(source -> source.hasPermission(2)))
                           .executes(context -> showStorage((CommandSourceStack)context.getSource()))
                           .then(
                              Commands.literal("clear")
                                 .then(
                                    Commands.argument("source", StringArgumentType.word())
                                       .suggests(PRELOAD_SOURCES)
                                       .executes(
                                          context -> clearStorage(
                                             (CommandSourceStack)context.getSource(),
                                             StringArgumentType.getString(context, "source")
                                          )
                                       )
                                 )
                           )
                           .then(
                              Commands.literal("delete")
                                 .then(
                                    Commands.argument("index", IntegerArgumentType.integer(0, 100000))
                                       .executes(
                                          context -> deleteStorageRegion(
                                             (CommandSourceStack)context.getSource(),
                                             IntegerArgumentType.getInteger(context, "index")
                                          )
                                       )
                                 )
                           )
                           .then(
                              Commands.literal("open")
                                 .executes(context -> openManageHint((CommandSourceStack)context.getSource()))
                           )
                     )
                     .then(
                        (Commands.literal("preload")
                              .requires(source -> source.hasPermission(2)))
                           .executes(context -> showPreloadStatus((CommandSourceStack)context.getSource()))
                           .then(
                              Commands.argument("lat1", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                 .then(
                                    Commands.argument("lon1", DoubleArgumentType.doubleArg(-180.0, 180.0))
                                       .then(
                                          Commands.argument("lat2", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                             .then(
                                                Commands.argument("lon2", DoubleArgumentType.doubleArg(-180.0, 180.0))
                                                   .executes(
                                                      context -> preloadAreaCommand(
                                                         (CommandSourceStack)context.getSource(),
                                                         DoubleArgumentType.getDouble(context, "lat1"),
                                                         DoubleArgumentType.getDouble(context, "lon1"),
                                                         DoubleArgumentType.getDouble(context, "lat2"),
                                                         DoubleArgumentType.getDouble(context, "lon2"),
                                                         ""
                                                      )
                                                   )
                                                   .then(
                                                      Commands.argument("layers", StringArgumentType.word())
                                                         .executes(
                                                            context -> preloadAreaCommand(
                                                               (CommandSourceStack)context.getSource(),
                                                               DoubleArgumentType.getDouble(context, "lat1"),
                                                               DoubleArgumentType.getDouble(context, "lon1"),
                                                               DoubleArgumentType.getDouble(context, "lat2"),
                                                               DoubleArgumentType.getDouble(context, "lon2"),
                                                               StringArgumentType.getString(context, "layers")
                                                            )
                                                         )
                                                   )
                                             )
                                       )
                                 )
                           )
                     )
                     .then(
                        (Commands.literal("weather")
                              .executes(context -> showTellusWeather((CommandSourceStack)context.getSource())))
                           .then(
                              Commands.literal("enable_realtime_time")
                                 .requires(source -> source.hasPermission(2))
                                 .then(
                                    Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                       .executes(
                                          context -> setRealtimeTimeOverride(
                                             (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                          )
                                       )
                                 )
                           )
                           .then(
                              Commands.literal("enable_realtime_weather")
                                 .requires(source -> source.hasPermission(2))
                                 .then(
                                    Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                       .executes(
                                          context -> setRealtimeWeatherOverride(
                                             (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                          )
                                       )
                                 )
                           )
                     ))
                  .then(
                     ((Commands.literal("config")
                              .requires(source -> source.hasPermission(2)))
                           .then(
                              (Commands.literal("weather")
                                    .then(
                                       Commands.literal("enable_realtime_time")
                                          .then(
                                             Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                                .executes(
                                                   context -> setRealtimeTimeOverride(
                                                      (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                   )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("enable_realtime_weather")
                                       .then(
                                          Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                             .executes(
                                                context -> setRealtimeWeatherOverride(
                                                   (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                )
                                             )
                                       )
                                 )
                           ))
                        .then(
                           ((((Commands.literal("voxy")
                                          // **2026-10-05 fix**: `status` revealed the whole server's
                                          // shared pre-generation state and queue depth to any player,
                                          // while every sibling that mutates that same state already
                                          // required permission 2 (see the note below). Read-only is not
                                          // harmless here: it is shared, server-wide state.
                                          .then((Commands.literal("status")
                                                .requires(source -> source.hasPermission(2)))
                                             .executes(context -> showVoxyPregenStatus((CommandSourceStack)context.getSource()))))
                                       .then(
                                          Commands.literal("enable_pregen")
                                             //  It mutates the **server-wide shared** pre-generation state (the properties file's comment
                                             //  records "one server tick stalls for 40 seconds"), so like map/tp/storage/preload it must be permission-gated
                                             .requires(source -> source.hasPermission(2))
                                             .then(
                                                Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                                   .executes(
                                                      context -> setVoxyPregenEnabledOverride(
                                                         (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                      )
                                                   )
                                             )
                                       ))
                                    .then(
                                       Commands.literal("max_radius")
                                          .requires(source -> source.hasPermission(2))
                                          .then(
                                             Commands.argument("chunks", Objects.requireNonNull(IntegerArgumentType.integer(0, 1024), "maxRadiusArgument"))
                                                .executes(
                                                   context -> setVoxyPregenMaxRadiusOverride(
                                                      (CommandSourceStack)context.getSource(), IntegerArgumentType.getInteger(context, "chunks")
                                                   )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("chunks_per_tick")
                                       .requires(source -> source.hasPermission(2))
                                       .then(
                                          Commands.argument("value", Objects.requireNonNull(IntegerArgumentType.integer(1, 200), "chunksPerTickArgument"))
                                             .executes(
                                                context -> setVoxyPregenChunksPerTickOverride(
                                                   (CommandSourceStack)context.getSource(), IntegerArgumentType.getInteger(context, "value")
                                                )
                                             )
                                       )
                                 ))
                              .then(Commands.literal("reset").requires(source -> source.hasPermission(2))
                                 .executes(context -> resetVoxyPregenOverrides((CommandSourceStack)context.getSource())))
                        )
                  )
            )
         );
      //  Purely mathematical preset spawn point (found in round 60; round 62's sampling version was reverted)
      //   vanilla `prepareLevels` prepares chunks around getSharedSpawnPos **before** SERVER_STARTED,
      //   at a moment when Tellus has set no spawn yet -> it prepares around (0,0) (= the sea surface
      //   at 0°N/0°E; the `zoom=15, x=16383...` tile requests in the log are exactly it)
      //   -> nothing but empty sea tiles -> chunk [0,0] fillFromNoise silently runs for 2 minutes
      //   -> lighting thread NPE -> the world cannot be entered. **Nothing here samples or touches the network**:
      //   only lat/lon x the projection formula gives the block coords, and Y is a heightOffset+1 placeholder that SERVER_STARTED overwrites with the exact surface Y.
      ServerLifecycleEvents.SERVER_STARTING.register(server -> {
         try {
            ServerLevel world = server.getLevel(Level.OVERWORLD);
            if (world == null) {
               return;
            }
            if (!(world.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
               return;
            }
            EarthGeneratorSettings s = earthGenerator.settings();
            double bpd = com.yucareux.tellus.worldgen.EarthProjection.blocksPerDegree(s.worldScale());
            int bx = (int) Math.floor(s.spawnLongitude() * bpd);
            int bz = (int) Math.floor(com.yucareux.tellus.worldgen.EarthProjection.latToBlockZ(
               s.spawnLatitude(), s.worldScale()));
            BlockPos spawn = new BlockPos(bx, s.heightOffset() + 1, bz);
            world.setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData.of(
               world.dimension(), spawn, 0.0F, 0.0F));
            LOGGER.info("[TellusCN] preset spawn point set at the chosen position during SERVER_STARTING: {}"
               + " = latitude {:.4f}, longitude {:.4f} (purely mathematical, no sampling; vanilla prepares chunks here)",
               spawn, s.spawnLatitude(), s.spawnLongitude());
         } catch (Throwable t) {
            LOGGER.warn("[TellusCN] failed to set the preset spawn point (harmless, retried at SERVER_STARTED): {}", t.toString());
         }
      });

      ServerLifecycleEvents.SERVER_STARTED.register((ServerStarted)server -> server.execute(() -> {
         ServerLevel world = server.getLevel(Level.OVERWORLD);
         if (world != null) {
            ChunkGenerator generator = world.getChunkSource().getGenerator();
            // Self-proof for the multi-threaded download layer: the pool size is visible in the log; add -Dtellus.download.threads=1 and run again to compare
            LOGGER.info("multi-threaded download: {} (override with -Dtellus.download.threads=N)", TellusDownloadExecutor.describe());
            try {
               // Pure logging only: even if it throws (e.g. the registry has no level stem), it must not take down the real
               // work below: setting the spawn / writing the dynamic dimension pack / widening the world border
               logOverworldSettings(server, world, generator);
            } catch (Throwable t) {
               LOGGER.warn("[TellusCN] failed to log overworld settings (harmless): {}", t.toString());
            }
            applyVoxyPregenProperties();
            probeCarvingMaskCapacity();
            checkVoxyCacheConsistency(server);

            // 2026-10-06: the follow-prefetch start goes here, **before** the `getSpawnPosition` call below.
            //
            // The previous version put it at the top of startConfiguredAutoPrefetch, and that method is the last line of
            // the same server.execute lambda in SERVER_STARTED -- the getSpawnPosition call on line 430 above
            // throws (measured: in a brand-new world with no land-cover tiles, TellusLandCoverSource.sampleCoverClass
            // does throw), the whole lambda is interrupted, the follow thread never starts, and **there is not a single
            // related line in the log**, which looks like "the feature shipped but does not work".
            //
            // Of those three methods only these are side-effect-free pure logging / probes; putting the thread start after them
            // and before the fragile call means downstream exceptions can no longer swallow it.
            if (world != null && Boolean.parseBoolean(System.getProperty("tellus.preload.follow", "false"))) {
               startFollowPlayerPrefetch(server, world, 0);
            }

            if (generator instanceof EarthChunkGenerator earthGenerator) {
               // 2026-10-06: getSpawnPosition goes through resolveSpawnCoverClassSafely -> sampleCoverClass
               // to ask the land-cover source, which can throw while the tiles are not in place yet. **After** it come setting
               // the respawn point, the dynamic dimension pack, the world border and the warm-up; one exception used to take
               // all of them down (measured: the whole spawn-related log section goes missing). Better to let just this one fail.
               BlockPos spawn = null;
               try {
                  spawn = Objects.requireNonNull(earthGenerator.getSpawnPosition(world), "spawnPosition");
                  world.setRespawnData(RespawnData.of(world.dimension(), spawn, 0.0F, 0.0F));
                  // Reuse the spawn already computed above (no recomputation)
                  beginSpawnChunkCheck(world, spawn);
               } catch (Throwable t) {
                  LOGGER.warn("[TellusCN] failed to resolve the spawn point (harmless, the world loads as usual, respawn data unchanged): {}", t.toString());
               }
               LOGGER.info("[TellusCN] native kernel status: {}", com.yucareux.tellus.worldgen.TellusNative.isAvailable() ? "loaded (Rust bilinear sampling available)" : "not loaded (using the Java sampling path)");
               reportAutoWorldTop(earthGenerator);
               ensureDynamicDimensionPack(server, world.dimensionTypeRegistration(), world.dimensionType(), earthGenerator);
               widenWorldBorderIfUntouched(server, world);
            }
            //  If the config says preload.auto=true, download the raw map data into the cache here
            startConfiguredAutoPrefetch(server, world);
         }
      }));
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         //  After shutdown, stop the tick callback from querying an already-stopped level (reopening the world leaks into the new tick loop)
         spawnCheckWorld = null;
         spawnCheckPos = null;
         //  Background download/cleanup threads: killed at shutdown (field nulled, rebuilt automatically when a single-player world is reopened)
         shutdownBackgroundExecutor();
         //  Shutdown of the Voxy pre-generation is handled by VoxyServerTickMixin (see the stopServer injection)
         REALTIME_MANAGER.onServerStopping(server);
      });
      ServerTickEvents.END_SERVER_TICK.register(REALTIME_MANAGER::onServerTick);
      //  The Voxy pre-generation tick is already injected into MinecraftServer.tickServer by VoxyServerTickMixin
      //   (registering it again here would **run twice every tick**, so it must stay deleted)
      ServerTickEvents.END_SERVER_TICK.register(server -> tickSpawnChunkCheck());
      //  Villagers in cities (the MISC category does not use vanilla natural spawning, so controlled spawning has
      //   to be done by hand); it has its own rate limit (every 5 seconds by default), only touches loaded chunks, never blocks the server thread
      ServerTickEvents.END_SERVER_TICK.register(com.yucareux.tellus.worldgen.TellusCityVillagers::onServerTick);
      ServerTickEvents.END_SERVER_TICK.register(server -> {
         for (ServerLevel level : server.getAllLevels()) {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (generator instanceof EarthChunkGenerator earthGenerator) {
               earthGenerator.processDeferredChunkDetailTick(level);
            }
         }
      });
      ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
         ChunkGenerator generator = level.getChunkSource().getGenerator();
         if (generator instanceof EarthChunkGenerator earthGenerator) {
            earthGenerator.discardPreparedChunkState(chunk.getPos());
         }
      });
      ServerPlayConnectionEvents.JOIN.register((Join)(handler, sender, server) -> {
         REALTIME_MANAGER.onPlayerJoin(server, handler.getPlayer());
         // Push the already-downloaded area to the player who just joined, so the map colours correctly
         ServerPlayNetworking.send(handler.getPlayer(),
               new TellusPreloadStatePayload(TellusPreloadRegistry.toJson()));
      });
      if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
         DistantHorizonsIntegration.bootstrap();
      }

      LOGGER.info("Tellus world generation initialised");
      // Print the multi-threaded download layer's configuration at startup, so it can be compared against -Dtellus.download.threads=1
      LOGGER.info("multi-threaded download: {}", TellusDownloadExecutor.describe());
   }

   private static int openGeoTpMap(CommandSourceStack source, Double requestedLatitude, Double requestedLongitude) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.literal("Tellus: GeoTP 地图只能由玩家使用。"));
         return 0;
      } else {
         ServerLevel level = player.level();
         if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
            // No arguments = centre on the player when the map is opened (original behaviour); with arguments = centre directly on the given lat/lon.
            double latitude = requestedLatitude != null
               ? clampLatitude(requestedLatitude)
               : clampLatitude(earthGenerator.latitudeFromBlock(player.getZ()));
            double longitude = requestedLongitude != null
               ? clampLongitude(requestedLongitude)
               : clampLongitude(earthGenerator.longitudeFromBlock(player.getX()));
            ServerPlayNetworking.send(player, new GeoTpOpenMapPayload(latitude, longitude));
            return 1;
         } else {
            source.sendFailure(Component.literal("Tellus: GeoTP 地图仅在 Tellus 世界中可用。"));
            return 0;
         }
      }
   }

   /**
    * {@code /tellus tp <latitude> <longitude>}: teleport straight to the surface at that latitude/longitude.
    *
    * <p>It resolves the landing spot through the same {@link EarthChunkGenerator#getSurfacePosition} as "Teleport"
    * in the map screen, so both land in the same place; no need to open that screen, and no need to wait for the map.
    */
   private static int teleportToCoordinates(CommandSourceStack source, double requestedLatitude, double requestedLongitude) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.literal("Tellus: /tellus tp 只能由玩家使用。"));
         return 0;
      }
      ServerLevel level = player.level();
      if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
         source.sendFailure(Component.literal("Tellus: /tellus tp 仅在 Tellus 世界中可用。"));
         return 0;
      }
      double latitude = clampLatitude(requestedLatitude);
      double longitude = clampLongitude(requestedLongitude);
      BlockPos target = earthGenerator.getSurfacePosition(level, latitude, longitude);
      // Land one block above the surface so you do not get stuck in the terrain
      double targetY = Math.max(level.getMinY() + 2, target.getY() + 1.0);
      player.teleportTo(target.getX() + 0.5, targetY, target.getZ() + 0.5);
      source.sendSuccess(
         () -> Component.literal(String.format(Locale.ROOT,
            "Tellus: 已传送到 %.6f, %.6f（地表 Y=%d)", latitude, longitude, target.getY())),
         false
      );
      return 1;
   }


   /** {@code /tellus preload}: show the current ledger (how many areas, how many square kilometres in total).  */
   private static int showPreloadStatus(CommandSourceStack source) {
      double area = TellusPreloadRegistry.downloadedAreaKm2();
      int count = TellusPreloadRegistry.regionCount();
      source.sendSuccess(
         () -> Component.literal(String.format(java.util.Locale.ROOT,
               "Tellus 预加载: 已记录 %d 个区域，合计约 %.1f 平方公里（默认图层: 建筑 + 道路）", count, area)),
         false
      );
      for (TellusPreloadRegistry.Region region : TellusPreloadRegistry.regions()) {
         source.sendSuccess(() -> Component.literal("  " + region.describe(0)), false);
      }
      return 1;
   }

   /** {@code /tellus preload <minLat> <minLon> <maxLat> <maxLon> [layers]}: download that area.  */
   private static int preloadAreaCommand(CommandSourceStack source, double lat1, double lon1,
                                         double lat2, double lon2, String layers) {
      EarthChunkGenerator generator = resolveEarthGenerator(source);
      if (generator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus preload 仅在 Tellus 世界中可用。"));
         return 0;
      }
      Set<String> selected = PreloadLayers.parse(layers);
      final MinecraftServer server = source.getServer();
      if (server == null) {
         source.sendFailure(Component.literal("Tellus: 服务器还没准备好。"));
         return 0;
      }
      // Downloading never happens on the server thread (see startBackgroundPreload)
      source.sendSuccess(() -> Component.literal(
         "Tellus: 开始后台预加载 纬度 " + Math.min(lat1, lat2) + "~" + Math.max(lat1, lat2)
            + "，经度 " + Math.min(lon1, lon2) + "~" + Math.max(lon1, lon2)
            + "（下载期间服务器照常运行，结束后会在这里回话）"), false);
      startBackgroundPreload(server,
         summary -> source.sendSuccess(() -> Component.literal("Tellus: " + summary), false),
         lat1, lon1, lat2, lon2, selected);
      return 1;
   }

   /**
    * {@code P} key asks "where am I": the server computes latitude/longitude, real elevation and scale and sends them back.
    *
    * <p>It runs once per key press (a dozen bytes), not by polling; and the conversion needs the generator instance,
    * which the client cannot get, so the server has to do the math.
    */
   /**
    * Coordinate queries that are already queued / running (deduplicated per player).
    *
    * <p><b>It may only be touched on the server main thread</b> -- {@link #handleCoordsRequest} puts both the add
    * and the remove inside {@code server.execute(...)}, so no synchronisation is needed.
    */
   private static final java.util.Set<java.util.UUID> coordsRequestsInFlight = new java.util.HashSet<>();

   private static void handleCoordsRequest(TellusCoordsRequestPayload payload, ServerPlayNetworking.Context context) {
      ServerPlayer player = context.player();
      MinecraftServer server = player.level().getServer();
      if (server == null) {
         return;
      }
      server.execute(() -> {
         //  Merge: if this player already has one queued / running, fold this one into it.
         //  The answer reads player.blockPosition live **at the moment of execution**, so the request that got folded in
         //  still gets the newest position; the client side is "last one wins" (TellusCoordsPayload carries no nonce),
         //  one duplicate popup fewer is imperceptible, and what is saved is one DEM sample + one water-column parse
         //  (with no cached tile those two steps are a ten-plus-millisecond stall on the main thread; repeated P presses stack up).
         if (!coordsRequestsInFlight.add(player.getUUID())) {
            return;
         }
         try {
            ServerLevel level = player.level();
            if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
               ServerPlayNetworking.send(player, new TellusCoordsPayload(0.0, 0.0, 0, 0.0, 0.0, false));
               return;
            }
            BlockPos pos = player.blockPosition();
            double latitude = clampLatitude(generator.latitudeFromBlock(pos.getZ()));
            double longitude = clampLongitude(generator.longitudeFromBlock(pos.getX()));
            double worldScale = generator.settings().worldScale();
            BlockPos surface = generator.getSurfacePosition(level, latitude, longitude);
            double elevation = TellusWorldgenSources.elevation()
                  .sampleElevationMeters(pos.getX(), pos.getZ(), worldScale);
            ServerPlayNetworking.send(player, new TellusCoordsPayload(
                  latitude, longitude, surface.getY(), elevation, worldScale, true));
         } finally {
            coordsRequestsInFlight.remove(player.getUUID());
         }
      });
   }

   /** Preload request sent by the client after a box selection.  */
   private static void handlePreloadRequest(TellusPreloadRequestPayload payload, ServerPlayNetworking.Context context) {
      ServerPlayer player = context.player();
      MinecraftServer server = player.level().getServer();
      if (server == null) {
         return;
      }
      // Degenerate rectangle (min==max) = the client just wants a fresh snapshot (refreshed when the map screen is opened)
      boolean stateOnly = payload.maxLat() <= payload.minLat() || payload.maxLon() <= payload.minLon();
      server.execute(() -> {
         if (stateOnly) {
            ServerPlayNetworking.send(player, new TellusPreloadStatePayload(TellusPreloadRegistry.toJson()));
            return;
         }
         if (!player.createCommandSourceStack().hasPermission(2)) {
            player.sendSystemMessage(Component.literal("Tellus: 预加载需要管理员权限。"));
            return;
         }
         ServerLevel level = player.level();
         if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
            player.sendSystemMessage(Component.literal("Tellus: 预加载仅在 Tellus 世界中可用。"));
            return;
         }
         Set<String> layers = PreloadLayers.parse(payload.layers());
         // Downloading never happens on the server thread (see startBackgroundPreload)
         player.sendSystemMessage(Component.literal("Tellus: 开始后台预加载，下载期间服务器照常运行，完成后会自动回报。"));
         startBackgroundPreload(server, summary -> {
            player.sendSystemMessage(Component.literal("Tellus: " + summary));
            // Push the latest ledger back: the client recolours from it and updates the area
            ServerPlayNetworking.send(player, new TellusPreloadStatePayload(TellusPreloadRegistry.toJson()));
         }, payload.minLat(), payload.minLon(), payload.maxLat(), payload.maxLon(), layers);
      });
   }


   /**
    *  Config-driven automatic pre-download (added 2026-09-20)
    *
    * <p>What the user wants is: <b>download the "raw map data" of an area into the cache, without going into the world,
    * flying there or generating chunks</b>. {@code /tellus preload <lat1> <lon1> <lat2> <lon2> [layers]} already does that, but it
    * has to be typed from inside the world; here it runs **automatically after the server starts**.
    *
     * <p>Written directly into {@code config/telluscn-layout.properties}
    * (any key of that file is turned into {@code -Dtellus.<key>} by {@code TellusPreLaunch}):
    *
    * <pre>
    * preload.auto=true
    * preload.regions=Guangzhou=22.90,112.90,23.50,113.65;EastChina=29.0,117.0,33.0,122.0,buildings,elevation
    * preload.delaySeconds=20
    * </pre>
    *
    * <p>Format: {@code name=minLat,minLon,maxLat,maxLon[,layers...]}, several entries separated by {@code ;}.
    * Layers omitted = the defaults (buildings + roads); write {@code all} for every layer.
    * The four lat/lon numbers may be written **in any order** (they are sorted into min/max internally).
    *
    * <p>Switch: the whole download runs on a **daemon thread**, it never occupies the server thread.
    * Hard rule of this project -- anything that "can switch itself on" must first be shown not to block the main thread
    * synchronously (that is how round 92's voxy pre-generation went down).
    */
   private static void startConfiguredAutoPrefetch(MinecraftServer server, ServerLevel world) {
      if (world == null || !(world.getChunkSource().getGenerator() instanceof EarthChunkGenerator)) {
         return;
      }

      if (!Boolean.parseBoolean(System.getProperty("tellus.preload.auto", "false"))) {
         return;
      }
      String spec = System.getProperty("tellus.preload.regions", "");
      if (spec == null || spec.isBlank()) {
         LOGGER.warn("[TellusCN] preload.auto=true but preload.regions is empty, nothing to download"
            + " (example: preload.regions=Guangzhou=22.90,112.90,23.50,113.65)");
         return;
      }
      int delaySeconds = 20;
      try {
         delaySeconds = Math.max(0, Integer.parseInt(System.getProperty("tellus.preload.delaySeconds", "20").trim()));
      } catch (NumberFormatException ignored) {
         // This used to be an empty catch: a typo silently used 20 seconds and the player never knew they had mistyped it
         LOGGER.warn("[TellusCN] preload.delaySeconds is not an integer ({}), falling back to 20 seconds",
            System.getProperty("tellus.preload.delaySeconds", ""));
      }
      final int delay = delaySeconds;
      final String specFinal = spec;
      Thread thread = new Thread(() -> {
         try {
            if (delay > 0) {
               // Let spawn-area generation grab the download pool first; the pre-download then fills in slowly (it takes neither bandwidth nor threads)
               Thread.sleep(delay * 1000L);
            }
            int total = 0;
            for (String part : specFinal.split(";")) {
               String entry = part.trim();
               if (entry.isEmpty()) {
                  continue;
               }
               int eq = entry.indexOf('=');
               if (eq <= 0) {
                  LOGGER.warn("[TellusCN] malformed preload region, skipped: {} (expected name=minLat,minLon,maxLat,maxLon[,layers...])", entry);
                  continue;
               }
               String name = entry.substring(0, eq).trim();
               String[] fields = entry.substring(eq + 1).split(",");
               if (fields.length < 4) {
                  LOGGER.warn("[TellusCN] preload region {} needs at least 4 numbers (minLat,minLon,maxLat,maxLon), skipped", name);
                  continue;
               }
               double[] v = new double[4];
               boolean ok = true;
               for (int i = 0; i < 4; i++) {
                  try {
                     v[i] = Double.parseDouble(fields[i].trim());
                  } catch (NumberFormatException e) {
                     LOGGER.warn("[TellusCN] preload region {}, field {} is not a number: {}", name, i + 1, fields[i]);
                     ok = false;
                     break;
                  }
               }
               if (!ok) {
                  continue;
               }
               double lat1 = Math.min(v[0], v[2]);
               double lat2 = Math.max(v[0], v[2]);
               double lon1 = Math.min(v[1], v[3]);
               double lon2 = Math.max(v[1], v[3]);
               StringBuilder layerCsv = new StringBuilder();
               for (int i = 4; i < fields.length; i++) {
                  String f = fields[i].trim();
                  if (f.isEmpty()) {
                     continue;
                  }
                  if (layerCsv.length() > 0) {
                     layerCsv.append(',');
                  }
                  layerCsv.append(f);
               }
               Set<String> layers = layerCsv.length() == 0
                  ? PreloadLayers.defaults()
                  : PreloadLayers.parse(layerCsv.toString());
               LOGGER.info("[TellusCN] automatic preload start: {} lat {}-{}, lon {}-{}, layers {} (raw data only, no chunk generation)",
                  name, lat1, lat2, lon1, lon2, PreloadLayers.format(layers));
               try {
                  LOGGER.info("[TellusCN] automatic preload done: {} -> {}", name, preloadArea(lat1, lon1, lat2, lon2, layers));
                  total++;
               } catch (Throwable t) {
                  LOGGER.warn("[TellusCN] automatic preload of {} failed (skipped, harmless): {}", name, t.toString());
               }
            }
            LOGGER.info("[TellusCN] automatic preload finished for all: {} regions (the cache is reused by chunk generation;"
               + "raising preload.delaySeconds or shrinking the areas makes it take effect earlier)", total);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (Throwable t) {
            LOGGER.warn("[TellusCN] automatic preload thread errored (harmless): {}", t.toString());
         }
      }, "tellus-autoprefetch");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      // Reopening a single-player world comes through here again : if the previous run is still going, do not stack another one, otherwise the same area gets downloaded twice
      if (autoPrefetchThread != null && autoPrefetchThread.isAlive()) {
         LOGGER.info("[TellusCN] the previous automatic preload round is still running (the cache directory is global), skipping the duplicate start");
         return;
      }
      autoPrefetchThread = thread;
      thread.start();
      int regionCount = 0;
      for (String part : spec.split(";")) {
         if (!part.isBlank()) {
            regionCount++;
         }
      }
      LOGGER.info("[TellusCN] automatic preload started from config ({} regions, {}s delay, daemon thread, does not block the server thread)",
         regionCount, delay);

   }

   /**
    * Why follow prefetch is needed (2026-10-06, added next to the switch above).
    *
    * <p>{@code preload.regions} is a **hard-coded lat/lon box** that quits once it is downloaded. That is right for
    * "I am going to Guangzhou later, warm it up first", and completely useless for **actually walking** around.
    *
    * <p>Client measurement on 2026-10-06: within minutes the player walked out of the
    * 22.75..23.45N / 112.95..113.70E box, and after that every tile was pulled live by the chunk generator
    * (z15, about 100 KB per tile) -- barely faster than walking. So the generator repeatedly sampled NaN, and
    * {@code fillMissingTerrainShellHeights} then **invented one uniform height** for the whole chunk, which is exactly
    *
    * <p>where the square bump comes from. The fix is not to drop {@code tile_zoom} one notch (that would throw away the
    * precision that is the whole point of this mod) but to **have the tiles already there**.
    */

   /**
    * Keep one square of data cached around the player, rebuilt only when they actually move.
    *
    * <p>2026-10-06. Four constraints, in the order they mattered:
    *
    * <ol>
    *   <li><b>Never re-download a square we already have.</b> The cache is global on disk, so
    *       re-fetching the same cell is pure waste and would starve the download pool. The loop
    *       remembers the last cell index pair and only acts when the player has left it.</li>
    *   <li><b>Never start a second one.</b> {@code /reload} and re-entering a world re-enter this
    *       method. The existing {@link #autoPrefetchThread} guard covers only the one-shot thread,
    *       so this has its own.</li>
    *   <li><b>Never block the server thread.</b> Daemon thread at MIN_PRIORITY; it only reads the
    *       player list's own position and calls the existing background download path. This
    *       project's hard rule is that anything which can start on its own must first be shown not
    *       to block the main thread (that is how round 92's Voxy pre-generation broke the server).</li>
    *   <li><b>Never spam the log.</b> One cell is {@link #FOLLOW_CELL_DEGREES} degrees across, so
    *       crossing one is a deliberate act; one INFO line per cell is the right amount.</li>
    * </ol>
    *
    * <p>Uses the generator's own {@code longitudeFromBlock}/{@code latitudeFromBlock} rather than
    * re-deriving the calibration, so it stays correct if the world scale or the offsets ever change.
    */
   private static void startFollowPlayerPrefetch(MinecraftServer server, ServerLevel world, int delaySeconds) {
      if (followPrefetchThread != null && followPrefetchThread.isAlive()) {
         return;
      }
      Thread thread = new Thread(() -> {
         try {
            if (delaySeconds > 0) {
               Thread.sleep(delaySeconds * 1000L);
            }
            // -1 rather than NaN: the first cell check must always fire, and comparing an int cell
            // index against -1 is well defined, whereas every NaN comparison is false.
            int lastCellLat = Integer.MIN_VALUE;
            int lastCellLon = Integer.MIN_VALUE;
            while (!Thread.currentThread().isInterrupted() && !server.isStopped()) {
               // 2026-10-06: PlayerList has no getPlayer(int) in 1.21.9 -- javap lists only
               // getPlayer(UUID) / getPlayer(String) / getPlayers(). Take the first entry of the
               // list. On a dedicated server there is normally exactly one player anyway.
               java.util.List<ServerPlayer> players = server.getPlayerList().getPlayers();
               ServerPlayer player = players.isEmpty() ? null : players.getFirst();
               if (player == null) {
                  Thread.sleep(FOLLOW_IDLE_SLEEP_MILLIS);
                  continue;
               }

               if (!(world.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
                  Thread.sleep(FOLLOW_IDLE_SLEEP_MILLIS);
                  continue;
               }

               // 2026-10-06: the accessor is blockPosition(), not getBlockPosition() -- javap on
               // Entity/ServerPlayer in 1.21.9 has no getBlockPosition. Line 619 of this same file
               // already used the right one; this method was written from memory and got it wrong.
               BlockPos playerPos = player.blockPosition();
               double centreLon = generator.longitudeFromBlock(playerPos.getX());
               double centreLat = generator.latitudeFromBlock(playerPos.getZ());
               int cellLat = (int) Math.floor(centreLat / FOLLOW_CELL_DEGREES);
               int cellLon = (int) Math.floor(centreLon / FOLLOW_CELL_DEGREES);
               if (cellLat == lastCellLat && cellLon == lastCellLon) {
                  Thread.sleep(FOLLOW_POLL_MILLIS);
                  continue;
               }
               lastCellLat = cellLat;
               lastCellLon = cellLon;

               double lat1 = cellLat * FOLLOW_CELL_DEGREES;
               double lon1 = cellLon * FOLLOW_CELL_DEGREES;
               double lat2 = lat1 + FOLLOW_CELL_DEGREES;
               double lon2 = lon1 + FOLLOW_CELL_DEGREES;
               LOGGER.info("[TellusCN] follow prefetch: player at {:.4f}, {:.4f} -> prefetching {:.1f}x{:.1f} degrees, layers {}",
                  centreLat, centreLon, FOLLOW_CELL_DEGREES, FOLLOW_CELL_DEGREES,
                  PreloadLayers.format(followLayers()));
               try {
                  preloadArea(lat1, lon1, lat2, lon2, followLayers());
               } catch (Throwable failure) {
                  LOGGER.warn("[TellusCN] follow prefetch failed (skipped, harmless): {}", failure.toString());
               }
               Thread.sleep(FOLLOW_POLL_MILLIS);
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (Throwable t) {
            LOGGER.warn("[TellusCN] follow prefetch thread errored (harmless): {}", t.toString());
         }
      }, "tellus-follow-prefetch");
      thread.setDaemon(true);
      thread.setPriority(Thread.MIN_PRIORITY);
      followPrefetchThread = thread;
      thread.start();
      // This line is deliberate: in the previous version the feature never shipped and the log had not one single line,
      // because nothing stated whether "the start condition is not met" or "it started but there is no player"; now it is logged even when the condition is not met.
      LOGGER.info("[TellusCN] follow prefetch started: one cell every {:.1f} degrees, downloaded only after leaving a cell, never re-downloading the same cell (polls every {}s while no player is online)",
         FOLLOW_CELL_DEGREES, FOLLOW_IDLE_SLEEP_MILLIS / 1000L);
   }

   /** The follow-prefetch thread: stored apart from {@link #autoPrefetchThread}, the two must not evict each other. */
   private static volatile Thread followPrefetchThread;

   /** Side length of a follow cell (degrees). 1 degree is about 111 km, i.e. "warm up underfoot and the nearby view". */
   private static final double FOLLOW_CELL_DEGREES = 1.0;

   /** Poll interval while there is no player. */
   private static final long FOLLOW_IDLE_SLEEP_MILLIS = 5_000L;

   /** Poll interval while there is a player. */
   private static final long FOLLOW_POLL_MILLIS = 2_000L;

   /**
    * Layers for the follow prefetch. The defaults match the manual preload and deliberately **do not** pull
    * {@code all}: {@code all} is for "box-selecting a whole area"; following cell by cell would download every layer in every cell.
    */
   private static Set<String> followLayers() {
      String spec = System.getProperty("tellus.preload.followLayers", "").trim();
      return spec.isEmpty() ? PreloadLayers.defaults() : PreloadLayers.parse(spec);
   }

   /** The last automatic preload thread (used to avoid a duplicate start when the world is reopened) . */
   private static volatile Thread autoPrefetchThread;

   /** Preload: dispatch by layer name to the two data sources(tellus / arnis), each keeping its own ledger.  */
   private static String preloadArea(double lat1, double lon1, double lat2, double lon2, Set<String> layers) {
      Set<String> arnisLayers = new LinkedHashSet<>();
      Set<String> tellusLayers = new LinkedHashSet<>();
      for (String layer : layers) {
         if (ArnisPreloader.isArnisLayer(layer)) {
            arnisLayers.add(layer);
         } else {
            tellusLayers.add(layer);
         }
      }
      StringBuilder message = new StringBuilder();
      if (!tellusLayers.isEmpty()) {
         message.append("tellus 数据源 -> ").append(TellusAreaPreloader.preload(
               lat1, lon1, lat2, lon2, tellusLayers, null).describe());
      }
      if (!arnisLayers.isEmpty()) {
         if (message.length() > 0) {
            message.append("; ");
         }
         message.append("arnis 数据源 -> ").append(
               ArnisPreloader.preload(lat1, lon1, lat2, lon2, arnisLayers).describe());
      }
      if (message.length() == 0) {
         message.append("没有可下载的图层");
      }
      return message.toString();
   }

   /**
    * Runs {@link #preloadArea} on a **background thread** , then replies back on the server thread .
    *
    * <p>Why it has to exist: downloading needs the network , while command execution and {@code ServerPlayNetworking}'s
    * {@code server.execute(...)} all run on the **server main thread** . Calling {@code preloadArea} directly
    * stalls the whole server for tens of minutes to tens of minutes (a wide box selection always
    * triggers it) —— the symptom is "server hang / A single server tick took ... seconds / the daemon thread times
    * out and crashes the server" . The automatic preload thread further down in this file is separate for the same hard rule .
    *
    * <p>Replies always go through {@code server.execute} : {@code sendSuccess} / {@code sendSystemMessage}
    * are not safe to send packets from another thread .
    */
   private static void startBackgroundPreload(
      MinecraftServer server, java.util.function.Consumer<String> reply,
      double lat1, double lon1, double lat2, double lon2, Set<String> layers
   ) {
      try {
         backgroundExecutor().execute(() -> {
            String summary;
            try {
               summary = preloadArea(lat1, lon1, lat2, lon2, layers);
            } catch (Throwable t) {
               LOGGER.warn("[TellusCN] preload failed (harmless): {}", t.toString());
               summary = "预加载失败: " + t;
            }
            final String result = summary;
            server.execute(() -> reply.accept(result));
         });
      } catch (java.util.concurrent.RejectedExecutionException rejected) {
         LOGGER.warn("[TellusCN] preload submit failed (the server is shutting down): {}", rejected.toString());
      }
   }

   /**
    * Deletes the cache on a **background thread** , then replies back on the server thread .
    *
    * <p>Same reasoning as {@link #startBackgroundPreload} , except this one does file IO :
    * {@code clearSource} recursively deletes the entire cache tree, and {@code deleteRegion} additionally has to walk
    * the whole arnis cache directory for every hit —— on the main thread that is a freeze measured in seconds (the watchdog kills the server outright).
    *
    * <p>{@code job} only touches files and {@link TellusPreloadRegistry} (CopyOnWriteArrayList, thread-safe) ,
    * never any world/chunk state , so moving it off the main thread is safe ; messages always go back to the main thread .
    */
   private static void startBackgroundCleanup(
      MinecraftServer server, java.util.function.Supplier<String> job, java.util.function.Consumer<String> reply
   ) {
      try {
         backgroundExecutor().execute(() -> {
            String message;
            try {
               message = job.get();
            } catch (Throwable t) {
               LOGGER.warn("[TellusCN] failed to clean the preload cache (harmless): {}", t.toString());
               message = "清理失败: " + t;
            }
            final String result = message;
            server.execute(() -> reply.accept(result));
         });
      } catch (java.util.concurrent.RejectedExecutionException rejected) {
         LOGGER.warn("[TellusCN] cleanup submit failed (the server is shutting down): {}", rejected.toString());
      }
   }

   /**
    * The **single-threaded** pool used for the heavy background work (preload / cleanup share it).
    *
    * <p>Why a pool and not "a new Thread per command" :
    * <ul>
    *   <li>{@code TellusPreloadRequestPayload} can be resent repeatedly by the client , and one new thread per send
    *       = an unbounded thread count (every thread is downloading several areas) , which is a client-amplifiable resource exhaustion ;</li>
    *   <li>The single thread also guarantees that {@link TellusPreloadRegistry#toJson} landing in
    *       {@code TellusPreloadRegistry.save()} never has two concurrent writers **inside this file**
    *       (save itself takes no lock, see TellusPreloadRegistry);</li>
    *   <li>Queued requests wait for the previous one to finish —— for a destructive operation such as "delete cache" that is safer .</li>
    * </ul>
    *
    * <p>At shutdown {@code SERVER_STOPPING} calls {@code shutdownNow()} and nulls the field ,
    * so it is rebuilt automatically when a single-player world is reopened .
    */
   private static volatile java.util.concurrent.ExecutorService backgroundExecutor;

   private static java.util.concurrent.ExecutorService backgroundExecutor() {
      java.util.concurrent.ExecutorService executor = backgroundExecutor;
      if (executor == null || executor.isShutdown()) {
         synchronized (Tellus.class) {
            executor = backgroundExecutor;
            if (executor == null || executor.isShutdown()) {
               executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                  Thread thread = new Thread(task, "tellus-background");
                  thread.setDaemon(true);
                  thread.setPriority(Thread.MIN_PRIORITY);
                  return thread;
               });
               backgroundExecutor = executor;
            }
         }
      }
      return executor;
   }

   private static void shutdownBackgroundExecutor() {
      java.util.concurrent.ExecutorService executor = backgroundExecutor;
      if (executor != null) {
         executor.shutdownNow();
         backgroundExecutor = null;
      }
   }

   /** {@code /tellus storage}: cache footprint of the two data sources + the ledger records.  */
   private static int runMaterialSelfCheck(CommandSourceStack source) {
      java.util.List<String> failures =
         com.yucareux.tellus.worldgen.building.TellusBuildingMaterialSelfCheck.verifyAll(2000);
      if (failures.isEmpty()) {
         source.sendSuccess(() -> Component.literal(
               "Tellus material self-check passed: "
                  + com.yucareux.tellus.worldgen.building.TellusBuildingMaterialLibrary.describe()
            ), false);
      } else {
         source.sendFailure(Component.literal("Tellus material self-check FAILED (" + failures.size() + "):"));
         for (String failure : failures) {
            source.sendFailure(Component.literal("  " + failure));
         }
      }
      source.sendSuccess(() -> Component.literal("  "
         + com.yucareux.tellus.world.data.elevation.TellusElevationSource.elevationRepairSummary()), false);
      source.sendSuccess(() -> Component.literal("  " + EarthChunkGenerator.describeShellRepair()), false);
      source.sendSuccess(() -> Component.literal(
         "  native kernel: " + (com.yucareux.tellus.worldgen.TellusNative.isAvailable() ? "loaded" : "absent, using the Java path")), false);
      return 1;
   }

   private static int showStorage(CommandSourceStack source) {
      source.sendSuccess(() -> Component.literal("Tellus 预加载缓存: " + PreloadStorage.describe()), false);
      int index = 1;
      for (TellusPreloadRegistry.Region region : TellusPreloadRegistry.regions()) {
         final int shown = index;
         source.sendSuccess(() -> Component.literal("  #" + shown + " " + region.describe(shown)), false);
         index++;
      }
      source.sendSuccess(() -> Component.literal(
            "  /tellus storage delete <index> 删除一个区域；/tellus storage clear <tellus|arnis> 清空该数据源"), false);
      return 1;
   }

   /** Empties the cache directory and the ledger of one data source.  */
   private static int clearStorage(CommandSourceStack source, String rawSource) {
      String sourceId = rawSource == null ? "" : rawSource.trim().toLowerCase(java.util.Locale.ROOT);
      if (!TellusPreloadRegistry.SOURCE_TELLUS.equals(sourceId)
            && !TellusPreloadRegistry.SOURCE_ARNIS.equals(sourceId)) {
         source.sendFailure(Component.literal("Tellus: 数据源必须是 'tellus' 或 'arnis'。"));
         return 0;
      }
      MinecraftServer server = source.getServer();
      if (server == null) {
         source.sendFailure(Component.literal("Tellus: 服务器还没准备好。"));
         return 0;
      }
      //  The recursive delete of the whole cache tree goes to the background thread : on the main thread it is a freeze of several seconds
      startBackgroundCleanup(server,
         () -> "Tellus: 已清空 " + sourceId + " 数据源缓存，删除 "
            + PreloadStorage.clearSource(sourceId) + " 个文件",
         message -> {
            source.sendSuccess(() -> Component.literal(message), true);
            pushStateTo(source);
         });
      return 1;
   }

   /** Deletes one downloaded area by its ledger index.  */
   private static int deleteStorageRegion(CommandSourceStack source, int index) {
      java.util.List<TellusPreloadRegistry.Region> regions = TellusPreloadRegistry.regions();
      if (index < 1 || index > regions.size()) {
         source.sendFailure(Component.literal("Tellus: 序号超出范围（当前共 " + regions.size() + " 个区域）。"));
         return 0;
      }
      TellusPreloadRegistry.Region region = regions.get(index - 1);
      MinecraftServer server = source.getServer();
      if (server == null) {
         source.sendFailure(Component.literal("Tellus: 服务器还没准备好。"));
         return 0;
      }
      //  The arnis source has to walk the whole cache directory to locate the tiles , so that moves to the background thread too
      startBackgroundCleanup(server,
         () -> String.format(java.util.Locale.ROOT,
            "Tellus: 已删除区域 #%d（%s），清理 %d 个缓存文件",
            index, region.effectiveSource(), Math.max(0, PreloadStorage.deleteRegion(region))),
         message -> {
            source.sendSuccess(() -> Component.literal(message), true);
            pushStateTo(source);
         });
      return 1;
   }

   private static int openManageHint(CommandSourceStack source) {
      source.sendSuccess(() -> Component.literal(
            "Tellus: 设置界面在 Mod Menu -> TellusCN -> Config（数据源 / 天体 / 地图主题 / 缓存占用 / 框选删除）"), false);
      return 1;
   }

   /** Box selection delete on the map: drops every ledger record intersecting that rectangle(cache included).  */
   private static void handlePreloadDelete(TellusPreloadDeletePayload payload, ServerPlayNetworking.Context context) {
      ServerPlayer player = context.player();
      MinecraftServer server = player.level().getServer();
      if (server == null) {
         return;
      }
      server.execute(() -> {
         if (!player.createCommandSourceStack().hasPermission(2)) {
            player.sendSystemMessage(Component.literal("Tellus: 删除已下载区域需要管理员权限。"));
            return;
         }
         String sourceFilter = payload.source() == null ? "" : payload.source().trim().toLowerCase(java.util.Locale.ROOT);

         // Degenerate rectangle = "wipe every cache and ledger entry of that source"(Mod Menu's two clear buttons take this path)
         if (payload.lat2() <= payload.lat1() || payload.lon2() <= payload.lon1()) {
            if (sourceFilter.isEmpty()) {
               player.sendSystemMessage(Component.literal("Tellus: 清空缓存需要指定数据源（tellus / arnis）。"));
               return;
            }
            // Validation is mandatory : PreloadStorage.clearSource treats **any non-arnis string** as tellus and deletes it ,
            // one missing letter from the client wipes the whole tellus cache (same validation as clearStorage)
            if (!TellusPreloadRegistry.SOURCE_TELLUS.equals(sourceFilter)
                  && !TellusPreloadRegistry.SOURCE_ARNIS.equals(sourceFilter)) {
               player.sendSystemMessage(Component.literal("Tellus: 数据源必须是 'tellus' 或 'arnis'。"));
               return;
            }
            startBackgroundCleanup(server,
               () -> "Tellus: 已清空 " + sourceFilter + " 数据源缓存，删除 "
                  + PreloadStorage.clearSource(sourceFilter) + " 个文件",
               message -> pushDeleteResult(player, message));
            return;
         }

         double minLat = Math.min(payload.lat1(), payload.lat2());
         double maxLat = Math.max(payload.lat1(), payload.lat2());
         double minLon = Math.min(payload.lon1(), payload.lon2());
         double maxLon = Math.max(payload.lon1(), payload.lon2());
         //  Deleting the cache is pure file IO : clearSource recursively deletes the whole tree, deleteRegion additionally has to walk the
         //  whole arnis cache directory for **every** hit —— on the main thread that is a freeze of several seconds, so hand it to the background thread
         startBackgroundCleanup(server, () -> {
            int removed = 0;
            int files = 0;
            // regions() already hands back the immutable List.copyOf snapshot(the loop mutates REGIONS itself),
            // wrapping it in another new ArrayList only copies the whole ledger again, pure waste
            for (TellusPreloadRegistry.Region region : TellusPreloadRegistry.regions()) {
               if (!sourceFilter.isEmpty() && !sourceFilter.equals(region.effectiveSource())) {
                  continue;
               }
               boolean overlaps = region.maxLat() >= minLat && region.minLat() <= maxLat
                     && region.maxLon() >= minLon && region.minLon() <= maxLon;
               if (overlaps) {
                  int deleted = PreloadStorage.deleteRegion(region);
                  files += Math.max(0, deleted);
                  removed++;
               }
            }
            return "Tellus: 框选删除完成，移除 " + removed + " 条区域记录，清理 " + files + " 个缓存文件";
         }, message -> pushDeleteResult(player, message));
      });
   }

   /** Reply after the cache delete: send the message on the main thread + push the latest ledger back . */
   private static void pushDeleteResult(ServerPlayer player, String message) {
      player.sendSystemMessage(Component.literal(message));
      ServerPlayNetworking.send(player, new TellusPreloadStatePayload(TellusPreloadRegistry.toJson()));
   }

   /** Push the latest ledger to the player who ran the command(skipped when the console ran it).  */
   private static void pushStateTo(CommandSourceStack source) {
      if (source.getEntity() instanceof ServerPlayer player) {
         ServerPlayNetworking.send(player, new TellusPreloadStatePayload(TellusPreloadRegistry.toJson()));
      }
   }

   /** Data source completions.  */
   private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> PRELOAD_SOURCES =
         (context, builder) -> {
            builder.suggest("tellus");
            builder.suggest("arnis");
            return builder.buildFuture();
         };

   private static int showTellusWeather(CommandSourceStack source) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.literal("Tellus: /tellus weather 只能由玩家使用。"));
         return 0;
      } else {
         ServerLevel level = player.level();
         if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
            source.sendFailure(Component.literal("Tellus: /tellus weather 仅在 Tellus 世界中可用。"));
            return 0;
         } else {
            BlockPos pos = player.blockPosition();
            double latitude = clampLatitude(earthGenerator.latitudeFromBlock(pos.getZ()));
            double longitude = clampLongitude(earthGenerator.longitudeFromBlock(pos.getX()));
            EarthGeneratorSettings settings = earthGenerator.settings();
            boolean realtimeTime = REALTIME_MANAGER.isRealtimeTimeEnabled(settings);
            boolean realtimeWeather = REALTIME_MANAGER.isRealtimeWeatherEnabled(settings);
            boolean realtimeWeatherActive = realtimeWeather && TellusRealtimeState.isWeatherEnabled();
            TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.precipitationMode();
            Tellus.WeatherDisplay weather = realtimeWeatherActive ? weatherFromRealtime(mode) : weatherFromVanilla(level, pos);
            source.sendSuccess(() -> Component.literal("Tellus 天气").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false);
            String locationText = Objects.requireNonNull(String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude), "locationText");
            source.sendSuccess(
               () -> Component.literal("位置: ").withStyle(ChatFormatting.GRAY).append(Component.literal(locationText).withStyle(ChatFormatting.AQUA)),
               false
            );
            String gameTime = Objects.requireNonNull(formatGameTime(level), "gameTime");
            source.sendSuccess(
               () -> Component.literal("游戏时间: ").withStyle(ChatFormatting.GRAY).append(Component.literal(gameTime).withStyle(ChatFormatting.YELLOW)),
               false
            );
            TellusRealtimeManager.WeatherSnapshot snapshot = REALTIME_MANAGER.lastWeatherSnapshot();
            ZoneId zone = null;
            boolean approximateTime = true;
            if (snapshot != null) {
               zone = resolveZoneId(snapshot.timeZoneId());
               approximateTime = zone == null;
            }

            if (zone == null && realtimeTime && REALTIME_MANAGER.hasTimeOffset()) {
               ZoneId managerZone = REALTIME_MANAGER.currentTimeZone();
               if (managerZone != null) {
                  zone = managerZone;
                  approximateTime = false;
               }
            }

            if (zone == null) {
               int offsetSeconds = approximateUtcOffsetSeconds(longitude);
               zone = ZoneOffset.ofTotalSeconds(offsetSeconds);
               approximateTime = true;
            }

            Instant now = REALTIME_MANAGER.currentInstant();
            int offsetSeconds = zone.getRules().getOffset(now).getTotalSeconds();
            String timeLabel = formatLocalTime(now, zone);
            String utcOffsetLabel = formatUtcOffset(offsetSeconds);
            MutableComponent timeLine = Component.literal("真实时间: ")
               .withStyle(ChatFormatting.GRAY)
               .append(Component.literal(timeLabel + " " + utcOffsetLabel).withStyle(ChatFormatting.YELLOW));
            if (approximateTime) {
               timeLine.append(Component.literal("（约）").withStyle(ChatFormatting.DARK_GRAY));
            }

            source.sendSuccess(() -> timeLine, false);
            MutableComponent tempLine = Component.literal("温度: ").withStyle(ChatFormatting.GRAY);
            if (snapshot != null) {
               String tempLabel = Objects.requireNonNull(String.format(Locale.ROOT, "%.1f °C", snapshot.temperatureC()), "tempLabel");
               tempLine.append(Component.literal(tempLabel).withStyle(ChatFormatting.YELLOW));
            } else {
               tempLine.append(Component.literal("无数据").withStyle(ChatFormatting.DARK_GRAY));
            }

            source.sendSuccess(() -> tempLine, false);
            String weatherLabel = Objects.requireNonNull(weather.label(), "weatherLabel");
            ChatFormatting weatherColor = Objects.requireNonNull(weather.color(), "weatherColor");
            MutableComponent weatherLine = Component.literal("天气: ")
               .withStyle(ChatFormatting.GRAY)
               .append(Component.literal(weatherLabel).withStyle(weatherColor));
            if (!realtimeWeather) {
               weatherLine.append(Component.literal("（原版）").withStyle(ChatFormatting.DARK_GRAY));
            } else if (!realtimeWeatherActive) {
               weatherLine.append(Component.literal("（实时数据待获取）").withStyle(ChatFormatting.DARK_GRAY));
            }

            source.sendSuccess(() -> weatherLine, false);
            return 1;
         }
      }
   }

   private static int setRealtimeTimeOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config weather 仅在 Tellus 世界中可用。"));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeTimeOverride(enabled);
         source.sendSuccess(() -> Component.literal("Tellus: 真实时间已设为 " + enabled + "。"), false);
         return 1;
      }
   }

   private static int setRealtimeWeatherOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config weather 仅在 Tellus 世界中可用。"));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeWeatherOverride(enabled);
         source.sendSuccess(() -> Component.literal("Tellus: 实时天气已设为 " + enabled + "。"), false);
         return 1;
      }
   }

   private static int setVoxyPregenEnabledOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy 仅在 Tellus 世界中可用。"));
         return 0;
      } else {
         voxyPregen().setEnabledOverride(enabled);
         source.sendSuccess(() -> Component.literal("Tellus: Voxy 预生成的启用覆盖值已设为 " + enabled + "。"), false);
         return 1;
      }
   }

   private static int setVoxyPregenMaxRadiusOverride(CommandSourceStack source, int chunks) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy 仅在 Tellus 世界中可用。"));
         return 0;
      } else {
         voxyPregen().setMaxRadiusOverride(chunks);
         source.sendSuccess(() -> Component.literal("Tellus: Voxy 预生成最大半径覆盖值已设为 " + chunks + " 区块。"), false);
         return 1;
      }
   }

   private static int setVoxyPregenChunksPerTickOverride(CommandSourceStack source, int chunksPerTick) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy 仅在 Tellus 世界中可用。"));
         return 0;
      } else {
         voxyPregen().setChunksPerTickOverride(chunksPerTick);
         source.sendSuccess(() -> Component.literal("Tellus: Voxy 预生成每 tick 配额覆盖值已设为 " + chunksPerTick + " 区块/tick。"), false);
         return 1;
      }
   }

   private static int resetVoxyPregenOverrides(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy 仅在 Tellus 世界中可用。"));
         return 0;
      } else {
         voxyPregen().clearOverrides();
         source.sendSuccess(() -> Component.literal("Tellus: Voxy 预生成覆盖值已重置为世界设置。"), false);
         return 1;
      }
   }

   private static int showVoxyPregenStatus(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy 仅在 Tellus 世界中可用。"));
         return 0;
      } else {
         EarthGeneratorSettings settings = earthGenerator.settings();
         boolean enabled = voxyPregen().effectiveEnabled(settings);
         int maxRadius = voxyPregen().effectiveMaxRadius(settings);
         int chunksPerTick = voxyPregen().effectiveChunksPerTick(settings);
         source.sendSuccess(() -> Component.literal("Tellus Voxy 预生成").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false);
         source.sendSuccess(
            () -> Component.literal(
                  "启用: " + enabled + "（世界设置=" + settings.voxyChunkPregenEnabled() + "，覆盖值=" + voxyPregen().enabledOverride() + "）"
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.literal(
                  "最大半径: "
                     + maxRadius
                     + " 区块（世界设置="
                     + settings.voxyChunkPregenMaxRadius()
                     + "，覆盖值="
                     + voxyPregen().maxRadiusOverride()
                     + "）"
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.literal(
                  "每 tick 配额: "
                     + chunksPerTick
                     + " 区块/tick（世界设置="
                     + settings.voxyChunkPregenChunksPerTick()
                     + "，覆盖值="
                     + voxyPregen().chunksPerTickOverride()
                     + "）"
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.literal(
                  "Voxy 半径: 配置值="
                     + voxyPregen().lastConfiguredVoxyRadiusChunks()
                     + " 区块，生效值="
                     + voxyPregen().lastEffectiveRadiusChunks()
                     + " 区块"
               )
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         source.sendSuccess(
            () -> Component.literal("队列: 已排队=" + voxyPregen().queuedChunkCount() + "，处理中=" + voxyPregen().inFlightChunkCount())
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         return 1;
      }
   }

   private static Tellus.WeatherDisplay weatherFromRealtime(TellusRealtimeState.PrecipitationMode mode) {
      return switch (mode) {
         case THUNDER -> new Tellus.WeatherDisplay("雷暴", ChatFormatting.DARK_PURPLE);
         case SNOW -> new Tellus.WeatherDisplay("降雪", ChatFormatting.AQUA);
         case RAIN -> new Tellus.WeatherDisplay("降雨", ChatFormatting.BLUE);
         case CLEAR -> new Tellus.WeatherDisplay("晴朗", ChatFormatting.GREEN);
      };
   }

   private static Tellus.WeatherDisplay weatherFromVanilla(ServerLevel level,  BlockPos pos) {
      if (level.isThundering()) {
         return new Tellus.WeatherDisplay("雷暴", ChatFormatting.DARK_PURPLE);
      } else if (level.isRainingAt(pos)) {
         Biome biome = (Biome)level.getBiome(pos).value();
         boolean snow = biome.getPrecipitationAt(pos, level.getSeaLevel()) == Precipitation.SNOW;
         return new Tellus.WeatherDisplay(snow ? "降雪" : "降雨", snow ? ChatFormatting.AQUA : ChatFormatting.BLUE);
      } else {
         return new Tellus.WeatherDisplay("晴朗", ChatFormatting.GREEN);
      }
   }

   private static ZoneId resolveZoneId(String zoneId) {
      if (zoneId != null && !zoneId.isBlank()) {
         try {
            return ZoneId.of(zoneId);
         } catch (Exception var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static int approximateUtcOffsetSeconds(double longitude) {
      double hours = longitude / 15.0;
      return (int)Math.round(hours * 3600.0);
   }

   private static String formatLocalTime(Instant instant, ZoneId zone) {
      int daySeconds = instant.atZone(zone).toLocalTime().toSecondOfDay();
      int hour = daySeconds / 3600;
      int minute = daySeconds % 3600 / 60;
      return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
   }

   private static long resolveDayTime(ServerLevel level) {
      // 1.21.9 has no WorldClock: the day/night time is simply ServerLevel's dayTime(0..23999).
      return Math.floorMod(level.getDayTime(), 24000L);
   }

   private static String formatGameTime(ServerLevel level) {
      long timeOfDay = resolveDayTime(level);
      int totalMinutes = (int)Math.floor(timeOfDay * 60.0 / 1000.0);
      int hour = (totalMinutes / 60 + 6) % 24;
      int minute = totalMinutes % 60;
      return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
   }

   private static String formatUtcOffset(int offsetSeconds) {
      int totalMinutes = offsetSeconds / 60;
      int hours = totalMinutes / 60;
      int minutes = Math.abs(totalMinutes % 60);
      // A negative half-hour zone (-00:30 and the like) has 0 hours , and %+03d eats the sign into "+" -> put it back separately
      String sign = (offsetSeconds < 0 && hours == 0) ? "-" : "";
      return String.format(Locale.ROOT, "UTC%s%+03d:%02d", sign, hours, minutes);
   }

   private static void handleGeoTeleport(GeoTpTeleportPayload payload, Context context) {
      if (Double.isFinite(payload.latitude()) && Double.isFinite(payload.longitude())) {
         ServerPlayer player = context.player();
         MinecraftServer server = player.level().getServer();
         if (server == null) {
            return;
         }

         server.execute(() -> {
            if (!player.createCommandSourceStack().hasPermission(2)) {
               player.sendSystemMessage(Component.literal("Tellus: 你没有使用 GeoTP 的权限。"));
               return;
            }

            ServerLevel level = player.level();
            if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
               double latitude = clampLatitude(payload.latitude());
               double longitude = clampLongitude(payload.longitude());
               BlockPos target = earthGenerator.getSurfacePosition(level, latitude, longitude);
               // 2026-10-05 fix: this used target.getY(), i.e. the surface block itself, while
               // /tellus tp (teleportToCoordinates) uses target.getY() + 1. getSurfacePosition
               // returns the topmost solid block, so teleporting to exactly that Y drops the player
               // INSIDE the terrain. Same landing rule as the command path, plus the same floor.
               double landingY = Math.max(level.getMinY() + 2, target.getY() + 1.0);
               player.teleportTo(target.getX() + 0.5, landingY, target.getZ() + 0.5);
            } else {
               player.sendSystemMessage(Component.literal("Tellus: GeoTP 仅在 Tellus 世界中可用。"));
            }
         });
      }
   }

   private static EarthChunkGenerator resolveEarthGenerator(CommandSourceStack source) {
      MinecraftServer server = source.getServer();
      if (server == null) {
         return null;
      }
      ServerLevel level = server.getLevel(Level.OVERWORLD);
      if (level == null) {
         return null;
      } else {
         return level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator ? earthGenerator : null;
      }
   }

   private static double clampLatitude(double latitude) {
      return Mth.clamp(latitude, -85.05112878, 85.05112878);
   }

   private static double clampLongitude(double longitude) {
      return Mth.clamp(longitude, -180.0, 180.0);
   }

   /**
    * Override Voxy pre-loading with system properties .
    *
    * <p>Why it is needed: all three onoff values live in the **world settings** ({@code voxy_chunk_pregen_enabled} etc. ),
    * and old worlds that already exist have {@code false} written there  ->  changing the default **does not affect them** .
    * This is a runtime override , it takes effect immediately in any world :
    * <ul>
    *   <li>{@code -Dtellus.voxy.pregen=true} -> force it on </li>
    *   <li>{@code -Dtellus.voxy.radius=256} -> override the pre-load radius(in chunks )</li>
    *   <li>{@code -Dtellus.voxy.chunksPerTick=32} -> override how many chunks are laid out per tick </li>
    * </ul>
    */
   /**
    * Self-check: whether Voxy's **cache** and its **mapping store** agree .
    *
    * <h2>Why this self-check is needed</h2>
    *
    * <p>Voxy keeps the "block state -> internal id" mapping in
    * {@code saves/<world>/voxy_gen_<dimension>.bin} , while the LOD data lives in
    * {@code saves/<world>/voxy/} . The two **must come as a pair** .
    *
    * <p>But that {@code .bin} is **only written on a clean exit** (`Mapper.close()`/`forceResaveStates()` )
    * —— so a **world that exited by crashing** is left in an inconsistent state:"there is a voxy/ cache, but the .bin is missing or nearly empty" .
    * Re-entering the game registers nothing in the mapper , while the cached data references
    * {@code Mapper.UNKNOWN_MAPPING}(= -1L )-> Voxy's
    * {@code getBlockStateOpacity(UNKNOWN_MAPPING)} looks up
    * {@code getBlockId(-1L) = 1048575}  in a dense table holding only a few dozen entries  ->
    * <b>{@code IndexOutOfBoundsException: Index (1048575) is greater than or equal to list size (...)}</b>
    * (confirmed by measurement : every world before that 11:17 session hit this )
    *
    * <p>So this checks once at server startup , and on a mismatch it **writes the fix straight into the log** ,
    * instead of making the player guess what that out-of-range number means .
    */
   /**
    * Self-check: **whether the chunk at the spawn point actually generated** .
    *
    * <h2>Why this check has to exist</h2>
    *
    * <p>Lesson measured the hard way(round 51 ): after turning `tellus.chunkgen.memoryOnlyTerrainCriticalPath`
    * on, the spawn point's DEM had not been downloaded yet  ->  the height came out as 0  ->  the chunk could not generate ,
    * while **vanilla only logs the one line `Loading 0 chunks for player spawn...`**  ——
    * that line is very easy to miss , and what the player sees is"a cube of air + a bedrock border + no collision" ,
    * with no way at all to guess that the terrain never generated .
    *
    * <p>So after server startup we proactively confirm it once : can the chunk holding the spawn point be**fetched immediately** .
    * If it cannot , it **reports it loudly** (with the coordinates and where to look) , instead of leaving the player staring into the void .
    *
    * <p>Note : this **does not** block or force-load any chunk  ——  it only calls `getChunkNow`(only queries the already loaded ones) ,
    * failing to get it only records one WARN , and changes no behaviour .
    */
   /**
    *  Automatically cross-checks the world top against the **highest elevation on Earth** (user request: "set the highest height automatically by the Earth's highest elevation" ).
    *
    * <p>The algorithm :
    * <pre>
    *   Everest 8848 m(the mod already has the constant EVEREST_ELEVATION_METERS )
     *   -> required Y = ceil(8848 × terrestrialHeightScale / worldScale) + heightOffset
    *   -> add another 25% of headroom , then align to 16  = the recommended top
    * </pre>
    *
    * <p>Then it is compared against the **current top** :
    * <ul>
    *   <li>the current top is high enough  -> log INFO stating the headroom </li>
    *   <li>the current top is **not high enough**  -> log WARN, and say **what to fill in** (into
    *       {@code config/telluscn-layout.properties} or as {@code -Dtellus.world.top=} )</li>
    *   <li>the current top is **far beyond what is needed** (e.g. 65535 while only 1 in ten thousand is usable)-> log WARN explaining"how many sections are being wasted for nothing"
    *       —— the section count directly determines performance </li>
    * </ul>
    */
   private static void reportAutoWorldTop(EarthChunkGenerator generator) {
      try {
         EarthGeneratorSettings settings = generator.settings();
         double scale = settings.worldScale();
         double heightScale = settings.terrestrialHeightScale();
         if (!(scale > 0.0) || !(heightScale > 0.0)) {
            return;
         }
         double everestY = 8848.0 * heightScale / scale + settings.heightOffset();
         int needed = (int) Math.ceil(everestY * 1.25);          // 25% headroom
         needed = needed + (16 - needed % 16) % 16;              // align to 16
         int current = com.yucareux.tellus.worldgen.TellusLayout.WORLD_TOP_Y;
         int neededSections = (needed - com.yucareux.tellus.worldgen.TellusLayout.WORLD_MIN_Y + 1) / 16;
         int currentSections = (current - com.yucareux.tellus.worldgen.TellusLayout.WORLD_MIN_Y + 1) / 16;
         if (current < needed) {
            LOGGER.warn("[TellusCN] the world top is not high enough: current={}, but Everest(8848 m, scale={} heightScale={})"
               + "needs {}; high mountains will be flattened. Please set world.top={} in config/telluscn-layout.properties"
               + "(or add -Dtellus.world.top={}), then create a new world",
               current, scale, heightScale, needed, needed, needed);
         } else if (currentSections > neededSections * 2) {
            LOGGER.warn("[TellusCN] the world top is far above what is needed: current={}({} sections), while Everest only needs {} "
               + "({} sections) -> the extra {} sections are pure waste of memory and iteration time."
               + "Setting world.top to {} saves them(the section count directly decides the per-chunk"
               + "array, fill, lighting, serialisation and packet costs); you need to create a new world",
               current, currentSections, needed, neededSections, currentSections - neededSections, needed);
         } else {
            LOGGER.info("[TellusCN] world top check passed: current={}({} sections), Everest needs {}({} sections) -> fits",
               current, currentSections, needed, neededSections);
         }
      } catch (Throwable t) {
         if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[TellusCN] world top check errored (harmless): {}", t.toString());
         }
      }
   }

   private static void checkSpawnChunkGenerated(
      net.minecraft.server.level.ServerLevel world, BlockPos spawn) {
      try {
         int chunkX = spawn.getX() >> 4;
         int chunkZ = spawn.getZ() >> 4;
         net.minecraft.world.level.chunk.LevelChunk chunk = world.getChunkSource()
            .getChunkNow(chunkX, chunkZ);
         if (chunk == null) {
            // Don't shout yet  ——  the spawn area is still being laid out ; give it about 60 seconds before reporting
            // (the counter goes up **only here** : tickSpawnChunkCheck used to bump it too , which meant two steps per tick ,
            //  so 60 seconds of patience were really only 30)
            if (spawnCheckTicks < SPAWN_CHECK_LIMIT_TICKS) {
               spawnCheckTicks++;
               return;
            }
            // Once it has reported, it is done : otherwise the next tick keeps asking about the same chunk, wasting one getChunkNow per tick
            spawnCheckWorld = null;
            spawnCheckPos = null;
            LOGGER.warn("[TellusCN] the spawn chunk never generated - still missing after 60 seconds. Spawn {} sits in chunk [{}, {}] "
               + "which cannot be fetched. If you see air blocks plus a bedrock border and no collision, this is the reason."
               + "Common causes: the terrain height was computed before the DEM tiles finished downloading"
               + "(the critical path was culled too early), or terrain generation was skipped. Please paste this log." ,
               spawn, chunkX, chunkZ);
            return;
         }
         spawnCheckWorld = null;   // success  ->  stop retrying
         spawnCheckPos = null;
         int surface = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
            spawn.getX(), spawn.getZ());
         LOGGER.info("[TellusCN] spawn check passed: chunk [{}, {}] generated, spawn {}, surface Y={}",
            chunkX, chunkZ, spawn, surface);
      } catch (Throwable t) {
         if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[TellusCN] spawn check errored (harmless): {}", t.toString());
         }
      }
   }

   /**
    * The **state** of the spawn chunk self-check (the "patient" version).
    *
    * <p>Why it changed: `SERVER_STARTED` fires while **the spawn area has not actually finished generating**  ——
    * vanilla lays it out slowly over the following ticks . The first version of the self-check asked "is the chunk there" here ,
    * so it **false-alarmed** a pile of "the spawn chunk never generated" reports (measured : in the log
    * `Loading 0 persistent chunks` had just gone by when it fired , while the player quit a minute later ).
    *
    * <p>Now it works like this: `SERVER_STARTED` only registers , afterwards it **retries every tick** ,
    * as soon as it gets it , it immediately logs the success INFO ; only **after about 60 seconds of still not getting it**  does it log WARN .
    * That way there are no false alarms , and real problems still get reported promptly .
    */
   private static volatile net.minecraft.server.level.ServerLevel spawnCheckWorld;
   private static volatile BlockPos spawnCheckPos;
   private static int spawnCheckTicks;
   /** How many ticks the spawn self-check waits at the most (1200 ≈ 60 seconds) . */
   private static final int SPAWN_CHECK_LIMIT_TICKS = 1200;

   private static void beginSpawnChunkCheck(net.minecraft.server.level.ServerLevel world, BlockPos spawn) {
      spawnCheckWorld = world;
      spawnCheckPos = spawn;
      spawnCheckTicks = 0;
      requestSpawnChunk(world, spawn);          // proactively pull the spawn chunk up (async, does not block the main thread )
      checkSpawnChunkGenerated(world, spawn);   // try once right away first (most of the time it is already there by then )
   }

   /**
    * Proactively requests generation of the chunk at the spawn point.
    *
    * <p><b>Why it is needed</b>: in {@code SERVER_STARTED} we change the spawn to a chosen position on Earth
    * (e.g. {@code (322548, 356, -108250)}), but vanilla's {@code Preparing spawn area}
    * had already finished before that —— it lays out the chunks around the **old spawn**.
    * whereas the self-check only calls {@code getChunkNow} (it only queries the already loaded ones) and never generates proactively,
    * so that chunk never shows up and after the full 60 seconds it must report "NEVER generated";
    * and the player's first join has to wait for it to be generated live (DEM download + terrain laid out), i.e. that stretch of
    * "ready but cannot get in" blank period.
     * <p><b>2026-10-06: only one step is left here now.</b>
     * <ol>
     *   <li>{@code getChunkFuture(FULL)} —— an async request; generation runs on a worker thread,
     *       and it does not block the main thread the way {@code ServerChunkCache#getChunk} does.</li>
     * </ol>
     *
     * <p><b>The original first step {@code setChunkForced} has been removed; it was itself the bug.</b> The previous version's comment
     * described this method as asynchronous, and the {@code getChunkFuture} line indeed is —— but
     * {@code ServerLevel#setChunkForced} loads the chunk **on the calling thread**, so calling it
     * from inside {@code SERVER_STARTED} means blocking the server main thread for the whole chunk generation.
     * This is measured, not inferred: in the 16:09:13 watchdog crash dump {@code "Server thread" TIMED_WAITING}
     * sits right inside {@code setChunkForced -> ServerChunkCache.getChunk}, and the next line is
     * {@code "A single server tick took 60.00 seconds (should be max 0.05)"}.
     * The only reason that FORCED ticket existed is "do not unload it right after generating", and a player standing
     * at the spawn keeps it in memory anyway; {@code getChunkFuture} has already handed the work to the worker pool.
    */
   private static void requestSpawnChunk(net.minecraft.server.level.ServerLevel world, BlockPos spawn) {
      try {
         int chunkX = spawn.getX() >> 4;
         int chunkZ = spawn.getZ() >> 4;
         world.getChunkSource()
            .getChunkFuture(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true)
            .whenComplete((result, error) -> {
               if (error != null) {
                  LOGGER.warn("[TellusCN] cannot request the spawn chunk [{}, {}]: {}",
                     chunkX, chunkZ, error.toString());
               }
            });
      } catch (Throwable t) {
         if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[TellusCN] spawn chunk request errored (harmless): {}", t.toString());
         }
      }
   }

   /** Retried every tick (at most about 60 seconds).  */
   private static void tickSpawnChunkCheck() {
      net.minecraft.server.level.ServerLevel world = spawnCheckWorld;
      BlockPos spawn = spawnCheckPos;
      if (world == null || spawn == null) {
         return;
      }
      if (spawnCheckTicks < SPAWN_CHECK_LIMIT_TICKS) {
         checkSpawnChunkGenerated(world, spawn);
      }
   }

   private static void checkVoxyCacheConsistency(net.minecraft.server.MinecraftServer server) {
      try {
         java.nio.file.Path root = server.getWorldPath(
            net.minecraft.world.level.storage.LevelResource.ROOT);
         java.nio.file.Path cache = root.resolve("voxy");
         if (!java.nio.file.Files.isDirectory(cache)) {
            return;   // no Voxy cache -> nothing to check
         }
         long binBytes = 0L;
         int binCount = 0;
         try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files::iterator) {
               String name = p.getFileName().toString();
               if (name.startsWith("voxy_gen_") && name.endsWith(".bin")) {
                  binCount++;
                  binBytes += java.nio.file.Files.size(p);
               }
            }
         }
         if (binCount > 0 && binBytes > 4096L) {
            LOGGER.info("[TellusCN] Voxy cache check: consistent (voxy/ and voxy_gen_*.bin exist as a pair)");
            return;
         }
         LOGGER.warn("[TellusCN] Voxy cache check: inconsistent - the voxy/ directory exists,"
            + "but the mapping store voxy_gen_*.bin has only {} files / {} bytes."
            + "This makes Voxy's import thread throw IndexOutOfBoundsException"
            + "(Index 1048575 ... - its own UNKNOWN_MAPPING sentinel value)."
            + "Cause: that .bin is only written on a clean exit; the previous crash left it missing."
            + "Fix: rename that world's voxy directory (e.g. to voxy.bak) and then enter the world again - "
            + "Voxy will rebuild a consistent cache and mapping (only the LOD display is rebuilt, the save is untouched).",
            binCount, binBytes);
      } catch (Throwable t) {
         if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[TellusCN] Voxy cache check errored (harmless): {}", t.toString());
         }
      }
   }

   private static void applyVoxyPregenProperties() {
      String pregen = System.getProperty("tellus.voxy.pregen");
      if (pregen != null) {
         boolean enabled = Boolean.parseBoolean(pregen.trim());
         voxyPregen().setEnabledOverride(enabled);
         LOGGER.info("[TellusCN] Voxy pre-generation forced to {} by a system property (-Dtellus.voxy.pregen)", enabled);
      }
      String radius = System.getProperty("tellus.voxy.radius");
      if (radius != null) {
         try {
            int value = Integer.parseInt(radius.trim());
            voxyPregen().setMaxRadiusOverride(value);
            LOGGER.info("[TellusCN] Voxy pre-generation radius overridden to {} chunks (-Dtellus.voxy.radius)", value);
         } catch (NumberFormatException e) {
            LOGGER.warn("[TellusCN] -Dtellus.voxy.radius is not an integer: {}", radius);
         }
      }
      String perTick = System.getProperty("tellus.voxy.chunksPerTick");
      if (perTick != null) {
         try {
            int value = Integer.parseInt(perTick.trim());
            voxyPregen().setChunksPerTickOverride(value);
            LOGGER.info("[TellusCN] Voxy chunks per tick overridden to {} (-Dtellus.voxy.chunksPerTick)", value);
         } catch (NumberFormatException e) {
            LOGGER.warn("[TellusCN] -Dtellus.voxy.chunksPerTick is not an integer: {}", perTick);
         }
      }
      LOGGER.info("[TellusCN] Voxy pre-generation overrides: enabled={} radius={} chunksPerTick={} (null = use the world setting)",
         voxyPregen().enabledOverride(), voxyPregen().maxRadiusOverride(),
         voxyPregen().chunksPerTickOverride());
   }

   /**
    * Self-check: whether the initial BitSet capacity of {@code CarvingMask} really is capped by
    * {@code CarvingMaskCapacityMixin} .
    *
    * <p>No "it should be working" assumption : build a 65600-high mask directly , then read its BitSet capacity by reflection .
    * Uncapped it is 16,793,600 bit ≈ 2 MB ; capped it is 65,536 bit = 8 KB .
    */
   private static void probeCarvingMaskCapacity() {
      try {
         net.minecraft.world.level.chunk.CarvingMask probe =
            new net.minecraft.world.level.chunk.CarvingMask(65600, -64);
         // Do not look it up by field name (in production the field name is intermediary's field_XXXXX ,
         // this self-check used to fail precisely because getDeclaredField("mask") threw NoSuchFieldException )
         // -> look for the BitSet field **by type** , which holds both in dev and in production
         java.util.BitSet bits = null;
         for (java.lang.reflect.Field f :
               net.minecraft.world.level.chunk.CarvingMask.class.getDeclaredFields()) {
            if (java.util.BitSet.class.isAssignableFrom(f.getType())) {
               f.setAccessible(true);
               bits = (java.util.BitSet) f.get(probe);
               break;
            }
         }
         if (bits == null) {
            LOGGER.warn("[TellusCN] CarvingMask check: no BitSet field found (harmless)");
            return;
         }
         int allotted = bits.size();
         // 2026-10-11: the threshold used to be a hard-coded 131072 (= 256 * 512). That number is stale:
         // MASK_SECTIONS is derived from the live layout, and with the 1:1 world (WORLD_MIN_Y = -11040) it
         // is 817 sections, so the real cap is 256 * 817 = 209152 bit. Because 209152 > 131072, this check
         // logged "NOT capped ... did not take effect" while the mixin was in fact working exactly as
         // designed -- the warning was false, and it sent this investigation down the wrong path once.
         // It now asks the mixin for the value instead of re-deriving (or worse, guessing) it, so the two
         // cannot drift apart when the layout changes.
         int expectedCap = 256 * com.yucareux.tellus.mixin.CarvingMaskCapacityMixin.expectedMaskSections();
         if (allotted <= expectedCap) {
            LOGGER.info("[TellusCN] CarvingMask initial capacity is capped: {} bit about {} KB, {}x below the uncapped 16,793,600 bit (about 2 MB)",
               allotted, allotted / 8 / 1024, 16_793_600 / Math.max(1, allotted));
         } else {
            LOGGER.warn("[TellusCN] CarvingMask initial capacity is NOT capped: {} bit about {} MB, expected at most {} bit - CarvingMaskCapacityMixin did not take effect",
               allotted, allotted / 8 / 1024 / 1024, expectedCap);
         }
      } catch (Throwable t) {
         LOGGER.warn("[TellusCN] CarvingMask capacity check failed (harmless): {}", t.toString());
      }
   }

   private static void logOverworldSettings(MinecraftServer server, Level world, ChunkGenerator generator) {
      DimensionType worldType = world.dimensionType();
      LOGGER.info("overworld dimension type: {}", describeDimensionType(worldType));
      LOGGER.info(
         "overworld generator: type={}, minY={}, height={}", new Object[]{generator.getClass().getSimpleName(), generator.getMinY(), generator.getGenDepth()}
      );
      Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
      Optional<Reference<LevelStem>> stemRef = stems.get(LevelStem.OVERWORLD);
      if (stemRef.isEmpty()) {
         LOGGER.warn("the registry has no level stem for the overworld");
      } else {
         LevelStem stem = (LevelStem)stemRef.get().value();
         DimensionType stemType = (DimensionType)stem.type().value();
         LOGGER.info("overworld level stem: dimensionType={}, generatorType={}", describeDimensionType(stemType), stem.generator().getClass().getSimpleName());
      }
   }

   /** The vanilla world border default value(≈30 million).  */
   private static final double VANILLA_BORDER_SIZE = 5.9999968E7;

   /**
    * Opens the Tellus world border up to the **maximum the coordinate format allows**.
    *
    * <p>It only acts while the border is still the **vanilla default** —— if the player set a border themselves it is not overwritten.
     * The upper limit is {@code EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE} (±33,554,431),
     * because {@code BlockPos} packs 26 bits each for X/Z, and anything beyond that overflows the coordinate.
    */
   /**
     * {@code /tellus region}: shows the **region** the player is currently in.
    *
     * <p>This is the first foundation stone of the long-term "break through the coordinate boundary" plan: split the Earth into Regions,
     * each Region is hosted in its own dimension, with only local coordinates inside (always within the safe range of ±33.55 million),
     * auto-teleporting when crossing regions. This way it is infinite on the outside, and coordinates are always valid on the inside,
     * and **does not touch the engine's coordinate format** (so it is compatible with all mods).
    */
   private static int describeRegion(CommandSourceStack source) {
      var pos = source.getPosition();
      int bx = (int) Math.floor(pos.x);
      int bz = (int) Math.floor(pos.z);
      source.sendSuccess(() -> Component.literal(TellusRegions.describe(bx, bz)), false);
      double scale = 30.0;
      ServerLevel sourceLevel = source.getLevel();
      if (sourceLevel != null && sourceLevel.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator) {
         scale = generator.settings().worldScale();
      }
      double latitude = clampLatitude(EarthProjection.blockZToLat(bz, scale));
      double longitude = clampLongitude(bx / EarthProjection.blocksPerDegree(scale));
      final double fScale = scale;
      source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
            "区域边长 %d 区块；局部坐标必须落在 +/-%d 之内。当前 纬度/经度 %.5f, %.5f（比例 %.3g 米/区块）",
            TellusRegions.REGION_SIZE, EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE,
            latitude, longitude, fScale)), false);
      return 1;
   }

   private static void widenWorldBorderIfUntouched(MinecraftServer server, ServerLevel level) {
      //  The target value is then clamped with vanilla's own upper limit via min: 2×33,554,431 = 67,108,862 > 59,999,968,
      //  and WorldBorder#setSize **neither clamps nor throws** (verified in 1.21.9 bytecode: the 59,999,968 check
      //  is only written in /worldborder set; we call setSize directly, so an out-of-range value would actually take effect).
      //  After taking the min, 59,999,968 is still > ±33,554,431 — the goal of "everything reachable within the coordinate format" is not compromised at all,
      //  it just stops feeding vanilla a value it would reject itself (the border wall is drawn by block coordinates; beyond it is out of bounds).
      double target = Math.min(2.0 * EarthGeneratorSettings.MAX_SAFE_BLOCK_COORDINATE, VANILLA_BORDER_SIZE);
      var border = level.getWorldBorder();
      double current = border.getSize();
      // **Only widen, never shrink** (same principle as the two codec upper-limit places):
      // Previously it only fired when "the border was exactly the vanilla default", so **worlds with a custom border would never be widened** —
      // once the player runs past 30 million they are permanently outside the border (red screen + damage); this was actually encountered in testing.
      if (current >= target - 1.0) {
         LOGGER.info("world border {} is already >= target {}, leaving unchanged", current, target);
         return;
      }
      border.setSize(target);
      LOGGER.info("world border widened from {} to {} (only widens, never shrinks; use /worldborder set {} to restore vanilla)",
            current, target, VANILLA_BORDER_SIZE);
   }

   private static String describeDimensionType(DimensionType type) {
      return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
   }

   private static void ensureDynamicDimensionPack(
      MinecraftServer server, Holder<DimensionType> dimensionTypeHolder, DimensionType currentDimensionType, EarthChunkGenerator earthGenerator
   ) {
      ResourceKey<DimensionType> dimensionKey = resolveTellusDimensionKey(dimensionTypeHolder).orElse(null);
      if (dimensionKey != null) {
         EarthGeneratorSettings settings = earthGenerator.settings();
         EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
         DimensionType updatedType = EarthGeneratorSettings.applyHeightLimits(currentDimensionType, limits);
         DynamicOps<JsonElement> jsonOps = Objects.requireNonNull(JsonOps.INSTANCE, "jsonOps");
         RegistryOps<JsonElement> registryOps = RegistryOps.create(jsonOps, server.registryAccess());
         JsonElement dimensionJson = (JsonElement)DimensionType.DIRECT_CODEC
            .encodeStart(registryOps, updatedType)
            .resultOrPartial(message -> LOGGER.error("failed to encode dynamic dimension type: {}", message))
            .orElse(null);
         if (dimensionJson != null) {
            // Self-explanatory height check: puts "the height the config wants" and "the world's current actual height" side by side.
            // Inconsistency is normal — the dimension height is fixed **when the world is loaded**, and we write the dynamic pack only after the server has started,
            // so the increased height only takes effect **on the next entry**. The conclusion is written directly into the log here,
            // so the user does not have to guess at a pile of numbers (-Dtellus.height.force=N can force it).
            EarthGeneratorSettings.HeightLimits wanted = EarthGeneratorSettings.resolveHeightLimits(settings);
            int actualMinY = currentDimensionType.minY();
            int actualHeight = currentDimensionType.height();
            if (actualMinY != wanted.minY() || actualHeight != wanted.height()) {
               LOGGER.warn(
                  "world height not yet in effect: current minY={}, height={} (top {}); config requires minY={}, height={} (top {}). "
                     + "Dimension height is read when the world is loaded, so it will take effect with height {} after re-entering the world.",
                  actualMinY, actualHeight, actualMinY + actualHeight - 1,
                  wanted.minY(), wanted.height(), wanted.minY() + wanted.height() - 1,
                  wanted.height());
            } else {
               LOGGER.info("world height in effect: minY={}, height={} (top {})", actualMinY, actualHeight,
                     actualMinY + actualHeight - 1);
            }

            Path packDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(DYNAMIC_DIMENSION_PACK_NAME);
            Path packMetaPath = packDir.resolve("pack.mcmeta");
            ResourceLocation dimensionId = dimensionKey.location();
            Path dimensionPath = packDir.resolve("data/" + dimensionId.getNamespace() + "/dimension_type/" + dimensionId.getPath() + ".json");

            try {
               Files.createDirectories(dimensionPath.getParent());
               writeJson(packMetaPath, createPackMeta());
               writeJson(dimensionPath, dimensionJson);
            } catch (IOException var16) {
               LOGGER.warn("failed to write dynamic dimension type datapack", var16);
               return;
            }

            enableDynamicPack(server.getWorldData());
         }
      }
   }

   private static Optional<ResourceKey<DimensionType>> resolveTellusDimensionKey(Holder<DimensionType> dimensionTypeHolder) {
      return dimensionTypeHolder.unwrapKey().filter(Tellus::isTellusDimensionKey);
   }

   private static boolean isTellusDimensionKey(ResourceKey<DimensionType> key) {
      return key.equals(DYNAMIC_DIMENSION_KEY) || key.equals(EARTH_DIMENSION_KEY);
   }

   /**
     * Generates the {@code pack.mcmeta} for the dynamic datapack.
    *
     * <p><b>1.21.9 pitfall</b>: the datapack format number is already 88, while
     * {@code PackFormat.lastPreMinorVersion(SERVER_DATA)} is only 81. The rule is:
     * <b>the old {@code pack_format} notation is only allowed up to 81; beyond that it must be written as {@code min_format}/{@code max_format}. </b>
     * Writing only {@code "pack_format": 88} is judged as "declaring a version newer than 81 but missing min_format / max_format",
     * the entire datapack is rejected -> the dimension_type in the registry cannot be read ->
     * <b>the world directly fails to load (Failed to load registries)</b>.
    *
     * <p>The notation was cross-checked against ForgeConfigAPIPort, also for 1.21.9 (it writes
    * {@code {"min_format":88,"max_format":88}}).
    */
   private static JsonObject createPackMeta() {
      JsonObject pack = new JsonObject();
      int packFormat = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
      pack.addProperty("min_format", packFormat);
      pack.addProperty("max_format", packFormat);
      pack.addProperty("description", "Tellus 动态维度设置");
      JsonObject root = new JsonObject();
      root.add("pack", pack);
      return root;
   }

   private static void writeJson(Path path, JsonElement payload) throws IOException {
      try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
         GSON.toJson(payload, writer);
      }
   }

   private static void enableDynamicPack(WorldData worldData) {
      WorldDataConfiguration configuration = worldData.getDataConfiguration();
      DataPackConfig dataPacks = configuration.dataPacks();
      List<String> enabled = new ArrayList<>(dataPacks.getEnabled());
      List<String> disabled = new ArrayList<>(dataPacks.getDisabled());
      if (!enabled.contains(DYNAMIC_DIMENSION_PACK_ID)) {
         enabled.add(DYNAMIC_DIMENSION_PACK_ID);
      }

      disabled.remove(DYNAMIC_DIMENSION_PACK_ID);
      if (!enabled.equals(dataPacks.getEnabled()) || !disabled.equals(dataPacks.getDisabled())) {
         WorldDataConfiguration updated = new WorldDataConfiguration(new DataPackConfig(enabled, disabled), configuration.enabledFeatures());
         worldData.setDataConfiguration(updated);
      }
   }

   private record WeatherDisplay(String label, ChatFormatting color) {
   }
}
