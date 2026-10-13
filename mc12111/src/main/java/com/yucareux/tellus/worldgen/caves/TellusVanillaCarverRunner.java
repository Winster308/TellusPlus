package com.yucareux.tellus.worldgen.caves;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Registry;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Aquifer.FluidStatus;
import net.minecraft.world.level.levelgen.Aquifer.FluidPicker;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.RandomState;

public final class TellusVanillaCarverRunner {
   private static final int CARVER_RADIUS_CHUNKS = 8;
   
   private final BiomeSource biomeSource;
   
   private final NoiseBasedChunkGenerator carvingContextGenerator;
   
   private final NoiseGeneratorSettings contextNoiseSettings;
   private final List<ConfiguredWorldCarver<?>> configuredCarvers;
   private final int chunkMinY;

   public TellusVanillaCarverRunner(
       BiomeSource biomeSource, Registry<Block> blockRegistry,  Holder<NoiseGeneratorSettings> noiseSettings, int tellusMinY, int tellusHeight,
      int tellusSeaLevel
   ) {
      this.biomeSource = Objects.requireNonNull(biomeSource, "biomeSource");
      Objects.requireNonNull(blockRegistry, "blockRegistry");
      Holder<NoiseGeneratorSettings> contextSettings = Objects.requireNonNull(noiseSettings, "noiseSettings");
      this.chunkMinY = tellusMinY;
      this.contextNoiseSettings = Objects.requireNonNull((NoiseGeneratorSettings)contextSettings.value(), "contextNoiseSettings");
      this.carvingContextGenerator = Objects.requireNonNull(new NoiseBasedChunkGenerator(this.biomeSource, contextSettings), "carvingContextGenerator");
      this.configuredCarvers = TellusConfiguredCarvers.create(blockRegistry, tellusMinY, tellusHeight, tellusSeaLevel)
         .orderedCarvers();
   }

   public void applyCarvers(
      WorldGenRegion level,
      long worldSeed,
      RandomState randomState,
      BiomeManager biomeManager,
      StructureManager structures,
      ChunkAccess chunk,
      int[] floodGuardYByColumn
   ) {
      RandomState safeRandomState = Objects.requireNonNull(randomState, "randomState");
      StructureManager safeStructures = Objects.requireNonNull(structures, "structures");
      NoiseGeneratorSettings safeNoiseSettings = Objects.requireNonNull(this.contextNoiseSettings, "contextNoiseSettings");
      NoiseBasedChunkGenerator safeCarvingContextGenerator = Objects.requireNonNull(this.carvingContextGenerator, "carvingContextGenerator");
      BiomeManager carvedBiomeManager = biomeManager.withDifferentSource(
         (quartX, quartY, quartZ) -> this.biomeSource.getNoiseBiome(quartX, quartY, quartZ, safeRandomState.sampler())
      );
      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
      ChunkPos targetPos = chunk.getPos();
      RegistryAccess registryAccess = level.registryAccess();
      FluidPicker fluidPicker = Objects.requireNonNull(createFluidPicker(this.chunkMinY + 8), "fluidPicker");
      NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
         candidateChunk -> NoiseChunk.forChunk(
            candidateChunk,
            safeRandomState,
            Beardifier.forStructuresInChunk(safeStructures, candidateChunk.getPos()),
            safeNoiseSettings,
            fluidPicker,
            Blender.empty()
         )
      );
// 2026-09-22 fix: a null NoiseChunk **must return early**.
      //
      //   Vanilla semantics: a NoiseChunk is only valid **during chunk generation**. Once the chunk is
      //   finished (it becomes a LevelChunk or leaves the generation flow),
      //   `chunk.getOrCreateNoiseChunk(...)` **returns null**.
      //
      //   The original implementation did not null check that in either of its two uses:
      //     1) `noiseChunk.aquifer()` throws an NPE directly
      //     2) `new CarvingContext(..., noiseChunk, ..., surfaceRule())` passes the **surfaceRule in
      //        as well**, and the LazyRule inside the surface rule calls
      //        `NoiseChunk.getBlockState(...)`, which is an NPE when the NoiseChunk is null
      //
      //   Measured stack trace (it kept showing up on `Worker-Main-*`, 20+ times):
      //     NullPointerException: NoiseChunk.getBlockState(...) because "$$2" is null
      //       at MaterialRule.apply              this is exactly that surfaceRule
      //       ChunkStatus -> worldgen task scheduling -> ForkJoinPool
      //   It **interrupts the generation threads**, so chunk generation is aborted and retried, which
      //   makes loading slower (94 seconds measured).
      //
      //   The fix: **without a NoiseChunk there is no carving**. The semantics are completely safe,
      //   because carving depends on the NoiseChunk anyway (the aquifer comes from it), and without it
      //   this chunk has long passed the carving stage and should not be carved again.
      if (noiseChunk == null) {
         return;
      }
      Aquifer aquifer = noiseChunk.aquifer();
      CarvingContext carvingContext = new CarvingContext(
         safeCarvingContextGenerator, registryAccess, chunk.getHeightAccessorForGeneration(), noiseChunk, safeRandomState, safeNoiseSettings.surfaceRule()
      );
      CarvingMask carvingMask = Objects.requireNonNull(getCarvingMask(chunk, floodGuardYByColumn), "carvingMask");

      for (int offsetX = -CARVER_RADIUS_CHUNKS; offsetX <= CARVER_RADIUS_CHUNKS; offsetX++) {
         for (int offsetZ = -CARVER_RADIUS_CHUNKS; offsetZ <= CARVER_RADIUS_CHUNKS; offsetZ++) {
            ChunkPos sourcePos = new ChunkPos(targetPos.x + offsetX, targetPos.z + offsetZ);

            for (int carverIndex = 0; carverIndex < this.configuredCarvers.size(); carverIndex++) {
               ConfiguredWorldCarver<?> configured = this.configuredCarvers.get(carverIndex);
               random.setLargeFeatureSeed(worldSeed + carverIndex, sourcePos.x, sourcePos.z);
               if (configured.isStartChunk(random)) {
                  configured.carve(
                     carvingContext,
                     chunk,
                     pos -> Objects.requireNonNull(carvedBiomeManager.getBiome(Objects.requireNonNull(pos, "carveBiomePos")), "carveBiome"),
                     random,
                     aquifer,
                     sourcePos,
                     carvingMask
                  );
               }
            }
         }
      }

      if (chunk instanceof ProtoChunk protoChunk && carvingMask != protoChunk.getCarvingMask()) {
         protoChunk.setCarvingMask(carvingMask);
      }
   }

   
/**
    * The carving mask of this chunk.
    *
    * <p><b>Optimisation (the original implementation built a new object per chunk and copied a whole
    * BitSet).</b> The original implementation used
    * {@code new CarvingMask(baseMask.toArray(), chunk.getMinY())}, that is "export the whole base mask
    * into a long[] and then build a second mask from it". Before the limit fix that was a **2 MB copy
    * per chunk**; now it is only 8 KB, but it is still pure waste.
    *
    * <p>It now attaches the additional mask **in place**: {@code setAdditionalMask} only sets a field,
    * the decision semantics are **exactly the same** as the original implementation (the extra
    * `y >= guard height` check is word for word identical), and it also **preserves whatever the mask
    * already holds**, so carving the same chunk twice does not lose anything. Behaviour is unchanged when
    * the chunk is not a ProtoChunk.
    */
   private static CarvingMask getCarvingMask(ChunkAccess chunk, int[] floodGuardYByColumn) {
      CarvingMask mask;
      if (chunk instanceof ProtoChunk protoChunk) {
         mask = protoChunk.getOrCreateCarvingMask();
      } else {
         mask = new CarvingMask(chunk.getHeight(), chunk.getMinY());
      }

      if (floodGuardYByColumn != null) {
         mask.setAdditionalMask((x, y, z) -> y >= floodGuardYByColumn[chunkIndex(x & 15, z & 15)]);
      }
      return Objects.requireNonNull(mask, "carvingMask");
   }

   
   private static FluidPicker createFluidPicker(int lavaLevel) {
      FluidStatus lava = new FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
      FluidStatus air = new FluidStatus(Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
      return Objects.requireNonNull((x, y, z) -> y < lavaLevel ? lava : air, "fluidPicker");
   }

   private static int chunkIndex(int localX, int localZ) {
      return localZ << 4 | localX;
   }
}
