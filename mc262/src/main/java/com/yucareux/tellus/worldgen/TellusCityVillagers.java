package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * Villagers in cities (asked for by the user on 2026-09-20).
 *
 * <h2>Why this needs its own implementation</h2>
 * Villagers belong to {@code MobCategory.MISC}, and vanilla natural spawning ({@code NaturalSpawner})
 * <b>only handles CREATURE / MONSTER / WATER_AMBIENT / AMBIENT / WATER_CREATURE</b>.
 * So writing {@code villager} in the biome JSON {@code spawners.misc} (which this project does, in
 * {@code tellus:city}, as a declaration of intent) does <b>not</b> actually produce villagers.
 * Vanilla villagers come from <b>village structures</b>, while Tellus builds its cities out of OSM
 * buildings rather than structures, so the only option is a controlled generation layer of our own.
 *
 * <h2>How it is done (strictly following this project's hard rules)</h2>
 * <ul>
 *   <li><b>Never blocks the server thread</b>: it runs only once every {@code tickInterval}
 *       (100 ticks, 5 seconds, by default), tries <b>at most once per player per round</b>, touches only
 *       <b>already loaded</b> chunks ({@code getChunkNow}), and <b>never triggers chunk loading or
 *       generation</b>;</li>
 *   <li><b>City biomes only</b> ({@code tellus:city}, meaning ESA land cover = built up area);</li>
 *   <li><b>There is a count cap</b> (at most 6 within a 48 block radius by default), so it cannot flood;</li>
 *   <li><b>It can be switched off with one line</b>: {@code -Dtellus.cityVillagers=false}
 *       (the value can live in {@code config/telluscn-layout.properties});</li>
 *   <li>the spawn call <b>copies what this mod already does</b>:
 *       {@code EntityType.X.create(level, e -> {}, pos, EntitySpawnReason.CHUNK_GENERATION, false, false)}
 *       plus {@code setPersistenceRequired()} plus {@code addFreshEntity}, as in
 *       {@code EarthChunkGenerator.spawnAxolotlsInLushPonds}, which is how the 1.21.9 API stays correct.</li>
 * </ul>
 */
public final class TellusCityVillagers {

   /** City biome id (matches data/tellus/worldgen/biome/city.json and the biome mapping CSV). */
   private static final String CITY_BIOME_ID = "tellus:city";

   private static final boolean ENABLED =
      Boolean.parseBoolean(System.getProperty("tellus.cityVillagers", "true"));
   private static final int MAX_NEARBY =
      intProperty("tellus.cityVillagers.max", 6);
   private static final int RADIUS =
      intProperty("tellus.cityVillagers.radius", 48);
   private static final int TICK_INTERVAL =
      intProperty("tellus.cityVillagers.tickInterval", 100);
   /** Probability (0 to 1) of attempting a spawn per player per round. */
   private static final double CHANCE =
      doubleProperty("tellus.cityVillagers.chance", 0.5);

   private static final AtomicBoolean FIRST_LOG = new AtomicBoolean();
   private static long tickCounter;

   private TellusCityVillagers() {
   }

   /** Called from the server tick event; the rate is controlled internally. */
   public static void onServerTick(net.minecraft.server.MinecraftServer server) {
      if (!ENABLED) {
         return;
      }
      if (++tickCounter % TICK_INTERVAL != 0L) {
         return;
      }
      try {
         for (ServerLevel level : server.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator)) {
               continue;   // only applies to Tellus worlds
            }
            for (ServerPlayer player : level.players()) {
               trySpawnNear(level, player);
            }
         }
      } catch (Throwable t) {
         Tellus.LOGGER.debug("[TellusCN] City villager spawn error (ignored): {}", t.toString());
      }
   }

   private static void trySpawnNear(ServerLevel level, ServerPlayer player) {
      if (level.getRandom().nextDouble() > CHANCE) {
         return;
      }
      BlockPos base = player.blockPosition();
      int dx = level.getRandom().nextInt(RADIUS * 2 + 1) - RADIUS;
      int dz = level.getRandom().nextInt(RADIUS * 2 + 1) - RADIUS;
      int x = base.getX() + dx;
      int z = base.getZ() + dz;

      // Only touch already loaded chunks: give up when it is not loaded, never trigger loading or generation
      int chunkX = x >> 4;
      int chunkZ = z >> 4;
      if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
         return;
      }

      int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
      BlockPos ground = new BlockPos(x, y - 1, z);
      BlockPos feet = new BlockPos(x, y, z);

      // Check the biome first (cheap), and bail out when it is not a city
      String biomeId = level.getBiome(feet)
         .unwrapKey()
         .map(key -> key.identifier().toString())
         .orElse("");
      if (!CITY_BIOME_ID.equals(biomeId)) {
         return;
      }

      // Must be able to stand there: solid block underfoot, air at the feet and at the head
      BlockState below = level.getBlockState(ground);
      // The block underfoot must be a real block and not a fluid (isAir and getFluidState are settled APIs this mod already uses)
      if (below.isAir() || !below.getFluidState().isEmpty()) {
         return;
      }
      if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) {
         return;
      }

      // Count cap, using the already loaded entities so the world is not scanned any further
      AABB box = new AABB(x - RADIUS, level.getMinY(), z - RADIUS, x + RADIUS, level.getMaxY(), z + RADIUS);
      int nearby = level.getEntitiesOfClass(Villager.class, box).size();
      if (nearby >= MAX_NEARBY) {
         return;
      }

      Villager villager = EntityTypes.VILLAGER.create(level, v -> { }, feet, EntitySpawnReason.CHUNK_GENERATION, false, false);
      if (villager == null) {
         return;
      }
      villager.setPersistenceRequired();   // do not let natural despawning clear it
      villager.setPos(x + 0.5, y, z + 0.5);
      if (level.addFreshEntity(villager) && FIRST_LOG.compareAndSet(false, true)) {
         Tellus.LOGGER.info("[TellusCN] City villagers are now spawning (at most {} within a {} block radius in biome {};"
            + "add -Dtellus.cityVillagers=false to turn this off)",
            CITY_BIOME_ID, RADIUS, MAX_NEARBY);
      }
   }

   private static int intProperty(String key, int def) {
      try {
         return Integer.parseInt(System.getProperty(key, Integer.toString(def)).trim());
      } catch (NumberFormatException e) {
         return def;
      }
   }

   private static double doubleProperty(String key, double def) {
      try {
         return Double.parseDouble(System.getProperty(key, Double.toString(def)).trim());
      } catch (NumberFormatException e) {
         return def;
      }
   }
}
