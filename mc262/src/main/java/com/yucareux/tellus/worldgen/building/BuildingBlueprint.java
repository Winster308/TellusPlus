package com.yucareux.tellus.worldgen.building;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

public record BuildingBlueprint(
   String groupId,
   long blueprintSeed,
   BuildingProfile profile,
   int baseY,
   int floorY,
   int roofBaseY,
   int topY,
   int minWorldX,
   int maxWorldX,
   int minWorldZ,
   int maxWorldZ,
   int entranceWorldX,
   int entranceWorldZ,
   Direction entranceFacing,
   int entranceWidth
) {
   public BuildingBlueprint {
      entranceWidth = Math.max(1, entranceWidth);
   }

   public int width() {
      return this.maxWorldX - this.minWorldX + 1;
   }

   public int depth() {
      return this.maxWorldZ - this.minWorldZ + 1;
   }

   public int floorCount() {
      return this.profile.floorCount();
   }

   public boolean interiorsEnabled() {
      return this.profile.interiorsEnabled();
   }

   public int floorBottomY(int floorIndex) {
      int clamped = Mth.clamp(floorIndex, 0, this.floorCount() - 1);
      return this.floorY + clamped * this.profile.storeyHeightBlocks();
   }

   public int floorTopY(int floorIndex) {
      int clearHeight = Math.max(1, this.profile.storeyHeightBlocks() - 1);
      return Math.min(this.roofBaseY - 1, this.floorBottomY(floorIndex) + clearHeight);
   }

   public int setbackForFloor(int floorIndex) {
      int cadence = this.profile.setbackEveryFloors();
      if (cadence <= 0 || this.profile.maxSetback() <= 0 || floorIndex < cadence) {
         return 0;
      }

      int inset = floorIndex / cadence;
      return Math.min(this.profile.maxSetback(), inset);
   }

   public boolean isEntranceCell(int worldX, int worldZ) {
      return switch (this.entranceFacing) {
         case NORTH, SOUTH -> worldZ == this.entranceWorldZ && Math.abs(worldX - this.entranceWorldX) <= this.entranceWidth / 2;
         case EAST, WEST -> worldX == this.entranceWorldX && Math.abs(worldZ - this.entranceWorldZ) <= this.entranceWidth / 2;
         default -> false;
      };
   }

   public boolean isActiveOnFloor(int boundaryDistance, int floorIndex) {
      int clamped = Mth.clamp(floorIndex, 0, this.floorCount() - 1);
      return boundaryDistance >= this.setbackForFloor(clamped);
   }

   public boolean isFacadeCell(int boundaryDistance, int floorIndex) {
      int clamped = Mth.clamp(floorIndex, 0, this.floorCount() - 1);
      return this.isActiveOnFloor(boundaryDistance, clamped) && boundaryDistance == this.setbackForFloor(clamped);
   }

   public int floorIndexAtY(int worldY) {
      if (worldY <= this.floorY) {
         return 0;
      }

      return Mth.clamp((worldY - this.floorY) / this.profile.storeyHeightBlocks(), 0, this.floorCount() - 1);
   }

   public int highestActiveFloor(int boundaryDistance) {
      for (int floor = this.floorCount() - 1; floor >= 0; floor--) {
         if (boundaryDistance >= this.setbackForFloor(floor)) {
            return floor;
         }
      }

      return 0;
   }

   public int roofBaseY(int boundaryDistance) {
      return this.floorY + (this.highestActiveFloor(boundaryDistance) + 1) * this.profile.storeyHeightBlocks();
   }

   public int roofTopY(int worldX, int worldZ, int boundaryDistance) {
      int roofTop = this.roofBaseY(boundaryDistance);
      int width = this.width();
      int depth = this.depth();
      int localX = worldX - this.minWorldX;
      int localZ = worldZ - this.minWorldZ;
      return switch (this.profile.roofProfile()) {
         case FLAT -> roofTop;
         case FLAT_SKYLIGHT -> roofTop + (localX > 1 && localX < width - 2 && localZ > 1 && localZ < depth - 2 ? 1 : 0);
         case FLAT_CROWN -> roofTop + crownRise(boundaryDistance);
         case GABLED_X -> roofTop + gabledRise(depth, localZ);
         case GABLED_Z -> roofTop + gabledRise(width, localX);
         case HIPPED -> roofTop + Math.min(gabledRise(width, localX), gabledRise(depth, localZ));
      };
   }

   public int topYForFloor(int floorIndex, int worldX, int worldZ, int boundaryDistance) {
      if (floorIndex >= this.floorCount() - 1) {
         return this.roofTopY(worldX, worldZ, boundaryDistance);
      }

      return this.floorTopY(floorIndex);
   }

   private int crownRise(int boundaryDistance) {
      if (this.profile.roofRise() <= 0) {
         return 0;
      }

      int threshold = Math.max(1, this.profile.maxSetback() + 1);
      return boundaryDistance >= threshold ? this.profile.roofRise() : 0;
   }

   private int gabledRise(int span, int position) {
      if (this.profile.roofRise() <= 0 || span <= 2) {
         return 0;
      }

      double half = (span - 1) * 0.5;
      double distance = Math.abs(position - half);
      double normalized = 1.0 - distance / Math.max(1.0, half);
      return Math.max(0, (int)Math.round(this.profile.roofRise() * normalized));
   }
}
