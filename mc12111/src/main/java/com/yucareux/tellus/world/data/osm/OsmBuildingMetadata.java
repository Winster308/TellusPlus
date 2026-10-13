package com.yucareux.tellus.world.data.osm;

public record OsmBuildingMetadata(
   String buildingClass,
   String subtype,
   String use,
   String name,
   int floorCount,
   String roofShape,
   String roofMaterial
) {
   public OsmBuildingMetadata {
      floorCount = Math.max(1, floorCount);
      buildingClass = normalize(buildingClass);
      subtype = normalize(subtype);
      use = normalize(use);
      name = normalize(name);
      roofShape = normalize(roofShape);
      roofMaterial = normalize(roofMaterial);
   }

   public String primaryType() {
      return this.use != null ? this.use : this.subtype != null ? this.subtype : this.buildingClass;
   }

   private static String normalize(String value) {
      return value == null || value.isBlank() ? null : value;
   }
}
