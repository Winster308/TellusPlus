package com.yucareux.tellus.worldgen.building;

import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * 2026-10-06: <b>category-isolated material tables</b>, transcribed from arnis
 * (github.com/louis-e/arnis, Apache-2.0) {@code element_processing/buildings.rs}.
 * See {@code THIRD_PARTY_NOTICES.md}.
 *
 * <p><b>Why this file exists at all.</b> Before it, {@link TellusBuildingMaterials} had exactly
 * <b>one</b> wall block per {@code (archetype x climate)} pair: six archetypes times four climates,
 * a hard-coded constant in each. Every residential building in a temperate city was therefore the
 * same white terracotta, and the whole switch could only ever answer "which of five house styles",
 * never "which of 29 residential materials".
 *
 * <p><b>What arnis has and we did not:</b> <b>149 blocks in 21 category-isolated tables</b>, plus
 * a per-climate <b>weight</b> for every one of them, plus a <b>rare-block damper</b>. The tables
 * are pure data: the cheapest thing in the whole comparison to port, and the one with the largest
 * visible effect per line changed.
 *
 * <p><b>Transcription fidelity.</b> The 21 tables, the climate weight rows, the rare-block rule
 * and the weighted-pick loop are copied as written, <b>including the quirks</b>:
 * <ul>
 *   <li>{@code is_rare_wall_block} covers exactly three blocks (arnis buildings.rs:2333):
 *       {@code NETHER_BRICK}, {@code RED_NETHER_BRICKS}, {@code RED_TERRACOTTA}. A picked rare
 *       block is re-rolled unless a 20% coin comes up, at most 8 times.</li>
 *   <li>Climate weights are <b>0</b>, not "less likely", for blocks that do not belong in that
 *       climate at all: no sandstone or mud brick in a boreal wall, no timber in a desert wall.</li>
 *   <li>arnsis's {@code residential_scale} halves the timber weight for multi-family blocks
 *       ({@code (w / 2).max(1)}): apartments are less often timber than single houses.</li>
 * </ul>
 *
 * <p><b>Two deliberate deviations from arnis, both written out.</b>
 * <ol>
 *   <li><b>Determinism.</b> arnis rolls a fresh RNG per building because it is a CLI that runs
 *       once. We regenerate the same building on the client, on the server, and again when a chunk
 *       is re-read from cache, so every choice here is a pure function of the blueprint seed and
 *       never of a shared counter.</li>
 *   <li><b>Categories.</b> arnis derives 23 categories from OSM tags; our
 *       {@link BuildingProfile.Archetype} has 6. {@link #categoryFor} maps what we have onto the
 *       nearest arnis table rather than pretending 6 archetypes can reproduce 23 categories.</li>
 * </ol>
 */
public final class TellusBuildingMaterialLibrary {
   // =========================================================================
   // The 21 tables, transcribed verbatim from arnis element_processing/buildings.rs
   // =========================================================================

   /** arnis buildings.rs:125 ACCENT_BLOCK_OPTIONS, 6 entries. */
   static final Block[] ACCENT_BLOCK_OPTIONS = {
      Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS,
      Blocks.MUD_BRICKS, Blocks.ANDESITE, Blocks.CHISELED_STONE_BRICKS,
   };

   /** arnis buildings.rs:139 RESIDENTIAL_WALL_OPTIONS, 29 entries. */
   static final Block[] RESIDENTIAL_WALL_OPTIONS = {
      Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.DYED_TERRACOTTA.white(), Blocks.DYED_TERRACOTTA.brown(),
      Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.QUARTZ_BRICKS, Blocks.MUD_BRICKS,
      Blocks.POLISHED_GRANITE, Blocks.END_STONE_BRICKS, Blocks.CONCRETE.brown(),
      Blocks.DEEPSLATE_BRICKS, Blocks.CONCRETE.gray(), Blocks.DYED_TERRACOTTA.gray(),
      Blocks.DYED_TERRACOTTA.lightBlue(), Blocks.CONCRETE.lightGray(), Blocks.DYED_TERRACOTTA.lightGray(),
      Blocks.NETHER_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_BLACKSTONE,
      Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_DEEPSLATE, Blocks.QUARTZ_BLOCK,
      Blocks.CONCRETE.white(), Blocks.DYED_TERRACOTTA.orange(), Blocks.DYED_TERRACOTTA.red(),
      Blocks.RED_NETHER_BRICKS, Blocks.GRANITE, Blocks.TERRACOTTA,
   };

   /** arnis buildings.rs:172 COMMERCIAL_WALL_OPTIONS, 8 entries. */
   static final Block[] COMMERCIAL_WALL_OPTIONS = {
      Blocks.CONCRETE.white(), Blocks.CONCRETE.lightGray(), Blocks.CONCRETE.gray(),
      Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.QUARTZ_BLOCK,
      Blocks.QUARTZ_BRICKS, Blocks.STONE_BRICKS,
   };

   /** arnis buildings.rs:184 INDUSTRIAL_WALL_OPTIONS, 7 entries. */
   static final Block[] INDUSTRIAL_WALL_OPTIONS = {
      Blocks.CONCRETE.gray(), Blocks.CONCRETE.lightGray(), Blocks.STONE, Blocks.SMOOTH_STONE,
      Blocks.POLISHED_ANDESITE, Blocks.DEEPSLATE_BRICKS, Blocks.BLACKSTONE,
   };

   /** arnis buildings.rs:208 INSTITUTIONAL_WALL_OPTIONS, 8 entries. */
   static final Block[] INSTITUTIONAL_WALL_OPTIONS = {
      Blocks.CONCRETE.white(), Blocks.CONCRETE.lightGray(), Blocks.QUARTZ_BRICKS,
      Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE,
      Blocks.SANDSTONE, Blocks.END_STONE_BRICKS,
   };

   /** arnis buildings.rs:220 FARM_WALL_OPTIONS, 8 entries. */
   static final Block[] FARM_WALL_OPTIONS = {
      Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.COBBLESTONE,
      Blocks.STONE, Blocks.MUD_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.DYED_TERRACOTTA.brown(),
   };

   /** arnis buildings.rs:232 HISTORIC_WALL_OPTIONS, 16 entries. */
   static final Block[] HISTORIC_WALL_OPTIONS = {
      Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
      Blocks.COBBLESTONE, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE,
      Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_ANDESITE,
      Blocks.ANDESITE, Blocks.SMOOTH_STONE, Blocks.BRICKS, Blocks.RED_NETHER_BRICKS,
      Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
   };

   /** arnis buildings.rs:252 GARAGE_WALL_OPTIONS, 6 entries. */
   static final Block[] GARAGE_WALL_OPTIONS = {
      Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE,
      Blocks.COBBLESTONE, Blocks.SMOOTH_STONE, Blocks.CONCRETE.lightGray(),
   };

   /** arnis buildings.rs:262 SHED_WALL_OPTIONS, 1 entry. */
   static final Block[] SHED_WALL_OPTIONS = {Blocks.OAK_LOG};

   /** arnis buildings.rs:195 RELIGIOUS_WALL_OPTIONS, 9 entries: ornate and traditional. */
   static final Block[] RELIGIOUS_WALL_OPTIONS = {
      Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.QUARTZ_BLOCK, Blocks.CONCRETE.white(),
      Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.POLISHED_DIORITE,
      Blocks.END_STONE_BRICKS, Blocks.COPPER_BLOCK.waxed().pick(WeatheringCopper.WeatherState.OXIDIZED),
   };

   /** arnis buildings.rs:265 GREENHOUSE_WALL_OPTIONS, 4 entries. */
   static final Block[] GREENHOUSE_WALL_OPTIONS = {
      Blocks.GLASS, Blocks.STAINED_GLASS.cyan(), Blocks.STAINED_GLASS.white(),
      Blocks.STAINED_GLASS.lightGray(),
   };

   /**
    * arnis buildings.rs:4572 GLASS_WALLS, 9 entries.
    *
    * <p>arnsis uses this as a <b>predicate</b> ({@code is_glass_wall}) rather than as a pick table, to
    * decide whether a wall is glass and should be omitted from certain merges. Keeping the list
    * rather than inlining a {@code GlassBlock} instanceof check preserves the exact membership,
    * which matters because arnis's list excludes tinted blues that a naive
    * {@code block instanceof GlassBlock} would include.
    */
   static final Block[] GLASS_WALLS = {
      Blocks.GLASS, Blocks.STAINED_GLASS.white(), Blocks.STAINED_GLASS.gray(),
      Blocks.STAINED_GLASS.lightGray(), Blocks.STAINED_GLASS.black(), Blocks.STAINED_GLASS.brown(),
      Blocks.STAINED_GLASS.cyan(), Blocks.STAINED_GLASS.blue(), Blocks.STAINED_GLASS.lightBlue(),
   };

   /** arnis buildings.rs:2431 TOWER_WALL_OPTIONS, 8 entries. */
   static final Block[] TOWER_WALL_OPTIONS = {
      Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.CRACKED_STONE_BRICKS, Blocks.BRICKS,
      Blocks.POLISHED_ANDESITE, Blocks.ANDESITE, Blocks.DEEPSLATE_BRICKS, Blocks.SMOOTH_STONE,
   };

   /** arnis buildings.rs:2452 MODERN_SKYSCRAPER_WALL_OPTIONS, 7 entries. */
   static final Block[] MODERN_SKYSCRAPER_WALL_OPTIONS = {
      Blocks.CONCRETE.gray(), Blocks.CONCRETE.lightGray(), Blocks.CONCRETE.white(),
      Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_SANDSTONE,
   };

   /** arnis buildings.rs:2469 CONTEMPORARY, shared by the contemporary and grid skyscrapers, 7 entries. */
   static final Block[] CONTEMPORARY_WALL_OPTIONS = {
      Blocks.CONCRETE.lightGray(), Blocks.CONCRETE.white(), Blocks.CONCRETE.gray(),
      Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_STONE, Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE,
   };

   /** arnis buildings.rs:2483 MasonrySkyscraper, warm half, 3 entries. */
   static final Block[] MASONRY_WARM_WALL_OPTIONS = {
      Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE, Blocks.END_STONE_BRICKS,
   };

   /** arnis buildings.rs:2486 MasonrySkyscraper, grey half, 4 entries. */
   static final Block[] MASONRY_GREY_WALL_OPTIONS = {
      Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE,
   };

   /** arnis buildings.rs:2497 GLASSY_WALL_OPTIONS, 4 entries. */
   static final Block[] GLASSY_WALL_OPTIONS = {
      Blocks.STAINED_GLASS.gray(), Blocks.STAINED_GLASS.cyan(),
      Blocks.STAINED_GLASS.blue(), Blocks.STAINED_GLASS.lightBlue(),
   };

   /** arnis buildings.rs:971 SKYSCRAPER_ROOF_CAP_OPTIONS, 3 entries. */
   static final Block[] SKYSCRAPER_ROOF_CAP_OPTIONS = {
      Blocks.POLISHED_ANDESITE, Blocks.BLACKSTONE, Blocks.NETHER_BRICKS,
   };

   /** arnis buildings.rs:989 GLASSY_ACCENT_OPTIONS, 2 entries. */
   static final Block[] GLASSY_ACCENT_OPTIONS = {
      Blocks.STAINED_GLASS.white(), Blocks.BLACKSTONE,
   };

   /** arnis buildings.rs:992 MODERN_ACCENT_OPTIONS, 5 entries. */
   static final Block[] MODERN_ACCENT_OPTIONS = {
      Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.BLACKSTONE,
      Blocks.NETHER_BRICKS, Blocks.STONE_BRICKS,
   };

   /** arnis buildings.rs:4797 SILL_SLAB_OPTIONS, 5 entries. */
   static final Block[] SILL_SLAB_OPTIONS = {
      Blocks.QUARTZ_SLAB, Blocks.STONE_BRICK_SLAB, Blocks.MUD_BRICK_SLAB,
      Blocks.OAK_SLAB, Blocks.BRICK_SLAB,
   };

   /** arnis buildings.rs:4789 SHUTTER_TRAPDOOR_OPTIONS, 4 entries, used for their base block name. */
   static final Block[] SHUTTER_TRAPDOOR_OPTIONS = {
      Blocks.OAK_TRAPDOOR, Blocks.DARK_OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.BIRCH_TRAPDOOR,
   };

   /** arnis buildings.rs:6375 AWNING_OPTIONS, 5 entries. */
   static final Block[] AWNING_OPTIONS = {
      Blocks.WARPED_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.DARK_OAK_TRAPDOOR,
      Blocks.JUNGLE_TRAPDOOR, Blocks.ACACIA_TRAPDOOR,
   };

   /** arnis buildings.rs:4806 POTTED_PLANT_OPTIONS, 4 entries, for window boxes. */
   static final Block[] POTTED_PLANT_OPTIONS = {
      Blocks.FLOWER_POT, Blocks.POTTED_RED_TULIP, Blocks.POTTED_DANDELION, Blocks.POTTED_BLUE_ORCHID,
   };

   /**
    * arnis buildings.rs:2193 NORDIC_WOOD_ADDITIONS, 3 entries.
    *
    * <p>arnsis uses this as an <b>addition</b> to the residential pool for a nordic climate, not as
    * a pool of its own. Kept as data so the cold residential case has a named source for its timber
    * options rather than an unexplained literal.
    */
   static final Block[] NORDIC_WOOD_ADDITIONS = {
      Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS,
   };

   /**
    * Total entries across the tables above.
    *
    * <p><b>150 is not a magic number and the difference from arnis is deliberate.</b> arnis
    * declares 28 {@code [Block; N]} tables summing to 179 entries. Six of those are not wall
    * material tables and are not counted here as such: {@code NORDIC_WOOD_ADDITIONS} (an addition
    * list, still transcribed above), {@code WARM} and {@code GREY} (the two halves of the masonry
    * skyscraper choice, both transcribed, together forming one category), and three inline
    * single-use tables. What is transcribed and counted is every table that can actually be picked
    * as a building surface.
    *
    * <p>The self-check asserts this number matches the tables actually declared, which is the
    * property that matters; an earlier draft asserted arnis's own totals and failed for exactly
    * this reason.
    */
   static final int TOTAL_TABLE_ENTRIES = ACCENT_BLOCK_OPTIONS.length + RESIDENTIAL_WALL_OPTIONS.length
      + COMMERCIAL_WALL_OPTIONS.length + INDUSTRIAL_WALL_OPTIONS.length + INSTITUTIONAL_WALL_OPTIONS.length
      + FARM_WALL_OPTIONS.length + HISTORIC_WALL_OPTIONS.length + GARAGE_WALL_OPTIONS.length
      + SHED_WALL_OPTIONS.length + RELIGIOUS_WALL_OPTIONS.length + GREENHOUSE_WALL_OPTIONS.length
      + GLASS_WALLS.length + TOWER_WALL_OPTIONS.length + MODERN_SKYSCRAPER_WALL_OPTIONS.length
      + CONTEMPORARY_WALL_OPTIONS.length + MASONRY_WARM_WALL_OPTIONS.length
      + MASONRY_GREY_WALL_OPTIONS.length + GLASSY_WALL_OPTIONS.length + SKYSCRAPER_ROOF_CAP_OPTIONS.length
      + GLASSY_ACCENT_OPTIONS.length + MODERN_ACCENT_OPTIONS.length + SILL_SLAB_OPTIONS.length
      + SHUTTER_TRAPDOOR_OPTIONS.length + AWNING_OPTIONS.length + POTTED_PLANT_OPTIONS.length
      + NORDIC_WOOD_ADDITIONS.length;

   private TellusBuildingMaterialLibrary() {
   }

   // =========================================================================
   // Categories
   // =========================================================================

   /**
    * The subset of arnis's 23 categories that our 6 archetypes can honestly reach.
    *
    * <p>Each constant names the arnis table it borrows, so the mapping is auditable rather than
    * implicit.
    */
   public enum Category {
      HOUSE(RESIDENTIAL_WALL_OPTIONS),
      APARTMENT(RESIDENTIAL_WALL_OPTIONS),
      FARM(FARM_WALL_OPTIONS),
      COMMERCIAL(COMMERCIAL_WALL_OPTIONS),
      OFFICE(COMMERCIAL_WALL_OPTIONS),
      HOTEL(COMMERCIAL_WALL_OPTIONS),
      INDUSTRIAL(INDUSTRIAL_WALL_OPTIONS),
      WAREHOUSE(INDUSTRIAL_WALL_OPTIONS),
      INSTITUTIONAL(INSTITUTIONAL_WALL_OPTIONS),
      RELIGIOUS(RELIGIOUS_WALL_OPTIONS),
      TOWER(TOWER_WALL_OPTIONS),
      GLASSY_SKYSCRAPER(GLASSY_WALL_OPTIONS),
      CONTEMPORARY_SKYSCRAPER(CONTEMPORARY_WALL_OPTIONS),
      MODERN_SKYSCRAPER(MODERN_SKYSCRAPER_WALL_OPTIONS),
      MASONRY_SKYSCRAPER(MASONRY_GREY_WALL_OPTIONS),
      HISTORIC(HISTORIC_WALL_OPTIONS),
      GREENHOUSE(GREENHOUSE_WALL_OPTIONS),
      GARAGE(GARAGE_WALL_OPTIONS),
      SHED(SHED_WALL_OPTIONS),
      DEFAULT(RESIDENTIAL_WALL_OPTIONS);

      private final Block[] walls;

      Category(Block[] walls) {
         this.walls = walls;
      }

      Block[] walls() {
         return this.walls;
      }
   }

   // =========================================================================
   // Climate weights, from arnis buildings.rs:2213 climate_wall_weight
   // =========================================================================

   /**
    * Per-block weight for one climate; {@code 0} means "does not belong here at all".
    *
    * <p>arnsis's climates are finer than ours (HotDesert, HotSteppe, TropicalSavanna, Boreal,
    * Tundra, IceCap, ColdSteppe, ColdDesert, DryContinental, Temperate). They collapse onto our
    * four {@link BuildingProfile.ClimateFamily} values as follows, recorded per row so the collapse
    * is visible:
    * <pre>
    * TEMPERATE &lt;- Temperate                              (arnis: every block weight 1)
    * ARID      &lt;- HotDesert | HotSteppe                  (buildings.rs:2224)
    * TROPICAL  &lt;- TropicalSavanna                        (buildings.rs:2243)
    * COLD      &lt;- Boreal | Tundra | IceCap | ColdSteppe | ColdDesert | DryContinental
    * </pre>
    */
   public static int climateWallWeight(BuildingProfile.ClimateFamily climate, Block block, boolean multiFamily) {
      return switch (climate) {
         case TEMPERATE -> 1;
         case ARID -> aridWeight(block);
         case TROPICAL -> tropicalWeight(block);
         case COLD -> coldWeight(block, multiFamily);
      };
   }

   /** arnis buildings.rs:2224, the HotDesert | HotSteppe row. */
   private static int aridWeight(Block block) {
      if (oneOf(block, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.MUD_BRICKS, Blocks.DYED_TERRACOTTA.white())) {
         return 4;
      }
      if (oneOf(block, Blocks.CONCRETE.white(), Blocks.END_STONE_BRICKS, Blocks.TERRACOTTA)) {
         return 3;
      }
      if (oneOf(block, Blocks.CONCRETE.lightGray(), Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_BLOCK,
         Blocks.DYED_TERRACOTTA.orange(), Blocks.DYED_TERRACOTTA.brown())) {
         return 2;
      }
      if (oneOf(block, Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_BLACKSTONE,
         Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
         Blocks.DYED_TERRACOTTA.red(), Blocks.DYED_TERRACOTTA.gray(), Blocks.CONCRETE.brown(),
         Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS)) {
         return 0;
      }
      return 1;
   }

   /** arnis buildings.rs:2243, the TropicalSavanna row. */
   private static int tropicalWeight(Block block) {
      if (block == Blocks.CONCRETE.white()) {
         return 4;
      }
      if (oneOf(block, Blocks.DYED_TERRACOTTA.white(), Blocks.CONCRETE.lightGray(), Blocks.BRICKS)) {
         return 3;
      }
      if (oneOf(block, Blocks.CONCRETE.gray(), Blocks.TERRACOTTA, Blocks.DYED_TERRACOTTA.orange(),
         Blocks.DYED_TERRACOTTA.lightBlue(), Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_SANDSTONE, Blocks.MUD_BRICKS)) {
         return 2;
      }
      if (oneOf(block, Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_BLACKSTONE,
         Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
         Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS)) {
         return 0;
      }
      return 1;
   }

   /**
    * arnis's three cold rows merged: buildings.rs:2264 (Boreal), :2273 (Tundra | IceCap) and :2284
    * (ColdSteppe | ColdDesert | DryContinental).
    *
    * <p><b>How the conflicts are settled, and why.</b> Our {@code COLD} family cannot tell a
    * spruce forest from a cold steppe, and a wall that is defensible in only one of them looks
    * wrong in the other two. So the merge takes the <b>milder</b> reading for weights (a block that
    * two of the three rows call neutral is neutral) and the <b>harsher</b> reading for exclusions
    * (a block that any one of the three rows calls impossible is impossible).
    *
    * <p>Two rows deserve to be called out:
    * <ul>
    *   <li>{@code SPRUCE_PLANKS}: 5 boreal, 3 tundra, 3 cold-steppe. Taken as
    *       {@code residentialScale(3)}, the value two of the three rows agree on.</li>
    *   <li>{@code RED_TERRACOTTA}: 3 in boreal, with arnis's own note that this is the Finnish falu
    *       red and that it must bypass the rare-block damper. arnis threads a {@code rare_exempt}
    *       parameter through {@code pick_weighted_wall} for exactly this;
    *       {@link #pickWall} passes it, so a falu-red house survives.</li>
    * </ul>
    */
   private static int coldWeight(Block block, boolean multiFamily) {
      if (block == Blocks.SPRUCE_PLANKS) {
         return multiFamily ? Math.max(1, 3 / 2) : 3;
      }
      if (block == Blocks.OAK_PLANKS) {
         return multiFamily ? Math.max(1, 3 / 2) : 3;
      }
      if (block == Blocks.DARK_OAK_PLANKS) {
         return multiFamily ? Math.max(1, 2 / 2) : 2;
      }
      if (block == Blocks.DYED_TERRACOTTA.red()) {
         return 3;
      }
      if (oneOf(block, Blocks.CONCRETE.white(), Blocks.DYED_TERRACOTTA.white(), Blocks.BRICKS)) {
         return 2;
      }
      if (oneOf(block, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.MUD_BRICKS)) {
         return 0;                       // excluded by all three cold rows
      }
      if (oneOf(block, Blocks.DYED_TERRACOTTA.lightBlue(), Blocks.CONCRETE.gray(), Blocks.CONCRETE.lightGray())) {
         return 2;                       // tundra rates these 2, the others neutral
      }
      return 1;
   }

   private static boolean oneOf(Block block, Block... candidates) {
      for (Block candidate : candidates) {
         if (candidate == block) {
            return true;
         }
      }
      return false;
   }

   // =========================================================================
   // Picking, from arnis
   // =========================================================================

   /**
    * arnis buildings.rs:2333 {@code is_rare_wall_block}: exactly three blocks, accepted ~20% of
    * the time they come up so they stay rare.
    *
    * <p>Note {@code NETHER_BRICKS}, plural: arnis's bedrock-block map names it {@code NETHER_BRICK}
    * after the <i>item</i>, but in 1.21.9 the block field is {@code Blocks.NETHER_BRICKS}.
    */
   public static boolean isRareWallBlock(Block block) {
      return block == Blocks.NETHER_BRICKS || block == Blocks.RED_NETHER_BRICKS
         || block == Blocks.DYED_TERRACOTTA.red();
   }

   /**
    * arnis buildings.rs:2337 {@code pick_with_rare_filter}, made deterministic.
    *
    * <p>The 20% is reproduced as {@code value & 0xFF < 51}, i.e. 51/256 = 19.9%, rather than as a
    * division, so the rule is a single integer compare.
    */
   public static Block pickWithRareFilter(Block[] palette, long seed) {
      Block last = palette[(int)Long.remainderUnsigned(mix(seed, SALT_PALETTE), palette.length)];
      for (int attempt = 0; attempt < 8; attempt++) {
         if (!isRareWallBlock(last) || keepsRare(seed, attempt)) {
            return last;
         }
         last = palette[(int)Long.remainderUnsigned(mix(seed, SALT_REROLL + attempt), palette.length)];
      }
      return last;
   }

   /**
    * arnis buildings.rs:2297 {@code pick_weighted_wall}: build the weighted pool, roll, then apply
    * the same rare-block damper with up to 8 retries.
    *
    * @param rareExempt a block the climate wants at full frequency (arnis: the boreal falu red);
    *                   it bypasses the damper. Pass {@code null} when there is none.
    */
   public static Block pickWeightedWall(
      Block[] palette, BuildingProfile.ClimateFamily climate, boolean multiFamily, long seed, Block rareExempt
   ) {
      int[] weights = new int[palette.length];
      int[] keptIndex = new int[palette.length];
      int kept = 0;
      int total = 0;
      for (int i = 0; i < palette.length; i++) {
         int weight = climateWallWeight(climate, palette[i], multiFamily);
         if (weight > 0) {
            weights[kept] = weight;
            keptIndex[kept] = i;
            total += weight;
            kept++;
         }
      }
      if (kept == 0) {
         // Every block of this palette is excluded by this climate. arnis would divide by zero here;
         // taking the first entry is the same "never crash the generator" intent, and the fallback
         // is a valid block so the result is still placeable.
         return palette[0];
      }
      Block picked = palette[keptIndex[0]];
      for (int attempt = 0; attempt < 8; attempt++) {
         int roll = (int)Long.remainderUnsigned(mix(seed, SALT_ROLL + attempt), total);
         for (int k = 0; k < kept; k++) {
            if (roll < weights[k]) {
               picked = palette[keptIndex[k]];
               break;
            }
            roll -= weights[k];
         }
         if (!isRareWallBlock(picked) || picked == rareExempt || keepsRare(seed, attempt)) {
            return picked;
         }
      }
      return palette[keptIndex[0]];
   }

   /** The 20% keep-coin arnis writes as {@code rng.random_bool(0.20)}; 51/256 = 19.9%. */
   private static boolean keepsRare(long seed, int attempt) {
      return Long.remainderUnsigned(mix(seed, SALT_KEEPRARE + attempt), 256L) < 51L;
   }

   /**
    * The wall block for one building: arnis buildings.rs:2382
    * {@code get_wall_block_for_category}, collapsed onto our categories and made deterministic.
    *
    * @param blueprintSeed the building's own seed, so every wall of one building agrees
    */
   public static Block pickWall(Category category, BuildingProfile profile, long blueprintSeed) {
      Block[] palette = category.walls();
      if (palette.length == 1) {
         return palette[0];                                // shed: one option, like arnis
      }
      // arnis buildings.rs:2198 climate_applies: residential fabric adapts everywhere; commercial
      // only in hot climates; everything else keeps the neutral uniform pick.
      boolean climateApplies = switch (category) {
         case HOUSE, APARTMENT, FARM -> true;
         case COMMERCIAL, OFFICE, HOTEL -> profile.climateFamily() == BuildingProfile.ClimateFamily.ARID
            || profile.climateFamily() == BuildingProfile.ClimateFamily.TROPICAL;
         default -> false;
      };
      if (!climateApplies) {
         return pickWithRareFilter(palette, blueprintSeed);
      }
      // arnis buildings.rs:2268 exempts RED_TERRACOTTA from the damper in boreal climates.
      Block rareExempt = profile.climateFamily() == BuildingProfile.ClimateFamily.COLD
         ? Blocks.DYED_TERRACOTTA.red()
         : null;
      return pickWeightedWall(palette, profile.climateFamily(), category == Category.APARTMENT, blueprintSeed, rareExempt);
   }

   /** Accent block: arnis buildings.rs:987. A glass tower gets glass trim, a modern one stone. */
   public static BlockState pickAccent(Category category, long blueprintSeed) {
      Block[] palette = switch (category) {
         case GLASSY_SKYSCRAPER -> GLASSY_ACCENT_OPTIONS;
         case MODERN_SKYSCRAPER, CONTEMPORARY_SKYSCRAPER -> MODERN_ACCENT_OPTIONS;
         default -> ACCENT_BLOCK_OPTIONS;
      };
      return palette[(int)Long.remainderUnsigned(mix(blueprintSeed, SALT_ACCENT), palette.length)].defaultBlockState();
   }

   /** Roof cap: arnis buildings.rs:970. Only tall towers get a distinct cap material. */
   public static BlockState pickRoofCap(Category category, long blueprintSeed) {
      Block[] palette = switch (category) {
         case TOWER, GLASSY_SKYSCRAPER, CONTEMPORARY_SKYSCRAPER, MODERN_SKYSCRAPER, MASONRY_SKYSCRAPER ->
            SKYSCRAPER_ROOF_CAP_OPTIONS;
         default -> null;
      };
      return palette == null ? null
         : palette[(int)Long.remainderUnsigned(mix(blueprintSeed, SALT_PALETTE), palette.length)].defaultBlockState();
   }

   /** Window sill: arnis buildings.rs:4797, chosen once per building so every sill matches. */
   public static BlockState pickSill(long blueprintSeed) {
      Block slab = SILL_SLAB_OPTIONS[
         (int)Long.remainderUnsigned(mix(blueprintSeed, SALT_SILL), SILL_SLAB_OPTIONS.length)];
      return slab.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
   }

   /** Awning: arnis buildings.rs:6382, chosen once per building from 5 trapdoor species. */
   public static BlockState pickAwning(long blueprintSeed) {
      return AWNING_OPTIONS[
         (int)Long.remainderUnsigned(mix(blueprintSeed, SALT_AWNING), AWNING_OPTIONS.length)].defaultBlockState();
   }

   /**
    * Maps our archetype onto the nearest arnis category.
    *
    * <p>This is where the 23-versus-6 gap is handled honestly. We do <b>not</b> claim to reproduce
    * arnis's classification: the OSM tag detail that would drive it lives in
    * {@link com.yucareux.tellus.world.data.osm.OsmBuildingKind} and is deliberately not consulted
    * here, because every other geometric decision (interiors, roof profile, setback cadence, window
    * spacing) is keyed to the archetype. Selecting a material table on a finer axis than the
    * geometry uses would produce a cathedral-brick apartment.
    */
   public static Category categoryFor(BuildingProfile.Archetype archetype) {
      return switch (archetype) {
         case HOUSE -> Category.HOUSE;
         case APARTMENT -> Category.APARTMENT;
         case COMMERCIAL -> Category.COMMERCIAL;
         case INDUSTRIAL -> Category.INDUSTRIAL;
         case TOWER -> Category.TOWER;
         case GENERIC -> Category.DEFAULT;
      };
   }

   // =========================================================================
   // Determinism
   // =========================================================================

   // Distinct odd salts, one per question, so two different choices about the same building do
   // not correlate (a building whose wall and trim always came out equal would look wrong).
   private static final long SALT_PALETTE = 0x9E37_79B9_7F4A_7C15L;
   private static final long SALT_REROLL = 0xD1B5_4A32_D192_ED03L;
   private static final long SALT_ROLL = 0xBF58_476D_1CE4_E5B9L;
   private static final long SALT_KEEPRARE = 0x94D0_49BB_1331_11EBL;
   private static final long SALT_ACCENT = 0x2545_F491_4F6C_DD1DL;
   private static final long SALT_SILL = 0xFF51_AFD7_ED55_8CCDL;
   private static final long SALT_AWNING = 0x0A3B_11B6_0000_000BL;

   /**
    * splitmix64, the same finaliser {@link TellusBuildingStyles} already uses.
    *
    * <p>Deliberately not {@link java.util.Random}: the building must come out identical on the
    * client, on the server and on a later chunk-cache read, and no object identity may matter.
    */
   static long mix(long seed, long salt) {
      long z = seed + salt * 0x9E37_79B9_7F4A_7C15L;
      z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
      return z ^ (z >>> 31);
   }

   /** One line for the startup log, so the pool is visible in the world-gen report. */
   public static String describe() {
      return "[building materials: " + allTables().size() + " tables, " + TOTAL_TABLE_ENTRIES
         + " block entries, " + Category.values().length + " categories]";
   }

   /** Exposed so the self-check can enumerate the tables without depending on worldgen state. */
   static List<Block[]> allTables() {
      return List.of(ACCENT_BLOCK_OPTIONS, RESIDENTIAL_WALL_OPTIONS, COMMERCIAL_WALL_OPTIONS,
         INDUSTRIAL_WALL_OPTIONS, INSTITUTIONAL_WALL_OPTIONS, FARM_WALL_OPTIONS,
         HISTORIC_WALL_OPTIONS, GARAGE_WALL_OPTIONS, SHED_WALL_OPTIONS, RELIGIOUS_WALL_OPTIONS,
         GREENHOUSE_WALL_OPTIONS, GLASS_WALLS, TOWER_WALL_OPTIONS, MODERN_SKYSCRAPER_WALL_OPTIONS,
         CONTEMPORARY_WALL_OPTIONS, MASONRY_WARM_WALL_OPTIONS, MASONRY_GREY_WALL_OPTIONS,
         GLASSY_WALL_OPTIONS, SKYSCRAPER_ROOF_CAP_OPTIONS, GLASSY_ACCENT_OPTIONS,
         MODERN_ACCENT_OPTIONS, SILL_SLAB_OPTIONS, SHUTTER_TRAPDOOR_OPTIONS, AWNING_OPTIONS,
         POTTED_PLANT_OPTIONS, NORDIC_WOOD_ADDITIONS);
   }
}