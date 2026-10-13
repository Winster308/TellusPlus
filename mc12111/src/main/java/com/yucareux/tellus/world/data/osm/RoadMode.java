package com.yucareux.tellus.world.data.osm;

public enum RoadMode {
   NORMAL,
   BRIDGE,
   TUNNEL;

   public int priority() {
      return switch (this) {
         case NORMAL -> 2;
         case BRIDGE -> 3;
         case TUNNEL -> 1;
      };
   }
}
