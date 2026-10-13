package com.yucareux.tellus.world.data.elevation;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.util.Mth;

record NormalizedElevationTileKey(
   int demSelectionMask,
   boolean highResOcean,
   int lod,
   int tileX,
   int tileZ
) {
   static final int TILE_SIZE = 256;
   static final int MAX_LOD = 17;

   NormalizedElevationTileKey {
      demSelectionMask = EarthGeneratorSettings.DemSelection.manual(demSelectionMask).enabledProviderMask();
      if (lod < 0 || lod > MAX_LOD) {
         throw new IllegalArgumentException("Invalid normalized elevation LOD: " + lod);
      }
   }

   static NormalizedElevationTileKey forProjectedMeters(
      double projectedX,
      double projectedZ,
      double resolutionMeters,
      EarthGeneratorSettings.DemSelection demSelection,
      boolean highResOcean
   ) {
      int lod = lodForResolutionMeters(resolutionMeters);
      int tileSpanMeters = tileSpanMeters(lod);
      int tileX = Mth.floor(projectedX / tileSpanMeters);
      int tileZ = Mth.floor(projectedZ / tileSpanMeters);
      return new NormalizedElevationTileKey(demSelection.enabledProviderMask(), highResOcean, lod, tileX, tileZ);
   }

   static NormalizedElevationTileKey forBlockCoordinates(
      double blockX,
      double blockZ,
      double worldScale,
      double resolutionMeters,
      EarthGeneratorSettings.DemSelection demSelection,
      boolean highResOcean
   ) {
      return forProjectedMeters(blockX * worldScale, blockZ * worldScale, resolutionMeters, demSelection, highResOcean);
   }

   static int lodForResolutionMeters(double resolutionMeters) {
      if (!Double.isFinite(resolutionMeters) || resolutionMeters <= 1.0) {
         return 0;
      } else {
         double clamped = Math.max(1.0, resolutionMeters);
         int lod = (int)Math.round(Math.log(clamped) / Math.log(2.0));
         return Mth.clamp(lod, 0, MAX_LOD);
      }
   }

   static int tileSpanMeters(int lod) {
      return TILE_SIZE << lod;
   }

   double sampleResolutionMeters() {
      return 1 << this.lod;
   }

   int tileSpanMeters() {
      return tileSpanMeters(this.lod);
   }

   double minProjectedX() {
      return (double)this.tileX * this.tileSpanMeters();
   }

   double minProjectedZ() {
      return (double)this.tileZ * this.tileSpanMeters();
   }

   double sampleProjectedX(int localX) {
      return this.minProjectedX() + localX * this.sampleResolutionMeters();
   }

   double sampleProjectedZ(int localZ) {
      return this.minProjectedZ() + localZ * this.sampleResolutionMeters();
   }

   EarthGeneratorSettings.DemSelection demSelection() {
      return EarthGeneratorSettings.DemSelection.manual(this.demSelectionMask);
   }

   String demSelectionFingerprint() {
      return this.demSelection().fingerprint();
   }

   NormalizedElevationTileKey withTile(int tileX, int tileZ) {
      return new NormalizedElevationTileKey(this.demSelectionMask, this.highResOcean, this.lod, tileX, tileZ);
   }
}
