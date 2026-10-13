package com.yucareux.tellus.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.MapEncoder.Implementation;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.util.Mth;

public record EarthGeneratorSettings(
   double worldScale,
   double terrestrialHeightScale,
   double oceanicHeightScale,
   int heightOffset,
   int seaLevel,
   double spawnLatitude,
   double spawnLongitude,
   int minAltitude,
   int maxAltitude,
   int riverLakeShorelineBlend,
   int oceanShorelineBlend,
   boolean shorelineBlendCliffLimit,
   boolean caveGeneration,
   boolean oreDistribution,
   boolean lavaPools,
   boolean addStrongholds,
   boolean addVillages,
   boolean addMineshafts,
   boolean addOceanMonuments,
   boolean addWoodlandMansions,
   boolean addDesertTemples,
   boolean addJungleTemples,
   boolean addPillagerOutposts,
   boolean addRuinedPortals,
   boolean addShipwrecks,
   boolean addOceanRuins,
   boolean addBuriedTreasure,
   boolean addIgloos,
   boolean addWitchHuts,
   boolean addAncientCities,
   boolean addTrialChambers,
   boolean addTrailRuins,
   boolean deepDark,
   boolean geodes,
   boolean distantHorizonsWaterResolver,
   boolean distantHorizonsOsmFeatures,
   int distantHorizonsOsmRoadMaxDetail,
   int distantHorizonsOsmBuildingMaxDetail,
   boolean distantHorizonsOsmNonBlockingFetch,
   boolean realtimeTime,
   boolean realtimeWeather,
   boolean historicalSnow,
   boolean voxyChunkPregenEnabled,
   int voxyChunkPregenMaxRadius,
   int voxyChunkPregenChunksPerTick,
   EarthGeneratorSettings.DistantHorizonsRenderMode distantHorizonsRenderMode,
   EarthGeneratorSettings.DemSelection demSelection,
   boolean enableRoads,
   boolean enableBuildings,
   boolean enableWater
) {
   public static final double DEFAULT_SPAWN_LATITUDE = 27.9881;
   public static final double DEFAULT_SPAWN_LONGITUDE = 86.925;
   public static final int AUTO_ALTITUDE = Integer.MIN_VALUE;
   public static final int AUTO_SEA_LEVEL = -2147483647;
   /** Bottom Y of the world. Derived from the coordinate layout (vanilla = -2032; -64 by default in the repackaged layout). */
   public static final int MIN_WORLD_Y = TellusLayout.WORLD_MIN_Y;


   /**
    * Hard coordinate limit. {@code BlockPos.asLong} packs X and Z into **26 bits** each,
    * so |coordinate| must be <= 2^25 - 1 = 33,554,431 -- beyond that the block coordinates overflow and
    * terrain / collision / saving all break down (this is where the "distance phenomenon" comes from).
    *
    * <p>Working the world scale backwards: the Earth equator is 40,075,016 m and half an Earth has to fit into that range,
    * so <b>world_scale must be at least 0.6 m per block</b> (at 0.6 the half width is about 33.4 million, which just fits).
    * At 0.5 m per block the half width reaches 40 million, about 19% over the limit.
    */
   public static final int MAX_SAFE_BLOCK_COORDINATE = TellusLayout.MAX_BLOCK_COORDINATE;

   /** The minimum world scale (metres per block) needed to fit the whole Earth into the safe coordinate range. */
   public static final double MIN_SAFE_WORLD_SCALE = 40_075_016.0 / 2.0 / MAX_SAFE_BLOCK_COORDINATE;
   /** Top Y of the world. Derived from the coordinate layout (vanilla 2047; 65535 by default in the repackaged layout). */
   public static final int MAX_WORLD_Y = TellusLayout.WORLD_TOP_Y;
   /**
    * The maximum **total height** that is safe for the packing.
    *
    * <p>The vanilla constructor checks {@code min_y + height <= MAX_WORLD_Y + 1} (= 2048, coming from
    * {@code DimensionTypeMaxYMixin} redirecting MAX_Y), and the floor of {@code min_y} is the vanilla
    * {@link #MIN_WORLD_Y} (-2032), so the maximum height = 2048 - (-2032) = **4080**,
    * at which the top is exactly 2047 and Y does not overflow.
    *
    * <p><b>The pit that was already stepped in (do not repeat it)</b>: this used to be 4096, so {@code HeightLimits.maxRange()} gave
    * {@code minY + height = -2032 + 4096 = 2064 > 2048} and the vanilla constructor threw directly
    * {@code IllegalStateException: min_y + height cannot be higher than: 2048},
    * so the game crashed as soon as the "create world" screen was opened (because every fallback branch returns maxRange()).
    * <b>Changing one end always requires changing the other end</b>, and {@code applyHeightLimits()} now clamps as a fallback.
    */
   public static final int MAX_WORLD_HEIGHT = MAX_WORLD_Y + 1 - MIN_WORLD_Y;
   /** Upper bound of the dimension height (vanilla is 4064; {@code DimensionTypeHeightMixin} replaces it with this value). */
   public static final int MAX_DIMENSION_HEIGHT = MAX_WORLD_HEIGHT;
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("TellusCN");

   /** Whether this is currently a client environment. Using the full height is done client side only (see {@link #resolveHeightLimits}). */
   private static final boolean CLIENT_SIDE = net.fabricmc.loader.api.FabricLoader.getInstance()
      .getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
   private static final int ALTITUDE_TOLERANCE = 50;
   private static final int HEIGHT_ALIGNMENT = 16;

   /**
    * The skylight **computation range** in sections. Default 1024 sections = 16384 blocks.
    * Tunable with {@code -Dtellus.skylight.height=N} (512 -> 8192 blocks / 4100 -> full height).
    *
    * <p><b>Measured on 1.21.9: this has NO effect on memory or save size.</b> Decompiling the game jar
    * (plus a whole-jar constant-pool scan) shows {@code DimensionType.logicalHeight()} is read only by
    * {@code PortalForcer} and {@code TeleportRandomlyConsumeEffect}; {@code LevelHeightAccessor} has no
    * such method, so lighting / chunk packets / saves never consult it. Lowering this value saves
    * nothing -- see {@code SerializableChunkDataSparseMixin} and {@code skipOnSave} for what actually
    * drives chunk size.
    *
    * <p>History: the range used to be squeezed to 512 sections to work around the client-side 2 MB chunk
    * packet cap. {@code ChunkPacketCapMixin} now raises that cap to 32 MB.
    */
   private static final int LOGICAL_HEIGHT_CAP =
      Integer.getInteger("tellus.skylight.height", 1024);

   /**
    * Last-resort switch: {@code -Dtellus.skylight.off=true} turns skylight off entirely.
    *
    * <p>Squeezing {@code logical_height} above is the way to "keep the skylight look"; in case the client still complains
    * about the packet being too large (`Chunk Packet trying to allocate too much memory on read`),
    * this switch **will definitely** zero out the skylight data -> the packet drops to the minimum. Cost: no skylight (still visible through ambient_light)
    */
   private static final boolean SKYLIGHT_DISABLED = Boolean.getBoolean("tellus.skylight.off");

   /**
    * Compress the ocean so the trench fits above the world floor.
    *
    * <p><b>2026-10-10: reverted back to {@code false}. The user's goal is a true 1:1 Earth, and
    * compressing the ocean is precisely what breaks that.</b>
    *
    * <p>History, so this is not flip-flopped again: for one round the default was flipped to
    * {@code true} because at {@code worldScale = 1} the trench (-11034 m -&gt; Y -10970) fell below
    * {@code MIN_WORLD_Y = -2032} and the whole deep sea was clamped into a flat floor plane. That
    * diagnosis was right but the remedy was wrong: it silently scaled the ocean ~5.3x while leaving
    * the land at 1:1, i.e. a "fake 1:1".
    *
    * <p>The actual fix is to make the world <b>deep enough to hold the real trench</b>, which
    * {@link TellusLayout} now supports: with {@code -Dtellus.world.minY=-11040} and
    * {@code -Dtellus.layout.repack=true} the repacked bit budget gives Y 15 bits (32768 blocks),
    * so the trench (-11034) and Everest (+8848) both fit at exactly 1 block = 1 metre, with no
    * vertical scaling anywhere. See {@link TellusLayout} for the bit arithmetic.
    *
    * <p>This switch stays available for anyone who deliberately wants the old compressed-ocean
    * behaviour: {@code -Dtellus.fitOceanDepth=true}.
    */
   private static final boolean fitOceanDepth = Boolean.parseBoolean(System.getProperty("tellus.fitOceanDepth", "false"));

   /**
    * The two debug switches of the height upper bound. They are **made static** because {@code TellusPreLaunch.onPreLaunch()} has already
    * poured {@code config/telluscn-layout.properties} into the system properties before class loading,
    * and that cannot change at runtime -- previously every {@link #resolveHeightLimits} re-read them,
    * while {@code Tellus} calls it twice in a row during the same launch (compute the dimension + print a comparison).
    */
   private static final boolean AUTO_HEIGHT_OVERRIDE = Boolean.getBoolean("tellus.world.autoHeight");
   private static final Integer FORCED_HEIGHT = Integer.getInteger("tellus.height.force");
   private static final double EVEREST_ELEVATION_METERS = 8848.0;
   private static final double MARIANA_TRENCH_METERS = -11034.0;

   /**
    * 2026-09-29: **DEM bad sample floor** (established by measuring real data)
    *
    * <p>Decoding 527 real elevation tiles straight from a player's machine ({@code tellus/cache/elevation-tellus}) showed:
    * the deepest value is **-23070 metres**, while the deepest trench on Earth is **-10994 metres** => that is **data source noise**
    * -- spot checking the RGB of those pixels gives values like {@code (80,163,138)} / {@code (78,211,228)} / {@code (77,4,62)}, i.e. random noise
    * -- only a few tiles of the 527 are affected, in 0.57% of their pixels, but **a single bad sample is enough to flatten a whole block**.
    *
    * <p>Anything below this value is treated as a bad sample and clamped: {@code -12000 / worldScale(6) = -2000}, which fits into the world.
    */
   public static final double DEM_NODATA_METERS = -12000.0;
   /** Upper bound relaxed to 1200 metres per block (about 93 blocks per degree), leaving headroom for the slider. */
   /** Upper bound 10000 metres per block (= 1 block stands for 10 km), matching the "1 block : 10000 metres" requirement. */
   private static final double MAX_WORLD_SCALE = 10000.0;
   private static final int MAX_VOXY_PREGEN_RADIUS = 1024;
   private static final int MAX_VOXY_PREGEN_CHUNKS_PER_TICK = 200;
   private static final int MAX_DH_OSM_DETAIL = 24;
   private static final boolean FIXED_DH_OSM_FEATURES = true;
   private static final int FIXED_DH_OSM_ROAD_MAX_DETAIL = 6;
   private static final int FIXED_DH_OSM_BUILDING_MAX_DETAIL = 6;
   private static final boolean FIXED_DH_OSM_NON_BLOCKING_FETCH = true;
   //  2026-09-29: default worldScale = **6.0** (true-scale 1:1)
   //    1 block = 6 metres, **horizontal and vertical use the same scale** -- that is what real 1:1 means
   //    Worked out: trench -10994m/6 = **-1832**, Everest 8848m/6 = **+1475**
   //       both extremes land safely inside minY=-2032 .. top=10239
   //    Why it cannot be 1.0 (literal 1:1):
   //       the trench would need -10994 blocks while the dimension is only 2032 blocks deep => the whole deep sea gets flattened
   //       => that is exactly the "square pit" players report
   //    The horizontal is shrunk by the same factor 6 => the Earth takes 10% of the world width and you can go around it 10 times
   public static final EarthGeneratorSettings DEFAULT = new EarthGeneratorSettings(
      6.0,
      1.0,
      1.0,
      64,
      -2147483647,
      27.9881,
      86.925,
      -64,
      Integer.MIN_VALUE,
      5,
      5,
      true,
      // caveGeneration: on by default (it used to be false; the old conversion algorithm sent the caves to Y=48454 up in the sky,
      // so not a single cave ended up in the terrain -- that was a bug, not "caves are off by design". The conversion algorithm is fixed => on by default again)
      true,
      false,
      false,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      true,
      false,
      FIXED_DH_OSM_FEATURES,
      FIXED_DH_OSM_ROAD_MAX_DETAIL,
      FIXED_DH_OSM_BUILDING_MAX_DETAIL,
      FIXED_DH_OSM_NON_BLOCKING_FETCH,
      false,
      false,
      false,
      // voxyChunkPregenEnabled: reverted to the author default of false
      //   In round 42 I changed this to true -- measurement proved that was a mistake (log of 2026-09-19 23:25):
      //     a single server tick stalled for 40 seconds, followed by 81 seconds
      //     the ModernFix thread dump points at Server thread -> ServerChunkCache.getChunk (blocked)
      //       <- TellusVoxyPregenManager.processQueue <- onServerTick (runs every tick)
      //   => the pregeneration manager synchronously waits for chunk generation inside the server tick -> the main thread is held up as a whole
      //   To use it, add -Dtellus.voxy.pregen=true (or -Dtellus.voxy.chunksPerTick=N)
      false,
      // voxyChunkPregenMaxRadius: 96 (still clamped by Voxy's own chunk radius)
      96,
      // voxyChunkPregenChunksPerTick: reverted from 24 to the author default of 4
      //   In round 46 I changed it to 24, reasoning back then that "the manager slows itself down based on tick time, so a bigger value cannot bog down the server"
      //   -- **that judgement was disproved by measurement**: the automatic slowdown only kicks in **after** it is already blocked,
      //   and the blocking point is fetching the chunk (synchronous), so dropping to 0 cannot rescue the tick that already hung
      //   (lesson: a throttling mechanism cannot rescue the "synchronous blocking" itself -- see the round 92 record)
      4,
      EarthGeneratorSettings.DistantHorizonsRenderMode.FAST,
      EarthGeneratorSettings.DemSelection.automaticSelection(),
      false,
      false,
      false
   );
   private static final MapCodec<EarthGeneratorSettings.BaseToggles> BASE_TOGGLES_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            Codec.BOOL.optionalFieldOf("cave_generation").forGetter(EarthGeneratorSettings.BaseToggles::caveGeneration),
            Codec.BOOL.optionalFieldOf("cave_carvers").forGetter(EarthGeneratorSettings.BaseToggles::caveCarvers),
            Codec.BOOL.optionalFieldOf("large_caves").forGetter(EarthGeneratorSettings.BaseToggles::largeCaves),
            Codec.BOOL.optionalFieldOf("canyon_carvers").forGetter(EarthGeneratorSettings.BaseToggles::canyonCarvers),
            Codec.BOOL.fieldOf("ore_distribution").orElse(DEFAULT.oreDistribution()).forGetter(EarthGeneratorSettings.BaseToggles::oreDistribution),
            Codec.BOOL.fieldOf("lava_pools").orElse(DEFAULT.lavaPools()).forGetter(EarthGeneratorSettings.BaseToggles::lavaPools)
         )
         .apply(instance, EarthGeneratorSettings::createBaseToggles)
   );
   private static final MapCodec<EarthGeneratorSettings.SettingsBase> BASE_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            Codec.DOUBLE.fieldOf("world_scale").orElse(DEFAULT.worldScale()).forGetter(EarthGeneratorSettings.SettingsBase::worldScale),
            Codec.DOUBLE
               .fieldOf("terrestrial_height_scale")
               .orElse(DEFAULT.terrestrialHeightScale())
               .forGetter(EarthGeneratorSettings.SettingsBase::terrestrialHeightScale),
            Codec.DOUBLE
               .fieldOf("oceanic_height_scale")
               .orElse(DEFAULT.oceanicHeightScale())
               .forGetter(EarthGeneratorSettings.SettingsBase::oceanicHeightScale),
            Codec.INT.fieldOf("height_offset").orElse(DEFAULT.heightOffset()).forGetter(EarthGeneratorSettings.SettingsBase::heightOffset),
            Codec.DOUBLE.fieldOf("spawn_latitude").orElse(DEFAULT.spawnLatitude()).forGetter(EarthGeneratorSettings.SettingsBase::spawnLatitude),
            Codec.DOUBLE.fieldOf("spawn_longitude").orElse(DEFAULT.spawnLongitude()).forGetter(EarthGeneratorSettings.SettingsBase::spawnLongitude),
            Codec.INT.fieldOf("min_altitude").orElse(DEFAULT.minAltitude()).forGetter(EarthGeneratorSettings.SettingsBase::minAltitude),
            Codec.INT.fieldOf("max_altitude").orElse(DEFAULT.maxAltitude()).forGetter(EarthGeneratorSettings.SettingsBase::maxAltitude),
            Codec.INT
               .fieldOf("river_lake_shoreline_blend")
               .orElse(DEFAULT.riverLakeShorelineBlend())
               .forGetter(EarthGeneratorSettings.SettingsBase::riverLakeShorelineBlend),
            Codec.INT
               .fieldOf("ocean_shoreline_blend")
               .orElse(DEFAULT.oceanShorelineBlend())
               .forGetter(EarthGeneratorSettings.SettingsBase::oceanShorelineBlend),
            Codec.BOOL
               .fieldOf("shoreline_blend_cliff_limit")
               .orElse(DEFAULT.shorelineBlendCliffLimit())
               .forGetter(EarthGeneratorSettings.SettingsBase::shorelineBlendCliffLimit),
            BASE_TOGGLES_CODEC.forGetter(
               settings -> new EarthGeneratorSettings.BaseToggles(
                  Optional.of(settings.caveGeneration()),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  settings.oreDistribution(),
                  settings.lavaPools()
               )
            )
         )
         .apply(
            instance,
            (worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, spawnLatitude, spawnLongitude, minAltitude, maxAltitude, riverLakeShorelineBlend, oceanShorelineBlend, shorelineBlendCliffLimit, toggles) -> createSettingsBase(
               worldScale,
               terrestrialHeightScale,
               oceanicHeightScale,
               heightOffset,
               spawnLatitude,
               spawnLongitude,
               minAltitude,
               maxAltitude,
               riverLakeShorelineBlend,
               oceanShorelineBlend,
               shorelineBlendCliffLimit,
               toggles.resolveCaveGeneration(),
               toggles.oreDistribution(),
               toggles.lavaPools()
            )
         )
   );
   private static final MapCodec<Optional<Integer>> SEA_LEVEL_CODEC = Codec.INT.optionalFieldOf("sea_level");
   private static final MapCodec<EarthGeneratorSettings.DistantHorizonsRenderMode> DISTANT_HORIZONS_RENDER_MODE_CODEC = EarthGeneratorSettings.DistantHorizonsRenderMode.CODEC
      .fieldOf("distant_horizons_render_mode")
      .orElse(DEFAULT.distantHorizonsRenderMode());
   private static final MapCodec<Boolean> DEM_AUTOMATIC_FIELD_CODEC = Codec.BOOL.fieldOf("dem_automatic");
   private static final MapCodec<List<String>> DEM_ENABLED_PROVIDERS_FIELD_CODEC = Codec.STRING.listOf().fieldOf("dem_enabled_providers");
   private static final MapCodec<EarthGeneratorSettings.DemProvider> LEGACY_DEM_PROVIDER_CODEC = EarthGeneratorSettings.DemProvider.CODEC
      .fieldOf("dem_provider")
      .orElse(EarthGeneratorSettings.DemProvider.AUTO);
   private static final MapCodec<EarthGeneratorSettings.DemSelection> DEM_SELECTION_CODEC = MapCodec.of(
      new Implementation<EarthGeneratorSettings.DemSelection>() {
         public <T> RecordBuilder<T> encode(EarthGeneratorSettings.DemSelection input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            RecordBuilder<T> builder = EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.encode(input.automatic(), ops, prefix);
            return EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.encode(input.enabledProviderIds(), ops, builder);
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.concat(
               EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.keys(ops), EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.keys(ops)
            );
         }
      },
      new com.mojang.serialization.MapDecoder.Implementation<EarthGeneratorSettings.DemSelection>() {
         public <T> DataResult<EarthGeneratorSettings.DemSelection> decode(DynamicOps<T> ops, MapLike<T> input) {
            boolean hasAutomatic = input.get("dem_automatic") != null;
            boolean hasEnabledProviders = input.get("dem_enabled_providers") != null;
            if (hasAutomatic || hasEnabledProviders) {
               DataResult<Boolean> automatic = hasAutomatic
                  ? EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.decode(ops, input)
                  : DataResult.success(!hasEnabledProviders ? EarthGeneratorSettings.DEFAULT.demSelection().automatic() : false);
               DataResult<List<String>> enabledProviders = hasEnabledProviders
                  ? EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.decode(ops, input)
                  : DataResult.success(EarthGeneratorSettings.DEFAULT.demSelection().enabledProviderIds());
               return automatic.apply2(EarthGeneratorSettings.DemSelection::fromSerializedIds, enabledProviders);
            }

            return input.get("dem_provider") != null
               ? EarthGeneratorSettings.LEGACY_DEM_PROVIDER_CODEC.decode(ops, input).map(EarthGeneratorSettings.DemSelection::fromLegacyProvider)
               : DataResult.success(EarthGeneratorSettings.DEFAULT.demSelection());
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            Stream<T> currentKeys = Stream.concat(
               EarthGeneratorSettings.DEM_AUTOMATIC_FIELD_CODEC.keys(ops), EarthGeneratorSettings.DEM_ENABLED_PROVIDERS_FIELD_CODEC.keys(ops)
            );
            return Stream.concat(currentKeys, EarthGeneratorSettings.LEGACY_DEM_PROVIDER_CODEC.keys(ops));
         }
      }
   );
   private static final MapCodec<Boolean> DISTANT_HORIZONS_WATER_RESOLVER_CODEC = Codec.BOOL
      .fieldOf("distant_horizons_water_resolver")
      .orElse(DEFAULT.distantHorizonsWaterResolver());
   private static final MapCodec<Boolean> DISTANT_HORIZONS_OSM_FEATURES_CODEC = Codec.BOOL
      .fieldOf("distant_horizons_osm_features")
      .orElse(DEFAULT.distantHorizonsOsmFeatures());
   private static final MapCodec<Integer> DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC = Codec.intRange(0, MAX_DH_OSM_DETAIL)
      .fieldOf("distant_horizons_osm_road_max_detail")
      .orElse(DEFAULT.distantHorizonsOsmRoadMaxDetail());
   private static final MapCodec<Integer> DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC = Codec.intRange(0, MAX_DH_OSM_DETAIL)
      .fieldOf("distant_horizons_osm_building_max_detail")
      .orElse(DEFAULT.distantHorizonsOsmBuildingMaxDetail());
   private static final MapCodec<Boolean> DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC = Codec.BOOL
      .fieldOf("distant_horizons_osm_non_blocking_fetch")
      .orElse(DEFAULT.distantHorizonsOsmNonBlockingFetch());
   private static final MapCodec<Boolean> REALTIME_TIME_CODEC = Codec.BOOL.fieldOf("realtime_time").orElse(DEFAULT.realtimeTime());
   private static final MapCodec<Boolean> REALTIME_WEATHER_CODEC = Codec.BOOL.fieldOf("realtime_weather").orElse(DEFAULT.realtimeWeather());
   private static final MapCodec<Boolean> HISTORICAL_SNOW_CODEC = Codec.BOOL.fieldOf("historical_snow").orElse(DEFAULT.historicalSnow());
   private static final MapCodec<Boolean> ENABLE_ROADS_CODEC = Codec.BOOL.fieldOf("enable_roads").orElse(DEFAULT.enableRoads());
   private static final MapCodec<Boolean> ENABLE_BUILDINGS_CODEC = Codec.BOOL.fieldOf("enable_buildings").orElse(DEFAULT.enableBuildings());
   private static final MapCodec<Boolean> ENABLE_WATER_CODEC = Codec.BOOL.fieldOf("enable_water").orElse(DEFAULT.enableWater());
   private static final MapCodec<Boolean> VOXY_CHUNK_PREGEN_ENABLED_CODEC = Codec.BOOL
      .fieldOf("voxy_chunk_pregen_enabled")
      .orElse(DEFAULT.voxyChunkPregenEnabled());
   private static final MapCodec<Integer> VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC = Codec.intRange(0, MAX_VOXY_PREGEN_RADIUS)
      .fieldOf("voxy_chunk_pregen_max_radius")
      .orElse(DEFAULT.voxyChunkPregenMaxRadius());
   private static final MapCodec<Integer> VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC = Codec.intRange(1, MAX_VOXY_PREGEN_CHUNKS_PER_TICK)
      .fieldOf("voxy_chunk_pregen_chunks_per_tick")
      .orElse(DEFAULT.voxyChunkPregenChunksPerTick());
   private static final MapCodec<Boolean> DEEP_DARK_CODEC = Codec.BOOL.fieldOf("deep_dark").orElse(DEFAULT.deepDark());
   private static final MapCodec<Boolean> GEODES_CODEC = Codec.BOOL.fieldOf("geodes").orElse(DEFAULT.geodes());
   private static final MapCodec<EarthGeneratorSettings.StructureSettings> STRUCTURE_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            Codec.BOOL.fieldOf("add_strongholds").orElse(DEFAULT.addStrongholds()).forGetter(EarthGeneratorSettings.StructureSettings::addStrongholds),
            Codec.BOOL.fieldOf("add_villages").orElse(DEFAULT.addVillages()).forGetter(EarthGeneratorSettings.StructureSettings::addVillages),
            Codec.BOOL.fieldOf("add_mineshafts").orElse(DEFAULT.addMineshafts()).forGetter(EarthGeneratorSettings.StructureSettings::addMineshafts),
            Codec.BOOL
               .fieldOf("add_ocean_monuments")
               .orElse(DEFAULT.addOceanMonuments())
               .forGetter(EarthGeneratorSettings.StructureSettings::addOceanMonuments),
            Codec.BOOL
               .fieldOf("add_woodland_mansions")
               .orElse(DEFAULT.addWoodlandMansions())
               .forGetter(EarthGeneratorSettings.StructureSettings::addWoodlandMansions),
            Codec.BOOL.fieldOf("add_desert_temples").orElse(DEFAULT.addDesertTemples()).forGetter(EarthGeneratorSettings.StructureSettings::addDesertTemples),
            Codec.BOOL.fieldOf("add_jungle_temples").orElse(DEFAULT.addJungleTemples()).forGetter(EarthGeneratorSettings.StructureSettings::addJungleTemples),
            Codec.BOOL
               .fieldOf("add_pillager_outposts")
               .orElse(DEFAULT.addPillagerOutposts())
               .forGetter(EarthGeneratorSettings.StructureSettings::addPillagerOutposts),
            Codec.BOOL.fieldOf("add_ruined_portals").orElse(DEFAULT.addRuinedPortals()).forGetter(EarthGeneratorSettings.StructureSettings::addRuinedPortals),
            Codec.BOOL.fieldOf("add_shipwrecks").orElse(DEFAULT.addShipwrecks()).forGetter(EarthGeneratorSettings.StructureSettings::addShipwrecks),
            Codec.BOOL.fieldOf("add_ocean_ruins").orElse(DEFAULT.addOceanRuins()).forGetter(EarthGeneratorSettings.StructureSettings::addOceanRuins),
            Codec.BOOL
               .fieldOf("add_buried_treasure")
               .orElse(DEFAULT.addBuriedTreasure())
               .forGetter(EarthGeneratorSettings.StructureSettings::addBuriedTreasure),
            Codec.BOOL.fieldOf("add_igloos").orElse(DEFAULT.addIgloos()).forGetter(EarthGeneratorSettings.StructureSettings::addIgloos),
            Codec.BOOL.fieldOf("add_witch_huts").orElse(DEFAULT.addWitchHuts()).forGetter(EarthGeneratorSettings.StructureSettings::addWitchHuts),
            Codec.BOOL.fieldOf("add_ancient_cities").orElse(DEFAULT.addAncientCities()).forGetter(EarthGeneratorSettings.StructureSettings::addAncientCities),
            Codec.BOOL.fieldOf("add_trial_chambers").orElse(DEFAULT.addTrialChambers()).forGetter(EarthGeneratorSettings.StructureSettings::addTrialChambers)
         )
         .apply(instance, EarthGeneratorSettings::createStructureSettings)
   );
   private static final MapCodec<Boolean> TRAIL_RUINS_CODEC = Codec.BOOL.fieldOf("add_trail_ruins").orElse(DEFAULT.addTrailRuins());
   private static final MapCodec<EarthGeneratorSettings> MAP_CODEC = MapCodec.of(
      new Implementation<EarthGeneratorSettings>() {
         public <T> RecordBuilder<T> encode(EarthGeneratorSettings input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            RecordBuilder<T> builder = EarthGeneratorSettings.BASE_CODEC.encode(EarthGeneratorSettings.SettingsBase.fromSettings(input), ops, prefix);
            Optional<Integer> seaLevel = input.seaLevel() == -2147483647 ? Optional.empty() : Optional.of(input.seaLevel());
            builder = EarthGeneratorSettings.SEA_LEVEL_CODEC.encode(seaLevel, ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC.encode(input.distantHorizonsRenderMode(), ops, builder);
            builder = EarthGeneratorSettings.DEM_SELECTION_CODEC.encode(input.demSelection(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.encode(input.distantHorizonsWaterResolver(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.encode(input.distantHorizonsOsmFeatures(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.encode(input.distantHorizonsOsmRoadMaxDetail(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.encode(input.distantHorizonsOsmBuildingMaxDetail(), ops, builder);
            builder = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.encode(input.distantHorizonsOsmNonBlockingFetch(), ops, builder);
            builder = EarthGeneratorSettings.REALTIME_TIME_CODEC.encode(input.realtimeTime(), ops, builder);
            builder = EarthGeneratorSettings.REALTIME_WEATHER_CODEC.encode(input.realtimeWeather(), ops, builder);
            builder = EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.encode(input.historicalSnow(), ops, builder);
            builder = EarthGeneratorSettings.ENABLE_ROADS_CODEC.encode(input.enableRoads(), ops, builder);
            builder = EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.encode(input.enableBuildings(), ops, builder);
            builder = EarthGeneratorSettings.ENABLE_WATER_CODEC.encode(input.enableWater(), ops, builder);
            builder = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.encode(input.voxyChunkPregenEnabled(), ops, builder);
            builder = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.encode(input.voxyChunkPregenMaxRadius(), ops, builder);
            builder = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.encode(input.voxyChunkPregenChunksPerTick(), ops, builder);
            builder = EarthGeneratorSettings.DEEP_DARK_CODEC.encode(input.deepDark(), ops, builder);
            builder = EarthGeneratorSettings.GEODES_CODEC.encode(input.geodes(), ops, builder);
            builder = EarthGeneratorSettings.STRUCTURE_CODEC.encode(EarthGeneratorSettings.StructureSettings.fromSettings(input), ops, builder);
            return EarthGeneratorSettings.TRAIL_RUINS_CODEC.encode(input.addTrailRuins(), ops, builder);
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            Stream<T> baseKeys = Stream.concat(EarthGeneratorSettings.BASE_CODEC.keys(ops), EarthGeneratorSettings.SEA_LEVEL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEM_SELECTION_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_TIME_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_WEATHER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_ROADS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_WATER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEEP_DARK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.GEODES_CODEC.keys(ops));
            Stream<T> structureKeys = Stream.concat(baseKeys, EarthGeneratorSettings.STRUCTURE_CODEC.keys(ops));
            return Stream.concat(structureKeys, EarthGeneratorSettings.TRAIL_RUINS_CODEC.keys(ops));
         }
      },
      new com.mojang.serialization.MapDecoder.Implementation<EarthGeneratorSettings>() {
         public <T> DataResult<EarthGeneratorSettings> decode(DynamicOps<T> ops, MapLike<T> input) {
            DataResult<EarthGeneratorSettings.SettingsBase> base = EarthGeneratorSettings.BASE_CODEC.decode(ops, input);
            DataResult<Optional<Integer>> seaLevel = EarthGeneratorSettings.SEA_LEVEL_CODEC.decode(ops, input);
            DataResult<EarthGeneratorSettings.DistantHorizonsRenderMode> distantHorizonsRenderMode = EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC
               .decode(ops, input);
            DataResult<EarthGeneratorSettings.DemSelection> demSelection = EarthGeneratorSettings.DEM_SELECTION_CODEC.decode(ops, input);
            DataResult<Boolean> distantHorizonsWaterResolver = EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.decode(ops, input);
            DataResult<Boolean> distantHorizonsOsmFeatures = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.decode(ops, input);
            DataResult<Integer> distantHorizonsOsmRoadMaxDetail = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.decode(ops, input);
            DataResult<Integer> distantHorizonsOsmBuildingMaxDetail = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.decode(ops, input);
            DataResult<Boolean> distantHorizonsOsmNonBlockingFetch = EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.decode(ops, input);
            DataResult<Boolean> realtimeTime = EarthGeneratorSettings.REALTIME_TIME_CODEC.decode(ops, input);
            DataResult<Boolean> realtimeWeather = EarthGeneratorSettings.REALTIME_WEATHER_CODEC.decode(ops, input);
            DataResult<Boolean> historicalSnow = EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.decode(ops, input);
            DataResult<Boolean> enableRoads = EarthGeneratorSettings.ENABLE_ROADS_CODEC.decode(ops, input);
            DataResult<Boolean> enableBuildings = EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.decode(ops, input);
            DataResult<Boolean> enableWater = EarthGeneratorSettings.ENABLE_WATER_CODEC.decode(ops, input);
            DataResult<Boolean> voxyChunkPregenEnabled = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.decode(ops, input);
            DataResult<Integer> voxyChunkPregenMaxRadius = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.decode(ops, input);
            DataResult<Integer> voxyChunkPregenChunksPerTick = EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.decode(ops, input);
            DataResult<Boolean> deepDark = EarthGeneratorSettings.DEEP_DARK_CODEC.decode(ops, input);
            DataResult<Boolean> geodes = EarthGeneratorSettings.GEODES_CODEC.decode(ops, input);
            DataResult<EarthGeneratorSettings.StructureSettings> structures = EarthGeneratorSettings.STRUCTURE_CODEC.decode(ops, input);
            DataResult<Boolean> trailRuins = EarthGeneratorSettings.TRAIL_RUINS_CODEC.decode(ops, input);
            DataResult<EarthGeneratorSettings.SettingsBase> withSeaLevel = base.apply2(EarthGeneratorSettings::applySeaLevel, seaLevel);
            DataResult<EarthGeneratorSettings.SettingsBase> withRenderMode = withSeaLevel.apply2(
               EarthGeneratorSettings::applyDistantHorizonsRenderMode, distantHorizonsRenderMode
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withDemSelection = withRenderMode.apply2(EarthGeneratorSettings::applyDemSelection, demSelection);
            DataResult<EarthGeneratorSettings.SettingsBase> withWaterResolver = withDemSelection.apply2(
               EarthGeneratorSettings::applyDistantHorizonsWaterResolver, distantHorizonsWaterResolver
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withOsmFeatures = withWaterResolver.apply2(
               EarthGeneratorSettings::applyDistantHorizonsOsmFeatures, distantHorizonsOsmFeatures
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withRealtimeTime = withOsmFeatures.apply2(EarthGeneratorSettings::applyRealtimeTime, realtimeTime);
            DataResult<EarthGeneratorSettings.SettingsBase> withRealtimeWeather = withRealtimeTime.apply2(
               EarthGeneratorSettings::applyRealtimeWeather, realtimeWeather
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withHistoricalSnow = withRealtimeWeather.apply2(
               EarthGeneratorSettings::applyHistoricalSnow, historicalSnow
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withVoxyEnabled = withHistoricalSnow.apply2(
               EarthGeneratorSettings::applyVoxyChunkPregenEnabled, voxyChunkPregenEnabled
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withVoxyMaxRadius = withVoxyEnabled.apply2(
               EarthGeneratorSettings::applyVoxyChunkPregenMaxRadius, voxyChunkPregenMaxRadius
            );
            DataResult<EarthGeneratorSettings.SettingsBase> withVoxyChunksPerTick = withVoxyMaxRadius.apply2(
               EarthGeneratorSettings::applyVoxyChunkPregenChunksPerTick, voxyChunkPregenChunksPerTick
            );
            DataResult<EarthGeneratorSettings> settings = withVoxyChunksPerTick.map(EarthGeneratorSettings.SettingsBase::toSettings);
            settings = settings.apply2(EarthGeneratorSettings::applyDistantHorizonsOsmRoadMaxDetail, distantHorizonsOsmRoadMaxDetail);
            settings = settings.apply2(EarthGeneratorSettings::applyDistantHorizonsOsmBuildingMaxDetail, distantHorizonsOsmBuildingMaxDetail);
            settings = settings.apply2(EarthGeneratorSettings::applyDistantHorizonsOsmNonBlockingFetch, distantHorizonsOsmNonBlockingFetch);
            settings = settings.apply2(EarthGeneratorSettings::applyEnableRoads, enableRoads);
            settings = settings.apply2(EarthGeneratorSettings::applyEnableBuildings, enableBuildings);
            settings = settings.apply2(EarthGeneratorSettings::applyEnableWater, enableWater);
            settings = settings.apply2(EarthGeneratorSettings::applyDeepDark, deepDark);
            settings = settings.apply2(EarthGeneratorSettings::applyGeodes, geodes);
            settings = settings.apply2(EarthGeneratorSettings::withStructureSettings, structures);
            return settings.apply2(EarthGeneratorSettings::applyTrailRuins, trailRuins);
         }

         public <T> Stream<T> keys(DynamicOps<T> ops) {
            Stream<T> baseKeys = Stream.concat(EarthGeneratorSettings.BASE_CODEC.keys(ops), EarthGeneratorSettings.SEA_LEVEL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_RENDER_MODE_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEM_SELECTION_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_WATER_RESOLVER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_FEATURES_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_ROAD_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_BUILDING_MAX_DETAIL_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DISTANT_HORIZONS_OSM_NON_BLOCKING_FETCH_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_TIME_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.REALTIME_WEATHER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.HISTORICAL_SNOW_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_ROADS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_BUILDINGS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.ENABLE_WATER_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_ENABLED_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_MAX_RADIUS_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.VOXY_CHUNK_PREGEN_CHUNKS_PER_TICK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.DEEP_DARK_CODEC.keys(ops));
            baseKeys = Stream.concat(baseKeys, EarthGeneratorSettings.GEODES_CODEC.keys(ops));
            Stream<T> structureKeys = Stream.concat(baseKeys, EarthGeneratorSettings.STRUCTURE_CODEC.keys(ops));
            return Stream.concat(structureKeys, EarthGeneratorSettings.TRAIL_RUINS_CODEC.keys(ops));
         }
      }
   );
   public static final Codec<EarthGeneratorSettings> CODEC = MAP_CODEC.codec();

   public EarthGeneratorSettings(
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      int heightOffset,
      int seaLevel,
      double spawnLatitude,
      double spawnLongitude,
      int minAltitude,
      int maxAltitude,
      int riverLakeShorelineBlend,
      int oceanShorelineBlend,
      boolean shorelineBlendCliffLimit,
      boolean caveGeneration,
      boolean oreDistribution,
      boolean lavaPools,
      boolean addStrongholds,
      boolean addVillages,
      boolean addMineshafts,
      boolean addOceanMonuments,
      boolean addWoodlandMansions,
      boolean addDesertTemples,
      boolean addJungleTemples,
      boolean addPillagerOutposts,
      boolean addRuinedPortals,
      boolean addShipwrecks,
      boolean addOceanRuins,
      boolean addBuriedTreasure,
      boolean addIgloos,
      boolean addWitchHuts,
      boolean addAncientCities,
      boolean addTrialChambers,
      boolean addTrailRuins,
      boolean deepDark,
      boolean geodes,
      boolean distantHorizonsWaterResolver,
      boolean distantHorizonsOsmFeatures,
      int distantHorizonsOsmRoadMaxDetail,
      int distantHorizonsOsmBuildingMaxDetail,
      boolean distantHorizonsOsmNonBlockingFetch,
      boolean realtimeTime,
      boolean realtimeWeather,
      boolean historicalSnow,
      boolean voxyChunkPregenEnabled,
      int voxyChunkPregenMaxRadius,
      int voxyChunkPregenChunksPerTick,
      EarthGeneratorSettings.DistantHorizonsRenderMode distantHorizonsRenderMode,
      EarthGeneratorSettings.DemSelection demSelection,
      boolean enableRoads,
      boolean enableBuildings,
      boolean enableWater
   ) {
      worldScale = clampWorldScale(worldScale);
      voxyChunkPregenMaxRadius = Mth.clamp(voxyChunkPregenMaxRadius, 0, MAX_VOXY_PREGEN_RADIUS);
      voxyChunkPregenChunksPerTick = Mth.clamp(voxyChunkPregenChunksPerTick, 1, MAX_VOXY_PREGEN_CHUNKS_PER_TICK);
      distantHorizonsOsmFeatures = FIXED_DH_OSM_FEATURES;
      distantHorizonsOsmRoadMaxDetail = FIXED_DH_OSM_ROAD_MAX_DETAIL;
      distantHorizonsOsmBuildingMaxDetail = FIXED_DH_OSM_BUILDING_MAX_DETAIL;
      distantHorizonsOsmNonBlockingFetch = FIXED_DH_OSM_NON_BLOCKING_FETCH;
      this.worldScale = worldScale;
      this.terrestrialHeightScale = terrestrialHeightScale;
      // 2026-09-29: the ocean scale compression belongs **here**
      //   It used to sit only in `createSettingsBase`, but that is just the **codec decoding chain**,
      //   and when a player creates a world from `EarthCustomizeScreen.buildSettings()` the code **directly does `new EarthGeneratorSettings(...)`**,
      //   and `withXxx()` is the same => the compression **was never executed at all**
      //   Real-machine evidence: the surface is clamped at `surface mean -2032` while the elevation grid is `grid mean -13964`
      //   => the whole deep sea area is perfectly level at the **bottom of the world** => square pit (this is exactly that bug).
      //   It only really takes effect inside the **main constructor that every path goes through**
      //  2026-09-29: in 1:1 true-scale mode the **oceans are no longer compressed separately**
      //    what the user wants is a "1:1 complete copy of the Earth", and compressing the ocean here = secretly changing the vertical scale = a **fake 1:1**
      //    the depth problem is instead solved once and uniformly by `worldScale=6` (horizontal and vertical share one scale)
      //    The old compression logic is kept in `fitOceanicHeightScaleToWorld`,
      //    and is only enabled by an explicit `-Dtellus.fitOceanDepth=true` (for old worlds that want the old behaviour back)
      this.oceanicHeightScale = fitOceanDepth ? fitOceanicHeightScaleToWorld(oceanicHeightScale, worldScale, heightOffset) : oceanicHeightScale;
      this.heightOffset = heightOffset;
      this.seaLevel = seaLevel;
      this.spawnLatitude = spawnLatitude;
      this.spawnLongitude = spawnLongitude;
      this.minAltitude = minAltitude;
      this.maxAltitude = maxAltitude;
      this.riverLakeShorelineBlend = riverLakeShorelineBlend;
      this.oceanShorelineBlend = oceanShorelineBlend;
      this.shorelineBlendCliffLimit = shorelineBlendCliffLimit;
      this.caveGeneration = caveGeneration;
      this.oreDistribution = oreDistribution;
      this.lavaPools = lavaPools;
      this.addStrongholds = addStrongholds;
      this.addVillages = addVillages;
      this.addMineshafts = addMineshafts;
      this.addOceanMonuments = addOceanMonuments;
      this.addWoodlandMansions = addWoodlandMansions;
      this.addDesertTemples = addDesertTemples;
      this.addJungleTemples = addJungleTemples;
      this.addPillagerOutposts = addPillagerOutposts;
      this.addRuinedPortals = addRuinedPortals;
      this.addShipwrecks = addShipwrecks;
      this.addOceanRuins = addOceanRuins;
      this.addBuriedTreasure = addBuriedTreasure;
      this.addIgloos = addIgloos;
      this.addWitchHuts = addWitchHuts;
      this.addAncientCities = addAncientCities;
      this.addTrialChambers = addTrialChambers;
      this.addTrailRuins = addTrailRuins;
      this.deepDark = deepDark;
      this.geodes = geodes;
      this.distantHorizonsWaterResolver = distantHorizonsWaterResolver;
      this.distantHorizonsOsmFeatures = distantHorizonsOsmFeatures;
      this.distantHorizonsOsmRoadMaxDetail = distantHorizonsOsmRoadMaxDetail;
      this.distantHorizonsOsmBuildingMaxDetail = distantHorizonsOsmBuildingMaxDetail;
      this.distantHorizonsOsmNonBlockingFetch = distantHorizonsOsmNonBlockingFetch;
      this.realtimeTime = realtimeTime;
      this.realtimeWeather = realtimeWeather;
      this.historicalSnow = historicalSnow;
      this.voxyChunkPregenEnabled = voxyChunkPregenEnabled;
      this.voxyChunkPregenMaxRadius = voxyChunkPregenMaxRadius;
      this.voxyChunkPregenChunksPerTick = voxyChunkPregenChunksPerTick;
      this.distantHorizonsRenderMode = Objects.requireNonNull(distantHorizonsRenderMode, "distantHorizonsRenderMode");
      this.demSelection = Objects.requireNonNull(demSelection, "demSelection");
      this.enableRoads = enableRoads;
      this.enableBuildings = enableBuildings;
      this.enableWater = enableWater;
   }

   public boolean isSeaLevelAutomatic() {
      return this.seaLevel == -2147483647;
   }

   public int resolveSeaLevel() {
      return this.seaLevel == -2147483647 ? this.heightOffset : this.seaLevel;
   }

   private static EarthGeneratorSettings.StructureSettings createStructureSettings(
      Boolean addStrongholds,
      Boolean addVillages,
      Boolean addMineshafts,
      Boolean addOceanMonuments,
      Boolean addWoodlandMansions,
      Boolean addDesertTemples,
      Boolean addJungleTemples,
      Boolean addPillagerOutposts,
      Boolean addRuinedPortals,
      Boolean addShipwrecks,
      Boolean addOceanRuins,
      Boolean addBuriedTreasure,
      Boolean addIgloos,
      Boolean addWitchHuts,
      Boolean addAncientCities,
      Boolean addTrialChambers
   ) {
      return new EarthGeneratorSettings.StructureSettings(
         Objects.requireNonNull(addStrongholds, "addStrongholds"),
         Objects.requireNonNull(addVillages, "addVillages"),
         Objects.requireNonNull(addMineshafts, "addMineshafts"),
         Objects.requireNonNull(addOceanMonuments, "addOceanMonuments"),
         Objects.requireNonNull(addWoodlandMansions, "addWoodlandMansions"),
         Objects.requireNonNull(addDesertTemples, "addDesertTemples"),
         Objects.requireNonNull(addJungleTemples, "addJungleTemples"),
         Objects.requireNonNull(addPillagerOutposts, "addPillagerOutposts"),
         Objects.requireNonNull(addRuinedPortals, "addRuinedPortals"),
         Objects.requireNonNull(addShipwrecks, "addShipwrecks"),
         Objects.requireNonNull(addOceanRuins, "addOceanRuins"),
         Objects.requireNonNull(addBuriedTreasure, "addBuriedTreasure"),
         Objects.requireNonNull(addIgloos, "addIgloos"),
         Objects.requireNonNull(addWitchHuts, "addWitchHuts"),
         Objects.requireNonNull(addAncientCities, "addAncientCities"),
         Objects.requireNonNull(addTrialChambers, "addTrialChambers")
      );
   }

   private static EarthGeneratorSettings.SettingsBase createSettingsBase(
      Double worldScale,
      Double terrestrialHeightScale,
      Double oceanicHeightScale,
      Integer heightOffset,
      Double spawnLatitude,
      Double spawnLongitude,
      Integer minAltitude,
      Integer maxAltitude,
      Integer riverLakeShorelineBlend,
      Integer oceanShorelineBlend,
      Boolean shorelineBlendCliffLimit,
      Boolean caveGeneration,
      Boolean oreDistribution,
      Boolean lavaPools
   ) {
      int resolvedHeightOffset = Objects.requireNonNull(heightOffset, "heightOffset");
      int resolvedSeaLevel = -2147483647;
      double resolvedWorldScale = clampWorldScale(Objects.requireNonNull(worldScale, "worldScale"));
      double resolvedOceanicHeightScale = fitOceanicHeightScaleToWorld(
         Objects.requireNonNull(oceanicHeightScale, "oceanicHeightScale"),
         resolvedWorldScale,
         resolvedHeightOffset
      );
      return new EarthGeneratorSettings.SettingsBase(
         resolvedWorldScale,
         Objects.requireNonNull(terrestrialHeightScale, "terrestrialHeightScale"),
         resolvedOceanicHeightScale,
         resolvedHeightOffset,
         resolvedSeaLevel,
         Objects.requireNonNull(spawnLatitude, "spawnLatitude"),
         Objects.requireNonNull(spawnLongitude, "spawnLongitude"),
         Objects.requireNonNull(minAltitude, "minAltitude"),
         Objects.requireNonNull(maxAltitude, "maxAltitude"),
         Objects.requireNonNull(riverLakeShorelineBlend, "riverLakeShorelineBlend"),
         Objects.requireNonNull(oceanShorelineBlend, "oceanShorelineBlend"),
         Objects.requireNonNull(shorelineBlendCliffLimit, "shorelineBlendCliffLimit"),
         Objects.requireNonNull(caveGeneration, "caveGeneration"),
         Objects.requireNonNull(oreDistribution, "oreDistribution"),
         Objects.requireNonNull(lavaPools, "lavaPools"),
         DEFAULT.distantHorizonsWaterResolver(),
         DEFAULT.distantHorizonsOsmFeatures(),
         DEFAULT.realtimeTime(),
         DEFAULT.realtimeWeather(),
         DEFAULT.historicalSnow(),
         DEFAULT.voxyChunkPregenEnabled(),
         DEFAULT.voxyChunkPregenMaxRadius(),
         DEFAULT.voxyChunkPregenChunksPerTick(),
         DEFAULT.distantHorizonsRenderMode(),
         DEFAULT.demSelection()
      );
   }

   private static EarthGeneratorSettings.BaseToggles createBaseToggles(
      Optional<Boolean> caveGeneration,
      Optional<Boolean> caveCarvers,
      Optional<Boolean> largeCaves,
      Optional<Boolean> canyonCarvers,
      Boolean oreDistribution,
      Boolean lavaPools
   ) {
      return new EarthGeneratorSettings.BaseToggles(
         caveGeneration,
         caveCarvers,
         largeCaves,
         canyonCarvers,
         Objects.requireNonNull(oreDistribution, "oreDistribution"),
         Objects.requireNonNull(lavaPools, "lavaPools")
      );
   }

   private static double clampWorldScale(double worldScale) {
      return worldScale <= 0.0 ? worldScale : Math.min(worldScale, MAX_WORLD_SCALE);
   }

   private static EarthGeneratorSettings.SettingsBase applySeaLevel(EarthGeneratorSettings.SettingsBase settings, Optional<Integer> seaLevel) {
      Optional<Integer> value = Objects.requireNonNull(seaLevel, "seaLevel");
      if (value.isEmpty()) {
         return settings;
      } else {
         int resolved = value.get();
         return resolved == -2147483647 ? settings.withSeaLevel(-2147483647) : settings.withSeaLevel(resolved);
      }
   }

   private static EarthGeneratorSettings.SettingsBase applyDistantHorizonsRenderMode(
      EarthGeneratorSettings.SettingsBase settings, EarthGeneratorSettings.DistantHorizonsRenderMode renderMode
   ) {
      return settings.withDistantHorizonsRenderMode(Objects.requireNonNull(renderMode, "renderMode"));
   }

   private static EarthGeneratorSettings.SettingsBase applyDemSelection(
      EarthGeneratorSettings.SettingsBase settings, EarthGeneratorSettings.DemSelection demSelection
   ) {
      return settings.withDemSelection(Objects.requireNonNull(demSelection, "demSelection"));
   }

   private static EarthGeneratorSettings.SettingsBase applyDistantHorizonsWaterResolver(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withDistantHorizonsWaterResolver(Objects.requireNonNull(enabled, "distantHorizonsWaterResolver"));
   }

   private static EarthGeneratorSettings.SettingsBase applyDistantHorizonsOsmFeatures(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withDistantHorizonsOsmFeatures(Objects.requireNonNull(enabled, "distantHorizonsOsmFeatures"));
   }

   private static EarthGeneratorSettings.SettingsBase applyRealtimeTime(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withRealtimeTime(Objects.requireNonNull(enabled, "realtimeTime"));
   }

   private static EarthGeneratorSettings.SettingsBase applyRealtimeWeather(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withRealtimeWeather(Objects.requireNonNull(enabled, "realtimeWeather"));
   }

   private static EarthGeneratorSettings.SettingsBase applyHistoricalSnow(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withHistoricalSnow(Objects.requireNonNull(enabled, "historicalSnow"));
   }

   private static EarthGeneratorSettings applyDistantHorizonsOsmRoadMaxDetail(EarthGeneratorSettings settings, Integer value) {
      return settings.withDistantHorizonsOsmRoadMaxDetail(Objects.requireNonNull(value, "distantHorizonsOsmRoadMaxDetail"));
   }

   private static EarthGeneratorSettings applyDistantHorizonsOsmBuildingMaxDetail(EarthGeneratorSettings settings, Integer value) {
      return settings.withDistantHorizonsOsmBuildingMaxDetail(Objects.requireNonNull(value, "distantHorizonsOsmBuildingMaxDetail"));
   }

   private static EarthGeneratorSettings applyDistantHorizonsOsmNonBlockingFetch(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withDistantHorizonsOsmNonBlockingFetch(Objects.requireNonNull(enabled, "distantHorizonsOsmNonBlockingFetch"));
   }

   private static EarthGeneratorSettings applyEnableRoads(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withEnableRoads(Objects.requireNonNull(enabled, "enableRoads"));
   }

   private static EarthGeneratorSettings applyEnableBuildings(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withEnableBuildings(Objects.requireNonNull(enabled, "enableBuildings"));
   }

   private static EarthGeneratorSettings applyEnableWater(EarthGeneratorSettings settings, Boolean enabled) {
      return settings.withEnableWater(Objects.requireNonNull(enabled, "enableWater"));
   }

   private static EarthGeneratorSettings.SettingsBase applyVoxyChunkPregenEnabled(EarthGeneratorSettings.SettingsBase settings, Boolean enabled) {
      return settings.withVoxyChunkPregenEnabled(Objects.requireNonNull(enabled, "voxyChunkPregenEnabled"));
   }

   private static EarthGeneratorSettings.SettingsBase applyVoxyChunkPregenMaxRadius(EarthGeneratorSettings.SettingsBase settings, Integer maxRadius) {
      return settings.withVoxyChunkPregenMaxRadius(Objects.requireNonNull(maxRadius, "voxyChunkPregenMaxRadius"));
   }

   private static EarthGeneratorSettings.SettingsBase applyVoxyChunkPregenChunksPerTick(EarthGeneratorSettings.SettingsBase settings, Integer chunksPerTick) {
      return settings.withVoxyChunkPregenChunksPerTick(Objects.requireNonNull(chunksPerTick, "voxyChunkPregenChunksPerTick"));
   }

   private EarthGeneratorSettings withStructureSettings(EarthGeneratorSettings.StructureSettings structures) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         structures.addStrongholds(),
         structures.addVillages(),
         structures.addMineshafts(),
         structures.addOceanMonuments(),
         structures.addWoodlandMansions(),
         structures.addDesertTemples(),
         structures.addJungleTemples(),
         structures.addPillagerOutposts(),
         structures.addRuinedPortals(),
         structures.addShipwrecks(),
         structures.addOceanRuins(),
         structures.addBuriedTreasure(),
         structures.addIgloos(),
         structures.addWitchHuts(),
         structures.addAncientCities(),
         structures.addTrialChambers(),
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private static EarthGeneratorSettings applyTrailRuins(EarthGeneratorSettings settings, Boolean addTrailRuins) {
      return settings.withTrailRuins(Objects.requireNonNull(addTrailRuins, "addTrailRuins"));
   }

   private static EarthGeneratorSettings applyDeepDark(EarthGeneratorSettings settings, Boolean deepDark) {
      return settings.withDeepDark(Objects.requireNonNull(deepDark, "deepDark"));
   }

   private static EarthGeneratorSettings applyGeodes(EarthGeneratorSettings settings, Boolean geodes) {
      return settings.withGeodes(Objects.requireNonNull(geodes, "geodes"));
   }

   private EarthGeneratorSettings withTrailRuins(boolean addTrailRuins) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withDeepDark(boolean deepDark) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withGeodes(boolean geodes) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withDistantHorizonsOsmRoadMaxDetail(int maxDetail) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         maxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withDistantHorizonsOsmBuildingMaxDetail(int maxDetail) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         maxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withDistantHorizonsOsmNonBlockingFetch(boolean nonBlockingFetch) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         nonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withEnableRoads(boolean enableRoads) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         enableRoads,
         this.enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withEnableBuildings(boolean enableBuildings) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         enableBuildings,
         this.enableWater
      );
   }

   private EarthGeneratorSettings withEnableWater(boolean enableWater) {
      return new EarthGeneratorSettings(
         this.worldScale,
         this.terrestrialHeightScale,
         this.oceanicHeightScale,
         this.heightOffset,
         this.seaLevel,
         this.spawnLatitude,
         this.spawnLongitude,
         this.minAltitude,
         this.maxAltitude,
         this.riverLakeShorelineBlend,
         this.oceanShorelineBlend,
         this.shorelineBlendCliffLimit,
         this.caveGeneration,
         this.oreDistribution,
         this.lavaPools,
         this.addStrongholds,
         this.addVillages,
         this.addMineshafts,
         this.addOceanMonuments,
         this.addWoodlandMansions,
         this.addDesertTemples,
         this.addJungleTemples,
         this.addPillagerOutposts,
         this.addRuinedPortals,
         this.addShipwrecks,
         this.addOceanRuins,
         this.addBuriedTreasure,
         this.addIgloos,
         this.addWitchHuts,
         this.addAncientCities,
         this.addTrialChambers,
         this.addTrailRuins,
         this.deepDark,
         this.geodes,
         this.distantHorizonsWaterResolver,
         this.distantHorizonsOsmFeatures,
         this.distantHorizonsOsmRoadMaxDetail,
         this.distantHorizonsOsmBuildingMaxDetail,
         this.distantHorizonsOsmNonBlockingFetch,
         this.realtimeTime,
         this.realtimeWeather,
         this.historicalSnow,
         this.voxyChunkPregenEnabled,
         this.voxyChunkPregenMaxRadius,
         this.voxyChunkPregenChunksPerTick,
         this.distantHorizonsRenderMode,
         this.demSelection,
         this.enableRoads,
         this.enableBuildings,
         enableWater
      );
   }

   public static EarthGeneratorSettings.HeightLimits resolveHeightLimits(EarthGeneratorSettings settings) {
      // In the repackaged layout **the full height is used by default**: turning this mode on is exactly what a tall world is wanted for,
      // while the automatic estimate only follows the terrain elevation (a few hundred blocks), which makes turning it on pointless. For the automatic estimate again: -Dtellus.world.autoHeight=true
      //
      // But this "use the full height" **can only be done on the client**: really raising the dimension is a capability of the "create world" screen (client side),
      //    on a dedicated server the dimension still keeps the height from the data packet; if this reported the full height anyway, the generator would try to fill sections
      //    the dimension cannot hold (65536 tall vs a dimension of 800) -> spawn generation hangs. That was actually hit in server testing, hence the environment check.
      if (TellusLayout.TALL && CLIENT_SIDE && !AUTO_HEIGHT_OVERRIDE) {
         return new EarthGeneratorSettings.HeightLimits(MIN_WORLD_Y, MAX_WORLD_HEIGHT, MAX_WORLD_HEIGHT);
      }
      if (TellusLayout.TALL && !CLIENT_SIDE) {
         LOGGER.warn("[TellusCN] Repacked layout requested on a dedicated server (-Dtellus.world.top={}), but dimension height cannot be changed there"
                            + "（那是客户端建世界时的功能），所以本存档仍按配置好的高度生成。"
                            + "要超高世界请在客户端建。", TellusLayout.WORLD_TOP_Y);
      }
      // Forced height (debug / extreme play): -Dtellus.height.force=67568
      // When it is passed, the automatic estimate is ignored and the dimension height is pulled straight to this value (bounded by MAX_DIMENSION_HEIGHT).
      Integer forced = FORCED_HEIGHT;
      if (forced != null && forced > 0) {
         // 2026-09-26: **clamp first, align afterwards**
         //   The original order was `Mth.clamp(alignUp(forced, 16), 16, MAX)`:
         //   `alignUp` internally does `value + 15` and then masks, so with a huge `forced` it **overflows into a negative number first**
         //   (for example `-Dtellus.height.force=999999999` -> the aligned result is negative -> clamped down to the lower bound 16
         //     => the world that should have been "pulled to the top" ends up **only 16 blocks tall**)
         //   Aligning only after clamping to the upper bound makes an overflow impossible; for normal values the result is exactly the same
         int height = alignUp(Mth.clamp(forced, HEIGHT_ALIGNMENT, MAX_DIMENSION_HEIGHT), HEIGHT_ALIGNMENT);
         int minY = MIN_WORLD_Y;
         if (minY + height > MAX_WORLD_Y + 1) {
            minY = MAX_WORLD_Y + 1 - height;
         }
         return new EarthGeneratorSettings.HeightLimits(minY, height, height);
      }
      int autoMin = computeAutoMinAltitude(settings);
      int autoMax = computeAutoMaxAltitude(settings);
      boolean autoMinEnabled = settings.minAltitude() == Integer.MIN_VALUE;
      boolean autoMaxEnabled = settings.maxAltitude() == Integer.MIN_VALUE;
      if ((!autoMinEnabled || autoMin >= MIN_WORLD_Y) && (!autoMaxEnabled || autoMax <= MAX_WORLD_Y)) {
         int resolvedMin = autoMinEnabled ? autoMin : settings.minAltitude();
         int resolvedMax = autoMaxEnabled ? autoMax : settings.maxAltitude();
         if (resolvedMin > resolvedMax) {
            int swap = resolvedMin;
            resolvedMin = resolvedMax;
            resolvedMax = swap;
         }

         resolvedMin = Mth.clamp(resolvedMin, MIN_WORLD_Y, MAX_WORLD_Y);
         resolvedMax = Mth.clamp(resolvedMax, MIN_WORLD_Y, MAX_WORLD_Y);
         int alignedMin = alignDown(resolvedMin, HEIGHT_ALIGNMENT);
         int alignedTop = alignUp(resolvedMax + 1, HEIGHT_ALIGNMENT);
         int height = alignedTop - alignedMin;
         if (alignedMin >= MIN_WORLD_Y && alignedTop - 1 <= MAX_WORLD_Y && height <= MAX_WORLD_HEIGHT) {
            if (height <= 0) {
               height = HEIGHT_ALIGNMENT;
               alignedTop = alignedMin + height;
               if (alignedTop - 1 > MAX_WORLD_Y) {
                  return EarthGeneratorSettings.HeightLimits.maxRange();
               }
            }

            return new EarthGeneratorSettings.HeightLimits(alignedMin, height, height);
         } else {
            return EarthGeneratorSettings.HeightLimits.maxRange();
         }
      } else {
         return EarthGeneratorSettings.HeightLimits.maxRange();
      }
   }

   /**
    * 2026-09-28: make **the trench fit into the world** (the fourth mechanism behind the "square pit")
    *
    * <p><b>Symptom</b>: the self-check log says {@code surface mean -64, grid mean -10680}
    * -- {@code -10680} is **the real Mariana Trench** (-11034 m) converted into blocks,
    * while the bottom of the world is only -64 => the whole deep sea area is **clamped to that same -64**
    * => a perfectly level floor plane for the world => at the chunk boundary there is a **square step** (the square players see).
    *
    * <p><b>Why the real depth cannot simply be copied over</b>: the Minecraft dimension floor is **hardcoded at -2032**,
    * at a 1:1 scale, fitting -11034 m needs a world height of 19882 blocks => 1242 sections per chunk
    * (the comments in this project already say that 132 sections is enough for "the client cannot get in").
    * So the **land stays at 1:1 untouched**, and only the **ocean** is compressed until it just fits below the bottom of the world.
    *
    * <p><b>How</b>: compute the largest {@code oceanicHeightScale} that keeps the trench from reaching past the bottom of the world,
    * then take the **smaller** of that and the user setting -- if the user lowered it, it is used as is; if they raised it, it is automatically pulled down to what fits.
    * The land scale {@code terrestrialHeightScale} is **left completely untouched**.
    *
    * <p>To opt out of the automatic change: {@code -Dtellus.fitOceanDepth=false}.
    */
   private static double fitOceanicHeightScaleToWorld(double oceanicHeightScale, double worldScale, int heightOffset) {
      if (!fitOceanDepth || worldScale <= 0.0 || oceanicHeightScale <= 0.0) {
         return oceanicHeightScale;
      }

      //  The deepest point of the trench converted into blocks must be **not below** the bottom of the world (keep 1 block of headroom)
      int deepestAllowedBlocks = heightOffset - MIN_WORLD_Y - 1;
      if (deepestAllowedBlocks <= 0) {
         return oceanicHeightScale;
      }

      double maxScale = deepestAllowedBlocks * worldScale / -MARIANA_TRENCH_METERS;
      double fitted = oceanicHeightScale < maxScale ? oceanicHeightScale : maxScale;
      //  2026-09-29: print the compression result -- the previous round's "the compression did not take effect" judgement was pure guesswork,
      //    this time hard evidence is needed to localise it (in the real-machine log the grid mean is still below the bottom of the world => the compression is in doubt)
      com.yucareux.tellus.Tellus.LOGGER.info(
         "[TellusCN] Ocean depth fit: configured oceanicHeightScale={} -> effective {} (world-bottom limit {}, worldScale={} heightOffset={} minY={}) {}",
         formatScaleForLog(oceanicHeightScale),
         formatScaleForLog(fitted),
         formatScaleForLog(maxScale),
         worldScale,
         heightOffset,
         MIN_WORLD_Y,
         fitted < oceanicHeightScale ? "⇒ 已压缩" : "⇒ 未压缩（用户设定本就在容许范围内）"
      );
      return fitted;
   }

   /** Fixed-point formatting used only for logging, avoiding the cost of one {@code String.format} per world. */
   private static String formatScaleForLog(double value) {
      return Double.toString(Math.round(value * 1000000.0) / 1000000.0);
   }

   private static int computeAutoMaxAltitude(EarthGeneratorSettings settings) {
      if (settings.worldScale() <= 0.0) {
         return settings.heightOffset();
      } else {
         double scaled = EVEREST_ELEVATION_METERS * settings.terrestrialHeightScale() / settings.worldScale();
         int maxSurface = Mth.ceil(scaled) + settings.heightOffset();
         return maxSurface + ALTITUDE_TOLERANCE;
      }
   }

   private static int computeAutoMinAltitude(EarthGeneratorSettings settings) {
      if (settings.worldScale() <= 0.0) {
         return settings.heightOffset();
      } else {
         double scaled = DEM_NODATA_METERS * settings.oceanicHeightScale() / settings.worldScale();
         int minSurface = Mth.floor(scaled) + settings.heightOffset();
         return minSurface - ALTITUDE_TOLERANCE;
      }
   }

   private static int alignDown(int value, int alignment) {
      int remainder = Math.floorMod(value, alignment);
      return value - remainder;
   }

   private static int alignUp(int value, int alignment) {
      int remainder = Math.floorMod(value, alignment);
      return remainder == 0 ? value : value + (alignment - remainder);
   }

   public static DimensionType applyHeightLimits(DimensionType base, EarthGeneratorSettings.HeightLimits limits) {
      // **Fallback clamp**: whatever the caller passes in is clamped into the range the vanilla constructor accepts.
      // The vanilla check is min_y >= MIN_WORLD_Y and min_y + height <= MAX_WORLD_Y + 1,
      // and failing it throws IllegalStateException and crashes the game (that is exactly how it crashed this round).
      int minY = Math.max(MIN_WORLD_Y, limits.minY());
      int height = Math.max(HEIGHT_ALIGNMENT, alignUp(limits.height(), HEIGHT_ALIGNMENT));
      if (minY + height > MAX_WORLD_Y + 1) {
         height = alignDown(MAX_WORLD_Y + 1 - minY, HEIGHT_ALIGNMENT);
      }
      if (height < HEIGHT_ALIGNMENT) {
         // The clamp above left nothing (this should not happen) -> fall back to the whole safe range
         minY = MIN_WORLD_Y;
         height = MAX_WORLD_HEIGHT;
      }
      height = Math.min(height, MAX_WORLD_HEIGHT);
      int logicalHeight = Math.min(height, Math.max(HEIGHT_ALIGNMENT, limits.logicalHeight()));
      // Skylight is only computed within logical_height -> it decides how much skylight data ends up in the **chunk packet**
      //   The vanilla chunk packet cap is 2 MB while skylight is about 2 KB per section => 4100 sections is about 8 MB and the client simply rejects it
      //   so a tall world has to squeeze logical_height down (measured: at 65536 tall, squeezing it to 512 gives a packet of about 1 MB, which the protocol allows)
      //   The cost: areas above 512 have no skylight (ambient_light keeps them visible)
      logicalHeight = Math.min(logicalHeight, Math.max(HEIGHT_ALIGNMENT, LOGICAL_HEIGHT_CAP));
      // The component order follows the DimensionType record of 1.21.11.
      // 1.21.11 shrank the record from 16 to 14 components: ultraWarm, natural,
      // bedWorks, respawnAnchorWorks, effectsLocation and cloudHeight were all
      // folded into the new `attributes` (EnvironmentAttributeMap) component,
      // and fixedTime() was renamed to hasFixedTime(). Carrying `base.attributes()`
      // over unchanged therefore preserves every one of those old flags, and this
      // method only has to override the three height-related components.
      return new DimensionType(
         base.hasFixedTime(),
         base.hasSkyLight() && !SKYLIGHT_DISABLED,
         base.hasCeiling(),
         base.coordinateScale(),
         minY,
         height,
         logicalHeight,
         base.infiniburn(),
         base.ambientLight(),
         base.monsterSettings(),
         base.skybox(),
         base.cardinalLightType(),
         base.attributes(),
         base.timelines()
      );
   }

   private record BaseToggles(
      Optional<Boolean> caveGeneration,
      Optional<Boolean> caveCarvers,
      Optional<Boolean> largeCaves,
      Optional<Boolean> canyonCarvers,
      boolean oreDistribution,
      boolean lavaPools
   ) {
      private boolean resolveCaveGeneration() {
         if (this.caveGeneration.isPresent()) {
            return this.caveGeneration.get();
         } else {
            boolean hasLegacy = this.caveCarvers.isPresent() || this.largeCaves.isPresent() || this.canyonCarvers.isPresent();
            return !hasLegacy
               ? EarthGeneratorSettings.DEFAULT.caveGeneration()
               : this.caveCarvers.orElse(false) || this.largeCaves.orElse(false) || this.canyonCarvers.orElse(false);
         }
      }
   }

   public static enum DemProvider {
      AUTO("auto", false, 0),
      TERRARIUM("terrarium", true, 1),
      SWISSALTI3D("swissalti3d", true, 1 << 1),
      AHN("ahn", true, 1 << 2),
      CANELEVATION("canelevation", true, 1 << 3),
      NORWAYDTM1("norwaydtm1", true, 1 << 4),
      JAPANGSI("japangsi", true, 1 << 5),
      USGS("usgs", true, 1 << 6),
      COPERNICUS("copernicus", true, 1 << 7),
      ARCTICDEM("arcticdem", true, 1 << 8);

      public static final Codec<EarthGeneratorSettings.DemProvider> CODEC = Codec.STRING
         .xmap(EarthGeneratorSettings.DemProvider::fromId, EarthGeneratorSettings.DemProvider::id);
      private final String id;
      private final boolean userSelectable;
      private final int selectionBit;

      private DemProvider(String id, boolean userSelectable, int selectionBit) {
         this.id = Objects.requireNonNull(id, "id");
         this.userSelectable = userSelectable;
         this.selectionBit = selectionBit;
      }

      public String id() {
         return this.id;
      }

      public boolean userSelectable() {
         return this.userSelectable;
      }

      public int selectionBit() {
         return this.selectionBit;
      }

      public static EarthGeneratorSettings.DemProvider fromId(String id) {
         if (id == null) {
            return AUTO;
         } else {
            if ("hma".equalsIgnoreCase(id)) {
               return AUTO;
            }

            for (EarthGeneratorSettings.DemProvider provider : values()) {
               if (provider.id.equalsIgnoreCase(id)) {
                  return provider;
               }
            }

            return AUTO;
         }
      }
   }

   public record DemSelection(boolean automatic, int enabledProviderMask) {
      private static final List<EarthGeneratorSettings.DemProvider> USER_SELECTABLE_PROVIDERS = List.of(
         EarthGeneratorSettings.DemProvider.TERRARIUM,
         EarthGeneratorSettings.DemProvider.SWISSALTI3D,
         EarthGeneratorSettings.DemProvider.AHN,
         EarthGeneratorSettings.DemProvider.CANELEVATION,
         EarthGeneratorSettings.DemProvider.NORWAYDTM1,
         EarthGeneratorSettings.DemProvider.JAPANGSI,
         EarthGeneratorSettings.DemProvider.USGS,
         EarthGeneratorSettings.DemProvider.COPERNICUS,
         EarthGeneratorSettings.DemProvider.ARCTICDEM
      );
      private static final int GLOBAL_COVERAGE_MASK = EarthGeneratorSettings.DemProvider.TERRARIUM.selectionBit()
         | EarthGeneratorSettings.DemProvider.COPERNICUS.selectionBit();
      private static final int FULL_USER_SELECTABLE_MASK = computeFullUserSelectableMask();
      private static final List<String> FULL_PROVIDER_IDS = USER_SELECTABLE_PROVIDERS.stream().map(EarthGeneratorSettings.DemProvider::id).toList();
      private static final EarthGeneratorSettings.DemSelection AUTOMATIC_SELECTION = new EarthGeneratorSettings.DemSelection(true, FULL_USER_SELECTABLE_MASK);

      public DemSelection {
         int normalizedMask = automatic ? FULL_USER_SELECTABLE_MASK : normalizeManualMask(enabledProviderMask);
         enabledProviderMask = normalizedMask;
      }

      public static EarthGeneratorSettings.DemSelection automaticSelection() {
         return AUTOMATIC_SELECTION;
      }

      public static EarthGeneratorSettings.DemSelection manual(int enabledProviderMask) {
         return new EarthGeneratorSettings.DemSelection(false, enabledProviderMask);
      }

      public static EarthGeneratorSettings.DemSelection manual(List<EarthGeneratorSettings.DemProvider> providers) {
         return manual(maskFromProviders(providers));
      }

      private static EarthGeneratorSettings.DemSelection fromSerializedIds(boolean automatic, List<String> providerIds) {
         return automatic ? automaticSelection() : manual(maskFromProviderIds(providerIds));
      }

      public static EarthGeneratorSettings.DemSelection fromLegacyProvider(EarthGeneratorSettings.DemProvider legacyProvider) {
         EarthGeneratorSettings.DemProvider provider = Objects.requireNonNullElse(legacyProvider, EarthGeneratorSettings.DemProvider.AUTO);
         return switch (provider) {
            case AUTO -> automaticSelection();
            case TERRARIUM -> manual(List.of(EarthGeneratorSettings.DemProvider.TERRARIUM));
            case COPERNICUS -> manual(List.of(EarthGeneratorSettings.DemProvider.COPERNICUS));
            case USGS -> manual(List.of(EarthGeneratorSettings.DemProvider.USGS, EarthGeneratorSettings.DemProvider.COPERNICUS));
            default -> manual(List.of(provider, EarthGeneratorSettings.DemProvider.TERRARIUM));
         };
      }

      public boolean isEnabled(EarthGeneratorSettings.DemProvider provider) {
         return provider != null && provider.userSelectable() && (this.enabledProviderMask & provider.selectionBit()) != 0;
      }

      public boolean usesPolarDem() {
         return this.isEnabled(EarthGeneratorSettings.DemProvider.ARCTICDEM);
      }

      public boolean terrainTilesEnabled() {
         return this.isEnabled(EarthGeneratorSettings.DemProvider.TERRARIUM);
      }

      public boolean copernicusEnabled() {
         return this.isEnabled(EarthGeneratorSettings.DemProvider.COPERNICUS);
      }

      public boolean isAllEnabled() {
         return this.enabledProviderMask == FULL_USER_SELECTABLE_MASK;
      }

      public String fingerprint() {
         return "mask_" + Integer.toHexString(this.enabledProviderMask);
      }

      public List<EarthGeneratorSettings.DemProvider> enabledProvidersInUiOrder() {
         List<EarthGeneratorSettings.DemProvider> providers = new ArrayList<>(USER_SELECTABLE_PROVIDERS.size());

         for (EarthGeneratorSettings.DemProvider provider : USER_SELECTABLE_PROVIDERS) {
            if (this.isEnabled(provider)) {
               providers.add(provider);
            }
         }

         return List.copyOf(providers);
      }

      public List<String> enabledProviderIds() {
         return this.isAllEnabled() ? FULL_PROVIDER_IDS : this.enabledProvidersInUiOrder().stream().map(EarthGeneratorSettings.DemProvider::id).toList();
      }

      public static List<EarthGeneratorSettings.DemProvider> userSelectableProviders() {
         return USER_SELECTABLE_PROVIDERS;
      }

      public static int fullUserSelectableMask() {
         return FULL_USER_SELECTABLE_MASK;
      }

      public static int maskFromProviders(Iterable<EarthGeneratorSettings.DemProvider> providers) {
         int mask = 0;
         if (providers == null) {
            return mask;
         }

         for (EarthGeneratorSettings.DemProvider provider : providers) {
            if (provider != null && provider.userSelectable()) {
               mask |= provider.selectionBit();
            }
         }

         return mask;
      }

      public static int maskFromProviderIds(List<String> providerIds) {
         int mask = 0;
         if (providerIds == null) {
            return mask;
         }

         for (String providerId : providerIds) {
            EarthGeneratorSettings.DemProvider provider = EarthGeneratorSettings.DemProvider.fromId(providerId);
            if (provider.userSelectable()) {
               mask |= provider.selectionBit();
            }
         }

         return mask;
      }

      private static int normalizeManualMask(int enabledProviderMask) {
         int normalized = 0;

         for (EarthGeneratorSettings.DemProvider provider : USER_SELECTABLE_PROVIDERS) {
            if ((enabledProviderMask & provider.selectionBit()) != 0) {
               normalized |= provider.selectionBit();
            }
         }

         if ((normalized & GLOBAL_COVERAGE_MASK) == 0) {
            normalized |= EarthGeneratorSettings.DemProvider.TERRARIUM.selectionBit();
         }

         return normalized;
      }

      private static int computeFullUserSelectableMask() {
         int mask = 0;

         for (EarthGeneratorSettings.DemProvider provider : USER_SELECTABLE_PROVIDERS) {
            mask |= provider.selectionBit();
         }

         return mask;
      }
   }

   public static enum DistantHorizonsRenderMode {
      FAST("fast"),
      ULTRA_FAST("ultra_fast"),
      DETAILED("detailed");

      public static final Codec<EarthGeneratorSettings.DistantHorizonsRenderMode> CODEC = Codec.STRING
         .xmap(EarthGeneratorSettings.DistantHorizonsRenderMode::fromId, EarthGeneratorSettings.DistantHorizonsRenderMode::id);
      private final String id;

      private DistantHorizonsRenderMode(String id) {
         this.id = Objects.requireNonNull(id, "id");
      }

      public String id() {
         return this.id;
      }

      public static EarthGeneratorSettings.DistantHorizonsRenderMode fromId(String id) {
         if (id == null) {
            return FAST;
         } else {
            for (EarthGeneratorSettings.DistantHorizonsRenderMode mode : values()) {
               if (mode.id.equalsIgnoreCase(id)) {
                  return mode;
               }
            }

            return FAST;
         }
      }
   }

   public record HeightLimits(int minY, int height, int logicalHeight) {
      public static EarthGeneratorSettings.HeightLimits maxRange() {
         return new EarthGeneratorSettings.HeightLimits(MIN_WORLD_Y, MAX_WORLD_HEIGHT, MAX_WORLD_HEIGHT);
      }
   }

   private record SettingsBase(
      double worldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      int heightOffset,
      int seaLevel,
      double spawnLatitude,
      double spawnLongitude,
      int minAltitude,
      int maxAltitude,
      int riverLakeShorelineBlend,
      int oceanShorelineBlend,
      boolean shorelineBlendCliffLimit,
      boolean caveGeneration,
      boolean oreDistribution,
      boolean lavaPools,
      boolean distantHorizonsWaterResolver,
      boolean distantHorizonsOsmFeatures,
      boolean realtimeTime,
      boolean realtimeWeather,
      boolean historicalSnow,
      boolean voxyChunkPregenEnabled,
      int voxyChunkPregenMaxRadius,
      int voxyChunkPregenChunksPerTick,
      EarthGeneratorSettings.DistantHorizonsRenderMode distantHorizonsRenderMode,
      EarthGeneratorSettings.DemSelection demSelection
   ) {
      private static EarthGeneratorSettings.SettingsBase fromSettings(EarthGeneratorSettings settings) {
         return new EarthGeneratorSettings.SettingsBase(
            settings.worldScale(),
            settings.terrestrialHeightScale(),
            settings.oceanicHeightScale(),
            settings.heightOffset(),
            settings.seaLevel(),
            settings.spawnLatitude(),
            settings.spawnLongitude(),
            settings.minAltitude(),
            settings.maxAltitude(),
            settings.riverLakeShorelineBlend(),
            settings.oceanShorelineBlend(),
            settings.shorelineBlendCliffLimit(),
            settings.caveGeneration(),
            settings.oreDistribution(),
            settings.lavaPools(),
            settings.distantHorizonsWaterResolver(),
            settings.distantHorizonsOsmFeatures(),
            settings.realtimeTime(),
            settings.realtimeWeather(),
            settings.historicalSnow(),
            settings.voxyChunkPregenEnabled(),
            settings.voxyChunkPregenMaxRadius(),
            settings.voxyChunkPregenChunksPerTick(),
            settings.distantHorizonsRenderMode(),
            settings.demSelection()
         );
      }

      private EarthGeneratorSettings.SettingsBase withSeaLevel(int seaLevel) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withDistantHorizonsWaterResolver(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            enabled,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withDistantHorizonsOsmFeatures(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            enabled,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withRealtimeTime(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            enabled,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withRealtimeWeather(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            enabled,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withHistoricalSnow(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            enabled,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withVoxyChunkPregenEnabled(boolean enabled) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            enabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withVoxyChunkPregenMaxRadius(int maxRadius) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            maxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withVoxyChunkPregenChunksPerTick(int chunksPerTick) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            chunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withDistantHorizonsRenderMode(EarthGeneratorSettings.DistantHorizonsRenderMode renderMode) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            renderMode,
            this.demSelection
         );
      }

      private EarthGeneratorSettings.SettingsBase withDemSelection(EarthGeneratorSettings.DemSelection demSelection) {
         return new EarthGeneratorSettings.SettingsBase(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            Objects.requireNonNull(demSelection, "demSelection")
         );
      }

      private EarthGeneratorSettings toSettings() {
         return new EarthGeneratorSettings(
            this.worldScale,
            this.terrestrialHeightScale,
            this.oceanicHeightScale,
            this.heightOffset,
            this.seaLevel,
            this.spawnLatitude,
            this.spawnLongitude,
            this.minAltitude,
            this.maxAltitude,
            this.riverLakeShorelineBlend,
            this.oceanShorelineBlend,
            this.shorelineBlendCliffLimit,
            this.caveGeneration,
            this.oreDistribution,
            this.lavaPools,
            EarthGeneratorSettings.DEFAULT.addStrongholds(),
            EarthGeneratorSettings.DEFAULT.addVillages(),
            EarthGeneratorSettings.DEFAULT.addMineshafts(),
            EarthGeneratorSettings.DEFAULT.addOceanMonuments(),
            EarthGeneratorSettings.DEFAULT.addWoodlandMansions(),
            EarthGeneratorSettings.DEFAULT.addDesertTemples(),
            EarthGeneratorSettings.DEFAULT.addJungleTemples(),
            EarthGeneratorSettings.DEFAULT.addPillagerOutposts(),
            EarthGeneratorSettings.DEFAULT.addRuinedPortals(),
            EarthGeneratorSettings.DEFAULT.addShipwrecks(),
            EarthGeneratorSettings.DEFAULT.addOceanRuins(),
            EarthGeneratorSettings.DEFAULT.addBuriedTreasure(),
            EarthGeneratorSettings.DEFAULT.addIgloos(),
            EarthGeneratorSettings.DEFAULT.addWitchHuts(),
            EarthGeneratorSettings.DEFAULT.addAncientCities(),
            EarthGeneratorSettings.DEFAULT.addTrialChambers(),
            EarthGeneratorSettings.DEFAULT.addTrailRuins(),
            EarthGeneratorSettings.DEFAULT.deepDark(),
            EarthGeneratorSettings.DEFAULT.geodes(),
            this.distantHorizonsWaterResolver,
            this.distantHorizonsOsmFeatures,
            EarthGeneratorSettings.DEFAULT.distantHorizonsOsmRoadMaxDetail(),
            EarthGeneratorSettings.DEFAULT.distantHorizonsOsmBuildingMaxDetail(),
            EarthGeneratorSettings.DEFAULT.distantHorizonsOsmNonBlockingFetch(),
            this.realtimeTime,
            this.realtimeWeather,
            this.historicalSnow,
            this.voxyChunkPregenEnabled,
            this.voxyChunkPregenMaxRadius,
            this.voxyChunkPregenChunksPerTick,
            this.distantHorizonsRenderMode,
            this.demSelection,
            EarthGeneratorSettings.DEFAULT.enableRoads(),
            EarthGeneratorSettings.DEFAULT.enableBuildings(),
            EarthGeneratorSettings.DEFAULT.enableWater()
         );
      }
   }

   private record StructureSettings(
      boolean addStrongholds,
      boolean addVillages,
      boolean addMineshafts,
      boolean addOceanMonuments,
      boolean addWoodlandMansions,
      boolean addDesertTemples,
      boolean addJungleTemples,
      boolean addPillagerOutposts,
      boolean addRuinedPortals,
      boolean addShipwrecks,
      boolean addOceanRuins,
      boolean addBuriedTreasure,
      boolean addIgloos,
      boolean addWitchHuts,
      boolean addAncientCities,
      boolean addTrialChambers
   ) {
      private static EarthGeneratorSettings.StructureSettings fromSettings(EarthGeneratorSettings settings) {
         return new EarthGeneratorSettings.StructureSettings(
            settings.addStrongholds(),
            settings.addVillages(),
            settings.addMineshafts(),
            settings.addOceanMonuments(),
            settings.addWoodlandMansions(),
            settings.addDesertTemples(),
            settings.addJungleTemples(),
            settings.addPillagerOutposts(),
            settings.addRuinedPortals(),
            settings.addShipwrecks(),
            settings.addOceanRuins(),
            settings.addBuriedTreasure(),
            settings.addIgloos(),
            settings.addWitchHuts(),
            settings.addAncientCities(),
            settings.addTrialChambers()
         );
      }
   }
}
