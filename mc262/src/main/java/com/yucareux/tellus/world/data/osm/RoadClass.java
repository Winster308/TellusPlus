package com.yucareux.tellus.world.data.osm;

import java.util.Locale;

public enum RoadClass {
   MAIN,
   NORMAL,
   DIRT;

   public int baseWidth() {
      return switch (this) {
         case MAIN -> 6;
         case NORMAL -> 4;
         case DIRT -> 2;
      };
   }

   
   public static RoadClass fromHighwayTag( String highwayTag) {
      if (highwayTag != null && !highwayTag.isBlank()) {
         String highway = highwayTag.toLowerCase(Locale.ROOT);

         return switch (highway) {
            case "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link" -> MAIN;
            case "residential", "unclassified", "living_street", "service", "road", "pedestrian" -> NORMAL;
            case "track", "path", "footway", "cycleway", "bridleway" -> DIRT;
            default -> NORMAL;
         };
      } else {
         return null;
      }
   }
}
