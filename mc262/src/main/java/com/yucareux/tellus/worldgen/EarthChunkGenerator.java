package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.BridgeSupportLayout;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmSandSource;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingLighting;
import com.yucareux.tellus.worldgen.building.TellusBuildingMaterials;
import com.yucareux.tellus.worldgen.building.BuildingPlacementSupport;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
import com.yucareux.tellus.worldgen.caves.TellusNoiseSettingsAdapter;
import com.yucareux.tellus.worldgen.caves.TellusVanillaCarverRunner;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeGenerationSettings.PlainBuilder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.core.QuartPos;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.tags.TagKey;
import net.minecraft.core.Holder;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.HolderSet;
import net.minecraft.core.HolderSet.Named;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.core.registries.Registries;
import com.yucareux.tellus.worldgen.EarthProjection;

public final class EarthChunkGenerator extends ChunkGenerator {
   public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
            EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::settings)
         )
         .apply(instance, EarthChunkGenerator::new)
   );
   private static final TellusElevationSource ELEVATION_SOURCE = TellusWorldgenSources.elevation();
   private static final TellusLandCoverSource LAND_COVER_SOURCE = TellusWorldgenSources.landCover();
   private static final TellusKoppenSource KOPPEN_SOURCE = TellusWorldgenSources.koppen();
   private static final TellusLandMaskSource LAND_MASK_SOURCE = TellusWorldgenSources.landMask();
   private static final TellusOsmRoadSource OSM_ROAD_SOURCE = TellusWorldgenSources.osmRoads();
   private static final TellusOsmBuildingSource OSM_BUILDING_SOURCE = TellusWorldgenSources.osmBuildings();
   private static final TellusOsmSandSource OSM_SAND_SOURCE = TellusWorldgenSources.osmSand();
   private static final double ESA_WORLD_COVER_RESOLUTION_METERS = 10.0;
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_BUILT_UP = 50;
   private static final int ESA_WATER = 80;
   private static final int ESA_MANGROVES = 95;
   private static final int SPAGHETTI_WATER_GUARD_DEPTH = 4;
   private static final double OCEAN_CHUNK_CARVER_GUARD_RATIO = 0.7;
   private static final int OCEAN_CARVER_FLOOR_BUFFER = 3;
   private static final float AXOLOTL_CHUNK_CHANCE = 0.55F;
   private static final float AXOLOTL_POND_CHANCE = 0.68F;
   private static final int MAX_AXOLOTLS_PER_CHUNK = 2;
   private static final int OSM_ROAD_MAX_SCALE = 15;
   private static final int OSM_ROAD_QUERY_MARGIN = 64;
   private static final int OSM_BUILDING_MAX_SCALE = 15;
   private static final int OSM_BUILDING_QUERY_MARGIN = 8;
   private static final int OSM_ROAD_CLASS_SEPARATION = 0;
   private static final int OSM_ROAD_BRIDGE_LEVEL_HEIGHT = intProperty("tellus.osm.roads.bridgeLevelHeight", 3, 1, 16);
   private static final int OSM_ROAD_BRIDGE_MAX_RISE = intProperty("tellus.osm.roads.bridgeMaxRise", 10, 1, 64);
   private static final int OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL = intProperty("tellus.osm.roads.bridgeRampHorizontalPerVertical", 4, 1, 32);
   private static final int OSM_TUNNEL_SIDE_CLEARANCE = 3;
   private static final int OSM_TUNNEL_INTERNAL_HEIGHT = 7;
   private static final int OCEAN_MONUMENT_SAMPLE_STEP = 8;
   private static final int OCEAN_MONUMENT_MARGIN = 8;
   private static final int OCEAN_MONUMENT_CORE_INSET = 8;
   private static final int OCEAN_MONUMENT_MIN_DEEP_OCEAN_DEPTH = 12;
   private static final double OCEAN_MONUMENT_MIN_OCEAN_RATIO = 0.85;
   private static final double OCEAN_MONUMENT_MIN_DEEP_OCEAN_RATIO = 0.7;
   private static final int OCEAN_MONUMENT_TOP_BELOW_SEA = 2;
   private static final double ROAD_LIGHT_BASE_SPACING_METERS = 40.0;
   private static final BlockState ROAD_MAIN_STATE = Blocks.CONCRETE.gray().defaultBlockState();

   private static final BlockState ROAD_NORMAL_STATE = Blocks.DYED_TERRACOTTA.cyan().defaultBlockState();

   private static final BlockState ROAD_DIRT_STATE = Blocks.DIRT_PATH.defaultBlockState();
   private static final BlockState BRIDGE_SUPPORT_SHAFT_STATE = Blocks.QUARTZ_PILLAR.defaultBlockState();
   private static final BlockState BRIDGE_SUPPORT_CAP_STATE = Blocks.QUARTZ_BRICKS.defaultBlockState();
   private static final BlockState ROAD_LIGHT_BASE_STATE = Blocks.STONE_BRICK_WALL.defaultBlockState();
   private static final BlockState ROAD_LIGHT_FENCE_STATE = Blocks.OAK_FENCE.defaultBlockState();
   private static final BlockState ROAD_LIGHT_GLOW_STATE = Blocks.GLOWSTONE.defaultBlockState();
   private static final BlockState BUILDING_BOOKSHELF_STATE = Blocks.BOOKSHELF.defaultBlockState();
   private static final BlockState BUILDING_BARREL_STATE = Blocks.BARREL.defaultBlockState();
   private static final BlockState BUILDING_CRAFTING_STATE = Blocks.CRAFTING_TABLE.defaultBlockState();
   private static final BlockState BUILDING_FURNACE_STATE = Blocks.FURNACE.defaultBlockState();
   private static final BlockState BUILDING_CAULDRON_STATE = Blocks.CAULDRON.defaultBlockState();
   private static final BlockState BUILDING_WHITE_WOOL_STATE = Blocks.WOOL.white().defaultBlockState();
   private static final BlockState ROAD_LIGHT_TRAPDOOR_BASE_STATE = Objects.requireNonNull(
      Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, Boolean.FALSE).setValue(BlockStateProperties.HALF, Half.BOTTOM),
      "roadLightTrapdoorBaseState"
   );
   private static final int BUILDING_SLICE_PADDING = 6;
   private static final boolean FULL_CHUNK_OSM_NON_BLOCKING = Boolean.parseBoolean(System.getProperty("tellus.osm.fullChunksNonBlocking", "false"));
   private static final boolean FAST_FULL_CHUNK = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.fastFullChunk", "true"));
   private static final boolean NON_BLOCKING_TERRAIN_INPUTS = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.nonBlockingTerrainInputs", "false"));
   private static final boolean MEMORY_ONLY_TERRAIN_CRITICAL_PATH = Boolean.parseBoolean(
      System.getProperty("tellus.chunkgen.memoryOnlyTerrainCriticalPath", "false")
   );
   private static final boolean DEFER_TERRAIN_REFINEMENT = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.deferTerrainRefinement", "false"));
   /**
    * 2026-09-26: whether to escalate step by step when the shell height is unavailable (full DEM chain read-only from disk -> network allowed)
    *
    * <p>Defaults to <b>on</b>. Benefit: no more "whole chunk concave / whole chunk convex" blocky terrain (see {@link #escalateShellHeight}).
    * Cost: in rare cases the critical path waits for one extra real local read / network download (bounded by the existing download deadline + circuit breaker).
    *
    * <p>Turn it off: {@code -Dtellus.chunkgen.shellDataEscalation=false} (back to "memory only, invent a height when data is missing").
    */
   private static final boolean SHELL_DATA_ESCALATION = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.shellDataEscalation", "true"));
   /**
    * 2026-09-26: surface self-audit (find out who really changed the height behind the blocky bumps / dents)
    *
    * <p>Defaults to on. Only logs WARN (and only when there really is a deviation); the cost is 256 integer comparisons per chunk, which is negligible.
    * Turn it off: {@code -Dtellus.chunkgen.surfaceAudit=false}
    */
   private static final boolean SURFACE_AUDIT_ENABLED = Boolean.parseBoolean(System.getProperty("tellus.chunkgen.surfaceAudit", "true"));
   /** Allowed per-column deviation between the surface and the height grid (in blocks). Terrain slope is far below it, while real blocky bumps / dents run to tens of blocks. */
   private static final int SURFACE_AUDIT_COLUMN_TOLERANCE = intProperty("tellus.chunkgen.surfaceAuditTolerance", 12, 2, 256);
   /**
    * How many times a single chunk may be reported, so that one bad chunk cannot spam the log.
    *
    * <p>A **bounded** LRU: this table is only written when an **anomaly is detected**,
    * but the world keeps generating new chunks, so an unbounded map would keep growing (a memory leak over a long play session).
    * only the most recent {@value #SURFACE_AUDIT_SEEN_MAX} bad chunks are kept, which is enough to deduplicate the log.
    */
   private static final int SURFACE_AUDIT_SEEN_MAX = 256;
   private static final Map<Long, Long> SURFACE_AUDIT_SEEN = Collections.synchronizedMap(
      new LinkedHashMap<Long, Long>(64, 0.75F, true) {
         @Override
         protected boolean removeEldestEntry(Entry<Long, Long> eldest) {
            return size() > SURFACE_AUDIT_SEEN_MAX;
         }
      }
   );
   private static final boolean BLOCKING_TERRAIN_INPUT_WARMUP = Boolean.parseBoolean(System.getProperty("tellus.prefetch.blockingWarmup", "false"));
   private static final EarthChunkGenerator.SurfaceMode FULL_CHUNK_SURFACE_MODE = surfaceModeProperty(
      "tellus.chunkgen.surfaceMode", EarthChunkGenerator.SurfaceMode.TWO_TIER
   );
   private static final int HEIGHT_GRID_CACHE_ENTRIES = intProperty("tellus.chunkgen.heightGrid.cacheEntries", 512, 0, 8192);
   // Per-column surface guard for caves (item B)
   // Old behaviour: the guard was set from sea level / ocean floor only -> at 1:1 scale city buildings (shaft exactly inside the cave band) got carved apart
   // New behaviour: additionally "protect y >= per-column surface height" -> only carve below the surface, buildings stay safe, mountains still get caves
   private static final boolean CAVES_FOLLOW_TERRAIN = Boolean.parseBoolean(
      System.getProperty("tellus.caves.followTerrain", "true")
   );
   private static final int CAVE_SURFACE_CACHE_ENTRIES = intProperty("tellus.caves.surfaceCacheEntries", 2048, 0, 65536);
   private static final AtomicBoolean CAVE_SURFACE_GUARD_LOGGED = new AtomicBoolean();
   private static final boolean CHUNK_DETAIL_DEFER_ROADS = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferRoads", "false"));
   private static final boolean CHUNK_DETAIL_DEFER_BUILDINGS = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferBuildings", "false"));
   private static final boolean CHUNK_DETAIL_DEFER_DETAILED_WATER = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferDetailedWater", "false"));
   private static final boolean CHUNK_DETAIL_DEFER_TREES = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.deferTrees", "false"));
   // Rollback notes (important)
   //
   // The flag group below has **all been rolled back to the author's original defaults**.
   // Reason (measured): with them turned on, the **spawn area of a new world is not generated at all**,
   //   [11:29:10] Selecting global world spawn...
   // [11:33:23] Loading 0 chunks for player spawn...    0 chunks
   // [11:33:55] Time elapsed: 285482 ms                 waited 285 s for nothing
   // while the world settings say scale=2.05 (Everest should sit at Y about 4380) -> yet the spawn heights were only in the "tens"
   // => height sampling got **no data at all**  -- because
   // `memoryOnlyTerrainCriticalPath=true` makes the **critical path use only the in-memory DEM**,
   // while the DEM tile at the spawn has not been downloaded yet -> heights evaluate to 0 -> chunks cannot be generated.
   // Adding `deferTerrainRefinement=true` moves refinement out of the critical path -> and then nothing fills it in.
   // => The author defaults these to off: **this is not a missing optimization, these paths are simply unfinished**
   // (Corroborating evidence: the deferred pipeline has an unbounded growth bug in readyQueue/jobs, see section 58).
   // Only optimizations that are **semantically identical** are kept (mask cap, property lookup cache, stale job reaping).
   //
   // The original "deferred / non-blocking pipeline" master switch (rolled back; these notes are kept so that it can be re-validated later under suitable conditions)
   //
   // The predicate for this group of flags is uniformly `!LEGACY_BLOCKING && DEFER_XXX`:
   // legacyBlocking=true (the original default) -> short-circuits **every** defer* to false
   // -> the author's deferred / non-blocking paths **stop working entirely**, and OSM / roads / buildings / trees
   // / terrain refinement all end up crammed onto the **critical path of chunk generation**.
   // Measured symptoms (world height 65600): in the log
   // * `Preparing spawn chunks: 16%` sits there for a long time
   // * `Time elapsed: 130561 ms` (130 s for the spawn area)
   //   * `Can't keep up! Running 11296ms or 225 ticks behind`
   //   * `Total time to load game and open world was 234.9 seconds`
   // So they are **switched on by default** here: the work still happens, it is just **moved off the
   // critical path** and backfilled later (`Tellus.processDeferredChunkDetailTick` finalizes it every tick).
   // Any single item can be switched back off with the system property of the same name, for example
   // `-Dtellus.chunkdetail.legacyBlocking=true` to restore the old behaviour.
   private static final boolean CHUNK_DETAIL_LEGACY_BLOCKING = Boolean.parseBoolean(System.getProperty("tellus.chunkdetail.legacyBlocking", "true"));
   private static final int CHUNK_DETAIL_PREFETCH_RADIUS = intProperty("tellus.chunkdetail.prefetchRadius", 2, 0, 32);
   private static final int CHUNK_DETAIL_APPLY_BUDGET_PER_TICK = intProperty("tellus.chunkdetail.applyBudgetPerTick", 2, 0, 64);
   private static final int PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS = intProperty(
      "tellus.chunkgen.preparedChunkStateReapIntervalTicks", 200, 20, 72000
   );
   private static final int PREPARED_CHUNK_STATE_STALE_TICKS = intProperty(
      "tellus.chunkgen.preparedChunkStateStaleTicks", 6000, PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS, 144000
   );
   private static final long PREPARED_CHUNK_STATE_STALE_NANOS = TimeUnit.MILLISECONDS.toNanos((long)PREPARED_CHUNK_STATE_STALE_TICKS * 50L);
   private static final int TERRAIN_REFINEMENT_APPLY_BUDGET_PER_TICK = intProperty(
      "tellus.chunkgen.terrainRefinementApplyBudgetPerTick", 4, 0, 64
   );
   /**
    * 2026-09-27: per-tick wall-clock budget (in milliseconds) for deferred detail / terrain refinement
    *
    * <p>Why the throttle must be by **time** and not only by **chunk count**: measured on a real machine
    * {@code detailApply(total=13576ms, calls=1)} -- <b>a single chunk takes 13.6 seconds</b>.
    * the old "at most N chunks per tick" cap means "an N x 13.6 s freeze on every tick" when chunks are like that,
    * (the measured budget was 2, so the worst case is 27 s), the client looks completely dead, and a dedicated server gets killed by the watchdog.
    *
    * <p>Current behaviour: the chunk in flight **always runs to completion** (<b>it is never interrupted</b> -- interrupting
    * leaves a half-populated chunk), but a **second chunk does not start in the same tick**, so a single hitch is bounded by the cost of one chunk.
    *
    * <p>Tuning: {@code -Dtellus.chunkdetail.applyBudgetMs=N} /
    * {@code -Dtellus.chunkgen.terrainRefinementApplyBudgetMs=N} (0 = no time limit, back to the old behaviour).
    */
   private static final long CHUNK_DETAIL_APPLY_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(
      intProperty("tellus.chunkdetail.applyBudgetMs", 12, 0, 10_000)
   );
   private static final long TERRAIN_REFINEMENT_APPLY_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(
      intProperty("tellus.chunkgen.terrainRefinementApplyBudgetMs", 12, 0, 10_000)
   );
   private static final int SPAWN_EXACT_CHUNK_RADIUS = intProperty("tellus.chunkgen.spawnExactChunkRadius", 2, 0, 16);
   private static final int MOVEMENT_PREFETCH_FORWARD_EXTRA = intProperty("tellus.prefetch.movement.forwardExtra", 2, 0, 32);
   private static final int MOVEMENT_PREFETCH_SIDE_EXTRA = intProperty("tellus.prefetch.movement.sideExtra", 1, 0, 32);
   private static final int MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS = intProperty("tellus.prefetch.movement.teleportBurstRadius", 5, 0, 64);
   private static final int MOVEMENT_PREFETCH_TELEPORT_THRESHOLD_CHUNKS = intProperty("tellus.prefetch.movement.teleportThresholdChunks", 3, 1, 64);
   private static final int MOVEMENT_PREFETCH_DIRECTION_CHANGE_MIN_DEGREES = intProperty("tellus.prefetch.movement.directionChangeDegrees", 60, 1, 180);
   private static final int MOVEMENT_PREFETCH_DIRECTION_CHANGE_COOLDOWN_TICKS = intProperty(
      "tellus.prefetch.movement.directionChangeCooldownTicks", 8, 0, 200
   );
   private static final int MOVEMENT_PREFETCH_EDGE_TRIGGER_DISTANCE_BLOCKS = intProperty(
      "tellus.prefetch.movement.edgeTriggerDistanceBlocks", 6, 0, 16
   );
   private static final int MOVEMENT_PREFETCH_MIN_SPEED_MILLIBLOCKS_PER_TICK = intProperty(
      "tellus.prefetch.movement.minSpeedMilliBlocksPerTick", 50, 0, 5000
   );
   private static final double MOVEMENT_PREFETCH_DIRECTION_CHANGE_MAX_DOT = Math.cos(
      Math.toRadians(MOVEMENT_PREFETCH_DIRECTION_CHANGE_MIN_DEGREES)
   );
   private static final double MOVEMENT_PREFETCH_MIN_SPEED_BLOCKS_PER_TICK = MOVEMENT_PREFETCH_MIN_SPEED_MILLIBLOCKS_PER_TICK / 1000.0;
   private static final double MOVEMENT_PREFETCH_MIN_SPEED_SQ =
      MOVEMENT_PREFETCH_MIN_SPEED_BLOCKS_PER_TICK * MOVEMENT_PREFETCH_MIN_SPEED_BLOCKS_PER_TICK;
   private static final int LOD_SHORELINE_DENSE_CACHE_MAX_AREA = intProperty("tellus.dhLodShorelineDenseCacheMaxArea", 100000, 4096, 1048576);
   private static final int LOD_MOUNTAIN_TRANSITION_DENSE_CACHE_MAX_AREA = intProperty(
      "tellus.dhLodMountainTransitionDenseCacheMaxArea", 131072, 4096, 1048576
   );
   private static final BlockState[] BADLANDS_BANDS = new BlockState[]{
      Blocks.TERRACOTTA.defaultBlockState(),
      Blocks.DYED_TERRACOTTA.orange().defaultBlockState(),
      Blocks.DYED_TERRACOTTA.yellow().defaultBlockState(),
      Blocks.DYED_TERRACOTTA.brown().defaultBlockState(),
      Blocks.DYED_TERRACOTTA.red().defaultBlockState(),
      Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState(),
      Blocks.DYED_TERRACOTTA.white().defaultBlockState()
   };
   private static final int CHUNK_SIDE = 16;
   private static final int CHUNK_MASK = 15;
   private static final int CHUNK_AREA = 256;
   private static final int TREE_MAX_SURFACE_DROP = 2;
   private static final int TREE_MAX_SURFACE_RISE = 3;
   private static final int LOD_MIN_WATER_DEPTH = 25;
   private static final int OCEAN_SHORE_MAX_DISTANCE = 8;
   private static final int INLAND_SHORE_MAX_DISTANCE = 4;
   private static final int FULL_CHUNK_OCEAN_BEACH_MAX_DISTANCE = intProperty("tellus.chunkgen.oceanBeachMaxDistance", 4, 0, OCEAN_SHORE_MAX_DISTANCE);
   private static final int SHORELINE_OUTER_NOISE_BAND = 2;
   private static final int SHALLOW_SHORE_WATER_DEPTH = 3;
   private static final int SHALLOW_SHORE_WATER_DISTANCE = 2;

   private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();

   private static final BlockState STONE_STATE = Blocks.STONE.defaultBlockState();

   private static final BlockState DEEPSLATE_STATE = Blocks.DEEPSLATE.defaultBlockState();
   /**
    * 2026-10-05: thickness of the deepslate band at the bottom of the world.
    *
    * <p>Vanilla's deepslate occupies the lowest 64 blocks (world floor -64 up to Y=-1). The band is
    * anchored to {@code minY} so it stays 64 blocks thick in a 67,568-block-tall world instead of
    * swallowing the whole thing.
    */
   private static final int DEEPSLATE_BAND_BLOCKS = 64;

   private static final BlockState WATER_STATE = Blocks.WATER.defaultBlockState();

   private static final BlockState BEDROCK_STATE = Blocks.BEDROCK.defaultBlockState();

   private static final BlockState CAVE_AIR_STATE = Blocks.CAVE_AIR.defaultBlockState();

   private static final BlockState DIRT_STATE = Blocks.DIRT.defaultBlockState();

   private static final BlockState SAND_STATE = Blocks.SAND.defaultBlockState();

   private static final BlockState RED_SAND_STATE = Blocks.RED_SAND.defaultBlockState();

   private static final BlockState SANDSTONE_STATE = Blocks.SANDSTONE.defaultBlockState();

   private static final BlockState TERRACOTTA_STATE = Blocks.TERRACOTTA.defaultBlockState();

   private static final BlockState GRASS_BLOCK_STATE = Blocks.GRASS_BLOCK.defaultBlockState();

   private static final BlockState PODZOL_STATE = Blocks.PODZOL.defaultBlockState();

   private static final BlockState COARSE_DIRT_STATE = Blocks.COARSE_DIRT.defaultBlockState();

   private static final BlockState ROOTED_DIRT_STATE = Blocks.ROOTED_DIRT.defaultBlockState();

   private static final BlockState MUD_STATE = Blocks.MUD.defaultBlockState();

   private static final BlockState PACKED_MUD_STATE = Blocks.PACKED_MUD.defaultBlockState();

   private static final BlockState MOSS_BLOCK_STATE = Blocks.MOSS_BLOCK.defaultBlockState();

   private static final BlockState ANDESITE_STATE = Blocks.ANDESITE.defaultBlockState();

   private static final BlockState TUFF_STATE = Blocks.TUFF.defaultBlockState();

   private static final BlockState GRAVEL_STATE = Blocks.GRAVEL.defaultBlockState();

   private static final BlockState CLAY_STATE = Blocks.CLAY.defaultBlockState();

   private static final BlockState POWDER_SNOW_STATE = Blocks.POWDER_SNOW.defaultBlockState();

   private static final BlockState SNOW_BLOCK_STATE = Blocks.SNOW_BLOCK.defaultBlockState();

   private static final BlockState SNOW_LAYER_STATE = Objects.requireNonNull(Blocks.SNOW.defaultBlockState(), "snowLayerState");
   private static final AtomicBoolean LOGGED_CHUNK_LAYOUT = new AtomicBoolean(false);
   private static final Map<EarthChunkGenerator.BiomeSettingsKey, BiomeGenerationSettings> FILTERED_SETTINGS = new ConcurrentHashMap<>();
   private static final Map<Holder<Biome>, List<ConfiguredFeature<?, ?>>> TREE_FEATURES = new ConcurrentHashMap<>();
   private final EarthGeneratorSettings settings;
   private final int seaLevel;
   private final int minY;
   private final int height;
   private final WaterSurfaceResolver waterResolver;
   private volatile TellusVanillaCarverRunner tellusCarverRunner;
   private final ThreadLocal<EarthChunkGenerator.WaterChunkCache> waterChunkCache = ThreadLocal.withInitial(EarthChunkGenerator.WaterChunkCache::new);
   private final ThreadLocal<EarthChunkGenerator.OsmOverlayScratch> osmOverlayScratch = ThreadLocal.withInitial(EarthChunkGenerator.OsmOverlayScratch::new);
   private final ThreadLocal<EarthChunkGenerator.LodMountainTransitionCache> lodMountainTransitionCache = new ThreadLocal<>();

   /**
    * Per-thread memo for the mountain surface rules' neighbourhood probes.
    *
    * <h2>Why</h2>
    * The detailed surface path probes a ring of neighbours around every column -- three distances x four
    * directions -- and every probe ends in {@code sampleCoverClass} + {@code sampleVisualCoverClass}.
    * Measured on a 1:1 world:
    * <pre>
    *   surface=245.03ms/3  paletteResolve=243.82ms/3   <- detailed path
    *   surface=  9.66ms/3  paletteResolve=  9.31ms/3   <- approximate path, 25x cheaper
    *   fast path taken: 0 of 121 calls
    * </pre>
    * Adjacent columns share almost all of their 16/32/48-block rings, so the same (x, z) is asked for many
    * times per chunk and the answer cannot change while the chunk is being built.
    *
    * <h2>Scope</h2>
    * Thread-local and cleared around each chunk build, so a stale answer can never leak between chunks or
    * worlds. Capped, because a chunk touches only a few hundred distinct probe points.
    */
   private final ThreadLocal<EarthChunkGenerator.MountainProbeCache> mountainProbeCache =
      ThreadLocal.withInitial(EarthChunkGenerator.MountainProbeCache::new);
   private final ThreadLocal<Boolean> lodShorelineOverrideSuppressed = new ThreadLocal<>();
   private final Map<Long, EarthChunkGenerator.PreparedChunkBuildings> preparedChunkBuildings = new ConcurrentHashMap<>();
   private final Map<Long, EarthChunkGenerator.PreparedChunkRoadLights> preparedChunkRoadLights = new ConcurrentHashMap<>();
   private final Map<Long, EarthChunkGenerator.ChunkDecorationContext> chunkDecorationContexts = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, Long> preparedChunkStateTouchedAt = new ConcurrentHashMap<>();
   private final EarthChunkGenerator.HeightGridCache heightGridCache = new EarthChunkGenerator.HeightGridCache(HEIGHT_GRID_CACHE_ENTRIES);

   /** 2026-09-29: average height of the most recently **successful** chunk,
    * used as the fallback when a whole chunk yields no elevation at all
    * (sea level 64 used to be the fallback here, so the real 4150 of the Tibetan plateau was filled with 64, which produced a 4000-block vertical cliff) */
   private final java.util.concurrent.atomic.AtomicInteger recentShellHeight =
      new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
   /** Per-column surface height per chunk (256 ints, about 1 KB). The carving stage uses it to set its guards. Kept in a bounded LRU, and when it cannot be resolved the old behaviour is used. */
   private final Map<Long, int[]> caveSurfaceByChunk = CAVE_SURFACE_CACHE_ENTRIES <= 0
      ? null
      : Collections.synchronizedMap(new LinkedHashMap<Long, int[]>(256, 0.75F, true) {
           @Override
           protected boolean removeEldestEntry(Entry<Long, int[]> eldest) {
              return size() > CAVE_SURFACE_CACHE_ENTRIES;
           }
        });
   private final EarthChunkGenerator.ChunkDetailManager chunkDetailManager = new EarthChunkGenerator.ChunkDetailManager();
   private final EarthChunkGenerator.TerrainRefinementManager terrainRefinementManager = new EarthChunkGenerator.TerrainRefinementManager();
   private final int configuredSpawnChunkX;
   private final int configuredSpawnChunkZ;
   private final boolean remaSnowEnabled;
   private final double remaSnowBoundaryZ;
   private volatile long worldSeed = 0L;
   private static final long JAVA_RANDOM_MULTIPLIER = 25214903917L;
   private static final long JAVA_RANDOM_ADDEND = 11L;
   private static final long JAVA_RANDOM_MASK = 281474976710655L;
   private final AtomicBoolean fastSpawnMode = new AtomicBoolean(true);
   private final AtomicLong chunkDetailGenerationSequence = new AtomicLong();
   private final ConcurrentHashMap<Long, Long> terrainGenerationStamps = new ConcurrentHashMap<>();

   public EarthChunkGenerator(BiomeSource biomeSource, EarthGeneratorSettings settings) {
      super(biomeSource, biome -> generationSettingsForBiome(biome, settings));
      this.settings = settings;
      this.seaLevel = settings.resolveSeaLevel();
      EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
      this.minY = limits.minY();
      this.height = limits.height();
      this.waterResolver = TellusWorldgenSources.waterResolver(settings);
      this.remaSnowEnabled = TellusElevationSource.usesPolarDem(settings.demSelection()) && settings.worldScale() > 0.0;
      this.remaSnowBoundaryZ = this.remaSnowEnabled ? TellusElevationSource.remaBoundaryBlockZ(settings.worldScale()) : Double.POSITIVE_INFINITY;
      double blocksPerDegree = blocksPerDegree(settings.worldScale());
      int spawnBlockX = Mth.floor(settings.spawnLongitude() * blocksPerDegree);
      int spawnBlockZ = Mth.floor(EarthProjection.latToBlockZ(settings.spawnLatitude(), settings.worldScale()));
      this.configuredSpawnChunkX = Math.floorDiv(spawnBlockX, 16);
      this.configuredSpawnChunkZ = Math.floorDiv(spawnBlockZ, 16);
      if (biomeSource instanceof EarthBiomeSource earthBiomeSource) {
         earthBiomeSource.setFastSpawnMode(true);
      }
      // 2026-10-06: the material-pool self-check runs here, on the first generator construction,
      // which is the one point that is guaranteed to happen on both the client and the server and
      // after the block registry is populated. /tellus verify re-runs it on demand.
      com.yucareux.tellus.worldgen.building.TellusBuildingMaterialSelfCheck.verifyAtStartup();

      if (Tellus.LOGGER.isInfoEnabled()) {
         Tellus.LOGGER
            .info(
               "EarthChunkGenerator init: scale={}, minAltitude={}, maxAltitude={}, heightOffset={}, limits=[minY={}, height={}, logicalHeight={}], seaLevel={}",
               new Object[]{
                  settings.worldScale(),
                  settings.minAltitude(),
                  settings.maxAltitude(),
                  settings.heightOffset(),
                  limits.minY(),
                  limits.height(),
                  limits.logicalHeight(),
                  this.seaLevel
               }
            );
      }
   }

   public static EarthChunkGenerator create(Provider registries, EarthGeneratorSettings settings) {
      return new EarthChunkGenerator(new EarthBiomeSource(registries.lookupOrThrow(Registries.BIOME), settings), settings);
   }

   public EarthGeneratorSettings settings() {
      return this.settings;
   }


   public ChunkGeneratorStructureState createState( HolderLookup<StructureSet> structureSets,  RandomState randomState, long seed) {
      this.worldSeed = seed;
      HolderLookup<StructureSet> filtered = new EarthChunkGenerator.FilteredStructureLookup(structureSets, this::isStructureSetEnabled);
      return ChunkGeneratorStructureState.createForNormal(randomState, seed, this.biomeSource, filtered);
   }

   public BlockPos getSpawnPosition(LevelHeightAccessor heightAccessor) {
      return this.getSurfacePosition(heightAccessor, this.settings.spawnLatitude(), this.settings.spawnLongitude(), true);
   }

   public BlockPos getSurfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude) {
      return this.getSurfacePosition(heightAccessor, latitude, longitude, true);
   }

   private BlockPos getSurfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude, boolean useDetailedWaterResolver) {
      double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
      int spawnX = Mth.floor(longitude * blocksPerDegree);
      int spawnZ = Mth.floor(EarthProjection.latToBlockZ(latitude, this.settings.worldScale()));
      // 2026-10-01: the spawn must **not** fail entirely just because one land-cover tile is unavailable
      // Real incident (10-01 00:05 to 00:12, world folder "new world (41)", 0 MB, could not enter the world):
      // ESA WorldCover simply **has no tile** for some coordinates on the Tibetan plateau, so the server answers **404**
      // => `sampleCoverClass` throws IOException => the whole `getSpawnPosition` throws
      // => the world cannot be created and **not a single chunk is generated** (save file 0 MB)
      // and `coverClass` in this method is **only an input for the water resolver**, not one byte of it is used directly
      // => when it is unavailable the "no cover" default is enough, the spawn should be decided by **elevation**
      // Only this one place is patched; the `fillFromNoise` path has its own missing-data handling and is unaffected
      int coverClass = resolveSpawnCoverClassSafely(spawnX, spawnZ);
      int surface;
      if (useDetailedWaterResolver) {
         // 2026-10-10: **this call must not touch the network at all.**
         //
         //   The 2026-10-06 fix only made the single spawn-cell cover lookup local-only; the water resolver
         //   below then built a whole region around the spawn, and buildRegionData re-samples cover /
         //   elevation / land-mask for **every cell of that region** (up to ~1.18 million samples per the
         //   config notes). Each uncached cell blocks the server thread on the shared download pool.
         //
         //   Measured on 2026-10-10 21:03: that pool was already saturated by the auto-preload of a
         //   78 km x 83 km "all layers" region -- `submitted 1526, in-flight 1102, queued 1090` across
         //   only 12 threads. The spawn's own tile sat behind ~2200 queued tasks and blew its 90 s
         //   deadline, and the resulting IOException aborted world startup:
         //       IOException: Download did not complete within 90 seconds
         //         at TellusDownloadExecutor.await -> ... -> WaterSurfaceResolver.buildRegionData(:725)
         //         -> EarthChunkGenerator.getSpawnPosition(:532) -> Tellus.lambda$onInitialize$37(:459)
         //
         //   `resolveSpawnSafeColumnData` reads everything through the `*LocalOnly` variants and never
         //   builds a region, so the server thread cannot block here regardless of how busy the pool is.
         //   It produces exactly what the coarse fallback already produces when the region is not cached,
         //   and the spawn path only needs a Y to stand a player on.
         WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveSpawnSafeColumnData(spawnX, spawnZ);
         surface = column.terrainSurface();
         if (column.hasWater()) {
            surface = Math.max(surface, column.waterSurface());
         }
      } else {
         WaterSurfaceResolver.WaterColumnData column = this.resolveLodWaterColumn(spawnX, spawnZ, coverClass);
         surface = column.hasWater() ? Math.max(column.terrainSurface(), column.waterSurface()) : column.terrainSurface();
      }

      int maxY = heightAccessor.getMaxY() - 1;
      int spawnY = Mth.clamp(surface + 1, heightAccessor.getMinY(), maxY);
      return new BlockPos(spawnX, spawnY, spawnZ);
   }

   /**
    * 2026-10-01: spawn-specific "fall back to the default when cover is unavailable"
    *
    * <p>Returns the equivalent of {@link TellusLandCoverSource#NO_DATA_CLASS} (0 = no cover),
    * and **logs one rate-limited warn only on the first failure** -- silent afterwards so it cannot spam the log.
    */
   private static int resolveSpawnCoverClassSafely(int blockX, int blockZ) {
      // 2026-10-06: **this is the block that kills the server**, and it is not a data problem.
      //
      //   The real thread dump from the run at 15:58 reads:
      //       "Server thread" TIMED_WAITING on java.util.concurrent.FutureTask
      //         at TellusDownloadExecutor.await(TellusDownloadExecutor.java:253)
      //         at TellusLandCoverSource$1.load(TellusLandCoverSource.java:167)
      //         at LocalCache$Segment.loadSync
      //         at EarthChunkGenerator.getSpawnPosition(...:532)
      //         at Tellus.lambda$onInitialize$37(Tellus.java:459)   <- server thread
      //   and one tick later: "A single server tick took 60.00 seconds (should be max 0.05)".
      //
      //   `getSpawnPosition` is called **on the server thread**, so `LocalCache.get` synchronously
      //   loads the land-cover tile, and that loader goes through `TellusDownloadExecutor.await`,
      //   which blocks until the download finishes or its own 90 s deadline expires. The
      //   try/catch that was added on 2026-10-05 only catches the *exception afterwards*; the
      //   60 s of blocking has already happened by then, and the watchdog has already decided the
      //   server is hung.
      //
      //   So the catch cannot be the fix. What has to change is that this call must not block.
      //   The spawn only needs a cover class to feed the water resolver (see the note above: no
      //   byte of coverClass is used directly), and a missing cover class already has a
      //   well-defined default. So the spawn path asks for the **local-only** sample, which by
      //   construction never touches the network.
      try {
         return LAND_COVER_SOURCE.sampleCoverClassLocalOnly(
            blockX, blockZ, EarthGeneratorSettings.DEFAULT.worldScale(), EarthGeneratorSettings.DEFAULT.worldScale());
      } catch (RuntimeException error) {
         if (SPAWN_COVER_FAILURE_LOGGED.compareAndSet(false, true)) {
            Tellus.LOGGER.warn(
               "[TellusCN] Spawn land-cover lookup failed (block {}, {}); using default cover class instead of aborting world creation",
               blockX,
               blockZ,
               error
            );
         }

         return 0;
      }
   }

   private static final java.util.concurrent.atomic.AtomicBoolean SPAWN_COVER_FAILURE_LOGGED =
      new java.util.concurrent.atomic.AtomicBoolean();

   public double longitudeFromBlock(double blockX) {
      return EarthProjection.wrapLongitude(blockX / blocksPerDegree(this.settings.worldScale()));
   }

   public double latitudeFromBlock(double blockZ) {
      return EarthProjection.blockZToLat(blockZ, this.settings.worldScale());
   }

   private static double blocksPerDegree(double worldScale) {
      return EarthProjection.blocksPerDegree(worldScale);
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Mth.clamp(Integer.parseInt(value), minInclusive, maxInclusive);
         } catch (NumberFormatException var6) {
            Tellus.LOGGER.debug("System property {}='{}' is not a valid integer, using {}", new Object[]{key, value, defaultValue});
            return defaultValue;
         }
      }
   }

   private static EarthChunkGenerator.SurfaceMode surfaceModeProperty(String key, EarthChunkGenerator.SurfaceMode defaultValue) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }

      try {
         return EarthChunkGenerator.SurfaceMode.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException error) {
         Tellus.LOGGER.debug("System property {}='{}' is not a valid surface mode, using {}", new Object[]{key, value, defaultValue});
         return defaultValue;
      }
   }

   private boolean usesDeferredChunkDetails() {
      return this.shouldDeferRoadDetails() || this.shouldDeferBuildingDetails() || this.shouldDeferDetailedWater() || this.shouldDeferTrees();
   }

   private boolean usesDeferredTerrainRefinement() {
      return DEFER_TERRAIN_REFINEMENT && MEMORY_ONLY_TERRAIN_CRITICAL_PATH;
   }

   private boolean shouldForceExactSpawnTerrain(ChunkPos pos) {
      return Math.abs(pos.x() - this.configuredSpawnChunkX) <= SPAWN_EXACT_CHUNK_RADIUS
         && Math.abs(pos.z() - this.configuredSpawnChunkZ) <= SPAWN_EXACT_CHUNK_RADIUS;
   }

   private boolean hasDeferredApplyWork() {
      return this.shouldDeferRoadDetails() || this.shouldDeferBuildingDetails() || this.shouldDeferTrees();
   }

   private boolean hasPendingTerrainRefinement(ChunkPos pos) {
      return this.terrainRefinementManager.hasPending(pos);
   }

   public void discardPreparedChunkState(ChunkPos pos) {
      this.discardPreparedChunkState(ChunkPos.pack(pos.x(), pos.z()));
   }

   private void discardPreparedChunkState(long chunkKey) {
      this.preparedChunkBuildings.remove(chunkKey);
      this.preparedChunkRoadLights.remove(chunkKey);
      this.chunkDecorationContexts.remove(chunkKey);
      this.preparedChunkStateTouchedAt.remove(chunkKey);
   }

   private void markPreparedChunkState(long chunkKey) {
      this.preparedChunkStateTouchedAt.put(chunkKey, System.nanoTime());
   }

   private void clearPreparedChunkStateTracking(long chunkKey) {
      if (!this.preparedChunkBuildings.containsKey(chunkKey)
         && !this.preparedChunkRoadLights.containsKey(chunkKey)
         && !this.chunkDecorationContexts.containsKey(chunkKey)) {
         this.preparedChunkStateTouchedAt.remove(chunkKey);
      }
   }

   /**
    * Reaps the jobs in the two deferred pipelines that will "never come back" (see the call-site notes in {@link #reapStalePreparedChunkState}).
    *
    * <p>Criterion: a job that has been queued longer than {@code PREPARED_CHUNK_STATE_STALE_NANOS} (5 minutes by default,
    * configurable through `-Dtellus.chunkgen.preparedChunkStateStaleTicks`) and is still unfinished -> drop it.
    *
    * <p>Safety: what gets dropped is only "the detail of this chunk was not applied in time" --
    * the chunk's own data was finished long ago, and only the OSM roads / buildings / trees / detail water / terrain refinement layer is lost
    * (it was only ever a bonus). And this threshold is far larger than the normal apply latency (the budget is 2 per tick).
    */
   private void reapStaleDeferredJobs() {
      long now = EarthChunkGenerator.ChunkDetailPerf.now();
      int removedTerrain = this.terrainRefinementManager.reapStale(now);
      int removedDetail = this.chunkDetailManager.reapStale(now);
      if (removedTerrain + removedDetail > 0) {
         Tellus.LOGGER.warn(
            "[TellusCN] Reaped expired deferred tasks: terrain refinement {}, chunk detail {}"
               + " (the chunk was unloaded before detail apply, so the detail was dropped; already generated terrain is unaffected)",
            removedTerrain, removedDetail
         );
      }
   }

   private void reapStalePreparedChunkState(long gameTime) {
      if (PREPARED_CHUNK_STATE_STALE_NANOS <= 0L
         || PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS <= 0
         || gameTime % PREPARED_CHUNK_STATE_REAP_INTERVAL_TICKS != 0L) {
         return;
      }

      // Safety-net reaping of deferred jobs (added in this pass)
      // Why it is required: deferred detail / terrain refinement enqueue **one job per chunk**,
      // and a job leaves `jobs` **only when it completes** -> if the chunk is unloaded or discarded before that,
      // the entry stays forever, and a job stuck at QUEUED/FETCHING never even reaches readyQueue.
      // So after "flipping the defer* defaults", bulk generation such as Voxy preload makes `jobs` **grow without bound**,
      // because every ChunkDetailJob still holds a PreparedChunkDetail.
      // They are reaped here using the same time threshold.
      this.reapStaleDeferredJobs();

      long now = System.nanoTime();
      for (Entry<Long, Long> entry : this.preparedChunkStateTouchedAt.entrySet()) {
         long chunkKey = entry.getKey();
         long touchedAt = entry.getValue();
         if (now - touchedAt < PREPARED_CHUNK_STATE_STALE_NANOS) {
            continue;
         }

         if (this.preparedChunkStateTouchedAt.remove(chunkKey, touchedAt)) {
            this.preparedChunkBuildings.remove(chunkKey);
            this.preparedChunkRoadLights.remove(chunkKey);
            this.chunkDecorationContexts.remove(chunkKey);
         }
      }
   }

   private boolean shouldDeferRoadDetails() {
      double worldScale = this.settings.worldScale();
      return !CHUNK_DETAIL_LEGACY_BLOCKING && CHUNK_DETAIL_DEFER_ROADS && this.settings.enableRoads() && worldScale > 0.0 && worldScale <= OSM_ROAD_MAX_SCALE;
   }

   private boolean shouldDeferBuildingDetails() {
      double worldScale = this.settings.worldScale();
      return !CHUNK_DETAIL_LEGACY_BLOCKING
         && CHUNK_DETAIL_DEFER_BUILDINGS
         && this.settings.enableBuildings()
         && OSM_BUILDING_SOURCE.available()
         && worldScale > 0.0
         && worldScale <= OSM_BUILDING_MAX_SCALE;
   }

   private boolean shouldDeferDetailedWater() {
      return !CHUNK_DETAIL_LEGACY_BLOCKING && CHUNK_DETAIL_DEFER_DETAILED_WATER && this.settings.enableWater();
   }

   private boolean shouldDeferTrees() {
      return !CHUNK_DETAIL_LEGACY_BLOCKING && CHUNK_DETAIL_DEFER_TREES;
   }

   private boolean shouldUseStructureOsmSyncFallback() {
      return CHUNK_DETAIL_LEGACY_BLOCKING;
   }


   protected MapCodec<? extends ChunkGenerator> codec() {
      return Objects.requireNonNull(CODEC, "CODEC");
   }

   public void applyCarvers(
       WorldGenRegion level,
      long seed,
       RandomState random,
       BiomeManager biomeManager,
       StructureManager structures,
       ChunkAccess chunk
   ) {
      long totalStartNs = beginFullChunkProfiling();
      if (!SharedConstants.DEBUG_DISABLE_CARVERS && cavesEnabled(this.settings)) {
         long phaseStartNs = beginFullChunkProfiling();
         boolean[] waterFlags = new boolean[CHUNK_AREA];
         WaterSurfaceResolver.WaterChunkData waterData = this.resolveChunkWaterData(chunk.getPos());
         int waterColumnCount = 0;

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
               boolean hasWater = waterData.hasWater(localX, localZ);
               waterFlags[chunkIndex(localX, localZ)] = hasWater;
               if (hasWater) {
                  waterColumnCount++;
               }
            }
         }

         boolean[] floodGuardColumns = computeFloodGuardColumns(waterFlags);
         int defaultFloodGuardY = this.seaLevel - SPAGHETTI_WATER_GUARD_DEPTH;
         int[] floodGuardYByColumn = new int[CHUNK_AREA];
         Arrays.fill(floodGuardYByColumn, Integer.MAX_VALUE);

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            for (int localXx = 0; localXx < CHUNK_SIDE; localXx++) {
               int index = chunkIndex(localXx, localZ);
               if (floodGuardColumns[index]) {
                  floodGuardYByColumn[index] = defaultFloodGuardY;
               }
            }
         }

         if (waterColumnCount >= Math.ceil(CHUNK_AREA * OCEAN_CHUNK_CARVER_GUARD_RATIO)) {
            for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
               for (int localXxx = 0; localXxx < CHUNK_SIDE; localXxx++) {
                  int index = chunkIndex(localXxx, localZ);
                  if (floodGuardColumns[index] && waterData.hasWater(localXxx, localZ)) {
                     int terrainSurface = waterData.terrainSurface(localXxx, localZ);
                     int oceanFloorGuardY = Math.max(chunk.getMinY(), terrainSurface - OCEAN_CARVER_FLOOR_BUFFER);
                     floodGuardYByColumn[index] = Math.min(floodGuardYByColumn[index], oceanFloorGuardY);
                  }
               }
            }
         }

         this.applyTerrainSurfaceCaveGuard(chunk, floodGuardYByColumn);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.CARVERS_WATER_GUARD, phaseStartNs);
         phaseStartNs = beginFullChunkProfiling();
         // Graceful degradation: carving is a bonus, it must never make the whole chunk upgrade fail
         // (lesson measured the hard way: the c2me aquifer optimization throws an AssertionError inside NoiseChunk.forChunk
         // -> the chunk upgrade fails -> **the world cannot be entered at all**, a wildly disproportionate cost)
         // So any Throwable is caught here: one ERROR with a stack trace, then this chunk's carving is skipped
         // and the world loads as usual, with the cause and the fix visible in the log for the player.
         try {
            this.getTellusCarverRunner(level.registryAccess())
               .applyCarvers(level, seed, random, biomeManager, structures, chunk, floodGuardYByColumn);
         } catch (Throwable carveFailure) {
            if (TELLUS_CARVE_FAILURE_WARNED.compareAndSet(false, true)) {
               Tellus.LOGGER.error("[TellusCN] Cave carving failed; carving skipped, world still loads normally"
                  + " (no caves in these chunks; everything else is normal). Most common cause: c2me with "
                  + " vanillaWorldGenOptimizations.optimizeAquifer enabled, which makes the "
                  + " aquifer construction inside NoiseChunk.forChunk throw an AssertionError (array init). "
                  + " Fix: set optimizeAquifer=false in config/c2me.toml and restart"
                  + " (or add -Dtellus.caves.enabled=false to turn caves off entirely)."
                  + " This error will not be reported again in this session.",
                  carveFailure);
            }
         }
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.CARVERS_RUNNER, phaseStartNs);
      }

      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.CARVERS_TOTAL, totalStartNs);
   }

   public void buildSurface( WorldGenRegion level,  StructureManager structures,  RandomState random,  ChunkAccess chunk) {
   }

   public void spawnOriginalMobs( WorldGenRegion level) {
      ChunkPos center = level.getCenter();
      Holder<Biome> biome = level.getBiome(center.getWorldPosition().atY(level.getMaxY()));
      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
      random.setDecorationSeed(level.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
      NaturalSpawner.spawnMobsForChunkGeneration(level, biome, center, random);
   }

   public void applyBiomeDecoration( WorldGenLevel level,  ChunkAccess chunk,  StructureManager structures) {
      long chunkKey = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
      long totalStartNs = beginFullChunkProfiling();
      long phaseStartNs;
      try {
         phaseStartNs = beginFullChunkProfiling();
         super.applyBiomeDecoration(level, chunk, structures);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_SUPER, phaseStartNs);
         // Remove the vanilla decoration that lands on top of buildings (explicitly requested by the user on 2026-09-20)
         // Vanilla decoration (grass / flowers / saplings / vanilla trees ...) **ignores buildings entirely**,
         // so grass grows on rooftops and trees grow through floors. The mod's own placeTrees already skips building columns
         // (preparedBuildings.suppressesTrees), but the vanilla step does not -> that is what is added here.
         // Only whitelisted scenery blocks are removed, only inside the building's own height range, only in columns that have a building.
         if (cavesEnabled(this.settings)) {
            phaseStartNs = beginFullChunkProfiling();
            this.spawnAxolotlsInLushPonds(level, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_AXOLOTLS, phaseStartNs);
         }

         boolean delayTellusDecoration = this.hasPendingTerrainRefinement(chunk.getPos());
         if (delayTellusDecoration) {
            EarthChunkGenerator.TerrainStreamingPerf.recordDetailDelayedByRefinement();
         }
         phaseStartNs = beginFullChunkProfiling();
         // Vanilla decoration landing on buildings (grass / flowers / leaves / saplings) is removed here (requested by the user on 2026-09-20)
         // This must live here rather than after super.applyBiomeDecoration, because delayTellusDecoration is only declared on this branch
         if (!delayTellusDecoration) {
            this.clearVanillaSceneryOnBuildings(level, chunk);
         }
         if (!delayTellusDecoration && !this.shouldDeferTrees()) {
            this.placeTrees(level, chunk);
         }
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_TREES, phaseStartNs);
         if (!delayTellusDecoration && !this.shouldDeferBuildingDetails()) {
            phaseStartNs = beginFullChunkProfiling();
            this.placePreparedBuildings(level, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_BUILDINGS, phaseStartNs);
         }

         phaseStartNs = beginFullChunkProfiling();
         if (!delayTellusDecoration) {
            this.applyRealtimeSnowCover(level, chunk);
         }
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_REALTIME_SNOW, phaseStartNs);
         if (!delayTellusDecoration && !this.shouldDeferRoadDetails()) {
            phaseStartNs = beginFullChunkProfiling();
            this.placePreparedRoadLights(level, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_ROAD_LIGHTS, phaseStartNs);
         }

         if (!delayTellusDecoration && this.hasDeferredApplyWork()) {
            phaseStartNs = beginFullChunkProfiling();
            this.applyReadyDeferredChunkDetail(level, chunk);
            endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_DEFERRED_APPLY, phaseStartNs);
         }

         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_TOTAL, totalStartNs);
      } finally {
         this.chunkDecorationContexts.remove(chunkKey);
         this.clearPreparedChunkStateTracking(chunkKey);
      }
   }

   public void createStructures(
       RegistryAccess registryAccess,
       ChunkGeneratorStructureState structureState,
       StructureManager structures,
       ChunkAccess chunk,
       StructureTemplateManager templates,
       ResourceKey<Level> levelKey
   ) {
      long totalStartNs = beginFullChunkProfiling();
      long phaseStartNs = beginFullChunkProfiling();
      super.createStructures(registryAccess, structureState, structures, chunk, templates, levelKey);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_SUPER, phaseStartNs);
      phaseStartNs = beginFullChunkProfiling();
      this.filterVillageStarts(registryAccess, chunk);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_VILLAGES, phaseStartNs);
      if (this.settings.addWoodlandMansions()) {
         phaseStartNs = beginFullChunkProfiling();
         this.filterWoodlandMansionStarts(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_WOODLAND_MANSIONS, phaseStartNs);
      }

      if (this.settings.addIgloos() && !this.isFrozenPeaksChunk(chunk.getPos(), structureState.randomState())) {
         phaseStartNs = beginFullChunkProfiling();
         this.stripIglooStarts(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_IGLOOS, phaseStartNs);
      }

      if (this.settings.addStrongholds()) {
         phaseStartNs = beginFullChunkProfiling();
         this.retargetStrongholdStarts(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_STRONGHOLDS, phaseStartNs);
      }

      if (this.settings.addMineshafts()) {
         phaseStartNs = beginFullChunkProfiling();
         this.retargetMineshaftStarts(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_MINESHAFTS, phaseStartNs);
      }

      if (this.settings.addTrialChambers()) {
         phaseStartNs = beginFullChunkProfiling();
         this.retargetTrialChamberStarts(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_TRIAL_CHAMBERS, phaseStartNs);
      }

      if (this.settings.addOceanMonuments()) {
         phaseStartNs = beginFullChunkProfiling();
         this.adjustOceanMonumentStarts(registryAccess, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_OCEAN_MONUMENTS, phaseStartNs);
      }

      phaseStartNs = beginFullChunkProfiling();
      this.filterStartsCollidingWithOsm(registryAccess, chunk);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_OSM_COLLISIONS, phaseStartNs);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.STRUCTURES_TOTAL, totalStartNs);
   }

   public void createReferences( WorldGenLevel level,  StructureManager structures,  ChunkAccess chunk) {
      super.createReferences(level, structures, chunk);
   }


   public CompletableFuture<ChunkAccess> fillFromNoise(
       Blender blender,  RandomState random,  StructureManager structures,  ChunkAccess chunk
   ) {
      this.disableFastSpawnMode();
      long baseTerrainStartNs = EarthChunkGenerator.ChunkDetailPerf.now();
      long fullChunkStartNs = beginFullChunkProfiling();
      this.fillTellusSurface(random, structures, chunk);
      EarthChunkGenerator.ChunkDetailPerf.recordBaseTerrain(EarthChunkGenerator.ChunkDetailPerf.elapsedSince(baseTerrainStartNs));
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_TOTAL, fullChunkStartNs);
      return Objects.requireNonNull(CompletableFuture.completedFuture(chunk), "completedFuture");
   }

   private void fillTellusSurface( RandomState random,  StructureManager structures,  ChunkAccess chunk) {
      ChunkPos pos = chunk.getPos();
      long chunkKey = ChunkPos.pack(pos.x(), pos.z());
      long generationStamp = this.chunkDetailGenerationSequence.incrementAndGet();
      boolean terrainShellMode = this.usesDeferredTerrainRefinement() && !this.shouldForceExactSpawnTerrain(pos);
      this.discardPreparedChunkState(chunkKey);
      if (terrainShellMode) {
         this.terrainGenerationStamps.put(chunkKey, generationStamp);
      } else {
         this.terrainGenerationStamps.remove(chunkKey);
      }
      long phaseStartNs = beginFullChunkProfiling();
      if (BLOCKING_TERRAIN_INPUT_WARMUP) {
         TellusWorldgenSources.warmCriticalTerrainInputsForChunk(pos, this.settings, this.settings.worldScale());
      }
      TellusWorldgenSources.prefetchForChunk(pos, this.settings, true, true, true, this.settings.worldScale(), false);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_PREFETCH, phaseStartNs);
      int chunkMinY = chunk.getMinY();
      int chunkHeight = chunk.getHeight();
      int chunkMaxY = chunkMinY + chunkHeight;
      if (LOGGED_CHUNK_LAYOUT.compareAndSet(false, true) && Tellus.LOGGER.isInfoEnabled()) {
         Tellus.LOGGER
            .info(
               "fillFromNoise layout: chunkPos={}, minY={}, height={}, maxY={}, sections={}, genMinY={}, genHeight={}, seaLevel={}, configuredMin={}, configuredMax={}",
               new Object[]{
                  pos,
                  chunkMinY,
                  chunkHeight,
                  chunkMinY + chunkHeight - 1,
                  chunkHeight >> 4,
                  this.minY,
                  this.height,
                  this.seaLevel,
                  this.settings.minAltitude(),
                  this.settings.maxAltitude()
               }
            );
      }

      int deepslateStart = this.minY + 64;
      boolean useFastFullChunk = FAST_FULL_CHUNK;
      boolean useFastSurfacePalette = useFastFullChunk && FULL_CHUNK_SURFACE_MODE == EarthChunkGenerator.SurfaceMode.TWO_TIER;
      MutableBlockPos cursor = new MutableBlockPos();
      int step = 4;
      int gridSize = 16 + step * 2;
      phaseStartNs = beginFullChunkProfiling();
      EarthChunkGenerator.TerrainShellHeightGridResult heightGridResult = terrainShellMode
         ? this.buildTerrainShellHeightGrid(pos, step, gridSize, useFastFullChunk)
         : EarthChunkGenerator.TerrainShellHeightGridResult.exact(
            this.buildHeightGrid(pos, step, gridSize, useFastFullChunk, NON_BLOCKING_TERRAIN_INPUTS)
         );
      int[] heightGrid = heightGridResult.heightGrid();

      int[] coverClasses = new int[CHUNK_AREA];
      int[] visualCoverClasses = new int[CHUNK_AREA];
      int[] surfaceCoverClasses = new int[CHUNK_AREA];
      boolean[] oceanFlags = new boolean[CHUNK_AREA];
      int[] terrainSurfaces = new int[CHUNK_AREA];
      copyChunkTerrainSurfaces(heightGrid, gridSize, step, terrainSurfaces);
      this.rememberCaveSurfaces(pos, terrainSurfaces);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID, phaseStartNs);
      recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_HIT, heightGridResult.cacheHits());
      recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_MISS, heightGridResult.cacheMisses());

      phaseStartNs = beginFullChunkProfiling();
      WaterSurfaceResolver.WaterChunkData waterData = this.resolveChunkWaterData(pos, terrainSurfaces);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_WATER_RESOLVE, phaseStartNs);
      int[] waterSurfaces = new int[CHUNK_AREA];
      boolean[] waterFlags = new boolean[CHUNK_AREA];
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int bedrockY = this.minY;
      boolean bedrockInChunk = bedrockY >= chunkMinY && bedrockY < chunkMaxY;
      long surfaceCoverResolveNs = 0L;
      int shellCoverMisses = 0;
      int shellVisualCoverMisses = 0;
      phaseStartNs = beginFullChunkProfiling();
      if (terrainShellMode) {
         EarthChunkGenerator.TerrainShellColumnFillResult shellColumns = this.fillTerrainShellColumns(
            pos,
            chunkMinY,
            chunkMaxY,
            step,
            gridSize,
            heightGrid,
            waterData,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            coverClasses,
            visualCoverClasses,
            surfaceCoverClasses,
            oceanFlags
         );
         surfaceCoverResolveNs = shellColumns.surfaceCoverResolveNs();
         shellCoverMisses = shellColumns.coverMisses();
         shellVisualCoverMisses = shellColumns.visualCoverMisses();
      } else {
         surfaceCoverResolveNs = this.fillExactChunkTerrainColumns(
            pos,
            chunkMinY,
            chunkMaxY,
            step,
            gridSize,
            heightGrid,
            waterData,
            null,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            coverClasses,
            visualCoverClasses,
            surfaceCoverClasses,
            oceanFlags,
            true
         );
      }
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_COLUMN_RESOLVE, phaseStartNs);
      this.auditChunkSurfaceDeviation(pos, heightGrid, gridSize, step, terrainSurfaces, waterData, terrainShellMode);

      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = null;
      if (!terrainShellMode) {
         phaseStartNs = beginFullChunkProfiling();
         this.repairAnomalousChunkTerrain(terrainSurfaces, waterSurfaces, waterFlags, coverClasses, heightGrid, gridSize, step, chunkMinY, chunkMaxY - 1);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_TERRAIN_REPAIR, phaseStartNs);
         phaseStartNs = beginFullChunkProfiling();
         preparedBuildings = this.shouldDeferBuildingDetails()
            ? null
            : this.prepareChunkBuildings(pos, terrainSurfaces, chunkMinY, chunkMaxY - 1, random);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_PREP, phaseStartNs);
         phaseStartNs = beginFullChunkProfiling();
         this.applyPreparedBuildingsToTerrain(preparedBuildings, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, chunkMaxY - 1);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_TERRAIN, phaseStartNs);
      }
      int[] slopeDiffs = new int[CHUNK_AREA];
      int[] convexities = new int[CHUNK_AREA];
      Holder<Biome>[] biomeCache = newBiomeCache(CHUNK_AREA);
      EarthBiomeSource earthBiomeSource = this.biomeSource instanceof EarthBiomeSource typedEarthBiomeSource ? typedEarthBiomeSource : null;
      EarthChunkGenerator.ChunkBiomeClimateCache climateCache = useFastFullChunk && earthBiomeSource != null
         ? new EarthChunkGenerator.ChunkBiomeClimateCache(pos, this.settings.worldScale())
         : null;
      phaseStartNs = beginFullChunkProfiling();
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache = this.buildFullChunkOceanBeachCache(
         chunkMinX, chunkMinZ, waterFlags, oceanFlags
      );

      this.fillChunkTerrainMetricsAndBiomes(
         pos,
         step,
         gridSize,
         heightGrid,
         terrainSurfaces,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         oceanFlags,
         slopeDiffs,
         convexities,
         biomeCache,
         earthBiomeSource,
         climateCache,
         random,
         null
      );
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE, phaseStartNs);
      if (climateCache != null) {
         recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_HIT, climateCache.hitCount());
         recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_MISS, climateCache.missCount());
      }
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_COVER_RESOLVE, surfaceCoverResolveNs);
      this.chunkDecorationContexts.put(
         chunkKey, EarthChunkGenerator.ChunkDecorationContext.capture(pos, terrainSurfaces, waterFlags, coverClasses, biomeCache)
      );
      this.markPreparedChunkState(chunkKey);

      EarthChunkGenerator.TerrainWarmupTicket warmupTicket = new EarthChunkGenerator.TerrainWarmupTicket(
         heightGridResult.missingCount(), shellCoverMisses, shellVisualCoverMisses, waterData.approximate(), heightGridResult.usedFallback()
      );
      if (terrainShellMode) {
         phaseStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.TerrainShellBuildResult shellResult = EarthChunkGenerator.TerrainShellBuildResult.capture(
            pos,
            chunkMinY,
            chunkMaxY - 1,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            coverClasses,
            visualCoverClasses,
            surfaceCoverClasses,
            oceanFlags,
            biomeCache,
            warmupTicket,
            generationStamp
         );
         this.terrainRefinementManager.schedule(this, shellResult);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_SCHEDULE, phaseStartNs);
      } else if (this.hasDeferredApplyWork()) {
         phaseStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.ChunkGenerationContext detailContext = EarthChunkGenerator.ChunkGenerationContext.capture(
            pos,
            chunkMinY,
            chunkMaxY - 1,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            coverClasses,
            biomeCache,
            preparedBuildings,
            generationStamp
         );
         this.chunkDetailManager.schedule(this, detailContext);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_SCHEDULE, phaseStartNs);
      }

      int minSurface = minSurfaceHeight(terrainSurfaces);
      LevelChunkSection[] sections = chunk.getSections();
      int sectionCount = sections.length;
      int[] sectionTopYs = new int[sectionCount];
      boolean[] solidSections = new boolean[sectionCount];

      for (int i = 0; i < sectionCount; i++) {
         sectionTopYs[i] = chunkMinY + (i << 4) + 15;
      }

      phaseStartNs = beginFullChunkProfiling();
      EarthChunkGenerator.SolidSectionFillProfiler solidSectionProfiler = new EarthChunkGenerator.SolidSectionFillProfiler();
      long columnStoneFillNs = 0L;
      long waterColumnFillNs = 0L;
      long surfaceApplyNs = 0L;
      long snowApplyNs = 0L;
      EarthChunkGenerator.ChunkSectionWriter sectionWriter = useFastFullChunk
         ? new EarthChunkGenerator.ChunkSectionWriter(sections, chunkMinY)
         : null;
      boolean[] columnFilledSections = useFastFullChunk ? null : new boolean[sectionCount];
      EarthChunkGenerator.SurfaceApplyProfiler surfaceProfiler = new EarthChunkGenerator.SurfaceApplyProfiler();

      try {
         long solidSectionsStartNs = beginFullChunkProfiling();
         long solidSectionsSubPhaseStartNs = beginFullChunkProfiling();
         int solidMaxIndex = resolveSolidSectionMaxIndex(chunk, chunkMinY, minSurface, sectionCount);
         solidSectionProfiler.maxIndexNs += elapsedFullChunkProfilingSince(solidSectionsSubPhaseStartNs);
         if (solidMaxIndex >= 0) {
            if (sectionWriter != null) {
               fillSolidSections(sectionWriter, solidSections, sectionTopYs, solidMaxIndex, STONE_STATE, DEEPSLATE_STATE, deepslateStart, solidSectionProfiler);
            } else {
               fillSolidSections(sections, solidSections, sectionTopYs, solidMaxIndex, STONE_STATE, DEEPSLATE_STATE, deepslateStart, solidSectionProfiler);
            }
         }
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS, solidSectionsStartNs);

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = chunkMinZ + localZ;
            int rowIndex = localZ * CHUNK_SIDE;

            for (int localX = 0; localX < CHUNK_SIDE; localX++) {
               int worldX = chunkMinX + localX;
               int index = rowIndex + localX;
               int surface = terrainSurfaces[index];
               int waterSurface = waterSurfaces[index];
               boolean hasWater = waterFlags[index];
               Holder<Biome> biome = biomeCache[index];
               int y = chunkMinY;
               long subPhaseStartNs = beginFullChunkProfiling();
               int surfaceCoverClass = surfaceCoverClasses[index];
               boolean underwater = hasWater && waterSurface > surface;
               int slopeDiff = slopeDiffs[index];
               int convexity = convexities[index];
               BlockState mountainMassFill = underwater
                  ? null
                  : this.resolveMountainMassFillBlock(surfaceCoverClass, surface, slopeDiff, convexity, worldZ);

               if (mountainMassFill != null) {
                  if (sectionWriter != null) {
                     sectionWriter.fillColumnConstant(localX, localZ, chunkMinY, surface, mountainMassFill);
                  } else {
                     fillColumnConstant(sections, columnFilledSections, chunkMinY, localX, localZ, chunkMinY, surface, mountainMassFill);
                  }
               } else {
                  while (y <= surface) {
                     int sectionIndex = chunk.getSectionIndex(y);
                     if (sectionIndex >= 0 && sectionIndex < sectionCount && solidSections[sectionIndex]) {
                        y = sectionTopYs[sectionIndex] + 1;
                     } else {
                        int spanTopY = sectionIndex >= 0 && sectionIndex < sectionCount ? Math.min(surface, sectionTopYs[sectionIndex]) : surface;
                        if (sectionWriter != null) {
                           sectionWriter.fillStoneColumnSpan(localX, localZ, y, spanTopY, deepslateStart, STONE_STATE, DEEPSLATE_STATE);
                        } else {
                           fillStoneColumnSpan(
                              sections,
                              columnFilledSections,
                              chunkMinY,
                              localX,
                              localZ,
                              y,
                              spanTopY,
                              deepslateStart,
                              STONE_STATE,
                              DEEPSLATE_STATE
                           );
                        }
                        y = spanTopY + 1;
                     }
                  }
               }

               if (bedrockInChunk) {
                  if (sectionWriter != null) {
                     sectionWriter.setBlock(localX, localZ, bedrockY, BEDROCK_STATE);
                  } else {
                     cursor.set(worldX, bedrockY, worldZ);
                     chunk.setBlockState(cursor, BEDROCK_STATE);
                  }
               }
               columnStoneFillNs += elapsedFullChunkProfilingSince(subPhaseStartNs);

               subPhaseStartNs = beginFullChunkProfiling();
               if (hasWater && surface < waterSurface) {
                  if (sectionWriter != null) {
                     sectionWriter.fillColumnConstant(localX, localZ, surface + 1, waterSurface, WATER_STATE);
                  } else {
                     for (int yx = surface + 1; yx <= waterSurface; yx++) {
                        cursor.set(worldX, yx, worldZ);
                        chunk.setBlockState(cursor, WATER_STATE);
                     }
                  }
               }
               waterColumnFillNs += elapsedFullChunkProfilingSince(subPhaseStartNs);

               subPhaseStartNs = beginFullChunkProfiling();
               if (sectionWriter != null) {
                  this.applySurface(
                     sectionWriter,
                     worldX,
                     worldZ,
                     localX,
                     localZ,
                     surface,
                     chunkMinY,
                     underwater,
                     biome,
                     slopeDiff,
                     convexity,
                     surfaceCoverClass,
                     oceanBeachCache,
                     surfaceProfiler,
                     useFastSurfacePalette
                  );
               } else {
                  this.applySurface(
                     chunk,
                     cursor,
                     worldX,
                     worldZ,
                     surface,
                     chunkMinY,
                     underwater,
                     biome,
                     slopeDiff,
                     convexity,
                     surfaceCoverClass,
                     oceanBeachCache,
                     surfaceProfiler,
                     useFastSurfacePalette
                  );
               }
               surfaceApplyNs += elapsedFullChunkProfilingSince(subPhaseStartNs);

               if (surface >= this.seaLevel
                  && this.shouldRetainSurfaceSnow(useFastSurfacePalette, surfaceCoverClass, surface, slopeDiff, convexity, worldX, worldZ)) {
                  subPhaseStartNs = beginFullChunkProfiling();
                  if (sectionWriter != null) {
                     applySnowCover(sectionWriter, localX, localZ, worldX, worldZ, surface, chunkMinY);
                  } else {
                     applySnowCover(chunk, cursor, worldX, worldZ, surface, chunkMinY);
                  }
                  snowApplyNs += elapsedFullChunkProfilingSince(subPhaseStartNs);
               }
            }
         }

         if (sectionWriter != null) {
            EarthChunkGenerator.ChunkSectionWriter.FlushResult flushResult = sectionWriter.finish();
            solidSectionProfiler.recalcNs += flushResult.recalcNs();
            recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_DIRTY_SECTIONS, flushResult.dirtySectionCount());
            sectionWriter = null;
         } else {
            long stoneRecalcStartNs = beginFullChunkProfiling();
            recalcFilledSections(sections, solidSections, columnFilledSections);
            columnStoneFillNs += elapsedFullChunkProfilingSince(stoneRecalcStartNs);
         }
      } finally {
         if (sectionWriter != null) {
            sectionWriter.close();
         }
      }

      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_MAX_INDEX, solidSectionProfiler.maxIndexNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SCAN, solidSectionProfiler.scanNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_ACCESS, solidSectionProfiler.sectionAccessNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_WRITE, solidSectionProfiler.sectionWriteNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_RECALC, solidSectionProfiler.recalcNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_STONE_FILL, columnStoneFillNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_WATER_FILL, waterColumnFillNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_PALETTE, surfaceProfiler.paletteResolveNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BADLANDS, surfaceProfiler.badlandsNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BLOCK_WRITES, surfaceProfiler.blockWriteNs);
      recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_FAST_PATH, surfaceProfiler.fastPathCount);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE, surfaceApplyNs);
      recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SNOW, snowApplyNs);
      endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS, phaseStartNs);

      if (!terrainShellMode && !this.shouldDeferRoadDetails()) {
         phaseStartNs = beginFullChunkProfiling();
         this.applyOsmRoadOverlay(chunk, pos, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, chunkMaxY - 1, preparedBuildings);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_ROADS, phaseStartNs);
      }

      if (!terrainShellMode) {
         phaseStartNs = beginFullChunkProfiling();
         this.carveStructureClearanceVolumes(structures, chunk);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_STRUCTURE_CLEARANCE, phaseStartNs);
      }
   }

   private OsmQueryMode resolveFullChunkOsmQueryMode() {
      return CHUNK_DETAIL_LEGACY_BLOCKING || !FULL_CHUNK_OSM_NON_BLOCKING ? OsmQueryMode.BLOCKING : OsmQueryMode.NON_BLOCKING;
   }

   private void applyOsmRoadOverlay(
      ChunkAccess chunk,
      ChunkPos pos,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      this.applyOsmRoadOverlay(null, chunk, pos, terrainSurfaces, waterSurfaces, waterFlags, chunkMinY, chunkMaxY, preparedBuildings, null);
   }

   private void applyOsmRoadOverlay(
      WorldGenLevel level,
      ChunkAccess chunk,
      ChunkPos pos,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      EarthChunkGenerator.OsmRoadQueryResult deferredRoadQuery
      ) {
      long chunkKey = ChunkPos.pack(pos.x(), pos.z());
      this.preparedChunkRoadLights.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      if (this.settings.enableRoads()) {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_ROAD_MAX_SCALE)) {
            int chunkMinX = pos.getMinBlockX();
            int chunkMinZ = pos.getMinBlockZ();
            int chunkMaxX = chunkMinX + CHUNK_MASK;
            int chunkMaxZ = chunkMinZ + CHUNK_MASK;
            OsmQueryMode queryMode = deferredRoadQuery == null ? this.resolveFullChunkOsmQueryMode() : OsmQueryMode.BLOCKING;
            long fetchStartNs = deferredRoadQuery == null ? OsmPerf.now() : 0L;
            EarthChunkGenerator.OsmRoadQueryResult roadQuery = deferredRoadQuery != null
               ? deferredRoadQuery
               : this.fetchOsmRoadsForAreaDetailed(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, OSM_ROAD_QUERY_MARGIN, queryMode);
            if (deferredRoadQuery == null && queryMode == OsmQueryMode.NON_BLOCKING && roadQuery.hadCacheMisses() && roadQuery.features().isEmpty()) {
               EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
            }

            List<RoadFeature> roads = roadQuery.features();
            long fetchNs = OsmPerf.elapsedSince(fetchStartNs);
            if (roads.isEmpty()) {
               OsmPerf.recordFullChunkRoad(fetchNs, 0L, 0);
            } else {
               long rasterStartNs = OsmPerf.now();
               EarthChunkGenerator.RoadWidths widths = resolveRoadWidths(worldScale);
               List<RoadFeature> mainRoads = new ArrayList<>();
               List<RoadFeature> mainBridgeRoads = new ArrayList<>();
               List<RoadFeature> normalRoads = new ArrayList<>();
               List<RoadFeature> normalBridgeRoads = new ArrayList<>();
               List<RoadFeature> dirtRoads = new ArrayList<>();
               List<RoadFeature> dirtBridgeRoads = new ArrayList<>();

               for (RoadFeature road : roads) {
                  switch (road.roadClass()) {
                     case MAIN:
                        mainRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           mainBridgeRoads.add(road);
                        }
                        break;
                     case NORMAL:
                        normalRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           normalBridgeRoads.add(road);
                        }
                        break;
                     case DIRT:
                        dirtRoads.add(road);
                        if (road.mode() == RoadMode.BRIDGE) {
                           dirtBridgeRoads.add(road);
                        }
                  }
               }

               if (mainRoads.isEmpty() && normalRoads.isEmpty() && dirtRoads.isEmpty()) {
                  OsmPerf.recordFullChunkRoad(fetchNs, OsmPerf.elapsedSince(rasterStartNs), roads.size());
               } else {
                  int padding = 0;
                  int extSide = CHUNK_SIDE + padding * 2;
                  int extArea = extSide * extSide;
                  int extMinX = chunkMinX - padding;
                  int extMinZ = chunkMinZ - padding;
                  EarthChunkGenerator.OsmOverlayScratch scratch = this.osmOverlayScratch.get();
                  scratch.ensureRoadExtCapacity(extArea);
                  byte[] resolvedClass = scratch.resolvedClass;
                  byte[] resolvedMode = scratch.resolvedMode;
                  int[] resolvedDeckY = scratch.resolvedDeckY;
                  boolean[] resolvedTunnelCarve = scratch.resolvedTunnelCarve;
                  boolean[] blockedByHigherClass = scratch.blockedByHigherClass;
                  boolean[] bridgeOverlayPresent = scratch.bridgeOverlayPresent;
                  int[] bridgeOverlayDeckY = scratch.bridgeOverlayDeckY;
                  byte[] bridgeOverlayClass = scratch.bridgeOverlayClass;
                  boolean[] bridgeSupportShaftPresent = scratch.bridgeSupportShaftPresent;
                  int[] bridgeSupportShaftBottomY = scratch.bridgeSupportShaftBottomY;
                  int[] bridgeSupportShaftTopY = scratch.bridgeSupportShaftTopY;
                  boolean[] bridgeSupportCapPresent = scratch.bridgeSupportCapPresent;
                  int[] bridgeSupportCapBottomY = scratch.bridgeSupportCapBottomY;
                  int[] bridgeSupportCapTopY = scratch.bridgeSupportCapTopY;
                  scratch.clearRoadExtState(extArea);
                  double blocksPerDegree = blocksPerDegree(worldScale);
                  Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache = scratch.edgeColumnCache;
                  edgeColumnCache.clear();
                  this.rasterizeRoadClassPass(
                     mainRoads,
                     RoadClass.MAIN,
                     widths.main(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedMode,
                     resolvedDeckY,
                     resolvedTunnelCarve,
                     blockedByHigherClass,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     bridgeOverlayClass,
                     scratch,
                     extArea
                  );
                  this.rasterizeRoadClassPass(
                     normalRoads,
                     RoadClass.NORMAL,
                     widths.normal(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedMode,
                     resolvedDeckY,
                     resolvedTunnelCarve,
                     blockedByHigherClass,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     bridgeOverlayClass,
                     scratch,
                     extArea
                  );
                  this.rasterizeRoadClassPass(
                     dirtRoads,
                     RoadClass.DIRT,
                     widths.dirt(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedMode,
                     resolvedDeckY,
                     resolvedTunnelCarve,
                     blockedByHigherClass,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     bridgeOverlayClass,
                     scratch,
                     extArea
                  );
                  this.rasterizeBridgeSupports(
                     mainBridgeRoads,
                     widths.main(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedDeckY,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     preparedBuildings,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY
                  );
                  this.rasterizeBridgeSupports(
                     normalBridgeRoads,
                     widths.normal(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedDeckY,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     preparedBuildings,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY
                  );
                  this.rasterizeBridgeSupports(
                     dirtBridgeRoads,
                     widths.dirt(),
                     blocksPerDegree,
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     terrainSurfaces,
                     waterSurfaces,
                     waterFlags,
                     chunkMinY,
                     chunkMaxY,
                     edgeColumnCache,
                     resolvedClass,
                     resolvedDeckY,
                     bridgeOverlayPresent,
                     bridgeOverlayDeckY,
                     preparedBuildings,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY
                  );
                  byte[] chunkRoadClass = scratch.chunkRoadClass;
                  byte[] chunkRoadMode = scratch.chunkRoadMode;
                  int[] chunkRoadDeckY = scratch.chunkRoadDeckY;
                  boolean[] chunkTunnelNeedsCarve = scratch.chunkTunnelNeedsCarve;
                  Arrays.fill(chunkRoadClass, (byte)0);
                  Arrays.fill(chunkRoadMode, (byte)0);
                  Arrays.fill(chunkRoadDeckY, 0);
                  Arrays.fill(chunkTunnelNeedsCarve, false);
                  MutableBlockPos cursor = new MutableBlockPos();

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                        int extIndex = extIndex(localX + padding, localZ + padding, extSide);
                        int classId = resolvedClass[extIndex];
                        if (classId > 0) {
                           int deckY = Mth.clamp(resolvedDeckY[extIndex], chunkMinY, chunkMaxY);
                           if (preparedBuildings != null && preparedBuildings.intersectsRoad(localX, localZ, deckY)) {
                              continue;
                              }

                              int worldX = chunkMinX + localX;
                              int worldZ = chunkMinZ + localZ;
                              cursor.set(worldX, deckY, worldZ);
                              this.setChunkBlock(level, chunk, cursor, roadStateForClass(roadClassFromId(classId)));
                              int chunkIndex = chunkIndex(localX, localZ);
                              chunkRoadClass[chunkIndex] = (byte)classId;
                              chunkRoadMode[chunkIndex] = resolvedMode[extIndex];
                           chunkRoadDeckY[chunkIndex] = deckY;
                           chunkTunnelNeedsCarve[chunkIndex] = resolvedTunnelCarve[extIndex];
                        }
                     }
                  }

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localXx = 0; localXx < CHUNK_SIDE; localXx++) {
                        int extIndex = extIndex(localXx + padding, localZ + padding, extSide);
                        if (bridgeOverlayPresent[extIndex]) {
                           int classId = bridgeOverlayClass[extIndex];
                           if (classId > 0) {
                              int deckY = Mth.clamp(bridgeOverlayDeckY[extIndex], chunkMinY, chunkMaxY);
                              if (preparedBuildings != null && preparedBuildings.intersectsRoad(localXx, localZ, deckY)) {
                                 continue;
                              }

                              int worldX = chunkMinX + localXx;
                              int worldZ = chunkMinZ + localZ;
                              cursor.set(worldX, deckY, worldZ);
                              this.setChunkBlock(level, chunk, cursor, roadStateForClass(roadClassFromId(classId)));
                           }
                        }
                     }
                  }

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                        int extIndex = extIndex(localX + padding, localZ + padding, extSide);
                        int worldX = chunkMinX + localX;
                        int worldZ = chunkMinZ + localZ;
                        if (bridgeSupportShaftPresent[extIndex]) {
                           int bottomY = Mth.clamp(bridgeSupportShaftBottomY[extIndex], chunkMinY, chunkMaxY);
                           int topY = Mth.clamp(bridgeSupportShaftTopY[extIndex], chunkMinY, chunkMaxY);
                           if (topY >= bottomY) {
                              for (int y = bottomY; y <= topY; y++) {
                                 cursor.set(worldX, y, worldZ);
                                 this.setChunkBlock(level, chunk, cursor, BRIDGE_SUPPORT_SHAFT_STATE);
                              }
                           }
                        }

                        if (bridgeSupportCapPresent[extIndex]) {
                           int bottomY = Mth.clamp(bridgeSupportCapBottomY[extIndex], chunkMinY, chunkMaxY);
                           int topY = Mth.clamp(bridgeSupportCapTopY[extIndex], chunkMinY, chunkMaxY);
                           if (topY >= bottomY) {
                              for (int y = bottomY; y <= topY; y++) {
                                 cursor.set(worldX, y, worldZ);
                                 this.setChunkBlock(level, chunk, cursor, BRIDGE_SUPPORT_CAP_STATE);
                              }
                           }
                        }
                     }
                  }

                  int[] classWidths = scratch.classWidths;
                  classWidths[0] = 0;
                  classWidths[1] = widths.main();
                  classWidths[2] = widths.normal();
                  classWidths[3] = widths.dirt();
                  boolean[] tunnelCarveMask = scratch.tunnelCarveMask;
                  int[] tunnelCarveDeckY = scratch.tunnelCarveDeckY;
                  Arrays.fill(tunnelCarveMask, false);
                  Arrays.fill(tunnelCarveDeckY, Integer.MIN_VALUE);
                  byte tunnelModeId = (byte)(RoadMode.TUNNEL.ordinal() + 1);

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     for (int localXxx = 0; localXxx < CHUNK_SIDE; localXxx++) {
                        int centerIndex = chunkIndex(localXxx, localZ);
                        if (chunkRoadClass[centerIndex] > 0 && chunkRoadMode[centerIndex] == tunnelModeId && chunkTunnelNeedsCarve[centerIndex]) {
                           int roadWidth = classWidths[chunkRoadClass[centerIndex]];
                           int carveWidth = roadWidth + OSM_TUNNEL_SIDE_CLEARANCE * 2;
                           double carveRadius = Math.max(0.5, (carveWidth - 1) * 0.5);
                           int radius = Mth.ceil(carveRadius);
                           double carveRadiusSq = carveRadius * carveRadius + 1.0E-6;

                           for (int dz = -radius; dz <= radius; dz++) {
                              int targetZ = localZ + dz;
                              if (targetZ >= 0 && targetZ < CHUNK_SIDE) {
                                 for (int dx = -radius; dx <= radius; dx++) {
                                    int targetX = localXxx + dx;
                                    if (targetX >= 0 && targetX < CHUNK_SIDE) {
                                       double distSq = dx * dx + dz * dz;
                                       if (!(distSq > carveRadiusSq)) {
                                          int targetIndex = chunkIndex(targetX, targetZ);
                                          tunnelCarveMask[targetIndex] = true;
                                          tunnelCarveDeckY[targetIndex] = Math.max(tunnelCarveDeckY[targetIndex], chunkRoadDeckY[centerIndex]);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }

                  for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
                     int worldZ = chunkMinZ + localZ;

                     for (int localXxxx = 0; localXxxx < CHUNK_SIDE; localXxxx++) {
                        int chunkIndex = chunkIndex(localXxxx, localZ);
                        if (tunnelCarveMask[chunkIndex]) {
                           int deckY = tunnelCarveDeckY[chunkIndex];
                           if (deckY >= chunkMinY && deckY < chunkMaxY) {
                              int topY = Math.min(chunkMaxY, deckY + OSM_TUNNEL_INTERNAL_HEIGHT);
                              if (topY > deckY) {
                                 int worldX = chunkMinX + localXxxx;
                                 boolean intersectsSolid = false;

                                 for (int y = deckY + 1; y <= topY; y++) {
                                    cursor.set(worldX, y, worldZ);
                                    if (isTunnelCarveReplaceable(chunk.getBlockState(cursor))) {
                                       intersectsSolid = true;
                                       break;
                                    }
                                 }

                                 if (intersectsSolid) {
                                    for (int yx = deckY + 1; yx <= topY; yx++) {
                                       cursor.set(worldX, yx, worldZ);
                                       if (isTunnelCarveReplaceable(chunk.getBlockState(cursor))) {
                                          this.setChunkBlock(level, chunk, cursor, CAVE_AIR_STATE);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }

                  EarthChunkGenerator.PreparedChunkRoadLights preparedRoadLights = this.prepareRoadLightsForChunk(
                     pos,
                     roads,
                     widths,
                     chunkRoadClass,
                     chunkRoadMode,
                     chunkRoadDeckY,
                     bridgeSupportShaftPresent,
                     bridgeSupportShaftBottomY,
                     bridgeSupportShaftTopY,
                     bridgeSupportCapPresent,
                     bridgeSupportCapBottomY,
                     bridgeSupportCapTopY,
                     preparedBuildings
                  );
                  if (preparedRoadLights != null && !preparedRoadLights.isEmpty()) {
                     this.preparedChunkRoadLights.put(chunkKey, preparedRoadLights);
                     this.markPreparedChunkState(chunkKey);
                  }

                  OsmPerf.recordFullChunkRoad(fetchNs, OsmPerf.elapsedSince(rasterStartNs), roads.size());
               }
            }
         }
      }
   }

   private void rasterizeRoadClassPass(
      List<RoadFeature> roads,
      RoadClass roadClass,
      int roadWidth,
      double blocksPerDegree,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache,
      byte[] resolvedClass,
      byte[] resolvedMode,
      int[] resolvedDeckY,
      boolean[] resolvedTunnelCarve,
      boolean[] blockedByHigherClass,
      boolean[] bridgeOverlayPresent,
      int[] bridgeOverlayDeckY,
      byte[] bridgeOverlayClass,
      EarthChunkGenerator.OsmOverlayScratch scratch,
      int extArea
   ) {
      if (!roads.isEmpty() && roadWidth > 0) {
         boolean[] candidatePresent = scratch.candidatePresent;
         int[] candidateDeckY = scratch.candidateDeckY;
         byte[] candidateMode = scratch.candidateMode;
         boolean[] candidateTunnelCarve = scratch.candidateTunnelCarve;
         boolean[] bridgeCandidatePresent = scratch.bridgeCandidatePresent;
         int[] bridgeCandidateDeckY = scratch.bridgeCandidateDeckY;
         scratch.clearRoadCandidateState(extArea);

         for (RoadFeature road : roads) {
            if (road.mode() == RoadMode.BRIDGE) {
               this.rasterizeRoadFeature(
                  road,
                  roadWidth,
                  blocksPerDegree,
                  extMinX,
                  extMinZ,
                  extSide,
                  chunkMinX,
                  chunkMinZ,
                  terrainSurfaces,
                  waterSurfaces,
                  waterFlags,
                  chunkMinY,
                  chunkMaxY,
                  edgeColumnCache,
                  bridgeCandidatePresent,
                  bridgeCandidateDeckY,
                  null,
                  null
               );
            } else {
               this.rasterizeRoadFeature(
                  road,
                  roadWidth,
                  blocksPerDegree,
                  extMinX,
                  extMinZ,
                  extSide,
                  chunkMinX,
                  chunkMinZ,
                  terrainSurfaces,
                  waterSurfaces,
                  waterFlags,
                  chunkMinY,
                  chunkMaxY,
                  edgeColumnCache,
                  candidatePresent,
                  candidateDeckY,
                  candidateMode,
                  candidateTunnelCarve
               );
            }
         }

         int classId = roadClass.ordinal() + 1;

         for (int index = 0; index < extArea; index++) {
            if (bridgeCandidatePresent[index]) {
               mergeBridgeOverlay(index, classId, bridgeCandidateDeckY[index], bridgeOverlayPresent, bridgeOverlayDeckY, bridgeOverlayClass);
            }
         }

         int[] placed = scratch.placed;
         int placedCount = 0;

         for (int indexx = 0; indexx < extArea; indexx++) {
            if (candidatePresent[indexx] && !blockedByHigherClass[indexx]) {
               resolvedClass[indexx] = (byte)classId;
               resolvedMode[indexx] = candidateMode[indexx];
               resolvedDeckY[indexx] = candidateDeckY[indexx];
               resolvedTunnelCarve[indexx] = candidateTunnelCarve[indexx];
               placed[placedCount++] = indexx;
            }
         }

         for (int i = 0; i < placedCount; i++) {
            int indexxx = placed[i];
            int localX = indexxx % extSide;
            int localZ = indexxx / extSide;

            for (int dz = -OSM_ROAD_CLASS_SEPARATION; dz <= OSM_ROAD_CLASS_SEPARATION; dz++) {
               int z = localZ + dz;
               if (z >= 0 && z < extSide) {
                  for (int dx = -OSM_ROAD_CLASS_SEPARATION; dx <= OSM_ROAD_CLASS_SEPARATION; dx++) {
                     int x = localX + dx;
                     if (x >= 0 && x < extSide) {
                        blockedByHigherClass[extIndex(x, z, extSide)] = true;
                     }
                  }
               }
            }
         }
      }
   }

   private void rasterizeRoadFeature(
      RoadFeature road,
      int roadWidth,
      double blocksPerDegree,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache,
      boolean[] candidatePresent,
      int[] candidateDeckY,
      byte[] candidateMode,
      boolean[] candidateTunnelCarve
   ) {
      int pointCount = road.pointCount();
      if (pointCount >= 2) {
         double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
         double startWorldX = road.lonAt(0) * blocksPerDegree;
         double startWorldZ = EarthProjection.latToBlockZ(road.latAt(0), worldScale);
         double previousX = startWorldX;
         double previousZ = startWorldZ;
         double endWorldX = startWorldX;
         double endWorldZ = startWorldZ;
         double totalLength = 0.0;

         for (int i = 1; i < pointCount; i++) {
            double currentX = road.lonAt(i) * blocksPerDegree;
            double currentZ = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
            double dx = currentX - previousX;
            double dz = currentZ - previousZ;
            double segmentLength = Math.sqrt(dx * dx + dz * dz);
            totalLength += segmentLength;
            previousX = currentX;
            previousZ = currentZ;
            endWorldX = currentX;
            endWorldZ = currentZ;
         }

         if (!(totalLength <= 1.0E-6)) {
            int tunnelStartSurface = 0;
            int tunnelEndSurface = 0;
            int bridgeStartSurface = 0;
            int bridgeEndSurface = 0;
            if (road.mode() == RoadMode.TUNNEL || road.mode() == RoadMode.BRIDGE) {
               EarthChunkGenerator.RoadColumnSample start = this.sampleRoadColumnForOverlay(
                  Mth.floor(startWorldX), Mth.floor(startWorldZ), chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
               );
               EarthChunkGenerator.RoadColumnSample end = this.sampleRoadColumnForOverlay(
                  Mth.floor(endWorldX), Mth.floor(endWorldZ), chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
               );
               if (road.mode() == RoadMode.TUNNEL) {
                  tunnelStartSurface = start.roadSurface();
                  tunnelEndSurface = end.roadSurface();
               } else {
                  bridgeStartSurface = start.roadSurface();
                  bridgeEndSurface = end.roadSurface();
               }
            }

            double halfWidth = Math.max(0.5, (roadWidth - 1) * 0.5);
            double radiusSq = halfWidth * halfWidth + 1.0E-6;
            double segmentStart = 0.0;
            double x1 = startWorldX;
            double z1 = startWorldZ;

            for (int i = 1; i < pointCount; i++) {
               double x2 = road.lonAt(i) * blocksPerDegree;
               double z2 = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
               double dx = x2 - x1;
               double dz = z2 - z1;
               double segmentLengthSq = dx * dx + dz * dz;
               if (segmentLengthSq <= 1.0E-6) {
                  x1 = x2;
                  z1 = z2;
               } else {
                  double segmentLength = Math.sqrt(segmentLengthSq);
                  int minX = Math.max(extMinX, Mth.floor(Math.min(x1, x2) - halfWidth - 1.0));
                  int maxX = Math.min(extMinX + extSide - 1, Mth.floor(Math.max(x1, x2) + halfWidth + 1.0));
                  int minZ = Math.max(extMinZ, Mth.floor(Math.min(z1, z2) - halfWidth - 1.0));
                  int maxZ = Math.min(extMinZ + extSide - 1, Mth.floor(Math.max(z1, z2) + halfWidth + 1.0));

                  for (int worldCellZ = minZ; worldCellZ <= maxZ; worldCellZ++) {
                     for (int worldCellX = minX; worldCellX <= maxX; worldCellX++) {
                        double t = ((worldCellX - x1) * dx + (worldCellZ - z1) * dz) / segmentLengthSq;
                        t = Mth.clamp(t, 0.0, 1.0);
                        double projectionX = x1 + t * dx;
                        double projectionZ = z1 + t * dz;
                        double distX = worldCellX - projectionX;
                        double distZ = worldCellZ - projectionZ;
                        double distanceSq = distX * distX + distZ * distZ;
                        if (!(distanceSq > radiusSq)) {
                           double station = segmentStart + t * segmentLength;
                           EarthChunkGenerator.RoadColumnSample column = this.sampleRoadColumnForOverlay(
                              worldCellX, worldCellZ, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                           );
                           boolean tunnelNeedsCarve = false;
                           int deckY;
                           if (road.mode() == RoadMode.BRIDGE) {
                              deckY = bridgeDeckYAtStation(
                                 station,
                                 totalLength,
                                 bridgeStartSurface,
                                 bridgeEndSurface,
                                 column.roadSurface(),
                                 road.bridgeLevel(),
                                 road.roadClass(),
                                 worldScale
                              );
                           } else if (road.mode() == RoadMode.TUNNEL) {
                              int requestedDeckY = tunnelDeckAtStation(station, totalLength, tunnelStartSurface, tunnelEndSurface);
                              requestedDeckY = Math.min(requestedDeckY, column.roadSurface());
                              tunnelNeedsCarve = column.terrainSurface() > requestedDeckY + 1;
                              deckY = tunnelNeedsCarve ? requestedDeckY : column.roadSurface();
                           } else {
                              deckY = column.roadSurface();
                           }

                           deckY = Mth.clamp(deckY, chunkMinY, chunkMaxY);
                           int extIndex = extIndex(worldCellX - extMinX, worldCellZ - extMinZ, extSide);
                           boolean replaceCandidate = candidateMode == null || candidateTunnelCarve == null
                              ? shouldReplaceBridgeCandidate(candidatePresent[extIndex], candidateDeckY[extIndex], deckY)
                              : shouldReplaceRoadCandidate(
                                 candidatePresent[extIndex],
                                 candidateDeckY[extIndex],
                                 candidateMode[extIndex],
                                 candidateTunnelCarve[extIndex],
                                 deckY,
                                 road.mode(),
                                 tunnelNeedsCarve
                              );
                           if (replaceCandidate) {
                              candidatePresent[extIndex] = true;
                              candidateDeckY[extIndex] = deckY;
                              if (candidateMode != null && candidateTunnelCarve != null) {
                                 candidateMode[extIndex] = (byte)(road.mode().ordinal() + 1);
                                 candidateTunnelCarve[extIndex] = tunnelNeedsCarve;
                              }
                           }
                        }
                     }
                  }

                  segmentStart += segmentLength;
                  x1 = x2;
                  z1 = z2;
               }
            }
         }
      }
   }

   private EarthChunkGenerator.RoadColumnSample sampleRoadColumnForOverlay(
      int worldX,
      int worldZ,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache
   ) {
      if (worldX >= chunkMinX && worldX < chunkMinX + CHUNK_SIDE && worldZ >= chunkMinZ && worldZ < chunkMinZ + CHUNK_SIDE) {
         int localX = worldX - chunkMinX;
         int localZ = worldZ - chunkMinZ;
         int index = chunkIndex(localX, localZ);
         return new EarthChunkGenerator.RoadColumnSample(
            terrainSurfaces[index], waterSurfaces[index], waterFlags[index] && waterSurfaces[index] > terrainSurfaces[index]
         );
      } else {
         long packed = packColumn(worldX, worldZ);
         EarthChunkGenerator.RoadColumnSample cached = (EarthChunkGenerator.RoadColumnSample)edgeColumnCache.get(packed);
         if (cached != null) {
            return cached;
         } else {
            WaterSurfaceResolver.WaterColumnData column = this.resolveAuxWaterColumn(worldX, worldZ);
            EarthChunkGenerator.RoadColumnSample sampled = new EarthChunkGenerator.RoadColumnSample(
               column.terrainSurface(), column.waterSurface(), column.hasWater() && column.waterSurface() > column.terrainSurface()
            );
            edgeColumnCache.put(packed, sampled);
            return sampled;
         }
      }
   }

   private static EarthChunkGenerator.RoadWidths resolveRoadWidths(double worldScale) {
      double factor = roadWidthFactorForScale(worldScale);

      return new EarthChunkGenerator.RoadWidths(
         widthForScale(RoadClass.MAIN.baseWidth(), factor),
         widthForScale(RoadClass.NORMAL.baseWidth(), factor),
         widthForScale(RoadClass.DIRT.baseWidth(), factor)
      );
   }

   private static int widthForScale(int baseWidth, double factor) {
      return Math.max(1, (int)Math.round(baseWidth * factor));
   }

   private static double roadWidthFactorForScale(double worldScale) {
      if (!(worldScale > 0.0)) {
         return 0.25;
      } else if (worldScale <= 1.0) {
         return 1.8;
      } else if (worldScale <= 5.0) {
         double t = (worldScale - 1.0) / 4.0;
         return Mth.lerp(Mth.clamp(t, 0.0, 1.0), 1.8, 1.0);
      } else if (worldScale <= 10.0) {
         double t = (worldScale - 5.0) / 5.0;
         return Mth.lerp(Mth.clamp(t, 0.0, 1.0), 1.0, 0.5);
      } else {
         return 0.25;
      }
   }

   private static int bridgeRiseAtStation(double station, double totalLength, int bridgeLevel) {
      int requestedRise = Math.max(0, bridgeLevel) * OSM_ROAD_BRIDGE_LEVEL_HEIGHT;
      requestedRise = Math.min(requestedRise, OSM_ROAD_BRIDGE_MAX_RISE);
      if (requestedRise > 0 && !(totalLength <= 1.0E-6)) {
         double maxRiseByLength = totalLength / (2.0 * OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL);
         int targetRise = Math.min(requestedRise, Math.max(0, (int)Math.floor(maxRiseByLength)));
         if (targetRise <= 0) {
            return 0;
         } else {
            double clampedStation = Mth.clamp(station, 0.0, totalLength);
            double rampLength = targetRise * OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL;
            double rise;
            if (totalLength >= rampLength * 2.0) {
               if (clampedStation < rampLength) {
                  rise = targetRise * (clampedStation / rampLength);
               } else if (clampedStation > totalLength - rampLength) {
                  rise = targetRise * ((totalLength - clampedStation) / rampLength);
               } else {
                  rise = targetRise;
               }
            } else {
               double half = totalLength * 0.5;
               if (half <= 1.0E-6) {
                  rise = targetRise;
               } else if (clampedStation <= half) {
                  rise = targetRise * (clampedStation / half);
               } else {
                  rise = targetRise * ((totalLength - clampedStation) / half);
               }
            }

            return Math.max(0, (int)Math.round(Mth.clamp(rise, 0.0, targetRise)));
         }
      } else {
         return 0;
      }
   }

   private static int bridgeDeckBaselineAtStation(double station, double totalLength, int startSurface, int endSurface) {
      return interpolateDeckAtStation(station, totalLength, startSurface, endSurface);
   }

   private static int bridgeDeckYAtStation(
      double station,
      double totalLength,
      int startSurface,
      int endSurface,
      int localRoadSurface,
      int bridgeLevel,
      RoadClass roadClass,
      double worldScale
   ) {
      int baseline = bridgeDeckBaselineAtStation(station, totalLength, startSurface, endSurface);
      int rise = bridgeRiseAtStation(station, totalLength, bridgeLevel);
      int clearance = bridgeClearanceAtStation(station, totalLength, roadClass, worldScale);
      return Math.max(baseline + rise, localRoadSurface + clearance);
   }

   private static int bridgeClearanceAtStation(double station, double totalLength, RoadClass roadClass, double worldScale) {
      int targetClearance = bridgeTargetClearanceBlocks(roadClass, worldScale);
      if (targetClearance <= 0 || totalLength <= 1.0E-6) {
         return 0;
      } else {
         double clampedStation = Mth.clamp(station, 0.0, totalLength);
         double rampLength = targetClearance * OSM_ROAD_BRIDGE_RAMP_HORIZONTAL_PER_VERTICAL;
         double clearance;
         if (totalLength >= rampLength * 2.0) {
            if (clampedStation < rampLength) {
               clearance = targetClearance * (clampedStation / rampLength);
            } else if (clampedStation > totalLength - rampLength) {
               clearance = targetClearance * ((totalLength - clampedStation) / rampLength);
            } else {
               clearance = targetClearance;
            }
         } else {
            double half = totalLength * 0.5;
            if (half <= 1.0E-6) {
               clearance = targetClearance;
            } else if (clampedStation <= half) {
               clearance = targetClearance * (clampedStation / half);
            } else {
               clearance = targetClearance * ((totalLength - clampedStation) / half);
            }
         }

         return Math.max(0, (int)Math.round(Mth.clamp(clearance, 0.0, targetClearance)));
      }
   }

   private static int bridgeTargetClearanceBlocks(RoadClass roadClass, double worldScale) {
      double safeScale = worldScale > 0.0 ? worldScale : 1.0;
      double clearanceMeters = switch (roadClass) {
         case MAIN -> 6.0;
         case NORMAL -> 5.0;
         case DIRT -> 3.0;
      };
      return Math.max(1, (int)Math.ceil(clearanceMeters / safeScale));
   }

   private static int tunnelDeckAtStation(double station, double totalLength, int startSurface, int endSurface) {
      return interpolateDeckAtStation(station, totalLength, startSurface, endSurface);
   }

   private static int interpolateDeckAtStation(double station, double totalLength, int startSurface, int endSurface) {
      if (totalLength <= 1.0E-6) {
         return startSurface;
      } else {
         double progress = Mth.clamp(station / totalLength, 0.0, 1.0);
         double interpolated = startSurface + (endSurface - startSurface) * progress;
         return (int)Math.round(interpolated);
      }
   }

   private static boolean shouldReplaceBridgeCandidate(boolean existingPresent, int existingDeckY, int newDeckY) {
      return !existingPresent || newDeckY > existingDeckY;
   }

   private static boolean shouldReplaceRoadCandidate(
      boolean existingPresent, int existingDeckY, byte existingModeId, boolean existingTunnelCarve, int newDeckY, RoadMode newMode, boolean newTunnelCarve
   ) {
      if (!existingPresent) {
         return true;
      } else if (newDeckY != existingDeckY) {
         return newDeckY > existingDeckY;
      } else {
         int existingModePriority = modePriority(existingModeId);
         if (newMode.priority() != existingModePriority) {
            return newMode.priority() > existingModePriority;
         } else {
            return newTunnelCarve != existingTunnelCarve ? newTunnelCarve : false;
         }
      }
   }

   private static int modePriority(byte modeId) {
      if (modeId <= 0) {
         return -1;
      } else {
         int index = modeId - 1;
         return index >= 0 && index < RoadMode.values().length ? RoadMode.values()[index].priority() : -1;
      }
   }

   private static void mergeBridgeOverlay(
      int index, int classId, int deckY, boolean[] bridgeOverlayPresent, int[] bridgeOverlayDeckY, byte[] bridgeOverlayClass
   ) {
      if (!bridgeOverlayPresent[index]) {
         bridgeOverlayPresent[index] = true;
         bridgeOverlayDeckY[index] = deckY;
         bridgeOverlayClass[index] = (byte)classId;
      } else {
         int existingDeck = bridgeOverlayDeckY[index];
         int existingClass = bridgeOverlayClass[index];
         if (deckY > existingDeck || deckY == existingDeck && classId < existingClass) {
            bridgeOverlayDeckY[index] = deckY;
            bridgeOverlayClass[index] = (byte)classId;
         }
      }
   }

   private void rasterizeBridgeSupports(
      List<RoadFeature> roads,
      int roadWidth,
      double blocksPerDegree,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int chunkMinY,
      int chunkMaxY,
      Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache,
      byte[] resolvedClass,
      int[] resolvedDeckY,
      boolean[] bridgeOverlayPresent,
      int[] bridgeOverlayDeckY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      boolean[] shaftPresent,
      int[] shaftBottomY,
      int[] shaftTopY,
      boolean[] capPresent,
      int[] capBottomY,
      int[] capTopY
   ) {
      if (roads.isEmpty() || roadWidth <= 0) {
         return;
      }

      double worldScale = EarthProjection.worldScaleFromBlocksPerDegree(blocksPerDegree);
      for (RoadFeature road : roads) {
         EarthChunkGenerator.RoadColumnSample startColumn = this.sampleRoadColumnForOverlay(
            Mth.floor(road.lonAt(0) * blocksPerDegree),
            Mth.floor(EarthProjection.latToBlockZ(road.latAt(0), worldScale)),
            chunkMinX,
            chunkMinZ,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            edgeColumnCache
         );
         EarthChunkGenerator.RoadColumnSample endColumn = this.sampleRoadColumnForOverlay(
            Mth.floor(road.lonAt(road.pointCount() - 1) * blocksPerDegree),
            Mth.floor(EarthProjection.latToBlockZ(road.latAt(road.pointCount() - 1), worldScale)),
            chunkMinX,
            chunkMinZ,
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            edgeColumnCache
         );
         int startSurface = startColumn.roadSurface();
         int endSurface = endColumn.roadSurface();
         BridgeSupportLayout.SupportStyle style = BridgeSupportLayout.styleFor(road.roadClass(), roadWidth);
         BridgeSupportLayout.forEachSupport(road, blocksPerDegree, worldScale, roadWidth, placement -> {
            IntArrayList capCells = new IntArrayList();
            IntArrayList[] shaftCells = new IntArrayList[style.shaftCount()];
            int[] minTerrain = new int[style.shaftCount()];
            int[] maxTerrain = new int[style.shaftCount()];

            for (int i = 0; i < style.shaftCount(); i++) {
               shaftCells[i] = new IntArrayList();
               minTerrain[i] = Integer.MAX_VALUE;
               maxTerrain[i] = Integer.MIN_VALUE;
            }

            EarthChunkGenerator.RoadColumnSample supportCenterColumn = this.sampleRoadColumnForOverlay(
               Mth.floor(placement.centerX()),
               Mth.floor(placement.centerZ()),
               chunkMinX,
               chunkMinZ,
               terrainSurfaces,
               waterSurfaces,
               waterFlags,
               edgeColumnCache
            );
            int deckY = bridgeDeckYAtStation(
               placement.station(),
               placement.totalLength(),
               startSurface,
               endSurface,
               supportCenterColumn.roadSurface(),
               road.bridgeLevel(),
               road.roadClass(),
               worldScale
            );
            int capTop = Math.min(chunkMaxY, deckY - 1);
            int capBottom = Math.max(chunkMinY, capTop - style.capThickness() + 1);
            if (capTop < capBottom) {
               return;
            }

            double radius = style.maxFootprintRadius() + 1.0;
            int minLocalX = Mth.clamp((int)Math.floor(placement.centerX() - radius) - extMinX, 0, extSide - 1);
            int maxLocalX = Mth.clamp((int)Math.ceil(placement.centerX() + radius) - extMinX, 0, extSide - 1);
            int minLocalZ = Mth.clamp((int)Math.floor(placement.centerZ() - radius) - extMinZ, 0, extSide - 1);
            int maxLocalZ = Mth.clamp((int)Math.ceil(placement.centerZ() + radius) - extMinZ, 0, extSide - 1);

            for (int localZ = minLocalZ; localZ <= maxLocalZ; localZ++) {
               int worldZ = extMinZ + localZ;

               for (int localX = minLocalX; localX <= maxLocalX; localX++) {
                  int worldX = extMinX + localX;
                  double deltaX = worldX - placement.centerX();
                  double deltaZ = worldZ - placement.centerZ();
                  double along = deltaX * placement.tangentX() + deltaZ * placement.tangentZ();
                  double across = deltaX * placement.normalX() + deltaZ * placement.normalZ();
                  int index = extIndex(localX, localZ, extSide);
                  if (Math.abs(along) <= style.capHalfAlong() && Math.abs(across) <= style.capHalfAcross()) {
                     capCells.add(index);
                  }

                  for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
                     double shaftAcross = style.shaftCount() == 1 ? 0.0 : (shaftIndex == 0 ? -style.shaftOffset() : style.shaftOffset());
                     if (Math.abs(along) <= style.shaftHalfAlong() && Math.abs(across - shaftAcross) <= style.shaftHalfAcross()) {
                        EarthChunkGenerator.RoadColumnSample column = this.sampleRoadColumnForOverlay(
                           worldX, worldZ, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                        );
                        int terrainSurface = column.terrainSurface();
                        if (!BridgeSupportRoadMask.overlapsRoad(
                           index, terrainSurface, capBottom - 1, resolvedClass, resolvedDeckY, bridgeOverlayPresent, bridgeOverlayDeckY
                        )) {
                           shaftCells[shaftIndex].add(index);
                           minTerrain[shaftIndex] = Math.min(minTerrain[shaftIndex], terrainSurface);
                           maxTerrain[shaftIndex] = Math.max(maxTerrain[shaftIndex], terrainSurface);
                        }
                     }
                  }
               }
            }

            // Nearby roads only remove directly overlapping support columns instead of suppressing the whole support.
            BridgeSupportRoadMask.retainRoadFreeSupportCells(
               capCells, index -> capBottom, capTop, resolvedClass, resolvedDeckY, bridgeOverlayPresent, bridgeOverlayDeckY
            );
            if (capCells.isEmpty()) {
               return;
            }

            int[] supportTops = new int[style.shaftCount()];
            boolean[] activeShafts = new boolean[style.shaftCount()];
            int activeShaftCount = 0;
            int requiredClearance = Math.max(1, Math.min(style.minClearance(), bridgeTargetClearanceBlocks(road.roadClass(), worldScale)));
            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               if (shaftCells[shaftIndex].isEmpty() || minTerrain[shaftIndex] == Integer.MAX_VALUE || maxTerrain[shaftIndex] == Integer.MIN_VALUE) {
                  continue;
               }

               if (capBottom - maxTerrain[shaftIndex] < requiredClearance) {
                  continue;
               }

               supportTops[shaftIndex] = capBottom - 1;
               if (supportTops[shaftIndex] < minTerrain[shaftIndex]) {
                  continue;
               }

               activeShafts[shaftIndex] = true;
               activeShaftCount++;
            }

            if (activeShaftCount == 0) {
               return;
            }

            for (int i = 0; i < capCells.size(); i++) {
               int index = capCells.getInt(i);
               if (this.bridgeSupportConflictsBuilding(
                  index,
                  capBottom,
                  capTop,
                  extMinX,
                  extMinZ,
                  extSide,
                  chunkMinX,
                  chunkMinZ,
                  preparedBuildings
               )) {
                  return;
               }
            }

            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               if (!activeShafts[shaftIndex]) {
                  continue;
               }

               for (int i = 0; i < shaftCells[shaftIndex].size(); i++) {
                  int index = shaftCells[shaftIndex].getInt(i);
                  int localBottom = this.bridgeSupportTerrainBottom(
                     index, extMinX, extMinZ, extSide, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                  );
                  if (supportTops[shaftIndex] < localBottom) {
                     continue;
                  }

                  if (this.bridgeSupportConflictsBuilding(
                     index,
                     localBottom,
                     supportTops[shaftIndex],
                     extMinX,
                     extMinZ,
                     extSide,
                     chunkMinX,
                     chunkMinZ,
                     preparedBuildings
                  )) {
                     return;
                  }
               }
            }

            for (int i = 0; i < capCells.size(); i++) {
               mergeBridgeSupportColumn(capCells.getInt(i), capBottom, capTop, capPresent, capBottomY, capTopY);
            }

            for (int shaftIndex = 0; shaftIndex < style.shaftCount(); shaftIndex++) {
               if (!activeShafts[shaftIndex]) {
                  continue;
               }

               for (int i = 0; i < shaftCells[shaftIndex].size(); i++) {
                  int index = shaftCells[shaftIndex].getInt(i);
                  int localBottom = this.bridgeSupportTerrainBottom(
                     index, extMinX, extMinZ, extSide, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
                  );
                  if (supportTops[shaftIndex] < localBottom) {
                     continue;
                  }

                  mergeBridgeSupportColumn(
                     index,
                     localBottom,
                     supportTops[shaftIndex],
                     shaftPresent,
                     shaftBottomY,
                     shaftTopY
                  );
               }
            }
         });
      }
   }

   private boolean bridgeSupportConflictsBuilding(
      int extIndex,
      int bottomY,
      int topY,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      if (topY < bottomY) {
         return false;
      }

      if (preparedBuildings != null) {
         int localX = extIndex % extSide;
         int localZ = extIndex / extSide;
         int worldX = extMinX + localX;
         int worldZ = extMinZ + localZ;
         int chunkLocalX = worldX - chunkMinX;
         int chunkLocalZ = worldZ - chunkMinZ;
         if (chunkLocalX >= 0
            && chunkLocalX < CHUNK_SIDE
            && chunkLocalZ >= 0
            && chunkLocalZ < CHUNK_SIDE
            && preparedBuildings.intersectsSpan(chunkLocalX, chunkLocalZ, bottomY, topY)) {
            return true;
         }
      }

      return false;
   }

   private RoadColumnSample bridgeSupportColumnSample(
      int extIndex,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      Long2ObjectOpenHashMap<RoadColumnSample> edgeColumnCache
   ) {
      int localX = extIndex % extSide;
      int localZ = extIndex / extSide;
      int worldX = extMinX + localX;
      int worldZ = extMinZ + localZ;
      return this.sampleRoadColumnForOverlay(worldX, worldZ, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache);
   }

   private int bridgeSupportTerrainBottom(
      int extIndex,
      int extMinX,
      int extMinZ,
      int extSide,
      int chunkMinX,
      int chunkMinZ,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      Long2ObjectOpenHashMap<RoadColumnSample> edgeColumnCache
   ) {
      return this.bridgeSupportColumnSample(
            extIndex, extMinX, extMinZ, extSide, chunkMinX, chunkMinZ, terrainSurfaces, waterSurfaces, waterFlags, edgeColumnCache
         )
         .terrainSurface();
   }

   private static void mergeBridgeSupportColumn(int index, int bottomY, int topY, boolean[] present, int[] bottoms, int[] tops) {
      if (topY < bottomY) {
         return;
      }

      if (!present[index]) {
         present[index] = true;
         bottoms[index] = bottomY;
         tops[index] = topY;
      } else {
         bottoms[index] = Math.min(bottoms[index], bottomY);
         tops[index] = Math.max(tops[index], topY);
      }
   }

   private static int extIndex(int localX, int localZ, int side) {
      return localZ * side + localX;
   }

   private static long packColumn(int worldX, int worldZ) {
      return (long)worldX << 32 ^ worldZ & 4294967295L;
   }


   private static BlockState roadStateForClass(RoadClass roadClass) {
      return switch (roadClass) {
         case MAIN -> ROAD_MAIN_STATE;
         case NORMAL -> ROAD_NORMAL_STATE;
         case DIRT -> ROAD_DIRT_STATE;
      };
   }


   private static RoadClass roadClassFromId(int classId) {
      return switch (classId) {
         case 1 -> RoadClass.MAIN;
         case 2 -> RoadClass.NORMAL;
         default -> RoadClass.DIRT;
      };
   }

   private static int roadClassId(RoadClass roadClass) {
      return switch (roadClass) {
         case MAIN -> 1;
         case NORMAL -> 2;
         case DIRT -> 3;
      };
   }

   private static int roadWidthForClass(RoadClass roadClass, EarthChunkGenerator.RoadWidths widths) {
      return switch (roadClass) {
         case MAIN -> widths.main();
         case NORMAL -> widths.normal();
         case DIRT -> widths.dirt();
      };
   }

   private static int roadLightSpacingBlocks(double worldScale) {
      if (!(worldScale > 0.0)) {
         return 40;
      } else {
         return Mth.clamp((int)Math.round(ROAD_LIGHT_BASE_SPACING_METERS / worldScale), 3, 40);
      }
   }

   private static int roadLightMinimumSpacingBlocks(int spacingBlocks) {
      return Math.max(3, (int)Math.round(spacingBlocks * 0.75));
   }

   private static int roadLightFenceCount(double worldScale) {
      if (worldScale <= 3.0) {
         return 3;
      } else {
         return worldScale <= 8.0 ? 2 : 1;
      }
   }

   private static EarthChunkGenerator.SampledRoadStation sampleRoadStation(
      double[] worldXs, double[] worldZs, double[] segmentStarts, double[] segmentLengths, double station
   ) {
      for (int i = 0; i < segmentLengths.length; i++) {
         double segmentLength = segmentLengths[i];
         if (!(segmentLength <= 1.0E-6)) {
            double segmentStart = segmentStarts[i];
            double segmentEnd = segmentStart + segmentLength;
            if (station <= segmentEnd + 1.0E-6 || i == segmentLengths.length - 1) {
               double dx = worldXs[i + 1] - worldXs[i];
               double dz = worldZs[i + 1] - worldZs[i];
               double t = Mth.clamp((station - segmentStart) / segmentLength, 0.0, 1.0);
               return new EarthChunkGenerator.SampledRoadStation(worldXs[i] + dx * t, worldZs[i] + dz * t, dx / segmentLength, dz / segmentLength);
            }
         }
      }

      return null;
   }

   private static EarthChunkGenerator.RoadLightAnchor findRoadLightAnchor(
      EarthChunkGenerator.SampledRoadStation sampled,
      boolean placeLeft,
      int roadWidth,
      int roadClassId,
      int roadModeId,
      int chunkMinX,
      int chunkMinZ,
      byte[] chunkRoadClass,
      byte[] chunkRoadMode,
      int[] chunkRoadDeckY
   ) {
      double normalX = placeLeft ? -sampled.tangentZ() : sampled.tangentZ();
      double normalZ = placeLeft ? sampled.tangentX() : -sampled.tangentX();
      double scanRadius = Math.max(2.0, roadWidth + 2.0);
      double alongTolerance = Math.max(1.25, roadWidth * 0.45);
      int minLocalX = Math.max(0, quantizeRoadCoordinate(sampled.worldX() - scanRadius) - chunkMinX);
      int maxLocalX = Math.min(CHUNK_MASK, quantizeRoadCoordinate(sampled.worldX() + scanRadius) - chunkMinX);
      int minLocalZ = Math.max(0, quantizeRoadCoordinate(sampled.worldZ() - scanRadius) - chunkMinZ);
      int maxLocalZ = Math.min(CHUNK_MASK, quantizeRoadCoordinate(sampled.worldZ() + scanRadius) - chunkMinZ);
      EarthChunkGenerator.RoadLightAnchor bestAnchor = null;
      double bestLateral = Double.NEGATIVE_INFINITY;
      double bestAlong = Double.POSITIVE_INFINITY;
      double bestDistanceSq = Double.POSITIVE_INFINITY;
      double minLateral = Double.POSITIVE_INFINITY;
      double maxLateral = Double.NEGATIVE_INFINITY;

      for (int localZ = minLocalZ; localZ <= maxLocalZ; localZ++) {
         for (int localX = minLocalX; localX <= maxLocalX; localX++) {
            int index = chunkIndex(localX, localZ);
            if (chunkRoadClass[index] == roadClassId && chunkRoadMode[index] == roadModeId) {
               double dx = chunkMinX + localX + 0.5 - sampled.worldX();
               double dz = chunkMinZ + localZ + 0.5 - sampled.worldZ();
               double along = dx * sampled.tangentX() + dz * sampled.tangentZ();
               if (!(Math.abs(along) > alongTolerance)) {
                  double lateral = dx * normalX + dz * normalZ;
                  minLateral = Math.min(minLateral, lateral);
                  maxLateral = Math.max(maxLateral, lateral);
                  if (!(lateral <= 0.05)) {
                     double distanceSq = dx * dx + dz * dz;
                     double absAlong = Math.abs(along);
                     if (lateral > bestLateral + 1.0E-6
                        || Math.abs(lateral - bestLateral) <= 1.0E-6 && absAlong < bestAlong - 1.0E-6
                        || Math.abs(lateral - bestLateral) <= 1.0E-6 && Math.abs(absAlong - bestAlong) <= 1.0E-6 && distanceSq < bestDistanceSq) {
                        bestLateral = lateral;
                        bestAlong = absAlong;
                        bestDistanceSq = distanceSq;
                        bestAnchor = new EarthChunkGenerator.RoadLightAnchor(localX, localZ, chunkRoadDeckY[index], index);
                     }
                  }
               }
            }
         }
      }

      if (bestAnchor == null || minLateral == Double.POSITIVE_INFINITY || maxLateral == Double.NEGATIVE_INFINITY) {
         return null;
      }

      double span = maxLateral - minLateral;
      if (span < 0.75) {
         return null;
      }

      if (span > roadWidth + 0.75) {
         return null;
      }

      return bestAnchor;
   }

   private static boolean hasNearbyPreparedRoadLight(
      int localX, int localZ, int minSpacingBlocks, EarthChunkGenerator.PreparedChunkRoadLights preparedRoadLights
   ) {
      if (preparedRoadLights == null || preparedRoadLights.isEmpty()) {
         return false;
      } else {
         int minSpacingSq = minSpacingBlocks * minSpacingBlocks;

         for (EarthChunkGenerator.PreparedRoadLight light : preparedRoadLights.lights()) {
            int dx = light.localX() - localX;
            int dz = light.localZ() - localZ;
            if (dx * dx + dz * dz < minSpacingSq) {
               return true;
            }
         }

         return false;
      }
   }

   private static boolean intersectsRoadLightBridgeSupport(
      int localX,
      int localZ,
      int minY,
      int maxY,
      boolean[] bridgeSupportShaftPresent,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      boolean[] bridgeSupportCapPresent,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY
   ) {
      int index = chunkIndex(localX, localZ);
      return bridgeSupportShaftPresent[index] && spansOverlap(minY, maxY, bridgeSupportShaftBottomY[index], bridgeSupportShaftTopY[index])
         || bridgeSupportCapPresent[index] && spansOverlap(minY, maxY, bridgeSupportCapBottomY[index], bridgeSupportCapTopY[index]);
   }

   private static boolean spansOverlap(int minY, int maxY, int otherMinY, int otherMaxY) {
      return maxY >= otherMinY && minY <= otherMaxY;
   }

   private static int quantizeRoadCoordinate(double value) {
      return Mth.floor(value + 0.5);
   }

   private static Direction dominantHorizontalDirection(double tangentX, double tangentZ) {
      if (Math.abs(tangentX) >= Math.abs(tangentZ)) {
         return tangentX >= 0.0 ? Direction.EAST : Direction.WEST;
      } else {
         return tangentZ >= 0.0 ? Direction.SOUTH : Direction.NORTH;
      }
   }

   private static BlockState roadLightTrapdoorState(Direction facing) {
      return (BlockState)ROAD_LIGHT_TRAPDOOR_BASE_STATE.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
   }

   private static boolean isRoadDeckState(BlockState state) {
      return state.is(Blocks.CONCRETE.gray()) || state.is(Blocks.DYED_TERRACOTTA.cyan()) || state.is(Blocks.DIRT_PATH);
   }

   private static boolean isRoadLightReplaceable(BlockState state) {
      return state.isAir()
         || state.is(Blocks.SNOW)
         || state.is(Blocks.POWDER_SNOW)
         || state.getFluidState().isEmpty() && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
   }

   private static boolean isTunnelCarveReplaceable(BlockState state) {
      return isReplaceableCaveBlock(state) && !isRoadDeckState(state) && !state.is(BRIDGE_SUPPORT_SHAFT_STATE.getBlock()) && !state.is(BRIDGE_SUPPORT_CAP_STATE.getBlock());
   }

   private static boolean[] computeFloodGuardColumns(boolean[] waterFlags) {
      boolean[] result = new boolean[CHUNK_AREA];

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            boolean nearWater = false;

            for (int dz = -2; dz <= 2 && !nearWater; dz++) {
               int z = localZ + dz;
               if (z >= 0 && z < CHUNK_SIDE) {
                  for (int dx = -2; dx <= 2; dx++) {
                     int x = localX + dx;
                     if (x >= 0 && x < CHUNK_SIDE && waterFlags[chunkIndex(x, z)]) {
                        nearWater = true;
                        break;
                     }
                  }
               }
            }

            result[chunkIndex(localX, localZ)] = nearWater;
         }
      }

      return result;
   }

   private static boolean isReplaceableCaveBlock(BlockState state) {
      return isSolidCaveAnchor(state) && !state.is(Blocks.BEDROCK);
   }

   private static boolean isSolidCaveAnchor(BlockState state) {
      return !state.isAir() && state.getFluidState().isEmpty() && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
   }

   private static int chunkIndex(int localX, int localZ) {
      return localZ * CHUNK_SIDE + localX;
   }

   @SuppressWarnings("unchecked")
   private static Holder<Biome>[] newBiomeCache(int size) {
      return (Holder<Biome>[])new Holder[size];
   }

   private void carveStructureClearanceVolumes(StructureManager structures, ChunkAccess chunk) {
      List<StructureStart> starts = structures.startsForStructure(
         chunk.getPos(), structure -> shouldApplyStructureTerrainAdjustment(structure.terrainAdaptation())
      );
      if (!starts.isEmpty()) {
         ChunkPos pos = chunk.getPos();
         int chunkMinX = pos.getMinBlockX();
         int chunkMinZ = pos.getMinBlockZ();
         int chunkMaxX = chunkMinX + 15;
         int chunkMaxZ = chunkMinZ + 15;
         int chunkMinY = chunk.getMinY();
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
         MutableBlockPos cursor = new MutableBlockPos();

         for (StructureStart start : starts) {
            if (start != null && start.isValid()) {
               for (StructurePiece piece : start.getPieces()) {
                 BoundingBox box = piece.getBoundingBox();
                 if (box.intersects(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
                     int centerX = box.minX() + box.maxX() >> 1;
                     int centerZ = box.minZ() + box.maxZ() >> 1;
                     int terrainSurface = this.resolveAuxWaterColumn(centerX, centerZ).terrainSurface();
                     if (box.maxY() <= terrainSurface - 20) {
                        int coreMinX = box.minX() - 1;
                        int coreMaxX = box.maxX() + 1;
                        int coreMinZ = box.minZ() - 1;
                        int coreMaxZ = box.maxZ() + 1;
                        int coreMinY = box.minY() - 0;
                        int coreMaxY = box.maxY() + 0;
                        int minX = Math.max(chunkMinX, coreMinX - 6);
                        int maxX = Math.min(chunkMaxX, coreMaxX + 6);
                        int minZ = Math.max(chunkMinZ, coreMinZ - 6);
                        int maxZ = Math.min(chunkMaxZ, coreMaxZ + 6);
                        int minY = Math.max(chunkMinY + 1, coreMinY - 0);
                        int maxY = Math.min(chunkMaxY - 1, coreMaxY + 4);
                        if (maxY >= minY && maxX >= minX && maxZ >= minZ) {
                           for (int z = minZ; z <= maxZ; z++) {
                              for (int x = minX; x <= maxX; x++) {
                                 for (int y = minY; y <= maxY; y++) {
                                    double nx = axisDistanceNormalized(x, coreMinX, coreMaxX, 6);
                                    double nz = axisDistanceNormalized(z, coreMinZ, coreMaxZ, 6);
                                    double ny = axisDistanceNormalized(y, coreMinY, coreMaxY, 0, 4);
                                    double distance = Math.sqrt(nx * nx + ny * ny + nz * nz);
                                    double threshold = 1.0 + this.structureClearanceNoiseJitter(x, y, z) * 0.22;
                                    if (!(distance > threshold)) {
                                       cursor.set(x, y, z);
                                       BlockState state = chunk.getBlockState(cursor);
                                       if (isReplaceableCaveBlock(state)) {
                                          chunk.setBlockState(cursor, CAVE_AIR_STATE);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private double structureClearanceNoiseJitter(int x, int y, int z) {
      long seed = seedFromCoords(x, y, z) ^ this.worldSeed ^ 7951840804584193857L;
      double t = Math.floorMod(seed, 2048L) / 2047.0;
      return t * 2.0 - 1.0;
   }

   private static double axisDistanceNormalized(int value, int coreMin, int coreMax, int shellRadius) {
      if (value < coreMin) {
         return (double)(coreMin - value) / Math.max(1, shellRadius);
      } else {
         return value > coreMax ? (double)(value - coreMax) / Math.max(1, shellRadius) : 0.0;
      }
   }

   private static double axisDistanceNormalized(int value, int coreMin, int coreMax, int shellRadiusBelow, int shellRadiusAbove) {
      if (value < coreMin) {
         return shellRadiusBelow <= 0 ? Double.POSITIVE_INFINITY : (double)(coreMin - value) / shellRadiusBelow;
      } else if (value > coreMax) {
         return shellRadiusAbove <= 0 ? Double.POSITIVE_INFINITY : (double)(value - coreMax) / shellRadiusAbove;
      } else {
         return 0.0;
      }
   }

   private static boolean shouldApplyStructureTerrainAdjustment(TerrainAdjustment adjustment) {
      return adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX;
   }

   private static int minSurfaceHeight(int[] terrainSurfaces) {
      int min = Integer.MAX_VALUE;

      for (int surface : terrainSurfaces) {
         if (surface < min) {
            min = surface;
         }
      }

      return min == Integer.MAX_VALUE ? 0 : min;
   }

   private EarthChunkGenerator.HeightGridBuildResult buildHeightGrid(
      ChunkPos pos, int step, int gridSize, boolean allowCacheReuse, boolean useLocalTerrainInputs
   ) {
      int[] heightGrid = new int[gridSize * gridSize];
      int cacheHits = 0;
      int cacheMisses = 0;
      boolean reusableLayout = allowCacheReuse && isReusableHeightGridLayout(step, gridSize);
      if (reusableLayout) {
         Arrays.fill(heightGrid, Integer.MIN_VALUE);
         cacheHits = this.heightGridCache.copyOverlaps(pos, step, gridSize, heightGrid, false);
      }

      int gridMinX = pos.getMinBlockX() - step;
      int gridMinZ = pos.getMinBlockZ() - step;
      // Multi-threaded per-column sampling: the columns are independent, each writing its own heightGrid slot;
      // the sampling pipeline (elevation source / tile cache / water resolution) is designed for concurrency anyway (chunks are generated in parallel).
      // On a 4-core machine this section is roughly divided by the core count; the miss counts are aggregated with AtomicInteger.
      java.util.concurrent.atomic.AtomicInteger missCounter =
         new java.util.concurrent.atomic.AtomicInteger(cacheMisses);
      // 2026-09-26: cells that genuinely "were not sampled" must be **counted separately**,
      // because the old miss count equalled the number of cells needing a sample (completely unrelated to "missing tiles"),
      // so it could not tell whether the grid was good enough to cache as an authoritative result
      java.util.concurrent.atomic.AtomicInteger unresolvedCounter = new java.util.concurrent.atomic.AtomicInteger();
      java.util.stream.IntStream.range(0, gridSize).parallel().forEach(dz -> {
         int worldZ = gridMinZ + dz;
         int row = dz * gridSize;

         for (int dx = 0; dx < gridSize; dx++) {
            int index = row + dx;
            if (!reusableLayout || heightGrid[index] == Integer.MIN_VALUE) {
               int worldX = gridMinX + dx;
               // Use the "report faithfully" version: no data -> Integer.MIN_VALUE (the miss sentinel)
               // The old code used `sampleSurfaceHeight` -- it turned NaN into `heightOffset` (sea level),
               // so fake heights entered the cache and `copyOverlaps` then copied them to 8 neighbours => a square plateau across 3x3 chunks
               int sampled = this.sampleSurfaceHeightForHeightGrid(worldX, worldZ, useLocalTerrainInputs);
               if (sampled == Integer.MIN_VALUE) {
                  unresolvedCounter.incrementAndGet();
               }

               heightGrid[index] = sampled;
               missCounter.incrementAndGet();
            }
         }
      });
      cacheMisses = missCounter.get();
      int unresolved = unresolvedCounter.get();

      if (reusableLayout) {
         // 2026-09-26: a grid that still holds "not sampled" cells **must not be treated as authoritative**,
         // because `copyOverlaps` only reuses entries with `approximate == false`,
         // so marking a grid that contains `Integer.MIN_VALUE` as authoritative would propagate the holes to 8 neighbouring chunks,
         // -- marking it approximate means it is only treated as a hint and never propagates
         this.heightGridCache.put(pos, step, gridSize, heightGrid, unresolved > 0);
      }

      return new EarthChunkGenerator.HeightGridBuildResult(heightGrid, cacheHits, cacheMisses);
   }

   private EarthChunkGenerator.TerrainShellHeightGridResult buildTerrainShellHeightGrid(ChunkPos pos, int step, int gridSize, boolean allowCacheReuse) {
      int[] heightGrid = new int[gridSize * gridSize];
      Arrays.fill(heightGrid, Integer.MIN_VALUE);
      int cacheHits = 0;
      boolean reusableLayout = allowCacheReuse && isReusableHeightGridLayout(step, gridSize);
      if (reusableLayout) {
         cacheHits = this.heightGridCache.copyOverlaps(pos, step, gridSize, heightGrid, true);
      }

      int initialMisses = 0;
      int gridMinX = pos.getMinBlockX() - step;
      int gridMinZ = pos.getMinBlockZ() - step;
      for (int dz = 0; dz < gridSize; dz++) {
         int worldZ = gridMinZ + dz;
         int row = dz * gridSize;

         for (int dx = 0; dx < gridSize; dx++) {
            int index = row + dx;
            if (heightGrid[index] == Integer.MIN_VALUE) {
               int worldX = gridMinX + dx;
               int sampled = this.sampleSurfaceHeightMemoryOnly(worldX, worldZ);
               if (sampled == Integer.MIN_VALUE && SHELL_DATA_ESCALATION) {
                  // 2026-09-26: not in memory != not in the data
                  // first fall back to the full DEM chain read-only from disk, then to "network allowed" -- never give up right here.
                  // giving up means fillMissingTerrainShellHeights lays seaLevel / the single known sample
                  // across all 256 columns of the chunk -- on a real machine that is exactly the "sometimes entirely concave, sometimes entirely convex" blocky terrain.
                  sampled = this.escalateShellHeight(worldX, worldZ);
               }
               if (sampled != Integer.MIN_VALUE) {
                  heightGrid[index] = sampled;
               } else {
                  initialMisses++;
               }
            }
         }
      }

      boolean usedFallback = initialMisses > 0;
      if (usedFallback) {
         // 2026-09-29: **fetch the data back first, then talk about fallbacks**
         // Previously it was "unavailable -> invent a height and flatten the chunk", so the Tibetan plateau (4150) was filled with sea level (64),
         // more than 4000 blocks off from its neighbours => a screen full of vertical cliffs (player screenshot).
         // Now: when data is missing it **downloads the tile synchronously**, then **resamples**,
         // and the fallback (invention) is left with exactly one case, "the network really cannot get it".
         int remainingMisses = this.repairShellHeightsByBlockingFetch(pos, step, gridSize, heightGrid, initialMisses);
         if (remainingMisses == 0) {
            initialMisses = 0;
            usedFallback = false;
         }
      }

      if (usedFallback) {
         this.fillMissingTerrainShellHeights(heightGrid, gridSize, step);
      }

      // 2026-09-26: a **fallback-filled** height grid no longer enters the cache
      // Why: usedFallback=true means some columns of this chunk are "invented" (fillMissingTerrainShellHeights
      // fills sea level / neighbour interpolation). The old code cached such a grid as approximate, and the shell path's copyOverlaps(..., true)
      // **allows** approximate grids to be copied to neighbours -- so "one chunk is missing tiles" became "a whole ring of chunks is missing tiles",
      // which on a real machine is a **blocky bump spreading wider and wider / a checkerboard**.
      // Now: an invented grid is **used inside this chunk only** and never propagates; clean grids (real elevation) are still reused.
      if (reusableLayout && !usedFallback) {
         this.heightGridCache.put(pos, step, gridSize, heightGrid, false);
      }

      return new EarthChunkGenerator.TerrainShellHeightGridResult(heightGrid, cacheHits, initialMisses, usedFallback);
   }

   /**
    * 2026-09-29: when an elevation tile is missing, **backfill it synchronously and resample** -- never "invent a height and flatten"
    *
    * <p>A chunk needs only **4 to 5 tiles** (the sample points cover a 24x24 area),
    * so the "backfill when missing" cost is a **one-off**, and **all 576 points of the chunk hit afterwards**.
    *
    * @return how many points are still missing after the backfill; {@code 0} means the whole chunk got **real elevation**
    */
   /**
    * 2026-09-29: convert a **block span** into a **tile radius**
    *
    * <p><b>Why this method must exist</b>: the {@code radius} of {@code prefetchTilesBlocking} is
    * <b>measured in tiles</b>, while the span of the sampling grid is <b>measured in blocks</b>; the two differ by one {@code worldScale} times the tile resolution
    * -- passing the half width in blocks directly resulted in <b>(2 x 52 + 1)^2 = 11025 tiles</b> being poured into the download pool
    * (the agent's measurement reported 13, i.e. 729 tiles; this call site is actually worse).
    *
    * <p><b>Algorithm</b>: at the current zoom one tile covers {@code tileBlocks} of about 614 / worldScale blocks
    * (614 m is about 256 pixels of web mercator z15 times 2.4 m per pixel)
    * => the tile radius needed to cover {@code spanBlocks} blocks = {@code ceil(span / tileBlocks) + 1}
    * (+1 is insurance: when a sample point sits on a tile boundary, that edge half has to be fetched as well)
    *
    * <p>When worldScale is unknown or absurd, **fall back to a small radius** -- prefetching a few tiles too few and letting it
    * retry is far better than pouring ten thousand tiles in at once.
    */
   private static int elevationTileRadiusForSpan(int spanBlocks, double worldScale) {
      if (!(worldScale > 0.0) || spanBlocks <= 0) {
         return 1;
      }

      double tileBlocks = 614.0 / worldScale;
      if (!(tileBlocks > 1.0)) {
         return 1;
      }

      int needed = (int) Math.ceil(spanBlocks / tileBlocks) + 1;
      // The cap is only a sanity guard: even with absurd arguments, (2r+1)^2 must not explode
      return Math.max(1, Math.min(needed, 8));
   }

   /** The tile radius is computed by {@link #elevationTileRadiusForSpan}; this method only calls it. */
   private int repairShellHeightsByBlockingFetch(ChunkPos pos, int step, int gridSize, int[] heightGrid, int initialMisses) {
      // Only a "patchy" shortage is worth a synchronous download -- one or two stray points are usually samples that landed on a tile edge
      if (initialMisses * 5 < heightGrid.length) {
         return this.resampleMissingShellHeights(pos, step, gridSize, heightGrid, initialMisses);
      }

      try {
         int gridMinX = pos.getMinBlockX() - step;
         int gridMinZ = pos.getMinBlockZ() - step;
         ELEVATION_SOURCE.prefetchTilesBlocking(
            (double)(gridMinX + (gridSize >> 1)),
            (double)(gridMinZ + (gridSize >> 1)),
            this.settings.worldScale(),
            this.elevationTileRadiusForSpan(gridSize * step, this.settings.worldScale()),
            this.settings.demSelection(),
            this.settings.worldScale()
         );
         EarthChunkGenerator.TerrainShellTerrainPerf.recordShellBlockingRepair();
         return this.resampleMissingShellHeights(pos, step, gridSize, heightGrid, initialMisses);
      } catch (RuntimeException error) {
         // If the backfill fails (network / rate limit) the fallback still runs, but it is **reported faithfully** and never silent
         EarthChunkGenerator.TerrainShellTerrainPerf.recordShellBlockingRepairFailure();
         return initialMisses;
      }
   }

   /** Once the tiles are in place, the points that were still missing are resampled once more, bit for bit through the original conversion. */
   private int resampleMissingShellHeights(ChunkPos pos, int step, int gridSize, int[] heightGrid, int expectedMisses) {
      int gridMinX = pos.getMinBlockX() - step;
      int gridMinZ = pos.getMinBlockZ() - step;
      int remaining = 0;
      for (int dz = 0; dz < gridSize; dz++) {
         int row = dz * gridSize;
         for (int dx = 0; dx < gridSize; dx++) {
            int index = row + dx;
            if (heightGrid[index] != Integer.MIN_VALUE) {
               continue;
            }

            int sampled = this.sampleSurfaceHeightMemoryOnly(gridMinX + dx, gridMinZ + dz);
            if (sampled == Integer.MIN_VALUE && SHELL_DATA_ESCALATION) {
               sampled = this.escalateShellHeight(gridMinX + dx, gridMinZ + dz);
            }

            if (sampled == Integer.MIN_VALUE) {
               remaining++;
            } else {
               heightGrid[index] = sampled;
            }
         }
      }

      TerrainShellTerrainPerf.recordShellResample(expectedMisses, remaining);
      return remaining;
   }

   /**
    * Counters for the "backfill" performed before the shell-height fallback (one summary line, no log spam).
    */
   private static final class TerrainShellTerrainPerf {
      private static final java.util.concurrent.atomic.AtomicLong RESAMPLE_CALLS =
         new java.util.concurrent.atomic.AtomicLong();
      private static final java.util.concurrent.atomic.AtomicLong RESAMPLE_FIXED =
         new java.util.concurrent.atomic.AtomicLong();
      private static final java.util.concurrent.atomic.AtomicLong RESAMPLE_LEFT =
         new java.util.concurrent.atomic.AtomicLong();

      private TerrainShellTerrainPerf() {
      }

      static void recordShellBlockingRepair() {
      SHELL_BLOCKING_REPAIRS.incrementAndGet();
   }

   static void recordShellBlockingRepairFailure() {
      SHELL_BLOCKING_REPAIR_FAILURES.incrementAndGet();
   }
   private static void recordShellResample(int expectedMisses, int remaining) {
         RESAMPLE_CALLS.incrementAndGet();
         RESAMPLE_FIXED.addAndGet(Math.max(0, expectedMisses - remaining));
         RESAMPLE_LEFT.addAndGet(remaining);
      }

      static String describe() {
         return "[repair resample: calls=" + RESAMPLE_CALLS.get()
            + " repaired=" + RESAMPLE_FIXED.get()
            + " stillMissing=" + RESAMPLE_LEFT.get()
            + " syncFetch=" + EarthChunkGenerator.SHELL_BLOCKING_REPAIRS.get()
            + " fetchFailed=" + EarthChunkGenerator.SHELL_BLOCKING_REPAIR_FAILURES.get()
            + " " + describeShellRepair()
            + " " + com.yucareux.tellus.world.data.elevation.TellusElevationSource.elevationRepairSummary()
            + "]";
      }
   }

   /**
    * 2026-10-05: **stage 0 of the fallback** 鈥?fill the holes from the neighbourhood before
    * anything is invented.
    *
    * <p><b>Why this stage exists.</b> The order used to be
    * "sample 鈫?if anything is missing, build coarse anchors 鈫?if no anchor at all could be
    * resolved, lay <b>one</b> height over the whole chunk". That last branch is the square
    * plateau: a flat 24x24 plane of one Y, ringed by the neighbouring chunks' real terrain, so
    * the player sees a step wherever a data hole happened to be.
    *
    * <p>arnis's answer is {@code fill_nan_values} (postprocess.rs:2530): iteratively replace each
    * missing cell with the mean of its eight neighbours until nothing changes, reading from a
    * snapshot each pass so scan order cannot tilt the result. That turns a hole into a smooth
    * continuation of whatever surrounds it, which is what the halo ring from
    * {@code heightGridCache.copyOverlaps} provides 鈥?{@code copyOverlaps} runs with
    * {@code allowApproximate = true} on this path, so even a neighbour that itself had a few
    * holes contributes.
    *
    * <p>Ported via {@link TellusElevationRepair#fillNanValues} (native when the kernel is
    * loaded, pure Java otherwise).
    *
    * <p><b>What it cannot do:</b> rescue a chunk where <em>every</em> neighbour is empty too. Then
    * there is no finite neighbour anywhere, the loop exits after one no-op pass, and control
    * falls through to the old anchor logic 鈥?which is the honest last resort, because at that
    * point there is genuinely no data to work from. What changed is that this is now the
    * <b>rare</b> branch rather than the common one, because
    * {@code TellusElevationSource.repairDecodedTile} stops a 200-but-empty tile from ever
    * reaching this method in the first place.
    *
    * @return how many cells were filled
    */
   private int fillShellHolesFromNeighbours(int[] heightGrid, int gridSize) {
      final int cells = gridSize * gridSize;
      double[] grid = new double[cells];
      int missing = 0;
      for (int i = 0; i < cells; i++) {
         int height = heightGrid[i];
         if (height == Integer.MIN_VALUE) {
            grid[i] = Double.NaN;
            missing++;
         } else {
            grid[i] = height;
         }
      }
      if (missing == 0 || missing == cells) {
         return 0;
      }
      int filled = TellusElevationRepair.fillNanValues(grid, gridSize, gridSize);
      if (filled <= 0) {
         return 0;
      }
      for (int i = 0; i < cells; i++) {
         double value = grid[i];
         if (Double.isFinite(value)) {
            heightGrid[i] = Mth.clamp((int)Math.round(value), this.minY, this.minY + this.height - 1);
         }
      }
      SHELL_HOLE_FILLS.addAndGet(filled);
      return filled;
   }

   /**
    * 2026-10-05: **stage 1** 鈥?despike the grid before the anchors are built.
    *
    * <p>Ported from arnis {@code postprocess.rs:56} (5x5 median + MAD). We had no denoising of
    * any kind: grepping the whole elevation package for {@code outlier|spike|MAD|IQR|despeckle}
    * returns one comment hit. A single bad DEM cell therefore reached the world as a
    * single-column tower, and a whole failed tile as a single-cell hole that the stage-0 fill
    * could only paper over.
    *
    * <p>{@code mPerCell} is {@code step * worldScale} 鈥?how many metres one cell of this grid
    * spans 鈥?which is what keeps the filter's reach at a constant physical scale instead of
    * scaling with the world.
    */
   private int despikeShellHeightGrid(int[] heightGrid, int gridSize, int step) {
      final int cells = gridSize * gridSize;
      double[] grid = new double[cells];
      for (int i = 0; i < cells; i++) {
         grid[i] = heightGrid[i] == Integer.MIN_VALUE ? Double.NaN : heightGrid[i];
      }
      double metersPerCell = Math.abs(step * this.settings.worldScale());
      int despiked = TellusElevationRepair.repairTerrainAnomalies(grid, gridSize, gridSize, metersPerCell);
      if (despiked <= 0) {
         return 0;
      }
      for (int i = 0; i < cells; i++) {
         double value = grid[i];
         if (Double.isFinite(value) && heightGrid[i] != Integer.MIN_VALUE) {
            heightGrid[i] = Mth.clamp((int)Math.round(value), this.minY, this.minY + this.height - 1);
         }
      }
      SHELL_DESPIKES.addAndGet(despiked);
      return despiked;
   }

   private static final java.util.concurrent.atomic.AtomicLong SHELL_HOLE_FILLS =
      new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong SHELL_DESPIKES =
      new java.util.concurrent.atomic.AtomicLong();

   /** Appended to the existing terrain-shell perf line; also printed by {@code /tellus verify}. */
   public static String describeShellRepair() {
      return "[hole fills: " + SHELL_HOLE_FILLS.get() + " cells, despikes: " + SHELL_DESPIKES.get() + " cells]";
   }

   private void fillMissingTerrainShellHeights(int[] heightGrid, int gridSize, int step) {
      // 2026-10-05: stage 0 and stage 1 run BEFORE the anchor machinery, so that "invent one
      // height for the whole chunk" is only ever reached when the neighbourhood has nothing to say.
      fillShellHolesFromNeighbours(heightGrid, gridSize);
      despikeShellHeightGrid(heightGrid, gridSize, step);

      int[] anchors = buildShellAnchorCoordinates(gridSize);
      int[][] coarse = new int[anchors.length][anchors.length];
      for (int z = 0; z < coarse.length; z++) {
         Arrays.fill(coarse[z], Integer.MIN_VALUE);
      }

      for (int anchorZIndex = 0; anchorZIndex < anchors.length; anchorZIndex++) {
         for (int anchorXIndex = 0; anchorXIndex < anchors.length; anchorXIndex++) {
            coarse[anchorZIndex][anchorXIndex] = nearestKnownTerrainHeight(heightGrid, gridSize, anchors[anchorXIndex], anchors[anchorZIndex], 2);
         }
      }

      int defaultHeight = this.seaLevel;
      int knownAnchorCount = 0;
      long knownAnchorSum = 0L;
      for (int[] coarseRow : coarse) {
         for (int coarseHeight : coarseRow) {
            if (coarseHeight != Integer.MIN_VALUE) {
               knownAnchorSum += coarseHeight;
               knownAnchorCount++;
            }
         }
      }

      if (knownAnchorCount > 0) {
         defaultHeight = Mth.floor((double)knownAnchorSum / knownAnchorCount);
         // 2026-09-29: remember the "average height of this piece of land"
         // used as the fallback when a player walks from an area with data into one without, so heights stay continuous
         this.recentShellHeight.set(defaultHeight);
      } else {
         // 2026-09-29: not a single anchor was obtained for the whole chunk -- previously `this.seaLevel`
         // Real incident: the player stood on the **Tibetan plateau** (surface about 4150) while the chunk underfoot,
         // being far from the preload area, obtained no elevation at all -> all 256 columns were filled with **sea level 64**
         // -> more than **4000 blocks** away from adjacent normal chunks -> a screen full of **vertical cliffs** (player screenshot)
         // Changed to: use the **average height of the most recently built chunk** -- at least it stays continuous
         int recent = this.recentShellHeight.get();
         if (recent != Integer.MIN_VALUE) {
            defaultHeight = recent;
         }
      }

      defaultHeight = Mth.clamp(defaultHeight, this.minY, this.minY + this.height - 1);
      for (int anchorZIndex = 0; anchorZIndex < anchors.length; anchorZIndex++) {
         for (int anchorXIndex = 0; anchorXIndex < anchors.length; anchorXIndex++) {
            if (coarse[anchorZIndex][anchorXIndex] == Integer.MIN_VALUE) {
               int replacement = nearestKnownAnchorHeight(coarse, anchorXIndex, anchorZIndex);
               coarse[anchorZIndex][anchorXIndex] = replacement != Integer.MIN_VALUE ? replacement : defaultHeight;
            }
         }
      }

      for (int z = 0; z < gridSize; z++) {
         for (int x = 0; x < gridSize; x++) {
            int index = z * gridSize + x;
            if (heightGrid[index] != Integer.MIN_VALUE) {
               continue;
            }

            int lowAnchorX = lowerAnchorIndex(anchors, x);
            int highAnchorX = upperAnchorIndex(anchors, x);
            int lowAnchorZ = lowerAnchorIndex(anchors, z);
            int highAnchorZ = upperAnchorIndex(anchors, z);
            int h00 = coarse[lowAnchorZ][lowAnchorX];
            int h10 = coarse[lowAnchorZ][highAnchorX];
            int h01 = coarse[highAnchorZ][lowAnchorX];
            int h11 = coarse[highAnchorZ][highAnchorX];
            heightGrid[index] = bilinearInterpolateHeight(
               anchors[lowAnchorX], anchors[highAnchorX], anchors[lowAnchorZ], anchors[highAnchorZ], h00, h10, h01, h11, x, z
            );
         }
      }

      for (int z = 0; z < gridSize; z++) {
         for (int x = 0; x < gridSize; x++) {
            int index = z * gridSize + x;
            if (heightGrid[index] == Integer.MIN_VALUE) {
               int replacement = nearestKnownTerrainHeight(heightGrid, gridSize, x, z, gridSize);
               heightGrid[index] = replacement != Integer.MIN_VALUE ? replacement : defaultHeight;
            }
         }
      }
   }

   private static int[] buildShellAnchorCoordinates(int gridSize) {
      IntArrayList coords = new IntArrayList();
      for (int index = 0; index < gridSize; index += 4) {
         coords.add(index);
      }

      if (coords.isEmpty() || coords.getInt(coords.size() - 1) != gridSize - 1) {
         coords.add(gridSize - 1);
      }

      return coords.toIntArray();
   }

   private static int nearestKnownAnchorHeight(int[][] anchors, int centerX, int centerZ) {
      int maxRadius = Math.max(anchors.length, anchors[0].length);
      for (int radius = 1; radius <= maxRadius; radius++) {
         long sum = 0L;
         int count = 0;
         for (int z = Math.max(0, centerZ - radius); z <= Math.min(anchors.length - 1, centerZ + radius); z++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(anchors[z].length - 1, centerX + radius); x++) {
               int value = anchors[z][x];
               if (value != Integer.MIN_VALUE) {
                  sum += value;
                  count++;
               }
            }
         }

         if (count > 0) {
            return Mth.floor((double)sum / count);
         }
      }

      return Integer.MIN_VALUE;
   }

   private static int nearestKnownTerrainHeight(int[] heightGrid, int gridSize, int centerX, int centerZ, int maxRadius) {
      if (centerX >= 0 && centerX < gridSize && centerZ >= 0 && centerZ < gridSize) {
         int center = heightGrid[centerZ * gridSize + centerX];
         if (center != Integer.MIN_VALUE) {
            return center;
         }
      }

      for (int radius = 1; radius <= maxRadius; radius++) {
         long sum = 0L;
         int count = 0;
         for (int z = Math.max(0, centerZ - radius); z <= Math.min(gridSize - 1, centerZ + radius); z++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(gridSize - 1, centerX + radius); x++) {
               int value = heightGrid[z * gridSize + x];
               if (value != Integer.MIN_VALUE) {
                  sum += value;
                  count++;
               }
            }
         }

         if (count > 0) {
            return Mth.floor((double)sum / count);
         }
      }

      return Integer.MIN_VALUE;
   }

   private static int lowerAnchorIndex(int[] anchors, int value) {
      int best = 0;
      for (int index = 0; index < anchors.length; index++) {
         if (anchors[index] > value) {
            break;
         }

         best = index;
      }

      return best;
   }

   private static int upperAnchorIndex(int[] anchors, int value) {
      for (int index = 0; index < anchors.length; index++) {
         if (anchors[index] >= value) {
            return index;
         }
      }

      return anchors.length - 1;
   }

   private static int bilinearInterpolateHeight(
      int x0, int x1, int z0, int z1, int h00, int h10, int h01, int h11, int x, int z
   ) {
      if (x0 == x1 && z0 == z1) {
         return h00;
      } else if (x0 == x1) {
         double tz = (z - z0) / (double)Math.max(1, z1 - z0);
         return Mth.floor(Mth.lerp(tz, h00, h01));
      } else if (z0 == z1) {
         double tx = (x - x0) / (double)Math.max(1, x1 - x0);
         return Mth.floor(Mth.lerp(tx, h00, h10));
      } else {
         double tx = (x - x0) / (double)Math.max(1, x1 - x0);
         double tz = (z - z0) / (double)Math.max(1, z1 - z0);
         double low = Mth.lerp(tx, h00, h10);
         double high = Mth.lerp(tx, h01, h11);
         return Mth.floor(Mth.lerp(tz, low, high));
      }
   }

   private static boolean isReusableHeightGridLayout(int step, int gridSize) {
      return step == 4 && gridSize == 24;
   }

   private static String sampleKoppenCode(int blockX, int blockZ, double worldScale) {
      String koppen = KOPPEN_SOURCE.sampleDitheredCode(blockX, blockZ, worldScale);
      return koppen != null ? koppen : KOPPEN_SOURCE.findNearestCode(blockX, blockZ, worldScale);
   }

   private void repairAnomalousChunkTerrain(
      int[] terrainSurfaces, int[] waterSurfaces, boolean[] waterFlags, int[] coverClasses, int[] heightGrid, int gridSize, int step, int minY, int maxY
   ) {
      int[] repaired = (int[])terrainSurfaces.clone();

      for (int pass = 0; pass < 4; pass++) {
         int[] source = (int[])repaired.clone();
         boolean changed = false;

         for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
               int index = chunkIndex(localX, localZ);
               int surface = source[index];
               if (this.shouldRepairTerrainAnomaly(coverClasses[index], surface, waterFlags[index])) {
                  int gridIndex = (localZ + step) * gridSize + localX + step;
                  int east = sampleNeighborHeight(source, localX + 1, localZ, heightGrid, gridIndex + 1);
                  int west = sampleNeighborHeight(source, localX - 1, localZ, heightGrid, gridIndex - 1);
                  int north = sampleNeighborHeight(source, localX, localZ - 1, heightGrid, gridIndex - gridSize);
                  int south = sampleNeighborHeight(source, localX, localZ + 1, heightGrid, gridIndex + gridSize);
                  int northEast = sampleNeighborHeight(source, localX + 1, localZ - 1, heightGrid, gridIndex - gridSize + 1);
                  int northWest = sampleNeighborHeight(source, localX - 1, localZ - 1, heightGrid, gridIndex - gridSize - 1);
                  int southEast = sampleNeighborHeight(source, localX + 1, localZ + 1, heightGrid, gridIndex + gridSize + 1);
                  int southWest = sampleNeighborHeight(source, localX - 1, localZ + 1, heightGrid, gridIndex + gridSize - 1);
                  int repairedHeight = repairAnomalousTerrainHeightFromNeighbors(surface, east, west, north, south, northEast, northWest, southEast, southWest);
                  if (repairedHeight != surface) {
                     repaired[index] = Mth.clamp(repairedHeight, minY, maxY);
                     changed = true;
                  }
               }
            }
         }

         for (int localZ = 0; localZ < 16; localZ++) {
            for (int localXx = 0; localXx < 16; localXx++) {
               int index = chunkIndex(localXx, localZ);
               int gridIndex = (localZ + step) * gridSize + localXx + step;
               heightGrid[gridIndex] = repaired[index];
            }
         }

         if (!changed) {
            break;
         }
      }

      System.arraycopy(repaired, 0, terrainSurfaces, 0, repaired.length);

      for (int localZ = 0; localZ < 16; localZ++) {
         for (int localXx = 0; localXx < 16; localXx++) {
            int index = chunkIndex(localXx, localZ);
            if (!waterFlags[index]) {
               waterSurfaces[index] = terrainSurfaces[index];
            } else if (terrainSurfaces[index] >= waterSurfaces[index]) {
               waterFlags[index] = false;
               waterSurfaces[index] = terrainSurfaces[index];
            }

            int gridIndex = (localZ + step) * gridSize + localXx + step;
            heightGrid[gridIndex] = terrainSurfaces[index];
         }
      }
   }

   private static int sampleNeighborHeight(int[] chunkSurfaces, int localX, int localZ, int[] heightGrid, int fallbackGridIndex) {
      return localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16 ? chunkSurfaces[chunkIndex(localX, localZ)] : heightGrid[fallbackGridIndex];
   }

   private int repairAnomalousSurfaceHeight(int worldX, int worldZ, int surface, int coverClass, int minY, int maxY) {
      return this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, coverClass, minY, maxY, false);
   }

   private int repairAnomalousSurfaceHeight(int worldX, int worldZ, int surface, int coverClass, int minY, int maxY, boolean hasWater) {
      if (!this.shouldRepairTerrainAnomaly(coverClass, surface, hasWater)) {
         return Mth.clamp(surface, minY, maxY);
      } else {
         int east = this.sampleSurfaceHeight(worldX + 1, worldZ);
         int west = this.sampleSurfaceHeight(worldX - 1, worldZ);
         int north = this.sampleSurfaceHeight(worldX, worldZ - 1);
         int south = this.sampleSurfaceHeight(worldX, worldZ + 1);
         int northEast = this.sampleSurfaceHeight(worldX + 1, worldZ - 1);
         int northWest = this.sampleSurfaceHeight(worldX - 1, worldZ - 1);
         int southEast = this.sampleSurfaceHeight(worldX + 1, worldZ + 1);
         int southWest = this.sampleSurfaceHeight(worldX - 1, worldZ + 1);
         int repaired = repairAnomalousTerrainHeightFromNeighbors(surface, east, west, north, south, northEast, northWest, southEast, southWest);
         return Mth.clamp(repaired, minY, maxY);
      }
   }

   private boolean shouldRepairTerrainAnomaly(int coverClass, int surface, boolean hasWater) {
      int heightAboveSea = surface - this.seaLevel;
      return heightAboveSea < 50 ? false : !hasWater && !isWaterCoverClass(coverClass) || heightAboveSea >= 90;
   }

   private static boolean isWaterCoverClass(int coverClass) {
      return coverClass == 80 || coverClass == 95;
   }

   private static int repairAnomalousTerrainHeightFromNeighbors(
      int center, int east, int west, int north, int south, int northEast, int northWest, int southEast, int southWest
   ) {
      return TerrainAnomalyRepair.repairHeightFromNeighbors(center, east, west, north, south, northEast, northWest, southEast, southWest);
   }

   private static int resolveSolidSectionMaxIndex(ChunkAccess chunk, int chunkMinY, int minSurface, int sectionCount) {
      if (sectionCount == 0) {
         return -1;
      } else {
         int sectionIndex = chunk.getSectionIndex(minSurface);
         int sectionBottom = chunkMinY + (sectionIndex << 4);
         int sectionTop = sectionBottom + 15;
         int solidMaxIndex = minSurface >= sectionTop ? sectionIndex : sectionIndex - 1;
         return solidMaxIndex < 0 ? -1 : Math.min(solidMaxIndex, sectionCount - 1);
      }
   }

   /**
    * Thread pool that fills sections in parallel.
    * Safety: every section to fill is first materialized into an independent object by materializeSection,
    * no section shares mutable state with another; PalettedContainer carries its own reentrant lock;
    * timing statistics are aggregated back on the calling thread.
    */
   private static final java.util.concurrent.ExecutorService TELLUS_SECTION_FILL_POOL =
      java.util.concurrent.Executors.newFixedThreadPool(
         intProperty("tellus.fill.threads",
            Math.max(2, Runtime.getRuntime().availableProcessors()), 1, 16),
         runnable -> {
            Thread thread = new Thread(runnable, "Tellus-SectionFill");
            thread.setDaemon(true);
            return thread;
         });

   private static void fillSolidSections(
      LevelChunkSection[] sections,
      boolean[] solidSections,
      int[] sectionTopYs,
      int solidMaxIndex,
      BlockState stone,
      BlockState deepslate,
      int deepslateStart,
      EarthChunkGenerator.SolidSectionFillProfiler profiler
   ) {
      int sectionCount = sections.length;
      long sectionScanStartNs = beginFullChunkProfiling();

      // Multi-threaded filling: the sections are independent of each other (materializeSection guarantees that every slot is its own object),
      // so on a 4-core machine this costs roughly 1/core of the serial time; timing is aggregated on the calling thread and failure semantics match the serial version.
      java.util.List<java.util.concurrent.Future<long[]>> fillFutures =
         new java.util.ArrayList<>();
      Throwable firstFillFailure = null;
      for (int i = 0; i <= solidMaxIndex && i < sectionCount; i++) {
         int topY = sectionTopYs[i];
         int bottomY = topY - 15;
         if (bottomY >= 0 || topY < 0) {
            BlockState fill = topY < deepslateStart ? deepslate : stone;
            final int idx = i;
            final BlockState fillState = fill;
            fillFutures.add(TELLUS_SECTION_FILL_POOL.submit(() -> {
               long sectionAccessStartNs = beginFullChunkProfiling();
               SparseSections.materializeSection(sections, idx);
               LevelChunkSection section =
                  Objects.requireNonNull(sections[idx], "section");
               long accessNs = elapsedFullChunkProfilingSince(sectionAccessStartNs);
               long writeStart = beginFullChunkProfiling();
               PalettedContainer<BlockState> states = section.getStates();
               section.acquire();
               boolean bulkFilled;
               try {
                  // 2026-10-12: raw-storage bulk fill when the whole section takes one state; also sets the
                  // block counters, which lets the recalc below be skipped. Measured at 1:1: sectionWrite
                  // 43824 ms and recalc 15946 ms over 120 chunk builds, both almost entirely inside these
                  // two loops. Falls back to the per-block path whenever the shortcut cannot be proven.
                  bulkFilled = TellusBulkSectionFill.tryFillWholeSection(section, fillState);
                  if (!bulkFilled) {
                     for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                           for (int x = 0; x < 16; x++) {
                              states.getAndSetUnchecked(x, y, z, fillState);
                           }
                        }
                     }
                  }
               } finally {
                  section.release();
               }
               long writeNs = elapsedFullChunkProfilingSince(writeStart);
               long recalcStart = beginFullChunkProfiling();
               if (!bulkFilled) {
                  section.recalcBlockCounts();
               }
               long recalcNs = elapsedFullChunkProfilingSince(recalcStart);
               solidSections[idx] = true;
               return new long[]{accessNs, writeNs, recalcNs};
            }));
         }
      }
      for (java.util.concurrent.Future<long[]> fillFuture : fillFutures) {
         try {
            long[] ns = fillFuture.get();
            profiler.sectionAccessNs += ns[0];
            profiler.sectionWriteNs += ns[1];
            profiler.recalcNs += ns[2];
         } catch (java.util.concurrent.ExecutionException executionException) {
            Throwable cause = executionException.getCause() != null
               ? executionException.getCause() : executionException;
            if (firstFillFailure == null) {
               firstFillFailure = cause;
            }
         } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
         }
      }
      if (firstFillFailure != null) {
         // Same failure semantics as the serial version: a fill failure fails this chunk's generation, which then goes through chunk retry
         if (firstFillFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
         }
         throw new IllegalStateException("Parallel section fill failed", firstFillFailure);
      }

      long totalSectionScanNs = elapsedFullChunkProfilingSince(sectionScanStartNs);
      long attributedNs = profiler.sectionAccessNs + profiler.sectionWriteNs + profiler.recalcNs;
      profiler.scanNs += Math.max(0L, totalSectionScanNs - attributedNs);
   }

   private static void fillSolidSections(
      EarthChunkGenerator.ChunkSectionWriter writer,
      boolean[] solidSections,
      int[] sectionTopYs,
      int solidMaxIndex,
      BlockState stone,
      BlockState deepslate,
      int deepslateStart,
      EarthChunkGenerator.SolidSectionFillProfiler profiler
   ) {
      int sectionCount = sectionTopYs.length;
      long sectionScanStartNs = beginFullChunkProfiling();

      for (int i = 0; i <= solidMaxIndex && i < sectionCount; i++) {
         int topY = sectionTopYs[i];
         int bottomY = topY - 15;
         if (bottomY >= 0 || topY < 0) {
            BlockState fill = topY < deepslateStart ? deepslate : stone;
            writer.fillSectionConstant(i, fill, profiler);
            solidSections[i] = true;
         }
      }

      long totalSectionScanNs = elapsedFullChunkProfilingSince(sectionScanStartNs);
      long attributedNs = profiler.sectionAccessNs + profiler.sectionWriteNs + profiler.recalcNs;
      profiler.scanNs += Math.max(0L, totalSectionScanNs - attributedNs);
   }

   private static void fillSection(LevelChunkSection section, BlockState fill, EarthChunkGenerator.SolidSectionFillProfiler profiler) {
      PalettedContainer<BlockState> states = section.getStates();
      long sectionWriteStartNs = beginFullChunkProfiling();
      section.acquire();
      boolean bulkFilled;
      try {
         // 2026-10-12: raw-storage bulk fill when the whole section takes one state; falls back to the
         // per-block loop otherwise. Measured at 1:1: sectionWrite 43824 ms and recalc 15946 ms over
         // 120 chunk builds, virtually all of it inside these two loops.
         bulkFilled = TellusBulkSectionFill.tryFillWholeSection(section, fill);
         if (!bulkFilled) {
            for (int y = 0; y < 16; y++) {
               for (int z = 0; z < 16; z++) {
                  for (int x = 0; x < 16; x++) {
                     states.getAndSetUnchecked(x, y, z, fill);
                  }
               }
            }
         }
      } finally {
         section.release();
      }
      profiler.sectionWriteNs += elapsedFullChunkProfilingSince(sectionWriteStartNs);

      long sectionRecalcStartNs = beginFullChunkProfiling();
      if (!bulkFilled) {
         section.recalcBlockCounts();
      }
      profiler.recalcNs += elapsedFullChunkProfilingSince(sectionRecalcStartNs);
   }

   private static void fillStoneColumnSpan(
      LevelChunkSection[] sections,
      boolean[] touchedSections,
      int chunkMinY,
      int localX,
      int localZ,
      int startY,
      int endY,
      int deepslateStart,
      BlockState stone,
      BlockState deepslate
   ) {
      if (endY < startY) {
         return;
      }

      int sectionIndex = (startY - chunkMinY) >> 4;
      if (sectionIndex < 0 || sectionIndex >= sections.length) {
         return;
      }

      // Materialize first (found by the first round of the new self-audit): this path **does not go through acquireStates**,
      // it inlines "fetch section -> write palette" itself -- in sparse mode that slot may be a globally shared instance,
      // and writing to it directly pollutes the empty section of every chunk (the cause of Missing Palette entries).
      SparseSections.materializeSection(sections, sectionIndex);
      // Materialize first (found by the first round of the new self-audit): the empty slot here may be a globally shared instance,
      // and writing the palette directly would pollute the empty section of every chunk -> Missing Palette entry
      SparseSections.materializeSection(sections, sectionIndex);
      LevelChunkSection section = Objects.requireNonNull(sections[sectionIndex], "section");
      PalettedContainer<BlockState> states = section.getStates();
      int sectionBottomY = chunkMinY + (sectionIndex << 4);
      section.acquire();

      try {
         for (int worldY = startY; worldY <= endY; worldY++) {
            states.getAndSetUnchecked(localX, worldY - sectionBottomY, localZ, worldY < deepslateStart ? deepslate : stone);
         }
      } finally {
         section.release();
      }

      touchedSections[sectionIndex] = true;
   }

   private static void fillColumnConstant(
      LevelChunkSection[] sections, boolean[] touchedSections, int chunkMinY, int localX, int localZ, int startY, int endY, BlockState state
   ) {
      if (endY < startY) {
         return;
      }

      int currentY = startY;
      while (currentY <= endY) {
         int sectionIndex = (currentY - chunkMinY) >> 4;
         if (sectionIndex < 0 || sectionIndex >= sections.length) {
            break;
         }

         // Materialize first (found by the first round of the new self-audit): the empty slot here may be a globally shared instance,
         // and writing the palette directly would pollute the empty section of every chunk -> Missing Palette entry
         SparseSections.materializeSection(sections, sectionIndex);
         LevelChunkSection section = Objects.requireNonNull(sections[sectionIndex], "section");
         PalettedContainer<BlockState> states = section.getStates();
         int sectionBottomY = chunkMinY + (sectionIndex << 4);
         int localStartY = Math.max(0, currentY - sectionBottomY);
         int localEndY = Math.min(15, endY - sectionBottomY);
         section.acquire();

         try {
            for (int localY = localStartY; localY <= localEndY; localY++) {
               states.getAndSetUnchecked(localX, localY, localZ, state);
            }
         } finally {
            section.release();
         }

         touchedSections[sectionIndex] = true;
         currentY = sectionBottomY + 16;
      }
   }

   private static void recalcFilledSections(LevelChunkSection[] sections, boolean[] solidSections, boolean[] touchedSections) {
      for (int i = 0; i < sections.length && i < touchedSections.length; i++) {
         if (touchedSections[i] && !solidSections[i]) {
            Objects.requireNonNull(sections[i], "section").recalcBlockCounts();
         }
      }
   }

   private void filterVillageStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start != null && start.isValid()) {
               Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
               if (this.isVillageStructure(registry, structure) && this.isVillageStartTooSteep(start)) {
                  chunk.setStartForStructure(structure, StructureStart.INVALID_START);
               }
            }
         }
      }
   }

   private boolean isVillageStartTooSteep(StructureStart start) {
      return this.isStructureStartTooSteep(start, 4, 4, 6);
   }

   private void filterWoodlandMansionStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            StructureStart start = entry.getValue();
            if (start != null && start.isValid()) {
               Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
               if (this.isWoodlandMansionStructure(registry, structure) && this.isWoodlandMansionStartTooSteep(start)) {
                  chunk.setStartForStructure(structure, StructureStart.INVALID_START);
               }
            }
         }
      }
   }

   private boolean isWoodlandMansionStartTooSteep(StructureStart start) {
      return this.isStructureStartTooSteep(start, 8, 6, 8);
   }

   private void filterStartsCollidingWithOsm(RegistryAccess registryAccess, ChunkAccess chunk) {
      double worldScale = this.settings.worldScale();
      boolean roadsActive = this.settings.enableRoads() && OSM_ROAD_SOURCE.available() && worldScale > 0.0 && worldScale <= OSM_ROAD_MAX_SCALE;
      boolean buildingsActive = this.settings.enableBuildings() && OSM_BUILDING_SOURCE.available() && worldScale > 0.0 && worldScale <= OSM_BUILDING_MAX_SCALE;
      if (roadsActive || buildingsActive) {
         Map<Structure, StructureStart> starts = chunk.getAllStarts();
         if (!starts.isEmpty()) {
            Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
            EarthChunkGenerator.RoadWidths roadWidths = roadsActive ? resolveRoadWidths(worldScale) : null;

            for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
               StructureStart start = entry.getValue();
               if (start != null && start.isValid()) {
                  Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
                  if (this.shouldAvoidOsmCollision(registry, structure)
                     && this.doesStructureStartCollideWithOsm(start, roadsActive, buildingsActive, roadWidths)) {
                     chunk.setStartForStructure(structure, StructureStart.INVALID_START);
                  }
               }
            }
         }
      }
   }

   private boolean doesStructureStartCollideWithOsm(
      StructureStart start, boolean roadsActive, boolean buildingsActive, EarthChunkGenerator.RoadWidths roadWidths
   ) {
      BoundingBox box = start.getBoundingBox();
      if (buildingsActive && this.structureCollidesWithBuildings(box)) {
         return true;
      } else {
         return roadsActive && roadWidths != null ? this.structureCollidesWithRoads(box, roadWidths) : false;
      }
   }

   private boolean structureCollidesWithBuildings(BoundingBox box) {
      int marginBlocks = 2;
      double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
      double minX = structureFootprintMinX(box);
      double maxX = structureFootprintMaxX(box);
      double minZ = structureFootprintMinZ(box);
      double maxZ = structureFootprintMaxZ(box);
      OsmQueryMode queryMode = this.shouldUseStructureOsmSyncFallback() ? OsmQueryMode.BLOCKING : OsmQueryMode.NON_BLOCKING;
      EarthChunkGenerator.OsmBuildingQueryResult query = this.fetchOsmBuildingsForAreaDetailed(
         box.minX(), box.minZ(), box.maxX(), box.maxZ(), marginBlocks, queryMode
      );
      if (queryMode == OsmQueryMode.NON_BLOCKING && query.hadCacheMisses() && query.features().isEmpty()) {
         EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
         return false;
      }

      for (OsmBuildingFeature building : query.features()) {
         if (this.structureIntersectsBuilding(box, building, blocksPerDegree, minX, minZ, maxX, maxZ)) {
            return true;
         }
      }

      return false;
   }

   private boolean structureCollidesWithRoads(BoundingBox box, EarthChunkGenerator.RoadWidths roadWidths) {
      int marginBlocks = Math.max(roadWidths.main(), Math.max(roadWidths.normal(), roadWidths.dirt())) + 2;
      OsmQueryMode queryMode = this.shouldUseStructureOsmSyncFallback() ? OsmQueryMode.BLOCKING : OsmQueryMode.NON_BLOCKING;
      EarthChunkGenerator.OsmRoadQueryResult query = this.fetchOsmRoadsForAreaDetailed(
         box.minX(), box.minZ(), box.maxX(), box.maxZ(), marginBlocks, queryMode
      );
      if (queryMode == OsmQueryMode.NON_BLOCKING && query.hadCacheMisses() && query.features().isEmpty()) {
         EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
         return false;
      }

      double blocksPerDegree = blocksPerDegree(this.settings.worldScale());
      double worldScale = this.settings.worldScale();

      for (RoadFeature road : query.features()) {
         if (road.mode() != RoadMode.TUNNEL && this.structureIntersectsRoad(box, road, roadWidths, blocksPerDegree, worldScale)) {
            return true;
         }
      }

      return false;
   }

   private boolean structureIntersectsBuilding(
      BoundingBox box, OsmBuildingFeature building, double blocksPerDegree, double minX, double minZ, double maxX, double maxZ
   ) {
      if (building.maxBlockX(blocksPerDegree) < minX
         || building.minBlockX(blocksPerDegree) > maxX
         || building.maxBlockZ(this.settings.worldScale()) < minZ
         || building.minBlockZ(this.settings.worldScale()) > maxZ) {
         return false;
      } else if (building.containsWorld(minX, minZ, this.settings.worldScale())
         || building.containsWorld(minX, maxZ, this.settings.worldScale())
         || building.containsWorld(maxX, minZ, this.settings.worldScale())
         || building.containsWorld(maxX, maxZ, this.settings.worldScale())) {
         return true;
      } else {
         for (int part = 0; part < building.partCount(); part++) {
            int points = building.pointCount(part);
            if (points >= 2) {
               double previousX = building.lonAt(part, points - 1) * blocksPerDegree;
               double previousZ = EarthProjection.latToBlockZ(building.latAt(part, points - 1), this.settings.worldScale());

               for (int i = 0; i < points; i++) {
                  double currentX = building.lonAt(part, i) * blocksPerDegree;
                  double currentZ = EarthProjection.latToBlockZ(building.latAt(part, i), this.settings.worldScale());
                  if (pointInRect(currentX, currentZ, minX, minZ, maxX, maxZ)
                     || segmentIntersectsRect(previousX, previousZ, currentX, currentZ, minX, minZ, maxX, maxZ)) {
                     return true;
                  }

                  previousX = currentX;
                  previousZ = currentZ;
               }
            }
         }

         return false;
      }
   }

   private boolean structureIntersectsRoad(
      BoundingBox box, RoadFeature road, EarthChunkGenerator.RoadWidths roadWidths, double blocksPerDegree, double worldScale
   ) {
      int roadWidth = switch (road.roadClass()) {
         case MAIN -> roadWidths.main();
         case NORMAL -> roadWidths.normal();
         case DIRT -> roadWidths.dirt();
      };
      double halfWidth = Math.max(0.5, (roadWidth - 1) * 0.5);
      double minX = structureFootprintMinX(box) - halfWidth;
      double maxX = structureFootprintMaxX(box) + halfWidth;
      double minZ = structureFootprintMinZ(box) - halfWidth;
      double maxZ = structureFootprintMaxZ(box) + halfWidth;
      int pointCount = road.pointCount();
      if (pointCount < 2) {
         return false;
      } else {
         double previousX = road.lonAt(0) * blocksPerDegree;
         double previousZ = EarthProjection.latToBlockZ(road.latAt(0), worldScale);
         if (pointInRect(previousX, previousZ, minX, minZ, maxX, maxZ)) {
            return true;
         } else {
            for (int i = 1; i < pointCount; i++) {
               double currentX = road.lonAt(i) * blocksPerDegree;
               double currentZ = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
               if (segmentIntersectsRect(previousX, previousZ, currentX, currentZ, minX, minZ, maxX, maxZ)) {
                  return true;
               }

               previousX = currentX;
               previousZ = currentZ;
            }

            return false;
         }
      }
   }

   private boolean shouldAvoidOsmCollision(Registry<Structure> registry, Structure structure) {
      Identifier key = registry.getKey(structure);
      if (key == null) {
         return false;
      } else {
         String path = key.getPath();
         return path.startsWith("village")
            || path.equals("woodland_mansion")
            || path.equals("desert_pyramid")
            || path.equals("desert_temple")
            || path.equals("jungle_pyramid")
            || path.equals("jungle_temple")
            || path.equals("pillager_outpost")
            || path.equals("igloo")
            || path.equals("swamp_hut")
            || path.equals("witch_hut")
            || path.startsWith("ruined_portal")
            || path.startsWith("trail_ruins");
      }
   }

   private static double structureFootprintMinX(BoundingBox box) {
      return box.minX() - 0.5;
   }

   private static double structureFootprintMaxX(BoundingBox box) {
      return box.maxX() + 0.5;
   }

   private static double structureFootprintMinZ(BoundingBox box) {
      return box.minZ() - 0.5;
   }

   private static double structureFootprintMaxZ(BoundingBox box) {
      return box.maxZ() + 0.5;
   }

   private static boolean pointInRect(double x, double z, double minX, double minZ, double maxX, double maxZ) {
      return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
   }

   private static boolean segmentIntersectsRect(double x1, double z1, double x2, double z2, double minX, double minZ, double maxX, double maxZ) {
      return pointInRect(x1, z1, minX, minZ, maxX, maxZ)
         || pointInRect(x2, z2, minX, minZ, maxX, maxZ)
         || segmentsIntersect(x1, z1, x2, z2, minX, minZ, maxX, minZ)
         || segmentsIntersect(x1, z1, x2, z2, maxX, minZ, maxX, maxZ)
         || segmentsIntersect(x1, z1, x2, z2, maxX, maxZ, minX, maxZ)
         || segmentsIntersect(x1, z1, x2, z2, minX, maxZ, minX, minZ);
   }

   private static boolean segmentsIntersect(double ax, double az, double bx, double bz, double cx, double cz, double dx, double dz) {
      double abx = bx - ax;
      double abz = bz - az;
      double acx = cx - ax;
      double acz = cz - az;
      double adx = dx - ax;
      double adz = dz - az;
      double cdx = dx - cx;
      double cdz = dz - cz;
      double cax = ax - cx;
      double caz = az - cz;
      double cbx = bx - cx;
      double cbz = bz - cz;
      double cross1 = cross(abx, abz, acx, acz);
      double cross2 = cross(abx, abz, adx, adz);
      double cross3 = cross(cdx, cdz, cax, caz);
      double cross4 = cross(cdx, cdz, cbx, cbz);
      double epsilon = 1.0E-7;
      if (Math.abs(cross1) <= epsilon && onSegment(ax, az, bx, bz, cx, cz)) {
         return true;
      } else if (Math.abs(cross2) <= epsilon && onSegment(ax, az, bx, bz, dx, dz)) {
         return true;
      } else if (Math.abs(cross3) <= epsilon && onSegment(cx, cz, dx, dz, ax, az)) {
         return true;
      } else if (Math.abs(cross4) <= epsilon && onSegment(cx, cz, dx, dz, bx, bz)) {
         return true;
      } else {
         return cross1 > 0.0 != cross2 > 0.0 && cross3 > 0.0 != cross4 > 0.0;
      }
   }

   private static double cross(double ax, double az, double bx, double bz) {
      return ax * bz - az * bx;
   }

   private static boolean onSegment(double ax, double az, double bx, double bz, double px, double pz) {
      return px >= Math.min(ax, bx) - 1.0E-7
         && px <= Math.max(ax, bx) + 1.0E-7
         && pz >= Math.min(az, bz) - 1.0E-7
         && pz <= Math.max(az, bz) + 1.0E-7;
   }

   private boolean isStructureStartTooSteep(StructureStart start, int margin, int sampleStep, int maxHeightDelta) {
      BoundingBox box = start.getBoundingBox();
      int minX = box.minX() - margin;
      int maxX = box.maxX() + margin;
      int minZ = box.minZ() - margin;
      int maxZ = box.maxZ() + margin;
      int minHeight = Integer.MAX_VALUE;
      int maxHeight = Integer.MIN_VALUE;
      int stride = Math.max(1, sampleStep);

      for (int z = minZ; z <= maxZ; z += stride) {
         for (int x = minX; x <= maxX; x += stride) {
            int surface = this.sampleSurfaceHeight(x, z);
            if (surface < minHeight) {
               minHeight = surface;
            }

            if (surface > maxHeight) {
               maxHeight = surface;
            }

            if (maxHeight - minHeight > maxHeightDelta) {
               return true;
            }
         }
      }

      return maxHeight - minHeight > maxHeightDelta;
   }

   private boolean isVillageStructure(Registry<Structure> registry, Structure structure) {
      Identifier key = registry.getKey(structure);
      return key != null && key.getPath().startsWith("village");
   }

   private boolean isWoodlandMansionStructure(Registry<Structure> registry, Structure structure) {
      Identifier key = registry.getKey(structure);
      return key != null && key.getPath().equals("woodland_mansion");
   }

   public int getGenDepth() {
      return this.height;
   }

   public int getSeaLevel() {
      return this.seaLevel;
   }

   public int getMinY() {
      return this.minY;
   }

   public int getBaseHeight(int x, int z,  Types heightmapType,  LevelHeightAccessor heightAccessor,  RandomState random) {
      if (this.isFastSpawnMode()) {
         int maxY = heightAccessor.getMaxY() - 1;
         return Mth.clamp(this.seaLevel + 1, heightAccessor.getMinY(), maxY);
      } else {
         int coverClass = this.sampleCoverClass(x, z);
         EarthChunkGenerator.ColumnHeights column = this.resolveFastColumnHeights(x, z, heightAccessor.getMinY(), heightAccessor.getMaxY(), coverClass);
         int surface = column.terrainSurface();
         if (heightmapType == Types.OCEAN_FLOOR_WG || heightmapType == Types.OCEAN_FLOOR) {
            return surface + 1;
         } else {
            return column.hasWater() ? Math.max(surface, column.waterSurface()) + 1 : surface + 1;
         }
      }
   }


   public NoiseColumn getBaseColumn(int x, int z,  LevelHeightAccessor heightAccessor,  RandomState random) {
      int minY = heightAccessor.getMinY();
      int height = heightAccessor.getHeight();
      BlockState[] states = new BlockState[height];
      Arrays.fill(states, AIR_STATE);
      int coverClass = this.sampleCoverClass(x, z);
      EarthChunkGenerator.ColumnHeights column = this.resolveFastColumnHeights(x, z, minY, minY + height, coverClass);
      int surface = column.terrainSurface();
      int surfaceIndex = surface - minY;

      for (int i = 0; i <= surfaceIndex; i++) {
         if (i >= 0 && i < states.length) {
            int y = minY + i;
            // 2026-10-05: this keyed the deepslate band to the **absolute** vanilla Y=0, i.e. it
            // assumed minY = -64. With TellusLayout.WORLD_MIN_Y = -2032 that turned the whole
            // Y in [-2032, 0) range -- **2032 blocks**, from the world floor up to Y=-1 -- into
            // deepslate, so almost the entire world was deep rock.
            //
            // Vanilla's deepslate is the bottom 64 blocks *of the world* (minY = -64 -> y < 0), so
            // the generalisation is minY + 64 rather than an absolute 0. With vanilla numbers this
            // is bit-for-bit the old behaviour; in a tall world it is a 64-block band at the floor.
            // (Anchoring to the sea level instead would NOT work: the default sea level is 64, which
            // puts the threshold back at y < 0 and reproduces the original bug exactly.)
            states[i] = y < this.minY + DEEPSLATE_BAND_BLOCKS ? DEEPSLATE_STATE : STONE_STATE;
         }
      }

      if (column.hasWater()) {
         int waterTop = column.waterSurface();
         int waterIndex = waterTop - minY;

         for (int ix = surfaceIndex + 1; ix <= waterIndex; ix++) {
            states[ix] = WATER_STATE;
         }
      }

      int bedrockIndex = this.minY - minY;
      if (bedrockIndex >= 0 && bedrockIndex < states.length) {
         states[bedrockIndex] = BEDROCK_STATE;
      }

      return Objects.requireNonNull(new NoiseColumn(minY, states), "noiseColumn");
   }

   public void addDebugScreenInfo( List<String> info,  RandomState random,  BlockPos pos) {
      info.add(String.format("Tellus 缂╂斁锛?.1f", this.settings.worldScale()));
   }

   private boolean isFastSpawnMode() {
      return this.fastSpawnMode.get();
   }

   private void disableFastSpawnMode() {
      if (this.fastSpawnMode.compareAndSet(true, false)) {
         if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
            earthBiomeSource.setFastSpawnMode(false);
         }
      }
   }

   /**
    * No natural scenery is kept where a building already exists (2026-09-20, explicitly requested by the user)
    *
    * <p>The vanilla decoration stage ({@code super.applyBiomeDecoration}) scatters grass / flowers / saplings / vanilla trees while
    * <b>completely ignoring buildings</b> -> grass grows on rooftops and trees grow through floors.
    *
    * <p>Approach (strictly following this project's hard rules):
    * <ul>
    * <li><b>Removal only</b> -- the whole path only calls setBlock(AIR) and never places any block; </li>
    * <li><b>Whitelist only</b> -- leaves / logs / flowers / saplings / grass / mushrooms / vines / sugar cane / bamboo and so on,
    * everything else (terrain, roads, the building itself) is left untouched; </li>
    * <li><b>Only in columns that have a building</b> ({@code treeSuppression} reuses the existing predicate); </li>
    * <li><b>Only inside the building's own vertical range</b> (with {@code BUILDING_SCENERY_MARGIN} blocks of slack above and below,
    * to catch trees that grew on the rooftop); outside that range nothing is touched; </li>
    * <li><b>Only when buildings are on</b> ({@code enableBuildings}), and it can be
    * switched off with {@code -Dtellus.vegetation.skipOnBuildings=false} --
    * once switched off the behaviour is bit-for-bit identical to before the change (hard rule 3). </li>
    * </ul>
    */
   private void clearVanillaSceneryOnBuildings(WorldGenLevel level, ChunkAccess chunk) {
      if (!SKIP_SCENERY_ON_BUILDINGS) {
         return;
      }
      if (!this.settings.enableBuildings()) {
         return;   // the user did not turn buildings on -> do nothing (only takes effect once buildings are on)
      }
      ChunkPos pos = chunk.getPos();
      EarthChunkGenerator.PreparedChunkBuildings buildings = this.preparedChunkBuildings.get(ChunkPos.pack(pos.x(), pos.z()));
      if (buildings == null) {
         return;
      }
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int cleared = 0;
      MutableBlockPos cursor = new MutableBlockPos();
      for (int localX = 0; localX < CHUNK_SIDE; localX++) {
         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            if (!buildings.suppressesTrees(localX, localZ)) {
               continue;   // columns without a building -> natural scenery is kept as usual
            }
            int[] span = buildings.buildingYSpan(localX, localZ);
            if (span == null) {
               continue;
            }
            int fromY = Math.max(chunk.getMinY(), span[0] - BUILDING_SCENERY_MARGIN);
            int toY = Math.min(chunk.getMaxY() - 1, span[1] + BUILDING_SCENERY_MARGIN);
            int worldX = chunkMinX + localX;
            int worldZ = chunkMinZ + localZ;
            for (int y = fromY; y <= toY; y++) {
               cursor.set(worldX, y, worldZ);
               BlockState state = level.getBlockState(cursor);
               if (state.isAir() || !isNaturalSceneryState(state)) {
                  continue;
               }
               level.setBlock(cursor, AIR_STATE, 2);
               cleared++;
            }
         }
      }
      if (cleared > 0 && BUILDING_SCENERY_LOG.compareAndSet(false, true)) {
         Tellus.LOGGER.info("[TellusCN] Cleared vegetation above buildings: removed {} block(s) (grass/flower/leaves/sapling) in this chunk; "
            + "Buildings, terrain and roads are unaffected. Restore with -Dtellus.vegetation.skipOnBuildings=false.",
            cleared);
      }
   }

   /** Whitelist: only these "natural scenery" blocks are cleared, everything else is left untouched. */
   private static boolean isNaturalSceneryState(BlockState state) {
      return state.is(BlockTags.LEAVES)
         || state.is(BlockTags.LOGS)
         || state.is(BlockTags.FLOWERS)
         || state.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock
         || state.is(BlockTags.SMALL_FLOWERS)
         || state.is(Blocks.SHORT_GRASS)
         || state.is(Blocks.TALL_GRASS)
         || state.is(Blocks.FERN)
         || state.is(Blocks.LARGE_FERN)
         || state.is(Blocks.DEAD_BUSH)
         || state.is(Blocks.SWEET_BERRY_BUSH)
         || state.is(Blocks.SUGAR_CANE)
         || state.is(Blocks.BAMBOO)
         || state.is(Blocks.BAMBOO_SAPLING)
         || state.is(Blocks.VINE)
         || state.is(Blocks.GLOW_LICHEN)
         || state.is(Blocks.BROWN_MUSHROOM)
         || state.is(Blocks.RED_MUSHROOM)
         || state.is(Blocks.PUMPKIN)
         || state.is(Blocks.MELON)
         || state.is(Blocks.LILY_PAD)
         || state.is(Blocks.CACTUS)
         || state.is(Blocks.SNOW)
         || state.is(Blocks.MOSS_CARPET)
         || state.is(Blocks.HANGING_ROOTS)
         || state.is(Blocks.BIG_DRIPLEAF)
         || state.is(Blocks.SMALL_DRIPLEAF);
   }

   private void placeTrees(WorldGenLevel level, ChunkAccess chunk) {
      ChunkPos pos = chunk.getPos();
      long chunkKey = ChunkPos.pack(pos.x(), pos.z());
      EarthChunkGenerator.ChunkDecorationContext decorationContext = this.chunkDecorationContexts.get(chunkKey);
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = this.preparedChunkBuildings.get(ChunkPos.pack(pos.x(), pos.z()));
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      int shorelineBlendRadius = Math.max(this.settings.riverLakeShorelineBlend(), this.settings.oceanShorelineBlend());
      int cellMinX = Math.floorDiv(chunkMinX, 5);
      int cellMaxX = Math.floorDiv(chunkMaxX, 5);
      int cellMinZ = Math.floorDiv(chunkMinZ, 5);
      int cellMaxZ = Math.floorDiv(chunkMaxZ, 5);
      long worldSeed = level.getSeed();

      for (int cellX = cellMinX; cellX <= cellMaxX; cellX++) {
         for (int cellZ = cellMinZ; cellZ <= cellMaxZ; cellZ++) {
            long seed = seedFromCoords(cellX, 0, cellZ) ^ worldSeed;
            RandomSource random = RandomSource.create(seed);
            int worldX = cellX * 5 + random.nextInt(5);
            int worldZ = cellZ * 5 + random.nextInt(5);
            if (worldX >= chunkMinX && worldX <= chunkMaxX && worldZ >= chunkMinZ && worldZ <= chunkMaxZ) {
               int localX = worldX - chunkMinX;
               int localZ = worldZ - chunkMinZ;
               if (preparedBuildings != null && preparedBuildings.suppressesTrees(localX, localZ)) {
                  continue;
               }

               int coverClass = decorationContext != null ? decorationContext.coverClass(localX, localZ) : this.sampleCoverClass(worldX, worldZ);
               boolean nearWater = false;
               if (shorelineBlendRadius > 0) {
                  nearWater = decorationContext != null && decorationContext.canResolveNearWaterWithinChunk(localX, localZ, shorelineBlendRadius)
                     ? decorationContext.isNearWaterWithinChunk(localX, localZ, shorelineBlendRadius)
                     : this.isNearWater(worldX, worldZ, shorelineBlendRadius);
               }

               if (coverClass == 10 && !nearWater) {
                  int expectedSurface = decorationContext != null ? decorationContext.terrainSurface(localX, localZ) : this.sampleSurfaceHeight(worldX, worldZ);
                  if (expectedSurface >= this.seaLevel) {
                     int topY = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
                     if (topY >= level.getMinY()
                        && topY >= this.seaLevel
                        && expectedSurface - topY <= TREE_MAX_SURFACE_DROP
                        && topY - expectedSurface <= TREE_MAX_SURFACE_RISE) {
                        BlockPos ground = new BlockPos(worldX, topY, worldZ);
                        BlockState groundState = level.getBlockState(ground);
                        if (!isRoadDeckState(groundState)
                           && !isRoadDeckState(level.getBlockState(ground.below()))
                           && isSolidCaveAnchor(groundState)
                           && !groundState.is(BlockTags.LOGS)
                           && !groundState.is(BlockTags.LEAVES)) {
                           BlockPos position = ground.above();
                           Holder<Biome> biome = decorationContext != null ? decorationContext.biome(localX, localZ) : level.getBiome(position);
                           if (!biome.is(Biomes.MANGROVE_SWAMP)) {
                              List<ConfiguredFeature<?, ?>> features = treeFeaturesForBiome(biome);
                              if (!features.isEmpty()) {
                                 if (!groundState.is(BlockTags.DIRT)) {
                                    level.setBlock(ground, GRASS_BLOCK_STATE, 260);
                                 }

                                 ConfiguredFeature<?, ?> feature = features.get(random.nextInt(features.size()));
                                 feature.place(level, this, random, position);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private List<EarthChunkGenerator.PreparedTreePlacement> prepareDeferredTreePlacements(
      EarthChunkGenerator.ChunkGenerationContext context, EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      ChunkPos pos = context.pos();
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      int shorelineBlendRadius = Math.max(this.settings.riverLakeShorelineBlend(), this.settings.oceanShorelineBlend());
      int cellMinX = Math.floorDiv(chunkMinX, 5);
      int cellMaxX = Math.floorDiv(chunkMaxX, 5);
      int cellMinZ = Math.floorDiv(chunkMinZ, 5);
      int cellMaxZ = Math.floorDiv(chunkMaxZ, 5);
      long worldSeed = this.worldSeed;
      List<EarthChunkGenerator.PreparedTreePlacement> placements = new ArrayList<>();

      for (int cellX = cellMinX; cellX <= cellMaxX; cellX++) {
         for (int cellZ = cellMinZ; cellZ <= cellMaxZ; cellZ++) {
            long seed = seedFromCoords(cellX, 0, cellZ) ^ worldSeed;
            RandomSource random = RandomSource.create(seed);
            int worldX = cellX * 5 + random.nextInt(5);
            int worldZ = cellZ * 5 + random.nextInt(5);
            if (worldX < chunkMinX || worldX > chunkMaxX || worldZ < chunkMinZ || worldZ > chunkMaxZ) {
               continue;
            }

            int localX = worldX - chunkMinX;
            int localZ = worldZ - chunkMinZ;
            if (preparedBuildings != null && preparedBuildings.suppressesTrees(localX, localZ)) {
               continue;
            }

            int index = chunkIndex(localX, localZ);
            int coverClass = context.coverClasses()[index];
            if (coverClass != 10) {
               continue;
            }

            boolean nearWater = false;
            if (shorelineBlendRadius > 0) {
               nearWater = localX - shorelineBlendRadius >= 0
                  && localX + shorelineBlendRadius <= CHUNK_MASK
                  && localZ - shorelineBlendRadius >= 0
                  && localZ + shorelineBlendRadius <= CHUNK_MASK
                     ? hasWaterNear(context.waterFlags(), localX, localZ, shorelineBlendRadius)
                     : this.isNearWater(worldX, worldZ, shorelineBlendRadius);
            }

            if (nearWater) {
               continue;
            }

            int expectedSurface = context.terrainSurfaces()[index];
            if (expectedSurface < this.seaLevel) {
               continue;
            }

            Holder<Biome> biome = context.sampleBiome(worldX, worldZ, expectedSurface + 1);
            if (biome.is(Biomes.MANGROVE_SWAMP) || treeFeaturesForBiome(biome).isEmpty()) {
               continue;
            }

            placements.add(new EarthChunkGenerator.PreparedTreePlacement(worldX, worldZ, expectedSurface, biome, seed));
         }
      }

      return placements.isEmpty() ? List.of() : List.copyOf(placements);
   }

   private void applyPreparedTreePlacements(WorldGenLevel level, ChunkAccess chunk, List<EarthChunkGenerator.PreparedTreePlacement> placements) {
      for (EarthChunkGenerator.PreparedTreePlacement placement : placements) {
         this.applyPreparedTreePlacement(level, placement);
      }
   }

   private void applyPreparedTreePlacement(WorldGenLevel level, EarthChunkGenerator.PreparedTreePlacement placement) {
      int worldX = placement.worldX();
      int worldZ = placement.worldZ();
      int topY = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
      if (topY < level.getMinY()
         || topY < this.seaLevel
         || placement.expectedSurface() - topY > TREE_MAX_SURFACE_DROP
         || topY - placement.expectedSurface() > TREE_MAX_SURFACE_RISE) {
         return;
      }

      BlockPos ground = new BlockPos(worldX, topY, worldZ);
      BlockState groundState = level.getBlockState(ground);
      if (isRoadDeckState(groundState)
         || isRoadDeckState(level.getBlockState(ground.below()))
         || !isSolidCaveAnchor(groundState)
         || groundState.is(BlockTags.LOGS)
         || groundState.is(BlockTags.LEAVES)) {
         return;
      }

      Holder<Biome> biome = placement.biome();
      if (biome.is(Biomes.MANGROVE_SWAMP)) {
         return;
      }

      List<ConfiguredFeature<?, ?>> features = treeFeaturesForBiome(biome);
      if (features.isEmpty()) {
         return;
      }

      BlockPos position = ground.above();
      RandomSource random = RandomSource.create(placement.seed());
      if (!groundState.is(BlockTags.DIRT)) {
         level.setBlock(ground, GRASS_BLOCK_STATE, 260);
      }

      ConfiguredFeature<?, ?> feature = features.get(random.nextInt(features.size()));
      feature.place(level, this, random, position);
   }

   private boolean isNearWater(int worldX, int worldZ, int radius) {
      for (int dz = -radius; dz <= radius; dz++) {
         int z = worldZ + dz;

         for (int dx = -radius; dx <= radius; dx++) {
            int x = worldX + dx;
            int coverClass = this.sampleCoverClass(x, z);
            WaterSurfaceResolver.WaterInfo info = this.waterResolver.resolveFastWaterInfo(x, z, coverClass);
            if (info.isWater()) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean hasWaterNear(boolean[] waterFlags, int localX, int localZ, int radius) {
      for (int z = localZ - radius; z <= localZ + radius; z++) {
         for (int x = localX - radius; x <= localX + radius; x++) {
            if (waterFlags[chunkIndex(x, z)]) {
               return true;
            }
         }
      }

      return false;
   }

   private int sampleSurfaceHeight(int blockX, int blockZ) {
      return this.sampleSurfaceHeight(blockX, blockZ, this.settings.worldScale());
   }

   private int sampleSurfaceHeightLocalOnly(int blockX, int blockZ) {
      return this.sampleSurfaceHeightLocalOnly(blockX, blockZ, this.settings.worldScale());
   }

   private int sampleSurfaceHeightMemoryOnly(int blockX, int blockZ) {
      return this.sampleSurfaceHeightMemoryOnly(blockX, blockZ, this.settings.worldScale());
   }

   private int sampleSurfaceHeight(int blockX, int blockZ, double previewResolutionMeters) {
      boolean oceanZoom = this.useOceanZoom(blockX, blockZ, previewResolutionMeters);
      double elevation;
      try {
         elevation = ELEVATION_SOURCE.samplePreviewElevationMeters(
            blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
         );
      } catch (RuntimeException error) {
         elevation = reportElevationSampleFailure(blockX, blockZ, error);
      }
      double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
      double scaled = sanitizeElevationMeters(elevation) * heightScale / this.settings.worldScale();
      int offset = this.settings.heightOffset();
      int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
      return height + offset;
   }

   /**
    * 2026-09-26: **step-by-step escalation** when the shell height is unavailable -- never flood a whole chunk with an "invented" height
    *
    * <p>Real-machine symptom: generated chunks are **sometimes entirely concave, sometimes entirely convex** (blocky), and it gets worse the more you explore.
    *
    * <h3>Cause</h3>
    * The shell critical path defaults to "read from memory only" ({@code -Dtellus.chunkgen.memoryOnlyTerrainCriticalPath=true}):
    * tiles pulled down by preload are often **on disk but not in memory** -> this column cannot be read -> a miss is recorded ->
    * {@code fillMissingTerrainShellHeights} then uses {@code seaLevel} (or the "single known sample" spread out from
    * {@code nearestKnownAnchorHeight}) to lay down **the same height across all 256 columns of the chunk**.
    * Result: terrain below sea level turns into an entirely **bulging** chunk; terrain above sea level turns into an entirely **dented** chunk.
    *
    * <h3>Escalation order (every step is real data, not one step is invented)</h3>
    * <ol>
    * <li>Memory + disk (the {@code TellusElevationSource.getTileMemoryOnly} fallback added in the previous pass) -- the caller has already tried this;</li>
    * <li><b>The full DEM chain read-only from disk</b> (Copernicus / USGS / ..., **without networking**):
    * this is exactly the height that refinement really uses, so shell and refined agree **from the very start**;</li>
    * <li><b>Allowing a network download</b> (bounded by the existing download deadline and the circuit breaker): the last resort when this tile really is not on disk;</li>
    * <li>If everything fails -> return {@code Integer.MIN_VALUE} and record the miss **honestly** (the caller's neighbour interpolation backfills it).</li>
    * </ol>
    */
   /**
    * 2026-09-29: clamp the DEM's **bad samples** (shared by every sampling exit point of this project)
    *
    * <p><b>Measured from real data</b>: decoding 527 real elevation tiles on this machine
    * ({@code tellus/cache/elevation-tellus}) turned up a minimum of **-23070 metres**,
    * while the deepest trench on Earth is only **-10994 metres**, so that is **data-source noise**
    * -- spot checking the RGB of those pixels gives {@code (80,163,138)} / {@code (78,211,228)} / {@code (77,4,62)}, i.e. random values
    * -- they are only 0.57% of the pixels of a few tiles, but **a single bad cell is enough to clamp the whole chunk to one Y** => blockiness
    *
    * <p>Clamped to {@link EarthGeneratorSettings#DEM_NODATA_METERS} (-12000):
    * at an exact 1:1 {@code worldScale=6} that lands at Y = -1936, comfortably inside the world.
    *
    * <p>Putting this here rather than at each call site is what makes the **five sampling exits** (exact / disk / memory / grid / fallback)
    * behave identically -- previously there was only clamping and no noise rejection, which is how "one outlier drags down a whole chunk" happened.
    */
   /**
    * 2026-10-05: this floor let NaN straight through.
    *
    * <p>{@code elevation < DEM_NODATA_METERS} is **false** for NaN (every comparison with NaN is
    * false), so a failed elevation sample survived as NaN and then
    * {@code Mth.floor(NaN) == 0} turned "I have no data" into a height of
    * {@code 0 + heightOffset} -- i.e. exactly sea level. That fabricated value was indistinguishable
    * from a real reading, which is how a single missing DEM tile becomes a flat plateau that then
    * gets cached and copied onto its neighbours by {@code copyOverlaps}.
    *
    * <p>Note we deliberately do NOT return {@code Integer.MIN_VALUE} from
    * {@code sampleSurfaceHeight}: it has ~30 call sites and the slope/convexity paths do
    * {@code east - center} on the result, so a MIN_VALUE there would overflow. Instead this helper
    * now honours the floor it always claimed to honour, for every non-finite input, and the caller
    * keeps getting a real int.
    */
   private static double sanitizeElevationMeters(double elevation) {
      return Double.isFinite(elevation) && elevation >= EarthGeneratorSettings.DEM_NODATA_METERS
         ? elevation
         : EarthGeneratorSettings.DEM_NODATA_METERS;
   }
   private int escalateShellHeight(int blockX, int blockZ) {
      int fromDisk = this.sampleSurfaceHeightOrUnknown(blockX, blockZ, this.settings.worldScale(), false);
      return fromDisk != Integer.MIN_VALUE
         ? fromDisk
         : this.sampleSurfaceHeightOrUnknown(blockX, blockZ, this.settings.worldScale(), true);
   }

   /**
    * Uses **exactly the same conversion** as {@link #sampleSurfaceHeight(int, int, double)}, but returns
    * {@code Integer.MIN_VALUE} when data is missing instead of inventing a sea-level height --
    * only by reporting "no data" honestly can the layer above choose to "escalate / interpolate" instead of "flattening the whole chunk".
    */
   private int sampleSurfaceHeightOrUnknown(int blockX, int blockZ, double previewResolutionMeters, boolean allowNetwork) {
      try {
         boolean oceanZoom = allowNetwork
            ? this.useOceanZoom(blockX, blockZ, previewResolutionMeters)
            : this.useOceanZoomLocalOnly(blockX, blockZ, previewResolutionMeters);
         double elevation = allowNetwork
            ? ELEVATION_SOURCE.samplePreviewElevationMeters(
               blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
            )
            : ELEVATION_SOURCE.samplePreviewElevationMetersLocalOnly(
               blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
            );
         if (Double.isNaN(elevation)) {
            return Integer.MIN_VALUE;
         }

         double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
         double scaled = sanitizeElevationMeters(elevation) * heightScale / this.settings.worldScale();
         int offset = this.settings.heightOffset();
         int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
         return height + offset;
      } catch (RuntimeException error) {
         reportElevationSampleFailure(blockX, blockZ, error);
         return Integer.MIN_VALUE;
      }
   }

   /**
    * 2026-09-26: the "report faithfully" sampling dedicated to {@link #buildHeightGrid}
    *
    * <p><b>Why a separate method</b>: {@link #sampleSurfaceHeight} / {@link #sampleSurfaceHeightLocalOnly}
    * run the whole conversion when they get "no data" (NaN), and {@code Mth.floor(NaN) == 0}
    * =&gt; returning {@code heightOffset} (usually 0 or 64) -- <b>passing "no data" off as a sea-level height</b>.
    * That fake height enters {@code heightGrid} -> is cached as authoritative ->
    * {@code copyOverlaps} copies it to 8 neighbouring chunks => <b>a square plateau across 3x3 chunks</b> (exactly the blocky bumps the players reported).
    *
    * <p>This method keeps <b>exactly the same</b> conversion as {@link #sampleSurfaceHeight},
    * with a single difference: on NaN it returns {@code Integer.MIN_VALUE}, which is already {@code buildHeightGrid}'s miss sentinel,
    * so "this tile has no data" goes through the neighbour interpolation of {@code fillMissingTerrainShellHeights}
    * instead of **laying one fake height across all 256 columns of the chunk**.
    *
    * <p><b>Only for the caching path</b>: the behaviour of the other 40-plus {@code sampleSurfaceHeight*} call sites is **completely unchanged**,
    * so there is no risk of "some subsystem suddenly receiving an extreme value".
    */
   private int sampleSurfaceHeightForHeightGrid(int blockX, int blockZ, boolean localOnly) {
      double previewResolutionMeters = this.settings.worldScale();
      boolean oceanZoom = localOnly
         ? this.useOceanZoomLocalOnly(blockX, blockZ, previewResolutionMeters)
         : this.useOceanZoom(blockX, blockZ, previewResolutionMeters);
      double elevation;
      try {
         elevation = localOnly
            ? ELEVATION_SOURCE.samplePreviewElevationMetersLocalOnly(
               blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
            )
            : ELEVATION_SOURCE.samplePreviewElevationMeters(
               blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
            );
      } catch (RuntimeException error) {
         elevation = reportElevationSampleFailure(blockX, blockZ, error);
      }

      if (Double.isNaN(elevation)) {
         return Integer.MIN_VALUE;   // Report "no data" faithfully
      }

      double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
      double scaled = sanitizeElevationMeters(elevation) * heightScale / this.settings.worldScale();
      int offset = this.settings.heightOffset();
      int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
      return height + offset;
   }

   private int sampleSurfaceHeightLocalOnly(int blockX, int blockZ, double previewResolutionMeters) {
      boolean oceanZoom = this.useOceanZoomLocalOnly(blockX, blockZ, previewResolutionMeters);
      double elevation;
      try {
         elevation = ELEVATION_SOURCE.samplePreviewElevationMetersLocalOnly(
            blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
         );
      } catch (RuntimeException error) {
         elevation = reportElevationSampleFailure(blockX, blockZ, error);
      }
      double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
      double scaled = sanitizeElevationMeters(elevation) * heightScale / this.settings.worldScale();
      int offset = this.settings.heightOffset();
      int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
      return height + offset;
   }

   private int sampleSurfaceHeightMemoryOnly(int blockX, int blockZ, double previewResolutionMeters) {
      boolean oceanZoom = this.useOceanZoomMemoryOnly(blockX, blockZ, previewResolutionMeters);
      double elevation;
      try {
         elevation = ELEVATION_SOURCE.samplePreviewElevationMetersMemoryOnly(
            blockX, blockZ, this.settings.worldScale(), oceanZoom, this.settings.demSelection(), previewResolutionMeters
         );
      } catch (RuntimeException error) {
         elevation = reportElevationSampleFailure(blockX, blockZ, error);
      }
      if (Double.isNaN(elevation)) {
         return Integer.MIN_VALUE;
      } else {
         double heightScale = elevation >= 0.0 ? this.settings.terrestrialHeightScale() : this.settings.oceanicHeightScale();
         double scaled = sanitizeElevationMeters(elevation) * heightScale / this.settings.worldScale();
         int offset = this.settings.heightOffset();
         int height = elevation >= 0.0 ? Mth.ceil(scaled) : Mth.floor(scaled);
         return height + offset;
      }
   }

   /** Counters for elevation sampling fallbacks (see {@link #reportElevationSampleFailure}). */
   private static final AtomicLong ELEVATION_SAMPLE_FAILURES = new AtomicLong();

   /** 2026-09-29: how many times a shell tile was backfilled synchronously (a successful backfill means real elevation was obtained). */
   static final AtomicLong SHELL_BLOCKING_REPAIRS = new AtomicLong();
   /** 2026-09-29: how many synchronous backfills **failed** (network / rate limit), the only case left for an "invented height fallback". */
   static final AtomicLong SHELL_BLOCKING_REPAIR_FAILURES = new AtomicLong();

   /**
    * 2026-09-26: a data-source failure must never escalate into a "chunk generation exception" that crashes the client
    *
    * <p>In a real machine log (10:35:59, chunk 176833,-59375) a cancelled tile download carried an **unchecked exception**
    * ({@code CancellationException} -> Guava {@code UncheckedExecutionException}) out of the data source,
    * which passed through worldgen and triggered {@code net.minecraft.class_148: Exception generating new chunk},
    * which Minecraft judged fatal -> the whole client exited.
    *
    * <p>The root cause is already fixed at the data-source layer (see {@code TellusDownloadExecutor} and each data source's {@code getTile}),
    * and this is the **last gate**: whatever goes wrong in the elevation chain, it degrades to "unknown elevation" and terrain generation continues --
    * an ugly chunk of terrain is acceptable, crashing the player's world generation is not.
    */
   private static double reportElevationSampleFailure(int blockX, int blockZ, RuntimeException error) {
      long failures = ELEVATION_SAMPLE_FAILURES.incrementAndGet();
      if (failures <= 5L || failures % 1024L == 0L) {
         Tellus.LOGGER.warn("[TellusCN] Elevation sampling failed at block {},{} ({} occurrence(s) so far); falling back to sea level so chunk generation"
            + " continues instead of crashing the game", blockX, blockZ, failures, error);
      } else {
         Tellus.LOGGER.debug("Elevation sampling failed at block {},{}", blockX, blockZ, error);
      }
      return Double.NaN;
   }

   private boolean useOceanZoom(double blockX, double blockZ, double previewResolutionMeters) {
      TellusLandMaskSource.LandMaskSample landSample = LAND_MASK_SOURCE.sampleLandMask(blockX, blockZ, this.settings.worldScale());
      if (!landSample.known()) {
         return true;
      } else if (landSample.land()) {
         return false;
      } else {
         int coverClass = LAND_COVER_SOURCE.sampleCoverClass(blockX, blockZ, this.settings.worldScale(), previewResolutionMeters);
         return coverClass == 0 || coverClass == 80;
      }
   }

   private boolean useOceanZoomLocalOnly(double blockX, double blockZ, double previewResolutionMeters) {
      TellusLandMaskSource.LandMaskSample landSample = LAND_MASK_SOURCE.sampleLandMaskLocalOnly(blockX, blockZ, this.settings.worldScale());
      if (!landSample.known()) {
         return true;
      } else if (landSample.land()) {
         return false;
      } else {
         int coverClass = LAND_COVER_SOURCE.sampleCoverClassLocalOnly(blockX, blockZ, this.settings.worldScale(), previewResolutionMeters);
         return coverClass == 0 || coverClass == 80;
      }
   }

   private boolean useOceanZoomMemoryOnly(double blockX, double blockZ, double previewResolutionMeters) {
      TellusLandMaskSource.LandMaskSample landSample = LAND_MASK_SOURCE.sampleLandMaskLocalOnly(blockX, blockZ, this.settings.worldScale());
      if (!landSample.known()) {
         return true;
      } else if (landSample.land()) {
         return false;
      } else {
         int coverClass = LAND_COVER_SOURCE.sampleCoverClassMemoryOnly(blockX, blockZ, this.settings.worldScale(), previewResolutionMeters);
         return coverClass == Integer.MIN_VALUE || coverClass == 0 || coverClass == 80;
      }
   }

   private int sampleCoverClassForBaseTerrain(int worldX, int worldZ) {
      return LAND_COVER_SOURCE.sampleCoverClassLocalOnly(worldX, worldZ, this.settings.worldScale(), this.settings.worldScale());
   }

   private int sampleCoverClassForExactTerrain(int worldX, int worldZ) {
      return NON_BLOCKING_TERRAIN_INPUTS
         ? this.sampleCoverClassForBaseTerrain(worldX, worldZ)
         : LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
   }

   private int sampleCoverClassForTerrainShell(int worldX, int worldZ) {
      double worldScale = this.settings.worldScale();
      int memoryOnly = LAND_COVER_SOURCE.sampleCoverClassMemoryOnly(worldX, worldZ, worldScale, worldScale);
      if (memoryOnly != Integer.MIN_VALUE) {
         return memoryOnly;
      }

      // The tile is not in memory -- fall back to the **read-only disk** (locally cached tile files), still without networking and without blocking on a download.
      //
      // This used to return MIN_VALUE directly, and the caller fillTerrainShellColumns backfilled it to NO_DATA_CLASS(0);
      // so chunks whose tile is in memory and chunks without it computed **different terrain heights** --
      // which chunks are missing a tile depends on memory state, so it showed up as patches of bumps, a checkerboard on the map.
      // After falling back to disk the cover class matches the neighbours; memory and disk differ only in read speed
      // (the tiles pulled down by preload are usually still in the OS page cache, where a hit takes microseconds).
      return LAND_COVER_SOURCE.sampleCoverClassLocalOnly(worldX, worldZ, worldScale, worldScale);
   }

   private int sampleVisualCoverClassForBaseTerrain(int worldX, int worldZ, int rawCoverClass) {
      double worldScale = this.settings.worldScale();
      return worldScale > 0.0 && worldScale < 10.0
         ? LAND_COVER_SOURCE.sampleVisualCoverClassLocalOnly(worldX, worldZ, worldScale, worldScale)
         : rawCoverClass;
   }

   private int sampleVisualCoverClassForExactTerrain(int worldX, int worldZ, int rawCoverClass) {
      return NON_BLOCKING_TERRAIN_INPUTS
         ? this.sampleVisualCoverClassForBaseTerrain(worldX, worldZ, rawCoverClass)
         : this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass);
   }

   private int sampleVisualCoverClassForTerrainShell(int worldX, int worldZ, int rawCoverClass) {
      double worldScale = this.settings.worldScale();
      return worldScale > 0.0 && worldScale < 10.0
         ? LAND_COVER_SOURCE.sampleVisualCoverClassMemoryOnly(worldX, worldZ, worldScale, worldScale)
         : rawCoverClass;
   }

   private int sampleSlopeDiff(int worldX, int worldZ, int surface) {
      return this.sampleSlopeDiff(worldX, worldZ, surface, this.settings.worldScale());
   }

   private int sampleSlopeDiff(int worldX, int worldZ, int surface, double previewResolutionMeters) {
      int step = 4;
      int east = this.sampleSurfaceHeight(worldX + step, worldZ, previewResolutionMeters);
      int west = this.sampleSurfaceHeight(worldX - step, worldZ, previewResolutionMeters);
      int north = this.sampleSurfaceHeight(worldX, worldZ - step, previewResolutionMeters);
      int south = this.sampleSurfaceHeight(worldX, worldZ + step, previewResolutionMeters);
      return Math.max(Math.max(Math.abs(east - surface), Math.abs(west - surface)), Math.max(Math.abs(north - surface), Math.abs(south - surface)));
   }

   private int sampleConvexity(int worldX, int worldZ, int surface) {
      int step = 4;
      int east = this.sampleSurfaceHeight(worldX + step, worldZ);
      int west = this.sampleSurfaceHeight(worldX - step, worldZ);
      int north = this.sampleSurfaceHeight(worldX, worldZ - step);
      int south = this.sampleSurfaceHeight(worldX, worldZ + step);
      int neighborAverage = (east + west + north + south) / 4;
      return neighborAverage - surface;
   }

   public long sampleLodSnowSlopeShape(int worldX, int worldZ) {
      int step = 4;
      double scale = this.settings.worldScale();
      int center = this.sampleSurfaceHeight(worldX, worldZ, scale);
      int east = this.sampleSurfaceHeight(worldX + step, worldZ, scale);
      int west = this.sampleSurfaceHeight(worldX - step, worldZ, scale);
      int north = this.sampleSurfaceHeight(worldX, worldZ - step, scale);
      int south = this.sampleSurfaceHeight(worldX, worldZ + step, scale);
      int slopeDiff = Math.max(
         Math.max(Math.abs(east - center), Math.abs(west - center)),
         Math.max(Math.abs(north - center), Math.abs(south - center))
      );
      int convexity = (east + west + north + south) / 4 - center;
      return (((long)slopeDiff) << 32) | ((long)convexity & 0xFFFFFFFFL);
   }

   private EarthChunkGenerator.ColumnHeights resolveColumnHeights(
      int worldX,
      int worldZ,
      int localX,
      int localZ,
      int minY,
      int maxYExclusive,
      int coverClass,
      WaterSurfaceResolver.WaterChunkData waterData,
      int cachedSurface
   ) {
      int maxY = Math.max(minY, maxYExclusive - 1);
      if (coverClass == 95) {
         if (this.settings.enableWater()) {
            WaterSurfaceResolver.WaterColumnData merged = this.mergeMangroveWaterColumn(
               worldX,
               worldZ,
               new WaterSurfaceResolver.WaterColumnData(
                  waterData.hasWater(localX, localZ),
                  waterData.isOcean(localX, localZ),
                  waterData.terrainSurface(localX, localZ),
                  waterData.waterSurface(localX, localZ)
               )
            );
            return this.columnHeightsFromWaterColumn(merged, minY, maxY);
         } else {
            int surface = cachedSurface == Integer.MIN_VALUE ? this.sampleSurfaceHeight(worldX, worldZ) : cachedSurface;
            surface = Mth.clamp(surface, minY, maxY);
            int waterSurface = this.resolveMangroveWaterSurface(worldX, worldZ, maxY);
            boolean hasWater = waterSurface > surface;
            return new EarthChunkGenerator.ColumnHeights(surface, waterSurface, hasWater);
         }
      } else {
         int surface = Mth.clamp(cachedSurface == Integer.MIN_VALUE ? waterData.terrainSurface(localX, localZ) : cachedSurface, minY, maxY);
         int waterSurface = Mth.clamp(waterData.waterSurface(localX, localZ), minY, maxY);
         boolean hasWater = waterData.hasWater(localX, localZ);
         return !hasWater
            ? new EarthChunkGenerator.ColumnHeights(surface, surface, false)
            // 2026-09-26: when there is water, **keep using the surface from the height grid**
            // This used to drop the surface and use waterData.terrainSurface(...) instead, which is **a different sampling chain**
            // (the oceanZoom decision of the water region is not the same one as the 4534 used here),
            // and the two chains can differ by tens of blocks -- and that difference is exactly a **16x16 square step**.
            // Now only "at most 1 block below the water surface" is allowed (the semantics of the old logic), and no foreign height is ever introduced.
            : new EarthChunkGenerator.ColumnHeights(
               Math.min(surface, Math.max(minY, waterSurface - 1)), waterSurface, true
            );
      }
   }

   private EarthChunkGenerator.ColumnHeights resolveFastColumnHeights(int worldX, int worldZ, int minY, int maxYExclusive, int coverClass) {
      int maxY = Math.max(minY, maxYExclusive - 1);
      if (this.settings.enableWater()) {
         WaterSurfaceResolver.WaterColumnData column = this.normalizeResolvedWaterColumn(
            worldX,
            worldZ,
            coverClass,
            minY,
            maxY,
            this.resolveOsmWaterColumn(worldX, worldZ, coverClass, false)
         );
         return this.columnHeightsFromWaterColumn(column, minY, maxY);
      } else {
         int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClass);
         WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(worldX, worldZ);
         int surface = Mth.clamp(column.terrainSurface(), minY, maxY);
         surface = this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, minY, maxY, column.hasWater());
         int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
         if (!column.hasWater()) {
            return new EarthChunkGenerator.ColumnHeights(surface, surface, false);
         } else {
            if (surface >= waterSurface) {
               surface = Math.max(minY, waterSurface - 1);
            }

            return new EarthChunkGenerator.ColumnHeights(surface, waterSurface, true);
         }
      }
   }

   private int resolveMangroveWaterSurface(int worldX, int worldZ, int maxY) {
      long seed = seedFromCoords(worldX, 1, worldZ) ^ -7046029254386353131L;
      int offset = 1 + seededRandomInt(seed, 3);
      int waterTop = Math.min(this.seaLevel, maxY);
      return Math.min(waterTop, this.seaLevel - offset);
   }

   private static long seedFromCoords(int x, int y, int z) {
      long seed = x * 3129871 ^ z * 116129781L ^ y;
      seed = seed * seed * 42317861L + seed * 11L;
      return seed >> 16;
   }

   // Reproduces new Random(seed).nextInt(bound) without allocating a Random.
   private static int seededRandomInt(long seed, int bound) {
      if (bound <= 0) {
         throw new IllegalArgumentException("Radius must be positive");
      }

      long state = (seed ^ JAVA_RANDOM_MULTIPLIER) & JAVA_RANDOM_MASK;
      if ((bound & -bound) == bound) {
         state = nextJavaRandomState(state);
         return (int)(((long)bound * (long)((int)(state >>> 17))) >> 31);
      }

      int bits;
      int value;
      do {
         state = nextJavaRandomState(state);
         bits = (int)(state >>> 17);
         value = bits % bound;
      } while (bits - value + (bound - 1) < 0);

      return value;
   }

   private static long nextJavaRandomState(long state) {
      return (state * JAVA_RANDOM_MULTIPLIER + JAVA_RANDOM_ADDEND) & JAVA_RANDOM_MASK;
   }

   private void applySurface(
      ChunkAccess chunk,
      MutableBlockPos cursor,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      boolean underwater,
      Holder<Biome> biome,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      if (surface >= minY) {
         long paletteResolveStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.SurfacePalette palette = this.selectFullChunkSurfacePalette(
            biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache, profiler, useFastSurfacePalette
         );
         profiler.paletteResolveNs += elapsedFullChunkProfilingSince(paletteResolveStartNs);
         if (palette != null) {
            if (!underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= 3) {
               long badlandsStartNs = beginFullChunkProfiling();
               applyBadlandsBands(chunk, cursor, worldX, worldZ, surface, minY, palette);
               profiler.badlandsNs += elapsedFullChunkProfilingSince(badlandsStartNs);
            } else {
               BlockState top = underwater ? palette.underwaterTop() : palette.top();
               BlockState filler = palette.filler();
               int depth = palette.depth();
               int bottom = surfaceMaterialBottomY(minY, surface, depth);

               long blockWriteStartNs = beginFullChunkProfiling();
               for (int y = surface; y >= bottom; y--) {
                  cursor.set(worldX, y, worldZ);
                  chunk.setBlockState(cursor, y == surface ? top : filler);
               }
               profiler.blockWriteNs += elapsedFullChunkProfilingSince(blockWriteStartNs);
            }
         }
      }
   }

   private void applySurface(
      EarthChunkGenerator.ChunkSectionWriter writer,
      int worldX,
      int worldZ,
      int localX,
      int localZ,
      int surface,
      int minY,
      boolean underwater,
      Holder<Biome> biome,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      if (surface >= minY) {
         long paletteResolveStartNs = beginFullChunkProfiling();
         EarthChunkGenerator.SurfacePalette palette = this.selectFullChunkSurfacePalette(
            biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache, profiler, useFastSurfacePalette
         );
         profiler.paletteResolveNs += elapsedFullChunkProfilingSince(paletteResolveStartNs);
         if (palette != null) {
            if (!underwater && biome.is(BiomeTags.IS_BADLANDS) && slopeDiff >= 3) {
               long badlandsStartNs = beginFullChunkProfiling();
               applyBadlandsBands(writer, localX, localZ, worldX, worldZ, surface, minY, palette);
               profiler.badlandsNs += elapsedFullChunkProfilingSince(badlandsStartNs);
            } else {
               BlockState top = underwater ? palette.underwaterTop() : palette.top();
               BlockState filler = palette.filler();
               int depth = palette.depth();
               int bottom = surfaceMaterialBottomY(minY, surface, depth);

               long blockWriteStartNs = beginFullChunkProfiling();
               writer.fillSurfaceColumn(localX, localZ, surface, bottom, top, filler);
               profiler.blockWriteNs += elapsedFullChunkProfilingSince(blockWriteStartNs);
            }
         }
      }
   }

   private EarthChunkGenerator.SurfacePalette selectFullChunkSurfacePalette(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache,
      EarthChunkGenerator.SurfaceApplyProfiler profiler,
      boolean useFastSurfacePalette
   ) {
      if (!underwater && this.isRemaSnowTerrain(worldZ)) {
         return EarthChunkGenerator.SurfacePalette.snowy();
      } else {
         EarthChunkGenerator.SurfacePalette palette = this.selectBaseSurfacePalette(biome, worldX, worldZ, surface, coverClass);
         if (palette == null) {
            return null;
         } else {
            palette = this.applyFullChunkOceanBeachPaletteOverride(
               palette, biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, oceanBeachCache
            );
            if (palette == null) {
               return null;
            } else {
               EarthChunkGenerator.SurfacePalette resolved;
               if (useFastSurfacePalette) {
                  if (underwater) {
                     profiler.fastPathCount++;
                     resolved = palette;
                  } else if (this.shouldUseDetailedMountainSurface(coverClass, surface, slopeDiff, convexity)) {
                     resolved = this.applySlopeSurfaceOverride(palette, false, slopeDiff, convexity, coverClass, biome, worldX, worldZ, surface, null);
                  } else {
                     profiler.fastPathCount++;
                     MountainSurfaceRules.ApproximateSurface approximate = MountainSurfaceRules.classifyApproximateSurface(
                        coverClass, coverClass, surface - this.seaLevel, slopeDiff, convexity, this.isRemaSnowTerrain(worldZ)
                     );
                     resolved = mapApproximateSurfacePalette(approximate, palette);
                  }
               } else {
                  resolved = this.applySlopeSurfaceOverride(palette, underwater, slopeDiff, convexity, coverClass, biome, worldX, worldZ, surface, null);
               }

               return this.applyOvertureSandPaletteOverride(resolved, worldX, worldZ, underwater);
            }
         }
      }
   }

   private boolean shouldUseDetailedMountainSurface(int surfaceCoverClass, int surface, int slopeDiff, int convexity) {
      return MountainSurfaceRules.qualifiesForMountainPalette(surfaceCoverClass, surface - this.seaLevel, slopeDiff, convexity);
   }

   private EarthChunkGenerator.SurfacePalette applyFullChunkOceanBeachPaletteOverride(
      EarthChunkGenerator.SurfacePalette palette,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache
   ) {
      if (underwater || oceanBeachCache == null || !oceanBeachCache.hasOcean() || !qualifiesForOceanBeachOverride(coverClass)) {
         return palette;
      } else {
         int distanceToOcean = oceanBeachCache.distanceToOcean(worldX, worldZ);
         if (distanceToOcean < 0) {
            return palette;
         } else {
            MountainSurfaceRules.ShorelineMaterial material = MountainSurfaceRules.classifyShorelineMaterial(
               coverClass,
               climateGroupForBiome(biome),
               surface - this.seaLevel,
               slopeDiff,
               convexity,
               MountainSurfaceRules.ShorelineKind.OCEAN,
               distanceToOcean,
               biome.is(BiomeTags.IS_BADLANDS)
            );
            return switch (material) {
               case NONE, PRESERVE_WETLAND -> palette;
               case SAND, RED_SAND -> palette;
               case GRAVEL -> EarthChunkGenerator.SurfacePalette.gravelly();
            };
         }
      }
   }

   private static boolean qualifiesForOceanBeachOverride(int coverClass) {
      return coverClass != MountainSurfaceRules.ESA_WATER
         && coverClass != MountainSurfaceRules.ESA_BUILT
         && coverClass != MountainSurfaceRules.ESA_SNOW_ICE
         && coverClass != MountainSurfaceRules.ESA_WETLAND
         && coverClass != MountainSurfaceRules.ESA_MANGROVES;
   }

   private static void applyBadlandsBands(
      ChunkAccess chunk, MutableBlockPos cursor, int worldX, int worldZ, int surface, int minY, EarthChunkGenerator.SurfacePalette palette
   ) {
      int depth = Math.max(palette.depth(), 16);
      int bottom = surfaceMaterialBottomY(minY, surface, depth);
      int offset = badlandsBandOffset(worldX, worldZ);
      BlockState top = palette.top();

      for (int y = surface; y >= bottom; y--) {
         cursor.set(worldX, y, worldZ);
         BlockState state = y == surface ? top : badlandsBand(y, offset);
         chunk.setBlockState(cursor, state);
      }
   }

   private static void applyBadlandsBands(
      EarthChunkGenerator.ChunkSectionWriter writer,
      int localX,
      int localZ,
      int worldX,
      int worldZ,
      int surface,
      int minY,
      EarthChunkGenerator.SurfacePalette palette
   ) {
      int depth = Math.max(palette.depth(), 16);
      int bottom = surfaceMaterialBottomY(minY, surface, depth);
      int offset = badlandsBandOffset(worldX, worldZ);
      writer.fillBadlandsBands(localX, localZ, surface, bottom, palette.top(), offset);
   }

   private static int surfaceMaterialBottomY(int minY, int surface, int depth) {
      // Bedrock is placed at minY during terrain fill; keep falling fillers one block above it.
      return Math.max(minY + 1, surface - depth + 1);
   }

   private static int badlandsBandOffset(int worldX, int worldZ) {
      int cellX = Math.floorDiv(worldX, 32);
      int cellZ = Math.floorDiv(worldZ, 32);
      long seed = seedFromCoords(cellX, 2, cellZ) ^ 12995809138867221L;
      int range = 3 * BADLANDS_BANDS.length;
      return Math.floorMod((int)seed, range);
   }


   private static BlockState badlandsBand(int y, int offset) {
      int index = Math.floorDiv(y + offset, 3);
      int bandIndex = Math.floorMod(index, BADLANDS_BANDS.length);
      return BADLANDS_BANDS[bandIndex];
   }


   public BlockState resolveLodSurfaceBlock(int worldX, int worldZ, int surface, boolean underwater) {
      if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
         Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
         return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater).top();
      } else {
         return STONE_STATE;
      }
   }


   public BlockState resolveLodFillerBlock(int worldX, int worldZ, int surface, boolean underwater) {
      if (this.biomeSource instanceof EarthBiomeSource earthBiomeSource) {
         Holder<Biome> biome = earthBiomeSource.getBiomeAtBlock(worldX, worldZ);
         return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater).filler();
      } else {
         return STONE_STATE;
      }
   }

   public boolean hasOvertureSandAt(int worldX, int worldZ) {
      return OSM_SAND_SOURCE.containsSand(worldX, worldZ, this.settings.worldScale(), this.resolveFullChunkOsmQueryMode());
   }


   public EarthChunkGenerator.LodSurface resolveLodSurface(Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater) {
      int rawCoverClass = this.sampleCoverClass(worldX, worldZ);
      int visualCoverClass = this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass);
   }


   public EarthChunkGenerator.LodSurface resolveLodSurface(Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater, int coverClass) {
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, coverClass, coverClass);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome, int worldX, int worldZ, int surface, boolean underwater, int rawCoverClass, int visualCoverClass
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, profiler);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, null, shorelineCache);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      int slopeDiff = this.sampleSlopeDiff(worldX, worldZ, surface);
      int convexity = this.sampleConvexity(worldX, worldZ, surface);
      return this.resolveLodSurface(biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, profiler, shorelineCache);
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity
   ) {
      return this.resolveLodSurface(
         biome,
         worldX,
         worldZ,
         surface,
         underwater,
         rawCoverClass,
         visualCoverClass,
         slopeDiff,
         convexity,
         (EarthChunkGenerator.LodSurfaceProfiler)null,
         null
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      return this.resolveLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, null, shorelineCache
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodSurfaceProfiler profiler
   ) {
      return this.resolveLodSurface(
         biome, worldX, worldZ, surface, underwater, rawCoverClass, visualCoverClass, slopeDiff, convexity, profiler, null
      );
   }

   public EarthChunkGenerator.LodSurface resolveLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      long phaseStart = beginLodSurfaceProfiling(profiler);
      int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(rawCoverClass);
      endLodSurfaceProfiling(profiler, "generator.effectiveCover", phaseStart);
      phaseStart = beginLodSurfaceProfiling(profiler);
      int surfaceCoverClass = this.resolveSurfaceCoverClassForTerrain(effectiveCoverClass, visualCoverClass);
      endLodSurfaceProfiling(profiler, "generator.surfaceCoverClass", phaseStart);
      EarthChunkGenerator.SurfacePalette palette = this.selectSurfacePalette(
         biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, surfaceCoverClass, profiler, shorelineCache
      );
      if (palette == null) {
         profilerAddPhase(profiler, "generator.result", 0L);
         return new EarthChunkGenerator.LodSurface(STONE_STATE, STONE_STATE);
      } else {
         phaseStart = beginLodSurfaceProfiling(profiler);
         BlockState top = underwater ? palette.underwaterTop() : palette.top();
         EarthChunkGenerator.LodSurface lodSurface = new EarthChunkGenerator.LodSurface(top, palette.filler());
         endLodSurfaceProfiling(profiler, "generator.result", phaseStart);
         return lodSurface;
      }
   }

   public EarthChunkGenerator.LodSurface resolveUltraFastLodSurface(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int rawCoverClass,
      int visualCoverClass,
      int slopeDiff,
      int convexity,
      boolean snowLikeTerrain
   ) {
      int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(rawCoverClass);
      int surfaceCoverClass = this.resolveSurfaceCoverClassForTerrain(effectiveCoverClass, visualCoverClass);
      EarthChunkGenerator.SurfacePalette palette;
      if (!underwater && snowLikeTerrain) {
         palette = EarthChunkGenerator.SurfacePalette.snowy();
      } else {
         palette = this.selectBaseSurfacePalette(biome, worldX, worldZ, surface, surfaceCoverClass);
         if (palette == null) {
            return new EarthChunkGenerator.LodSurface(STONE_STATE, STONE_STATE);
         }

         int heightAboveSea = surface - this.seaLevel;
         if (!underwater) {
            if (surfaceCoverClass == MountainSurfaceRules.ESA_SNOW_ICE) {
               if (this.shouldRetainMountainSnow(surfaceCoverClass, heightAboveSea, slopeDiff, convexity, worldX, worldZ)) {
                  palette = EarthChunkGenerator.SurfacePalette.snowy();
               } else {
                  MountainSurfaceRules.ApproximateSurface rockSurface = MountainSurfaceRules.classifyApproximateSurface(
                     MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, heightAboveSea, slopeDiff, convexity, false
                  );
                  palette = rockSurface.isMountain()
                     ? mapApproximateSurfacePalette(rockSurface, EarthChunkGenerator.SurfacePalette.stonyPeaks())
                     : EarthChunkGenerator.SurfacePalette.stonyPeaks();
               }
            } else {
               MountainSurfaceRules.ApproximateSurface mountainSurface = MountainSurfaceRules.classifyApproximateSurface(
                  surfaceCoverClass, surfaceCoverClass, heightAboveSea, slopeDiff, convexity, false
               );
               if (mountainSurface.isMountain()) {
                  palette = mapApproximateSurfacePalette(mountainSurface, palette);
               } else if (surfaceCoverClass != MountainSurfaceRules.ESA_TREE_COVER && isSoilPalette(palette) && slopeDiff >= 3) {
                  palette = EarthChunkGenerator.SurfacePalette.stonyPeaks();
               }
            }
         }

         palette = this.applyOvertureSandPaletteOverride(palette, worldX, worldZ, underwater);
      }

      BlockState top = underwater ? palette.underwaterTop() : palette.top();
      return new EarthChunkGenerator.LodSurface(top, palette.filler());
   }

   public EarthChunkGenerator.LodShorelineCache buildLodShorelineCache(int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ) {
      return this.buildLodShorelineCache(minWorldX, maxWorldX, minWorldZ, maxWorldZ, this.settings.worldScale());
   }

   public EarthChunkGenerator.LodShorelineCache buildLodShorelineCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ, double previewResolutionMeters
   ) {
      int minX = minWorldX - OCEAN_SHORE_MAX_DISTANCE;
      int maxX = maxWorldX + OCEAN_SHORE_MAX_DISTANCE;
      int minZ = minWorldZ - OCEAN_SHORE_MAX_DISTANCE;
      int maxZ = maxWorldZ + OCEAN_SHORE_MAX_DISTANCE;
      long width = (long)maxX - (long)minX + 1L;
      long height = (long)maxZ - (long)minZ + 1L;
      long area = width * height;
      return area > (long)LOD_SHORELINE_DENSE_CACHE_MAX_AREA
         ? new EarthChunkGenerator.SparseLodShorelineCache(previewResolutionMeters)
         : this.buildDenseLodShorelineCache(minX, maxX, minZ, maxZ, (int)width, (int)height, previewResolutionMeters);
   }

   public EarthChunkGenerator.LodSharedTerrainCache buildLodSharedTerrainCache(int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ) {
      return this.buildLodSharedTerrainCache(minWorldX, maxWorldX, minWorldZ, maxWorldZ, this.settings.worldScale());
   }

   public EarthChunkGenerator.LodSharedTerrainCache buildLodSharedTerrainCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ, double previewResolutionMeters
   ) {
      int coverMinX = minWorldX - 48;
      int coverMaxX = maxWorldX + 48;
      int coverMinZ = minWorldZ - 48;
      int coverMaxZ = maxWorldZ + 48;
      long coverWidth = (long)coverMaxX - (long)coverMinX + 1L;
      long coverHeight = (long)coverMaxZ - (long)coverMinZ + 1L;
      long coverArea = coverWidth * coverHeight;
      int shoreMinX = minWorldX - OCEAN_SHORE_MAX_DISTANCE;
      int shoreMaxX = maxWorldX + OCEAN_SHORE_MAX_DISTANCE;
      int shoreMinZ = minWorldZ - OCEAN_SHORE_MAX_DISTANCE;
      int shoreMaxZ = maxWorldZ + OCEAN_SHORE_MAX_DISTANCE;
      long shoreWidth = (long)shoreMaxX - (long)shoreMinX + 1L;
      long shoreHeight = (long)shoreMaxZ - (long)shoreMinZ + 1L;
      long shoreArea = shoreWidth * shoreHeight;
      return coverArea <= (long)LOD_MOUNTAIN_TRANSITION_DENSE_CACHE_MAX_AREA && shoreArea <= (long)LOD_SHORELINE_DENSE_CACHE_MAX_AREA
         ? this.buildDenseLodSharedTerrainCache(
            coverMinX,
            coverMaxX,
            coverMinZ,
            coverMaxZ,
            (int)coverWidth,
            (int)coverHeight,
            shoreMinX,
            shoreMaxX,
            shoreMinZ,
            shoreMaxZ,
            (int)shoreWidth,
            (int)shoreHeight,
            previewResolutionMeters
         )
         : null;
   }

   public EarthChunkGenerator.LodMountainTransitionCache buildLodMountainTransitionCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ
   ) {
      return this.buildLodMountainTransitionCache(minWorldX, maxWorldX, minWorldZ, maxWorldZ, this.settings.worldScale());
   }

   public EarthChunkGenerator.LodMountainTransitionCache buildLodMountainTransitionCache(
      int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ, double previewResolutionMeters
   ) {
      int maxTransitionDistance = 48;
      int minX = minWorldX - maxTransitionDistance;
      int maxX = maxWorldX + maxTransitionDistance;
      int minZ = minWorldZ - maxTransitionDistance;
      int maxZ = maxWorldZ + maxTransitionDistance;
      long width = (long)maxX - (long)minX + 1L;
      long height = (long)maxZ - (long)minZ + 1L;
      long area = width * height;
      return area > (long)LOD_MOUNTAIN_TRANSITION_DENSE_CACHE_MAX_AREA
         ? null
         : this.buildDenseLodMountainTransitionCache(minX, maxX, minZ, maxZ, (int)width, (int)height, previewResolutionMeters);
   }

   public void setLodMountainTransitionCache(EarthChunkGenerator.LodMountainTransitionCache cache) {
      if (cache == null) {
         this.lodMountainTransitionCache.remove();
      } else {
         this.lodMountainTransitionCache.set(cache);
      }
   }

   public void clearLodMountainTransitionCache() {
      this.lodMountainTransitionCache.remove();
   }

   public void setLodShorelineOverrideSuppressed(boolean suppressed) {
      if (suppressed) {
         this.lodShorelineOverrideSuppressed.set(Boolean.TRUE);
      } else {
         this.lodShorelineOverrideSuppressed.remove();
      }
   }

   public void clearLodShorelineOverrideSuppressed() {
      this.lodShorelineOverrideSuppressed.remove();
   }

   public void processDeferredChunkDetailTick(ServerLevel level) {
      this.reapStalePreparedChunkState(level.getGameTime());
      if (this.usesDeferredTerrainRefinement()) {
         this.terrainRefinementManager.applyReady(level, this, TERRAIN_REFINEMENT_APPLY_BUDGET_PER_TICK);
      }

      if (this.usesDeferredChunkDetails()) {
         this.chunkDetailManager.prefetchAroundPlayers(level, this, CHUNK_DETAIL_PREFETCH_RADIUS);
         if (this.hasDeferredApplyWork()) {
            this.chunkDetailManager.applyReady(level, this, CHUNK_DETAIL_APPLY_BUDGET_PER_TICK);
         }
      }
   }

   private void applyReadyDeferredChunkDetail(WorldGenLevel level, ChunkAccess chunk) {
      if (this.hasDeferredApplyWork()) {
         EarthChunkGenerator.PreparedChunkDetail detail = this.chunkDetailManager.claimReady(chunk.getPos());
         if (detail != null) {
            this.applyPreparedChunkDetail(level, chunk, detail);
         }
      }
   }

   private EarthChunkGenerator.PreparedChunkDetail prepareDeferredChunkDetail(EarthChunkGenerator.ChunkGenerationContext context) {
      EarthChunkGenerator.PreparedChunkDetail detail = this.buildPreparedChunkDetail(
         context, this.shouldDeferBuildingDetails(), this.shouldDeferBuildingDetails(), this.shouldDeferRoadDetails(), this.shouldDeferTrees(), OsmQueryMode.NON_BLOCKING
      );
      if (this.shouldDeferDetailedWater()) {
         this.waterResolver.prefetchRegionsForChunk(context.pos().x(), context.pos().z(), Math.max(1, CHUNK_DETAIL_PREFETCH_RADIUS));
      }

      return detail;
   }

   private EarthChunkGenerator.PreparedChunkDetail buildPreparedChunkDetail(
      EarthChunkGenerator.ChunkGenerationContext context,
      boolean prepareBuildings,
      boolean placeBuildings,
      boolean prepareRoads,
      boolean prepareTrees,
      OsmQueryMode queryMode
   ) {
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = prepareBuildings
         ? this.buildChunkBuildings(
            context.pos(),
            context.terrainSurfaces(),
            context.minY(),
            context.maxY(),
            (feature, baseY) -> this.sampleBuildingBiome(feature, baseY, context),
            queryMode
         )
         : context.preparedBuildings();
      boolean shouldPlaceBuildings = placeBuildings && preparedBuildings != null && !preparedBuildings.isEmpty();
      EarthChunkGenerator.OsmRoadQueryResult roadQuery = prepareRoads ? this.fetchDeferredRoadQuery(context.pos(), queryMode) : null;
      List<EarthChunkGenerator.PreparedTreePlacement> treePlacements = List.of();
      if (prepareTrees) {
         long treePrepStartNs = beginFullChunkProfiling();
         treePlacements = this.prepareDeferredTreePlacements(context, preparedBuildings);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_TREE_PREP, treePrepStartNs);
      }

      return new EarthChunkGenerator.PreparedChunkDetail(context, preparedBuildings, shouldPlaceBuildings, roadQuery, treePlacements);
   }

   private EarthChunkGenerator.OsmRoadQueryResult fetchDeferredRoadQuery(ChunkPos pos, OsmQueryMode queryMode) {
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      int chunkMaxX = chunkMinX + CHUNK_MASK;
      int chunkMaxZ = chunkMinZ + CHUNK_MASK;
      return this.fetchOsmRoadsForAreaDetailed(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, OSM_ROAD_QUERY_MARGIN, queryMode);
   }

   private EarthChunkGenerator.PreparedTerrainRefinement buildPreparedTerrainRefinement(EarthChunkGenerator.TerrainShellBuildResult shell) {
      ChunkPos pos = shell.pos();
      int step = 4;
      int gridSize = 16 + step * 2;
      EarthChunkGenerator.HeightGridBuildResult exactHeightGrid = this.buildHeightGrid(pos, step, gridSize, FAST_FULL_CHUNK, NON_BLOCKING_TERRAIN_INPUTS);
      int[] heightGrid = exactHeightGrid.heightGrid();
      int[] rawCoverClasses = new int[CHUNK_AREA];
      int[] terrainSurfaces = new int[CHUNK_AREA];
      int[] waterSurfaces = new int[CHUNK_AREA];
      boolean[] waterFlags = new boolean[CHUNK_AREA];
      int[] coverClasses = new int[CHUNK_AREA];
      int[] visualCoverClasses = new int[CHUNK_AREA];
      int[] surfaceCoverClasses = new int[CHUNK_AREA];
      boolean[] oceanFlags = new boolean[CHUNK_AREA];
      int[] slopeDiffs = new int[CHUNK_AREA];
      int[] convexities = new int[CHUNK_AREA];
      int chunkMinY = shell.minY();
      int chunkMaxY = shell.maxY() + 1;

      copyChunkTerrainSurfaces(heightGrid, gridSize, step, terrainSurfaces);

      this.fillBaseTerrainRawCoverClasses(pos, rawCoverClasses);
      WaterSurfaceResolver.WaterChunkData exactWaterData = this.buildExactWaterChunkData(pos, terrainSurfaces, rawCoverClasses);
      this.fillExactChunkTerrainColumns(
         pos,
         chunkMinY,
         chunkMaxY,
         step,
         gridSize,
         heightGrid,
         exactWaterData,
         rawCoverClasses,
         terrainSurfaces,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         surfaceCoverClasses,
         oceanFlags,
         false
      );

      this.repairAnomalousChunkTerrain(terrainSurfaces, waterSurfaces, waterFlags, coverClasses, heightGrid, gridSize, step, chunkMinY, shell.maxY());
      EarthBiomeSource earthBiomeSource = this.biomeSource instanceof EarthBiomeSource typedEarthBiomeSource ? typedEarthBiomeSource : null;
      EarthChunkGenerator.ChunkBiomeClimateCache climateCache = FAST_FULL_CHUNK && earthBiomeSource != null
         ? new EarthChunkGenerator.ChunkBiomeClimateCache(pos, this.settings.worldScale())
         : null;
      Holder<Biome>[] biomeCache = newBiomeCache(CHUNK_AREA);
      this.fillChunkTerrainMetricsAndBiomes(
         pos,
         step,
         gridSize,
         heightGrid,
         terrainSurfaces,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         oceanFlags,
         slopeDiffs,
         convexities,
         biomeCache,
         earthBiomeSource,
         climateCache,
         null,
         shell.biomeCache()
      );

      EarthChunkGenerator.ChunkGenerationContext exactContext = EarthChunkGenerator.ChunkGenerationContext.capture(
         pos, chunkMinY, shell.maxY(), terrainSurfaces, waterSurfaces, waterFlags, coverClasses, biomeCache, null, shell.generationStamp()
      );
      EarthChunkGenerator.PreparedChunkDetail delayedDetail = this.preparePostRefinementChunkDetail(exactContext);
      return new EarthChunkGenerator.PreparedTerrainRefinement(
         shell,
         exactContext,
         delayedDetail,
         terrainSurfaces,
         waterSurfaces,
         waterFlags,
         coverClasses,
         visualCoverClasses,
         surfaceCoverClasses,
         oceanFlags,
         slopeDiffs,
         convexities
      );
   }

   private EarthChunkGenerator.PreparedChunkDetail preparePostRefinementChunkDetail(EarthChunkGenerator.ChunkGenerationContext context) {
      return this.buildPreparedChunkDetail(context, true, true, true, true, this.resolveFullChunkOsmQueryMode());
   }

   private WaterSurfaceResolver.WaterChunkData buildExactWaterChunkData(ChunkPos pos, int[] terrainSurfaces, int[] rawCoverClasses) {
      int[] waterTerrain = Arrays.copyOf(terrainSurfaces, terrainSurfaces.length);
      int[] waterSurfaces = Arrays.copyOf(terrainSurfaces, terrainSurfaces.length);
      byte[] waterFlags = new byte[terrainSurfaces.length];
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();

      for (int localX = 0; localX < CHUNK_SIDE; localX++) {
         int worldX = chunkMinX + localX;

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = chunkMinZ + localZ;
            int index = chunkIndex(localX, localZ);
            WaterSurfaceResolver.WaterColumnData column = this.waterResolver.resolveColumnData(
               worldX, worldZ, rawCoverClasses[index], this.settings.worldScale()
            );
            waterTerrain[index] = column.terrainSurface();
            waterSurfaces[index] = column.waterSurface();
            waterFlags[index] = !column.hasWater() ? 0 : (byte)(column.isOcean() ? 2 : 1);
         }
      }

      return WaterSurfaceResolver.WaterChunkData.fromArrays(waterTerrain, waterSurfaces, waterFlags, false);
   }

   /** Remember this chunk's per-column surface heights (called during the fill stage) so that the carving stage can set a "carve only below the surface" guard */
   private void rememberCaveSurfaces(ChunkPos pos, int[] terrainSurfaces) {
      if (!CAVES_FOLLOW_TERRAIN || this.caveSurfaceByChunk == null || terrainSurfaces == null) {
         return;
      }

      this.caveSurfaceByChunk.put(ChunkPos.pack(pos.x(), pos.z()), terrainSurfaces.clone());
   }

   /**
    * Fold the "per-column surface height" into the carving guard -- this can only make the protected range larger and can never relax existing protection.
    *
    * <p>The semantics of {@code CarvingMask.get} are {@code additionalMask.test(..) || bitset.get(..)},
    * so positions that evaluate to true are skipped by the carver as "already carved", which makes the guard "protect y &gt;= guard".
    * Buildings all stand above the surface -> they all fall into the protected range; a mountain has a high surface -> caves still form below it.
    *
    * <p>When the per-column surfaces are unavailable (cache miss / old world / switched off) this returns as-is,
    * so the behaviour is bit-for-bit identical to before the change (hard rule: the default behaviour must not change).
    */
   private void applyTerrainSurfaceCaveGuard(ChunkAccess chunk, int[] floodGuardYByColumn) {
      if (!CAVES_FOLLOW_TERRAIN || this.caveSurfaceByChunk == null) {
         return;
      }

      ChunkPos pos = chunk.getPos();
      int[] surfaces = this.caveSurfaceByChunk.get(ChunkPos.pack(pos.x(), pos.z()));
      if (surfaces == null || surfaces.length < CHUNK_AREA) {
         return;
      }

      int appliedCount = 0;
      int knownCount = 0;
      int sampleSurface = Integer.MIN_VALUE;
      for (int index = 0; index < CHUNK_AREA; index++) {
         int surface = surfaces[index];
         if (surface == Integer.MIN_VALUE) {
            continue;
         }

         knownCount++;
         if (sampleSurface == Integer.MIN_VALUE) {
            sampleSurface = surface;
         }

         int merged = Math.min(floodGuardYByColumn[index], surface);
         if (merged != floodGuardYByColumn[index]) {
            floodGuardYByColumn[index] = merged;
            appliedCount++;
         }
      }

      // Logged once only when the "per-column surfaces were found", whether or not the guard really got tighter,
      // so the log can tell "the mechanism did not engage" apart from "this chunk's guard was already below the surface (an all-ocean chunk)".
      if (knownCount > 0 && CAVE_SURFACE_GUARD_LOGGED.compareAndSet(false, true)) {
         Tellus.LOGGER.info("[TellusCN] Per-column cave surface guard enabled: {} of 256 columns got per-column surfaces, "
            + "where the guard was tightened to the surface for {} columns (carving only below the surface), so 1:1 city "
            + "buildings and landmarks are no longer punctured by caves, while mountains still get caves below the surface. "
            + "Sample surface Y={} (negative means below sea level). Restore old behaviour: -Dtellus.caves.followTerrain=false",
            knownCount, appliedCount, sampleSurface);
      }
   }

   private static void copyChunkTerrainSurfaces(int[] heightGrid, int gridSize, int step, int[] terrainSurfaces) {
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int sourceIndex = (localZ + step) * gridSize + step;
         System.arraycopy(heightGrid, sourceIndex, terrainSurfaces, localZ * CHUNK_SIDE, CHUNK_SIDE);
      }
   }

   private EarthChunkGenerator.TerrainShellColumnFillResult fillTerrainShellColumns(
      ChunkPos pos,
      int chunkMinY,
      int chunkMaxY,
      int step,
      int gridSize,
      int[] heightGrid,
      WaterSurfaceResolver.WaterChunkData waterData,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags
   ) {
      long surfaceCoverResolveNs = 0L;
      int shellCoverMisses = 0;
      int shellVisualCoverMisses = 0;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      boolean waterEnabled = this.settings.enableWater();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int gridRowIndex = (localZ + step) * gridSize + step;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int sampledCoverClass = this.sampleCoverClassForTerrainShell(worldX, worldZ);
            if (sampledCoverClass == Integer.MIN_VALUE) {
               shellCoverMisses++;
               sampledCoverClass = 0;
            }

            int coverClass = this.resolveEffectiveCoverClassForTerrain(sampledCoverClass);
            EarthChunkGenerator.ColumnHeights column = this.resolveColumnHeights(
               worldX, worldZ, localX, localZ, chunkMinY, chunkMaxY, coverClass, waterData, heightGrid[gridRowIndex + localX]
            );
            coverClasses[index] = coverClass;
            long surfaceCoverStartNs = beginFullChunkProfiling();
            int visualCoverClass = this.sampleVisualCoverClassForTerrainShell(worldX, worldZ, coverClass);
            if (visualCoverClass == Integer.MIN_VALUE) {
               shellVisualCoverMisses++;
               visualCoverClass = coverClass;
            }

            visualCoverClasses[index] = visualCoverClass;
            surfaceCoverClasses[index] = this.resolveSurfaceCoverClassForTerrain(coverClass, visualCoverClass);
            surfaceCoverResolveNs += elapsedFullChunkProfilingSince(surfaceCoverStartNs);
            terrainSurfaces[index] = column.terrainSurface();
            waterSurfaces[index] = column.waterSurface();
            waterFlags[index] = column.hasWater();
            oceanFlags[index] = column.hasWater() && waterEnabled && waterData.isOcean(localX, localZ);
         }
      }

      return new EarthChunkGenerator.TerrainShellColumnFillResult(surfaceCoverResolveNs, shellCoverMisses, shellVisualCoverMisses);
   }

   private void fillBaseTerrainRawCoverClasses(ChunkPos pos, int[] rawCoverClasses) {
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();

      for (int localX = 0; localX < CHUNK_SIDE; localX++) {
         int worldX = chunkMinX + localX;

         for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
            int worldZ = chunkMinZ + localZ;
            int index = chunkIndex(localX, localZ);
            rawCoverClasses[index] = this.sampleCoverClassForExactTerrain(worldX, worldZ);
         }
      }
   }

   private long fillExactChunkTerrainColumns(
      ChunkPos pos,
      int chunkMinY,
      int chunkMaxY,
      int step,
      int gridSize,
      int[] heightGrid,
      WaterSurfaceResolver.WaterChunkData waterData,
      int[] rawCoverClasses,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags,
      boolean profileSurfaceCover
   ) {
      long surfaceCoverResolveNs = 0L;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      boolean reuseRawCoverClasses = rawCoverClasses != null;
      boolean waterEnabled = this.settings.enableWater();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int gridRowIndex = (localZ + step) * gridSize + step;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int rawCoverClass = reuseRawCoverClasses ? rawCoverClasses[index] : this.sampleCoverClassForExactTerrain(worldX, worldZ);
            int coverClass = this.resolveEffectiveCoverClassForTerrain(rawCoverClass);
            EarthChunkGenerator.ColumnHeights column = this.resolveColumnHeights(
               worldX, worldZ, localX, localZ, chunkMinY, chunkMaxY, coverClass, waterData, heightGrid[gridRowIndex + localX]
            );
            coverClasses[index] = coverClass;
            long surfaceCoverStartNs = profileSurfaceCover ? beginFullChunkProfiling() : 0L;
            int visualCoverClass = this.sampleVisualCoverClassForExactTerrain(worldX, worldZ, coverClass);
            visualCoverClasses[index] = visualCoverClass;
            surfaceCoverClasses[index] = this.resolveSurfaceCoverClassForTerrain(coverClass, visualCoverClass);
            if (profileSurfaceCover) {
               surfaceCoverResolveNs += elapsedFullChunkProfilingSince(surfaceCoverStartNs);
            }

            terrainSurfaces[index] = column.terrainSurface();
            waterSurfaces[index] = column.waterSurface();
            waterFlags[index] = column.hasWater();
            oceanFlags[index] = column.hasWater() && waterEnabled && waterData.isOcean(localX, localZ);
         }
      }

      return surfaceCoverResolveNs;
   }

   private void fillChunkTerrainMetricsAndBiomes(
      ChunkPos pos,
      int step,
      int gridSize,
      int[] heightGrid,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      boolean[] oceanFlags,
      int[] slopeDiffs,
      int[] convexities,
      Holder<Biome>[] biomeCache,
      EarthBiomeSource earthBiomeSource,
      EarthChunkGenerator.ChunkBiomeClimateCache climateCache,
      RandomState random,
      Holder<Biome>[] fallbackBiomes
   ) {
      RandomState noiseBiomeRandom = earthBiomeSource == null && fallbackBiomes == null ? Objects.requireNonNull(random, "random") : null;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int gridRowIndex = (localZ + step) * gridSize + step;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int surface = terrainSurfaces[index];
            int gridIndex = gridRowIndex + localX;
            // 2026-09-29: slope and convexity use **the same set of 4 neighbours**
            // The two methods used to read them separately => 8 array reads + 8 subtractions per column (256 columns per chunk => 2048 redundant reads)
            // Now they are read once and computed twice; the operation order is copied from the original method, so the result is **bit-for-bit identical**
            int east = heightGrid[gridIndex + step];
            int west = heightGrid[gridIndex - step];
            int north = heightGrid[gridIndex - step * gridSize];
            int south = heightGrid[gridIndex + step * gridSize];
            // 2026-09-29: sentinel guards
            // `heightGrid` uses `Integer.MIN_VALUE` throughout to mean "this column was not sampled" (3046/3072/3119/3212/3324/3345),
            // while `fillTerrainShellColumns:5906` writes only the **clamped** heights back into `terrainSurfaces[]`
            // => **`heightGrid` is never cleaned**, so outer neighbours can still carry the sentinel
            // and `Math.abs(MIN_VALUE - surface)` **overflows int** (for 64 it yields 2147483584)
            // => `slopeDiffs` / `convexities` become astronomical numbers => they go **straight into biome selection** (6033)
            // -- the symptom is "silently picking the wrong biome" rather than the -64 hole, which makes it much harder to notice.
            // Approach: a sentinel neighbour is **replaced with the centre height** (difference 0, which does not affect slope extremes)
            // with real data this condition is never true, so the result is **bit-for-bit unchanged**
            // Note: the replaced `sampleSlopeDiffCached` / `sampleConvexityCached` **never had guards either**,
            // so this fixes a pre-existing defect rather than being a regression; and since the 4 values are now read once, **adding the guards costs nothing**
            if (east == Integer.MIN_VALUE) {
               east = surface;
            }
            if (west == Integer.MIN_VALUE) {
               west = surface;
            }
            if (north == Integer.MIN_VALUE) {
               north = surface;
            }
            if (south == Integer.MIN_VALUE) {
               south = surface;
            }
            slopeDiffs[index] = Math.max(
               Math.max(Math.abs(east - surface), Math.abs(west - surface)),
               Math.max(Math.abs(north - surface), Math.abs(south - surface))
            );
            convexities[index] = (east + west + north + south) / 4 - surface;
            if (earthBiomeSource != null) {
               biomeCache[index] = earthBiomeSource.getBiomeAtBlock(
                  worldX,
                  worldZ,
                  coverClasses[index],
                  visualCoverClasses[index],
                  new WaterSurfaceResolver.WaterColumnData(waterFlags[index], oceanFlags[index], surface, waterSurfaces[index]),
                  climateCache != null ? climateCache.resolve(worldX, worldZ, EarthChunkGenerator::sampleKoppenCode) : null
               );
            } else if (fallbackBiomes != null) {
               biomeCache[index] = fallbackBiomes[index];
            } else {
               biomeCache[index] = this.biomeSource.getNoiseBiome(
                  QuartPos.fromBlock(worldX), QuartPos.fromBlock(surface), QuartPos.fromBlock(worldZ), noiseBiomeRandom.sampler()
               );
            }
         }
      }
   }

   private void applyPreparedTerrainRefinement(ServerLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedTerrainRefinement refinement) {
      long chunkKey = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
      if (!Objects.equals(this.terrainGenerationStamps.get(chunkKey), refinement.generationStamp())) {
         EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
         return;
      }

      // 2026-09-26: the registration is cancelled **unconditionally**
      // The `remove` used to sit on the last line -- a throw from patch / structure clear / deferred detail / realtime snow
      // in **any one of those steps** left that registration in the map forever
      // (`terrainGenerationStamps` is an unbounded ConcurrentHashMap, so memory leaks slowly).
      // `remove(key, value)` only deletes the entry **whose value also matches**, so it cannot delete the new registration created by a regeneration
      try {
         this.applyTerrainRefinementPatch(chunk, refinement);
         this.carveStructureClearanceVolumes(level.structureManager(), chunk);
         this.applyPreparedChunkDetail(level, chunk, refinement.delayedDetail());
         this.applyRealtimeSnowCover(level, chunk);
      } finally {
         this.terrainGenerationStamps.remove(chunkKey, refinement.generationStamp());
      }
   }

   private void applyTerrainRefinementPatch(ChunkAccess chunk, EarthChunkGenerator.PreparedTerrainRefinement refinement) {
      ChunkPos pos = chunk.getPos();
      int chunkMinY = chunk.getMinY();
      int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      MutableBlockPos cursor = new MutableBlockPos();
      EarthChunkGenerator.TerrainShellBuildResult shell = refinement.shell();
      EarthChunkGenerator.FullChunkOceanBeachCache oceanBeachCache = this.buildFullChunkOceanBeachCache(
         chunkMinX, chunkMinZ, refinement.waterFlags(), refinement.oceanFlags()
      );
      boolean useFastSurfacePalette = FAST_FULL_CHUNK && FULL_CHUNK_SURFACE_MODE == EarthChunkGenerator.SurfaceMode.TWO_TIER;
      EarthChunkGenerator.SurfaceApplyProfiler profiler = new EarthChunkGenerator.SurfaceApplyProfiler();
      // 2026-10-12: drop the probe memo from the previous chunk. This is where the surface pass starts,
      // so a stale entry can never survive into the next chunk.
      EarthChunkGenerator.MountainProbeCache probeCache = this.mountainProbeCache.get();
      if (probeCache != null) {
         probeCache.clear();
      }
      int[] shellTerrainSurfaces = shell.terrainSurfaces();
      int[] shellWaterSurfaces = shell.waterSurfaces();
      boolean[] shellWaterFlags = shell.waterFlags();
      int[] refinedTerrainSurfaces = refinement.terrainSurfaces();
      int[] refinedWaterSurfaces = refinement.waterSurfaces();
      boolean[] refinedWaterFlags = refinement.waterFlags();
      int[] refinedSlopeDiffs = refinement.slopeDiffs();
      int[] refinedConvexities = refinement.convexities();
      int[] refinedSurfaceCoverClasses = refinement.surfaceCoverClasses();
      Holder<Biome>[] refinedBiomes = refinement.context().biomeCache();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;
         int rowIndex = localZ * CHUNK_SIDE;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkMinX + localX;
            int index = rowIndex + localX;
            int oldSurface = shellTerrainSurfaces[index];
            int newSurface = refinedTerrainSurfaces[index];
            int oldTop = shellWaterFlags[index] ? Math.max(oldSurface, shellWaterSurfaces[index]) : oldSurface;
            int newTop = refinedWaterFlags[index] ? Math.max(newSurface, refinedWaterSurfaces[index]) : newSurface;
            int rewriteBottom = Mth.clamp(Math.min(oldSurface, newSurface) - 4, chunkMinY, chunkMaxY);
            int rewriteTop = Mth.clamp(Math.max(oldTop, newTop) + 2, chunkMinY, chunkMaxY);

            for (int y = rewriteBottom; y <= rewriteTop; y++) {
               cursor.set(worldX, y, worldZ);
               chunk.setBlockState(cursor, AIR_STATE);
            }

            for (int y = rewriteBottom; y <= newSurface; y++) {
               cursor.set(worldX, y, worldZ);
               chunk.setBlockState(cursor, y < this.minY + 64 ? DEEPSLATE_STATE : STONE_STATE);
            }

            if (refinedWaterFlags[index] && newSurface < refinedWaterSurfaces[index]) {
               for (int y = newSurface + 1; y <= refinedWaterSurfaces[index]; y++) {
                  cursor.set(worldX, y, worldZ);
                  chunk.setBlockState(cursor, WATER_STATE);
               }
            }

            this.applySurface(
               chunk,
               cursor,
               worldX,
               worldZ,
               newSurface,
               chunkMinY,
               refinedWaterFlags[index] && refinedWaterSurfaces[index] > newSurface,
               refinedBiomes[index],
               refinedSlopeDiffs[index],
               refinedConvexities[index],
               refinedSurfaceCoverClasses[index],
               oceanBeachCache,
               profiler,
               useFastSurfacePalette
            );
            if (newSurface >= this.seaLevel
               && this.shouldRetainSurfaceSnow(
                  useFastSurfacePalette,
                  refinedSurfaceCoverClasses[index],
                  newSurface,
                  refinedSlopeDiffs[index],
                  refinedConvexities[index],
                  worldX,
                  worldZ
               )) {
               applySnowCover(chunk, cursor, worldX, worldZ, newSurface, chunkMinY);
            }
         }
      }
   }

   private void applyPreparedChunkDetail(WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkDetail detail) {
      long applyStartNs = EarthChunkGenerator.ChunkDetailPerf.now();
      EarthChunkGenerator.ChunkGenerationContext context = detail.context();
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings = detail.preparedBuildings();
      if (detail.placeBuildings() && preparedBuildings != null && !preparedBuildings.isEmpty()) {
         // 2026-09-27: the three stages are timed separately (measured: one apply took 13.6 s, and before this you could not tell which one)
         long buildingsStartNs = EarthChunkGenerator.ChunkDetailPerf.now();
         this.applyPreparedBuildingTerrainToChunk(level, chunk, preparedBuildings);
         this.placePreparedBuildings(level, chunk, preparedBuildings);
         EarthChunkGenerator.ChunkDetailPerf.recordDetailApplyBuildings(
            EarthChunkGenerator.ChunkDetailPerf.elapsedSince(buildingsStartNs)
         );
      }

      EarthChunkGenerator.OsmRoadQueryResult roadQuery = detail.roadQuery();
      if (roadQuery != null && !roadQuery.features().isEmpty()) {
         long roadsStartNs = EarthChunkGenerator.ChunkDetailPerf.now();
         int[] terrainSurfaces = context.copyTerrainSurfaces();
         int[] waterSurfaces = context.copyWaterSurfaces();
         boolean[] waterFlags = context.copyWaterFlags();
         if (preparedBuildings != null && !preparedBuildings.isEmpty()) {
            this.applyPreparedBuildingsToTerrain(preparedBuildings, terrainSurfaces, waterSurfaces, waterFlags, context.minY(), context.maxY());
         }

         this.applyOsmRoadOverlay(
            level,
            chunk,
            context.pos(),
            terrainSurfaces,
            waterSurfaces,
            waterFlags,
            context.minY(),
            context.maxY(),
            preparedBuildings,
            roadQuery
         );
         this.placePreparedRoadLights(level, chunk);
         EarthChunkGenerator.ChunkDetailPerf.recordDetailApplyRoads(
            EarthChunkGenerator.ChunkDetailPerf.elapsedSince(roadsStartNs)
         );
      }

      List<EarthChunkGenerator.PreparedTreePlacement> treePlacements = detail.treePlacements();
      if (!treePlacements.isEmpty()) {
         long treeApplyStartNs = beginFullChunkProfiling();
         long treesPerfStartNs = EarthChunkGenerator.ChunkDetailPerf.now();   // records the **start** time
         this.applyPreparedTreePlacements(level, chunk, treePlacements);
         endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase.DECORATION_TREES_DEFER_APPLY, treeApplyStartNs);
         EarthChunkGenerator.ChunkDetailPerf.recordDetailApplyTrees(
            EarthChunkGenerator.ChunkDetailPerf.elapsedSince(treesPerfStartNs)
         );
      }

      EarthChunkGenerator.ChunkDetailPerf.recordDetailApply(EarthChunkGenerator.ChunkDetailPerf.elapsedSince(applyStartNs));
   }

   public void prefetchForChunk(int chunkX, int chunkZ) {
      this.prefetchForChunk(chunkX, chunkZ, true);
   }

   public void prefetchForChunk(int chunkX, int chunkZ, boolean includeRoadsPrefetch) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, true);
   }

   public void prefetchForChunk(int chunkX, int chunkZ, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, includeDetailedWaterPrefetch, true);
   }

   public void prefetchForChunk(
      int chunkX, int chunkZ, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch, boolean includeBuildingsPrefetch
   ) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, this.settings.worldScale());
   }

   public void prefetchForChunk(
      int chunkX,
      int chunkZ,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters
   ) {
      this.prefetchForChunk(chunkX, chunkZ, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, previewResolutionMeters, true);
   }

   public void prefetchForChunk(
      int chunkX,
      int chunkZ,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      TellusWorldgenSources.prefetchForChunk(
         new ChunkPos(chunkX, chunkZ),
         this.settings,
         includeRoadsPrefetch,
         includeDetailedWaterPrefetch,
         includeBuildingsPrefetch,
         previewResolutionMeters,
         allowInlineExecution
      );
   }

   public List<RoadFeature> fetchOsmRoadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks) {
      return this.fetchOsmRoadsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, OsmQueryMode.BLOCKING);
   }

   public List<RoadFeature> fetchOsmRoadsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode) {
      return this.fetchOsmRoadsForAreaDetailed(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, mode).features();
   }

   public EarthChunkGenerator.OsmRoadQueryResult fetchOsmRoadsForAreaDetailed(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      if (!this.settings.enableRoads()) {
         return new EarthChunkGenerator.OsmRoadQueryResult(List.of(), false);
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_ROAD_MAX_SCALE)) {
            OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
            TellusOsmRoadSource.RoadQueryResult result = OSM_ROAD_SOURCE.roadsForAreaWithStatus(
               minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, Math.max(0, marginBlocks), queryMode
            );
            return new EarthChunkGenerator.OsmRoadQueryResult(result.features(), result.hadCacheMiss());
         } else {
            return new EarthChunkGenerator.OsmRoadQueryResult(List.of(), false);
         }
      }
   }

   public List<OsmBuildingFeature> fetchOsmBuildingsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks) {
      return this.fetchOsmBuildingsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, OsmQueryMode.BLOCKING);
   }

   public List<OsmBuildingFeature> fetchOsmBuildingsForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      return this.fetchOsmBuildingsForAreaDetailed(minBlockX, minBlockZ, maxBlockX, maxBlockZ, marginBlocks, mode).features();
   }

   public EarthChunkGenerator.OsmBuildingQueryResult fetchOsmBuildingsForAreaDetailed(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, int marginBlocks, OsmQueryMode mode
   ) {
      if (!this.settings.enableBuildings()) {
         return new EarthChunkGenerator.OsmBuildingQueryResult(List.of(), false);
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_BUILDING_MAX_SCALE) && OSM_BUILDING_SOURCE.available()) {
            OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
            TellusOsmBuildingSource.BuildingQueryResult result = OSM_BUILDING_SOURCE.buildingsForAreaWithStatus(
               minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, Math.max(0, marginBlocks), queryMode
            );
            return new EarthChunkGenerator.OsmBuildingQueryResult(result.features(), result.hadCacheMiss());
         } else {
            return new EarthChunkGenerator.OsmBuildingQueryResult(List.of(), false);
         }
      }
   }

   private EarthChunkGenerator.PreparedChunkBuildings prepareChunkBuildings(ChunkPos pos, int[] terrainSurfaces, int chunkMinY, int chunkMaxY, RandomState random) {
      long chunkKey = ChunkPos.pack(pos.x(), pos.z());
      this.preparedChunkBuildings.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      EarthChunkGenerator.PreparedChunkBuildings prepared = this.buildChunkBuildings(
         pos, terrainSurfaces, chunkMinY, chunkMaxY, (feature, baseY) -> this.sampleBuildingBiome(feature, baseY, random), this.resolveFullChunkOsmQueryMode()
      );
      if (prepared != null && !prepared.isEmpty()) {
         this.preparedChunkBuildings.put(chunkKey, prepared);
         this.markPreparedChunkState(chunkKey);
      }

      return prepared;
   }

   private EarthChunkGenerator.PreparedChunkBuildings buildChunkBuildings(
      ChunkPos pos,
      int[] terrainSurfaces,
      int chunkMinY,
      int chunkMaxY,
      EarthChunkGenerator.BuildingBiomeResolver biomeResolver,
      OsmQueryMode queryMode
   ) {
      if (!this.settings.enableBuildings()) {
         return null;
      } else {
         double worldScale = this.settings.worldScale();
         if (!(worldScale <= 0.0) && !(worldScale > OSM_BUILDING_MAX_SCALE) && OSM_BUILDING_SOURCE.available()) {
            int chunkMinX = pos.getMinBlockX();
            int chunkMinZ = pos.getMinBlockZ();
            int chunkMaxX = chunkMinX + CHUNK_MASK;
            int chunkMaxZ = chunkMinZ + CHUNK_MASK;
            long fetchStartNs = OsmPerf.now();
            EarthChunkGenerator.OsmBuildingQueryResult query = this.fetchOsmBuildingsForAreaDetailed(
               chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, OSM_BUILDING_QUERY_MARGIN, queryMode
            );
            long fetchNs = OsmPerf.elapsedSince(fetchStartNs);
            if (queryMode == OsmQueryMode.NON_BLOCKING && query.hadCacheMisses() && query.features().isEmpty()) {
               EarthChunkGenerator.ChunkDetailPerf.recordSkippedBlockingFallback();
               OsmPerf.recordFullChunkBuilding(fetchNs, 0L, 0);
               return null;
            }

            List<OsmBuildingFeature> features = query.features();
            if (features.isEmpty()) {
               OsmPerf.recordFullChunkBuilding(fetchNs, 0L, 0);
               return null;
            } else {
               long prepStartNs = OsmPerf.now();
               double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
               int sampleMinX = chunkMinX - OSM_BUILDING_QUERY_MARGIN;
               int sampleMaxX = chunkMaxX + OSM_BUILDING_QUERY_MARGIN;
               int sampleMinZ = chunkMinZ - OSM_BUILDING_QUERY_MARGIN;
               int sampleMaxZ = chunkMaxZ + OSM_BUILDING_QUERY_MARGIN;
               List<RoadFeature> entranceRoads = this.fetchEntranceRoads(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, worldScale, queryMode);
               Map<String, EarthChunkGenerator.BuildingGroupScratch> groups = new HashMap<>();
               List<EarthChunkGenerator.RasterizedBuildingFeature> partFeatures = new ArrayList<>();
               List<EarthChunkGenerator.RasterizedBuildingFeature> footprintFeatures = new ArrayList<>();

               for (OsmBuildingFeature feature : features) {
                  String groupId = resolveBuildingGroupId(feature);
                  EarthChunkGenerator.BuildingGroupScratch group = groups.computeIfAbsent(groupId, id -> new EarthChunkGenerator.BuildingGroupScratch());
                  boolean groundContact = feature.kind() != com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART || this.buildingMinHeightBlocks(feature.minHeightMeters()) <= 0;
                  EarthChunkGenerator.AnalyzedBuildingFeature analyzed = this.analyzeChunkBuildingFeature(
                     feature,
                     groupId,
                     terrainSurfaces,
                     chunkMinX,
                     chunkMinZ,
                     sampleMinX,
                     sampleMaxX,
                     sampleMinZ,
                     sampleMaxZ,
                     blocksPerDegree,
                     groundContact
                  );
                  if (analyzed == null) {
                     continue;
                  }

                  IntArrayList groundSamples = analyzed.groundSamples();
                  for (int i = 0; i < groundSamples.size(); i++) {
                     group.groundSamples().add(groundSamples.getInt(i));
                  }

                  EarthChunkGenerator.RasterizedBuildingFeature rasterized = analyzed.rasterized();
                  if (rasterized != null) {
                     for (int index : rasterized.occupiedIndices()) {
                        group.fallbackSamples().add(terrainSurfaces[index]);
                     }

                     if (feature.kind() == com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART) {
                        partFeatures.add(rasterized);
                     } else {
                        footprintFeatures.add(rasterized);
                     }
                  }
               }

               if (partFeatures.isEmpty() && footprintFeatures.isEmpty()) {
                  OsmPerf.recordFullChunkBuilding(fetchNs, OsmPerf.elapsedSince(prepStartNs), features.size());
                  return null;
               } else {
                  for (EarthChunkGenerator.BuildingGroupScratch group : groups.values()) {
                     IntArrayList samples = !group.groundSamples().isEmpty() ? group.groundSamples() : group.fallbackSamples();
                     if (!samples.isEmpty()) {
                        group.setBaseY(medianValue(samples));
                     }
                  }

                  EarthChunkGenerator.PreparedChunkBuildings prepared = new EarthChunkGenerator.PreparedChunkBuildings();
                  int[] overlyingPartFloorY = new int[CHUNK_AREA];
                  Arrays.fill(overlyingPartFloorY, Integer.MAX_VALUE);

                  // 2026-09-22 fix: process building:part in [descending] height order
                  // Why sorting is mandatory (the root cause of the Canton Tower "two broken sections and a notch at the top"):
                  // `overlyingPartFloorY` is "the **lowest** floorY seen so far",
                  // and it is used as the "floor of the part **above** this column" to cap this part.
                  // That only holds when the **higher parts are processed before the lower ones**;
                  // the original implementation kept the raw OSM order (the ArrayList insertion order),
                  // so as soon as a **lower part was processed before a higher one** the upper bound ended up below this part's floorY ->
                  // `capLowerColumnTopY` judged that upper bound meaningless and the higher part was dropped from the column
                  // => a whole section of the tower **vanished** (measured on the Canton Tower).
                  // after sorting: the higher parts land first -> the upper bound is always above the lower part's floorY -> capping works normally again
                  // (the lower part is correctly cut off below the floor of the higher part, so it neither clips through nor breaks)
                  // Together with the "do not cap" fallback in BuildingPlacementSupport this is **double insurance**
                  // (with the right order the normal capping runs; even if the data is broken it only skips capping, it **never drops a column**)
                  // the sort key is minHeightMeters descending: within a group baseY is the same
                  // and floorY = baseY + round(minHeight / scale) + 1, so both orders agree
                  partFeatures.sort((a, b) -> Double.compare(
                     b.feature().minHeightMeters(), a.feature().minHeightMeters()));

                  for (EarthChunkGenerator.RasterizedBuildingFeature rasterized : partFeatures) {
                     int baseY = groups.get(rasterized.groupId()).baseY();
                     BuildingProfile profile = this.resolveBuildingProfile(
                        rasterized.feature(), baseY, biomeResolver.sample(rasterized.feature(), baseY), worldScale
                     );
                     int floorY = this.buildingFloorY(baseY, rasterized.feature());
                     int roofBaseY = this.buildingRoofBaseY(baseY, rasterized.feature(), floorY, profile);
                     int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
                     BuildingBlueprint blueprint = TellusBuildingBlueprints.create(
                        rasterized.groupId(),
                        rasterized.feature(),
                        profile,
                        this.worldSeed,
                        baseY,
                        floorY,
                        roofBaseY,
                        topY,
                        entranceRoads,
                        worldScale
                     );
                     EarthChunkGenerator.PreparedBuildingFeature preparedFeature = this.prepareLocalBuildingFeature(
                        rasterized, overlyingPartFloorY, blueprint, chunkMinX, chunkMinZ
                     );
                     if (preparedFeature != null) {
                        prepared.addFeature(preparedFeature);

                        for (int index : rasterized.occupiedIndices()) {
                           overlyingPartFloorY[index] = Math.min(overlyingPartFloorY[index], preparedFeature.floorY());
                        }
                     }
                  }

                  for (EarthChunkGenerator.RasterizedBuildingFeature rasterized : footprintFeatures) {
                     int baseY = groups.get(rasterized.groupId()).baseY();
                     BuildingProfile profile = this.resolveBuildingProfile(
                        rasterized.feature(), baseY, biomeResolver.sample(rasterized.feature(), baseY), worldScale
                     );
                     int floorY = this.buildingFloorY(baseY, rasterized.feature());
                     int roofBaseY = this.buildingRoofBaseY(baseY, rasterized.feature(), floorY, profile);
                     int topY = roofBaseY + Math.max(profile.parapetHeight(), profile.roofRise());
                     BuildingBlueprint blueprint = TellusBuildingBlueprints.create(
                        rasterized.groupId(),
                        rasterized.feature(),
                        profile,
                        this.worldSeed,
                        baseY,
                        floorY,
                        roofBaseY,
                        topY,
                        entranceRoads,
                        worldScale
                     );
                     EarthChunkGenerator.PreparedBuildingFeature preparedFeature = this.prepareLocalBuildingFeature(
                        rasterized, overlyingPartFloorY, blueprint, chunkMinX, chunkMinZ
                     );
                     if (preparedFeature != null) {
                        prepared.addFeature(preparedFeature);
                     }
                  }

                  OsmPerf.recordFullChunkBuilding(fetchNs, OsmPerf.elapsedSince(prepStartNs), features.size());
                  return prepared.isEmpty() ? null : prepared;
               }
            }
         } else {
            return null;
         }
      }
   }

   private EarthChunkGenerator.AnalyzedBuildingFeature analyzeChunkBuildingFeature(
      OsmBuildingFeature feature,
      String groupId,
      int[] terrainSurfaces,
      int chunkMinX,
      int chunkMinZ,
      int sampleMinX,
      int sampleMaxX,
      int sampleMinZ,
      int sampleMaxZ,
      double blocksPerDegree,
      boolean collectGroundSamples
   ) {
      double worldScale = this.settings.worldScale();
      int minX = Math.max(sampleMinX, Mth.floor(feature.minBlockX(blocksPerDegree) - 1.0));
      int maxX = Math.min(sampleMaxX, Mth.ceil(feature.maxBlockX(blocksPerDegree) + 1.0));
      int minZ = Math.max(sampleMinZ, Mth.floor(feature.minBlockZ(worldScale) - 1.0));
      int maxZ = Math.min(sampleMaxZ, Mth.ceil(feature.maxBlockZ(worldScale) + 1.0));
      if (maxX < minX || maxZ < minZ) {
         return null;
      } else {
         int padding = BUILDING_SLICE_PADDING;
         int extSide = CHUNK_SIDE + padding * 2;
         int extMinX = chunkMinX - padding;
         int extMinZ = chunkMinZ - padding;
         int extMaxX = chunkMinX + CHUNK_MASK + padding;
         int extMaxZ = chunkMinZ + CHUNK_MASK + padding;
         boolean[] occupied = new boolean[CHUNK_AREA];
         IntArrayList occupiedIndices = new IntArrayList();
         IntArrayList groundSamples = new IntArrayList();
         boolean[] extOccupied = new boolean[extSide * extSide];
         int partCount = feature.partCount();
         double[][] partXs = new double[partCount][];
         double[][] partZs = new double[partCount][];
         int rasterMinX = Integer.MAX_VALUE;
         int rasterMaxX = Integer.MIN_VALUE;
         int rasterMinZ = Integer.MAX_VALUE;
         int rasterMaxZ = Integer.MIN_VALUE;

         for (int part = 0; part < partCount; part++) {
            int pointCount = feature.pointCount(part);
            double[] xs = new double[pointCount];
            double[] zs = new double[pointCount];

            for (int point = 0; point < pointCount; point++) {
               double worldX = feature.lonAt(part, point) * blocksPerDegree - 0.5;
               double worldZ = EarthProjection.latToBlockZ(feature.latAt(part, point), worldScale) - 0.5;
               xs[point] = worldX;
               zs[point] = worldZ;
               rasterMinX = Math.min(rasterMinX, Mth.floor(worldX));
               rasterMaxX = Math.max(rasterMaxX, Mth.ceil(worldX));
               rasterMinZ = Math.min(rasterMinZ, Mth.floor(worldZ));
               rasterMaxZ = Math.max(rasterMaxZ, Mth.ceil(worldZ));
            }

            partXs[part] = xs;
            partZs[part] = zs;
         }

         int clampedMinX = Math.max(minX, rasterMinX);
         int clampedMaxX = Math.min(maxX, rasterMaxX);
         int clampedMinZ = Math.max(minZ, rasterMinZ);
         int clampedMaxZ = Math.min(maxZ, rasterMaxZ);
         if (clampedMaxX < clampedMinX || clampedMaxZ < clampedMinZ) {
            return null;
         }

         ScanlinePolygonRasterizer.fill(partXs, partZs, clampedMinX, clampedMinZ, clampedMaxX, clampedMaxZ, (worldX, worldZ) -> {
               boolean inChunk = worldX >= chunkMinX && worldX < chunkMinX + CHUNK_SIDE && worldZ >= chunkMinZ && worldZ < chunkMinZ + CHUNK_SIDE;
               if (inChunk) {
                  int index = chunkIndex(worldX - chunkMinX, worldZ - chunkMinZ);
                  if (!occupied[index]) {
                     occupied[index] = true;
                     occupiedIndices.add(index);
                     if (collectGroundSamples) {
                        groundSamples.add(terrainSurfaces[index]);
                     }
                  }
               } else if (collectGroundSamples) {
                  groundSamples.add(this.sampleSurfaceHeight(worldX, worldZ));
               }

               if (worldX >= extMinX && worldX <= extMaxX && worldZ >= extMinZ && worldZ <= extMaxZ) {
                  extOccupied[extIndex(worldX - extMinX, worldZ - extMinZ, extSide)] = true;
               }
            });

         if (occupiedIndices.isEmpty()) {
            return new EarthChunkGenerator.AnalyzedBuildingFeature(null, groundSamples);
         } else {
            EarthChunkGenerator.BuildingBoundaryInfo boundaryInfo = this.computeBuildingBoundaryInfo(occupied, extOccupied, extSide);
            return new EarthChunkGenerator.AnalyzedBuildingFeature(
               new EarthChunkGenerator.RasterizedBuildingFeature(
                  feature,
                  groupId,
                  occupied,
                  occupiedIndices.toIntArray(),
                  boundaryInfo.boundary(),
                  boundaryInfo.boundaryDistance()
               ),
               groundSamples
            );
         }
      }
   }

   private EarthChunkGenerator.PreparedBuildingFeature prepareLocalBuildingFeature(
      EarthChunkGenerator.RasterizedBuildingFeature rasterized,
      int[] overlyingPartFloorY,
      BuildingBlueprint blueprint,
      int chunkMinX,
      int chunkMinZ
   ) {
      boolean[] occupied = new boolean[CHUNK_AREA];
      IntArrayList occupiedIndices = new IntArrayList(rasterized.occupiedIndices().length);
      int minHeightBlocks = this.buildingMinHeightBlocks(rasterized.feature().minHeightMeters());
      int[] columnTopY = new int[CHUNK_AREA];
      Arrays.fill(columnTopY, Integer.MIN_VALUE);

      for (int index : rasterized.occupiedIndices()) {
         int localX = index % CHUNK_SIDE;
         int localZ = index / CHUNK_SIDE;
         int worldX = chunkMinX + localX;
         int worldZ = chunkMinZ + localZ;
         int topY = blueprint.roofTopY(worldX, worldZ, rasterized.boundaryDistance(index));
         if (this.usesParapet(blueprint.profile().roofProfile()) && rasterized.boundary(index)) {
            topY += blueprint.profile().parapetHeight();
         }

         if (overlyingPartFloorY != null) {
            topY = BuildingPlacementSupport.capLowerColumnTopY(blueprint.floorY(), topY, overlyingPartFloorY[index]);
         }

         if (topY >= blueprint.floorY()) {
            occupied[index] = true;
            occupiedIndices.add(index);
            columnTopY[index] = topY;
         }
      }

      if (occupiedIndices.isEmpty()) {
         return null;
      } else {
         boolean groundContact = rasterized.feature().kind() != com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART || minHeightBlocks <= 0;
         return new EarthChunkGenerator.PreparedBuildingFeature(
            occupied,
            occupiedIndices.toIntArray(),
            rasterized.boundary(),
            rasterized.boundaryDistance(),
            columnTopY,
            blueprint,
            groundContact,
            this.coreLayoutFor(blueprint)
         );
      }
   }

   private void applyPreparedBuildingsToTerrain(
      EarthChunkGenerator.PreparedChunkBuildings prepared, int[] terrainSurfaces, int[] waterSurfaces, boolean[] waterFlags, int minY, int maxY
   ) {
      if (prepared != null && !prepared.isEmpty()) {
         for (int index = 0; index < CHUNK_AREA; index++) {
            int flattenedSurface = prepared.flattenedTerrainSurface(index);
            if (flattenedSurface != Integer.MIN_VALUE) {
               int clampedSurface = Mth.clamp(flattenedSurface, minY, maxY);
               terrainSurfaces[index] = clampedSurface;
               waterSurfaces[index] = clampedSurface;
               waterFlags[index] = false;
            }
         }
      }
   }

   private void placePreparedBuildings(WorldGenLevel level, ChunkAccess chunk) {
      long chunkKey = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
      EarthChunkGenerator.PreparedChunkBuildings prepared = this.preparedChunkBuildings.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      this.placePreparedBuildings(level, chunk, prepared);
   }

   private void placePreparedBuildings(WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkBuildings prepared) {
      if (prepared != null && !prepared.isEmpty()) {
         int minY = chunk.getMinY();
         int maxY = minY + chunk.getHeight() - 1;
         int chunkMinX = chunk.getPos().getMinBlockX();
         int chunkMinZ = chunk.getPos().getMinBlockZ();
         int flags = level instanceof ServerLevel ? 2 : 260;
         MutableBlockPos cursor = new MutableBlockPos();

         for (EarthChunkGenerator.PreparedBuildingFeature feature : prepared.features()) {
            EarthChunkGenerator.BuildingPalette palette = this.paletteForBlueprint(feature.blueprint());

            for (int index : feature.occupiedIndices()) {
               int columnTopY = Mth.clamp(feature.columnTopY(index), minY, maxY);
               if (columnTopY < feature.floorY()) {
                  continue;
               }

               int localX = index % CHUNK_SIDE;
               int localZ = index / CHUNK_SIDE;
               int worldX = chunkMinX + localX;
               int worldZ = chunkMinZ + localZ;
               this.placePreparedBuildingColumn(level, cursor, flags, feature, palette, worldX, worldZ, index, columnTopY, minY, maxY);
            }
         }
      }
   }

   private void applyPreparedBuildingTerrainToChunk(WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkBuildings prepared) {
      if (prepared == null || prepared.isEmpty()) {
         return;
      }

      int minY = chunk.getMinY();
      int maxY = minY + chunk.getHeight() - 1;
      int chunkMinX = chunk.getPos().getMinBlockX();
      int chunkMinZ = chunk.getPos().getMinBlockZ();
      int flags = this.detailApplyFlags(level);
      MutableBlockPos cursor = new MutableBlockPos();

      for (int index = 0; index < CHUNK_AREA; index++) {
         int flattenedSurface = prepared.flattenedTerrainSurface(index);
         if (flattenedSurface == Integer.MIN_VALUE) {
            continue;
         }

         int targetY = Mth.clamp(flattenedSurface, minY, maxY);
         int localX = index % CHUNK_SIDE;
         int localZ = index / CHUNK_SIDE;
         int worldX = chunkMinX + localX;
         int worldZ = chunkMinZ + localZ;
         int currentY = Mth.clamp(level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1, minY - 1, maxY);
         if (currentY < targetY) {
            for (int y = currentY + 1; y <= targetY; y++) {
               cursor.set(worldX, y, worldZ);
               level.setBlock(cursor, y < this.minY + 64 ? DEEPSLATE_STATE : STONE_STATE, flags);
            }
         } else if (currentY > targetY) {
            for (int y = targetY + 1; y <= currentY; y++) {
               cursor.set(worldX, y, worldZ);
               level.setBlock(cursor, AIR_STATE, flags);
            }
         }
      }
   }

   private List<RoadFeature> fetchEntranceRoads(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, double worldScale, OsmQueryMode queryMode
   ) {
      if (!(worldScale > 0.0) || worldScale > OSM_ROAD_MAX_SCALE || !OSM_ROAD_SOURCE.available()) {
         return List.of();
      }

      return this.fetchOsmRoadsForAreaDetailed(minBlockX, minBlockZ, maxBlockX, maxBlockZ, 24, queryMode).features();
   }

   private BuildingProfile resolveBuildingProfile(OsmBuildingFeature feature, int baseY, Holder<Biome> biome, double worldScale) {
      boolean interiorsEnabled = worldScale == 1.0;
      return TellusBuildingProfiles.resolveProfile(feature, worldScale, biome, interiorsEnabled);
   }

   private Holder<Biome> sampleBuildingBiome(OsmBuildingFeature feature, int baseY, RandomState random) {
      double[] centroid = feature.centroidWorld(this.settings.worldScale());
      int centerX = Mth.floor(centroid[0]);
      int centerZ = Mth.floor(centroid[1]);
      int sampleY = Mth.clamp(baseY, this.minY, this.minY + this.height - 1);
      return this.biomeSource.getNoiseBiome(QuartPos.fromBlock(centerX), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(centerZ), random.sampler());
   }

   private Holder<Biome> sampleBuildingBiome(OsmBuildingFeature feature, int baseY, EarthChunkGenerator.ChunkGenerationContext context) {
      double[] centroid = feature.centroidWorld(this.settings.worldScale());
      int centerX = Mth.floor(centroid[0]);
      int centerZ = Mth.floor(centroid[1]);
      int sampleY = Mth.clamp(baseY, this.minY, this.minY + this.height - 1);
      return context.sampleBiome(centerX, centerZ, sampleY);
   }

   private int buildingFloorY(int baseY, OsmBuildingFeature feature) {
      return baseY + this.buildingMinHeightBlocks(feature.minHeightMeters()) + 1;
   }

   private int buildingRoofBaseY(int baseY, OsmBuildingFeature feature, int floorY, BuildingProfile profile) {
      int dataHeight = Math.max(floorY + 1, baseY + this.buildingHeightBlocks(feature.heightMeters()));

      // 2026-09-22 fix (the second root cause of the Canton Tower "two broken sections and a notch at the top")
      //
      // In OSM the `height` of a `building:part` is the **absolute height above ground**
      // (see TellusOsmBuildingSource.resolvePartHeightMeters),
      // so `baseY + height/scale` is already the **top of that part**.
      //
      // while `floorY` is [the floor of that part itself] = baseY + min_height/scale + 1.
      // The original implementation additionally computed `floorY + floorCount * storeyHeightBlocks` --
      // where floorCount is the **storey count derived from the whole part height** (the kind with 188 storeys),
      // which means "part floor + full part height" and therefore **grows one whole extra part** ->
      // adjacent parts overlap badly -> they are then hard-capped by capLowerColumnTopY -> **the middle breaks off**
      //
      // => for a PART: **trust only the absolute OSM height** (dataHeight),
      // never add "storey count x storey height" on top again.
      // => for a FOOTPRINT: keep the original behaviour (there floorY is about baseY,
      // so `max(dataHeight, floorY + storeys * storey height)` is the right semantics --
      // it uses the OSM height and still covers buildings that have "levels but no height").
      if (feature.kind() == com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART) {
         return dataHeight;
      }

      int storeyHeight = Math.max(1, profile.floorCount() * profile.storeyHeightBlocks());
      return Math.max(dataHeight, floorY + storeyHeight);
   }

   private EarthChunkGenerator.BuildingBoundaryInfo computeBuildingBoundaryInfo(boolean[] occupied, boolean[] extOccupied, int extSide) {
      int extArea = extOccupied.length;
      int[] extDistance = new int[extArea];
      Arrays.fill(extDistance, Integer.MAX_VALUE);
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      int padding = BUILDING_SLICE_PADDING;

      for (int localZ = 0; localZ < extSide; localZ++) {
         for (int localX = 0; localX < extSide; localX++) {
            int index = extIndex(localX, localZ, extSide);
            if (!extOccupied[index]) {
               continue;
            }

            boolean boundary = localX == 0
               || localX == extSide - 1
               || localZ == 0
               || localZ == extSide - 1
               || !extOccupied[extIndex(localX - 1, localZ, extSide)]
               || !extOccupied[extIndex(localX + 1, localZ, extSide)]
               || !extOccupied[extIndex(localX, localZ - 1, extSide)]
               || !extOccupied[extIndex(localX, localZ + 1, extSide)];
            if (boundary) {
               extDistance[index] = 0;
               queue.add(index);
            }
         }
      }

      while (!queue.isEmpty()) {
         int index = queue.removeFirst();
         int localX = index % extSide;
         int localZ = index / extSide;
         int nextDistance = extDistance[index] + 1;
         if (localX > 0) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX - 1, localZ, extSide), nextDistance);
         }

         if (localX + 1 < extSide) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX + 1, localZ, extSide), nextDistance);
         }

         if (localZ > 0) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX, localZ - 1, extSide), nextDistance);
         }

         if (localZ + 1 < extSide) {
            this.propagateBoundaryDistance(extOccupied, extDistance, queue, extIndex(localX, localZ + 1, extSide), nextDistance);
         }
      }

      boolean[] boundary = new boolean[CHUNK_AREA];
      int[] boundaryDistance = new int[CHUNK_AREA];
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = chunkIndex(localX, localZ);
            if (!occupied[index]) {
               continue;
            }

            int extIndex = extIndex(localX + padding, localZ + padding, extSide);
            int distance = extDistance[extIndex];
            boundary[index] = distance == 0;
            boundaryDistance[index] = distance == Integer.MAX_VALUE ? 0 : distance;
         }
      }

      return new EarthChunkGenerator.BuildingBoundaryInfo(boundary, boundaryDistance);
   }

   private void propagateBoundaryDistance(boolean[] occupied, int[] distance, ArrayDeque<Integer> queue, int index, int nextDistance) {
      if (occupied[index] && nextDistance < distance[index]) {
         distance[index] = nextDistance;
         queue.add(index);
      }
   }

   private boolean usesParapet(BuildingProfile.RoofProfile roofProfile) {
      return roofProfile == BuildingProfile.RoofProfile.FLAT
         || roofProfile == BuildingProfile.RoofProfile.FLAT_CROWN
         || roofProfile == BuildingProfile.RoofProfile.FLAT_SKYLIGHT;
   }

   private EarthChunkGenerator.BuildingPalette paletteForBlueprint(BuildingBlueprint blueprint) {
      TellusBuildingMaterials.BuildingMaterialPalette palette = TellusBuildingMaterials.resolvePalette(blueprint);
      return new EarthChunkGenerator.BuildingPalette(
         palette.wall(),
         palette.trim(),
         palette.roof(),
         palette.window(),
         palette.floor(),
         palette.partition(),
         palette.stair(),
         palette.slab(),
         palette.light(),
         palette.sill(),
         palette.awning()
      );
   }

   private void placePreparedBuildingColumn(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      EarthChunkGenerator.BuildingPalette palette,
      int worldX,
      int worldZ,
      int index,
      int columnTopY,
      int minY,
      int maxY
   ) {
      BuildingBlueprint blueprint = feature.blueprint();
      if (!blueprint.interiorsEnabled()) {
         this.placeExteriorOnlyBuildingColumn(level, cursor, flags, feature, palette, worldX, worldZ, index, columnTopY, minY, maxY);
         return;
      }

      EarthChunkGenerator.BuildingCoreLayout coreLayout = feature.coreLayout();
      int boundaryDistance = feature.boundaryDistance(index);
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      for (int floorIndex = 0; floorIndex <= highestFloor; floorIndex++) {
         if (boundaryDistance < blueprint.setbackForFloor(floorIndex)) {
            continue;
         }

         int floorBottom = Mth.clamp(blueprint.floorBottomY(floorIndex), minY, maxY);
         int floorTop = Mth.clamp(Math.min(columnTopY, blueprint.floorTopY(floorIndex)), minY, maxY);
         if (floorTop < floorBottom) {
            continue;
         }

         this.setBuildingBlock(level, cursor, worldX, floorBottom, worldZ, palette.floor(), flags, minY, maxY);
         boolean facadeCell = blueprint.isFacadeCell(boundaryDistance, floorIndex);
         boolean entrance = floorIndex == 0 && facadeCell && blueprint.isEntranceCell(worldX, worldZ);
         for (int y = floorBottom + 1; y <= floorTop; y++) {
            BlockState stairState = this.stairStateForColumn(coreLayout, palette, worldX, worldZ, floorBottom, y);
            boolean partitionCell = this.isPartitionCell(blueprint, coreLayout, worldX, worldZ, floorIndex);
            BlockState state;
            if (entrance && y <= floorBottom + 2) {
               state = AIR_STATE;
            } else if (stairState != null) {
               state = stairState;
            } else if (facadeCell) {
               boolean window = this.shouldPlaceWindow(blueprint, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop, y);
               // 2026-10-06: a sill under a window and an awning band over the ground-floor
               // storefront, both from the arnis material tables. The detail is checked first so
               // that a sill is not overwritten by the wall it hangs under, and an awning is not
               // overwritten by the window band it caps.
               BlockState detail = this.facadeDetailState(palette, floorIndex, floorTop, y, window);
               state = detail != null ? detail : window ? palette.window() : palette.wall();
            } else if (partitionCell) {
               state = palette.partition();
            } else if (y == floorTop && TellusBuildingLighting.shouldPlaceInteriorLight(blueprint, boundaryDistance, worldX, worldZ, floorIndex)) {
               state = palette.light();
            } else if (y == floorBottom + 1) {
               state = this.furnitureStateFor(blueprint, palette, coreLayout, worldX, worldZ, floorIndex);
            } else {
               state = AIR_STATE;
            }

            this.setBuildingBlock(level, cursor, worldX, y, worldZ, state, flags, minY, maxY);
         }
      }

      int roofBaseY = Mth.clamp(blueprint.roofBaseY(boundaryDistance), minY, maxY);
      boolean roofEdge = blueprint.isFacadeCell(boundaryDistance, highestFloor);
      for (int y = roofBaseY; y <= columnTopY; y++) {
         BlockState state = roofEdge && y == columnTopY ? palette.trim() : palette.roof();
         this.setBuildingBlock(level, cursor, worldX, y, worldZ, state, flags, minY, maxY);
      }

      if (blueprint.isEntranceCell(worldX, worldZ)) {
         this.placeEntranceDoor(level, cursor, flags, blueprint, feature, worldX, worldZ, minY, maxY);
      }
   }

   private void placeExteriorOnlyBuildingColumn(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      EarthChunkGenerator.BuildingPalette palette,
      int worldX,
      int worldZ,
      int index,
      int columnTopY,
      int minY,
      int maxY
   ) {
      BuildingBlueprint blueprint = feature.blueprint();
      int floorY = Mth.clamp(blueprint.floorY(), minY, maxY);
      int boundaryDistance = feature.boundaryDistance(index);
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      boolean roofEdge = blueprint.isFacadeCell(boundaryDistance, highestFloor);
      for (int y = floorY; y <= columnTopY; y++) {
         BlockState state;
         if (y == floorY) {
            state = palette.floor();
         } else if (y >= blueprint.roofBaseY(boundaryDistance)) {
            state = roofEdge && y == columnTopY ? palette.trim() : palette.roof();
         } else {
            int floorIndex = blueprint.floorIndexAtY(y);
            int floorBottom = blueprint.floorBottomY(floorIndex);
            int floorTop = Math.min(columnTopY, blueprint.floorTopY(floorIndex));
            boolean facadeCell = blueprint.isFacadeCell(boundaryDistance, floorIndex);
            if (facadeCell) {
               // 2026-10-06: same sill / awning detail as the interior-enabled path, so a
               // low-poly house gets the ledge under its windows too.
               boolean window = this.shouldPlaceWindow(blueprint, boundaryDistance, worldX, worldZ, floorIndex, floorBottom, floorTop, y);
               BlockState detail = this.facadeDetailState(palette, floorIndex, floorTop, y, window);
               state = detail != null ? detail : window ? palette.window() : palette.wall();
            } else {
               state = palette.wall();
            }
         }

         this.setBuildingBlock(level, cursor, worldX, y, worldZ, state, flags, minY, maxY);
      }

      if (blueprint.isEntranceCell(worldX, worldZ)) {
         this.placeEntranceDoor(level, cursor, flags, blueprint, feature, worldX, worldZ, minY, maxY);
      }
   }

   private void placeEntranceDoor(
      WorldGenLevel level,
      MutableBlockPos cursor,
      int flags,
      BuildingBlueprint blueprint,
      EarthChunkGenerator.PreparedBuildingFeature feature,
      int worldX,
      int worldZ,
      int minY,
      int maxY
   ) {
      if (!this.shouldPlaceBuildingDoors()) {
         return;
      }

      int lowerY = blueprint.floorY() + 1;
      int upperY = lowerY + 1;
      if (lowerY < minY || upperY > maxY) {
         return;
      }

      BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
         .setValue(BlockStateProperties.HORIZONTAL_FACING, blueprint.entranceFacing())
         .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
      BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
      this.setBuildingBlock(level, cursor, worldX, lowerY, worldZ, lower, flags, minY, maxY);
      this.setBuildingBlock(level, cursor, worldX, upperY, worldZ, upper, flags, minY, maxY);
      if (blueprint.interiorsEnabled()) {
         int insideX = worldX - blueprint.entranceFacing().getStepX();
         int insideZ = worldZ - blueprint.entranceFacing().getStepZ();
         this.setBuildingBlock(level, cursor, insideX, lowerY, insideZ, AIR_STATE, flags, minY, maxY);
         this.setBuildingBlock(level, cursor, insideX, upperY, insideZ, AIR_STATE, flags, minY, maxY);
      }
   }

   private boolean shouldPlaceBuildingDoors() {
      return this.settings.worldScale() > 0.0 && this.settings.worldScale() <= 1.0;
   }

   private void setBuildingBlock(
      WorldGenLevel level, MutableBlockPos cursor, int worldX, int worldY, int worldZ, BlockState state, int flags, int minY, int maxY
   ) {
      if (state != null && worldY >= minY && worldY <= maxY) {
         cursor.set(worldX, worldY, worldZ);
         level.setBlock(cursor, state, flags);
      }
   }

   private EarthChunkGenerator.BuildingCoreLayout coreLayoutFor(BuildingBlueprint blueprint) {
      BuildingProfile.Archetype archetype = blueprint.profile().archetype();
      if (archetype == BuildingProfile.Archetype.HOUSE && blueprint.floorCount() <= 1) {
         return null;
      }

      int width = blueprint.width();
      int depth = blueprint.depth();
      int coreWidth = switch (archetype) {
         case TOWER -> 4;
         case HOUSE -> 2;
         case APARTMENT, COMMERCIAL, INDUSTRIAL -> 3;
         case GENERIC -> 0;
      };
      int coreDepth = switch (archetype) {
         case TOWER -> 4;
         case HOUSE -> 4;
         case APARTMENT, COMMERCIAL, INDUSTRIAL -> 3;
         case GENERIC -> 0;
      };
      if (coreWidth < 2 || coreDepth < 2 || width < coreWidth + 2 || depth < coreDepth + 2) {
         return null;
      }

      int minX = blueprint.minWorldX() + Math.max(1, (width - coreWidth) / 2);
      int minZ = blueprint.minWorldZ() + Math.max(1, (depth - coreDepth) / 2);
      Direction stairFacing = width >= depth ? Direction.EAST : Direction.SOUTH;
      return new EarthChunkGenerator.BuildingCoreLayout(minX, minX + coreWidth - 1, minZ, minZ + coreDepth - 1, stairFacing);
   }

   private BlockState stairStateForColumn(
      EarthChunkGenerator.BuildingCoreLayout coreLayout,
      EarthChunkGenerator.BuildingPalette palette,
      int worldX,
      int worldZ,
      int floorBottom,
      int y
   ) {
      if (coreLayout == null || !coreLayout.contains(worldX, worldZ)) {
         return null;
      }

      if (coreLayout.stairFacing() == Direction.EAST || coreLayout.stairFacing() == Direction.WEST) {
         int stairZ = coreLayout.minZ() + (coreLayout.maxZ() - coreLayout.minZ()) / 2;
         int step = worldX - coreLayout.minX();
         if (worldZ == stairZ && step >= 0 && step <= 2 && y == floorBottom + 1 + step) {
            return palette.stair().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
         }
      } else {
         int stairX = coreLayout.minX() + (coreLayout.maxX() - coreLayout.minX()) / 2;
         int step = worldZ - coreLayout.minZ();
         if (worldX == stairX && step >= 0 && step <= 2 && y == floorBottom + 1 + step) {
            return palette.stair().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
         }
      }

      return null;
   }

   private boolean isPartitionCell(BuildingBlueprint blueprint, EarthChunkGenerator.BuildingCoreLayout coreLayout, int worldX, int worldZ, int floorIndex) {
      if (coreLayout != null && coreLayout.boundary(worldX, worldZ)) {
         return true;
      }

      int localX = worldX - blueprint.minWorldX();
      int localZ = worldZ - blueprint.minWorldZ();
      int width = blueprint.width();
      int depth = blueprint.depth();
      int midX = width / 2;
      int midZ = depth / 2;
      return switch (blueprint.profile().archetype()) {
         case HOUSE -> width >= depth ? localZ == midZ && Math.abs(localX - midX) > 1 : localX == midX && Math.abs(localZ - midZ) > 1;
         case APARTMENT -> width >= depth ? localZ == midZ || localZ == midZ - 1 : localX == midX || localX == midX - 1;
         case COMMERCIAL -> coreLayout != null && (
            worldX == coreLayout.maxX() + 1 && worldZ >= coreLayout.minZ() && worldZ <= coreLayout.maxZ()
               || worldZ == coreLayout.maxZ() + 1 && worldX >= coreLayout.minX() && worldX <= coreLayout.maxX()
         );
         case INDUSTRIAL -> localX <= 3 && localZ <= 3 && (localX == 3 || localZ == 3);
         case TOWER -> false;
         case GENERIC -> floorIndex == 0 && (localX == midX || localZ == midZ);
      };
   }

   /**
    * 2026-10-06: the two facade details arnis places and we did not 鈥?an <b>awning</b> band over the
    * ground-floor storefront and a <b>sill</b> string course under the upper floors.
    *
    * <p><b>Why horizontal banding is the single biggest readability win here.</b> Without it a facade
    * is one flat rectangle of one block, and a player standing on the street cannot tell where one
    * building ends and the next begins, or how many storeys a thing has. A continuous band at the
    * top of each storey is what real facades do, and it costs one block per column per floor.
    *
    * <p><b>Both are on the wall itself, never projected outwards.</b> That is a deliberate
    * difference from arnis, which hangs the awning one cell beyond the facade line: a projection
    * lands in the neighbouring column, and at a chunk border that column belongs to a different
    * chunk whose generation this one must not write into. A flush band reads the same from the
    * street and cannot produce a chunk-edge seam.
    *
    * <p><b>The {@code !windowHere} guard is load-bearing.</b> How much free wall a storey has
    * depends on its height: {@code shouldPlaceWindow} puts a house window on rows
    * {@code floorBottom+1..+2} and a commercial window on {@code floorBottom+1..floorTop}, so a
    * 3-block house storey has <b>no</b> free row and a 4-block one has exactly one. Testing
    * {@code y == floorTop && !windowHere} rather than assuming a row exists makes the rule correct
    * for every storey height instead of quietly overwriting a window wherever the arithmetic
    * guessed wrong.
    *
    * <p>The materials come from the arnis tables but are chosen <b>once per building</b>
    * ({@link TellusBuildingMaterialLibrary#pickSill} / {@code pickAwning} take the blueprint seed),
    * so every window of a facade matches, which is arnis's rule too.
    *
    * @return the override block, or {@code null} to leave the caller's decision alone
    */
   private BlockState facadeDetailState(
      EarthChunkGenerator.BuildingPalette palette,
      int floorIndex,
      int floorTop,
      int y,
      boolean windowHere
   ) {
      // Awning / storefront fascia: commercial ground floor only, on the top window row, so the
      // ground floor reads as "glass below, solid band above" 鈥?which is what a shopfront is.
      if (palette.awning() != null && floorIndex == 0 && windowHere) {
         return palette.awning();
      }
      // Sill / string course: the topmost wall row of an upper storey, when that row is not itself
      // a window. Floor 0 is excluded because the entrance needs clear floor there.
      if (palette.sill() != null && floorIndex > 0 && y == floorTop && !windowHere) {
         return palette.sill();
      }
      return null;
   }

   private boolean shouldPlaceWindow(
      BuildingBlueprint blueprint,
      int boundaryDistance,
      int worldX,
      int worldZ,
      int floorIndex,
      int floorBottom,
      int floorTop,
      int y
   ) {
      if (!blueprint.isFacadeCell(boundaryDistance, floorIndex) || blueprint.isEntranceCell(worldX, worldZ) && floorIndex == 0) {
         return false;
      }

      int setback = blueprint.setbackForFloor(floorIndex);
      int minWorldX = blueprint.minWorldX() + setback;
      int maxWorldX = blueprint.maxWorldX() - setback;
      int minWorldZ = blueprint.minWorldZ() + setback;
      int maxWorldZ = blueprint.maxWorldZ() - setback;
      if ((worldX == minWorldX || worldX == maxWorldX) && (worldZ == minWorldZ || worldZ == maxWorldZ)) {
         return false;
      }

      int edgeCoord = worldX == minWorldX || worldX == maxWorldX ? worldZ - minWorldZ : worldX - minWorldX;
      int spacing = blueprint.profile().windowSpacing();
      if (Math.floorMod(edgeCoord, spacing) == 0) {
         return false;
      }

      return switch (blueprint.profile().archetype()) {
         case COMMERCIAL, TOWER -> y >= floorBottom + 1 && y <= floorTop;
         case INDUSTRIAL -> y == floorTop || y == floorTop - 1;
         default -> y == floorBottom + 1 || y == floorBottom + 2;
      };
   }

   private BlockState furnitureStateFor(
      BuildingBlueprint blueprint,
      EarthChunkGenerator.BuildingPalette palette,
      EarthChunkGenerator.BuildingCoreLayout coreLayout,
      int worldX,
      int worldZ,
      int floorIndex
   ) {
      if (coreLayout != null && coreLayout.contains(worldX, worldZ)) {
         return AIR_STATE;
      }

      int localX = worldX - blueprint.minWorldX();
      int localZ = worldZ - blueprint.minWorldZ();
      int width = blueprint.width();
      int depth = blueprint.depth();
      return switch (blueprint.profile().archetype()) {
         case HOUSE -> this.houseFurnitureState(localX, localZ, width, depth, floorIndex, palette);
         case APARTMENT -> this.apartmentFurnitureState(localX, localZ, width, depth, floorIndex, palette);
         case COMMERCIAL -> (localX % 3 == 1 && localZ % 3 == 1) ? palette.slab() : AIR_STATE;
         case INDUSTRIAL -> (localX + localZ + floorIndex) % 4 == 0 ? BUILDING_BARREL_STATE : (localX + localZ + floorIndex) % 7 == 0 ? BUILDING_FURNACE_STATE : AIR_STATE;
         case TOWER -> (localX % 4 == 1 && localZ % 4 == 1) ? palette.slab() : (floorIndex % 5 == 0 && localX % 5 == 2 && localZ % 5 == 2) ? BUILDING_BOOKSHELF_STATE : AIR_STATE;
         case GENERIC -> AIR_STATE;
      };
   }

   private BlockState houseFurnitureState(int localX, int localZ, int width, int depth, int floorIndex, EarthChunkGenerator.BuildingPalette palette) {
      int frontBand = Math.max(2, depth / 3);
      if (floorIndex == 0 && localZ >= depth - 2 && localX % 3 == 1) {
         return switch (Math.floorMod(localX, 6)) {
            case 1 -> BUILDING_BARREL_STATE;
            case 3 -> BUILDING_CRAFTING_STATE;
            default -> BUILDING_FURNACE_STATE;
         };
      }
      if (floorIndex > 0 && localZ <= frontBand && localX % 4 == 1) {
         return BUILDING_WHITE_WOOL_STATE;
      }
      if (localX <= 1 && localZ <= 1) {
         return BUILDING_CAULDRON_STATE;
      }
      if (floorIndex == 0 && localX == width / 2 && localZ == frontBand) {
         return palette.slab();
      }
      return AIR_STATE;
   }

   private BlockState apartmentFurnitureState(int localX, int localZ, int width, int depth, int floorIndex, EarthChunkGenerator.BuildingPalette palette) {
      if (width >= depth) {
         int corridorMin = depth / 2 - 1;
         int corridorMax = corridorMin + 1;
         if (localZ < corridorMin && localX % 4 == 1) {
            return floorIndex == 0 ? BUILDING_BARREL_STATE : BUILDING_WHITE_WOOL_STATE;
         }
         if (localZ > corridorMax && localX % 4 == 2) {
            return localX % 8 == 2 ? BUILDING_BOOKSHELF_STATE : palette.slab();
         }
      } else {
         int corridorMin = width / 2 - 1;
         int corridorMax = corridorMin + 1;
         if (localX < corridorMin && localZ % 4 == 1) {
            return floorIndex == 0 ? BUILDING_BARREL_STATE : BUILDING_WHITE_WOOL_STATE;
         }
         if (localX > corridorMax && localZ % 4 == 2) {
            return localZ % 8 == 2 ? BUILDING_BOOKSHELF_STATE : palette.slab();
         }
      }
      return AIR_STATE;
   }

   private EarthChunkGenerator.PreparedChunkRoadLights prepareRoadLightsForChunk(
      ChunkPos pos,
      List<RoadFeature> roads,
      EarthChunkGenerator.RoadWidths widths,
      byte[] chunkRoadClass,
      byte[] chunkRoadMode,
      int[] chunkRoadDeckY,
      boolean[] bridgeSupportShaftPresent,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      boolean[] bridgeSupportCapPresent,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings
   ) {
      double worldScale = this.settings.worldScale();
      int spacingBlocks = roadLightSpacingBlocks(worldScale);
      int minLampSpacingBlocks = roadLightMinimumSpacingBlocks(spacingBlocks);
      int fenceCount = roadLightFenceCount(worldScale);
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      double blocksPerDegree = blocksPerDegree(worldScale);
      boolean[] occupiedAnchors = new boolean[CHUNK_AREA];
      EarthChunkGenerator.PreparedChunkRoadLights prepared = new EarthChunkGenerator.PreparedChunkRoadLights();

      for (RoadFeature road : roads) {
         if (road.mode() == RoadMode.TUNNEL) {
            continue;
         }

         int roadWidth = roadWidthForClass(road.roadClass(), widths);
         if (roadWidth <= 0 || road.pointCount() < 2) {
            continue;
         }

         int segmentCount = road.pointCount() - 1;
         double[] worldXs = new double[road.pointCount()];
         double[] worldZs = new double[road.pointCount()];
         double[] segmentStarts = new double[segmentCount];
         double[] segmentLengths = new double[segmentCount];

         for (int i = 0; i < road.pointCount(); i++) {
            worldXs[i] = road.lonAt(i) * blocksPerDegree;
            worldZs[i] = EarthProjection.latToBlockZ(road.latAt(i), worldScale);
         }

         double totalLength = 0.0;
         for (int i = 0; i < segmentCount; i++) {
            double dx = worldXs[i + 1] - worldXs[i];
            double dz = worldZs[i + 1] - worldZs[i];
            segmentStarts[i] = totalLength;
            segmentLengths[i] = Math.sqrt(dx * dx + dz * dz);
            totalLength += segmentLengths[i];
         }

         double endpointInset = Math.max(Math.max(4.0, roadWidth), spacingBlocks * 0.75);
         if (!(totalLength > endpointInset * 2.0)) {
            continue;
         }

         boolean placeLeft = true;
         for (double station = endpointInset; station <= totalLength - endpointInset + 1.0E-6; station += spacingBlocks) {
            EarthChunkGenerator.SampledRoadStation sampled = sampleRoadStation(worldXs, worldZs, segmentStarts, segmentLengths, station);
            if (sampled == null) {
               placeLeft = !placeLeft;
               continue;
            }

            EarthChunkGenerator.PreparedRoadLight light = this.prepareRoadLightAtStation(
               sampled,
               placeLeft,
               road,
               roadWidth,
               fenceCount,
               chunkMinX,
               chunkMinZ,
               chunkRoadClass,
               chunkRoadMode,
               chunkRoadDeckY,
               bridgeSupportShaftPresent,
               bridgeSupportShaftBottomY,
               bridgeSupportShaftTopY,
               bridgeSupportCapPresent,
               bridgeSupportCapBottomY,
               bridgeSupportCapTopY,
               preparedBuildings,
               prepared,
               occupiedAnchors,
               minLampSpacingBlocks
            );
            if (light != null) {
               prepared.add(light);
            }

            placeLeft = !placeLeft;
         }
      }

      return prepared.isEmpty() ? null : prepared;
   }

   private EarthChunkGenerator.PreparedRoadLight prepareRoadLightAtStation(
      EarthChunkGenerator.SampledRoadStation sampled,
      boolean placeLeft,
      RoadFeature road,
      int roadWidth,
      int fenceCount,
      int chunkMinX,
      int chunkMinZ,
      byte[] chunkRoadClass,
      byte[] chunkRoadMode,
      int[] chunkRoadDeckY,
      boolean[] bridgeSupportShaftPresent,
      int[] bridgeSupportShaftBottomY,
      int[] bridgeSupportShaftTopY,
      boolean[] bridgeSupportCapPresent,
      int[] bridgeSupportCapBottomY,
      int[] bridgeSupportCapTopY,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      EarthChunkGenerator.PreparedChunkRoadLights preparedRoadLights,
      boolean[] occupiedAnchors,
      int minLampSpacingBlocks
   ) {
      int roadClassId = roadClassId(road.roadClass());
      int roadModeId = road.mode().ordinal() + 1;
      EarthChunkGenerator.RoadLightAnchor anchor = findRoadLightAnchor(
         sampled, placeLeft, roadWidth, roadClassId, roadModeId, chunkMinX, chunkMinZ, chunkRoadClass, chunkRoadMode, chunkRoadDeckY
      );
      if (anchor == null || occupiedAnchors[anchor.index()]) {
         return null;
      }

      if (hasNearbyPreparedRoadLight(anchor.localX(), anchor.localZ(), minLampSpacingBlocks, preparedRoadLights)) {
         return null;
      }

      int wallBaseY = anchor.baseY() + 1;
      int topY = anchor.baseY() + fenceCount + 3;
      if (preparedBuildings != null && preparedBuildings.intersectsSpan(anchor.localX(), anchor.localZ(), wallBaseY, topY)) {
         return null;
      }

      if (intersectsRoadLightBridgeSupport(
         anchor.localX(),
         anchor.localZ(),
         wallBaseY,
         topY,
         bridgeSupportShaftPresent,
         bridgeSupportShaftBottomY,
         bridgeSupportShaftTopY,
         bridgeSupportCapPresent,
         bridgeSupportCapBottomY,
         bridgeSupportCapTopY
      )) {
         return null;
      }

      occupiedAnchors[anchor.index()] = true;
      return new EarthChunkGenerator.PreparedRoadLight(
         anchor.localX(),
         anchor.localZ(),
         anchor.baseY(),
         fenceCount,
         dominantHorizontalDirection(sampled.tangentX(), sampled.tangentZ())
      );
   }

   private void placePreparedRoadLights(WorldGenLevel level, ChunkAccess chunk) {
      long chunkKey = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
      EarthChunkGenerator.PreparedChunkRoadLights prepared = this.preparedChunkRoadLights.remove(chunkKey);
      this.clearPreparedChunkStateTracking(chunkKey);
      this.placePreparedRoadLights(level, chunk, prepared);
   }

   private void placePreparedRoadLights(WorldGenLevel level, ChunkAccess chunk, EarthChunkGenerator.PreparedChunkRoadLights prepared) {
      if (prepared == null || prepared.isEmpty()) {
         return;
      }

      int minY = chunk.getMinY();
      int maxY = minY + chunk.getHeight() - 1;
      int chunkMinX = chunk.getPos().getMinBlockX();
      int chunkMinZ = chunk.getPos().getMinBlockZ();
      int flags = level instanceof ServerLevel ? 2 : 260;
      MutableBlockPos cursor = new MutableBlockPos();

      for (EarthChunkGenerator.PreparedRoadLight light : prepared.lights()) {
         int baseY = light.baseY();
         int wallY = baseY + 1;
         int glowY = wallY + light.fenceCount() + 1;
         int trapdoorY = glowY + 1;
         if (wallY < minY || trapdoorY > maxY) {
            continue;
         }

         int worldX = chunkMinX + light.localX();
         int worldZ = chunkMinZ + light.localZ();
         if (!this.canPlacePreparedRoadLight(level, worldX, worldZ, baseY, light.fenceCount(), cursor)) {
            continue;
         }

         cursor.set(worldX, wallY, worldZ);
         level.setBlock(cursor, ROAD_LIGHT_BASE_STATE, flags);

         for (int i = 1; i <= light.fenceCount(); i++) {
            cursor.set(worldX, wallY + i, worldZ);
            level.setBlock(cursor, ROAD_LIGHT_FENCE_STATE, flags);
         }

         cursor.set(worldX, glowY, worldZ);
         level.setBlock(cursor, ROAD_LIGHT_GLOW_STATE, flags);
         cursor.set(worldX, trapdoorY, worldZ);
         level.setBlock(cursor, roadLightTrapdoorState(light.trapdoorFacing()), flags);
      }
   }

   private boolean canPlacePreparedRoadLight(WorldGenLevel level, int worldX, int worldZ, int baseY, int fenceCount, MutableBlockPos cursor) {
      cursor.set(worldX, baseY, worldZ);
      if (!isRoadDeckState(level.getBlockState(cursor))) {
         return false;
      }

      int topY = baseY + fenceCount + 3;
      for (int y = baseY + 1; y <= topY; y++) {
         cursor.set(worldX, y, worldZ);
         if (!isRoadLightReplaceable(level.getBlockState(cursor))) {
            return false;
         }
      }

      return true;
   }

   private void setChunkBlock(WorldGenLevel level, ChunkAccess chunk, MutableBlockPos cursor, BlockState state) {
      if (level == null) {
         chunk.setBlockState(cursor, state);
      } else {
         level.setBlock(cursor, state, this.detailApplyFlags(level));
      }
   }

   private int detailApplyFlags(WorldGenLevel level) {
      return level instanceof ServerLevel ? 2 : 260;
   }

   private static int medianValue(IntArrayList values) {
      int[] sorted = values.toIntArray();
      Arrays.sort(sorted);
      return sorted[sorted.length >> 1];
   }

   private int buildingHeightBlocks(double meters) {
      return Math.max(3, (int)Math.round(meters / this.settings.worldScale()));
   }

   private int buildingMinHeightBlocks(double meters) {
      return Math.max(0, (int)Math.round(meters / this.settings.worldScale()));
   }

   private static String resolveBuildingGroupId(OsmBuildingFeature feature) {
      if (feature.kind() == com.yucareux.tellus.world.data.osm.OsmBuildingKind.PART) {
         String buildingId = feature.buildingId();
         return buildingId != null ? "part:" + buildingId : "part:" + feature.featureId();
      } else {
         return "footprint:" + feature.featureId();
      }
   }

   /**
    * 2026-09-26: a block-level "who changed the height" self-audit
    *
    * <p><b>What it has to solve</b>: on a real machine there are always "square areas that are sometimes raised, sometimes dented",
    * and three rounds of guessing from the code only caught part of the reason. So this performs a **falsifiable measurement**:
    * the elevation grid ({@code heightGrid}) is the **cleanest** height source on this chain (it comes straight from the elevation source),
    * and it is compared column by column with the {@code terrainSurfaces} that are **finally written into the blocks**:
    * <ul>
    * <li>if the number of wildly deviating columns exceeds 25% -> this chunk's surface was changed wholesale by some downstream chain -> log a WARN naming
    * {@code waterApproximate}, {@code terrainShellMode} and the two averages, so whoever changed it ends up in the log;</li>
    * <li>otherwise nothing is logged (real terrain has slope, so a few differing columns are expected).</li>
    * </ul>
    *
    * <p>The threshold of {@value #SURFACE_AUDIT_COLUMN_TOLERANCE} blocks means "the surface computed for this column differs from the grid by this much",
    * and the measured slope between neighbouring terrain columns is far below it, while a real blocky bump or dent runs to tens of blocks, so there are no false alarms.
    */
   private void auditChunkSurfaceDeviation(
      ChunkPos pos,
      int[] heightGrid,
      int gridSize,
      int step,
      int[] terrainSurfaces,
      WaterSurfaceResolver.WaterChunkData waterData,
      boolean terrainShellMode
   ) {
      if (!SURFACE_AUDIT_ENABLED || heightGrid == null || terrainSurfaces == null || terrainSurfaces.length != CHUNK_AREA) {
         return;
      }

      int mismatches = 0;
      long surfaceSum = 0L;
      long gridSum = 0L;
      int worst = 0;
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         int worldZ = chunkMinZ + localZ;

         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = chunkIndex(localX, localZ);
            int surface = terrainSurfaces[index];
            // 2026-09-26 correction: the grid index must be `(localZ + step) * gridSize + localX + step`
            // the grid lines sit `step` blocks **outside** the chunk (`gridMinX = chunkMinX - step`)
            // **every** other place in this class uses this formula (`copyChunkTerrainSurfaces` 5612,
            // `fillTerrainShellColumns` 5641, `repairAnomalousChunkTerrain` 3315),
            // it used to be written here as `(localZ + 1) * gridSize + localX + 1`, which shifted every sample point by 3 blocks
            // consequences: false alarms on steep slopes, and the "square bump right above it" that it was supposed to catch was **never caught**
            int gridIndex = (localZ + step) * gridSize + localX + step;
            if (gridIndex < 0 || gridIndex >= heightGrid.length) {
               continue;
            }

            int expected = heightGrid[gridIndex];
            if (expected == Integer.MIN_VALUE) {
               continue;
            }

            surfaceSum += surface;
            gridSum += expected;
            int delta = Math.abs(surface - expected);
            if (delta > worst) {
               worst = delta;
            }
            if (delta > SURFACE_AUDIT_COLUMN_TOLERANCE) {
               mismatches++;
            }
         }
      }

      int columns = CHUNK_AREA;
      if (mismatches * 4 <= columns) {
         return;
      }

      long stamp = SURFACE_AUDIT_SEEN.compute(ChunkPos.pack(pos.x(), pos.z()), (key, previous) -> previous == null ? 1L : previous + 1L);
      if (stamp > 3L) {
         return;   // report at most 3 times per chunk, do not spam the log
      }

      Tellus.LOGGER.warn(
         "[TellusCN] Terrain surface audit: chunk [{}, {}] {} columns deviate > {} blocks from the elevation grid "
            + "(worst {}, surface mean {}, grid mean {}). waterApproximate={} shell={}",
         pos.x(), pos.z(), mismatches, SURFACE_AUDIT_COLUMN_TOLERANCE, worst,
         surfaceSum / columns, gridSum / columns, waterData.approximate(), terrainShellMode
      );
   }

   private WaterSurfaceResolver.WaterChunkData resolveChunkWaterData(ChunkPos pos) {
      return this.resolveChunkWaterData(pos, null);
   }

   private WaterSurfaceResolver.WaterChunkData resolveChunkWaterData(ChunkPos pos, int[] dryTerrainSurfaces) {
      EarthChunkGenerator.WaterChunkCache cache = this.waterChunkCache.get();
      if (cache.matches(pos)) {
         return cache.data();
      } else {
         WaterSurfaceResolver.WaterChunkData data = this.waterResolver.resolveChunkWaterData(pos.x(), pos.z(), dryTerrainSurfaces);
         cache.update(pos, data);
         return data;
      }
   }

   /**
    * Master switch for caves.
    *
    * <p>It takes the world's {@code cave_generation} setting; additionally {@code -Dtellus.caves.enabled=false}
    * forces caves off (the default forces them **on**, because the old implementation carved caves up in the high vacuum where
    * there is no terrain at all, so the feature was effectively broken; now that the conversion algorithm is fixed
    * it should be on by default; add {@code -Dtellus.caves.enabled=false} to go back to the old behaviour).
    */
   private static final boolean CAVES_FORCED = !"false".equalsIgnoreCase(
      System.getProperty("tellus.caves.enabled", "true")
   );

   /** A carving failure is reported only once (avoids per-chunk log spam). */
   private static final java.util.concurrent.atomic.AtomicBoolean TELLUS_CARVE_FAILURE_WARNED =
      new java.util.concurrent.atomic.AtomicBoolean();

   /** Whether caves are enabled: the world setting, or a forced property override. */
   static boolean cavesEnabled(EarthGeneratorSettings settings) {
      return CAVES_FORCED || settings.caveGeneration();
   }

   private TellusVanillaCarverRunner getTellusCarverRunner(RegistryAccess registryAccess) {
      TellusVanillaCarverRunner cached = this.tellusCarverRunner;
      if (cached != null) {
         return cached;
      } else {
         synchronized (this) {
            cached = this.tellusCarverRunner;
            if (cached == null) {
               Registry<Block> blockRegistry = registryAccess.lookupOrThrow(Registries.BLOCK);
               Registry<NoiseGeneratorSettings> noiseSettings = registryAccess.lookupOrThrow(Registries.NOISE_SETTINGS);
               Holder<NoiseGeneratorSettings> adaptedCarverNoiseSettings = Objects.requireNonNull(
                  TellusNoiseSettingsAdapter.adaptToTellusHeight(
                     noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD), this.minY, this.height, this.seaLevel
                  ),
                  "adaptedCarverNoiseSettings"
               );
               cached = new TellusVanillaCarverRunner(
                  this.biomeSource, blockRegistry, adaptedCarverNoiseSettings, this.minY, this.height, this.seaLevel
               );
               this.tellusCarverRunner = cached;
            }

            return cached;
         }
      }
   }

   public int sampleCoverClass(int worldX, int worldZ) {
      return LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale());
   }

   public int sampleCoverClass(int worldX, int worldZ, double previewResolutionMeters) {
      return LAND_COVER_SOURCE.sampleCoverClass(worldX, worldZ, this.settings.worldScale(), previewResolutionMeters);
   }

   public int sampleVisualCoverClass(int worldX, int worldZ) {
      int rawCoverClass = this.sampleCoverClass(worldX, worldZ);
      return this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass);
   }

   public int sampleVisualCoverClass(int worldX, int worldZ, int rawCoverClass) {
      return this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass, this.settings.worldScale());
   }

   public int sampleVisualCoverClass(int worldX, int worldZ, int rawCoverClass, double previewResolutionMeters) {
      double worldScale = this.settings.worldScale();
      return shouldSampleVisualCover(worldScale, previewResolutionMeters, rawCoverClass)
         ? LAND_COVER_SOURCE.sampleVisualCoverClass(worldX, worldZ, worldScale, previewResolutionMeters)
         : rawCoverClass;
   }

   private static boolean shouldSampleVisualCover(double worldScale, double previewResolutionMeters, int rawCoverClass) {
      return worldScale > 0.0
         && worldScale < ESA_WORLD_COVER_RESOLUTION_METERS
         && !isHardRawCoverClass(rawCoverClass)
         && effectiveCoverResolutionMeters(worldScale, previewResolutionMeters) < ESA_WORLD_COVER_RESOLUTION_METERS;
   }

   private static double effectiveCoverResolutionMeters(double worldScale, double previewResolutionMeters) {
      return Double.isFinite(previewResolutionMeters) && previewResolutionMeters > 0.0
         ? Math.max(worldScale, previewResolutionMeters)
         : worldScale;
   }

   private static boolean isHardRawCoverClass(int coverClass) {
      return coverClass == ESA_NO_DATA || coverClass == ESA_WATER || coverClass == ESA_MANGROVES || coverClass == ESA_BUILT_UP;
   }

   public int resolveLodTerrainSurface(int worldX, int worldZ, int coverClass, double previewResolutionMeters) {
      int surface = this.sampleSurfaceHeight(worldX, worldZ, previewResolutionMeters);
      int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClass);
      return this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, this.minY, this.minY + this.height - 1);
   }

   int resolveLodMangroveWaterSurface(int worldX, int worldZ, int maxY) {
      return this.resolveMangroveWaterSurface(worldX, worldZ, maxY);
   }

   private int resolveEffectiveCoverClassForTerrain(int coverClass) {
      return coverClass;
   }

   private int resolveSurfaceCoverClassForTerrain(int terrainCoverClass, int visualCoverClass) {
      return MountainSurfaceRules.resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
   }

   private WaterSurfaceResolver.WaterColumnData resolveAuxWaterColumn(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      return this.waterResolver.resolveFastColumnData(worldX, worldZ, coverClass);
   }

   private WaterSurfaceResolver.WaterColumnData resolveOsmWaterColumn(int worldX, int worldZ, int coverClass, boolean detailed) {
      return this.resolveOsmWaterColumn(worldX, worldZ, coverClass, detailed, this.settings.worldScale());
   }

   private WaterSurfaceResolver.WaterColumnData resolveOsmWaterColumn(
      int worldX, int worldZ, int coverClass, boolean detailed, double previewResolutionMeters
   ) {
      WaterSurfaceResolver.WaterColumnData column = detailed
         ? this.waterResolver.resolveColumnData(worldX, worldZ, coverClass, previewResolutionMeters)
         : this.waterResolver.resolveFastColumnData(worldX, worldZ, coverClass, previewResolutionMeters);
      return coverClass == 95 ? this.mergeMangroveWaterColumn(worldX, worldZ, column) : column;
   }

   private WaterSurfaceResolver.WaterColumnData mergeMangroveWaterColumn(
      int worldX, int worldZ, WaterSurfaceResolver.WaterColumnData baseColumn
   ) {
      int terrainSurface = baseColumn.terrainSurface();
      int mangroveSurface = this.resolveMangroveWaterSurface(worldX, worldZ, Math.max(this.seaLevel, baseColumn.waterSurface()));
      int waterSurface = baseColumn.hasWater() ? Math.max(baseColumn.waterSurface(), mangroveSurface) : mangroveSurface;
      boolean hasWater = baseColumn.hasWater() || waterSurface > terrainSurface;
      if (!hasWater) {
         return baseColumn;
      } else {
         if (terrainSurface >= waterSurface) {
            terrainSurface = waterSurface - 1;
         }

         return new WaterSurfaceResolver.WaterColumnData(true, baseColumn.isOcean(), terrainSurface, waterSurface);
      }
   }

   private WaterSurfaceResolver.WaterColumnData normalizeResolvedWaterColumn(
      int worldX, int worldZ, int coverClass, int minY, int maxY, WaterSurfaceResolver.WaterColumnData column
   ) {
      int terrainSurface = Mth.clamp(column.terrainSurface(), minY, maxY);
      terrainSurface = this.repairAnomalousSurfaceHeight(worldX, worldZ, terrainSurface, coverClass, minY, maxY, column.hasWater());
      if (!column.hasWater()) {
         return new WaterSurfaceResolver.WaterColumnData(false, false, terrainSurface, terrainSurface);
      } else {
         int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
         if (terrainSurface >= waterSurface) {
            terrainSurface = Math.max(minY, waterSurface - 1);
         }

         return new WaterSurfaceResolver.WaterColumnData(true, column.isOcean(), terrainSurface, waterSurface);
      }
   }

   private EarthChunkGenerator.ColumnHeights columnHeightsFromWaterColumn(WaterSurfaceResolver.WaterColumnData column, int minY, int maxY) {
      int terrainSurface = Mth.clamp(column.terrainSurface(), minY, maxY);
      if (!column.hasWater()) {
         return new EarthChunkGenerator.ColumnHeights(terrainSurface, terrainSurface, false);
      } else {
         int waterSurface = Mth.clamp(column.waterSurface(), minY, maxY);
         if (terrainSurface >= waterSurface) {
            terrainSurface = Math.max(minY, waterSurface - 1);
         }

         return new EarthChunkGenerator.ColumnHeights(terrainSurface, waterSurface, true);
      }
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      return this.resolveLodWaterColumn(worldX, worldZ, coverClass);
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass) {
      return this.resolveLodWaterColumn(worldX, worldZ, coverClass, this.settings.worldScale());
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass, double previewResolutionMeters) {
      if (this.settings.enableWater()) {
         WaterSurfaceResolver.WaterColumnData column = this.normalizeResolvedWaterColumn(
            worldX,
            worldZ,
            coverClass,
            this.minY,
            this.minY + this.height - 1,
            this.resolveOsmWaterColumn(worldX, worldZ, coverClass, false, previewResolutionMeters)
         );
         return this.applyLodWaterDepthProfile(column);
      }

      int surface = this.sampleSurfaceHeight(worldX, worldZ, previewResolutionMeters);
      int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClass);
      surface = this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, this.minY, this.minY + this.height - 1);
      boolean noData = effectiveCoverClass == 0;
      boolean hasWater = effectiveCoverClass == 80 || effectiveCoverClass == 95 || noData && surface <= this.seaLevel;
      if (!hasWater) {
         return new WaterSurfaceResolver.WaterColumnData(false, false, surface, surface);
      } else {
         int waterSurface = Math.max(surface + 1, this.seaLevel);
         boolean isOcean = noData && surface <= this.seaLevel;
         return this.applyLodWaterDepthProfile(new WaterSurfaceResolver.WaterColumnData(true, isOcean, surface, waterSurface));
      }
   }

   public WaterSurfaceResolver.WaterColumnData applyLodWaterDepthProfile(WaterSurfaceResolver.WaterColumnData column) {
      if (!column.hasWater()) {
         return column;
      } else {
         int surface = column.terrainSurface();
         int waterSurface = column.waterSurface();
         if (!column.isOcean()) {
            int targetSurface = waterSurface - Math.max(1, LOD_MIN_WATER_DEPTH);
            if (surface > targetSurface) {
               surface = targetSurface;
            }

            if (surface >= waterSurface) {
               surface = waterSurface - 1;
            }
         }

         return new WaterSurfaceResolver.WaterColumnData(true, column.isOcean(), surface, waterSurface);
      }
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(int worldX, int worldZ, int coverClass, boolean useDetailedResolver) {
      return this.resolveLodWaterColumn(worldX, worldZ, coverClass, useDetailedResolver, this.settings.worldScale());
   }

   public WaterSurfaceResolver.WaterColumnData resolveLodWaterColumn(
      int worldX, int worldZ, int coverClass, boolean useDetailedResolver, double previewResolutionMeters
   ) {
      if (!useDetailedResolver) {
         return this.resolveLodWaterColumn(worldX, worldZ, coverClass, previewResolutionMeters);
      } else if (this.settings.enableWater()) {
         return this.normalizeResolvedWaterColumn(
            worldX,
            worldZ,
            coverClass,
            this.minY,
            this.minY + this.height - 1,
            this.resolveOsmWaterColumn(worldX, worldZ, coverClass, true, previewResolutionMeters)
         );
      } else {
         int surface = this.sampleSurfaceHeight(worldX, worldZ, previewResolutionMeters);
         int effectiveCoverClass = this.resolveEffectiveCoverClassForTerrain(coverClass);
         surface = this.repairAnomalousSurfaceHeight(worldX, worldZ, surface, effectiveCoverClass, this.minY, this.minY + this.height - 1);
         if (effectiveCoverClass == 95) {
            int waterSurface = this.resolveMangroveWaterSurface(worldX, worldZ, this.seaLevel);
            boolean hasWater = waterSurface > surface;
            return new WaterSurfaceResolver.WaterColumnData(hasWater, false, surface, waterSurface);
         } else {
            return this.waterResolver.resolveColumnData(worldX, worldZ, coverClass, previewResolutionMeters);
         }
      }
   }

   public void prefetchLodWaterRegions(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
      this.prefetchLodWaterRegions(minBlockX, minBlockZ, maxBlockX, maxBlockZ, true);
   }

   public void prefetchLodWaterRegions(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, boolean allowInlineExecution) {
      int padding = OCEAN_SHORE_MAX_DISTANCE;
      TellusWorldgenSources.prefetchWaterRegionsForArea(
         minBlockX - padding, minBlockZ - padding, maxBlockX + padding, maxBlockZ + padding, this.settings, allowInlineExecution
      );
   }


   public BlockState resolveBadlandsBandBlock(int worldX, int worldZ, int y) {
      int offset = badlandsBandOffset(worldX, worldZ);
      return badlandsBand(y, offset);
   }

   private EarthChunkGenerator.SurfacePalette selectSurfacePalette(
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      if (!underwater && this.isRemaSnowTerrain(worldZ)) {
         return EarthChunkGenerator.SurfacePalette.snowy();
      }

      long phaseStart = beginLodSurfaceProfiling(profiler);
      EarthChunkGenerator.SurfacePalette palette = this.selectBaseSurfacePalette(biome, worldX, worldZ, surface, coverClass);
      endLodSurfaceProfiling(profiler, "generator.basePalette", phaseStart);
      if (palette != null) {
         phaseStart = beginLodSurfaceProfiling(profiler);
         palette = this.applyShorelinePaletteOverride(
            palette, biome, worldX, worldZ, surface, underwater, slopeDiff, convexity, coverClass, profiler, shorelineCache
         );
         endLodSurfaceProfiling(profiler, "generator.shoreline", phaseStart);
      }

      if (palette == null) {
         return null;
      } else {
         phaseStart = beginLodSurfaceProfiling(profiler);
         EarthChunkGenerator.SurfacePalette resolved = this.applySlopeSurfaceOverride(
            palette, underwater, slopeDiff, convexity, coverClass, biome, worldX, worldZ, surface, profiler
         );
         endLodSurfaceProfiling(profiler, "generator.slopeOverride", phaseStart);
         phaseStart = beginLodSurfaceProfiling(profiler);
         EarthChunkGenerator.SurfacePalette sandResolved = this.applyOvertureSandPaletteOverride(resolved, worldX, worldZ, underwater);
         endLodSurfaceProfiling(profiler, "generator.overtureSand", phaseStart);
         return sandResolved;
      }
   }

   private EarthChunkGenerator.SurfacePalette applyShorelinePaletteOverride(
      EarthChunkGenerator.SurfacePalette palette,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      EarthChunkGenerator.LodSurfaceProfiler profiler,
      EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      if (Boolean.TRUE.equals(this.lodShorelineOverrideSuppressed.get())) {
         return palette;
      }

      if (shorelineCache != null && shorelineCache.definitelyDry()) {
         return palette;
      }

      long phaseStart = beginLodSurfaceProfiling(profiler);
      EarthChunkGenerator.ShorelineContext shoreline = this.resolveShorelineContext(worldX, worldZ, surface, underwater, coverClass, shorelineCache);
      endLodSurfaceProfiling(profiler, "generator.shoreline.context", phaseStart);
      if (shoreline.kind() == MountainSurfaceRules.ShorelineKind.NONE) {
         return palette;
      } else if (underwater && (!shoreline.shallowWater() || shoreline.distanceToShore() > SHALLOW_SHORE_WATER_DISTANCE)) {
         return palette;
      } else {
         phaseStart = beginLodSurfaceProfiling(profiler);
         byte climateGroup = climateGroupForBiome(biome);
         MountainSurfaceRules.ShorelineMaterial material = MountainSurfaceRules.classifyShorelineMaterial(
            coverClass,
            climateGroup,
            surface - this.seaLevel,
            slopeDiff,
            convexity,
            shoreline.kind(),
            shoreline.distanceToShore(),
            biome.is(BiomeTags.IS_BADLANDS)
         );
         endLodSurfaceProfiling(profiler, "generator.shoreline.classify", phaseStart);
         return switch (material) {
            case NONE, PRESERVE_WETLAND -> palette;
            case SAND, RED_SAND -> palette;
            case GRAVEL -> EarthChunkGenerator.SurfacePalette.gravelly();
         };
      }
   }

   private EarthChunkGenerator.SurfacePalette applyOvertureSandPaletteOverride(
      EarthChunkGenerator.SurfacePalette palette, int worldX, int worldZ, boolean underwater
   ) {
      if (palette == null || underwater || !this.hasOvertureSandAt(worldX, worldZ)) {
         return palette;
      } else {
         return EarthChunkGenerator.SurfacePalette.beach();
      }
   }

   private static EarthChunkGenerator.SurfacePalette mapApproximateSurfacePalette(
      MountainSurfaceRules.ApproximateSurface approximate, EarthChunkGenerator.SurfacePalette fallback
   ) {
      return switch (approximate.palette()) {
         case NONE -> fallback;
         case SNOW -> EarthChunkGenerator.SurfacePalette.snowy();
         case DEEPSLATE -> EarthChunkGenerator.SurfacePalette.deepslateBlend();
         case DEEPSLATE_SCREE -> EarthChunkGenerator.SurfacePalette.deepslateScree();
         case DEEPSLATE_TALUS -> EarthChunkGenerator.SurfacePalette.deepslateTalus();
         case WEATHERED_ANDESITE -> EarthChunkGenerator.SurfacePalette.weatheredAndesite();
         case TUFF -> EarthChunkGenerator.SurfacePalette.tuffPeaks();
      };
   }

   private BlockState resolveMountainMassFillBlock(int surfaceCoverClass, int surface, int slopeDiff, int convexity, int worldZ) {
      MountainSurfaceRules.ApproximateSurface approximate = MountainSurfaceRules.classifyApproximateSurface(
         surfaceCoverClass, surfaceCoverClass, surface - this.seaLevel, slopeDiff, convexity, this.isRemaSnowTerrain(worldZ)
      );
      return switch (approximate.palette()) {
         case DEEPSLATE, DEEPSLATE_TALUS -> DEEPSLATE_STATE;
         case WEATHERED_ANDESITE -> ANDESITE_STATE;
         case TUFF -> TUFF_STATE;
         default -> null;
      };
   }

   private EarthChunkGenerator.ShorelineContext resolveShorelineContext(
      int worldX, int worldZ, int surface, boolean underwater, int coverClass, EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      return this.resolveShorelineContext(worldX, worldZ, surface, underwater, coverClass, shorelineCache, true);
   }

   private EarthChunkGenerator.ShorelineContext resolveShorelineContext(
      int worldX,
      int worldZ,
      int surface,
      boolean underwater,
      int coverClass,
      EarthChunkGenerator.LodShorelineCache shorelineCache,
      boolean applyNoiseMask
   ) {
      return underwater
         ? this.resolveShallowWaterShorelineContext(worldX, worldZ, surface, coverClass, shorelineCache)
         : this.resolveLandShorelineContext(worldX, worldZ, shorelineCache, applyNoiseMask);
   }

   private EarthChunkGenerator.ShorelineContext resolveLandShorelineContext(
      int worldX, int worldZ, EarthChunkGenerator.LodShorelineCache shorelineCache, boolean applyNoiseMask
   ) {
      if (shorelineCache != null && shorelineCache.definitelyDry()) {
         return EarthChunkGenerator.ShorelineContext.NONE;
      }

      int bestOceanDistance = Integer.MAX_VALUE;
      int bestInlandDistance = Integer.MAX_VALUE;

      for (int dz = -OCEAN_SHORE_MAX_DISTANCE; dz <= OCEAN_SHORE_MAX_DISTANCE; dz++) {
         int z = worldZ + dz;

         for (int dx = -OCEAN_SHORE_MAX_DISTANCE; dx <= OCEAN_SHORE_MAX_DISTANCE; dx++) {
            int distance = shorelineDistance(dx, dz);
            if (distance <= 0 || distance > OCEAN_SHORE_MAX_DISTANCE) {
               continue;
            }

            int x = worldX + dx;
            WaterSurfaceResolver.WaterInfo info = shorelineCache != null
               ? shorelineCache.resolveFastWaterInfo(x, z)
               : this.resolveFastWaterInfoForShoreline(x, z);
            if (!info.isWater()) {
               continue;
            }

            if (info.isOcean()) {
               if (distance < bestOceanDistance) {
                  bestOceanDistance = distance;
               }
            } else if (distance <= INLAND_SHORE_MAX_DISTANCE && distance < bestInlandDistance) {
               bestInlandDistance = distance;
            }
         }
      }

      MountainSurfaceRules.ShorelineKind bestKind = MountainSurfaceRules.ShorelineKind.NONE;
      int bestDistance = Integer.MAX_VALUE;
      if (bestOceanDistance <= OCEAN_SHORE_MAX_DISTANCE) {
         bestKind = MountainSurfaceRules.ShorelineKind.OCEAN;
         bestDistance = bestOceanDistance;
      }

      if (bestInlandDistance <= INLAND_SHORE_MAX_DISTANCE && bestInlandDistance < bestDistance) {
         bestKind = MountainSurfaceRules.ShorelineKind.INLAND;
         bestDistance = bestInlandDistance;
      }

      if (bestKind == MountainSurfaceRules.ShorelineKind.NONE || applyNoiseMask && !this.passesShorelineEdgeNoise(bestKind, bestDistance, worldX, worldZ)) {
         return EarthChunkGenerator.ShorelineContext.NONE;
      } else {
         return new EarthChunkGenerator.ShorelineContext(bestKind, bestDistance, false);
      }
   }

   private EarthChunkGenerator.ShorelineContext resolveShallowWaterShorelineContext(
      int worldX, int worldZ, int surface, int coverClass, EarthChunkGenerator.LodShorelineCache shorelineCache
   ) {
      WaterSurfaceResolver.WaterInfo info = shorelineCache != null
         ? shorelineCache.resolveFastWaterInfo(worldX, worldZ)
         : this.waterResolver.resolveFastWaterInfo(worldX, worldZ, coverClass);
      if (!info.isWater()) {
         return EarthChunkGenerator.ShorelineContext.NONE;
      } else {
         int waterDepth = info.surface() - surface;
         if (waterDepth <= 0 || waterDepth > SHALLOW_SHORE_WATER_DEPTH) {
            return EarthChunkGenerator.ShorelineContext.NONE;
         } else {
            MountainSurfaceRules.ShorelineKind shoreKind = info.isOcean()
               ? MountainSurfaceRules.ShorelineKind.OCEAN
               : MountainSurfaceRules.ShorelineKind.INLAND;
            int bestLandDistance = Integer.MAX_VALUE;

            for (int dz = -SHALLOW_SHORE_WATER_DISTANCE; dz <= SHALLOW_SHORE_WATER_DISTANCE; dz++) {
               int z = worldZ + dz;

               for (int dx = -SHALLOW_SHORE_WATER_DISTANCE; dx <= SHALLOW_SHORE_WATER_DISTANCE; dx++) {
                  int distance = shorelineDistance(dx, dz);
                  if (distance <= 0 || distance > SHALLOW_SHORE_WATER_DISTANCE) {
                     continue;
                  }

                  int x = worldX + dx;
                  WaterSurfaceResolver.WaterInfo neighborInfo = shorelineCache != null
                     ? shorelineCache.resolveFastWaterInfo(x, z)
                     : this.resolveFastWaterInfoForShoreline(x, z);
                  if (!neighborInfo.isWater() && distance < bestLandDistance) {
                     bestLandDistance = distance;
                  }
               }
            }

            return bestLandDistance <= SHALLOW_SHORE_WATER_DISTANCE
               ? new EarthChunkGenerator.ShorelineContext(shoreKind, bestLandDistance, true)
               : EarthChunkGenerator.ShorelineContext.NONE;
            }
      }
   }

   private WaterSurfaceResolver.WaterInfo resolveFastWaterInfoForShoreline(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      return this.resolveFastWaterInfoForShoreline(worldX, worldZ, coverClass);
   }

   private WaterSurfaceResolver.WaterInfo resolveFastWaterInfoForShoreline(int worldX, int worldZ, double previewResolutionMeters) {
      int coverClass = this.sampleCoverClass(worldX, worldZ, previewResolutionMeters);
      return this.resolveFastWaterInfoForShoreline(worldX, worldZ, coverClass);
   }

   private WaterSurfaceResolver.WaterInfo resolveFastWaterInfoForShoreline(int worldX, int worldZ, int coverClass) {
      return this.waterResolver.resolveFastWaterInfo(worldX, worldZ, coverClass);
   }

   private EarthChunkGenerator.FullChunkOceanBeachCache buildFullChunkOceanBeachCache(
      int chunkMinX, int chunkMinZ, boolean[] waterFlags, boolean[] oceanFlags
   ) {
      int radius = FULL_CHUNK_OCEAN_BEACH_MAX_DISTANCE;
      if (radius <= 0 || !this.settings.enableWater()) {
         return EarthChunkGenerator.FullChunkOceanBeachCache.disabled(chunkMinX, chunkMinZ);
      } else {
         int side = CHUNK_SIDE + radius * 2;
         boolean[] oceanMask = new boolean[side * side];
         boolean hasOcean = false;

         for (int dz = -radius; dz < CHUNK_SIDE + radius; dz++) {
            int worldZ = chunkMinZ + dz;
            int row = (dz + radius) * side;

            for (int dx = -radius; dx < CHUNK_SIDE + radius; dx++) {
               int worldX = chunkMinX + dx;
               boolean ocean;
               if (dx >= 0 && dx < CHUNK_SIDE && dz >= 0 && dz < CHUNK_SIDE) {
                  int chunkIndex = chunkIndex(dx, dz);
                  ocean = waterFlags[chunkIndex] && oceanFlags[chunkIndex];
               } else {
                  WaterSurfaceResolver.WaterInfo info = this.resolveFastWaterInfoForShoreline(worldX, worldZ);
                  ocean = info.isWater() && info.isOcean();
               }

               oceanMask[row + dx + radius] = ocean;
               hasOcean |= ocean;
            }
         }

         if (!hasOcean) {
            return EarthChunkGenerator.FullChunkOceanBeachCache.disabled(chunkMinX, chunkMinZ);
         } else {
            byte[] oceanDistances = new byte[CHUNK_AREA];
            Arrays.fill(oceanDistances, (byte)-1);

            for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
               for (int localX = 0; localX < CHUNK_SIDE; localX++) {
                  int chunkIndex = chunkIndex(localX, localZ);
                  if (!waterFlags[chunkIndex]) {
                     int bestDistance = Integer.MAX_VALUE;

                     for (int dz = -radius; dz <= radius; dz++) {
                        int row = (localZ + dz + radius) * side;

                        for (int dx = -radius; dx <= radius; dx++) {
                           int distance = shorelineDistance(dx, dz);
                           if (distance > 0 && distance <= radius && oceanMask[row + localX + dx + radius] && distance < bestDistance) {
                              bestDistance = distance;
                           }
                        }
                     }

                     if (bestDistance != Integer.MAX_VALUE) {
                        oceanDistances[chunkIndex] = (byte)bestDistance;
                     }
                  }
               }
            }

            return new EarthChunkGenerator.FullChunkOceanBeachCache(chunkMinX, chunkMinZ, oceanDistances, true);
         }
      }
   }

   private EarthChunkGenerator.LodShorelineCache buildDenseLodShorelineCache(
      int minX, int maxX, int minZ, int maxZ, int width, int height, double previewResolutionMeters
   ) {
      WaterSurfaceResolver.WaterInfo[] waterInfos = new WaterSurfaceResolver.WaterInfo[width * height];
      boolean hasWater = false;
      int index = 0;

      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            WaterSurfaceResolver.WaterInfo info = this.resolveFastWaterInfoForShoreline(x, z, previewResolutionMeters);
            waterInfos[index++] = info;
            hasWater |= info.isWater();
         }
      }

      return new EarthChunkGenerator.DenseLodShorelineCache(
         minX, maxX, minZ, maxZ, width, waterInfos, hasWater, previewResolutionMeters
      );
   }

   private EarthChunkGenerator.LodMountainTransitionCache buildDenseLodMountainTransitionCache(
      int minX, int maxX, int minZ, int maxZ, int width, int height, double previewResolutionMeters
   ) {
      byte[] surfaceCoverClasses = new byte[width * height];
      int index = 0;

      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            int rawCoverClass = this.sampleCoverClass(x, z, previewResolutionMeters);
            int visualCoverClass = this.sampleVisualCoverClass(x, z, rawCoverClass, previewResolutionMeters);
            int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass);
            surfaceCoverClasses[index++] = (byte)surfaceCoverClass;
         }
      }

      return new EarthChunkGenerator.DenseLodMountainTransitionCache(
         minX, maxX, minZ, maxZ, width, surfaceCoverClasses, previewResolutionMeters
      );
   }

   private EarthChunkGenerator.LodSharedTerrainCache buildDenseLodSharedTerrainCache(
      int coverMinX,
      int coverMaxX,
      int coverMinZ,
      int coverMaxZ,
      int coverWidth,
      int coverHeight,
      int shoreMinX,
      int shoreMaxX,
      int shoreMinZ,
      int shoreMaxZ,
      int shoreWidth,
      int shoreHeight,
      double previewResolutionMeters
   ) {
      byte[] rawCoverClasses = new byte[coverWidth * coverHeight];
      byte[] visualCoverClasses = new byte[coverWidth * coverHeight];
      byte[] surfaceCoverClasses = new byte[coverWidth * coverHeight];
      WaterSurfaceResolver.WaterInfo[] waterInfos = new WaterSurfaceResolver.WaterInfo[shoreWidth * shoreHeight];
      boolean hasWater = false;
      int coverIndex = 0;

      for (int z = coverMinZ; z <= coverMaxZ; z++) {
         boolean inShorelineZ = z >= shoreMinZ && z <= shoreMaxZ;
         int shorelineRow = inShorelineZ ? (z - shoreMinZ) * shoreWidth : -1;

         for (int x = coverMinX; x <= coverMaxX; x++) {
            int rawCoverClass = this.sampleCoverClass(x, z, previewResolutionMeters);
            int visualCoverClass = this.sampleVisualCoverClass(x, z, rawCoverClass, previewResolutionMeters);
            int surfaceCoverClass = MountainSurfaceRules.resolveSurfaceCoverClass(rawCoverClass, visualCoverClass);
            rawCoverClasses[coverIndex] = (byte)rawCoverClass;
            visualCoverClasses[coverIndex] = (byte)visualCoverClass;
            surfaceCoverClasses[coverIndex] = (byte)surfaceCoverClass;
            if (inShorelineZ && x >= shoreMinX && x <= shoreMaxX) {
               WaterSurfaceResolver.WaterInfo info = this.resolveFastWaterInfoForShoreline(x, z, rawCoverClass);
               waterInfos[shorelineRow + (x - shoreMinX)] = info;
               hasWater |= info.isWater();
            }

            coverIndex++;
         }
      }

      EarthChunkGenerator.DenseLodShorelineCache shorelineCache = new EarthChunkGenerator.DenseLodShorelineCache(
         shoreMinX, shoreMaxX, shoreMinZ, shoreMaxZ, shoreWidth, waterInfos, hasWater, previewResolutionMeters
      );
      EarthChunkGenerator.DenseLodMountainTransitionCache mountainTransitionCache = new EarthChunkGenerator.DenseLodMountainTransitionCache(
         coverMinX, coverMaxX, coverMinZ, coverMaxZ, coverWidth, surfaceCoverClasses, previewResolutionMeters
      );
      return new EarthChunkGenerator.DenseLodSharedTerrainCache(
         coverMinX,
         coverMaxX,
         coverMinZ,
         coverMaxZ,
         coverWidth,
         rawCoverClasses,
         visualCoverClasses,
         shorelineCache,
         mountainTransitionCache,
         previewResolutionMeters
      );
   }

   private boolean passesShorelineEdgeNoise(MountainSurfaceRules.ShorelineKind shoreKind, int distanceToShore, int worldX, int worldZ) {
      int maxDistance = shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN ? OCEAN_SHORE_MAX_DISTANCE : INLAND_SHORE_MAX_DISTANCE;
      if (distanceToShore > maxDistance) {
         return false;
      } else {
         int solidDistance = Math.max(1, maxDistance - SHORELINE_OUTER_NOISE_BAND);
         if (distanceToShore <= solidDistance) {
            return true;
         } else {
            double mask = this.sampleLowFrequencyMask(
               worldX,
               worldZ,
               shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN ? 80 : 56,
               shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN ? 8654246259872604351L : 6417398719980302667L
            );
            int edgeIndex = distanceToShore - solidDistance;
            double threshold = edgeIndex >= SHORELINE_OUTER_NOISE_BAND ? 0.72 : 0.38;
            return mask >= threshold;
         }
      }
   }

   private static int shorelineDistance(int dx, int dz) {
      if (dx == 0 && dz == 0) {
         return 0;
      } else {
         return (int)Math.ceil(Math.sqrt((double)(dx * dx + dz * dz)));
      }
   }

   private EarthChunkGenerator.SurfacePalette applySlopeSurfaceOverride(
      EarthChunkGenerator.SurfacePalette palette,
      boolean underwater,
      int slopeDiff,
      int convexity,
      int coverClass,
      Holder<Biome> biome,
      int worldX,
      int worldZ,
      int surface,
      EarthChunkGenerator.LodSurfaceProfiler profiler
   ) {
      if (underwater) {
         return palette;
      } else {
         int heightAboveSea = surface - this.seaLevel;
         long phaseStart = beginLodSurfaceProfiling(profiler);
         float vegetationTransitionWeight = this.sampleMountainVegetationTransitionWeight(worldX, worldZ, coverClass, heightAboveSea);
         endLodSurfaceProfiling(profiler, "generator.slope.transition", phaseStart);
         phaseStart = beginLodSurfaceProfiling(profiler);
         EarthChunkGenerator.MountainSurfaceContext mountain = this.evaluateMountainSurfaceContext(
            biome, coverClass, heightAboveSea, slopeDiff, convexity, vegetationTransitionWeight, worldX, worldZ
         );
         endLodSurfaceProfiling(profiler, "generator.slope.context", phaseStart);
         if (coverClass == 70) {
            if (mountain.retainSnow()) {
               return isSnowPalette(palette) ? palette : EarthChunkGenerator.SurfacePalette.snowy();
            } else {
               phaseStart = beginLodSurfaceProfiling(profiler);
               EarthChunkGenerator.SurfacePalette mountainPalette = this.selectMountainRockPalette(
                  biome, coverClass, heightAboveSea, slopeDiff, convexity, vegetationTransitionWeight, worldX, worldZ, surface, mountain, true
               );
               endLodSurfaceProfiling(profiler, "generator.slope.rockPalette", phaseStart);
               return mountainPalette;
            }
         } else {
            if (MountainSurfaceRules.isMountainRockyCover(coverClass, heightAboveSea)) {
               phaseStart = beginLodSurfaceProfiling(profiler);
               EarthChunkGenerator.SurfacePalette mountainPalette = this.selectMountainRockPalette(
                  biome, coverClass, heightAboveSea, slopeDiff, convexity, vegetationTransitionWeight, worldX, worldZ, surface, mountain, false
               );
               endLodSurfaceProfiling(profiler, "generator.slope.rockPalette", phaseStart);
               if (mountainPalette != null) {
                  return mountainPalette;
               }
            }

            if (!isSoilPalette(palette) || coverClass == 10) {
               return palette;
            } else {
               return slopeDiff >= 3 ? EarthChunkGenerator.SurfacePalette.stonyPeaks() : palette;
            }
         }
      }
   }

   private EarthChunkGenerator.SurfacePalette selectMountainRockPalette(
      Holder<Biome> biome,
      int coverClass,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      float vegetationTransitionWeight,
      int worldX,
      int worldZ,
      int surface,
      EarthChunkGenerator.MountainSurfaceContext mountain,
      boolean forceRock
   ) {
      int ruggedness = slopeDiff + Math.max(0, -convexity);
      if (!forceRock && !MountainSurfaceRules.qualifiesForMountainPalette(coverClass, heightAboveSea, slopeDiff, convexity)) {
         return null;
      } else {
         EarthChunkGenerator.MountainRockStyle rockStyle = this.selectMountainRockStyle(
            biome, coverClass, heightAboveSea, slopeDiff, convexity, vegetationTransitionWeight, worldX, worldZ, surface, mountain
         );
         return vegetationTransitionWeight >= 0.18F
            ? this.selectMountainTransitionPalette(rockStyle, slopeDiff, convexity, vegetationTransitionWeight, worldX, worldZ)
            : this.selectOpenMountainPalette(rockStyle, coverClass, heightAboveSea, slopeDiff, convexity, ruggedness, worldX, worldZ, mountain);
      }
   }

   private EarthChunkGenerator.MountainRockStyle selectMountainRockStyle(
      Holder<Biome> biome,
      int coverClass,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      float vegetationTransitionWeight,
      int worldX,
      int worldZ,
      int surface,
      EarthChunkGenerator.MountainSurfaceContext mountain
   ) {
      int ruggedness = slopeDiff + Math.max(0, -convexity);
      boolean exposedHeadwall = ruggedness >= 7 && slopeDiff >= 4 && convexity <= 0;
      double massifMask = this.sampleLowFrequencyMask(worldX, worldZ, 384, 5218017843159689053L);
      double weatheringMask = this.sampleLowFrequencyMask(worldX, worldZ, 160, 1763801554899217605L);
      double cliffBandMask = this.sampleLowFrequencyMask(worldX, worldZ, 112, 6603111125920475051L);
      if (heightAboveSea >= 170
         && exposedHeadwall
         && vegetationTransitionWeight <= 0.14F
         && ruggedness >= 8
         && massifMask < 0.18
         && weatheringMask < 0.42
         && cliffBandMask < 0.3) {
         return EarthChunkGenerator.MountainRockStyle.TUFF;
      } else if (heightAboveSea >= MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA
         && exposedHeadwall
         && vegetationTransitionWeight <= 0.2F
         && (weatheringMask > 0.86 || cliffBandMask > 0.82 && ruggedness >= 8 || massifMask > 0.84 && biome.is(Biomes.STONY_PEAKS))) {
         return EarthChunkGenerator.MountainRockStyle.ANDESITE;
      } else {
         return EarthChunkGenerator.MountainRockStyle.DEEPSLATE;
      }
   }

   private EarthChunkGenerator.SurfacePalette selectMountainTransitionPalette(
      EarthChunkGenerator.MountainRockStyle rockStyle, int slopeDiff, int convexity, float transitionWeight, int worldX, int worldZ
   ) {
      boolean rockyEdge = slopeDiff >= 4 || convexity <= 0 || transitionWeight < 0.45F;
      double accentMask = this.sampleLowFrequencyMask(worldX, worldZ, 144, 2396572851536995599L);
      return switch (rockStyle) {
         case DEEPSLATE -> rockyEdge ? EarthChunkGenerator.SurfacePalette.transitionDeepslate() : EarthChunkGenerator.SurfacePalette.deepslateBlend();
         case TUFF -> rockyEdge && accentMask < 0.52 ? EarthChunkGenerator.SurfacePalette.transitionTuff() : EarthChunkGenerator.SurfacePalette.transitionDeepslate();
         case ANDESITE -> rockyEdge && accentMask > 0.74
            ? EarthChunkGenerator.SurfacePalette.transitionAndesite()
            : EarthChunkGenerator.SurfacePalette.transitionDeepslate();
      };
   }

   private EarthChunkGenerator.SurfacePalette selectOpenMountainPalette(
      EarthChunkGenerator.MountainRockStyle rockStyle,
      int coverClass,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      int ruggedness,
      int worldX,
      int worldZ,
      EarthChunkGenerator.MountainSurfaceContext mountain
   ) {
      boolean exposedHeadwall = ruggedness >= 6 && slopeDiff >= 4 && convexity <= 0;
      boolean screeCandidate = slopeDiff >= 6 || slopeDiff >= 5 && convexity >= 1;
      boolean talusCandidate = slopeDiff >= 4 && convexity >= 1;
      double talusMask = this.sampleLowFrequencyMask(worldX, worldZ, 88, 3675309543363558789L);
      if (screeCandidate && talusMask >= 0.38) {
         return rockStyle == EarthChunkGenerator.MountainRockStyle.TUFF
            ? EarthChunkGenerator.SurfacePalette.tuffScree()
            : EarthChunkGenerator.SurfacePalette.deepslateScree();
      } else if (talusCandidate && talusMask >= 0.58) {
         return EarthChunkGenerator.SurfacePalette.deepslateTalus();
      } else {
         return switch (rockStyle) {
            case DEEPSLATE -> mountain.darkRockScore() >= 78 || ruggedness >= 7
               ? EarthChunkGenerator.SurfacePalette.deepslateMass()
               : EarthChunkGenerator.SurfacePalette.deepslateBlend();
            case TUFF -> exposedHeadwall ? EarthChunkGenerator.SurfacePalette.tuffPeaks() : EarthChunkGenerator.SurfacePalette.deepslateBlend();
            case ANDESITE -> exposedHeadwall && heightAboveSea >= 160
               ? EarthChunkGenerator.SurfacePalette.weatheredAndesite()
               : EarthChunkGenerator.SurfacePalette.deepslateBlend();
         };
      }
   }

   private float sampleMountainVegetationTransitionWeight(int worldX, int worldZ, int coverClass, int heightAboveSea) {
      float localWeight = MountainSurfaceRules.vegetationTransitionWeight(coverClass, coverClass, heightAboveSea);
      if (localWeight >= 1.0F) {
         return 1.0F;
      } else if (!MountainSurfaceRules.isMountainRockyCover(coverClass, heightAboveSea)) {
         return 0.0F;
      } else {
         float weightedHits = 0.0F;
         float totalWeight = 0.0F;
         weightedHits += this.sampleVegetationTransitionRing(worldX, worldZ, 16, 0.46F, heightAboveSea);
         totalWeight++;
         weightedHits += this.sampleVegetationTransitionRing(worldX, worldZ, 32, 0.34F, heightAboveSea);
         totalWeight++;
         weightedHits += this.sampleVegetationTransitionRing(worldX, worldZ, 48, 0.2F, heightAboveSea);
         totalWeight += 0.8F;
         return totalWeight <= 0.0F ? 0.0F : Mth.clamp(weightedHits / totalWeight, 0.0F, 1.0F);
      }
   }

   private float sampleVegetationTransitionRing(int worldX, int worldZ, int distance, float weightPerSample, int heightAboveSea) {
      float hits = 0.0F;
      hits += this.sampleMountainVegetationWeight(worldX + distance, worldZ, heightAboveSea) * weightPerSample;
      hits += this.sampleMountainVegetationWeight(worldX - distance, worldZ, heightAboveSea) * weightPerSample;
      hits += this.sampleMountainVegetationWeight(worldX, worldZ + distance, heightAboveSea) * weightPerSample;
      return hits + this.sampleMountainVegetationWeight(worldX, worldZ - distance, heightAboveSea) * weightPerSample;
   }

   private float sampleMountainVegetationWeight(int worldX, int worldZ, int heightAboveSea) {
      EarthChunkGenerator.LodMountainTransitionCache cache = this.lodMountainTransitionCache.get();
      if (cache != null) {
         return cache.sampleVegetationWeight(worldX, worldZ, heightAboveSea);
      }

      // 2026-10-12: memoise the probe. Without this every neighbour ring sample re-reads the cover data for
      // the same coordinates (see the note on mountainProbeCache). Keyed by position only -- heightAboveSea is
      // derived from the position by the sampler, so two probes at the same (x, z) always agree.
      EarthChunkGenerator.MountainProbeCache probeCache = this.mountainProbeCache.get();
      if (probeCache != null) {
         return probeCache.sample(this, worldX, worldZ, heightAboveSea);
      }

      return this.sampleMountainVegetationWeightUncached(worldX, worldZ, heightAboveSea);
   }

   /**
    * Bounded per-thread memo for {@link #sampleMountainVegetationWeightUncached}.
    *
    * <p>A small open-addressing table rather than a map: the working set is a few hundred entries per chunk
    * and the probes sit on the hot path of every surface column, so allocation and boxing are worth avoiding.
    * Collisions simply overwrite, which costs a recompute but can never return a wrong answer.
    */
   static final class MountainProbeCache {
      private static final int SIZE = 4096;
      private static final int MASK = SIZE - 1;

      private final int[] keyX = new int[SIZE];
      private final int[] keyZ = new int[SIZE];
      private final float[] value = new float[SIZE];
      private final boolean[] used = new boolean[SIZE];

      void clear() {
         java.util.Arrays.fill(this.used, false);
      }

      float sample(EarthChunkGenerator generator, int worldX, int worldZ, int heightAboveSea) {
         int slot = hash(worldX, worldZ) & MASK;
         if (this.used[slot] && this.keyX[slot] == worldX && this.keyZ[slot] == worldZ) {
            return this.value[slot];
         }
         float result = generator.sampleMountainVegetationWeightUncached(worldX, worldZ, heightAboveSea);
         this.keyX[slot] = worldX;
         this.keyZ[slot] = worldZ;
         this.value[slot] = result;
         this.used[slot] = true;
         return result;
      }

      private static int hash(int x, int z) {
         int h = x * 0x9E3779B1 ^ z * 0x85EBCA77;
         return h ^ (h >>> 15);
      }
   }

   private float sampleMountainVegetationWeightUncached(int worldX, int worldZ, int heightAboveSea) {
      return this.sampleMountainVegetationWeightUncached(worldX, worldZ, heightAboveSea, this.settings.worldScale());
   }

   private float sampleMountainVegetationWeightUncached(int worldX, int worldZ, int heightAboveSea, double previewResolutionMeters) {
      int rawCoverClass = this.sampleCoverClass(worldX, worldZ, previewResolutionMeters);
      int visualCoverClass = this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass, previewResolutionMeters);
      return MountainSurfaceRules.vegetationTransitionWeight(rawCoverClass, visualCoverClass, heightAboveSea);
   }

   private static boolean isSnowPalette(EarthChunkGenerator.SurfacePalette palette) {
      return palette.top().is(Blocks.SNOW_BLOCK);
   }

   private boolean shouldRetainMountainSnow(int coverClass, int heightAboveSea, int slopeDiff, int convexity, int worldX, int worldZ) {
      return coverClass != 70 ? false : this.computeSnowRetentionScore(heightAboveSea, slopeDiff, convexity, worldX, worldZ) >= 52;
   }

   private EarthChunkGenerator.MountainSurfaceContext evaluateMountainSurfaceContext(
      Holder<Biome> biome, int coverClass, int heightAboveSea, int slopeDiff, int convexity, float vegetationTransitionWeight, int worldX, int worldZ
   ) {
      boolean mountainCover = MountainSurfaceRules.isMountainRockyCover(coverClass, heightAboveSea);
      int snowRetentionScore = 0;
      boolean retainSnow = false;
      if (coverClass == 70) {
         snowRetentionScore = this.computeSnowRetentionScore(heightAboveSea, slopeDiff, convexity, worldX, worldZ);
         retainSnow = snowRetentionScore >= 52;
      }

      int darkRockScore = this.computeDarkRockScore(
         biome, coverClass, mountainCover, heightAboveSea, slopeDiff, convexity, vegetationTransitionWeight, snowRetentionScore, worldX, worldZ
      );
      if (heightAboveSea < MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA) {
         darkRockScore = 0;
      }

      if (darkRockScore >= 74 && heightAboveSea < 180) {
         darkRockScore = 54;
      }

      return new EarthChunkGenerator.MountainSurfaceContext(retainSnow, darkRockScore);
   }

   private int computeSnowRetentionScore(int heightAboveSea, int slopeDiff, int convexity, int worldX, int worldZ) {
      double breakupMask = this.sampleLowFrequencyMask(worldX, worldZ, 96, 6520119525519978593L);
      int score = 56;
      score += Mth.clamp((heightAboveSea - 110) / 2, -20, 32);
      score -= slopeDiff * 11;
      score += Mth.clamp(convexity * 9, -18, 24);
      score += (int)Math.round((breakupMask - 0.5) * 24.0);
      if (heightAboveSea >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA) {
         score += 8;
      }

      return Mth.clamp(score, 0, 100);
   }

   private int computeDarkRockScore(
      Holder<Biome> biome,
      int coverClass,
      boolean mountainCover,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      float vegetationTransitionWeight,
      int snowRetentionScore,
      int worldX,
      int worldZ
   ) {
      if (!mountainCover) {
         return 0;
      } else {
         double darkMask = this.sampleLowFrequencyMask(worldX, worldZ, 192, 3586483281342227053L);
         int ruggedness = slopeDiff + Math.max(0, -convexity);
         int score = -18;
         score += Math.max(0, (heightAboveSea - MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA) / 2);
         score += ruggedness * 9;
         score += (int)Math.round((darkMask - 0.5) * 26.0);
         score += Mth.clamp((100 - snowRetentionScore) / 3, 0, 28);
         if (heightAboveSea >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA) {
            score += 10;
         }

         score = Math.max(score, biomeDarkRockFloor(biome, coverClass, heightAboveSea, slopeDiff, vegetationTransitionWeight));
         score -= Math.round(vegetationTransitionWeight * 30.0F);
         return Mth.clamp(score, 0, 100);
      }
   }

   private static int biomeDarkRockFloor(Holder<Biome> biome, int coverClass, int heightAboveSea, int slopeDiff, float vegetationTransitionWeight) {
      if (biome.is(Biomes.FROZEN_PEAKS)) {
         int progress = altitudeProgress(heightAboveSea, 135, 235);
         if (progress <= 0) {
            return 0;
         } else {
            int floor = 20 + progress * 76 / 100;
            if (slopeDiff < 3) {
               floor -= 8;
            }

            if (heightAboveSea >= 220 && slopeDiff >= 3) {
               floor = Math.max(floor, 84);
            }

            floor -= Math.round(vegetationTransitionWeight * 34.0F);
            return Mth.clamp(floor, 0, 100);
         }
      } else if (biome.is(Biomes.STONY_PEAKS)) {
         int progress = altitudeProgress(heightAboveSea, 155, 255);
         if (progress <= 0) {
            return 0;
         } else {
            int floorx = 16 + progress * 72 / 100;
            if (slopeDiff < 3) {
               floorx -= 8;
            }

            if (heightAboveSea >= 245 && slopeDiff >= 3) {
               floorx = Math.max(floorx, 78);
            }

            floorx -= Math.round(vegetationTransitionWeight * 30.0F);
            return Mth.clamp(floorx, 0, 100);
         }
      } else if (coverClass != 60 && coverClass != 70 && coverClass != 100 && coverClass != 20) {
         return 0;
      } else {
         int progress = altitudeProgress(heightAboveSea, 115, 225);
         if (progress <= 0) {
            return 0;
         } else {
            int floorxx = 12 + progress * 74 / 100;
            if (slopeDiff < 3) {
               floorxx -= 6;
            }

            if (heightAboveSea >= 205 && slopeDiff >= 2) {
               floorxx = Math.max(floorxx, 76);
            }

            floorxx -= Math.round(vegetationTransitionWeight * 26.0F);
            return Mth.clamp(floorxx, 0, 100);
         }
      }
   }

   private static int altitudeProgress(int heightAboveSea, int startHeight, int fullHeight) {
      if (heightAboveSea <= startHeight) {
         return 0;
      } else if (heightAboveSea >= fullHeight) {
         return 100;
      } else {
         int span = Math.max(1, fullHeight - startHeight);
         return (heightAboveSea - startHeight) * 100 / span;
      }
   }

   private double sampleLowFrequencyMask(int worldX, int worldZ, int cellSize, long salt) {
      int cellX = Math.floorDiv(worldX, cellSize);
      int cellZ = Math.floorDiv(worldZ, cellSize);
      double fracX = (double)Math.floorMod(worldX, cellSize) / cellSize;
      double fracZ = (double)Math.floorMod(worldZ, cellSize) / cellSize;
      double v00 = this.hashedCellNoise(cellX, cellZ, salt);
      double v10 = this.hashedCellNoise(cellX + 1, cellZ, salt);
      double v01 = this.hashedCellNoise(cellX, cellZ + 1, salt);
      double v11 = this.hashedCellNoise(cellX + 1, cellZ + 1, salt);
      double i0 = Mth.lerp(fracX, v00, v10);
      double i1 = Mth.lerp(fracX, v01, v11);
      return Mth.lerp(fracZ, i0, i1);
   }

   private double hashedCellNoise(int cellX, int cellZ, long salt) {
      long seed = this.worldSeed ^ salt ^ cellX * 341873128712L ^ cellZ * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      long bits = seed >>> 11 & 9007199254740991L;
      return bits / 9.007199E15F;
   }

   private static int sampleSlopeDiffCached(int[] heightGrid, int gridSize, int step, int centerIndex, int surface) {
      int east = heightGrid[centerIndex + step];
      int west = heightGrid[centerIndex - step];
      int north = heightGrid[centerIndex - step * gridSize];
      int south = heightGrid[centerIndex + step * gridSize];
      return Math.max(Math.max(Math.abs(east - surface), Math.abs(west - surface)), Math.max(Math.abs(north - surface), Math.abs(south - surface)));
   }

   private static long beginLodSurfaceProfiling(EarthChunkGenerator.LodSurfaceProfiler profiler) {
      return profiler == null ? 0L : System.nanoTime();
   }

   private static void endLodSurfaceProfiling(EarthChunkGenerator.LodSurfaceProfiler profiler, String phase, long startNanos) {
      if (profiler != null) {
         profiler.addPhase(phase, System.nanoTime() - startNanos);
      }
   }

   private static void profilerAddPhase(EarthChunkGenerator.LodSurfaceProfiler profiler, String phase, long nanos) {
      if (profiler != null) {
         profiler.addPhase(phase, nanos);
      }
   }

   private static long beginFullChunkProfiling() {
      return EarthChunkGenerator.FullChunkPerf.now();
   }

   private static long elapsedFullChunkProfilingSince(long startNanos) {
      return EarthChunkGenerator.FullChunkPerf.elapsedSince(startNanos);
   }

   private static void endFullChunkProfiling(EarthChunkGenerator.FullChunkPhase phase, long startNanos) {
      EarthChunkGenerator.FullChunkPerf.record(phase, elapsedFullChunkProfilingSince(startNanos));
   }

   private static void recordFullChunkProfiling(EarthChunkGenerator.FullChunkPhase phase, long nanos) {
      EarthChunkGenerator.FullChunkPerf.record(phase, nanos);
   }

   private static void recordFullChunkProfilingCount(EarthChunkGenerator.FullChunkPhase phase, int count) {
      EarthChunkGenerator.FullChunkPerf.recordCount(phase, count);
   }

   static void recordTerrainStreamingPrefetchQueueRejection() {
      EarthChunkGenerator.TerrainStreamingPerf.recordPrefetchQueueRejection();
   }

   private static int sampleConvexityCached(int[] heightGrid, int gridSize, int step, int centerIndex, int surface) {
      int east = heightGrid[centerIndex + step];
      int west = heightGrid[centerIndex - step];
      int north = heightGrid[centerIndex - step * gridSize];
      int south = heightGrid[centerIndex + step * gridSize];
      int neighborAverage = (east + west + north + south) / 4;
      return neighborAverage - surface;
   }

   private static boolean isSoilPalette(EarthChunkGenerator.SurfacePalette palette) {
      BlockState filler = palette.filler();
      return filler.is(BlockTags.DIRT) || filler.is(Blocks.MUD);
   }

   private boolean isRemaSnowTerrain(int worldZ) {
      return this.remaSnowEnabled && worldZ >= this.remaSnowBoundaryZ;
   }

   private EarthChunkGenerator.SurfacePalette selectBaseSurfacePalette(Holder<Biome> biome, int worldX, int worldZ, int surface, int coverClass) {
      if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
         return this.oceanFloorPalette(worldX, worldZ);
      } else if (biome.is(BiomeTags.IS_BADLANDS)) {
         return EarthChunkGenerator.SurfacePalette.badlands();
      } else if (biome.is(Biomes.DESERT)) {
         return EarthChunkGenerator.SurfacePalette.desert();
      } else if (biome.is(Biomes.MANGROVE_SWAMP)) {
         return EarthChunkGenerator.SurfacePalette.mangrove();
      } else if (biome.is(Biomes.SWAMP)) {
         return EarthChunkGenerator.SurfacePalette.swamp();
      } else if (biome.is(Biomes.STONY_PEAKS)) {
         return EarthChunkGenerator.SurfacePalette.stonyPeaks();
      } else if (biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
         return EarthChunkGenerator.SurfacePalette.gravelly();
      } else if (!biome.is(Biomes.SNOWY_PLAINS)
         && !biome.is(Biomes.SNOWY_TAIGA)
         && !biome.is(Biomes.SNOWY_SLOPES)
         && !biome.is(Biomes.GROVE)
         && !biome.is(Biomes.ICE_SPIKES)
         && !biome.is(Biomes.FROZEN_PEAKS)) {
         byte climateGroup = climateGroupForBiome(biome);
         EarthChunkGenerator.SurfacePalette coverPalette = this.coverDrivenSurfacePalette(coverClass, climateGroup, worldX, worldZ, surface);
         return coverClass != 50 && isPlainsOrMeadowBiome(biome) && isGravellyPalette(coverPalette) && surfaceVariant(worldX, worldZ, 211) < 50
            ? EarthChunkGenerator.SurfacePalette.defaultOverworld()
            : coverPalette;
      } else {
         return EarthChunkGenerator.SurfacePalette.snowy();
      }
   }

   private EarthChunkGenerator.SurfacePalette coverDrivenSurfacePalette(int coverClass, byte climateGroup, int worldX, int worldZ, int surface) {
      int roll = surfaceVariant(worldX, worldZ, 23);
      int heightAboveSea = surface - this.seaLevel;

      return switch (coverClass) {
         case 10 -> climateGroup != 4 && climateGroup != 5
            ? (
               climateGroup == 2
                  ? (roll < 70 ? EarthChunkGenerator.SurfacePalette.steppe() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  : (
                     climateGroup == 1
                        ? (roll < 35 ? EarthChunkGenerator.SurfacePalette.rooted() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                        : (roll < 20 ? EarthChunkGenerator.SurfacePalette.rooted() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  )
            )
            : (roll < 65 ? EarthChunkGenerator.SurfacePalette.podzolic() : EarthChunkGenerator.SurfacePalette.defaultOverworld());
         case 20 -> heightAboveSea >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 70
            ? EarthChunkGenerator.SurfacePalette.gravelly()
            : (
               climateGroup == 2
                  ? EarthChunkGenerator.SurfacePalette.steppe()
                  : (
                     climateGroup != 4 && climateGroup != 5
                        ? (roll < 40 ? EarthChunkGenerator.SurfacePalette.steppe() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                        : EarthChunkGenerator.SurfacePalette.podzolic()
                  )
            );
         case 30 -> heightAboveSea >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 35
            ? EarthChunkGenerator.SurfacePalette.gravelly()
            : (
               climateGroup == 2
                  ? (roll < 65 ? EarthChunkGenerator.SurfacePalette.steppe() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  : (
                     climateGroup != 4 && climateGroup != 5
                        ? EarthChunkGenerator.SurfacePalette.defaultOverworld()
                        : (roll < 40 ? EarthChunkGenerator.SurfacePalette.podzolic() : EarthChunkGenerator.SurfacePalette.defaultOverworld())
                  )
            );
         case 40 -> climateGroup == 2
            ? EarthChunkGenerator.SurfacePalette.steppe()
            : (roll < 65 ? EarthChunkGenerator.SurfacePalette.rooted() : EarthChunkGenerator.SurfacePalette.defaultOverworld());
         case 50 -> roll < 45 ? EarthChunkGenerator.SurfacePalette.gravelly() : EarthChunkGenerator.SurfacePalette.stonyPeaks();
         case 60 -> heightAboveSea >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA
            ? EarthChunkGenerator.SurfacePalette.stonyPeaks()
            : (
               climateGroup == 2
                  ? (roll < 55 ? EarthChunkGenerator.SurfacePalette.desert() : EarthChunkGenerator.SurfacePalette.badlands())
                  : (
                     climateGroup != 4 && climateGroup != 5
                        ? EarthChunkGenerator.SurfacePalette.gravelly()
                        : (roll < 70 ? EarthChunkGenerator.SurfacePalette.gravelly() : EarthChunkGenerator.SurfacePalette.stonyPeaks())
                  )
            );
         case 70 -> EarthChunkGenerator.SurfacePalette.snowy();
         case 90 -> roll < 40 ? EarthChunkGenerator.SurfacePalette.swamp() : EarthChunkGenerator.SurfacePalette.wetland();
         case 95 -> EarthChunkGenerator.SurfacePalette.mangrove();
         case 100 -> heightAboveSea >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 60
            ? EarthChunkGenerator.SurfacePalette.stonyPeaks()
            : (roll < 35 ? EarthChunkGenerator.SurfacePalette.mossy() : EarthChunkGenerator.SurfacePalette.defaultOverworld());
         default -> climateGroup != 4 && climateGroup != 5
            ? EarthChunkGenerator.SurfacePalette.defaultOverworld()
            : EarthChunkGenerator.SurfacePalette.podzolic();
      };
   }

   private static byte climateGroupForBiome(Holder<Biome> biome) {
      if (biome.is(Biomes.MANGROVE_SWAMP) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA)) {
         return 1;
      } else if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) {
         return 2;
      } else if (biome.is(Biomes.FROZEN_PEAKS) || biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_SLOPES) || biome.is(Biomes.ICE_SPIKES)) {
         return 5;
      } else if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.GROVE) || biome.is(Biomes.JAGGED_PEAKS) || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
         return 4;
      } else {
         return (byte)(!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_RIVER) ? 3 : 0);
      }
   }

   private static boolean isPlainsOrMeadowBiome(Holder<Biome> biome) {
      return biome.is(Biomes.PLAINS) || biome.is(Biomes.MEADOW);
   }

   private static boolean isGravellyPalette(EarthChunkGenerator.SurfacePalette palette) {
      return palette.top().is(Blocks.GRAVEL);
   }

   private static int surfaceVariant(int worldX, int worldZ, int salt) {
      long seed = seedFromCoords(worldX, salt, worldZ) ^ 2870177441569192885L;
      return Math.floorMod((int)(seed ^ seed >>> 32), 100);
   }

   private EarthChunkGenerator.SurfacePalette oceanFloorPalette(int worldX, int worldZ) {
      long seed = seedFromCoords(worldX, 0, worldZ) ^ 8006659316467387678L;
      int roll = seededRandomInt(seed, 100);
      if (roll < 10) {
         return EarthChunkGenerator.SurfacePalette.ocean(GRAVEL_STATE);
      } else {
         return roll < 15 ? EarthChunkGenerator.SurfacePalette.ocean(CLAY_STATE) : EarthChunkGenerator.SurfacePalette.ocean(SAND_STATE);
      }
   }

   private static BiomeGenerationSettings generationSettingsForBiome(Holder<Biome> biome, EarthGeneratorSettings settings) {
      boolean keepTrees = biome.is(Biomes.MANGROVE_SWAMP);
      int flags = geologyFlags(settings, keepTrees);
      EarthChunkGenerator.BiomeSettingsKey key = new EarthChunkGenerator.BiomeSettingsKey(biome, flags);
      return FILTERED_SETTINGS.computeIfAbsent(key, cached -> filterGenerationSettings(biome, settings, keepTrees));
   }

   private static BiomeGenerationSettings filterGenerationSettings(Holder<Biome> biome, EarthGeneratorSettings settings, boolean keepTrees) {
      BiomeGenerationSettings original = ((Biome)biome.value()).getGenerationSettings();
      PlainBuilder builder = new PlainBuilder();

      for (Holder<ConfiguredWorldCarver<?>> carver : original.getCarvers()) {
         Holder<ConfiguredWorldCarver<?>> safeCarver = Objects.requireNonNull(carver, "carver");
         if (shouldKeepCarver(safeCarver, settings)) {
            builder.addCarver(safeCarver);
         }
      }

      List<HolderSet<PlacedFeature>> features = original.features();

      for (int step = 0; step < features.size(); step++) {
         for (Holder<PlacedFeature> feature : features.get(step)) {
            Holder<PlacedFeature> safeFeature = Objects.requireNonNull(feature, "feature");
            if ((keepTrees || !isTreeFeature((PlacedFeature)safeFeature.value())) && shouldKeepFeature(safeFeature, settings)) {
               builder.addFeature(step, safeFeature);
            }
         }
      }

      return builder.build();
   }

   private static int geologyFlags(EarthGeneratorSettings settings, boolean keepTrees) {
      int flags = 0;
      if (cavesEnabled(settings)) {
         flags |= 1;
      }

      if (settings.oreDistribution()) {
         flags |= 2;
      }

      if (settings.lavaPools()) {
         flags |= 4;
      }

      if (settings.deepDark()) {
         flags |= 8;
      }

      if (settings.geodes()) {
         flags |= 16;
      }

      if (keepTrees) {
         flags |= 32;
      }

      return flags;
   }

   private static boolean shouldKeepCarver(Holder<ConfiguredWorldCarver<?>> carver, EarthGeneratorSettings settings) {
      return carver.unwrapKey().<Identifier>map(ResourceKey::identifier).map(id -> shouldKeepCarverId(id.getPath(), settings)).orElse(true);
   }

   private static boolean shouldKeepCarverId(String path, EarthGeneratorSettings settings) {
      return cavesEnabled(settings) || !path.equals("cave") && !path.equals("cave_extra_underground") && !path.equals("canyon");
   }

   private static boolean shouldKeepFeature(Holder<PlacedFeature> feature, EarthGeneratorSettings settings) {
      return feature.unwrapKey().<Identifier>map(ResourceKey::identifier).map(id -> shouldKeepFeatureId(id.getPath(), settings)).orElse(true);
   }

   private static boolean shouldKeepFeatureId(String path, EarthGeneratorSettings settings) {
      if (path.equals("freeze_top_layer") || path.equals("snow_and_freeze")) {
         return false;
      } else if (!settings.oreDistribution() && path.startsWith("ore_")) {
         return false;
      } else if (!settings.geodes() && path.contains("geode")) {
         return false;
      } else if (settings.deepDark() || !path.contains("sculk") && !path.contains("deep_dark")) {
         return cavesEnabled(settings) || !path.contains("dripstone") && !path.startsWith("spring_water")
            ? settings.lavaPools() || !path.startsWith("lake_lava") && !path.startsWith("spring_lava")
            : false;
      } else {
         return false;
      }
   }

   private boolean isStructureSetEnabled(Holder<StructureSet> structureSet) {
      for (StructureSelectionEntry entry : ((StructureSet)structureSet.value()).structures()) {
         if (!this.isStructureEnabled(entry.structure())) {
            return false;
         }
      }

      return true;
   }

   private boolean isStructureEnabled(Holder<Structure> structure) {
      return structure.unwrapKey().<Identifier>map(ResourceKey::identifier).map(id -> this.isStructureEnabled(id.getPath())).orElse(true);
   }

   private boolean isStructureEnabled(String path) {
      if (path.startsWith("village")) {
         return this.settings.addVillages();
      } else if (path.equals("stronghold")) {
         return this.settings.addStrongholds();
      } else if (path.startsWith("mineshaft")) {
         return this.settings.addMineshafts();
      } else if (path.equals("igloo")) {
         return this.settings.addIgloos();
      } else if (path.equals("ocean_monument") || path.equals("monument")) {
         return this.settings.addOceanMonuments();
      } else if (path.equals("woodland_mansion")) {
         return this.settings.addWoodlandMansions();
      } else if (path.equals("desert_pyramid") || path.equals("desert_temple")) {
         return this.settings.addDesertTemples();
      } else if (path.equals("jungle_pyramid") || path.equals("jungle_temple")) {
         return this.settings.addJungleTemples();
      } else if (path.equals("pillager_outpost")) {
         return this.settings.addPillagerOutposts();
      } else if (path.startsWith("ruined_portal")) {
         return this.settings.addRuinedPortals();
      } else if (path.startsWith("shipwreck")) {
         return this.settings.addShipwrecks();
      } else if (path.startsWith("ocean_ruin")) {
         return this.settings.addOceanRuins();
      } else if (path.equals("buried_treasure")) {
         return this.settings.addBuriedTreasure();
      } else if (path.equals("swamp_hut") || path.equals("witch_hut")) {
         return this.settings.addWitchHuts();
      } else if (path.equals("ancient_city")) {
         return this.settings.addAncientCities();
      } else if (path.equals("trial_chambers")) {
         return this.settings.addTrialChambers();
      } else {
         return path.startsWith("trail_ruins") ? this.settings.addTrailRuins() : true;
      }
   }

   private boolean isFrozenPeaksChunk(ChunkPos pos, RandomState randomState) {
      int centerX = pos.getMinBlockX() + 8;
      int centerZ = pos.getMinBlockZ() + 8;
      Holder<Biome> biome = this.biomeSource.getNoiseBiome(QuartPos.fromBlock(centerX), 0, QuartPos.fromBlock(centerZ), randomState.sampler());
      return biome.is(Biomes.FROZEN_PEAKS);
   }

   private void stripIglooStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
      Structure igloo = Objects.requireNonNull((Structure)registry.getValueOrThrow(BuiltinStructures.IGLOO), "iglooStructure");
      StructureStart start = chunk.getStartForStructure(igloo);
      if (start != null && start.isValid()) {
         chunk.setStartForStructure(igloo, StructureStart.INVALID_START);
         Map<Structure, LongSet> references = chunk.getAllReferences();
         if (!references.isEmpty()) {
            Map<Structure, LongSet> updated = new HashMap<>(references.size());

            for (Entry<Structure, LongSet> entry : references.entrySet()) {
               if (entry.getKey() != igloo) {
                  updated.put(entry.getKey(), entry.getValue());
               }
            }

            chunk.setAllReferences(updated);
         }
      }
   }

   private static List<ConfiguredFeature<?, ?>> treeFeaturesForBiome(Holder<Biome> biome) {
      return TREE_FEATURES.computeIfAbsent(biome, holder -> {
         List<ConfiguredFeature<?, ?>> result = new ArrayList<>();

         for (HolderSet<PlacedFeature> set : ((Biome)holder.value()).getGenerationSettings().features()) {
            for (Holder<PlacedFeature> feature : set) {
               PlacedFeature placed = (PlacedFeature)feature.value();
               if (isTreeFeature(placed)) {
                  result.add((ConfiguredFeature<?, ?>)placed.feature().value());
               }
            }
         }

         return List.copyOf(result);
      });
   }

   private void retargetStrongholdStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
      Structure stronghold = Objects.requireNonNull((Structure)registry.getValueOrThrow(BuiltinStructures.STRONGHOLD), "strongholdStructure");
      StructureStart start = chunk.getStartForStructure(stronghold);
      if (start != null && start.isValid()) {
         int chunkMinY = chunk.getMinY();
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
         BlockPos center = start.getBoundingBox().getCenter();
         int centerX = center.getX();
         int centerY = center.getY();
         int centerZ = center.getZ();
         WaterSurfaceResolver.WaterColumnData column = this.resolveAuxWaterColumn(center.getX(), center.getZ());
         int terrainSurface = column.terrainSurface();
         boolean inOceanColumn = column.hasWater() && column.isOcean() && column.waterSurface() > terrainSurface;
         boolean tooShallow = centerY > terrainSurface - 20;
         if (inOceanColumn || tooShallow) {
            int minTargetY = Math.max(this.minY + 20, chunkMinY + 20);
            int maxTargetY = Math.min(chunkMaxY - 20, terrainSurface - 20);
            if (maxTargetY > minTargetY) {
               long seed = seedFromCoords(centerX, 19, centerZ) ^ this.worldSeed ^ 8927292519556160640L;
               int targetDepth = 42 + seededRandomInt(seed, 17);
               int targetCenterY = Mth.clamp(terrainSurface - targetDepth, minTargetY, maxTargetY);
               int offsetY = targetCenterY - centerY;
               if (Math.abs(offsetY) < 3) {
                  if (centerY <= maxTargetY) {
                     return;
                  }

                  offsetY = maxTargetY - centerY;
               }

               List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

               for (StructurePiece piece : start.getPieces()) {
                  piece.move(0, offsetY, 0);
                  movedPieces.add(piece);
               }

               StructureStart moved = new StructureStart(stronghold, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
               chunk.setStartForStructure(stronghold, moved);
            }
         }
      }
   }

   private void retargetMineshaftStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
         Structure normalMineshaft = Objects.requireNonNull((Structure)registry.getValueOrThrow(BuiltinStructures.MINESHAFT), "normalMineshaft");
         int chunkMinY = chunk.getMinY();
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
            if (structure == normalMineshaft) {
               StructureStart start = entry.getValue();
               if (start != null && start.isValid()) {
                  BoundingBox box = start.getBoundingBox();
                  BlockPos center = box.getCenter();
                  int centerX = center.getX();
                  int centerY = center.getY();
                  int centerZ = center.getZ();
                  int surface = this.sampleSurfaceHeight(centerX, centerZ);
                  int minTargetY = Math.max(this.minY + 14, chunkMinY + 14);
                  int maxTargetY = Math.min(chunkMaxY - 14, surface - 14);
                  if (maxTargetY > minTargetY) {
                     long seed = seedFromCoords(centerX, 14, centerZ) ^ this.worldSeed ^ 2115183054018638465L;
                     int targetDepth = 30 + seededRandomInt(seed, 21);
                     int targetCenterY = Mth.clamp(surface - targetDepth, minTargetY, maxTargetY);
                     int minOffset = minTargetY - centerY;
                     int maxOffset = maxTargetY - centerY;
                     int offsetY = Mth.clamp(targetCenterY - centerY, minOffset, maxOffset);
                     if (Math.abs(offsetY) >= 3) {
                        List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

                        for (StructurePiece piece : start.getPieces()) {
                           piece.move(0, offsetY, 0);
                           movedPieces.add(piece);
                        }

                        StructureStart moved = new StructureStart(structure, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
                        chunk.setStartForStructure(structure, moved);
                     }
                  }
               }
            }
         }
      }
   }

   private void retargetTrialChamberStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Map<Structure, StructureStart> starts = chunk.getAllStarts();
      if (!starts.isEmpty()) {
         Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
         Structure trialChambers = Objects.requireNonNull((Structure)registry.getValueOrThrow(BuiltinStructures.TRIAL_CHAMBERS), "trialChambers");
         int chunkMinY = chunk.getMinY();
         int chunkMaxY = chunkMinY + chunk.getHeight() - 1;

         for (Entry<Structure, StructureStart> entry : starts.entrySet()) {
            Structure structure = Objects.requireNonNull(entry.getKey(), "structure");
            if (structure == trialChambers) {
               StructureStart start = entry.getValue();
               if (start != null && start.isValid()) {
                  BoundingBox box = start.getBoundingBox();
                  BlockPos center = box.getCenter();
                  int centerX = center.getX();
                  int centerY = center.getY();
                  int centerZ = center.getZ();
                  WaterSurfaceResolver.WaterColumnData column = this.resolveAuxWaterColumn(centerX, centerZ);
                  int terrainSurface = column.terrainSurface();
                  int requiredTopY = terrainSurface - 26;
                  if (box.maxY() > requiredTopY) {
                     int minAllowedOffset = chunkMinY + 26 - box.minY();
                     int maxAllowedOffset = chunkMaxY - 26 - box.maxY();
                     if (maxAllowedOffset >= minAllowedOffset) {
                        int minTargetY = Math.max(this.minY + 26, chunkMinY + 26);
                        int maxTargetY = Math.min(chunkMaxY - 26, terrainSurface - 26);
                        int offsetY;
                        if (maxTargetY > minTargetY) {
                           long seed = seedFromCoords(centerX, 17, centerZ) ^ this.worldSeed ^ 4235900233171027605L;
                           int targetDepth = 72 + seededRandomInt(seed, 25);
                           int targetCenterY = Mth.clamp(terrainSurface - targetDepth, minTargetY, maxTargetY);
                           offsetY = targetCenterY - centerY;
                        } else {
                           offsetY = requiredTopY - box.maxY();
                        }

                        int buryOffset = requiredTopY - box.maxY();
                        if (offsetY > buryOffset) {
                           offsetY = buryOffset;
                        }

                        offsetY = Mth.clamp(offsetY, minAllowedOffset, maxAllowedOffset);
                        if (offsetY != 0 && (Math.abs(offsetY) >= 4 || box.maxY() + offsetY > requiredTopY)) {
                           List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

                           for (StructurePiece piece : start.getPieces()) {
                              piece.move(0, offsetY, 0);
                              movedPieces.add(piece);
                           }

                           StructureStart moved = new StructureStart(structure, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
                           chunk.setStartForStructure(structure, moved);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void adjustOceanMonumentStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
      Structure monument = Objects.requireNonNull((Structure)registry.getValueOrThrow(BuiltinStructures.OCEAN_MONUMENT), "oceanMonument");
      StructureStart start = chunk.getStartForStructure(monument);
      if (start != null && start.isValid()) {
         BoundingBox box = start.getBoundingBox();
         if (!this.isOceanMonumentStartViable(box)) {
            chunk.setStartForStructure(monument, StructureStart.INVALID_START);
         } else {
            int targetTopY = this.seaLevel - OCEAN_MONUMENT_TOP_BELOW_SEA;
            int desiredMinY = targetTopY - box.getYSpan() + 1;
            int worldMaxY = this.minY + this.height - 1;
            int minAllowedOffset = this.minY + 1 - box.minY();
            int maxAllowedOffset = worldMaxY - box.maxY();
            int offsetY = Mth.clamp(desiredMinY - box.minY(), minAllowedOffset, maxAllowedOffset);
            if (box.maxY() + offsetY > targetTopY) {
               chunk.setStartForStructure(monument, StructureStart.INVALID_START);
            } else if (offsetY != 0) {
               List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());

               for (StructurePiece piece : start.getPieces()) {
                  piece.move(0, offsetY, 0);
                  movedPieces.add(piece);
               }

               StructureStart moved = new StructureStart(monument, start.getChunkPos(), start.getReferences(), new PiecesContainer(movedPieces));
               chunk.setStartForStructure(monument, moved);
            }
         }
      }
   }

   private boolean isOceanMonumentStartViable(BoundingBox box) {
      if (!this.settings.enableWater()) {
         return true;
      } else {
         int minX = box.minX() - OCEAN_MONUMENT_MARGIN;
         int maxX = box.maxX() + OCEAN_MONUMENT_MARGIN;
         int minZ = box.minZ() - OCEAN_MONUMENT_MARGIN;
         int maxZ = box.maxZ() + OCEAN_MONUMENT_MARGIN;
         int coreMinX = box.minX() + OCEAN_MONUMENT_CORE_INSET;
         int coreMaxX = box.maxX() - OCEAN_MONUMENT_CORE_INSET;
         int coreMinZ = box.minZ() + OCEAN_MONUMENT_CORE_INSET;
         int coreMaxZ = box.maxZ() - OCEAN_MONUMENT_CORE_INSET;
         int requiredCoreDepth = Math.max(OCEAN_MONUMENT_MIN_DEEP_OCEAN_DEPTH + 7, box.getYSpan() - 4);
         int totalSamples = 0;
         int oceanSamples = 0;
         int deepOceanSamples = 0;
         int coreSamples = 0;

         for (int sampleZ = minZ; ; sampleZ = Math.min(sampleZ + OCEAN_MONUMENT_SAMPLE_STEP, maxZ)) {
            for (int sampleX = minX; ; sampleX = Math.min(sampleX + OCEAN_MONUMENT_SAMPLE_STEP, maxX)) {
               WaterSurfaceResolver.WaterColumnData column = this.resolveOceanMonumentWaterColumn(sampleX, sampleZ);
               int waterDepth = oceanMonumentWaterDepth(column);
               boolean oceanColumn = waterDepth > 0;
               totalSamples++;
               if (oceanColumn) {
                  oceanSamples++;
               }

               if (waterDepth >= OCEAN_MONUMENT_MIN_DEEP_OCEAN_DEPTH) {
                  deepOceanSamples++;
               }

               if (sampleX >= coreMinX && sampleX <= coreMaxX && sampleZ >= coreMinZ && sampleZ <= coreMaxZ) {
                  coreSamples++;
                  if (!oceanColumn || waterDepth < requiredCoreDepth) {
                     return false;
                  }
               }

               if (sampleX == maxX) {
                  break;
               }
            }

            if (sampleZ == maxZ) {
               return coreSamples > 0
                  && (double)oceanSamples / (double)totalSamples >= OCEAN_MONUMENT_MIN_OCEAN_RATIO
                  && (double)deepOceanSamples / (double)totalSamples >= OCEAN_MONUMENT_MIN_DEEP_OCEAN_RATIO;
            }
         }
      }
   }

   private WaterSurfaceResolver.WaterColumnData resolveOceanMonumentWaterColumn(int worldX, int worldZ) {
      int coverClass = this.sampleCoverClass(worldX, worldZ);
      WaterSurfaceResolver.WaterColumnData column = this.resolveOsmWaterColumn(worldX, worldZ, coverClass, true);
      return this.normalizeResolvedWaterColumn(worldX, worldZ, coverClass, this.minY, this.minY + this.height - 1, column);
   }

   private static int oceanMonumentWaterDepth(WaterSurfaceResolver.WaterColumnData column) {
      return column.hasWater() && column.isOcean() && column.waterSurface() > column.terrainSurface() ? column.waterSurface() - column.terrainSurface() : 0;
   }

   private static boolean isTreeFeature(PlacedFeature feature) {
      return feature.getFeatures().anyMatch(configured -> {
         Feature<?> type = configured.value().feature();
         return type == Feature.TREE || type == Feature.FALLEN_TREE;
      });
   }

   private boolean shouldRetainSurfaceSnow(
      boolean useFastSurfacePalette, int surfaceCoverClass, int surface, int slopeDiff, int convexity, int worldX, int worldZ
   ) {
      if (surfaceCoverClass != 70) {
         return false;
      } else if (!useFastSurfacePalette || this.shouldUseDetailedMountainSurface(surfaceCoverClass, surface, slopeDiff, convexity)) {
         return this.shouldRetainMountainSnow(surfaceCoverClass, surface - this.seaLevel, slopeDiff, convexity, worldX, worldZ);
      } else {
         MountainSurfaceRules.ApproximateSurface approximate = MountainSurfaceRules.classifyApproximateSurface(
            surfaceCoverClass, surfaceCoverClass, surface - this.seaLevel, slopeDiff, convexity, this.isRemaSnowTerrain(worldZ)
         );
         return approximate.isSnow();
      }
   }

   private static void applySnowCover(ChunkAccess chunk, MutableBlockPos cursor, int worldX, int worldZ, int surface, int minY) {
      long seed = seedFromCoords(worldX, 0, worldZ) ^ 25214903917L;
      Random random = new Random(seed);
      int roll = random.nextInt(200);
      if (roll >= 33) {
         cursor.set(worldX, surface, worldZ);
         chunk.setBlockState(cursor, SNOW_BLOCK_STATE);
      } else {
         int depth = 1 + random.nextInt(5);

         for (int i = 0; i < depth; i++) {
            int y = surface - i;
            if (y < minY) {
               break;
            }

            cursor.set(worldX, y, worldZ);
            chunk.setBlockState(cursor, POWDER_SNOW_STATE);
         }
      }
   }

   private static void applySnowCover(
      EarthChunkGenerator.ChunkSectionWriter writer, int localX, int localZ, int worldX, int worldZ, int surface, int minY
   ) {
      long seed = seedFromCoords(worldX, 0, worldZ) ^ 25214903917L;
      Random random = new Random(seed);
      int roll = random.nextInt(200);
      if (roll >= 33) {
         writer.setBlock(localX, localZ, surface, SNOW_BLOCK_STATE);
      } else {
         int depth = 1 + random.nextInt(5);

         for (int i = 0; i < depth; i++) {
            int y = surface - i;
            if (y < minY) {
               break;
            }

            writer.setBlock(localX, localZ, y, POWDER_SNOW_STATE);
         }
      }
   }

   public void applyRealtimeSnowCover(WorldGenLevel level, ChunkAccess chunk) {
      if (TellusRealtimeState.isHistoricalSnowEnabled()
         || TellusRealtimeState.isWeatherEnabled() && TellusRealtimeState.precipitationMode() == TellusRealtimeState.PrecipitationMode.SNOW) {
         int updateFlags = level instanceof ServerLevel ? 2 : 260;
         ChunkPos pos = chunk.getPos();
         int chunkMinX = pos.getMinBlockX();
         int chunkMinZ = pos.getMinBlockZ();
         int minY = chunk.getMinY();
         int maxY = minY + chunk.getHeight();
         MutableBlockPos cursor = new MutableBlockPos();
         BlockState snowLayer = SNOW_LAYER_STATE;

         for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;

            for (int localZ = 0; localZ < 16; localZ++) {
               int worldZ = chunkMinZ + localZ;
               if (TellusRealtimeState.shouldApplySnow(worldX, worldZ)) {
                  int surface = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
                  if (surface >= minY && surface + 1 < maxY) {
                     int slopeDiff = sampleSlopeDiff(level, worldX, worldZ, surface);
                     if (slopeDiff < 4) {
                        cursor.set(worldX, surface, worldZ);
                        BlockState surfaceState = level.getBlockState(cursor);
                        if (surfaceState.getFluidState().isEmpty()) {
                           BlockPos above = cursor.above();
                           if (level.getBlockState(above).isAir() && snowLayer.canSurvive(level, above)) {
                              level.setBlock(above, snowLayer, updateFlags);
                              if (surfaceState.hasProperty(BlockStateProperties.SNOWY) && !(Boolean)surfaceState.getValue(BlockStateProperties.SNOWY)) {
                                 BlockState snowySurface = Objects.requireNonNull(
                                    (BlockState)surfaceState.setValue(BlockStateProperties.SNOWY, Boolean.TRUE), "snowySurface"
                                 );
                                 level.setBlock(cursor, snowySurface, updateFlags);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void spawnAxolotlsInLushPonds(WorldGenLevel level, ChunkAccess chunk) {
      ServerLevel serverLevel = level.getLevel();
      if (serverLevel.getServer().isSameThread()) {
         ChunkPos pos = chunk.getPos();
         long seed = seedFromCoords(pos.x(), 11, pos.z()) ^ this.worldSeed ^ 8943931030581793800L;
         RandomSource random = RandomSource.create(seed);
         if (!(random.nextFloat() > AXOLOTL_CHUNK_CHANCE)) {
            int chunkMinX = pos.getMinBlockX();
            int chunkMinZ = pos.getMinBlockZ();
            int minY = Math.max(this.minY + 4, chunk.getMinY() + 4);
            int spawned = 0;
            int attempts = 6 + random.nextInt(6);
            MutableBlockPos cursor = new MutableBlockPos();

            for (int attempt = 0; attempt < attempts; attempt++) {
               int worldX = chunkMinX + random.nextInt(CHUNK_SIDE);
               int worldZ = chunkMinZ + random.nextInt(CHUNK_SIDE);
               int surface = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
               int maxY = Math.min(surface - 4, this.seaLevel - 1);
               if (maxY > minY) {
                  for (int y = maxY; y >= minY; y--) {
                     cursor.set(worldX, y, worldZ);
                     BlockState water = level.getBlockState(cursor);
                     if (water.getFluidState().is(Fluids.WATER)) {
                        cursor.set(worldX, y - 1, worldZ);
                        if (level.getBlockState(cursor).is(Blocks.CLAY)) {
                           cursor.set(worldX, y + 1, worldZ);
                           if (level.getBlockState(cursor).isAir()) {
                              cursor.set(worldX, y, worldZ);
                              if (level.getBiome(cursor).is(Biomes.LUSH_CAVES) && !(random.nextFloat() > AXOLOTL_POND_CHANCE)) {
                                 Axolotl axolotl = (Axolotl)EntityTypes.AXOLOTL
                                    .create(serverLevel, entity -> {}, cursor.immutable(), EntitySpawnReason.CHUNK_GENERATION, false, false);
                                 if (axolotl == null) {
                                    return;
                                 }

                                 axolotl.setPersistenceRequired();
                                 axolotl.snapTo(worldX + 0.5, y + 0.1, worldZ + 0.5, random.nextFloat() * 360.0F, 0.0F);
                                 if (serverLevel.addFreshEntity(axolotl)) {
                                    spawned++;
                                 }
                              }
                              break;
                           }
                        }
                     }
                  }

                  if (spawned >= MAX_AXOLOTLS_PER_CHUNK) {
                     return;
                  }
               }
            }
         }
      }
   }

   private static int sampleSlopeDiff(WorldGenLevel level, int worldX, int worldZ, int surface) {
      int step = 4;
      int east = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX + step, worldZ);
      int west = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX - step, worldZ);
      int north = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ - step);
      int south = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ + step);
      return Math.max(Math.max(Math.abs(east - surface), Math.abs(west - surface)), Math.max(Math.abs(north - surface), Math.abs(south - surface)));
   }

   private record BiomeSettingsKey(Holder<Biome> biome, int flags) {
   }

   private record ColumnHeights(int terrainSurface, int waterSurface, boolean hasWater) {
   }

   private static final class FilteredStructureLookup implements HolderLookup<StructureSet> {
      private final HolderLookup<StructureSet> delegate;
      private final Predicate<Holder<StructureSet>> predicate;

      private FilteredStructureLookup(HolderLookup<StructureSet> delegate, Predicate<Holder<StructureSet>> predicate) {
         this.delegate = delegate;
         this.predicate = predicate;
      }


      public Stream<Reference<StructureSet>> listElements() {
         return Objects.requireNonNull(this.delegate.listElements().filter(this.predicate), "listElements");
      }


      public Stream<Named<StructureSet>> listTags() {
         return Objects.requireNonNull(this.delegate.listTags(), "listTags");
      }


      public Optional<Reference<StructureSet>> get( ResourceKey<StructureSet> key) {
         return Objects.requireNonNull(this.delegate.get(key), "getStructureSet");
      }


      public Optional<Named<StructureSet>> get( TagKey<StructureSet> tag) {
         return Objects.requireNonNull(this.delegate.get(tag), "getStructureSetTag");
      }
   }

   public record LodSurface( BlockState top,  BlockState filler) {
   }

   public interface LodSurfaceProfiler {
      void addPhase(String phase, long nanos);
   }

   public interface LodShorelineCache {
      boolean definitelyDry();

      String mode();

      WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int worldX, int worldZ);
   }

   public interface LodSharedTerrainCache {
      String mode();

      int sampleCoverClass(int worldX, int worldZ);

      int sampleVisualCoverClass(int worldX, int worldZ);

      EarthChunkGenerator.LodShorelineCache shorelineCache();

      EarthChunkGenerator.LodMountainTransitionCache mountainTransitionCache();
   }

   public interface LodMountainTransitionCache {
      String mode();

      float sampleVegetationWeight(int worldX, int worldZ, int heightAboveSea);
   }

   private final class DenseLodShorelineCache implements EarthChunkGenerator.LodShorelineCache {
      private final int minX;
      private final int maxX;
      private final int minZ;
      private final int maxZ;
      private final int width;
      private final WaterSurfaceResolver.WaterInfo[] waterInfos;
      private final boolean hasWater;
      private final double previewResolutionMeters;

      private DenseLodShorelineCache(
         int minX,
         int maxX,
         int minZ,
         int maxZ,
         int width,
         WaterSurfaceResolver.WaterInfo[] waterInfos,
         boolean hasWater,
         double previewResolutionMeters
      ) {
         this.minX = minX;
         this.maxX = maxX;
         this.minZ = minZ;
         this.maxZ = maxZ;
         this.width = width;
         this.waterInfos = waterInfos;
         this.hasWater = hasWater;
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public boolean definitelyDry() {
         return !this.hasWater;
      }

      @Override
      public String mode() {
         return this.hasWater ? "dense" : "dry";
      }

      @Override
      public WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int worldX, int worldZ) {
         if (worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            return this.waterInfos[localZ * this.width + localX];
         } else {
            return EarthChunkGenerator.this.resolveFastWaterInfoForShoreline(worldX, worldZ, this.previewResolutionMeters);
         }
      }
   }

   /**
    * 2026-10-05: cap on {@link SparseLodShorelineCache}'s per-point memo table.
    *
    * <p>{@code -Dtellus.dhShorelineCacheEntries} (min 1024, max 4,194,304). Chosen well above what a
    * normal LOD region queries, so the clear() fallback is only reached by an unusually large region.
    */
   private static final int MAX_SHORELINE_CACHE_ENTRIES =
      intProperty("tellus.dhShorelineCacheEntries", 262_144, 1024, 4_194_304);

   private final class SparseLodShorelineCache implements EarthChunkGenerator.LodShorelineCache {
      private final double previewResolutionMeters;
      /**
       * 2026-10-05: this map had no size bound and no expiry, so it recorded one entry per distinct
       * query point for the whole lifetime of the shoreline cache. Every entry pins a
       * {@code WaterInfo}, and for a LOD region larger than the dense-cache area limit that is an
       * unbounded number of live objects.
       *
       * <p>It is a pure cache (recomputable from {@code resolveFastWaterInfoForShoreline}), so when the
       * cap is hit we simply drop everything: worst case some points are resolved twice. That is a far
       * better failure mode than unbounded growth, and it keeps this class free of an extra LRU.
       * {@code -Dtellus.dhShorelineCacheEntries} tunes it.
       */
      private final Long2ObjectOpenHashMap<WaterSurfaceResolver.WaterInfo> waterInfoCache = new Long2ObjectOpenHashMap<>();

      private void rememberWaterInfo(long key, WaterSurfaceResolver.WaterInfo resolved) {
         if (this.waterInfoCache.size() >= MAX_SHORELINE_CACHE_ENTRIES) {
            this.waterInfoCache.clear();
         }

         this.waterInfoCache.put(key, resolved);
      }

      private SparseLodShorelineCache(double previewResolutionMeters) {
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public boolean definitelyDry() {
         return false;
      }

      @Override
      public String mode() {
         return "sparse";
      }

      @Override
      public WaterSurfaceResolver.WaterInfo resolveFastWaterInfo(int worldX, int worldZ) {
         long key = BlockPos.asLong(worldX, 0, worldZ);
         WaterSurfaceResolver.WaterInfo cached = this.waterInfoCache.get(key);
         if (cached != null) {
            return cached;
         } else {
            WaterSurfaceResolver.WaterInfo resolved = EarthChunkGenerator.this.resolveFastWaterInfoForShoreline(
               worldX, worldZ, this.previewResolutionMeters
            );
            this.rememberWaterInfo(key, resolved);
            return resolved;
         }
      }
   }

   private final class DenseLodMountainTransitionCache implements EarthChunkGenerator.LodMountainTransitionCache {
      private final int minX;
      private final int maxX;
      private final int minZ;
      private final int maxZ;
      private final int width;
      private final byte[] surfaceCoverClasses;
      private final double previewResolutionMeters;

      private DenseLodMountainTransitionCache(
         int minX, int maxX, int minZ, int maxZ, int width, byte[] surfaceCoverClasses, double previewResolutionMeters
      ) {
         this.minX = minX;
         this.maxX = maxX;
         this.minZ = minZ;
         this.maxZ = maxZ;
         this.width = width;
         this.surfaceCoverClasses = surfaceCoverClasses;
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public String mode() {
         return "dense";
      }

      @Override
      public float sampleVegetationWeight(int worldX, int worldZ, int heightAboveSea) {
         if (worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            int surfaceCoverClass = Byte.toUnsignedInt(this.surfaceCoverClasses[localZ * this.width + localX]);
            return MountainSurfaceRules.vegetationTransitionWeightForSurfaceCoverClass(surfaceCoverClass, heightAboveSea);
         } else {
            return EarthChunkGenerator.this.sampleMountainVegetationWeightUncached(worldX, worldZ, heightAboveSea, this.previewResolutionMeters);
         }
      }
   }

   private final class DenseLodSharedTerrainCache implements EarthChunkGenerator.LodSharedTerrainCache {
      private final int minX;
      private final int maxX;
      private final int minZ;
      private final int maxZ;
      private final int width;
      private final byte[] rawCoverClasses;
      private final byte[] visualCoverClasses;
      private final EarthChunkGenerator.DenseLodShorelineCache shorelineCache;
      private final EarthChunkGenerator.DenseLodMountainTransitionCache mountainTransitionCache;
      private final double previewResolutionMeters;

      private DenseLodSharedTerrainCache(
         int minX,
         int maxX,
         int minZ,
         int maxZ,
         int width,
         byte[] rawCoverClasses,
         byte[] visualCoverClasses,
         EarthChunkGenerator.DenseLodShorelineCache shorelineCache,
         EarthChunkGenerator.DenseLodMountainTransitionCache mountainTransitionCache,
         double previewResolutionMeters
      ) {
         this.minX = minX;
         this.maxX = maxX;
         this.minZ = minZ;
         this.maxZ = maxZ;
         this.width = width;
         this.rawCoverClasses = rawCoverClasses;
         this.visualCoverClasses = visualCoverClasses;
         this.shorelineCache = shorelineCache;
         this.mountainTransitionCache = mountainTransitionCache;
         this.previewResolutionMeters = previewResolutionMeters;
      }

      @Override
      public String mode() {
         return "dense";
      }

      @Override
      public int sampleCoverClass(int worldX, int worldZ) {
         if (this.contains(worldX, worldZ)) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            return Byte.toUnsignedInt(this.rawCoverClasses[localZ * this.width + localX]);
         } else {
            return EarthChunkGenerator.this.sampleCoverClass(worldX, worldZ, this.previewResolutionMeters);
         }
      }

      @Override
      public int sampleVisualCoverClass(int worldX, int worldZ) {
         if (this.contains(worldX, worldZ)) {
            int localX = worldX - this.minX;
            int localZ = worldZ - this.minZ;
            return Byte.toUnsignedInt(this.visualCoverClasses[localZ * this.width + localX]);
         } else {
            int rawCoverClass = EarthChunkGenerator.this.sampleCoverClass(worldX, worldZ, this.previewResolutionMeters);
            return EarthChunkGenerator.this.sampleVisualCoverClass(worldX, worldZ, rawCoverClass, this.previewResolutionMeters);
         }
      }

      @Override
      public EarthChunkGenerator.LodShorelineCache shorelineCache() {
         return this.shorelineCache;
      }

      @Override
      public EarthChunkGenerator.LodMountainTransitionCache mountainTransitionCache() {
         return this.mountainTransitionCache;
      }

      private boolean contains(int worldX, int worldZ) {
         return worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ;
      }
   }

   private static enum MountainRockStyle {
      DEEPSLATE,
      TUFF,
      ANDESITE;
   }

   private record MountainSurfaceContext(boolean retainSnow, int darkRockScore) {
   }

   private static final class OsmOverlayScratch {
      private final Long2ObjectOpenHashMap<EarthChunkGenerator.RoadColumnSample> edgeColumnCache = new Long2ObjectOpenHashMap<>();
      private byte[] resolvedClass = new byte[CHUNK_AREA];
      private byte[] resolvedMode = new byte[CHUNK_AREA];
      private int[] resolvedDeckY = new int[CHUNK_AREA];
      private boolean[] resolvedTunnelCarve = new boolean[CHUNK_AREA];
      private boolean[] blockedByHigherClass = new boolean[CHUNK_AREA];
      private boolean[] bridgeOverlayPresent = new boolean[CHUNK_AREA];
      private int[] bridgeOverlayDeckY = new int[CHUNK_AREA];
      private byte[] bridgeOverlayClass = new byte[CHUNK_AREA];
      private boolean[] bridgeSupportShaftPresent = new boolean[CHUNK_AREA];
      private int[] bridgeSupportShaftBottomY = new int[CHUNK_AREA];
      private int[] bridgeSupportShaftTopY = new int[CHUNK_AREA];
      private boolean[] bridgeSupportCapPresent = new boolean[CHUNK_AREA];
      private int[] bridgeSupportCapBottomY = new int[CHUNK_AREA];
      private int[] bridgeSupportCapTopY = new int[CHUNK_AREA];
      private boolean[] candidatePresent = new boolean[CHUNK_AREA];
      private int[] candidateDeckY = new int[CHUNK_AREA];
      private byte[] candidateMode = new byte[CHUNK_AREA];
      private boolean[] candidateTunnelCarve = new boolean[CHUNK_AREA];
      private boolean[] bridgeCandidatePresent = new boolean[CHUNK_AREA];
      private int[] bridgeCandidateDeckY = new int[CHUNK_AREA];
      private int[] placed = new int[CHUNK_AREA];
      private final byte[] chunkRoadClass = new byte[CHUNK_AREA];
      private final byte[] chunkRoadMode = new byte[CHUNK_AREA];
      private final int[] chunkRoadDeckY = new int[CHUNK_AREA];
      private final boolean[] chunkTunnelNeedsCarve = new boolean[CHUNK_AREA];
      private final boolean[] tunnelCarveMask = new boolean[CHUNK_AREA];
      private final int[] tunnelCarveDeckY = new int[CHUNK_AREA];
      private final int[] classWidths = new int[4];

      private void ensureRoadExtCapacity(int extArea) {
         if (this.resolvedClass.length < extArea) {
            this.resolvedClass = new byte[extArea];
            this.resolvedMode = new byte[extArea];
            this.resolvedDeckY = new int[extArea];
            this.resolvedTunnelCarve = new boolean[extArea];
            this.blockedByHigherClass = new boolean[extArea];
            this.bridgeOverlayPresent = new boolean[extArea];
            this.bridgeOverlayDeckY = new int[extArea];
            this.bridgeOverlayClass = new byte[extArea];
            this.bridgeSupportShaftPresent = new boolean[extArea];
            this.bridgeSupportShaftBottomY = new int[extArea];
            this.bridgeSupportShaftTopY = new int[extArea];
            this.bridgeSupportCapPresent = new boolean[extArea];
            this.bridgeSupportCapBottomY = new int[extArea];
            this.bridgeSupportCapTopY = new int[extArea];
            this.candidatePresent = new boolean[extArea];
            this.candidateDeckY = new int[extArea];
            this.candidateMode = new byte[extArea];
            this.candidateTunnelCarve = new boolean[extArea];
            this.bridgeCandidatePresent = new boolean[extArea];
            this.bridgeCandidateDeckY = new int[extArea];
            this.placed = new int[extArea];
         }
      }

      private void clearRoadExtState(int extArea) {
         Arrays.fill(this.resolvedClass, 0, extArea, (byte)0);
         Arrays.fill(this.resolvedMode, 0, extArea, (byte)0);
         Arrays.fill(this.resolvedDeckY, 0, extArea, 0);
         Arrays.fill(this.resolvedTunnelCarve, 0, extArea, false);
         Arrays.fill(this.blockedByHigherClass, 0, extArea, false);
         Arrays.fill(this.bridgeOverlayPresent, 0, extArea, false);
         Arrays.fill(this.bridgeOverlayDeckY, 0, extArea, 0);
         Arrays.fill(this.bridgeOverlayClass, 0, extArea, (byte)0);
         Arrays.fill(this.bridgeSupportShaftPresent, 0, extArea, false);
         Arrays.fill(this.bridgeSupportShaftBottomY, 0, extArea, 0);
         Arrays.fill(this.bridgeSupportShaftTopY, 0, extArea, 0);
         Arrays.fill(this.bridgeSupportCapPresent, 0, extArea, false);
         Arrays.fill(this.bridgeSupportCapBottomY, 0, extArea, 0);
         Arrays.fill(this.bridgeSupportCapTopY, 0, extArea, 0);
      }

      private void clearRoadCandidateState(int extArea) {
         Arrays.fill(this.candidatePresent, 0, extArea, false);
         Arrays.fill(this.candidateDeckY, 0, extArea, 0);
         Arrays.fill(this.candidateMode, 0, extArea, (byte)0);
         Arrays.fill(this.candidateTunnelCarve, 0, extArea, false);
         Arrays.fill(this.bridgeCandidatePresent, 0, extArea, false);
         Arrays.fill(this.bridgeCandidateDeckY, 0, extArea, 0);
      }
   }

   public record OsmRoadQueryResult(List<RoadFeature> features, boolean hadCacheMisses) {
      public OsmRoadQueryResult(List<RoadFeature> features, boolean hadCacheMisses) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMisses = hadCacheMisses;
      }
   }

   public record OsmBuildingQueryResult(List<OsmBuildingFeature> features, boolean hadCacheMisses) {
      public OsmBuildingQueryResult(List<OsmBuildingFeature> features, boolean hadCacheMisses) {
         features = features == null ? List.of() : List.copyOf(features);
         this.features = features;
         this.hadCacheMisses = hadCacheMisses;
      }
   }

   @FunctionalInterface
   private interface BuildingBiomeResolver {
      Holder<Biome> sample(OsmBuildingFeature feature, int baseY);
   }

   private record TerrainWarmupTicket(
      int heightGridMisses, int coverMisses, int visualCoverMisses, boolean waterFallback, boolean usedHeightFallback
   ) {
   }

   private record TerrainShellColumnFillResult(long surfaceCoverResolveNs, int coverMisses, int visualCoverMisses) {
   }

   private record TerrainShellBuildResult(
      ChunkPos pos,
      int minY,
      int maxY,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags,
      Holder<Biome>[] biomeCache,
      EarthChunkGenerator.TerrainWarmupTicket warmupTicket,
      long generationStamp
   ) {
      private static EarthChunkGenerator.TerrainShellBuildResult capture(
         ChunkPos pos,
         int minY,
         int maxY,
         int[] terrainSurfaces,
         int[] waterSurfaces,
         boolean[] waterFlags,
         int[] coverClasses,
         int[] visualCoverClasses,
         int[] surfaceCoverClasses,
         boolean[] oceanFlags,
         Holder<Biome>[] biomeCache,
         EarthChunkGenerator.TerrainWarmupTicket warmupTicket,
         long generationStamp
      ) {
         return new EarthChunkGenerator.TerrainShellBuildResult(
            pos,
            minY,
            maxY,
            terrainSurfaces.clone(),
            waterSurfaces.clone(),
            waterFlags.clone(),
            coverClasses.clone(),
            visualCoverClasses.clone(),
            surfaceCoverClasses.clone(),
            oceanFlags.clone(),
            Arrays.copyOf(biomeCache, biomeCache.length),
            warmupTicket,
            generationStamp
         );
      }

      private long chunkKey() {
         return ChunkPos.pack(this.pos.x(), this.pos.z());
      }
   }

   private record PreparedTerrainRefinement(
      EarthChunkGenerator.TerrainShellBuildResult shell,
      EarthChunkGenerator.ChunkGenerationContext context,
      EarthChunkGenerator.PreparedChunkDetail delayedDetail,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      int[] visualCoverClasses,
      int[] surfaceCoverClasses,
      boolean[] oceanFlags,
      int[] slopeDiffs,
      int[] convexities
   ) {
      private long generationStamp() {
         return this.shell.generationStamp();
      }
   }

   private record ChunkGenerationContext(
      ChunkPos pos,
      int minY,
      int maxY,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] coverClasses,
      Holder<Biome>[] biomeCache,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      Holder<Biome> fallbackBiome,
      long generationStamp
   ) {
      private static EarthChunkGenerator.ChunkGenerationContext capture(
         ChunkPos pos,
         int minY,
         int maxY,
         int[] terrainSurfaces,
         int[] waterSurfaces,
         boolean[] waterFlags,
         int[] coverClasses,
         Holder<Biome>[] biomeCache,
         EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
         long generationStamp
      ) {
         Holder<Biome>[] biomeCopy = Arrays.copyOf(biomeCache, biomeCache.length);
         Holder<Biome> fallbackBiome = null;

         for (Holder<Biome> biome : biomeCopy) {
            if (biome != null) {
               fallbackBiome = biome;
               break;
            }
         }

         if (fallbackBiome == null) {
            throw new IllegalStateException("Missing biome sampler while capturing chunk generation context");
         } else {
            return new EarthChunkGenerator.ChunkGenerationContext(
               pos,
               minY,
               maxY,
               terrainSurfaces.clone(),
               waterSurfaces.clone(),
               waterFlags.clone(),
               coverClasses.clone(),
               biomeCopy,
               preparedBuildings,
               fallbackBiome,
               generationStamp
            );
         }
      }

      private long chunkKey() {
         return ChunkPos.pack(this.pos.x(), this.pos.z());
      }

      private Holder<Biome> sampleBiome(int worldX, int worldZ, int sampleY) {
         int localX = Mth.clamp(worldX - this.pos.getMinBlockX(), 0, CHUNK_MASK);
         int localZ = Mth.clamp(worldZ - this.pos.getMinBlockZ(), 0, CHUNK_MASK);
         Holder<Biome> biome = this.biomeCache[chunkIndex(localX, localZ)];
         return biome != null ? biome : this.fallbackBiome;
      }

      private int[] copyTerrainSurfaces() {
         return this.terrainSurfaces.clone();
      }

      private int[] copyWaterSurfaces() {
         return this.waterSurfaces.clone();
      }

      private boolean[] copyWaterFlags() {
         return this.waterFlags.clone();
      }
   }

   private record ChunkDecorationContext(
      ChunkPos pos, int[] terrainSurfaces, boolean[] waterFlags, int[] coverClasses, Holder<Biome>[] biomeCache, Holder<Biome> fallbackBiome
   ) {
      private static EarthChunkGenerator.ChunkDecorationContext capture(
         ChunkPos pos, int[] terrainSurfaces, boolean[] waterFlags, int[] coverClasses, Holder<Biome>[] biomeCache
      ) {
         Holder<Biome> fallbackBiome = null;

         for (Holder<Biome> biome : biomeCache) {
            if (biome != null) {
               fallbackBiome = biome;
               break;
            }
         }

         if (fallbackBiome == null) {
            throw new IllegalStateException("Missing biome sampler while capturing chunk decoration context");
         } else {
            return new EarthChunkGenerator.ChunkDecorationContext(pos, terrainSurfaces, waterFlags, coverClasses, biomeCache, fallbackBiome);
         }
      }

      private int terrainSurface(int localX, int localZ) {
         return this.terrainSurfaces[chunkIndex(localX, localZ)];
      }

      private int coverClass(int localX, int localZ) {
         return this.coverClasses[chunkIndex(localX, localZ)];
      }

      private Holder<Biome> biome(int localX, int localZ) {
         Holder<Biome> biome = this.biomeCache[chunkIndex(localX, localZ)];
         return biome != null ? biome : this.fallbackBiome;
      }

      private boolean canResolveNearWaterWithinChunk(int localX, int localZ, int radius) {
         return localX - radius >= 0 && localX + radius <= CHUNK_MASK && localZ - radius >= 0 && localZ + radius <= CHUNK_MASK;
      }

      private boolean isNearWaterWithinChunk(int localX, int localZ, int radius) {
         for (int z = localZ - radius; z <= localZ + radius; z++) {
            for (int x = localX - radius; x <= localX + radius; x++) {
               if (this.waterFlags[chunkIndex(x, z)]) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private record PreparedChunkDetail(
      EarthChunkGenerator.ChunkGenerationContext context,
      EarthChunkGenerator.PreparedChunkBuildings preparedBuildings,
      boolean placeBuildings,
      EarthChunkGenerator.OsmRoadQueryResult roadQuery,
      List<EarthChunkGenerator.PreparedTreePlacement> treePlacements
   ) {
   }

   private record PreparedTreePlacement(int worldX, int worldZ, int expectedSurface, Holder<Biome> biome, long seed) {
   }

   @FunctionalInterface
   interface ClimateSampler {
      String sample(int blockX, int blockZ, double worldScale);
   }

   static final class HeightGridBuildResult {
      private final int[] heightGrid;
      private final int cacheHits;
      private final int cacheMisses;

      HeightGridBuildResult(int[] heightGrid, int cacheHits, int cacheMisses) {
         this.heightGrid = heightGrid;
         this.cacheHits = cacheHits;
         this.cacheMisses = cacheMisses;
      }

      int[] heightGrid() {
         return this.heightGrid;
      }

      int cacheHits() {
         return this.cacheHits;
      }

      int cacheMisses() {
         return this.cacheMisses;
      }
   }

   private record TerrainShellHeightGridResult(int[] heightGrid, int cacheHits, int missingCount, boolean usedFallback) {
      private static EarthChunkGenerator.TerrainShellHeightGridResult exact(EarthChunkGenerator.HeightGridBuildResult result) {
         return new EarthChunkGenerator.TerrainShellHeightGridResult(result.heightGrid(), result.cacheHits(), result.cacheMisses(), false);
      }

      private int cacheMisses() {
         return this.missingCount;
      }
   }

   static final class HeightGridCache {
      private final int maxEntries;
      private final LinkedHashMap<Long, EarthChunkGenerator.HeightGridCacheEntry> entries;

      HeightGridCache(int maxEntries) {
         this.maxEntries = maxEntries;
         this.entries = maxEntries <= 0 ? null : new LinkedHashMap<>(maxEntries, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Entry<Long, EarthChunkGenerator.HeightGridCacheEntry> eldest) {
               return size() > HeightGridCache.this.maxEntries;
            }
         };
      }

      synchronized int copyOverlaps(ChunkPos pos, int step, int gridSize, int[] target, boolean allowApproximate) {
         if (this.entries == null || !isReusableLayout(step, gridSize)) {
            return 0;
         }

         int copied = 0;
         for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx == 0 && dz == 0) {
                  continue;
               }

               EarthChunkGenerator.HeightGridCacheEntry entry = this.entries.get(ChunkPos.pack(pos.x() + dx, pos.z() + dz));
               if (entry != null && (allowApproximate || !entry.approximate())) {
                  copied += entry.copyOverlapTo(pos, step, gridSize, target);
               }
            }
         }

         return copied;
      }

      synchronized void put(ChunkPos pos, int step, int gridSize, int[] heightGrid, boolean approximate) {
         if (this.entries != null && isReusableLayout(step, gridSize)) {
            this.entries.put(
               ChunkPos.pack(pos.x(), pos.z()), new EarthChunkGenerator.HeightGridCacheEntry(pos, step, gridSize, heightGrid.clone(), approximate)
            );
         }
      }

      private static boolean isReusableLayout(int step, int gridSize) {
         return step == 4 && gridSize == 24;
      }
   }

   static final class HeightGridCacheEntry {
      private final ChunkPos pos;
      private final int step;
      private final int gridSize;
      private final int[] heightGrid;
      private final boolean approximate;

      HeightGridCacheEntry(ChunkPos pos, int step, int gridSize, int[] heightGrid, boolean approximate) {
         this.pos = pos;
         this.step = step;
         this.gridSize = gridSize;
         this.heightGrid = heightGrid;
         this.approximate = approximate;
      }

      boolean approximate() {
         return this.approximate;
      }

      int copyOverlapTo(ChunkPos targetPos, int step, int gridSize, int[] target) {
         if (this.step != step || this.gridSize != gridSize) {
            return 0;
         }

         int sourceMinX = this.pos.getMinBlockX() - step;
         int sourceMinZ = this.pos.getMinBlockZ() - step;
         int targetMinX = targetPos.getMinBlockX() - step;
         int targetMinZ = targetPos.getMinBlockZ() - step;
         int copied = 0;

         for (int dz = 0; dz < gridSize; dz++) {
            int sourceZ = targetMinZ + dz - sourceMinZ;
            if (sourceZ < 0 || sourceZ >= gridSize) {
               continue;
            }

            int targetRow = dz * gridSize;
            int sourceRow = sourceZ * gridSize;
            for (int dx = 0; dx < gridSize; dx++) {
               int sourceX = targetMinX + dx - sourceMinX;
               if (sourceX < 0 || sourceX >= gridSize) {
                  continue;
               }

               int index = targetRow + dx;
               if (target[index] == Integer.MIN_VALUE) {
                  target[index] = this.heightGrid[sourceRow + sourceX];
                  copied++;
               }
            }
         }

         return copied;
      }
   }

   static final class ChunkBiomeClimateCache {
      private static final int CELL_SIZE = 4;
      private static final int GRID_SIZE = CHUNK_SIDE / CELL_SIZE;
      private final int chunkMinX;
      private final int chunkMinZ;
      private final double worldScale;
      private final String[] codes = new String[GRID_SIZE * GRID_SIZE];
      private final boolean[] resolved = new boolean[this.codes.length];
      private int hits;
      private int misses;

      ChunkBiomeClimateCache(ChunkPos pos, double worldScale) {
         this.chunkMinX = pos.getMinBlockX();
         this.chunkMinZ = pos.getMinBlockZ();
         this.worldScale = worldScale;
      }

      String resolve(int worldX, int worldZ, EarthChunkGenerator.ClimateSampler sampler) {
         int localX = Mth.clamp(worldX - this.chunkMinX, 0, CHUNK_MASK);
         int localZ = Mth.clamp(worldZ - this.chunkMinZ, 0, CHUNK_MASK);
         int index = cellIndex(localX, localZ);
         if (this.resolved[index]) {
            this.hits++;
            return this.codes[index];
         }

         int cellX = localX >> 2;
         int cellZ = localZ >> 2;
         int sampleX = this.chunkMinX + (cellX << 2) + 2;
         int sampleZ = this.chunkMinZ + (cellZ << 2) + 2;
         this.codes[index] = sampler.sample(sampleX, sampleZ, this.worldScale);
         this.resolved[index] = true;
         this.misses++;
         return this.codes[index];
      }

      static int cellIndex(int localX, int localZ) {
         return (localZ >> 2) * GRID_SIZE + (localX >> 2);
      }

      int hitCount() {
         return this.hits;
      }

      int missCount() {
         return this.misses;
      }
   }

   static final class ChunkSectionWriter {
      private final LevelChunkSection[] sections;
      private final int chunkMinY;
      private final boolean[] dirtySections;
      private final boolean[] acquiredSections;
      private final PalettedContainer<BlockState>[] stateContainers;
      private int dirtySectionCount;
      private boolean closed;

      @SuppressWarnings("unchecked")
      ChunkSectionWriter(LevelChunkSection[] sections, int chunkMinY) {
         this.sections = sections;
         this.chunkMinY = chunkMinY;
         this.dirtySections = new boolean[sections.length];
         this.acquiredSections = new boolean[sections.length];
         this.stateContainers = (PalettedContainer<BlockState>[])new PalettedContainer[sections.length];
      }

      void fillSectionConstant(int sectionIndex, BlockState state, EarthChunkGenerator.SolidSectionFillProfiler profiler) {
         // 2026-09-23 performance fix: fill a whole section with [one construction] instead of 4096 per-cell writes
         //
         // Measured (profiler from `19-47-14.log`):
         // solidSections=2074.53ms/140   sectionWrite=2022.88ms/140   <- 97% of the time is here
         // Root cause: the original implementation called `getAndSetUnchecked` on all 4096 cells of the section one by one.
         // While "the whole section is one block" (solid strata) could have used
         // `new PalettedContainer<>(state, strategy)` <- constructing it with the default fills the whole section
         // in one go (4096 operations -> 1).
         //
         // Why the magnitude is so large: `solidMaxIndex` is decided by the [lowest surface] --
         // spawning at sea level -> about 8 sections -> 37 000 operations, hardly noticeable
         // spawning on a plateau / high mountain -> **about 557 sections** -> **2 280 000 operations** -> measured 2 s per chunk
         //
         // Safety:
         // (1) the new path is taken only when the factory is obtainable (sparse mode enabled) and the section **has not been acquired**
         // -- an acquired section already holds written content and must not be reset wholesale (that would lose data)
         // (2) the new path **also updates** the `stateContainers[index]` cache
         // -- otherwise later `fillColumnConstant` / `setBlock` calls would write into the discarded old object
         // (3) any exception falls back to the original per-cell path (behaviour bit-for-bit identical to before the change)
         // How `blockStatesStrategy()` was actually found (this lesson was expensive):
         // (1) I first guessed `blockStateStrategy()` from its meaning -> the compiler reported "cannot find symbol"
         // (2) I switched to the **intermediary** name `comp_4785()` read with javap -> **also not found**
         // (javap prints obfuscated names, source code needs Yarn names, the two are not interchangeable)
         // (3) finally I looked it up in the **Yarn mappings** in the Gradle cache:
         //        `~/.gradle/caches/fabric-loom/1.21.9/*/mappings.tiny`
         // line: `m ()Lemi; c comp_4785 blockStatesStrategy`
         // => the real name is **`blockStatesStrategy()`** (`States` is plural, I was missing an s)
         // => lesson (folded into the hard rules): **to get a Yarn name, read `mappings.tiny`**,
         // do not guess, and do not write source code with the obfuscated names from javap.
         PalettedContainerFactory factory = SparseSections.factoryOrNull();
         if (factory != null && sectionIndex >= 0 && sectionIndex < this.sections.length
               && !this.acquiredSections[sectionIndex] && this.sections[sectionIndex] != null) {
            long fastStartNs = beginFullChunkProfiling();
            try {
               LevelChunkSection fresh = new LevelChunkSection(
                  new PalettedContainer<>(state, factory.blockStatesStrategy()),
                  this.sections[sectionIndex].getBiomes()
               );
               fresh.recalcBlockCounts();
               this.sections[sectionIndex] = fresh;
               this.stateContainers[sectionIndex] = fresh.getStates();   // keep the cache in sync
               this.acquiredSections[sectionIndex] = true;
               profiler.sectionWriteNs += elapsedFullChunkProfilingSince(fastStartNs);
               this.markDirty(sectionIndex);
               return;
            } catch (RuntimeException | LinkageError fastPathError) {
               // Fallback: go back to per-cell writes (no behaviour change)
               Tellus.LOGGER.debug("Tellus fast section fill unavailable, falling back to generic implementation", fastPathError);
            }
         }

         PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, profiler);
         long sectionWriteStartNs = beginFullChunkProfiling();

         for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
               for (int x = 0; x < 16; x++) {
                  states.getAndSetUnchecked(x, y, z, state);
               }
            }
         }

         profiler.sectionWriteNs += elapsedFullChunkProfilingSince(sectionWriteStartNs);
         this.markDirty(sectionIndex);
      }

      void fillStoneColumnSpan(int localX, int localZ, int startY, int endY, int deepslateStart, BlockState stone, BlockState deepslate) {
         if (endY < startY) {
            return;
         }

         int currentY = startY;
         while (currentY <= endY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localStartY = Math.max(0, currentY - sectionBottomY);
            int localEndY = Math.min(15, endY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localStartY; localY <= localEndY; localY++) {
               int worldY = sectionBottomY + localY;
               states.getAndSetUnchecked(localX, localY, localZ, worldY < deepslateStart ? deepslate : stone);
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY + 16;
         }
      }

      void fillColumnConstant(int localX, int localZ, int startY, int endY, BlockState state) {
         if (endY < startY) {
            return;
         }

         int currentY = startY;
         while (currentY <= endY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localStartY = Math.max(0, currentY - sectionBottomY);
            int localEndY = Math.min(15, endY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localStartY; localY <= localEndY; localY++) {
               states.getAndSetUnchecked(localX, localY, localZ, state);
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY + 16;
         }
      }

      void fillSurfaceColumn(int localX, int localZ, int surfaceY, int bottomY, BlockState top, BlockState filler) {
         if (surfaceY < bottomY) {
            return;
         }

         int currentY = surfaceY;
         while (currentY >= bottomY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localTopY = Math.min(15, currentY - sectionBottomY);
            int localBottomY = Math.max(0, bottomY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localTopY; localY >= localBottomY; localY--) {
               states.getAndSetUnchecked(localX, localY, localZ, sectionBottomY + localY == surfaceY ? top : filler);
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY - 1;
         }
      }

      void fillBadlandsBands(int localX, int localZ, int surfaceY, int bottomY, BlockState top, int offset) {
         if (surfaceY < bottomY) {
            return;
         }

         int currentY = surfaceY;
         while (currentY >= bottomY) {
            int sectionIndex = this.sectionIndexForY(currentY);
            if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
               break;
            }

            int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
            int localTopY = Math.min(15, currentY - sectionBottomY);
            int localBottomY = Math.max(0, bottomY - sectionBottomY);
            PalettedContainer<BlockState> states = this.acquireStates(sectionIndex, null);
            for (int localY = localTopY; localY >= localBottomY; localY--) {
               int worldY = sectionBottomY + localY;
               states.getAndSetUnchecked(localX, localY, localZ, worldY == surfaceY ? top : badlandsBand(worldY, offset));
            }

            this.markDirty(sectionIndex);
            currentY = sectionBottomY - 1;
         }
      }

      void setBlock(int localX, int localZ, int worldY, BlockState state) {
         int sectionIndex = this.sectionIndexForY(worldY);
         if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
            return;
         }

         int sectionBottomY = this.chunkMinY + (sectionIndex << 4);
         this.acquireStates(sectionIndex, null).getAndSetUnchecked(localX, worldY - sectionBottomY, localZ, state);
         this.markDirty(sectionIndex);
      }

      FlushResult finish() {
         if (this.closed) {
            return new FlushResult(0L, this.dirtySectionCount);
         }

         this.releaseSections();
         long recalcStartNs = beginFullChunkProfiling();
         for (int i = 0; i < this.sections.length; i++) {
            if (this.dirtySections[i]) {
               Objects.requireNonNull(this.sections[i], "section").recalcBlockCounts();
            }
         }

         this.closed = true;
         return new FlushResult(elapsedFullChunkProfilingSince(recalcStartNs), this.dirtySectionCount);
      }

      void close() {
         if (!this.closed) {
            this.releaseSections();
            this.closed = true;
         }
      }

      private void releaseSections() {
         for (int i = 0; i < this.sections.length; i++) {
            if (this.acquiredSections[i]) {
               Objects.requireNonNull(this.sections[i], "section").release();
               this.acquiredSections[i] = false;
            }
         }
      }

      private int sectionIndexForY(int worldY) {
         return (worldY - this.chunkMinY) >> 4;
      }

      private PalettedContainer<BlockState> acquireStates(int sectionIndex, EarthChunkGenerator.SolidSectionFillProfiler profiler) {
         if (sectionIndex < 0 || sectionIndex >= this.sections.length) {
            throw new IndexOutOfBoundsException("sectionIndex=" + sectionIndex);
         }

         if (!this.acquiredSections[sectionIndex]) {
            long sectionAccessStartNs = beginFullChunkProfiling();
            // Materialize first: in sparse mode the empty slot is a **globally shared instance**,
            // and the code below uses `getStates().getAndSetUnchecked(...)` to **write the palette directly**
            // -- that path bypasses `setBlockState`, so no guard can stop it (found by the first round of the self-audit)
            SparseSections.materializeSection(this.sections, sectionIndex);
            LevelChunkSection section = Objects.requireNonNull(this.sections[sectionIndex], "section");
            section.acquire();
            this.stateContainers[sectionIndex] = section.getStates();
            this.acquiredSections[sectionIndex] = true;
            if (profiler != null) {
               profiler.sectionAccessNs += elapsedFullChunkProfilingSince(sectionAccessStartNs);
            }
         }

         return Objects.requireNonNull(this.stateContainers[sectionIndex], "states");
      }

      private void markDirty(int sectionIndex) {
         if (!this.dirtySections[sectionIndex]) {
            this.dirtySections[sectionIndex] = true;
            this.dirtySectionCount++;
         }
      }

      static final class FlushResult {
         private final long recalcNs;
         private final int dirtySectionCount;

         FlushResult(long recalcNs, int dirtySectionCount) {
            this.recalcNs = recalcNs;
            this.dirtySectionCount = dirtySectionCount;
         }

         long recalcNs() {
            return this.recalcNs;
         }

         int dirtySectionCount() {
            return this.dirtySectionCount;
         }
      }
   }

   private static final class TerrainRefinementManager {
      private final Map<Long, EarthChunkGenerator.TerrainRefinementJob> jobs = new ConcurrentHashMap<>();
      private final ConcurrentLinkedQueue<Long> readyQueue = new ConcurrentLinkedQueue<>();

      private void schedule(EarthChunkGenerator generator, EarthChunkGenerator.TerrainShellBuildResult shell) {
         long chunkKey = shell.chunkKey();
         EarthChunkGenerator.TerrainRefinementJob job = new EarthChunkGenerator.TerrainRefinementJob(EarthChunkGenerator.TerrainRefinementJobState.QUEUED);
         EarthChunkGenerator.TerrainRefinementJob previous = this.jobs.put(chunkKey, job);
         if (previous != null) {
            previous.state = EarthChunkGenerator.TerrainRefinementJobState.STALE;
            EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
         }

         EarthChunkGenerator.TerrainStreamingPerf.recordRefinementQueued();
         EarthChunkGenerator.TerrainStreamingPerf.recordShellBuild(shell.warmupTicket());
         TellusWorldgenSources.submitTerrainDetailTask(() -> {
            AtomicBoolean shouldFetch = new AtomicBoolean();
            AtomicBoolean staleBeforeFetch = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.TerrainRefinementJobState.STALE;
                  staleBeforeFetch.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.TerrainRefinementJobState.FETCHING;
               shouldFetch.set(true);
               return current;
            });
            if (!shouldFetch.get()) {
               if (staleBeforeFetch.get()) {
                  EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
               }
               return;
            }

            try {
               job.refinement = generator.buildPreparedTerrainRefinement(shell);
            } catch (RuntimeException error) {
               job.state = EarthChunkGenerator.TerrainRefinementJobState.FAILED;
               Tellus.LOGGER.debug("Failed to build terrain refinement for {}", shell.pos(), error);
               this.jobs.remove(chunkKey, job);
               return;
            }

            AtomicBoolean staleAfterBuild = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.TerrainRefinementJobState.STALE;
                  staleAfterBuild.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.TerrainRefinementJobState.READY;
               this.readyQueue.offer(chunkKey);
               return current;
            });
            if (staleAfterBuild.get()) {
               EarthChunkGenerator.TerrainStreamingPerf.recordRefinementStaleDrop();
            }
         });
      }

      private boolean hasPending(ChunkPos pos) {
         EarthChunkGenerator.TerrainRefinementJob job = this.jobs.get(ChunkPos.pack(pos.x(), pos.z()));
         return job != null && job.state != EarthChunkGenerator.TerrainRefinementJobState.APPLIED && job.state != EarthChunkGenerator.TerrainRefinementJobState.FAILED;
      }

      /**
       * Reaps jobs that have been queued longer than the stale threshold (returns how many were removed).
       *
       * <p>It uses {@code ConcurrentHashMap.remove(key, value)} for an **atomic** removal --
       * even if the async job happens to complete and swap the value at that exact moment, only one side can win,
       * so a job that is in use is never deleted by mistake. Iterating is safe.
       */
      private int reapStale(long now) {
         int removed = 0;
         for (Entry<Long, EarthChunkGenerator.TerrainRefinementJob> entry : this.jobs.entrySet()) {
            EarthChunkGenerator.TerrainRefinementJob job = entry.getValue();
            if (job != null
                  && now - job.createdNs >= EarthChunkGenerator.PREPARED_CHUNK_STATE_STALE_NANOS
                  && this.jobs.remove(entry.getKey(), job)) {
               removed++;
            }
         }
         return removed;
      }

      private EarthChunkGenerator.PreparedTerrainRefinement claimReady(ChunkPos pos) {
         long chunkKey = ChunkPos.pack(pos.x(), pos.z());
         EarthChunkGenerator.TerrainRefinementJob job = this.jobs.get(chunkKey);
         if (job == null || job.state != EarthChunkGenerator.TerrainRefinementJobState.READY) {
            return null;
         } else if (!this.jobs.remove(chunkKey, job)) {
            return null;
         } else {
            job.state = EarthChunkGenerator.TerrainRefinementJobState.APPLIED;
            return job.refinement;
         }
      }

      private void applyReady(ServerLevel level, EarthChunkGenerator generator, int budget) {
         if (budget <= 0) {
            return;
         }

         int applied = 0;
         int scanned = 0;
         int maxScans = Math.max(budget * 4, budget);
         // 2026-09-27: same as `ChunkDetailManager.applyReady` -- add a wall-clock budget
         // the refinement patch rewrites the 256 column surfaces (`applyTerrainRefinementPatch`), so a single chunk can be just as expensive
         long deadlineNanos = System.nanoTime() + TERRAIN_REFINEMENT_APPLY_BUDGET_NANOS;

         while (applied < budget && scanned < maxScans) {
            if (System.nanoTime() - deadlineNanos >= 0L) {
               break;   // time is up, the rest is left to the next tick
            }

            Long chunkKey = this.readyQueue.poll();
            if (chunkKey == null) {
               break;
            }

            scanned++;
            EarthChunkGenerator.TerrainRefinementJob job = this.jobs.get(chunkKey);
            if (job == null || job.state != EarthChunkGenerator.TerrainRefinementJobState.READY) {
               continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
               // the chunk is gone (unloaded) -- it must no longer be put back on the queue unconditionally
               // the original implementation offered it back onto the queue tail, so:
               // - the deferred detail of this chunk **was never applied** (the feature silently stops working)
               // - that key cycles through the queue forever, and the queue grows without bound as the world generates
               // now: only "still young" jobs are put back (waiting for the chunk to load again);
               // past the stale threshold (5 minutes by default) they are **declared abandoned**, the job is removed and never put back
               if (EarthChunkGenerator.ChunkDetailPerf.now() - job.createdNs
                     < EarthChunkGenerator.PREPARED_CHUNK_STATE_STALE_NANOS) {
                  this.readyQueue.offer(chunkKey);
               } else {
                  this.jobs.remove(chunkKey, job);
                  EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
               }
               continue;
            }

            EarthChunkGenerator.PreparedTerrainRefinement refinement = this.claimReady(chunk.getPos());
            if (refinement == null) {
               continue;
            }

            try {
               generator.applyPreparedTerrainRefinement(level, chunk, refinement);
               applied++;
               EarthChunkGenerator.TerrainStreamingPerf.recordRefinementApplied();
            } catch (RuntimeException error) {
               Tellus.LOGGER.debug("Failed to apply terrain refinement for {}", chunk.getPos(), error);
            }
         }
      }
   }

   private static final class ChunkDetailManager {
      private final Map<Long, EarthChunkGenerator.ChunkDetailJob> jobs = new ConcurrentHashMap<>();
      private final ConcurrentLinkedQueue<Long> readyQueue = new ConcurrentLinkedQueue<>();
      private final Map<UUID, EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState> playerPrefetchStates = new ConcurrentHashMap<>();

      private void schedule(EarthChunkGenerator generator, EarthChunkGenerator.ChunkGenerationContext context) {
         long chunkKey = context.chunkKey();
         EarthChunkGenerator.ChunkDetailJob job = new EarthChunkGenerator.ChunkDetailJob(EarthChunkGenerator.ChunkDetailJobState.QUEUED);
         EarthChunkGenerator.ChunkDetailJob previous = this.jobs.put(chunkKey, job);
         if (previous != null) {
            previous.state = EarthChunkGenerator.ChunkDetailJobState.STALE;
            EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
         }

         EarthChunkGenerator.ChunkDetailPerf.recordQueueDepth(this.jobs.size());
         TellusWorldgenSources.submitTerrainDetailTask(() -> {
            AtomicBoolean shouldFetch = new AtomicBoolean();
            AtomicBoolean staleBeforeFetch = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.ChunkDetailJobState.STALE;
                  staleBeforeFetch.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.ChunkDetailJobState.FETCHING;
               shouldFetch.set(true);
               return current;
            });
            if (!shouldFetch.get()) {
               if (staleBeforeFetch.get()) {
                  EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
               }
               return;
            }

            try {
               job.detail = generator.prepareDeferredChunkDetail(context);
            } catch (RuntimeException error) {
               EarthChunkGenerator.ChunkDetailPerf.recordFailure();
               job.state = EarthChunkGenerator.ChunkDetailJobState.FAILED;
               Tellus.LOGGER.debug("Failed to prepare deferred chunk detail for {}", context.pos(), error);
               this.jobs.remove(chunkKey, job);
               return;
            }

            AtomicBoolean staleAfterBuild = new AtomicBoolean();
            AtomicBoolean ready = new AtomicBoolean();
            this.jobs.compute(chunkKey, (key, current) -> {
               if (current != job) {
                  job.state = EarthChunkGenerator.ChunkDetailJobState.STALE;
                  staleAfterBuild.set(true);
                  return current;
               }

               job.state = EarthChunkGenerator.ChunkDetailJobState.READY;
               this.readyQueue.offer(chunkKey);
               ready.set(true);
               return current;
            });
            if (staleAfterBuild.get()) {
               EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
            } else if (ready.get()) {
               EarthChunkGenerator.ChunkDetailPerf.recordDetailJob(EarthChunkGenerator.ChunkDetailPerf.elapsedSince(job.createdNs));
            }
         });
      }

      /**
       * Reaps jobs that have been queued longer than the stale threshold (returns how many were removed).
       *
       * <p>It uses {@code ConcurrentHashMap.remove(key, value)} for an **atomic** removal --
       * even if the async job happens to complete and swap the value at that exact moment, only one side can win,
       * so a job that is in use is never deleted by mistake. Iterating is safe.
       */
      private int reapStale(long now) {
         int removed = 0;
         for (Entry<Long, EarthChunkGenerator.ChunkDetailJob> entry : this.jobs.entrySet()) {
            EarthChunkGenerator.ChunkDetailJob job = entry.getValue();
            if (job != null
                  && now - job.createdNs >= EarthChunkGenerator.PREPARED_CHUNK_STATE_STALE_NANOS
                  && this.jobs.remove(entry.getKey(), job)) {
               removed++;
            }
         }
         return removed;
      }

      private EarthChunkGenerator.PreparedChunkDetail claimReady(ChunkPos pos) {
         long chunkKey = ChunkPos.pack(pos.x(), pos.z());
         EarthChunkGenerator.ChunkDetailJob job = this.jobs.get(chunkKey);
         if (job == null || job.state != EarthChunkGenerator.ChunkDetailJobState.READY) {
            return null;
         } else if (!this.jobs.remove(chunkKey, job)) {
            return null;
         } else {
            job.state = EarthChunkGenerator.ChunkDetailJobState.APPLIED;
            return job.detail;
         }
      }

      private void applyReady(ServerLevel level, EarthChunkGenerator generator, int budget) {
         if (budget <= 0) {
            return;
         }

         int applied = 0;
         int scanned = 0;
         int maxScans = Math.max(budget * 4, budget);
         // 2026-09-27: **wall-clock budget** (measured from a real machine log: `detailApply(total=13576ms, calls=1)`)
         // the old code throttled **by chunk count only** -- the slowest single chunk measured 13.6 s,
         // so a budget of 2 meant a **27 s main-thread freeze** (the game looked completely dead).
         // changed to "at most this much time per tick": the chunk in flight still runs to completion (**never interrupted**,
         // otherwise a half-populated chunk would be left behind), but a **second chunk no longer follows in the same tick**, so a hitch is bounded by one chunk's cost.
         long deadlineNanos = System.nanoTime() + CHUNK_DETAIL_APPLY_BUDGET_NANOS;

         while (applied < budget && scanned < maxScans) {
            if (System.nanoTime() - deadlineNanos >= 0L) {
               break;   // this tick's time is used up, the rest goes to the next tick
            }

            Long chunkKey = this.readyQueue.poll();
            if (chunkKey == null) {
               break;
            }

            scanned++;
            EarthChunkGenerator.ChunkDetailJob job = this.jobs.get(chunkKey);
            if (job == null || job.state != EarthChunkGenerator.ChunkDetailJobState.READY) {
               continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
               // the chunk is gone (unloaded) -- it must no longer be put back on the queue unconditionally
               // the original implementation offered it back onto the queue tail, so:
               // - the deferred detail of this chunk **was never applied** (the feature silently stops working)
               // - that key cycles through the queue forever, and the queue grows without bound as the world generates
               // now: only "still young" jobs are put back (waiting for the chunk to load again);
               // past the stale threshold (5 minutes by default) they are **declared abandoned**, the job is removed and never put back
               if (EarthChunkGenerator.ChunkDetailPerf.now() - job.createdNs
                     < EarthChunkGenerator.PREPARED_CHUNK_STATE_STALE_NANOS) {
                  this.readyQueue.offer(chunkKey);
               } else {
                  this.jobs.remove(chunkKey, job);
                  EarthChunkGenerator.ChunkDetailPerf.recordStaleDrop();
               }
               continue;
            }

            EarthChunkGenerator.PreparedChunkDetail detail = this.claimReady(chunk.getPos());
            if (detail == null) {
               continue;
            }

            try {
               generator.applyPreparedChunkDetail(level, chunk, detail);
               applied++;
            } catch (RuntimeException error) {
               EarthChunkGenerator.ChunkDetailPerf.recordFailure();
               Tellus.LOGGER.debug("Failed to apply deferred chunk detail for {}", chunk.getPos(), error);
            }
         }
      }

      private void prefetchAroundPlayers(ServerLevel level, EarthChunkGenerator generator, int radius) {
         Set<UUID> activePlayers = new HashSet<>();
         long gameTime = level.getGameTime();

         for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) {
               continue;
            }

            UUID playerId = player.getUUID();
            activePlayers.add(playerId);
            EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState previousState = this.playerPrefetchStates.get(playerId);
            ChunkPos pos = player.chunkPosition();
            long chunkKey = ChunkPos.pack(pos.x(), pos.z());
            Long previousChunkKey = previousState == null ? null : previousState.chunkKey();
            EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction = this.resolvePrefetchDirection(player, pos, previousChunkKey);
            boolean changedChunk = previousState == null || previousState.chunkKey() != chunkKey;
            if (!changedChunk && !this.shouldRefreshForDirectionChange(player, pos, previousState, direction, gameTime)) {
               continue;
            }

            this.playerPrefetchStates.put(playerId, new EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState(chunkKey, direction, gameTime));

            boolean includeRoadsPrefetch = generator.shouldDeferRoadDetails();
            boolean includeDetailedWaterPrefetch = generator.shouldDeferDetailedWater();
            boolean includeBuildingsPrefetch = generator.shouldDeferBuildingDetails();
            double previewResolutionMeters = generator.settings.worldScale();
            boolean teleportBurst = changedChunk && this.shouldUseTeleportBurst(pos, previousChunkKey);
            List<ChunkPos> terrainTargets = this.buildMovementAwareTargets(
               pos,
               direction,
               teleportBurst ? Math.max(radius + MOVEMENT_PREFETCH_FORWARD_EXTRA, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS) : radius + MOVEMENT_PREFETCH_FORWARD_EXTRA,
               teleportBurst ? Math.max(radius + MOVEMENT_PREFETCH_SIDE_EXTRA, Math.max(radius, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS / 2)) : radius + MOVEMENT_PREFETCH_SIDE_EXTRA,
               teleportBurst ? radius : Math.max(0, radius - 1)
            );
            for (ChunkPos target : terrainTargets) {
               TellusWorldgenSources.prefetchTerrainForChunk(target, generator.settings, previewResolutionMeters, false);
            }

            List<ChunkPos> waterTargets = this.buildMovementAwareTargets(
               pos,
               direction,
               teleportBurst ? Math.max(radius + 1, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS - 1) : radius + 1,
               teleportBurst ? Math.max(radius, MOVEMENT_PREFETCH_TELEPORT_BURST_RADIUS / 2) : radius,
               Math.max(0, radius - 1)
            );
            for (ChunkPos target : waterTargets) {
               TellusWorldgenSources.prefetchWaterForChunk(target, generator.settings, includeDetailedWaterPrefetch, previewResolutionMeters, false);
            }

            List<ChunkPos> osmTargets = this.buildMovementAwareTargets(pos, direction, radius + 1, radius, Math.max(0, radius - 1));
            for (ChunkPos target : osmTargets) {
               TellusWorldgenSources.prefetchOsmDetailsForChunk(
                  target, generator.settings, includeRoadsPrefetch, includeBuildingsPrefetch, previewResolutionMeters, false
               );
            }
         }

         this.playerPrefetchStates.keySet().removeIf(playerId -> !activePlayers.contains(playerId));
      }

      private boolean shouldUseTeleportBurst(ChunkPos current, Long previousChunkKey) {
         if (previousChunkKey == null) {
            return true;
         } else {
            int deltaX = Math.abs(current.x() - ChunkPos.getX(previousChunkKey));
            int deltaZ = Math.abs(current.z() - ChunkPos.getZ(previousChunkKey));
            return Math.max(deltaX, deltaZ) >= MOVEMENT_PREFETCH_TELEPORT_THRESHOLD_CHUNKS;
         }
      }

      private EarthChunkGenerator.ChunkDetailManager.PrefetchVector resolvePrefetchDirection(ServerPlayer player, ChunkPos current, Long previousChunkKey) {
         if (previousChunkKey != null) {
            int deltaX = current.x() - ChunkPos.getX(previousChunkKey);
            int deltaZ = current.z() - ChunkPos.getZ(previousChunkKey);
            EarthChunkGenerator.ChunkDetailManager.PrefetchVector fromChunkDelta = EarthChunkGenerator.ChunkDetailManager.PrefetchVector.normalized(deltaX, deltaZ);
            if (fromChunkDelta.hasDirection()) {
               return fromChunkDelta;
            }
         }

         Vec3 deltaMovement = player.getDeltaMovement();
         EarthChunkGenerator.ChunkDetailManager.PrefetchVector fromMotion = EarthChunkGenerator.ChunkDetailManager.PrefetchVector.normalized(
            deltaMovement.x, deltaMovement.z
         );
         if (fromMotion.hasDirection()) {
            return fromMotion;
         } else {
            Vec3 look = player.getLookAngle();
            return EarthChunkGenerator.ChunkDetailManager.PrefetchVector.normalized(look.x, look.z);
         }
      }

      private boolean shouldRefreshForDirectionChange(
         ServerPlayer player,
         ChunkPos current,
         EarthChunkGenerator.ChunkDetailManager.PlayerPrefetchState previousState,
         EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction,
         long gameTime
      ) {
         if (previousState == null || !direction.hasDirection() || !previousState.direction().hasDirection()) {
            return false;
         } else if (gameTime - previousState.lastPrefetchGameTime() < MOVEMENT_PREFETCH_DIRECTION_CHANGE_COOLDOWN_TICKS) {
            return false;
         } else if (direction.dot(previousState.direction()) > MOVEMENT_PREFETCH_DIRECTION_CHANGE_MAX_DOT) {
            return false;
         } else {
            return this.hasDirectionalRefreshSignal(player, current, direction);
         }
      }

      private boolean hasDirectionalRefreshSignal(
         ServerPlayer player, ChunkPos current, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction
      ) {
         Vec3 velocity = player.getDeltaMovement();
         double speedSq = velocity.x * velocity.x + velocity.z * velocity.z;
         return speedSq >= MOVEMENT_PREFETCH_MIN_SPEED_SQ || this.isNearLeadingChunkEdge(player, current, direction);
      }

      private boolean isNearLeadingChunkEdge(
         ServerPlayer player, ChunkPos current, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction
      ) {
         double localX = Mth.clamp(player.getX() - current.getMinBlockX(), 0.0, CHUNK_SIDE);
         double localZ = Mth.clamp(player.getZ() - current.getMinBlockZ(), 0.0, CHUNK_SIDE);
         double edgeDistance = Double.POSITIVE_INFINITY;
         if (direction.x() > 0.25) {
            edgeDistance = Math.min(edgeDistance, CHUNK_SIDE - localX);
         } else if (direction.x() < -0.25) {
            edgeDistance = Math.min(edgeDistance, localX);
         }

         if (direction.z() > 0.25) {
            edgeDistance = Math.min(edgeDistance, CHUNK_SIDE - localZ);
         } else if (direction.z() < -0.25) {
            edgeDistance = Math.min(edgeDistance, localZ);
         }

         return edgeDistance <= MOVEMENT_PREFETCH_EDGE_TRIGGER_DISTANCE_BLOCKS;
      }

      private List<ChunkPos> buildMovementAwareTargets(
         ChunkPos center, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction, int forwardReach, int sideReach, int rearReach
      ) {
         int clampedForwardReach = Math.max(0, forwardReach);
         int clampedSideReach = Math.max(0, sideReach);
         int clampedRearReach = Math.max(0, rearReach);
         int span = Math.max(clampedForwardReach, Math.max(clampedSideReach, clampedRearReach));
         if (!direction.hasDirection()) {
            return this.buildSymmetricTargets(center, span);
         } else {
            double dirX = direction.x();
            double dirZ = direction.z();
            double perpX = -dirZ;
            double perpZ = dirX;
            List<EarthChunkGenerator.ChunkDetailManager.PrefetchTarget> ranked = new ArrayList<>();

            for (int dz = -span; dz <= span; dz++) {
               for (int dx = -span; dx <= span; dx++) {
                  double forward = dx * dirX + dz * dirZ;
                  double side = Math.abs(dx * perpX + dz * perpZ);
                  if (!(forward < -clampedRearReach - 0.25) && !(forward > clampedForwardReach + 0.25) && !(side > clampedSideReach + 0.25)) {
                     int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
                     int manhattan = Math.abs(dx) + Math.abs(dz);
                     double score = dx == 0 && dz == 0 ? Double.NEGATIVE_INFINITY : chebyshev * 8.0 + manhattan * 1.5 + side * 2.0 - forward * 3.0;
                     ranked.add(new EarthChunkGenerator.ChunkDetailManager.PrefetchTarget(new ChunkPos(center.x() + dx, center.z() + dz), score));
                  }
               }
            }

            ranked.sort((left, right) -> Double.compare(left.score(), right.score()));
            List<ChunkPos> ordered = new ArrayList<>(ranked.size());

            for (EarthChunkGenerator.ChunkDetailManager.PrefetchTarget target : ranked) {
               ordered.add(target.pos());
            }

            return ordered;
         }
      }

      private List<ChunkPos> buildSymmetricTargets(ChunkPos center, int radius) {
         int clampedRadius = Math.max(0, radius);
         List<EarthChunkGenerator.ChunkDetailManager.PrefetchTarget> ranked = new ArrayList<>();

         for (int dz = -clampedRadius; dz <= clampedRadius; dz++) {
            for (int dx = -clampedRadius; dx <= clampedRadius; dx++) {
               int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
               int manhattan = Math.abs(dx) + Math.abs(dz);
               double score = dx == 0 && dz == 0 ? Double.NEGATIVE_INFINITY : chebyshev * 8.0 + manhattan;
               ranked.add(new EarthChunkGenerator.ChunkDetailManager.PrefetchTarget(new ChunkPos(center.x() + dx, center.z() + dz), score));
            }
         }

         ranked.sort((left, right) -> Double.compare(left.score(), right.score()));
         List<ChunkPos> ordered = new ArrayList<>(ranked.size());

         for (EarthChunkGenerator.ChunkDetailManager.PrefetchTarget target : ranked) {
            ordered.add(target.pos());
         }

         return ordered;
      }

      private record PrefetchTarget(ChunkPos pos, double score) {
      }

      private record PlayerPrefetchState(long chunkKey, EarthChunkGenerator.ChunkDetailManager.PrefetchVector direction, long lastPrefetchGameTime) {
      }

      private record PrefetchVector(double x, double z) {
         private static final EarthChunkGenerator.ChunkDetailManager.PrefetchVector NONE = new EarthChunkGenerator.ChunkDetailManager.PrefetchVector(0.0, 0.0);

         private static EarthChunkGenerator.ChunkDetailManager.PrefetchVector normalized(double x, double z) {
            double length = Math.hypot(x, z);
            return length > 1.0E-4 ? new EarthChunkGenerator.ChunkDetailManager.PrefetchVector(x / length, z / length) : NONE;
         }

         private boolean hasDirection() {
            return Math.abs(this.x) > 1.0E-4 || Math.abs(this.z) > 1.0E-4;
         }

         private double dot(EarthChunkGenerator.ChunkDetailManager.PrefetchVector other) {
            return this.x * other.x + this.z * other.z;
         }
      }
   }

   private static final class TerrainRefinementJob {
      /** Enqueue time -- used to reap stale jobs whose chunk "never came back after being queued" (see reapStaleDeferredJobs). */
      private final long createdNs = EarthChunkGenerator.ChunkDetailPerf.now();
      private volatile EarthChunkGenerator.TerrainRefinementJobState state;
      private volatile EarthChunkGenerator.PreparedTerrainRefinement refinement;

      private TerrainRefinementJob(EarthChunkGenerator.TerrainRefinementJobState state) {
         this.state = state;
      }
   }

   private static enum TerrainRefinementJobState {
      QUEUED,
      FETCHING,
      READY,
      APPLIED,
      FAILED,
      STALE;
   }

   private static final class ChunkDetailJob {
      private final long createdNs = EarthChunkGenerator.ChunkDetailPerf.now();
      private volatile EarthChunkGenerator.ChunkDetailJobState state;
      private volatile EarthChunkGenerator.PreparedChunkDetail detail;

      private ChunkDetailJob(EarthChunkGenerator.ChunkDetailJobState state) {
         this.state = state;
      }
   }

   private static enum ChunkDetailJobState {
      QUEUED,
      FETCHING,
      READY,
      APPLIED,
      FAILED,
      STALE;
   }

   private static enum SurfaceMode {
      LEGACY,
      TWO_TIER;
   }

   private static enum FullChunkPhase {
      STRUCTURES_TOTAL("total"),
      STRUCTURES_SUPER("super"),
      STRUCTURES_VILLAGES("villages"),
      STRUCTURES_WOODLAND_MANSIONS("woodland"),
      STRUCTURES_IGLOOS("igloos"),
      STRUCTURES_STRONGHOLDS("strongholds"),
      STRUCTURES_MINESHAFTS("mineshafts"),
      STRUCTURES_TRIAL_CHAMBERS("trialChambers"),
      STRUCTURES_OCEAN_MONUMENTS("monuments"),
      STRUCTURES_OSM_COLLISIONS("osmCollision"),
      FILL_TOTAL("total"),
      FILL_PREFETCH("prefetch"),
      FILL_HEIGHT_GRID("heightGrid"),
      FILL_HEIGHT_GRID_CACHE_HIT("cacheHit"),
      FILL_HEIGHT_GRID_CACHE_MISS("cacheMiss"),
      FILL_WATER_RESOLVE("waterResolve"),
      FILL_COLUMN_RESOLVE("columnResolve"),
      FILL_TERRAIN_REPAIR("terrainRepair"),
      FILL_BUILDING_PREP("buildingPrep"),
      FILL_BUILDING_TERRAIN("buildingTerrain"),
      FILL_BIOME_CACHE("biomeCache"),
      FILL_BIOME_CACHE_KOPPEN_HIT("koppenHit"),
      FILL_BIOME_CACHE_KOPPEN_MISS("koppenMiss"),
      FILL_DETAIL_SCHEDULE("detailSchedule"),
      FILL_DETAIL_TREE_PREP("treePrep"),
      FILL_BLOCKS("blockFill"),
      FILL_BLOCKS_SOLID_SECTIONS("solidSections"),
      FILL_BLOCKS_SOLID_SECTIONS_MAX_INDEX("maxIndex"),
      FILL_BLOCKS_SOLID_SECTIONS_SCAN("scan"),
      FILL_BLOCKS_SOLID_SECTIONS_SECTION_ACCESS("sectionAccess"),
      FILL_BLOCKS_SOLID_SECTIONS_SECTION_WRITE("sectionWrite"),
      FILL_BLOCKS_SOLID_SECTIONS_RECALC("recalc"),
      FILL_BLOCKS_DIRTY_SECTIONS("dirtySections"),
      FILL_BLOCKS_STONE_FILL("stoneFill"),
      FILL_BLOCKS_WATER_FILL("waterFill"),
      FILL_BLOCKS_SURFACE("surface"),
      FILL_BLOCKS_SURFACE_COVER_RESOLVE("coverResolve"),
      FILL_BLOCKS_SURFACE_PALETTE("paletteResolve"),
      FILL_BLOCKS_SURFACE_BADLANDS("badlands"),
      FILL_BLOCKS_SURFACE_BLOCK_WRITES("blockWrites"),
      FILL_BLOCKS_SURFACE_FAST_PATH("fastPath"),
      FILL_BLOCKS_SNOW("snow"),
      FILL_ROADS("roads"),
      FILL_STRUCTURE_CLEARANCE("structureClearance"),
      CARVERS_TOTAL("total"),
      CARVERS_WATER_GUARD("waterGuard"),
      CARVERS_RUNNER("runner"),
      DECORATION_TOTAL("total"),
      DECORATION_SUPER("super"),
      DECORATION_AXOLOTLS("axolotls"),
      DECORATION_TREES("trees"),
      DECORATION_BUILDINGS("buildings"),
      DECORATION_REALTIME_SNOW("realtimeSnow"),
      DECORATION_ROAD_LIGHTS("roadLights"),
      DECORATION_DEFERRED_APPLY("deferredApply"),
      DECORATION_TREES_DEFER_APPLY("deferredTreeApply");

      private final String logId;

      private FullChunkPhase(String logId) {
         this.logId = logId;
      }

      public String logId() {
         return this.logId;
      }
   }

   private static final class FullChunkPerf {
      private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.debug.fullChunkPerf", "false"));
      private static final long LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(2L);
      private static final AtomicLong NEXT_LOG_AT_NS = new AtomicLong(System.nanoTime() + LOG_INTERVAL_NS);
      private static final EarthChunkGenerator.FullChunkPhase[] PHASES = EarthChunkGenerator.FullChunkPhase.values();
      private static final LongAdder[] TOTAL_NS = createCounters();
      private static final LongAdder[] CALLS = createCounters();

      private static long now() {
         return ENABLED ? System.nanoTime() : 0L;
      }

      private static long elapsedSince(long startNs) {
         return ENABLED && startNs != 0L ? System.nanoTime() - startNs : 0L;
      }

      private static void record(EarthChunkGenerator.FullChunkPhase phase, long totalNs) {
         if (ENABLED && phase != null) {
            int index = phase.ordinal();
            CALLS[index].increment();
            if (totalNs > 0L) {
               TOTAL_NS[index].add(totalNs);
            }

            maybeLog();
         }
      }

      private static void recordCount(EarthChunkGenerator.FullChunkPhase phase, int count) {
         if (ENABLED && phase != null && count > 0) {
            CALLS[phase.ordinal()].add(count);
            maybeLog();
         }
      }

      private static LongAdder[] createCounters() {
         LongAdder[] counters = new LongAdder[PHASES.length];

         for (int i = 0; i < counters.length; i++) {
            counters[i] = new LongAdder();
         }

         return counters;
      }

      private static void maybeLog() {
         long now = System.nanoTime();
         long next = NEXT_LOG_AT_NS.get();
         if (now >= next && NEXT_LOG_AT_NS.compareAndSet(next, now + LOG_INTERVAL_NS)) {
            logAndReset();
         }
      }

      private static void logAndReset() {
         long[] totals = new long[PHASES.length];
         long[] calls = new long[PHASES.length];

         for (int i = 0; i < PHASES.length; i++) {
            totals[i] = TOTAL_NS[i].sumThenReset();
            calls[i] = CALLS[i].sumThenReset();
         }

         logGroup(
            "structures",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_TOTAL,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_SUPER,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_VILLAGES,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_WOODLAND_MANSIONS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_IGLOOS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_STRONGHOLDS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_MINESHAFTS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_TRIAL_CHAMBERS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_OCEAN_MONUMENTS,
            EarthChunkGenerator.FullChunkPhase.STRUCTURES_OSM_COLLISIONS
         );
         logGroup(
            "fill",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_TOTAL,
            EarthChunkGenerator.FullChunkPhase.FILL_PREFETCH,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID,
            EarthChunkGenerator.FullChunkPhase.FILL_WATER_RESOLVE,
            EarthChunkGenerator.FullChunkPhase.FILL_COLUMN_RESOLVE,
            EarthChunkGenerator.FullChunkPhase.FILL_TERRAIN_REPAIR,
            EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_PREP,
            EarthChunkGenerator.FullChunkPhase.FILL_BUILDING_TERRAIN,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE,
            EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_SCHEDULE,
            EarthChunkGenerator.FullChunkPhase.FILL_DETAIL_TREE_PREP,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS,
            EarthChunkGenerator.FullChunkPhase.FILL_ROADS,
            EarthChunkGenerator.FullChunkPhase.FILL_STRUCTURE_CLEARANCE
        );
         logGroup(
            "fill.heightGrid.detail",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_HIT,
            EarthChunkGenerator.FullChunkPhase.FILL_HEIGHT_GRID_CACHE_MISS
         );
         logGroup(
            "fill.biomeCache.detail",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_HIT,
            EarthChunkGenerator.FullChunkPhase.FILL_BIOME_CACHE_KOPPEN_MISS
         );
         logGroup(
            "fill.blockFill",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_DIRTY_SECTIONS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_STONE_FILL,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_WATER_FILL,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SNOW
        );
         logGroup(
            "fill.blockFill.solidSections",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_MAX_INDEX,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SCAN,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_ACCESS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_SECTION_WRITE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SOLID_SECTIONS_RECALC
         );
         logGroup(
            "fill.blockFill.surface",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_COVER_RESOLVE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_PALETTE,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BADLANDS,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_BLOCK_WRITES,
            EarthChunkGenerator.FullChunkPhase.FILL_BLOCKS_SURFACE_FAST_PATH
        );
         logGroup(
            "carvers",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.CARVERS_TOTAL,
            EarthChunkGenerator.FullChunkPhase.CARVERS_WATER_GUARD,
            EarthChunkGenerator.FullChunkPhase.CARVERS_RUNNER
         );
         logGroup(
            "decoration",
            totals,
            calls,
            EarthChunkGenerator.FullChunkPhase.DECORATION_TOTAL,
            EarthChunkGenerator.FullChunkPhase.DECORATION_SUPER,
            EarthChunkGenerator.FullChunkPhase.DECORATION_AXOLOTLS,
            EarthChunkGenerator.FullChunkPhase.DECORATION_TREES,
            EarthChunkGenerator.FullChunkPhase.DECORATION_BUILDINGS,
            EarthChunkGenerator.FullChunkPhase.DECORATION_REALTIME_SNOW,
            EarthChunkGenerator.FullChunkPhase.DECORATION_ROAD_LIGHTS,
            EarthChunkGenerator.FullChunkPhase.DECORATION_DEFERRED_APPLY,
            EarthChunkGenerator.FullChunkPhase.DECORATION_TREES_DEFER_APPLY
        );
      }

      private static void logGroup(String label, long[] totals, long[] calls, EarthChunkGenerator.FullChunkPhase... phases) {
         if (phases.length != 0) {
            long rootCalls = calls[phases[0].ordinal()];
            long rootTotal = totals[phases[0].ordinal()];
            if (rootCalls > 0L || rootTotal > 0L) {
               StringBuilder message = new StringBuilder("Full chunk perf (2s)[").append(label).append("]:");

               for (EarthChunkGenerator.FullChunkPhase phase : phases) {
                  int index = phase.ordinal();
                  long total = totals[index];
                  long callCount = calls[index];
                  if (callCount > 0L || total > 0L) {
                     message
                        .append(' ')
                        .append(phase.logId())
                        .append('=')
                        .append(toMillis(total))
                        .append("ms/")
                        .append(callCount);
                  }
               }

               Tellus.LOGGER.info(message.toString());
            }
         }
      }

      private static String toMillis(long nanos) {
         return nanos <= 0L ? "0.00" : String.format(Locale.ROOT, "%.2f", nanos / 1000000.0);
      }
   }

   private static final class SolidSectionFillProfiler {
      private long maxIndexNs;
      private long scanNs;
      private long sectionAccessNs;
      private long sectionWriteNs;
      private long recalcNs;
   }

   private static final class SurfaceApplyProfiler {
      private long paletteResolveNs;
      private long badlandsNs;
      private long blockWriteNs;
      private int fastPathCount;
   }

   private static final class TerrainStreamingPerf {
      private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.debug.chunkDetailPerf", "false"));
      private static final long LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(2L);
      private static final AtomicLong NEXT_LOG_AT_NS = new AtomicLong(System.nanoTime() + LOG_INTERVAL_NS);
      private static final LongAdder SHELL_CHUNKS = new LongAdder();
      private static final LongAdder SHELL_HEIGHT_MISSES = new LongAdder();
      private static final LongAdder SHELL_COVER_MISSES = new LongAdder();
      private static final LongAdder SHELL_VISUAL_MISSES = new LongAdder();
      private static final LongAdder SHELL_WATER_FALLBACKS = new LongAdder();
      private static final LongAdder SHELL_HEIGHT_FALLBACKS = new LongAdder();
      private static final LongAdder PREFETCH_QUEUE_REJECTIONS = new LongAdder();
      private static final LongAdder REFINEMENT_QUEUED = new LongAdder();
      private static final LongAdder REFINEMENT_APPLIES = new LongAdder();
      private static final LongAdder REFINEMENT_STALE_DROPS = new LongAdder();
      private static final LongAdder DETAIL_DELAYS = new LongAdder();

      private static void recordShellBuild(EarthChunkGenerator.TerrainWarmupTicket ticket) {
         if (ENABLED && ticket != null) {
            SHELL_CHUNKS.increment();
            if (ticket.heightGridMisses() > 0) {
               SHELL_HEIGHT_MISSES.add(ticket.heightGridMisses());
            }

            if (ticket.coverMisses() > 0) {
               SHELL_COVER_MISSES.add(ticket.coverMisses());
            }

            if (ticket.visualCoverMisses() > 0) {
               SHELL_VISUAL_MISSES.add(ticket.visualCoverMisses());
            }

            if (ticket.waterFallback()) {
               SHELL_WATER_FALLBACKS.increment();
            }

            if (ticket.usedHeightFallback()) {
               SHELL_HEIGHT_FALLBACKS.increment();
            }

            maybeLog();
         }
      }

      private static void recordRefinementApplied() {
         if (ENABLED) {
            REFINEMENT_APPLIES.increment();
            maybeLog();
         }
      }

      private static void recordRefinementQueued() {
         if (ENABLED) {
            REFINEMENT_QUEUED.increment();
            maybeLog();
         }
      }

      private static void recordRefinementStaleDrop() {
         if (ENABLED) {
            REFINEMENT_STALE_DROPS.increment();
            maybeLog();
         }
      }

      private static void recordPrefetchQueueRejection() {
         if (ENABLED) {
            PREFETCH_QUEUE_REJECTIONS.increment();
            maybeLog();
         }
      }

      private static void recordDetailDelayedByRefinement() {
         if (ENABLED) {
            DETAIL_DELAYS.increment();
            maybeLog();
         }
      }

      private static void maybeLog() {
         long now = System.nanoTime();
         long next = NEXT_LOG_AT_NS.get();
         if (now >= next && NEXT_LOG_AT_NS.compareAndSet(next, now + LOG_INTERVAL_NS)) {
            Tellus.LOGGER.info(
               "Terrain streaming perf (2s): shell(chunks={},heightMisses={},coverMisses={},visualMisses={},waterFallbacks={},heightFallbacks={},chunkThreadElevationDiskOpens=0,chunkThreadCoverDiskOpens=0,prefetchQueueRejections={}) repair(syncFetch={},fetchFailed={},resampleCalls={},repaired={},stillMissing={}) refinement(queued={},applied={},staleDrops={},detailDelays={})",
               new Object[]{
                  SHELL_CHUNKS.sumThenReset(),
                  SHELL_HEIGHT_MISSES.sumThenReset(),
                  SHELL_COVER_MISSES.sumThenReset(),
                  SHELL_VISUAL_MISSES.sumThenReset(),
                  SHELL_WATER_FALLBACKS.sumThenReset(),
                  SHELL_HEIGHT_FALLBACKS.sumThenReset(),
                  PREFETCH_QUEUE_REJECTIONS.sumThenReset(),
                  REFINEMENT_QUEUED.sumThenReset(),
                  REFINEMENT_APPLIES.sumThenReset(),
                  REFINEMENT_STALE_DROPS.sumThenReset(),
                  DETAIL_DELAYS.sumThenReset()
               }
            );
         }
      }
   }

   private static final class ChunkDetailPerf {
      private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.debug.chunkDetailPerf", "false"));
      private static final long LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(2L);
      private static final AtomicLong NEXT_LOG_AT_NS = new AtomicLong(System.nanoTime() + LOG_INTERVAL_NS);
      private static final LongAdder BASE_TERRAIN_NS = new LongAdder();
      private static final LongAdder BASE_TERRAIN_CALLS = new LongAdder();
      private static final LongAdder DETAIL_JOB_NS = new LongAdder();
      private static final LongAdder DETAIL_JOB_CALLS = new LongAdder();
      private static final LongAdder DETAIL_APPLY_NS = new LongAdder();
      /** 2026-09-27: the three sub-stages of apply are timed separately (measured: a single apply reached 13.6 s,
       * before there was only one grand total, so it was impossible to tell buildings, roads or trees apart) */
      private static final LongAdder DETAIL_APPLY_BUILDINGS_NS = new LongAdder();
      private static final LongAdder DETAIL_APPLY_ROADS_NS = new LongAdder();
      private static final LongAdder DETAIL_APPLY_TREES_NS = new LongAdder();
      private static final LongAdder DETAIL_APPLY_CALLS = new LongAdder();
      private static final LongAdder SKIPPED_BLOCKING_FALLBACKS = new LongAdder();
      private static final LongAdder STALE_DROPS = new LongAdder();
      private static final LongAdder FAILURES = new LongAdder();
      private static final AtomicLong MAX_QUEUE_DEPTH = new AtomicLong();

      private static long now() {
         return ENABLED ? System.nanoTime() : 0L;
      }

      private static long elapsedSince(long startNs) {
         return ENABLED && startNs != 0L ? System.nanoTime() - startNs : 0L;
      }

      private static void recordBaseTerrain(long totalNs) {
         if (ENABLED) {
            BASE_TERRAIN_CALLS.increment();
            if (totalNs > 0L) {
               BASE_TERRAIN_NS.add(totalNs);
            }

            maybeLog();
         }
      }

      private static void recordDetailJob(long totalNs) {
         if (ENABLED) {
            DETAIL_JOB_CALLS.increment();
            if (totalNs > 0L) {
               DETAIL_JOB_NS.add(totalNs);
            }

            maybeLog();
         }
      }

      private static void recordDetailApply(long totalNs) {
         if (ENABLED) {
            DETAIL_APPLY_CALLS.increment();
            if (totalNs > 0L) {
               DETAIL_APPLY_NS.add(totalNs);
            }

            maybeLog();
         }
      }

      /** 2026-09-27: timing of the apply sub-stages */
      private static void recordDetailApplyBuildings(long ns) {
         if (ENABLED && ns > 0L) {
            DETAIL_APPLY_BUILDINGS_NS.add(ns);
         }
      }

      private static void recordDetailApplyRoads(long ns) {
         if (ENABLED && ns > 0L) {
            DETAIL_APPLY_ROADS_NS.add(ns);
         }
      }

      private static void recordDetailApplyTrees(long ns) {
         if (ENABLED && ns > 0L) {
            DETAIL_APPLY_TREES_NS.add(ns);
         }
      }

      private static void recordSkippedBlockingFallback() {
         if (ENABLED) {
            SKIPPED_BLOCKING_FALLBACKS.increment();
            maybeLog();
         }
      }

      private static void recordStaleDrop() {
         if (ENABLED) {
            STALE_DROPS.increment();
            maybeLog();
         }
      }

      private static void recordFailure() {
         if (ENABLED) {
            FAILURES.increment();
            maybeLog();
         }
      }

      private static void recordQueueDepth(int queueDepth) {
         if (ENABLED) {
            MAX_QUEUE_DEPTH.accumulateAndGet(queueDepth, Math::max);
            maybeLog();
         }
      }

      private static void maybeLog() {
         long now = System.nanoTime();
         long next = NEXT_LOG_AT_NS.get();
         if (now >= next && NEXT_LOG_AT_NS.compareAndSet(next, now + LOG_INTERVAL_NS)) {
            logAndReset();
         }
      }

      private static void logAndReset() {
         Tellus.LOGGER.info(
            "Chunk detail perf (2s): baseTerrain(total={}ms,calls={}) detailJob(total={}ms,calls={}) detailApply(total={}ms,calls={})"
               + " [apply breakdown: buildings={}ms roads={}ms trees={}ms] skippedFallbacks={} staleDrops={} failures={} maxQueueDepth={}",
            new Object[]{
               toMillis(BASE_TERRAIN_NS.sumThenReset()),
               BASE_TERRAIN_CALLS.sumThenReset(),
               toMillis(DETAIL_JOB_NS.sumThenReset()),
               DETAIL_JOB_CALLS.sumThenReset(),
               toMillis(DETAIL_APPLY_NS.sumThenReset()),
               DETAIL_APPLY_CALLS.sumThenReset(),
               // 2026-09-27: a three-way breakdown of apply -- measured 13.6 s for a single chunk,
               // before there was only one grand total, so it was impossible to see whether buildings, roads or trees burned the time
               toMillis(DETAIL_APPLY_BUILDINGS_NS.sumThenReset()),
               toMillis(DETAIL_APPLY_ROADS_NS.sumThenReset()),
               toMillis(DETAIL_APPLY_TREES_NS.sumThenReset()),
               SKIPPED_BLOCKING_FALLBACKS.sumThenReset(),
               STALE_DROPS.sumThenReset(),
               FAILURES.sumThenReset(),
               MAX_QUEUE_DEPTH.getAndSet(0L)
            }
         );
      }

      private static String toMillis(long nanos) {
         return nanos <= 0L ? "0.00" : String.format(Locale.ROOT, "%.2f", nanos / 1000000.0);
      }
   }

   private record RoadColumnSample(int terrainSurface, int waterSurface, boolean hasWater) {
      private int roadSurface() {
         return this.hasWater ? Math.max(this.terrainSurface, this.waterSurface) : this.terrainSurface;
      }
   }

   private static final class FullChunkOceanBeachCache {
      private final int minX;
      private final int minZ;
      private final byte[] oceanDistances;
      private final boolean hasOcean;

      private FullChunkOceanBeachCache(int minX, int minZ, byte[] oceanDistances, boolean hasOcean) {
         this.minX = minX;
         this.minZ = minZ;
         this.oceanDistances = oceanDistances;
         this.hasOcean = hasOcean;
      }

      private static EarthChunkGenerator.FullChunkOceanBeachCache disabled(int minX, int minZ) {
         byte[] oceanDistances = new byte[CHUNK_AREA];
         Arrays.fill(oceanDistances, (byte)-1);
         return new EarthChunkGenerator.FullChunkOceanBeachCache(minX, minZ, oceanDistances, false);
      }

      private boolean hasOcean() {
         return this.hasOcean;
      }

      private int distanceToOcean(int worldX, int worldZ) {
         int localX = worldX - this.minX;
         int localZ = worldZ - this.minZ;
         if (localX >= 0 && localX < CHUNK_SIDE && localZ >= 0 && localZ < CHUNK_SIDE) {
            return this.oceanDistances[chunkIndex(localX, localZ)];
         } else {
            return -1;
         }
      }
   }

   private record ShorelineContext(MountainSurfaceRules.ShorelineKind kind, int distanceToShore, boolean shallowWater) {
      private static final EarthChunkGenerator.ShorelineContext NONE = new EarthChunkGenerator.ShorelineContext(
         MountainSurfaceRules.ShorelineKind.NONE, Integer.MAX_VALUE, false
      );
   }

   private record RoadWidths(int main, int normal, int dirt) {
   }

   private static final class BuildingGroupScratch {
      private final IntArrayList groundSamples = new IntArrayList();
      private final IntArrayList fallbackSamples = new IntArrayList();
      private int baseY = Integer.MIN_VALUE;

      private IntArrayList groundSamples() {
         return this.groundSamples;
      }

      private IntArrayList fallbackSamples() {
         return this.fallbackSamples;
      }

      private int baseY() {
         return this.baseY;
      }

      private void setBaseY(int baseY) {
         this.baseY = baseY;
      }
   }

   private record RasterizedBuildingFeature(
      OsmBuildingFeature feature,
      String groupId,
      boolean[] occupiedMask,
      int[] occupiedIndices,
      boolean[] boundary,
      int[] boundaryDistance
   ) {
      private boolean boundary(int index) {
         return this.boundary[index];
      }

      private int boundaryDistance(int index) {
         return this.boundaryDistance[index];
      }
   }

   private record AnalyzedBuildingFeature(EarthChunkGenerator.RasterizedBuildingFeature rasterized, IntArrayList groundSamples) {
   }

   private record SampledRoadStation(double worldX, double worldZ, double tangentX, double tangentZ) {
   }

   private record RoadLightAnchor(int localX, int localZ, int baseY, int index) {
   }

   /** The switch is read only once (the original implementation queried the system property for every generated chunk, which is pure waste). */
   private static final boolean SKIP_SCENERY_ON_BUILDINGS =
         Boolean.parseBoolean(System.getProperty("tellus.vegetation.skipOnBuildings", "true"));

   /** Vertical slack kept above and below when clearing scenery from buildings (to catch trees that grew on the rooftop). */
   private static final int BUILDING_SCENERY_MARGIN = 24;
   /** The cleanup log is emitted only once (avoids log spam). */
   private static final java.util.concurrent.atomic.AtomicBoolean BUILDING_SCENERY_LOG =
         new java.util.concurrent.atomic.AtomicBoolean();

   private static final class PreparedChunkBuildings {
      private final List<EarthChunkGenerator.PreparedBuildingFeature> features = new ArrayList<>();
      private final List<List<EarthChunkGenerator.BuildingColumnSpan>> columnSpans = new ArrayList<>(CHUNK_AREA);
      private final int[] flattenedTerrain = new int[CHUNK_AREA];
      private final boolean[] treeSuppression = new boolean[CHUNK_AREA];

      private PreparedChunkBuildings() {
         for (int i = 0; i < CHUNK_AREA; i++) {
            this.columnSpans.add(null);
         }

         Arrays.fill(this.flattenedTerrain, Integer.MIN_VALUE);
      }

      private boolean isEmpty() {
         return this.features.isEmpty();
      }

      private List<EarthChunkGenerator.PreparedBuildingFeature> features() {
         return this.features;
      }

      private int flattenedTerrainSurface(int index) {
         return this.flattenedTerrain[index];
      }

      private boolean suppressesTrees(int localX, int localZ) {
         return this.treeSuppression[chunkIndex(localX, localZ)];
      }

      /**
       * The vertical range of the building in this column (minY..maxY), or null when there is none.
       * Used to confine "removing the vegetation vanilla scattered on buildings" to the building's own height range --
       * it never touches terrain, roads or anything below the surface (project hard rule 1: only tighten, never widen).
       */
      private int[] buildingYSpan(int localX, int localZ) {
         List<EarthChunkGenerator.BuildingColumnSpan> spans = this.columnSpans.get(chunkIndex(localX, localZ));
         if (spans == null || spans.isEmpty()) {
            return null;
         }
         int min = Integer.MAX_VALUE;
         int max = Integer.MIN_VALUE;
         for (EarthChunkGenerator.BuildingColumnSpan span : spans) {
            min = Math.min(min, span.startY());
            max = Math.max(max, span.endY());
         }
         return min <= max ? new int[]{min, max} : null;
      }

      private boolean intersectsRoad(int localX, int localZ, int y) {
         List<EarthChunkGenerator.BuildingColumnSpan> spans = this.columnSpans.get(chunkIndex(localX, localZ));
         if (spans == null) {
            return false;
         } else {
            for (EarthChunkGenerator.BuildingColumnSpan span : spans) {
               if (span.contains(y)) {
                  return true;
               }
            }

            return false;
         }
      }

      private boolean intersectsSpan(int localX, int localZ, int minY, int maxY) {
         List<EarthChunkGenerator.BuildingColumnSpan> spans = this.columnSpans.get(chunkIndex(localX, localZ));
         if (spans == null) {
            return false;
         } else {
            for (EarthChunkGenerator.BuildingColumnSpan span : spans) {
               if (span.endY() >= minY && span.startY() <= maxY) {
                  return true;
               }
            }

            return false;
         }
      }

      private void addFeature(EarthChunkGenerator.PreparedBuildingFeature feature) {
         this.features.add(feature);

         for (int index : feature.occupiedIndices()) {
            int spanStart = feature.groundContact() ? feature.floorY() - 1 : feature.floorY();
            this.addSpan(index, spanStart, feature.columnTopY(index));
            if (feature.groundContact()) {
               int flattened = feature.floorY() - 1;
               if (flattened > this.flattenedTerrain[index]) {
                  this.flattenedTerrain[index] = flattened;
               }

               this.treeSuppression[index] = true;
            }
         }
      }

      private void addSpan(int index, int startY, int endY) {
         if (endY < startY) {
            return;
         }

         List<EarthChunkGenerator.BuildingColumnSpan> spans = this.columnSpans.get(index);
         if (spans == null) {
            spans = new ArrayList<>(2);
            spans.add(new EarthChunkGenerator.BuildingColumnSpan(startY, endY));
            this.columnSpans.set(index, spans);
         } else {
            int mergedStart = startY;
            int mergedEnd = endY;
            int insertAt = spans.size();

            for (int i = 0; i < spans.size(); i++) {
               EarthChunkGenerator.BuildingColumnSpan span = spans.get(i);
               if (mergedEnd + 1 < span.startY()) {
                  insertAt = i;
                  break;
               }

               if (mergedStart <= span.endY() + 1 && mergedEnd + 1 >= span.startY()) {
                  mergedStart = Math.min(mergedStart, span.startY());
                  mergedEnd = Math.max(mergedEnd, span.endY());
                  spans.remove(i--);
                  insertAt = i + 1;
               }
            }

            spans.add(insertAt, new EarthChunkGenerator.BuildingColumnSpan(mergedStart, mergedEnd));
         }
      }
   }

   private static final class PreparedChunkRoadLights {
      private final List<EarthChunkGenerator.PreparedRoadLight> lights = new ArrayList<>();

      private boolean isEmpty() {
         return this.lights.isEmpty();
      }

      private List<EarthChunkGenerator.PreparedRoadLight> lights() {
         return this.lights;
      }

      private void add(EarthChunkGenerator.PreparedRoadLight light) {
         this.lights.add(light);
      }
   }

   private record PreparedBuildingFeature(
      boolean[] occupied,
      int[] occupiedIndices,
      boolean[] boundary,
      int[] boundaryDistance,
      int[] columnTopY,
      BuildingBlueprint blueprint,
      boolean groundContact,
      EarthChunkGenerator.BuildingCoreLayout coreLayout
   ) {
      private int boundaryDistance(int index) {
         return this.boundaryDistance[index];
      }

      private int columnTopY(int index) {
         return this.columnTopY[index];
      }

      private int floorY() {
         return this.blueprint.floorY();
      }
   }

   private record BuildingColumnSpan(int startY, int endY) {
      private boolean contains(int y) {
         return y >= this.startY && y <= this.endY;
      }
   }

   private record PreparedRoadLight(int localX, int localZ, int baseY, int fenceCount, Direction trapdoorFacing) {
   }

   private record BuildingBoundaryInfo(boolean[] boundary, int[] boundaryDistance) {
   }

   private record BuildingCoreLayout(int minX, int maxX, int minZ, int maxZ, Direction stairFacing) {
      private boolean contains(int worldX, int worldZ) {
         return worldX >= this.minX && worldX <= this.maxX && worldZ >= this.minZ && worldZ <= this.maxZ;
      }

      private boolean boundary(int worldX, int worldZ) {
         return this.contains(worldX, worldZ)
            && (worldX == this.minX || worldX == this.maxX || worldZ == this.minZ || worldZ == this.maxZ);
      }
   }

   private record BuildingPalette(
      BlockState wall,
      BlockState trim,
      BlockState roof,
      BlockState window,
      BlockState floor,
      BlockState partition,
      BlockState stair,
      BlockState slab,
      BlockState light,
      /** 2026-10-06: window sill / string course, null when the material library is off. */
      BlockState sill,
      /** 2026-10-06: ground-floor storefront awning, commercial only, else null. */
      BlockState awning
   ) {
   }

   private record SurfacePalette( BlockState top,  BlockState underwaterTop,  BlockState filler, int depth) {
      static EarthChunkGenerator.SurfacePalette defaultOverworld() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.GRASS_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette steppe() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.COARSE_DIRT_STATE, EarthChunkGenerator.COARSE_DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette podzolic() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.PODZOL_STATE, EarthChunkGenerator.PODZOL_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette rooted() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.ROOTED_DIRT_STATE, EarthChunkGenerator.ROOTED_DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette mossy() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.MOSS_BLOCK_STATE, EarthChunkGenerator.MOSS_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette deepslateBlend() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.STONE_STATE,
            EarthChunkGenerator.STONE_STATE,
            EarthChunkGenerator.DEEPSLATE_STATE,
            10
         );
      }

      static EarthChunkGenerator.SurfacePalette deepslateMass() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.DEEPSLATE_STATE, EarthChunkGenerator.DEEPSLATE_STATE, EarthChunkGenerator.DEEPSLATE_STATE, 12
         );
      }

      static EarthChunkGenerator.SurfacePalette deepslateTalus() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.STONE_STATE,
            EarthChunkGenerator.STONE_STATE,
            EarthChunkGenerator.DEEPSLATE_STATE,
            8
         );
      }

      static EarthChunkGenerator.SurfacePalette deepslateScree() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.GRAVEL_STATE,
            EarthChunkGenerator.GRAVEL_STATE,
            EarthChunkGenerator.STONE_STATE,
            10
         );
      }

      static EarthChunkGenerator.SurfacePalette transitionDeepslate() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.DEEPSLATE_STATE, 8
         );
      }

      static EarthChunkGenerator.SurfacePalette transitionAndesite() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.ANDESITE_STATE, 8
         );
      }

      static EarthChunkGenerator.SurfacePalette transitionTuff() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.TUFF_STATE, 8);
      }

      static EarthChunkGenerator.SurfacePalette weatheredAndesite() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.ANDESITE_STATE, 12
         );
      }

      static EarthChunkGenerator.SurfacePalette tuffPeaks() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.TUFF_STATE, 12);
      }

      static EarthChunkGenerator.SurfacePalette tuffScree() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.GRAVEL_STATE, EarthChunkGenerator.GRAVEL_STATE, EarthChunkGenerator.TUFF_STATE, 10);
      }

      static EarthChunkGenerator.SurfacePalette desert() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SANDSTONE_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette badlands() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.RED_SAND_STATE, EarthChunkGenerator.RED_SAND_STATE, EarthChunkGenerator.TERRACOTTA_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette beach() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SAND_STATE, EarthChunkGenerator.SAND_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette ocean( BlockState top) {
         return new EarthChunkGenerator.SurfacePalette(top, top, top, 4);
      }

      static EarthChunkGenerator.SurfacePalette snowy() {
         return new EarthChunkGenerator.SurfacePalette(
            EarthChunkGenerator.SNOW_BLOCK_STATE, EarthChunkGenerator.SNOW_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, 4
         );
      }

      static EarthChunkGenerator.SurfacePalette swamp() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.GRASS_BLOCK_STATE, EarthChunkGenerator.DIRT_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette wetland() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.PACKED_MUD_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette mangrove() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.MUD_STATE, EarthChunkGenerator.DIRT_STATE, 4);
      }

      static EarthChunkGenerator.SurfacePalette stonyPeaks() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, EarthChunkGenerator.STONE_STATE, 12);
      }

      static EarthChunkGenerator.SurfacePalette gravelly() {
         return new EarthChunkGenerator.SurfacePalette(EarthChunkGenerator.GRAVEL_STATE, EarthChunkGenerator.GRAVEL_STATE, EarthChunkGenerator.STONE_STATE, 4);
      }
   }

   private static final class WaterChunkCache {
      private int chunkX = Integer.MIN_VALUE;
      private int chunkZ = Integer.MIN_VALUE;
      private WaterSurfaceResolver.WaterChunkData data;

      private boolean matches(ChunkPos pos) {
         return this.data != null && this.chunkX == pos.x() && this.chunkZ == pos.z();
      }

      private WaterSurfaceResolver.WaterChunkData data() {
         return this.data;
      }

      private void update(ChunkPos pos, WaterSurfaceResolver.WaterChunkData data) {
         this.chunkX = pos.x();
         this.chunkZ = pos.z();
         this.data = data;
      }
   }
}
