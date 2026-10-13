package com.yucareux.tellus.worldgen.building;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

public final class TellusBuildingMaterials {
   private static final BlockState BUILDING_STATE = Blocks.CONCRETE.gray().defaultBlockState();
   private static final BlockState BUILDING_WINDOW_STATE = Blocks.STAINED_GLASS.lightGray().defaultBlockState();
   private static final BlockState BUILDING_TOWER_WINDOW_STATE = Blocks.STAINED_GLASS.lightBlue().defaultBlockState();
   private static final BlockState BUILDING_ROOF_STATE = Blocks.CONCRETE.gray().defaultBlockState();
   private static final BlockState BUILDING_SLATE_ROOF_STATE = Blocks.DEEPSLATE_TILES.defaultBlockState();
   private static final BlockState BUILDING_CLAY_TILE_ROOF_STATE = Blocks.BRICKS.defaultBlockState();
   private static final BlockState BUILDING_STONE_ROOF_STATE = Blocks.STONE_BRICKS.defaultBlockState();
   private static final BlockState BUILDING_RESIDENTIAL_WALL_STATE = Blocks.DYED_TERRACOTTA.white().defaultBlockState();
   private static final BlockState BUILDING_ARID_WALL_STATE = Blocks.SANDSTONE.defaultBlockState();
   private static final BlockState BUILDING_SANDSTONE_WALL_STATE = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
   private static final BlockState BUILDING_COLD_WALL_STATE = Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState();
   private static final BlockState BUILDING_TROPICAL_WALL_STATE = Blocks.BIRCH_PLANKS.defaultBlockState();
   private static final BlockState BUILDING_BRICK_WALL_STATE = Blocks.BRICKS.defaultBlockState();
   private static final BlockState BUILDING_PALE_STONE_WALL_STATE = Blocks.CALCITE.defaultBlockState();
   private static final BlockState BUILDING_COMMERCIAL_WALL_STATE = Blocks.CONCRETE.lightGray().defaultBlockState();
   private static final BlockState BUILDING_INDUSTRIAL_WALL_STATE = Blocks.ANDESITE.defaultBlockState();
   private static final BlockState BUILDING_TOWER_WALL_STATE = Blocks.DYED_TERRACOTTA.cyan().defaultBlockState();
   private static final BlockState BUILDING_TRIM_STATE = Blocks.POLISHED_ANDESITE.defaultBlockState();
   private static final BlockState BUILDING_WHITE_TRIM_STATE = Blocks.SMOOTH_QUARTZ.defaultBlockState();
   private static final BlockState BUILDING_SANDSTONE_TRIM_STATE = Blocks.CUT_SANDSTONE.defaultBlockState();
   private static final BlockState BUILDING_BRICK_TRIM_STATE = Blocks.STONE_BRICKS.defaultBlockState();
   private static final BlockState BUILDING_FLOOR_STATE = Blocks.POLISHED_ANDESITE.defaultBlockState();
   private static final BlockState BUILDING_RESIDENTIAL_FLOOR_STATE = Blocks.OAK_PLANKS.defaultBlockState();
   private static final BlockState BUILDING_PARTITION_STATE = Blocks.SMOOTH_STONE.defaultBlockState();
   private static final BlockState BUILDING_STAIR_STATE = Blocks.OAK_STAIRS.defaultBlockState();
   private static final BlockState BUILDING_COMMERCIAL_STAIR_STATE = Blocks.STONE_BRICK_STAIRS.defaultBlockState();
   private static final BlockState BUILDING_SLAB_STATE = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
   private static final BlockState BUILDING_RESIDENTIAL_SLAB_STATE = Blocks.OAK_SLAB.defaultBlockState();
   private static final BlockState BUILDING_LIGHT_STATE = Blocks.SEA_LANTERN.defaultBlockState();

   /**
    * 2026-10-06: when the category-isolated library is active (the default), the wall block comes
    * from the table pool rather than from the fixed constant above.
    *
    * <p><b>What changed and why.</b> The old palette resolved <b>one</b> wall block per
    * {@code (archetype x climate)} pair, so every residential building in a temperate city was the
    * same white terracotta, and the whole switch could only answer "which of five house styles",
    * never "which of 29 residential materials". arnis has 149 blocks across 21 category-isolated
    * tables with per-climate weights (Apache-2.0; see {@code THIRD_PARTY_NOTICES.md} and
    * {@link TellusBuildingMaterialLibrary}); that pool is now what the wall is drawn from.
    *
    * <p>The constants above are still the fallback when the pool cannot be used — see
    * {@link #legacyWallFallback} — so a missing entry degrades to exactly the old behaviour rather
    * than to an exception.
    */
   private static final boolean MATERIAL_LIBRARY_ENABLED =
      Boolean.parseBoolean(System.getProperty("tellus.building.materialLibrary", "true"));

   private TellusBuildingMaterials() {
   }

   public static TellusBuildingMaterials.BuildingMaterialPalette resolvePalette(BuildingBlueprint blueprint) {
      return resolvePalette(blueprint.profile(), blueprint.blueprintSeed());
   }

   public static TellusBuildingMaterials.BuildingMaterialPalette resolvePalette(BuildingProfile profile, long blueprintSeed) {
      BlockState wall = legacyWallFallback(profile);
      BlockState trim = BUILDING_TRIM_STATE;
      BlockState roof = BUILDING_ROOF_STATE;
      if (profile.archetype() == BuildingProfile.Archetype.HOUSE) {
         TellusBuildingStyles.HouseStyle houseStyle = TellusBuildingStyles.resolveHouseStyle(profile, blueprintSeed);
         wall = switch (houseStyle) {
            case WHITE_SLATE -> BUILDING_RESIDENTIAL_WALL_STATE;
            case WARM_CLAY -> BUILDING_SANDSTONE_WALL_STATE;
            case GRAY_CHARCOAL -> BUILDING_COLD_WALL_STATE;
            case BRICK_SLATE, PALE_STONE -> BUILDING_BRICK_WALL_STATE;
         };
         trim = switch (houseStyle) {
            case WHITE_SLATE -> BUILDING_WHITE_TRIM_STATE;
            case WARM_CLAY -> BUILDING_SANDSTONE_TRIM_STATE;
            case GRAY_CHARCOAL -> BUILDING_TRIM_STATE;
            case BRICK_SLATE, PALE_STONE -> BUILDING_BRICK_TRIM_STATE;
         };
         roof = switch (houseStyle) {
            case WHITE_SLATE, BRICK_SLATE -> BUILDING_SLATE_ROOF_STATE;
            case WARM_CLAY -> BUILDING_CLAY_TILE_ROOF_STATE;
            case GRAY_CHARCOAL -> BUILDING_ROOF_STATE;
            case PALE_STONE -> BUILDING_STONE_ROOF_STATE;
         };
      }

      BlockState floor = switch (profile.archetype()) {
         case HOUSE, APARTMENT -> BUILDING_RESIDENTIAL_FLOOR_STATE;
         default -> BUILDING_FLOOR_STATE;
      };
      BlockState stair = switch (profile.archetype()) {
         case HOUSE, APARTMENT -> BUILDING_STAIR_STATE;
         default -> BUILDING_COMMERCIAL_STAIR_STATE;
      };
      BlockState slab = switch (profile.archetype()) {
         case HOUSE, APARTMENT -> BUILDING_RESIDENTIAL_SLAB_STATE.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
         default -> BUILDING_SLAB_STATE.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
      };
      BlockState window = profile.archetype() == BuildingProfile.Archetype.TOWER || profile.archetype() == BuildingProfile.Archetype.COMMERCIAL
         ? BUILDING_TOWER_WINDOW_STATE
         : BUILDING_WINDOW_STATE;

      BlockState sill = null;
      BlockState awning = null;
      if (MATERIAL_LIBRARY_ENABLED) {
         // 2026-10-06: the wall comes from the arnis category pool; the accent replaces the trim
         // constant for the same building (arnis picks one accent per building, not per surface),
         // and the sill / awning are also once-per-building so every window of a facade matches.
         TellusBuildingMaterialLibrary.Category category = TellusBuildingMaterialLibrary.categoryFor(profile.archetype());
         BlockState picked = TellusBuildingMaterialLibrary.pickWall(category, profile, blueprintSeed).defaultBlockState();
         if (picked != null) {
            wall = picked;
         }
         BlockState accent = TellusBuildingMaterialLibrary.pickAccent(category, blueprintSeed);
         if (accent != null) {
            trim = accent;
         }
         sill = TellusBuildingMaterialLibrary.pickSill(blueprintSeed);
         awning = profile.archetype() == BuildingProfile.Archetype.COMMERCIAL
            ? TellusBuildingMaterialLibrary.pickAwning(blueprintSeed)
            : null;
      }

      return new TellusBuildingMaterials.BuildingMaterialPalette(
         wall, trim, roof, window, floor, BUILDING_PARTITION_STATE, stair, slab, BUILDING_LIGHT_STATE, sill, awning
      );
   }

   /** The pre-2026-10-06 behaviour, kept as the fallback so a missing pool entry degrades instead of throwing. */
   private static BlockState legacyWallFallback(BuildingProfile profile) {
      return switch (profile.archetype()) {
         case HOUSE, APARTMENT -> switch (profile.climateFamily()) {
            case COLD -> BUILDING_COLD_WALL_STATE;
            case ARID -> BUILDING_ARID_WALL_STATE;
            case TROPICAL -> BUILDING_TROPICAL_WALL_STATE;
            case TEMPERATE -> BUILDING_RESIDENTIAL_WALL_STATE;
         };
         case COMMERCIAL -> BUILDING_COMMERCIAL_WALL_STATE;
         case INDUSTRIAL -> BUILDING_INDUSTRIAL_WALL_STATE;
         case TOWER -> BUILDING_TOWER_WALL_STATE;
         case GENERIC -> BUILDING_STATE;
      };
   }

   public static BlockState resolveLodFacadeBlock(
      BuildingBlueprint blueprint, TellusBuildingMaterials.BuildingMaterialPalette palette, int boundaryDistance, int floorIndex
   ) {
      if (!blueprint.isFacadeCell(boundaryDistance, floorIndex)) {
         return palette.wall();
      }

      return switch (blueprint.profile().archetype()) {
         case TOWER, COMMERCIAL -> palette.window();
         case APARTMENT -> floorIndex > 0 ? palette.window() : palette.wall();
         case HOUSE, INDUSTRIAL, GENERIC -> palette.wall();
      };
   }

   public static BlockState resolveLodRoofBlock(TellusBuildingMaterials.BuildingMaterialPalette palette, boolean roofEdge) {
      return roofEdge ? palette.trim() : palette.roof();
   }

   public record BuildingMaterialPalette(
      BlockState wall,
      BlockState trim,
      BlockState roof,
      BlockState window,
      BlockState floor,
      BlockState partition,
      BlockState stair,
      BlockState slab,
      BlockState light,
      /** 2026-10-06: window sill slab, chosen once per building (arnis SILL_SLAB_OPTIONS). Null when the library is off. */
      BlockState sill,
      /** 2026-10-06: awning material, commercial only (arnis AWNING_OPTIONS). Null otherwise. */
      BlockState awning
   ) {
   }
}
