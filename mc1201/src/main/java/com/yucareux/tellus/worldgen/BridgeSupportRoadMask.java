package com.yucareux.tellus.worldgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.function.IntUnaryOperator;

final class BridgeSupportRoadMask {
   private BridgeSupportRoadMask() {
   }

   static void retainRoadFreeSupportCells(
      IntArrayList cells,
      IntUnaryOperator bottomYAtIndex,
      int topY,
      byte[] resolvedClass,
      int[] resolvedDeckY,
      boolean[] bridgeOverlayPresent,
      int[] bridgeOverlayDeckY
   ) {
      int write = 0;
      int size = cells.size();

      for (int i = 0; i < size; i++) {
         int index = cells.getInt(i);
         int bottomY = bottomYAtIndex.applyAsInt(index);
         if (topY >= bottomY && !overlapsRoad(index, bottomY, topY, resolvedClass, resolvedDeckY, bridgeOverlayPresent, bridgeOverlayDeckY)) {
            cells.set(write++, index);
         }
      }

      cells.size(write);
   }

   static boolean overlapsRoad(
      int extIndex, int bottomY, int topY, byte[] resolvedClass, int[] resolvedDeckY, boolean[] bridgeOverlayPresent, int[] bridgeOverlayDeckY
   ) {
      if (topY < bottomY) {
         return false;
      }

      if (resolvedClass[extIndex] > 0) {
         int roadY = resolvedDeckY[extIndex];
         if (roadY >= bottomY && roadY <= topY) {
            return true;
         }
      }

      if (bridgeOverlayPresent[extIndex]) {
         int bridgeY = bridgeOverlayDeckY[extIndex];
         if (bridgeY >= bottomY && bridgeY <= topY) {
            return true;
         }
      }

      return false;
   }
}
