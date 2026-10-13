package com.yucareux.tellus.worldgen.building;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

public final class TellusBuildingBlueprints {
   private TellusBuildingBlueprints() {
   }

   public static BuildingBlueprint create(
      String groupId,
      OsmBuildingFeature feature,
      BuildingProfile profile,
      long worldSeed,
      int baseY,
      int floorY,
      int roofBaseY,
      int topY,
      List<RoadFeature> nearbyRoads,
      double worldScale
   ) {
      Objects.requireNonNull(groupId, "groupId");
      Objects.requireNonNull(feature, "feature");
      Objects.requireNonNull(profile, "profile");
      int minWorldX = Mth.floor(feature.minBlockXForScale(worldScale));
      int maxWorldX = Mth.ceil(feature.maxBlockXForScale(worldScale));
      int minWorldZ = Mth.floor(feature.minBlockZ(worldScale));
      int maxWorldZ = Mth.ceil(feature.maxBlockZ(worldScale));
      long seed = mixSeed(worldSeed, feature.featureId(), groupId.hashCode());
      EntrancePlacement entrance = resolveEntrance(feature, nearbyRoads, worldScale, minWorldX, maxWorldX, minWorldZ, maxWorldZ, seed);
      return new BuildingBlueprint(groupId, seed, profile, baseY, floorY, roofBaseY, topY, minWorldX, maxWorldX, minWorldZ, maxWorldZ, entrance.worldX(), entrance.worldZ(), entrance.facing(), entrance.width());
   }

   private static EntrancePlacement resolveEntrance(
      OsmBuildingFeature feature,
      List<RoadFeature> nearbyRoads,
      double worldScale,
      int minWorldX,
      int maxWorldX,
      int minWorldZ,
      int maxWorldZ,
      long seed
   ) {
      double[] centroid = feature.centroidWorld(worldScale);
      int fallbackX = Mth.clamp((int)Math.round(centroid[0]), minWorldX, maxWorldX);
      int fallbackZ = Mth.clamp((int)Math.round(centroid[1]), minWorldZ, maxWorldZ);
      Direction bestFacing = longestAxisFacing(minWorldX, maxWorldX, minWorldZ, maxWorldZ);
      double bestDistanceSq = Double.POSITIVE_INFINITY;
      if (nearbyRoads != null) {
         for (RoadFeature road : nearbyRoads) {
            for (int i = 0; i < road.pointCount(); i++) {
               double roadX = road.lonAt(i) * com.yucareux.tellus.worldgen.EarthProjection.blocksPerDegree(worldScale);
               double roadZ = com.yucareux.tellus.worldgen.EarthProjection.latToBlockZ(road.latAt(i), worldScale);
               double dx = roadX - centroid[0];
               double dz = roadZ - centroid[1];
               double distanceSq = dx * dx + dz * dz;
               if (distanceSq <= 24.0 * 24.0 && distanceSq < bestDistanceSq) {
                  bestDistanceSq = distanceSq;
                  if (Math.abs(dx) >= Math.abs(dz)) {
                     bestFacing = dx > 0.0 ? Direction.EAST : Direction.WEST;
                  } else {
                     bestFacing = dz > 0.0 ? Direction.SOUTH : Direction.NORTH;
                  }
               }
            }
         }
      }

      EntrancePlacement snapped = snapEntranceToFootprint(feature, worldScale, minWorldX, maxWorldX, minWorldZ, maxWorldZ, bestFacing, fallbackX, fallbackZ);
      if (snapped != null) {
         return snapped;
      }

      // 2026-10-05: these clamps used minWorldX+1 / maxWorldX-1, i.e. they targeted the *interior*.
      // For a footprint that is only one block wide minWorldX == maxWorldX, so the arguments become
      // (min+1, max-1) = (min+1, min-1) -- lo > hi. Mth.clamp then returns lo for any smaller input,
      // which is minWorldX + 1: **outside the footprint**. isEntranceCell can never match it, so the
      // door silently disappeared for single-wide buildings. Clamping into the footprint itself is
      // correct for every width, including the degenerate one.
      return switch (bestFacing) {
         case NORTH -> new EntrancePlacement(Mth.clamp(fallbackX, minWorldX, maxWorldX), minWorldZ, bestFacing, 1);
         case SOUTH -> new EntrancePlacement(Mth.clamp(fallbackX, minWorldX, maxWorldX), maxWorldZ, bestFacing, 1);
         case EAST -> new EntrancePlacement(maxWorldX, Mth.clamp(fallbackZ, minWorldZ, maxWorldZ), bestFacing, 1);
         case WEST -> new EntrancePlacement(minWorldX, Mth.clamp(fallbackZ, minWorldZ, maxWorldZ), bestFacing, 1);
         default -> new EntrancePlacement(Mth.clamp(fallbackX, minWorldX, maxWorldX), minWorldZ, Direction.NORTH, 1);
      };
   }

   private static EntrancePlacement snapEntranceToFootprint(
      OsmBuildingFeature feature,
      double worldScale,
      int minWorldX,
      int maxWorldX,
      int minWorldZ,
      int maxWorldZ,
      Direction preferredFacing,
      int preferredWorldX,
      int preferredWorldZ
   ) {
      EntranceCandidate preferred = findBoundaryCandidate(
         feature, worldScale, minWorldX, maxWorldX, minWorldZ, maxWorldZ, preferredFacing, preferredWorldX, preferredWorldZ
      );
      if (preferred != null) {
         return new EntrancePlacement(preferred.worldX(), preferred.worldZ(), preferred.facing(), 1);
      }

      EntranceCandidate best = null;
      for (Direction direction : Direction.Plane.HORIZONTAL) {
         EntranceCandidate candidate = findBoundaryCandidate(
            feature, worldScale, minWorldX, maxWorldX, minWorldZ, maxWorldZ, direction, preferredWorldX, preferredWorldZ
         );
         if (candidate != null && (best == null || candidate.score() < best.score())) {
            best = candidate;
         }
      }

      return best == null ? null : new EntrancePlacement(best.worldX(), best.worldZ(), best.facing(), 1);
   }

   private static EntranceCandidate findBoundaryCandidate(
      OsmBuildingFeature feature,
      double worldScale,
      int minWorldX,
      int maxWorldX,
      int minWorldZ,
      int maxWorldZ,
      Direction facing,
      int preferredWorldX,
      int preferredWorldZ
   ) {
      EntranceCandidate best = null;
      for (int worldZ = minWorldZ; worldZ <= maxWorldZ; worldZ++) {
         for (int worldX = minWorldX; worldX <= maxWorldX; worldX++) {
            if (!feature.containsWorld(worldX + 0.5, worldZ + 0.5, worldScale) || !touchesExterior(feature, worldScale, worldX, worldZ, facing)) {
               continue;
            }

            double dx = worldX - preferredWorldX;
            double dz = worldZ - preferredWorldZ;
            double score = dx * dx + dz * dz;
            if (best == null || score < best.score()) {
               best = new EntranceCandidate(worldX, worldZ, facing, score);
            }
         }
      }

      return best;
   }

   private static boolean touchesExterior(OsmBuildingFeature feature, double worldScale, int worldX, int worldZ, Direction facing) {
      return !feature.containsWorld(worldX + 0.5 + facing.getStepX(), worldZ + 0.5 + facing.getStepZ(), worldScale);
   }

   private static Direction longestAxisFacing(int minWorldX, int maxWorldX, int minWorldZ, int maxWorldZ) {
      int width = maxWorldX - minWorldX;
      int depth = maxWorldZ - minWorldZ;
      return width >= depth ? Direction.SOUTH : Direction.EAST;
   }

   private static long mixSeed(long worldSeed, long featureId, long salt) {
      long seed = worldSeed ^ featureId * 341873128712L ^ salt * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      return seed;
   }

   private record EntrancePlacement(int worldX, int worldZ, Direction facing, int width) {
   }

   private record EntranceCandidate(int worldX, int worldZ, Direction facing, double score) {
   }
}
