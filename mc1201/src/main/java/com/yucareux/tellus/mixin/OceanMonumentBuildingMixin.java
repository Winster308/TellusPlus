package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public class OceanMonumentBuildingMixin {
   // 2026-10-05: `ordinal = 0` was added to the @At.
   //   Trigger: an @At with no ordinal must match exactly one call site; a second `Math.max(II)` in
   //   `postProcess` (a Mojang tweak to the water box) would make this reference ambiguous
   //   (`expect` defaults to 1) and Mixin would reject the mixin while applying it.
   //   Consequence avoided: worldgen crash (structure start) instead of a working monument.
   //   Verified against the shipped bytecode: `postProcess` calls `Math.max(II)I` exactly once
   //   (javap: `WorldGenLevel.getSeaLevel()`, `bipush 64`, `invokestatic Math.max`), so ordinal 0 is that call.
   //   (`ordinal` is an @At attribute, not a @Redirect one, same as DimensionTypeMinYMixin.)
   //
   //   On the value that is lost on the Earth branch (deliberate, kept as is):
   //   vanilla computes `Math.max(level.getSeaLevel(), 64) - boundingBox.minY()` as the water level of the
   //   monument. The Earth branch returns `seaLevel` alone, dropping vanilla's floor of 64. That floor *is*
   //   lost whenever `seaLevel < 64`, and in Tellus that is the normal case: `EarthGeneratorSettings.resolveSeaLevel()`
   //   is the automatic sea level (`heightOffset`), which follows the real ocean surface of the chosen area and is
   //   regularly below Y=64. The consequence is intentional: the monument is flooded up to the *real* sea level
   //   instead of being lifted to the vanilla Y=64, otherwise the base would sit (64 - seaLevel) blocks above the
   //   water surface. Only the non-Earth branch keeps the vanilla maximum.
   @Redirect(
      method = "postProcess",
      at = @At(
         value = "INVOKE",
         target = "Ljava/lang/Math;max(II)I",
         ordinal = 0
      )
   )
   private int tellus$useTellusSeaLevel(
      int seaLevel,
      int minimumSeaLevel,
      WorldGenLevel level,
      StructureManager structureManager,
      ChunkGenerator generator,
      RandomSource random,
      BoundingBox box,
      ChunkPos chunkPos,
      BlockPos pos
   ) {
      return generator instanceof EarthChunkGenerator ? seaLevel : Math.max(seaLevel, minimumSeaLevel);
   }
}
