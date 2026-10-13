package com.yucareux.tellus.world.data.osm;

import java.util.List;

public final class OsmSandTile {
   private static final OsmSandTile EMPTY = new OsmSandTile(List.of());
   private final List<OsmSandFeature> features;

   public OsmSandTile(List<OsmSandFeature> features) {
      this.features = List.copyOf(features == null ? List.of() : features);
   }

   public static OsmSandTile empty() {
      return EMPTY;
   }

   public List<OsmSandFeature> features() {
      return this.features;
   }

   public boolean isEmpty() {
      return this.features.isEmpty();
   }
}
