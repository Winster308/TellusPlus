package com.yucareux.tellus.worldgen.building;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;

public final class TellusBuildingProfiles {
   private static final double DEFAULT_STOREY_METERS = 3.2;
   /** The tall building height diagnostic is logged only once (used to verify how OSM height relates to building:levels). */
   private static final java.util.concurrent.atomic.AtomicBoolean BUILDING_HEIGHT_PROBE =
         new java.util.concurrent.atomic.AtomicBoolean();

   private TellusBuildingProfiles() {
   }

   public static BuildingProfile resolveProfile(OsmBuildingFeature feature, double worldScale, Holder<Biome> biome, boolean interiorsEnabled) {
      OsmBuildingMetadata metadata = feature.metadata();
      // Only relax, never narrow (project hard rule 1).
      //   The original implementation only took metadata.floorCount() (from OSM `building:levels`), so
      //   the building height was floorCount * 3.2m/scale and the **height explicitly written in OSM was
      //   completely ignored**. A typical victim is the Canton Tower (height=600 m but levels far below
      //   600/3.2=188): it was built only levels*3.2 metres tall, so the top was sliced flat, identically
      //   every single time. The user measured a "clean break, same every time", which is exactly the
      //   signature of that deterministic truncation.
      //   The fix takes the larger of the two, so a building is never shorter than the height its data
      //   declares. When the floor count is already the larger one, max changes nothing, so ordinary
      //   buildings stay bit for bit identical.
      int floorCount = Math.max(Math.max(1, metadata.floorCount()),
            inferFloorCount(feature.heightMeters()));
      double area = feature.areaSquareMeters();
      BuildingProfile.Archetype archetype = resolveArchetype(metadata, area, floorCount, feature.heightMeters());
      BuildingProfile.ClimateFamily climate = climateFamily(biome);
      int storeyHeightBlocks = interiorsEnabled ? 4 : Math.max(1, (int)Math.round(DEFAULT_STOREY_METERS / Math.max(1.0, worldScale)));
      // One shot diagnostic: a tall building whose name mentions a height logs the declared data height
      // against the height actually built, so the next run of the game can tell at a glance whether the
      // height is being ignored (already fixed) or something else is at play.
      if (feature.heightMeters() >= 100.0 && BUILDING_HEIGHT_PROBE.compareAndSet(false, true)) {
         Tellus.LOGGER.info("[TellusCN] Tall building probe: {} - OSM height={} m, OSM levels={}, "
            + "levels actually used={}, storey height={} blocks, about {} blocks tall when built (scale {}). "
            + "If levels actually used is far below height/3.2, then the flat top bug fixed here is still present.",
            metadata.name() == null ? "(unnamed)" : metadata.name(), feature.heightMeters(),
            metadata.floorCount(), floorCount, storeyHeightBlocks, floorCount * storeyHeightBlocks, worldScale);
      }
      BuildingProfile.RoofProfile roofProfile = resolveRoofProfile(metadata, archetype, feature);
      int parapetHeight = switch (archetype) {
         case HOUSE -> 0;
         case APARTMENT, COMMERCIAL, INDUSTRIAL -> 1;
         case TOWER -> 2;
         case GENERIC -> 1;
      };
      int roofRise = switch (roofProfile) {
         case GABLED_X, GABLED_Z, HIPPED -> Math.max(1, Math.min(4, (int)Math.round(feature.heightMeters() / 12.0)));
         case FLAT_CROWN -> 2;
         case FLAT_SKYLIGHT -> 1;
         case FLAT -> 0;
      };
      int setbackEveryFloors = archetype == BuildingProfile.Archetype.TOWER && floorCount >= 12 ? 8 : 0;
      int maxSetback = archetype == BuildingProfile.Archetype.TOWER ? 3 : 0;
      int windowSpacing = switch (archetype) {
         case HOUSE -> 3;
         case APARTMENT -> 3;
         case COMMERCIAL -> 4;
         case INDUSTRIAL -> 5;
         case TOWER -> 4;
         case GENERIC -> 4;
      };
      return new BuildingProfile(archetype, roofProfile, climate, floorCount, storeyHeightBlocks, interiorsEnabled, parapetHeight, roofRise, setbackEveryFloors, maxSetback, windowSpacing);
   }

   public static int inferFloorCount(double heightMeters) {
      return Math.max(1, (int)Math.round(heightMeters / DEFAULT_STOREY_METERS));
   }

   private static BuildingProfile.Archetype resolveArchetype(OsmBuildingMetadata metadata, double areaSquareMeters, int floorCount, double heightMeters) {
      String type = metadata.primaryType();
      String normalized = type == null ? "" : type.toLowerCase();
      if (heightMeters >= 30.0 || floorCount >= 10 || containsAny(normalized, "tower", "skyscraper", "highrise", "high-rise")) {
         return BuildingProfile.Archetype.TOWER;
      }

      if (containsAny(normalized, "warehouse", "industrial", "factory", "shed", "hangar")) {
         return BuildingProfile.Archetype.INDUSTRIAL;
      }

      if (containsAny(normalized, "house", "home", "residential", "dwelling", "villa", "cabin", "bungalow")) {
         if (areaSquareMeters <= 180.0 && floorCount <= 3) {
            return BuildingProfile.Archetype.HOUSE;
         }

         return BuildingProfile.Archetype.APARTMENT;
      }

      if (areaSquareMeters <= 180.0 && floorCount <= 3) {
         return BuildingProfile.Archetype.HOUSE;
      }

      if (areaSquareMeters >= 700.0 && floorCount <= 3) {
         return BuildingProfile.Archetype.INDUSTRIAL;
      }

      if (containsAny(normalized, "apartment", "flat", "condo", "residence", "residential")) {
         return BuildingProfile.Archetype.APARTMENT;
      }

      if (containsAny(normalized, "office", "shop", "retail", "commercial", "mall", "hotel", "school", "civic")) {
         return BuildingProfile.Archetype.COMMERCIAL;
      }

      if (areaSquareMeters <= 600.0 && floorCount <= 6) {
         return BuildingProfile.Archetype.APARTMENT;
      }

      return areaSquareMeters > 0.0 ? BuildingProfile.Archetype.COMMERCIAL : BuildingProfile.Archetype.GENERIC;
   }

   private static BuildingProfile.RoofProfile resolveRoofProfile(OsmBuildingMetadata metadata, BuildingProfile.Archetype archetype, OsmBuildingFeature feature) {
      String roofShape = metadata.roofShape();
      if (roofShape != null) {
         String normalized = roofShape.toLowerCase();
         if (normalized.contains("hip")) {
            return BuildingProfile.RoofProfile.HIPPED;
         }
         if (normalized.contains("gabled") || normalized.contains("gable")) {
            return feature.widthLongerThanDepth() ? BuildingProfile.RoofProfile.GABLED_Z : BuildingProfile.RoofProfile.GABLED_X;
         }
         if (normalized.contains("flat")) {
            return archetype == BuildingProfile.Archetype.TOWER ? BuildingProfile.RoofProfile.FLAT_CROWN : BuildingProfile.RoofProfile.FLAT;
         }
      }

      return switch (archetype) {
         case HOUSE -> feature.widthLongerThanDepth() ? BuildingProfile.RoofProfile.GABLED_Z : BuildingProfile.RoofProfile.HIPPED;
         case INDUSTRIAL -> BuildingProfile.RoofProfile.FLAT_SKYLIGHT;
         case TOWER -> BuildingProfile.RoofProfile.FLAT_CROWN;
         case APARTMENT, COMMERCIAL, GENERIC -> BuildingProfile.RoofProfile.FLAT;
      };
   }

   public static BuildingProfile.ClimateFamily climateFamily(Holder<Biome> biome) {
      if (biome == null) {
         return BuildingProfile.ClimateFamily.TEMPERATE;
      }

      Biome value = biome.value();
      float temperature = value.getBaseTemperature();
      boolean precipitation = value.hasPrecipitation();
      if (temperature <= 0.3F) {
         return BuildingProfile.ClimateFamily.COLD;
      }
      if (temperature >= 1.2F && !precipitation) {
         return BuildingProfile.ClimateFamily.ARID;
      }
      if (temperature >= 1.0F && precipitation) {
         return BuildingProfile.ClimateFamily.TROPICAL;
      }
      return BuildingProfile.ClimateFamily.TEMPERATE;
   }

   private static boolean containsAny(String value, String... parts) {
      for (String part : parts) {
         if (value.contains(part)) {
            return true;
         }
      }
      return false;
   }
}
