package com.yucareux.tellus.worldgen;

public final class EarthProjection {
   /** Whether longitude wrapping is disabled ({@code -Dtellus.wrap=false}). Wrapping is on by default. */
   private static final boolean WRAP_DISABLED = !Boolean.parseBoolean(
      System.getProperty("tellus.wrap", "true"));

   public static final double METERS_PER_DEGREE = 111319.49166666667;
   public static final double MAX_MERCATOR_LATITUDE = 85.05112878;
   private static final double EARTH_RADIUS_METERS = METERS_PER_DEGREE * 180.0 / Math.PI;
   private static final EarthProjection.ProjectionMode PROJECTION_MODE = resolveMode(System.getProperty("tellus.projection.mode", "mercator"));

   private EarthProjection() {
   }

   public static double blocksPerDegree(double worldScale) {
      return worldScale <= 0.0 ? 0.0 : METERS_PER_DEGREE / worldScale;
   }

   public static double worldScaleFromBlocksPerDegree(double blocksPerDegree) {
      return blocksPerDegree <= 0.0 ? 0.0 : METERS_PER_DEGREE / blocksPerDegree;
   }

   public static double latToBlockZ(double latitude, double worldScale) {
      if (worldScale <= 0.0) {
         return 0.0;
      } else if (PROJECTION_MODE == EarthProjection.ProjectionMode.LEGACY) {
         return -latitude * blocksPerDegree(worldScale);
      } else {
         double clampedLatitude = clampLatitude(latitude);
         double latitudeRad = Math.toRadians(clampedLatitude);
         double mercatorY = EARTH_RADIUS_METERS * Math.log(Math.tan(Math.PI * 0.25 + latitudeRad * 0.5));
         return -mercatorY / worldScale;
      }
   }

   public static double blockZToLat(double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return 0.0;
      } else if (PROJECTION_MODE == EarthProjection.ProjectionMode.LEGACY) {
         return -blockZ / blocksPerDegree(worldScale);
      } else {
         double mercatorY = -blockZ * worldScale;
         double latitudeRad = Math.atan(Math.sinh(mercatorY / EARTH_RADIUS_METERS));
         return clampLatitude(Math.toDegrees(latitudeRad));
      }
   }

   /**
    * Wrap an arbitrary longitude into [-180, 180).
    *
    * <p>Coordinates beyond +/-180 degrees wrap around to the other side of the globe, so the
    * "Earth repeats" -- there is no longer a horizontal border at all (the chosen approach: no more
    * "empty world outside the border"). Note the difference from {@code clampLongitude}: clamping
     * collapses everything beyond 180 degrees into a single narrow strip.
    */
   public static double wrapLongitude(double longitude) {
      if (WRAP_DISABLED) {
         return longitude;   // -Dtellus.wrap=false: fall back to the old behaviour (no wrapping), for troubleshooting
      }
      double wrapped = (longitude + 180.0) % 360.0;
      if (wrapped < 0.0) {
         wrapped += 360.0;
      }
      return wrapped - 180.0;
   }

   public static double clampLatitude(double latitude) {
      return Math.max(-MAX_MERCATOR_LATITUDE, Math.min(MAX_MERCATOR_LATITUDE, latitude));
   }

   public static String projectionModeId() {
      return PROJECTION_MODE.id();
   }

   private static EarthProjection.ProjectionMode resolveMode(String value) {
      if (value == null) {
         return EarthProjection.ProjectionMode.MERCATOR;
      } else {
         return "legacy".equalsIgnoreCase(value.trim()) ? EarthProjection.ProjectionMode.LEGACY : EarthProjection.ProjectionMode.MERCATOR;
      }
   }

   private static enum ProjectionMode {
      LEGACY("legacy"),
      MERCATOR("mercator");

      private final String id;

      private ProjectionMode(String id) {
         this.id = id;
      }

      private String id() {
         return this.id;
      }
   }
}
