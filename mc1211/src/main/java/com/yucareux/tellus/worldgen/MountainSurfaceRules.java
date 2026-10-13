package com.yucareux.tellus.worldgen;

public final class MountainSurfaceRules {
   public static final int ESA_NO_DATA = 0;
   public static final int ESA_TREE_COVER = 10;
   public static final int ESA_SHRUBLAND = 20;
   public static final int ESA_GRASSLAND = 30;
   public static final int ESA_CROPLAND = 40;
   public static final int ESA_BUILT = 50;
   public static final int ESA_BARE = 60;
   public static final int ESA_SNOW_ICE = 70;
   public static final int ESA_WATER = 80;
   public static final int ESA_WETLAND = 90;
   public static final int ESA_MANGROVES = 95;
   public static final int ESA_MOSS_LICHEN = 100;
   public static final int SURFACE_ALPINE_HEIGHT_ABOVE_SEA = 200;
   public static final int SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA = 120;

   private MountainSurfaceRules() {
   }

   public static int resolveSurfaceCoverClass(int terrainCoverClass, int visualCoverClass) {
      if (terrainCoverClass == ESA_BUILT) {
         return ESA_BUILT;
      } else {
         return !isWaterLikeCoverClass(terrainCoverClass) && !isWaterLikeCoverClass(visualCoverClass) ? visualCoverClass : terrainCoverClass;
      }
   }

   public static MountainSurfaceRules.ShorelineMaterial classifyShorelineMaterial(
      int surfaceCoverClass,
      byte climateGroup,
      int heightAboveSea,
      int slopeDiff,
      int convexity,
      MountainSurfaceRules.ShorelineKind shoreKind,
      int distanceToShore,
      boolean preferRedSand
   ) {
      if (shoreKind == MountainSurfaceRules.ShorelineKind.NONE || distanceToShore < 0) {
         return MountainSurfaceRules.ShorelineMaterial.NONE;
      } else if (surfaceCoverClass == ESA_WETLAND || surfaceCoverClass == ESA_MANGROVES) {
         return MountainSurfaceRules.ShorelineMaterial.PRESERVE_WETLAND;
      } else if (surfaceCoverClass == ESA_BUILT || surfaceCoverClass == ESA_SNOW_ICE) {
         return MountainSurfaceRules.ShorelineMaterial.NONE;
      } else {
         boolean ocean = shoreKind == MountainSurfaceRules.ShorelineKind.OCEAN;
         boolean aridClimate = climateGroup == 2;
         boolean coldClimate = climateGroup == 4 || climateGroup == 5;
         boolean tropicalClimate = climateGroup == 1;
         boolean sparseCover = surfaceCoverClass == ESA_BARE
            || surfaceCoverClass == ESA_SHRUBLAND
            || surfaceCoverClass == ESA_GRASSLAND
            || surfaceCoverClass == ESA_CROPLAND;
         boolean woodedCover = surfaceCoverClass == ESA_TREE_COVER || surfaceCoverClass == ESA_MOSS_LICHEN;
         boolean steepShore = slopeDiff >= (ocean ? 3 : 2) || convexity <= -2;
         boolean elevatedColdShore = heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA && (slopeDiff >= 2 || coldClimate);
         if (steepShore || elevatedColdShore || coldClimate && distanceToShore <= 2) {
            return MountainSurfaceRules.ShorelineMaterial.GRAVEL;
         } else if (ocean) {
            if (aridClimate) {
               return preferRedSand ? MountainSurfaceRules.ShorelineMaterial.RED_SAND : MountainSurfaceRules.ShorelineMaterial.SAND;
            } else if (tropicalClimate || sparseCover || distanceToShore <= 4 || !woodedCover) {
               return MountainSurfaceRules.ShorelineMaterial.SAND;
            } else {
               return MountainSurfaceRules.ShorelineMaterial.NONE;
            }
         } else if (woodedCover && !aridClimate && !tropicalClimate) {
            return MountainSurfaceRules.ShorelineMaterial.NONE;
         } else if (aridClimate || tropicalClimate) {
            return distanceToShore <= 1 || sparseCover && distanceToShore <= 2
               ? MountainSurfaceRules.ShorelineMaterial.SAND
               : MountainSurfaceRules.ShorelineMaterial.NONE;
         } else {
            return distanceToShore <= 1 || sparseCover && distanceToShore <= 2
               ? MountainSurfaceRules.ShorelineMaterial.GRAVEL
               : MountainSurfaceRules.ShorelineMaterial.NONE;
         }
      }
   }

   public static boolean isWaterLikeCoverClass(int coverClass) {
      return coverClass == ESA_WATER || coverClass == ESA_MANGROVES || coverClass == ESA_NO_DATA;
   }

   public static boolean isTreeCoverClass(int coverClass) {
      return coverClass == ESA_TREE_COVER;
   }

   public static boolean isTreeMarkerCoverClass(int terrainCoverClass, int visualCoverClass) {
      int surfaceCoverClass = resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      return surfaceCoverClass == ESA_TREE_COVER || surfaceCoverClass == ESA_MANGROVES;
   }

   public static boolean isVegetatedCoverClass(int coverClass) {
      return coverClass == ESA_TREE_COVER
         || coverClass == ESA_SHRUBLAND
         || coverClass == ESA_GRASSLAND
         || coverClass == ESA_CROPLAND
         || coverClass == ESA_MOSS_LICHEN;
   }

   public static boolean isMountainRockyCover(int coverClass, int heightAboveSea) {
      return coverClass == ESA_NO_DATA
         ? heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA
         : coverClass == ESA_SNOW_ICE || coverClass == ESA_BARE || coverClass == ESA_SHRUBLAND || coverClass == ESA_MOSS_LICHEN;
   }

   public static boolean qualifiesForMountainPalette(int coverClass, int heightAboveSea, int slopeDiff, int convexity) {
      if (!isMountainRockyCover(coverClass, heightAboveSea)) {
         return false;
      } else if (coverClass == ESA_SNOW_ICE || heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA) {
         return true;
      } else {
         int ruggedness = slopeDiff + Math.max(0, -convexity);
         return heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA || slopeDiff >= 3 || ruggedness >= 5;
      }
   }

   public static float vegetationTransitionWeight(int terrainCoverClass, int visualCoverClass, int heightAboveSea) {
      int surfaceCoverClass = resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      return vegetationTransitionWeightForSurfaceCoverClass(surfaceCoverClass, heightAboveSea);
   }

   public static float vegetationTransitionWeightForSurfaceCoverClass(int surfaceCoverClass, int heightAboveSea) {
      return switch (surfaceCoverClass) {
         case ESA_TREE_COVER -> 1.0F;
         case ESA_SHRUBLAND -> 0.6F;
         case ESA_GRASSLAND, ESA_CROPLAND -> heightAboveSea >= SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA ? 0.24F : 0.38F;
         case ESA_MOSS_LICHEN -> heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA ? 0.22F : 0.42F;
         default -> 0.0F;
      };
   }

   public static MountainSurfaceRules.ApproximateSurface classifyApproximateSurface(
      int terrainCoverClass, int visualCoverClass, int heightAboveSea, int slopeDiff, int convexity, boolean snowLikeTerrain
   ) {
      int surfaceCoverClass = resolveSurfaceCoverClass(terrainCoverClass, visualCoverClass);
      if (snowLikeTerrain || surfaceCoverClass == ESA_SNOW_ICE) {
         if (snowLikeTerrain || retainsApproximateSnow(heightAboveSea, slopeDiff, convexity)) {
            return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.SNOW);
         }
      }

      if (!qualifiesForMountainPalette(surfaceCoverClass, heightAboveSea, slopeDiff, convexity)) {
         return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.NONE);
      } else {
         int ruggedness = slopeDiff + Math.max(0, -convexity);
         boolean exposedHeadwall = ruggedness >= 7 && slopeDiff >= 4 && convexity <= 0;
         if (slopeDiff >= 6 || slopeDiff >= 5 && convexity >= 1) {
            return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.DEEPSLATE_SCREE);
         } else if (slopeDiff >= 4 && convexity >= 1) {
            return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.DEEPSLATE_TALUS);
         } else if (heightAboveSea >= 190 && exposedHeadwall && ruggedness >= 9) {
            return new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.WEATHERED_ANDESITE);
         } else {
            return heightAboveSea >= 170 && exposedHeadwall && ruggedness >= 8
               ? new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.TUFF)
               : new MountainSurfaceRules.ApproximateSurface(surfaceCoverClass, MountainSurfaceRules.ApproximatePalette.DEEPSLATE);
         }
      }
   }

   private static boolean retainsApproximateSnow(int heightAboveSea, int slopeDiff, int convexity) {
      if (heightAboveSea < SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA) {
         return false;
      } else {
         int score = 52;
         score += Math.max(0, (heightAboveSea - 150) / 3);
         score -= slopeDiff * 12;
         score += Math.max(-16, Math.min(20, convexity * 10));
         if (heightAboveSea >= SURFACE_ALPINE_HEIGHT_ABOVE_SEA) {
            score += 10;
         }

         return score >= 54;
      }
   }

   public static enum ApproximatePalette {
      NONE,
      SNOW,
      DEEPSLATE,
      DEEPSLATE_SCREE,
      DEEPSLATE_TALUS,
      WEATHERED_ANDESITE,
      TUFF;
   }

   public record ApproximateSurface(int surfaceCoverClass, MountainSurfaceRules.ApproximatePalette palette) {
      public boolean isMountain() {
         return this.palette != MountainSurfaceRules.ApproximatePalette.NONE && this.palette != MountainSurfaceRules.ApproximatePalette.SNOW;
      }

      public boolean isSnow() {
         return this.palette == MountainSurfaceRules.ApproximatePalette.SNOW;
      }
   }

   public static enum ShorelineKind {
      NONE,
      OCEAN,
      INLAND;
   }

   public static enum ShorelineMaterial {
      NONE,
      SAND,
      RED_SAND,
      GRAVEL,
      PRESERVE_WETLAND;
   }
}
