package com.yucareux.tellus.worldgen.building;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;

public final class TellusBuildingLighting {
   public static final byte BUILDING_LIGHT_LEVEL = 15;

   private TellusBuildingLighting() {
   }

   public static boolean shouldPlaceInteriorLight(
      BuildingBlueprint blueprint, int boundaryDistance, int worldX, int worldZ, int floorIndex
   ) {
      FloorLightingGeometry geometry = floorGeometry(blueprint, boundaryDistance, floorIndex);
      if (!geometry.hasInterior()) {
         return false;
      }

      int localX = worldX - geometry.innerMinX();
      int localZ = worldZ - geometry.innerMinZ();
      if (localX < 0 || localX >= geometry.innerWidth() || localZ < 0 || localZ >= geometry.innerDepth()) {
         return false;
      }

      if (localX == geometry.anchorX() && localZ == geometry.anchorZ()) {
         return true;
      }

      if (shouldPlaceFacadeStripLight(blueprint, geometry, localX, localZ, floorIndex)) {
         return true;
      }

      return geometry.denseGrid()
         && Math.floorMod(localX - geometry.gridPhaseX(), geometry.gridSpacingX()) == 0
         && Math.floorMod(localZ - geometry.gridPhaseZ(), geometry.gridSpacingZ()) == 0;
   }

   public static byte resolveLodFacadeLightLevel(
      BuildingBlueprint blueprint, BlockState facadeBlock, BlockState windowBlock, int boundaryDistance, int worldX, int worldZ, int floorIndex
   ) {
      if (facadeBlock != windowBlock || !blueprint.isFacadeCell(boundaryDistance, floorIndex)) {
         return 0;
      }

      int setback = blueprint.setbackForFloor(floorIndex);
      int minWorldX = blueprint.minWorldX() + setback;
      int maxWorldX = blueprint.maxWorldX() - setback;
      int minWorldZ = blueprint.minWorldZ() + setback;
      int edgeCoord = worldX == minWorldX || worldX == maxWorldX ? worldZ - minWorldZ : worldX - minWorldX;
      int darkStride = facadeDarkStride(blueprint);
      int darkPhase = positiveFloorMod(mixFloorSeed(blueprint.blueprintSeed(), floorIndex) ^ setback * 37, darkStride);
      return Math.floorMod(edgeCoord, darkStride) == darkPhase ? 0 : BUILDING_LIGHT_LEVEL;
   }

   public static byte resolveLodFacadeLightLevel(
      BuildingBlueprint blueprint,
      BlockState facadeBlock,
      BlockState windowBlock,
      int boundaryDistance,
      int worldX,
      int worldZ,
      int floorIndex,
      int cellSize
   ) {
      if (cellSize <= 1) {
         return resolveLodFacadeLightLevel(blueprint, facadeBlock, windowBlock, boundaryDistance, worldX, worldZ, floorIndex);
      }

      if (facadeBlock != windowBlock || !blueprint.isFacadeCell(boundaryDistance, floorIndex)) {
         return 0;
      }

      int setback = blueprint.setbackForFloor(floorIndex);
      int minWorldX = blueprint.minWorldX() + setback;
      int maxWorldX = blueprint.maxWorldX() - setback;
      int minWorldZ = blueprint.minWorldZ() + setback;
      int maxWorldZ = blueprint.maxWorldZ() - setback;
      int darkStride = facadeDarkStride(blueprint);
      int darkPhase = positiveFloorMod(mixFloorSeed(blueprint.blueprintSeed(), floorIndex) ^ setback * 37, darkStride);
      int cellRadius = cellSize >> 1;
      int cellMinX = worldX - cellRadius;
      int cellMaxX = cellMinX + cellSize - 1;
      int cellMinZ = worldZ - cellRadius;
      int cellMaxZ = cellMinZ + cellSize - 1;
      boolean touchesNorthSouthFacade = cellMinZ <= minWorldZ && cellMaxZ >= minWorldZ || cellMinZ <= maxWorldZ && cellMaxZ >= maxWorldZ;
      boolean touchesWestEastFacade = cellMinX <= minWorldX && cellMaxX >= minWorldX || cellMinX <= maxWorldX && cellMaxX >= maxWorldX;
      boolean hasLitNorthSouthWindow = touchesNorthSouthFacade && hasLitFacadeInterval(cellMinX, cellMaxX, minWorldX, maxWorldX, darkStride, darkPhase);
      boolean hasLitWestEastWindow = touchesWestEastFacade && hasLitFacadeInterval(cellMinZ, cellMaxZ, minWorldZ, maxWorldZ, darkStride, darkPhase);
      return hasLitNorthSouthWindow || hasLitWestEastWindow ? BUILDING_LIGHT_LEVEL : 0;
   }

   // Put some ceiling lights directly behind facades so night windows read as lit,
   // without flooding every floor with emitters.
   private static boolean shouldPlaceFacadeStripLight(
      BuildingBlueprint blueprint, FloorLightingGeometry geometry, int localX, int localZ, int floorIndex
   ) {
      int spacing = facadeStripSpacing(blueprint);
      if (spacing <= 0) {
         return false;
      }

      boolean onWestEastStrip = localX == 0 || localX == geometry.innerWidth() - 1;
      boolean onNorthSouthStrip = localZ == 0 || localZ == geometry.innerDepth() - 1;
      if (!onWestEastStrip && !onNorthSouthStrip) {
         return false;
      }

      long floorSeed = mixFloorSeed(blueprint.blueprintSeed(), floorIndex);
      int phaseX = positiveFloorMod(floorSeed ^ geometry.innerWidth() * 31L, spacing);
      int phaseZ = positiveFloorMod(Long.rotateLeft(floorSeed, 7) ^ geometry.innerDepth() * 17L, spacing);
      boolean westEastLit = onWestEastStrip && Math.floorMod(localZ - phaseZ, spacing) == 0;
      boolean northSouthLit = onNorthSouthStrip && Math.floorMod(localX - phaseX, spacing) == 0;
      return westEastLit || northSouthLit;
   }

   private static FloorLightingGeometry floorGeometry(BuildingBlueprint blueprint, int boundaryDistance, int floorIndex) {
      int setback = blueprint.setbackForFloor(floorIndex);
      int innerMinX = blueprint.minWorldX() + setback + 1;
      int innerMaxX = blueprint.maxWorldX() - setback - 1;
      int innerMinZ = blueprint.minWorldZ() + setback + 1;
      int innerMaxZ = blueprint.maxWorldZ() - setback - 1;
      int innerWidth = innerMaxX - innerMinX + 1;
      int innerDepth = innerMaxZ - innerMinZ + 1;
      if (boundaryDistance < setback + 1 || innerWidth <= 0 || innerDepth <= 0) {
         return new FloorLightingGeometry(innerMinX, innerMinZ, innerWidth, innerDepth, 0, 0, 1, 1, 0, 0, false);
      }

      long floorSeed = mixFloorSeed(blueprint.blueprintSeed(), floorIndex);
      int centerX = innerWidth / 2;
      int centerZ = innerDepth / 2;
      int anchorSpreadX = Math.max(0, innerWidth / 6);
      int anchorSpreadZ = Math.max(0, innerDepth / 6);
      int anchorX = Mth.clamp(centerX + signedOffset(floorSeed, anchorSpreadX), 0, innerWidth - 1);
      int anchorZ = Mth.clamp(centerZ + signedOffset(Long.rotateLeft(floorSeed, 19), anchorSpreadZ), 0, innerDepth - 1);
      int gridSpacingX = switch (blueprint.profile().archetype()) {
         case HOUSE -> 5;
         case APARTMENT -> 7;
         case COMMERCIAL -> 6;
         case INDUSTRIAL -> 8;
         case TOWER -> 8;
         case GENERIC -> 7;
      };
      int gridSpacingZ = switch (blueprint.profile().archetype()) {
         case HOUSE -> 5;
         case APARTMENT -> 6;
         case COMMERCIAL -> 6;
         case INDUSTRIAL -> 7;
         case TOWER -> 8;
         case GENERIC -> 7;
      };
      int gridPhaseX = positiveFloorMod(floorSeed, gridSpacingX);
      int gridPhaseZ = positiveFloorMod(Long.rotateLeft(floorSeed, 11), gridSpacingZ);
      boolean denseGrid = innerWidth >= gridSpacingX * 2 || innerDepth >= gridSpacingZ * 2;
      return new FloorLightingGeometry(innerMinX, innerMinZ, innerWidth, innerDepth, anchorX, anchorZ, gridSpacingX, gridSpacingZ, gridPhaseX, gridPhaseZ, denseGrid);
   }

   private static long mixFloorSeed(long blueprintSeed, int floorIndex) {
      return Long.rotateLeft(blueprintSeed ^ 0x9E3779B97F4A7C15L, floorIndex + 1) + floorIndex * 0x632BE59BD9B4E019L;
   }

   private static int facadeDarkStride(BuildingBlueprint blueprint) {
      return switch (blueprint.profile().archetype()) {
         case HOUSE -> 4;
         case APARTMENT -> 6;
         case COMMERCIAL -> 10;
         case INDUSTRIAL -> 8;
         case TOWER -> 12;
         case GENERIC -> 7;
      };
   }

   private static int facadeStripSpacing(BuildingBlueprint blueprint) {
      return switch (blueprint.profile().archetype()) {
         case HOUSE -> 0;
         case APARTMENT -> 5;
         case COMMERCIAL -> 4;
         case INDUSTRIAL -> 5;
         case TOWER -> 4;
         case GENERIC -> 5;
      };
   }

   private static int signedOffset(long seed, int magnitude) {
      if (magnitude <= 0) {
         return 0;
      }

      int width = magnitude * 2 + 1;
      return positiveFloorMod(seed, width) - magnitude;
   }

   private static int positiveFloorMod(long value, int modulus) {
      return Math.floorMod((int)(value ^ value >>> 32), modulus);
   }

   private static boolean hasLitFacadeInterval(int intervalMin, int intervalMax, int facadeMin, int facadeMax, int darkStride, int darkPhase) {
      int overlapMin = Math.max(intervalMin, facadeMin);
      int overlapMax = Math.min(intervalMax, facadeMax);
      if (overlapMin > overlapMax) {
         return false;
      }

      int intervalLength = overlapMax - overlapMin + 1;
      if (intervalLength >= darkStride) {
         return true;
      }

      for (int coord = overlapMin; coord <= overlapMax; coord++) {
         if (Math.floorMod(coord - facadeMin, darkStride) != darkPhase) {
            return true;
         }
      }

      return false;
   }

   private record FloorLightingGeometry(
      int innerMinX,
      int innerMinZ,
      int innerWidth,
      int innerDepth,
      int anchorX,
      int anchorZ,
      int gridSpacingX,
      int gridSpacingZ,
      int gridPhaseX,
      int gridPhaseZ,
      boolean denseGrid
   ) {
      private boolean hasInterior() {
         return this.innerWidth > 0 && this.innerDepth > 0;
      }
   }
}
