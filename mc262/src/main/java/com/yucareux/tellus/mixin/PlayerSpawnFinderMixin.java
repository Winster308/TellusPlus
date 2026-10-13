package com.yucareux.tellus.mixin;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>26.2 spawn stall fix (measured: "Selecting global world spawn..." stuck for 11m40s).</b>
 *
 * <h2>What actually happens on 26.2</h2>
 * {@code MinecraftServer#setInitialSpawn} (26.2, verified with {@code javap -p -c}) runs:
 * <pre>
 *   offset  82: sampler.findSpawnPosition()                       // cheap, BEFORE the log line
 *   offset  96: levelLoadListener.start(PREPARE_GLOBAL_SPAWN, 0)  // "Selecting global world spawn..."
 *   offset 195: for (i = 0; i &lt; Mth.square(11); i++)              // 121 iterations
 *   offset 246:   new ChunkPos(base.x + dx, base.z + dz)
 *   offset 270:   PlayerSpawnFinder.getSpawnPosInChunk(level, chunkPos)
 *   offset 275:   if (result != null) { setSpawn(...); goto 367 } // exits on first hit
 * </pre>
 * and {@code getSpawnPosInChunk} delegates to {@code getLevelRespawnPos}, whose first
 * meaningful call is
 * <pre>
 *   offset 17: ServerLevel.getChunk(x, z)
 * </pre>
 * That is the <b>blocking, generate-now</b> variant, and it scans up to 16x16 block
 * positions inside the chunk looking for a standable column. When our terrain never
 * satisfies that heightmap test, all <b>121</b> chunks are generated on the server thread,
 * each through {@code EarthChunkGenerator#fillFromNoise} (the full DEM / OSM / building
 * path). That is the 11 minutes with no log output in between — the loop only logs when it
 * finishes.
 *
 * <h2>Why the existing "fast spawn" work never helped</h2>
 * The mod has a complete local-only spawn path ({@code EarthChunkGenerator#getSpawnPosition},
 * {@code EarthBiomeSource#resolveFastSpawnSurfaceBiome}, {@code fastSpawnMode}), but on 26.2
 * {@code ChunkGenerator} <b>no longer declares getSpawnPosition at all</b>:
 * <pre>
 *   javap ChunkGenerator -> getSpawnPosition: ABSENT;  getSpawnHeight: present
 *   whole-jar scan       -> "getSpawnPosition" appears only in Climate$SpawnFinder
 * </pre>
 * So that override became dead code and vanilla never calls it. This mixin re-attaches the
 * existing local-only computation to the method 26.2 <i>does</i> call.
 *
 * <h2>Why this is safe</h2>
 * <ul>
 *   <li>{@code getSpawnPosInChunk} is referenced <b>only</b> by {@code MinecraftServer}
 *       (whole-jar scan), so the blast radius is exactly the initial spawn search.</li>
 *   <li>Terrain generation itself is unchanged: no chunk is generated differently, it is
 *       simply not generated <i>during the spawn search</i>. It is built the first time the
 *       player actually needs it.</li>
 *   <li>The position comes from the mod's existing {@code getSurfacePosition}, which reads
 *       through the {@code *LocalOnly} variants and therefore cannot block on the network
 *       (see the notes on {@code WaterSurfaceResolver#resolveSpawnSafeColumnData}).</li>
 *   <li>{@code Tellus} (SERVER_STARTED) already computes and applies exactly this same
 *       position via {@code world.setRespawnData}, so the spawn the player receives is the
 *       one the mod intended all along.</li>
 *   <li>Any failure leaves {@code ci} uncancelled and the untouched vanilla search runs.</li>
 *   <li>Kill switch: {@code -Dtellus.spawn.fastInitialSpawn=false}.</li>
 * </ul>
 */
@Mixin(PlayerSpawnFinder.class)
public abstract class PlayerSpawnFinderMixin {

   /** Kill switch, following the project's convention for behaviour-changing fixes. */
   private static final boolean TELLUS_FAST_INITIAL_SPAWN =
      Boolean.parseBoolean(System.getProperty("tellus.spawn.fastInitialSpawn", "true"));

   /** Diagnostic: how many chunks vanilla would have generated before this fix. */
   private static final java.util.concurrent.atomic.AtomicLong DIAG_SPAWN_LOOKUPS =
      new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong DIAG_FIRST_LOG_NANOS =
      new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_LOG_NANOS =
      new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong DIAG_SAVED_CHUNKS =
      new java.util.concurrent.atomic.AtomicLong();

   @Inject(
      method = "getSpawnPosInChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/core/BlockPos;",
      at = @At("HEAD"),
      cancellable = true
   )
   private static void tellus$resolveInitialSpawnLocally(ServerLevel level, ChunkPos pos,
                                                         CallbackInfoReturnable<BlockPos> ci) {
      if (!TELLUS_FAST_INITIAL_SPAWN) {
         return;
      }

      try {
         ChunkGenerator generator = level.getChunkSource().getGenerator();
         if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
            return;   // not our world -> leave vanilla completely alone
         }

         // Reuse the mod's existing local-only spawn computation: this is the code that used to
         // hang off ChunkGenerator#getSpawnPosition before 26.2 removed that method.
         BlockPos spawn = earthGenerator.getSpawnPosition(level);
         if (spawn == null) {
            return;
         }

         long lookups = DIAG_SPAWN_LOOKUPS.incrementAndGet();
         long now = System.nanoTime();
         long first = DIAG_FIRST_LOG_NANOS.get();

         if (first == 0L) {
            if (DIAG_FIRST_LOG_NANOS.compareAndSet(0L, now)) {
               Tellus.LOGGER.info(
                  "[TellusCN] initial spawn resolved from local cache, no chunk generation needed: {} (vanilla would have generated a chunk for chunk {} here)",
                  spawn, pos);
            }
         } else if (now - first > 10_000_000_000L) {
            long prev = DIAG_LAST_LOG_NANOS.get();
            if (now - prev > 10_000_000_000L && DIAG_LAST_LOG_NANOS.compareAndSet(prev, now)) {
               Tellus.LOGGER.info(
                  "[TellusCN] local initial spawn still being used: {} lookups, {} chunk generations skipped so far",
                  lookups, DIAG_SAVED_CHUNKS.get());
            }
         }

         if (lookups > 1L) {
            DIAG_SAVED_CHUNKS.incrementAndGet();
         }

         ci.setReturnValue(spawn);
      } catch (Throwable error) {
         // Never take the world down over a spawn shortcut: fall back to the vanilla search.
         Tellus.LOGGER.warn("[TellusCN] local initial-spawn lookup failed, falling back to the vanilla search: {}",
            error.toString());
      }
   }
}
