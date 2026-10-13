package com.yucareux.tellus.worldgen.building;

public final class TellusBuildingStyles {
   private static final TellusBuildingStyles.HouseStyle[] TEMPERATE_HOUSE_STYLES = new TellusBuildingStyles.HouseStyle[]{
      TellusBuildingStyles.HouseStyle.WHITE_SLATE,
      TellusBuildingStyles.HouseStyle.GRAY_CHARCOAL,
      TellusBuildingStyles.HouseStyle.BRICK_SLATE,
      TellusBuildingStyles.HouseStyle.PALE_STONE,
      TellusBuildingStyles.HouseStyle.WARM_CLAY
   };
   private static final TellusBuildingStyles.HouseStyle[] COLD_HOUSE_STYLES = new TellusBuildingStyles.HouseStyle[]{
      TellusBuildingStyles.HouseStyle.GRAY_CHARCOAL,
      TellusBuildingStyles.HouseStyle.WHITE_SLATE,
      TellusBuildingStyles.HouseStyle.BRICK_SLATE,
      TellusBuildingStyles.HouseStyle.PALE_STONE
   };
   private static final TellusBuildingStyles.HouseStyle[] ARID_HOUSE_STYLES = new TellusBuildingStyles.HouseStyle[]{
      TellusBuildingStyles.HouseStyle.WARM_CLAY,
      TellusBuildingStyles.HouseStyle.PALE_STONE,
      TellusBuildingStyles.HouseStyle.WHITE_SLATE
   };
   private static final TellusBuildingStyles.HouseStyle[] TROPICAL_HOUSE_STYLES = new TellusBuildingStyles.HouseStyle[]{
      TellusBuildingStyles.HouseStyle.WHITE_SLATE,
      TellusBuildingStyles.HouseStyle.WARM_CLAY,
      TellusBuildingStyles.HouseStyle.PALE_STONE,
      TellusBuildingStyles.HouseStyle.GRAY_CHARCOAL
   };

   private TellusBuildingStyles() {
   }

   public static TellusBuildingStyles.HouseStyle resolveHouseStyle(BuildingProfile profile, long blueprintSeed) {
      TellusBuildingStyles.HouseStyle[] styles = switch (profile.climateFamily()) {
         case COLD -> COLD_HOUSE_STYLES;
         case ARID -> ARID_HOUSE_STYLES;
         case TROPICAL -> TROPICAL_HOUSE_STYLES;
         case TEMPERATE -> TEMPERATE_HOUSE_STYLES;
      };
      long mixedSeed = mixSeed(blueprintSeed, profile.floorCount(), profile.roofProfile().ordinal());
      return styles[Math.floorMod(mixedSeed, styles.length)];
   }

   public static int previewColor(BuildingProfile profile, long blueprintSeed) {
      return switch (profile.archetype()) {
         case HOUSE -> resolveHouseStyle(profile, blueprintSeed).previewColor();
         case APARTMENT -> 12566463;
         case COMMERCIAL -> 10000536;
         case INDUSTRIAL -> 7697781;
         case TOWER -> 7833914;
         case GENERIC -> 11119017;
      };
   }

   private static long mixSeed(long blueprintSeed, int floorCount, int roofProfileOrdinal) {
      long seed = blueprintSeed ^ (long)floorCount * 341873128712L ^ (long)roofProfileOrdinal * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      return seed;
   }

   public enum HouseStyle {
      WHITE_SLATE(14543032),
      WARM_CLAY(14140346),
      GRAY_CHARCOAL(12763842),
      BRICK_SLATE(11758425),
      PALE_STONE(14342095);

      private final int previewColor;

      HouseStyle(int previewColor) {
         this.previewColor = previewColor;
      }

      public int previewColor() {
         return this.previewColor;
      }
   }
}
