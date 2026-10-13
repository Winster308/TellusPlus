package com.yucareux.tellus.worldgen;

final class TerrainAnomalyRepair {
   private TerrainAnomalyRepair() {
   }

   static int repairHeightFromNeighbors(
      int center, int east, int west, int north, int south, int northEast, int northWest, int southEast, int southWest
   ) {
      int valid = 0;
      int sum = 0;
      int min = Integer.MAX_VALUE;
      int max = Integer.MIN_VALUE;
      if (east != Integer.MIN_VALUE) {
         valid++;
         sum += east;
         min = Math.min(min, east);
         max = Math.max(max, east);
      }

      if (west != Integer.MIN_VALUE) {
         valid++;
         sum += west;
         min = Math.min(min, west);
         max = Math.max(max, west);
      }

      if (north != Integer.MIN_VALUE) {
         valid++;
         sum += north;
         min = Math.min(min, north);
         max = Math.max(max, north);
      }

      if (south != Integer.MIN_VALUE) {
         valid++;
         sum += south;
         min = Math.min(min, south);
         max = Math.max(max, south);
      }

      if (valid < 3) {
         return center;
      } else {
         int mean = sum / valid;
         int meanDrop = mean - center;
         int edgeDrop = min - center;
         int span = max - min;
         int axisDropEW = east != Integer.MIN_VALUE && west != Integer.MIN_VALUE ? Math.min(east, west) - center : Integer.MIN_VALUE;
         int axisDropNS = north != Integer.MIN_VALUE && south != Integer.MIN_VALUE ? Math.min(north, south) - center : Integer.MIN_VALUE;
         int axisDrop = Math.max(axisDropEW, axisDropNS);
         boolean linearSeamEW = axisDropEW >= 30
            && north != Integer.MIN_VALUE
            && south != Integer.MIN_VALUE
            && Math.abs(north - center) <= 10
            && Math.abs(south - center) <= 10;
         boolean linearSeamNS = axisDropNS >= 30
            && east != Integer.MIN_VALUE
            && west != Integer.MIN_VALUE
            && Math.abs(east - center) <= 10
            && Math.abs(west - center) <= 10;
         int axisDropNESW = northEast != Integer.MIN_VALUE && southWest != Integer.MIN_VALUE ? Math.min(northEast, southWest) - center : Integer.MIN_VALUE;
         int axisDropNWSE = northWest != Integer.MIN_VALUE && southEast != Integer.MIN_VALUE ? Math.min(northWest, southEast) - center : Integer.MIN_VALUE;
         boolean linearSeamNESW = axisDropNESW >= 30
            && east != Integer.MIN_VALUE
            && west != Integer.MIN_VALUE
            && Math.abs(east - center) <= 10
            && Math.abs(west - center) <= 10;
         boolean linearSeamNWSE = axisDropNWSE >= 30
            && north != Integer.MIN_VALUE
            && south != Integer.MIN_VALUE
            && Math.abs(north - center) <= 10
            && Math.abs(south - center) <= 10;
         int[] ring = new int[]{east, west, north, south, northEast, northWest, southEast, southWest};
         boolean severe = axisDrop >= 34 || meanDrop >= 26 || linearSeamEW || linearSeamNS || linearSeamNESW || linearSeamNWSE;
         if (severe && (edgeDrop >= 20 || linearSeamEW || linearSeamNS || linearSeamNESW || linearSeamNWSE)) {
            if (span > 44 && axisDrop < 44 && !linearSeamEW && !linearSeamNS && !linearSeamNESW && !linearSeamNWSE) {
               return center;
            } else {
               boolean linearSeam = linearSeamEW || linearSeamNS || linearSeamNESW || linearSeamNWSE;
               int repairMargin = linearSeam ? 0 : 0;
               int target = mean - repairMargin;
               if ((axisDropEW >= 34 || linearSeamEW) && east != Integer.MIN_VALUE && west != Integer.MIN_VALUE) {
                  target = Math.max(target, Math.min(east, west) - repairMargin);
               }

               if ((axisDropNS >= 34 || linearSeamNS) && north != Integer.MIN_VALUE && south != Integer.MIN_VALUE) {
                  target = Math.max(target, Math.min(north, south) - repairMargin);
               }

               if ((axisDropNESW >= 34 || linearSeamNESW) && northEast != Integer.MIN_VALUE && southWest != Integer.MIN_VALUE) {
                  target = Math.max(target, Math.min(northEast, southWest) - repairMargin);
               }

               if ((axisDropNWSE >= 34 || linearSeamNWSE) && northWest != Integer.MIN_VALUE && southEast != Integer.MIN_VALUE) {
                  target = Math.max(target, Math.min(northWest, southEast) - repairMargin);
               }

               int highNeighborCount = 0;
               int highNeighborMin = Integer.MAX_VALUE;
               int highNeighborMax = Integer.MIN_VALUE;

               for (int value : ring) {
                  if (value != Integer.MIN_VALUE && value - center >= 24) {
                     highNeighborCount++;
                     highNeighborMin = Math.min(highNeighborMin, value);
                     highNeighborMax = Math.max(highNeighborMax, value);
                  }
               }

               if (highNeighborCount >= 5 && highNeighborMax - highNeighborMin <= 36) {
                  target = Math.max(target, highNeighborMin);
                  linearSeam = true;
               }

               int cap = linearSeam ? max : max - 1;
               target = Math.min(target, cap);
               return Math.max(center, target);
            }
         } else {
            int lowNeighborCount = 0;
            int lowNeighborMin = Integer.MAX_VALUE;
            int lowNeighborMax = Integer.MIN_VALUE;

            for (int value : ring) {
               if (value != Integer.MIN_VALUE && center - value >= 24) {
                  lowNeighborCount++;
                  lowNeighborMin = Math.min(lowNeighborMin, value);
                  lowNeighborMax = Math.max(lowNeighborMax, value);
               }
            }

            return lowNeighborCount >= 5 && lowNeighborMax - lowNeighborMin <= 36 && center - lowNeighborMax >= 24 ? lowNeighborMax : center;
         }
      }
   }
}
