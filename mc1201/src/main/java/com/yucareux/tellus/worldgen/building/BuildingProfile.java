package com.yucareux.tellus.worldgen.building;

public record BuildingProfile(
   BuildingProfile.Archetype archetype,
   BuildingProfile.RoofProfile roofProfile,
   BuildingProfile.ClimateFamily climateFamily,
   int floorCount,
   int storeyHeightBlocks,
   boolean interiorsEnabled,
   int parapetHeight,
   int roofRise,
   int setbackEveryFloors,
   int maxSetback,
   int windowSpacing
) {
   public BuildingProfile {
      floorCount = Math.max(1, floorCount);
      storeyHeightBlocks = Math.max(1, storeyHeightBlocks);
      parapetHeight = Math.max(0, parapetHeight);
      roofRise = Math.max(0, roofRise);
      setbackEveryFloors = Math.max(0, setbackEveryFloors);
      maxSetback = Math.max(0, maxSetback);
      windowSpacing = Math.max(2, windowSpacing);
   }

   public enum Archetype {
      HOUSE,
      APARTMENT,
      COMMERCIAL,
      INDUSTRIAL,
      TOWER,
      GENERIC
   }

   public enum RoofProfile {
      GABLED_X,
      GABLED_Z,
      HIPPED,
      FLAT,
      FLAT_CROWN,
      FLAT_SKYLIGHT
   }

   public enum ClimateFamily {
      TEMPERATE,
      COLD,
      ARID,
      TROPICAL
   }
}
