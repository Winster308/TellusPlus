package com.yucareux.tellus.integration.voxy;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

public final class TellusVoxyPregenManager {
   private static final long TICK_NANOS = 50000000L;
   private static final long SOFT_OVERLOAD_TICK_NANOS = 55000000L;
   private static final long HARD_OVERLOAD_TICK_NANOS = 70000000L;
   private static final int MAX_QUEUE_SIZE = 50000;
   private static final int IN_FLIGHT_MULTIPLIER = 4;
   private static final int ABSOLUTE_MAX_IN_FLIGHT = 256;
   private static final int COMPLETED_CACHE_MAX = 200000;
   private static final int TELEPORT_REPRIORITIZE_DISTANCE_CHUNKS = 64;
   private static final int MOVEMENT_REPRIORITIZE_DISTANCE_CHUNKS = 16;
   private static final int REPRIORITIZE_IN_FLIGHT_KEEP_RADIUS_CHUNKS = 16;
   private static final int REPRIORITIZE_RADIUS_PADDING_CHUNKS = 8;
   private static final int PRIORITY_RADIUS_CHUNKS = 2;
   private static final int STALE_QUEUE_PRUNE_TRIGGER = 10000;
   private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
   private final LongOpenHashSet queued = new LongOpenHashSet();
   private final LongArrayFIFOQueue priorityQueue = new LongArrayFIFOQueue();
   private final LongOpenHashSet priorityQueued = new LongOpenHashSet();
   /**
    * Recently completed chunk keys, LRU-bounded.
    *
    * <p>★★ 2026-10-04 Thread safety: {@code LongLinkedOpenHashSet} is an **intrusive doubly linked list** ✗ --
    * {@code addAndMoveToLast} modifies the next/prev of existing nodes in place, {@code removeFirstLong} modifies first ✗.
    * Meanwhile {@code recordCompletedChunk} runs on the **chunk completion callback thread** ({@code ifSuccess} callback,
    * see {@code ingestIfLevelChunk}), {@code contains} runs on the **server tick thread** ✗ --
    * the two sides interleave without synchronization ⇒ you may see a circular linked list (infinite loop while traversing) or lost entries.
    *
    * <p>The access frequency is "once per chunk" rather than "once per block", so simply guarding it with {@code synchronized} is fine ✓,
    * there is no need to switch to a concurrent collection (switching to {@code LongOpenHashSet} would lose LRU eviction ⇒ it would not re-run even in another world).
    */
   private final LongLinkedOpenHashSet completedChunks = new LongLinkedOpenHashSet();
   private final Map<UUID, TellusVoxyPregenManager.PlayerPregenState> playerStates = new HashMap<>();
   private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

   private volatile Boolean enabledOverride;

   private volatile Integer maxRadiusOverride;

   private volatile Integer chunksPerTickOverride;
   private volatile int lastConfiguredVoxyRadiusChunks;
   private volatile int lastEffectiveRadiusChunks;

   /**
    * Whether Voxy is present.
    *
    * <p>2026-09-23: static fast path for the standalone {@code VoxyServerTickMixin} mixin --
    * the mixin asks "is there work to do?" on every tick, so this check has to stay as cheap as
    * possible: a single {@code FabricLoader.isModLoaded("voxy")} map lookup that touches no Voxy class.
    */
   public static boolean isVoxyPresent() {
      try {
         return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy");
      } catch (RuntimeException | LinkageError error) {
         return false;
      }
   }

   /**
    * Central report for exceptions thrown by pre-generation inside a tick (logged once, to avoid spam).
    *
    * <p>2026-10-05 fix: this flag used to be set once and **never reset for the whole process
    * lifetime**, so a single failure anywhere -- in any world, at any time -- silenced every later
    * tick failure permanently. VoxyBridge's own LOGGED_CONFIG_ERROR / LOGGED_INGEST_ERROR in the
    * same package reset on success; this one did not, so the two halves of the same module had
    * different semantics. It now re-arms as soon as a tick completes cleanly (see
    * {@link #reportTickSuccess()}), which still suppresses spam from a persistently broken tick
    * while letting a new failure after a recovery be reported again.
    */
   private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_TICK_FAILURE =
      new java.util.concurrent.atomic.AtomicBoolean(false);

   public static void reportTickFailure(Throwable error) {
      if (LOGGED_TICK_FAILURE.compareAndSet(false, true)) {
         com.yucareux.tellus.Tellus.LOGGER.warn(
            "Tellus Voxy pre-generation hit an unexpected error (it keeps retrying; this message is printed once until a tick succeeds)", error);
      }
   }

   /** Called after a tick that did not throw, so the next distinct failure is reported again. */
   public static void reportTickSuccess() {
      LOGGED_TICK_FAILURE.set(false);
   }

   public void onServerTick(MinecraftServer server) {
      ServerLevel level = server.getLevel(Level.OVERWORLD);
      if (level == null) {
         this.clearQueueAndPlayerState();
         this.lastConfiguredVoxyRadiusChunks = 0;
         this.lastEffectiveRadiusChunks = 0;
      } else if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
         EarthGeneratorSettings var11 = earthGenerator.settings();
         if (!this.effectiveEnabled(var11)) {
            this.clearQueueAndPlayerState();
            this.lastConfiguredVoxyRadiusChunks = 0;
            this.lastEffectiveRadiusChunks = 0;
         } else if (!VoxyBridge.isVoxyLoaded()) {
            this.clearQueueAndPlayerState();
            this.lastConfiguredVoxyRadiusChunks = 0;
            this.lastEffectiveRadiusChunks = 0;
         } else {
            int configuredVoxyRadius = Math.max(0, VoxyBridge.configuredChunkRadius());
            this.lastConfiguredVoxyRadiusChunks = configuredVoxyRadius;
            int targetRadius = Math.min(configuredVoxyRadius, this.effectiveMaxRadius(var11));
            this.lastEffectiveRadiusChunks = targetRadius;
            if (targetRadius <= 0) {
               this.clearQueueAndPlayerState();
            } else {
               List<ServerPlayer> players = overworldPlayers(server, level);
               if (players.isEmpty()) {
                  this.clearQueueAndPlayerState();
               } else {
                  boolean movedPlayers = this.enqueueAroundPlayers(players, targetRadius);
                  if (movedPlayers) {
                     this.refreshPriorityQueue(players, targetRadius);
                     if (this.queue.size() >= STALE_QUEUE_PRUNE_TRIGGER) {
                        this.pruneNormalQueue(players, targetRadius + REPRIORITIZE_RADIUS_PADDING_CHUNKS);
                     }
                  }

                  long averageTickNanos = server.getAverageTickTimeNanos();
                  this.processQueue(server, level.getChunkSource(), this.effectiveChunksPerTick(var11), averageTickNanos);
               }
            }
         }
      } else {
         this.clearQueueAndPlayerState();
         this.lastConfiguredVoxyRadiusChunks = 0;
         this.lastEffectiveRadiusChunks = 0;
      }
   }

   public void shutdown() {
      this.clearQueueAndPlayerState();
      this.inFlight.clear();
      // ★★ 2026-10-04: completedChunks must be cleared as well ✗→✓
      //   VoxyPregenHost.MANAGER is a **process-level singleton** ✗; when you leave world A and open world B,
      //   chunks at the same coordinates in B are judged "completed" by contains() ⇒ **pregeneration silently does not run** ✗,
      //   and it does not recover until the LRU (200k) evicts them ✗.
      synchronized (this.completedChunks) {
         this.completedChunks.clear();
      }
      this.clearOverrides();
      this.lastConfiguredVoxyRadiusChunks = 0;
      this.lastEffectiveRadiusChunks = 0;
   }

   public void clearOverrides() {
      this.enabledOverride = null;
      this.maxRadiusOverride = null;
      this.chunksPerTickOverride = null;
   }

   public void setEnabledOverride( Boolean enabled) {
      this.enabledOverride = enabled;
   }

   public void setMaxRadiusOverride( Integer maxRadius) {
      this.maxRadiusOverride = maxRadius;
   }

   public void setChunksPerTickOverride( Integer chunksPerTick) {
      this.chunksPerTickOverride = chunksPerTick;
   }

   public boolean effectiveEnabled(EarthGeneratorSettings settings) {
      Boolean override = this.enabledOverride;
      return override != null ? override : settings.voxyChunkPregenEnabled();
   }

   public int effectiveMaxRadius(EarthGeneratorSettings settings) {
      Integer override = this.maxRadiusOverride;
      int configured = Math.max(0, override != null ? override : settings.voxyChunkPregenMaxRadius());
      return Math.min(configured, VRAM_AWARE_RADIUS_CAP);
   }

   /**
    * Upper bound on the pre-generation radius, derived from what Voxy can actually hold in GPU memory.
    *
    * <h2>Why this exists</h2>
    * Pre-generation is only useful if Voxy can keep the result resident. When the geometry buffer is much
    * smaller than the render distance implies, the extra chunks are generated, uploaded and then evicted
    * again -- pure cost, no visible benefit, and it competes with chunk generation for the same CPU.
    *
    * <h2>How the budget is derived</h2>
    * Voxy sizes its geometry buffer in {@code VoxyRenderSystem.getGeometryBufferSize()} (verified with
    * {@code javap -p -c}); the relevant part is
    * <pre>
    *   free = Capabilities.getFreeDedicatedGpuMemory() - 1610612736L      // 1.5 GB reserved
    *   free = Math.max(536870912L, free)                                  // floor 512 MB
    *   size = Math.min(ssboCap, free)
    * </pre>
    * Measured on this machine (GT 740, 2 GB, log line "1328MB of free memory"):
    * <pre>
    *   1392508928 - 1610612736 = -218103808   (negative)
    *   Math.max(536870912, -218103808) = 536870912   -> 512 MB, matching the logged
    *   "Voxy render system created with 536870912 geometry capacity"
    * </pre>
    * So a small card lands on the 512 MB floor. The cap below is scaled from that floor: the 512 MB case
    * gets the conservative radius, and each doubling of the budget adds one step. The numbers are
    * deliberately conservative -- generating slightly too little is recoverable (Voxy fills the rest on
    * demand), whereas over-generating wastes CPU and VRAM on a machine that has neither to spare.
    *
    * <p>{@code -Dtellus.voxy.radiusCap=N} overrides the cap outright; 0 disables it.
    */
   private static final int VRAM_AWARE_RADIUS_CAP = resolveVramAwareRadiusCap();

   private static int resolveVramAwareRadiusCap() {
      Integer explicit = Integer.getInteger("tellus.voxy.radiusCap");
      if (explicit != null) {
         return Math.max(0, explicit);
      }
      try {
         long geometry = voxyGeometryCapacityBytes();
         if (geometry <= 0L) {
            return Integer.MAX_VALUE;   // unknown -> do not restrict anything
         }
         // One step per doubling above the 512 MB floor: 512MB->512, 1GB->1024, 2GB->2048, ...
         long steps = Math.max(0L, Long.numberOfTrailingZeros(Long.highestOneBit(geometry)) - 29L);
         long cap = 512L << Math.min(4L, steps);
         return (int)Math.min(Integer.MAX_VALUE, cap);
      } catch (Throwable t) {
         return Integer.MAX_VALUE;      // never let a probe failure shrink the world
      }
   }

   /**
    * Reads Voxy's geometry capacity through the same class that logs it, without linking against Voxy at
    * compile time (Voxy is a soft dependency and is not on the compile classpath).
    */
   private static long voxyGeometryCapacityBytes() {
      try {
         Class<?> renderSystem = Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem");
         for (java.lang.reflect.Field field : renderSystem.getDeclaredFields()) {
            if (field.getType() == long.class
                  && (field.getName().toLowerCase(java.util.Locale.ROOT).contains("geometry")
                      || field.getName().toLowerCase(java.util.Locale.ROOT).contains("capacity"))) {
               field.setAccessible(true);
               return field.getLong(null);
            }
         }
      } catch (Throwable ignored) {
         // Voxy absent, or the field was renamed in a newer build -> caller keeps the unrestricted cap.
      }
      return 0L;
   }

   public int effectiveChunksPerTick(EarthGeneratorSettings settings) {
      Integer override = this.chunksPerTickOverride;
      return Math.max(1, override != null ? override : settings.voxyChunkPregenChunksPerTick());
   }


   public Boolean enabledOverride() {
      return this.enabledOverride;
   }


   public Integer maxRadiusOverride() {
      return this.maxRadiusOverride;
   }


   public Integer chunksPerTickOverride() {
      return this.chunksPerTickOverride;
   }

   public int queuedChunkCount() {
      return this.queue.size() + this.priorityQueue.size();
   }

   public int inFlightChunkCount() {
      return this.inFlight.size();
   }

   public int lastConfiguredVoxyRadiusChunks() {
      return this.lastConfiguredVoxyRadiusChunks;
   }

   public int lastEffectiveRadiusChunks() {
      return this.lastEffectiveRadiusChunks;
   }

   private static List<ServerPlayer> overworldPlayers(MinecraftServer server, ServerLevel level) {
      List<ServerPlayer> players = new ArrayList<>();

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (player.level() == level) {
            players.add(player);
         }
      }

      return players;
   }

   private boolean enqueueAroundPlayers(List<ServerPlayer> players, int radius) {
      Set<UUID> active = new HashSet<>();
      boolean reprioritize = false;
      boolean movedPlayers = false;

      for (ServerPlayer player : players) {
         active.add(player.getUUID());
         TellusVoxyPregenManager.PlayerPregenState state = this.playerStates
            .computeIfAbsent(player.getUUID(), ignored -> new TellusVoxyPregenManager.PlayerPregenState());
         ChunkPos center = player.chunkPosition();
         if (state.centerX != center.x || state.centerZ != center.z) {
            movedPlayers = true;
            if (state.centerX != Integer.MIN_VALUE && state.centerZ != Integer.MIN_VALUE) {
               int jumpDistance = chebyshevChunkDistance(state.centerX, state.centerZ, center.x, center.z);
               boolean requiresReprioritize = jumpDistance >= TELEPORT_REPRIORITIZE_DISTANCE_CHUNKS;
               if (!requiresReprioritize) {
                  if (state.anchorX == Integer.MIN_VALUE || state.anchorZ == Integer.MIN_VALUE) {
                     state.anchorX = state.centerX;
                     state.anchorZ = state.centerZ;
                  }

                  int driftFromAnchor = chebyshevChunkDistance(state.anchorX, state.anchorZ, center.x, center.z);
                  requiresReprioritize = driftFromAnchor >= MOVEMENT_REPRIORITIZE_DISTANCE_CHUNKS;
               }

               if (requiresReprioritize) {
                  reprioritize = true;
                  state.nextRing = 0;
                  state.anchorX = center.x;
                  state.anchorZ = center.z;
               } else {
                  state.nextRing = Math.max(0, state.nextRing - jumpDistance);
               }

               state.centerX = center.x;
               state.centerZ = center.z;
            } else {
               state.centerX = center.x;
               state.centerZ = center.z;
               state.anchorX = center.x;
               state.anchorZ = center.z;
               state.nextRing = 0;
            }
         }
      }

      this.playerStates.entrySet().removeIf(entry -> !active.contains(entry.getKey()));
      if (reprioritize) {
         this.reprioritizeForCurrentPlayers(players, radius);
      }

      for (ServerPlayer playerx : players) {
         TellusVoxyPregenManager.PlayerPregenState state = this.playerStates.get(playerx.getUUID());
         if (state != null && state.nextRing <= radius && this.queue.size() < MAX_QUEUE_SIZE) {
            this.enqueueRing(state.centerX, state.centerZ, state.nextRing);
            state.nextRing++;
         }
      }

      return movedPlayers;
   }

   private void reprioritizeForCurrentPlayers(List<ServerPlayer> players, int radius) {
      this.queue.clear();
      this.queued.clear();
      int keepRadius = Math.min(radius + REPRIORITIZE_RADIUS_PADDING_CHUNKS, REPRIORITIZE_IN_FLIGHT_KEEP_RADIUS_CHUNKS);
      this.releaseFarInFlight(players, keepRadius);
      this.priorityQueue.clear();
      this.priorityQueued.clear();

      for (ServerPlayer player : players) {
         ChunkPos pos = player.chunkPosition();
         this.enqueueChunk(pos.x, pos.z);
         this.enqueuePriorityChunk(pos.x, pos.z);
      }
   }

   private void refreshPriorityQueue(List<ServerPlayer> players, int radius) {
      int localRadius = Math.min(PRIORITY_RADIUS_CHUNKS, radius);
      this.prunePriorityQueue(players, localRadius + PRIORITY_RADIUS_CHUNKS);

      for (ServerPlayer player : players) {
         ChunkPos center = player.chunkPosition();

         for (int ring = 0; ring <= localRadius; ring++) {
            this.enqueuePriorityRing(center.x, center.z, ring);
         }
      }
   }

   private void prunePriorityQueue(List<ServerPlayer> players, int keepRadius) {
      if (!this.priorityQueue.isEmpty()) {
         int size = this.priorityQueue.size();
         LongArrayFIFOQueue retained = new LongArrayFIFOQueue(size);
         this.priorityQueued.clear();

         for (int i = 0; i < size; i++) {
            long key = this.priorityQueue.dequeueLong();
            if (isNearAnyPlayer(players, key, keepRadius) && this.priorityQueued.add(key)) {
               retained.enqueue(key);
            }
         }

         this.priorityQueue.clear();

         while (!retained.isEmpty()) {
            this.priorityQueue.enqueue(retained.dequeueLong());
         }
      }
   }

   private void pruneNormalQueue(List<ServerPlayer> players, int keepRadius) {
      if (!this.queue.isEmpty()) {
         int size = this.queue.size();
         LongArrayFIFOQueue retained = new LongArrayFIFOQueue(size);

         for (int i = 0; i < size; i++) {
            long key = this.queue.dequeueLong();
            if (this.queued.contains(key)) {
               if (!isNearAnyPlayer(players, key, keepRadius)) {
                  this.queued.remove(key);
               } else {
                  retained.enqueue(key);
               }
            }
         }

         this.queue.clear();

         while (!retained.isEmpty()) {
            long key = retained.dequeueLong();
            if (this.queued.contains(key)) {
               this.queue.enqueue(key);
            }
         }
      }
   }

   private void releaseFarInFlight(List<ServerPlayer> players, int keepRadius) {
      for (long key : this.inFlight) {
         if (!isNearAnyPlayer(players, key, keepRadius)) {
            this.inFlight.remove(key);
         }
      }
   }

   private static boolean isNearAnyPlayer(List<ServerPlayer> players, long chunkKey, int radius) {
      int chunkX = ChunkPos.getX(chunkKey);
      int chunkZ = ChunkPos.getZ(chunkKey);

      for (ServerPlayer player : players) {
         ChunkPos center = player.chunkPosition();
         if (chebyshevChunkDistance(center.x, center.z, chunkX, chunkZ) <= radius) {
            return true;
         }
      }

      return false;
   }

   private static int chebyshevChunkDistance(int xA, int zA, int xB, int zB) {
      long dx = Math.abs((long)xA - xB);
      long dz = Math.abs((long)zA - zB);
      return (int)Math.min(2147483647L, Math.max(dx, dz));
   }

   private void enqueueRing(int centerX, int centerZ, int ring) {
      if (ring <= 0) {
         this.enqueueChunk(centerX, centerZ);
      } else {
         int minX = centerX - ring;
         int maxX = centerX + ring;
         int minZ = centerZ - ring;
         int maxZ = centerZ + ring;

         for (int x = minX; x <= maxX; x++) {
            this.enqueueChunk(x, minZ);
            if (maxZ != minZ) {
               this.enqueueChunk(x, maxZ);
            }
         }

         for (int z = minZ + 1; z < maxZ; z++) {
            this.enqueueChunk(minX, z);
            if (maxX != minX) {
               this.enqueueChunk(maxX, z);
            }
         }
      }
   }

   private void enqueueChunk(int chunkX, int chunkZ) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      if (!isCompleted(key)) {
         if (this.queued.add(key)) {
            this.queue.enqueue(key);
         }
      }
   }

   private void enqueuePriorityRing(int centerX, int centerZ, int ring) {
      if (ring <= 0) {
         this.enqueuePriorityChunk(centerX, centerZ);
      } else {
         int minX = centerX - ring;
         int maxX = centerX + ring;
         int minZ = centerZ - ring;
         int maxZ = centerZ + ring;

         for (int x = minX; x <= maxX; x++) {
            this.enqueuePriorityChunk(x, minZ);
            if (maxZ != minZ) {
               this.enqueuePriorityChunk(x, maxZ);
            }
         }

         for (int z = minZ + 1; z < maxZ; z++) {
            this.enqueuePriorityChunk(minX, z);
            if (maxX != minX) {
               this.enqueuePriorityChunk(maxX, z);
            }
         }
      }
   }

   private void enqueuePriorityChunk(int chunkX, int chunkZ) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      if (!isCompleted(key)) {
         if (this.priorityQueued.add(key)) {
            this.priorityQueue.enqueue(key);
         }
      }
   }

   private void processQueue(MinecraftServer server, ServerChunkCache source, int chunksPerTick, long averageTickNanos) {
      int launchBudget = this.effectiveLaunchBudget(chunksPerTick, averageTickNanos);
      if (launchBudget > 0) {
         int maxInFlight = Math.min(ABSOLUTE_MAX_IN_FLIGHT, Math.max(launchBudget + 1, launchBudget * IN_FLIGHT_MULTIPLIER));
         int launched = 0;

         while (launched < launchBudget) {
            if (this.inFlight.size() >= maxInFlight) {
               return;
            }

            Long polled = this.pollNextChunk();
            if (polled == null) {
               return;
            }

            long key = polled;
            if (!this.inFlight.contains(key) && this.inFlight.add(key)) {
               int chunkX = ChunkPos.getX(key);
               int chunkZ = ChunkPos.getZ(key);
               source.getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true).whenComplete((result, error) -> {
                  this.inFlight.remove(key);
                  if (error == null && result != null && result.isSuccess()) {
                     result.ifSuccess(chunk -> this.ingestIfLevelChunk(server, chunk, key));
                  }
               });
               launched++;
            }
         }
      }
   }

   /**
    * How many chunk requests pre-generation may launch this tick.
    *
    * <h2>2026-10-12: tick time alone is not enough</h2>
    * The original version only looked at {@code averageTickNanos}, and its fast branch actively <b>raised</b>
    * the quota when the tick was healthy ({@code base + base/2}). That is wrong for an asynchronous workload:
    * requests are submitted and return later, so a backlog does not show up in tick time until it is already
    * deep. Measured on a 1:1 world with pre-generation enabled, structure generation degraded monotonically
    * as the backlog grew:
    * <pre>
    *   23:49:01  super=5253ms/241    (22 ms per chunk)
    *   23:49:03  super=10651ms/151   (71 ms)
    *   23:50:45  super=44891ms/94    (478 ms)
    *   23:52:03  super=77122ms/1     (77 seconds for one chunk)
    *   23:53:16  super=77589ms/1
    * </pre>
    * and the server tick climbed 40s -> 80s -> ... -> 243s. Pre-generation was competing with the very chunk
    * generation the player was waiting on.
    *
    * <h2>What changed</h2>
    * Two guards, both based on the work already in hand rather than on tick time:
    * <ul>
    *   <li><b>Backlog ceiling.</b> Above {@link #BACKLOG_SOFT_LIMIT} queued-or-in-flight requests the quota
    *       drops to one per tick, and above {@link #BACKLOG_HARD_LIMIT} it stops entirely, so the queue drains
    *       before more is added and pre-generation cannot run ahead of what the pipeline absorbs.</li>
    *   <li><b>No more speculative growth.</b> The healthy-tick branch no longer multiplies the quota. The
    *       caller's {@code chunksPerTick} is now a ceiling rather than a starting point to grow from.</li>
    * </ul>
    * The tick-time checks are kept as a second line of defence for genuine synchronous stalls.
    *
    * <p>{@code -Dtellus.voxy.backlogSoftLimit=N} / {@code -Dtellus.voxy.backlogHardLimit=N} override the
    * thresholds.
    */
   private int effectiveLaunchBudget(int chunksPerTick, long averageTickNanos) {
      int base = Math.max(1, chunksPerTick);

      int backlog = this.queuedChunkCount() + this.inFlightChunkCount();
      if (backlog >= BACKLOG_HARD_LIMIT) {
         return 0;
      }
      if (backlog >= BACKLOG_SOFT_LIMIT) {
         return 1;
      }

      if (averageTickNanos >= HARD_OVERLOAD_TICK_NANOS) {
         return 0;
      } else if (averageTickNanos >= SOFT_OVERLOAD_TICK_NANOS) {
         return 1;
      } else if (averageTickNanos >= TICK_NANOS) {
         return Math.max(1, base / 2);
      } else {
         return base;
      }
   }

   /**
    * Backlog thresholds for pre-generation, counted as queued + in-flight requests.
    *
    * <p>Chosen from the observed queue sizes rather than guessed: with the old code the queue reached
    * thousands while structure generation stalled. One chunk is 1188 sections at 1:1, so a few hundred
    * outstanding requests is already a lot of work to be holding.
    */
   private static final int BACKLOG_SOFT_LIMIT = Math.max(1,
      Integer.getInteger("tellus.voxy.backlogSoftLimit", 64));

   private static final int BACKLOG_HARD_LIMIT = Math.max(BACKLOG_SOFT_LIMIT + 1,
      Integer.getInteger("tellus.voxy.backlogHardLimit", 256));


   private Long pollNextChunk() {
      if (!this.priorityQueue.isEmpty()) {
         long key = this.priorityQueue.dequeueLong();
         this.priorityQueued.remove(key);
         this.queued.remove(key);
         return key;
      } else {
         while (!this.queue.isEmpty()) {
            long key = this.queue.dequeueLong();
            if (this.queued.remove(key)) {
               return key;
            }
         }

         return null;
      }
   }

   private void ingestIfLevelChunk(MinecraftServer server, ChunkAccess chunk, long chunkKey) {
      if (chunk instanceof LevelChunk levelChunk) {
         this.recordCompletedChunk(chunkKey);
         VoxyBridge.tryIngestChunkAsync(server, levelChunk);
      }
   }

   /** Thread-safely check "whether this chunk has already been completed" ✓ (see the race notes on {@link #completedChunks}). */
   private boolean isCompleted(long chunkKey) {
      synchronized (this.completedChunks) {
         return this.completedChunks.contains(chunkKey);
      }
   }

   /** Thread-safely record one completed chunk + LRU eviction ✓. */
   private void recordCompletedChunk(long chunkKey) {
      synchronized (this.completedChunks) {
         if (this.completedChunks.addAndMoveToLast(chunkKey) && this.completedChunks.size() > COMPLETED_CACHE_MAX) {
            this.completedChunks.removeFirstLong();
         }
      }
   }

   private void clearQueueAndPlayerState() {
      this.queue.clear();
      this.queued.clear();
      this.priorityQueue.clear();
      this.priorityQueued.clear();
      this.playerStates.clear();
   }

   private static final class PlayerPregenState {
      private int centerX = Integer.MIN_VALUE;
      private int centerZ = Integer.MIN_VALUE;
      private int anchorX = Integer.MIN_VALUE;
      private int anchorZ = Integer.MIN_VALUE;
      private int nextRing;
   }
}
