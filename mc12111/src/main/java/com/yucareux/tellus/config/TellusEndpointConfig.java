/*
 * TellusCN - Chinese Mirror & CDN Support for Tellus Mod
 * Copyright (c) 2026 BlackHoleEra-Team
 * 
 * Original Tellus Mod Copyright (c) Yucareux
 * Licensed under LGPL-3.0
 */

package com.yucareux.tellus.config;

/**
 * Tellus endpoint configuration utility class
 * 
 * Provides unified data source endpoint retrieval methods
 * Priority: in-game settings > JVM startup parameters > default official source
 */
public final class TellusEndpointConfig {
   
   private TellusEndpointConfig() {
   }
   
   /**
    * Get endpoint URL
    * 
    * @param systemPropertyKey JVM parameter key
    * @param defaultValue Default value
    * @return The actual endpoint URL used
    */
   public static String getEndpoint(String systemPropertyKey, String defaultValue) {
      // 1. First check in-game config (if mirror is enabled)
      if (MirrorConfig.isEnabled()) {
         String mirrorEndpoint = getMirrorEndpoint(systemPropertyKey);
         if (!mirrorEndpoint.isBlank()) {
            return mirrorEndpoint;
         }
      }
      
      // 2. Check JVM startup parameters
      String jvmValue = System.getProperty(systemPropertyKey);
      if (jvmValue != null && !jvmValue.isBlank()) {
         return jvmValue;
      }
      
      // 3. Return default value
      return defaultValue;
   }
   
   /**
    * Get the corresponding mirror endpoint based on system property key.
    *
    * <p>Only routes allowed by {@link MirrorConfig#isRouteMirrored(String)} will go through the mirror;
    * others return empty string, and the caller falls back to the official source. See the comment in MirrorConfig:
    * the mirror only proxies some backends, indiscriminate pointing would cause land cover / road network / buildings to fail entirely.
    */
   private static String getMirrorEndpoint(String systemPropertyKey) {
      String domain = MirrorConfig.getActiveDomain();
      if (domain.isBlank()) {
         return "";
      }
      
      // System property key -> mirror route name
      String route = switch (systemPropertyKey) {
         case "tellus.elevation.endpoint" -> "elevation";
         case "tellus.copernicus30.endpoint" -> "copernicus30";
         case "tellus.copernicus90.endpoint" -> "copernicus90";
         case "tellus.usgs.endpoint" -> "usgs";
         case "tellus.japangsi.endpoint" -> "japangsi";
         case "tellus.arcticdem.endpoint" -> "arcticdem";
         case "tellus.rema.endpoint" -> "rema";
         case "tellus.landcover.endpoint" -> "landcover";
         case "tellus.weather.endpoint" -> "weather";
         case "tellus.geocoding.endpoint" -> "geocoding";
         case "tellus.osm.overpass.endpoints" -> "overpass";
         case "tellus.landmask.baseUrl" -> "landmask";
         case "tellus.s3proxy.endpoint" -> "s3";
         case "tellus.map.tiles.endpoint" -> "tiles";
         case "tellus.overture.roads.endpoint" -> "overture/roads";
         case "tellus.overture.buildings.endpoint" -> "overture/buildings";
         case "tellus.overture.water.endpoint" -> "overture/water";
         case "tellus.overture.sand.endpoint" -> "overture/sand";
         default -> "";
      };
      if (route.isEmpty() || !MirrorConfig.isRouteMirrored(route)) {
         return "";
      }
      // Map Base map: include the basemap source, of the form {domain}/tiles/carto, the map UI appends /{z}/{x}/{y}.png
      if ("tiles".equals(route)) {
         return domain + "/" + route + "/" + MirrorConfig.getTileSource();
      }
      // landmask is "base URL" semantics, keep trailing slash
      return "tellus.landmask.baseUrl".equals(systemPropertyKey)
            ? domain + "/" + route + "/"
            : domain + "/" + route;
   }
   
   /**
    * Get Terrarium elevation endpoint
    */
   public static String getElevationEndpoint(String defaultValue) {
      return getEndpoint("tellus.elevation.endpoint", defaultValue);
   }
   
   /**
    * Get Copernicus 30m endpoint
    */
   public static String getCopernicus30Endpoint(String defaultValue) {
      return getEndpoint("tellus.copernicus30.endpoint", defaultValue);
   }
   
   /**
    * Get Copernicus 90m endpoint
    */
   public static String getCopernicus90Endpoint(String defaultValue) {
      return getEndpoint("tellus.copernicus90.endpoint", defaultValue);
   }
   
   /**
    * Get USGS endpoint
    */
   public static String getUsgsEndpoint(String defaultValue) {
      return getEndpoint("tellus.usgs.endpoint", defaultValue);
   }
   
   /**
    * Get Japan GSI endpoint
    */
   public static String getJapanGsiEndpoint(String defaultValue) {
      return getEndpoint("tellus.japangsi.endpoint", defaultValue);
   }
   
   /**
    * Get ArcticDEM endpoint
    */
   public static String getArcticDemEndpoint(String defaultValue) {
      return getEndpoint("tellus.arcticdem.endpoint", defaultValue);
   }
   
   /**
    * Get REMA endpoint
    */
   public static String getRemaEndpoint(String defaultValue) {
      return getEndpoint("tellus.rema.endpoint", defaultValue);
   }
   
   /**
    * Get land cover endpoint
    */
   public static String getLandCoverEndpoint(String defaultValue) {
      return getEndpoint("tellus.landcover.endpoint", defaultValue);
   }
   
   /**
    * Get weather endpoint
    */
   public static String getWeatherEndpoint(String defaultValue) {
      return getEndpoint("tellus.weather.endpoint", defaultValue);
   }
   
   /**
    * Get geocoding endpoint
    */
   public static String getGeocodingEndpoint(String defaultValue) {
      return getEndpoint("tellus.geocoding.endpoint", defaultValue);
   }
   
   /**
    * Get Overpass endpoint
    */
   public static String getOverpassEndpoint(String defaultValue) {
      return getEndpoint("tellus.osm.overpass.endpoints", defaultValue);
   }
   
   /**
    * Get Land Mask base URL
    */
   public static String getLandMaskBaseUrl(String defaultValue) {
      return getEndpoint("tellus.landmask.baseUrl", defaultValue);
   }
   
   /**
    * Get S3 proxy endpoint
    */
   public static String getS3ProxyEndpoint(String defaultValue) {
      return getEndpoint("tellus.s3proxy.endpoint", defaultValue);
   }
   
   /**
    * Get map tiles endpoint
    */
   public static String getMapTilesEndpoint(String defaultValue) {
      return getEndpoint("tellus.map.tiles.endpoint", defaultValue);
   }
   
   /**
    * Get Overture Roads endpoint
    */
   public static String getOvertureRoadsEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.roads.endpoint", defaultValue);
   }
   
   /**
    * Get Overture Buildings endpoint
    */
   public static String getOvertureBuildingsEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.buildings.endpoint", defaultValue);
   }
   
   /**
    * Get Overture Water endpoint
    */
   public static String getOvertureWaterEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.water.endpoint", defaultValue);
   }
   
   /**
    * Get Overture Sand endpoint
    */
   public static String getOvertureSandEndpoint(String defaultValue) {
      return getEndpoint("tellus.overture.sand.endpoint", defaultValue);
   }
}
