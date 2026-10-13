package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import java.util.List;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips the "empty shared section" when writing the save file.
 *
 * <p>Vanilla {@code copyOf} writes when {@code hasSection || has lighting data}, and
 * {@code hasSection} only checks whether the index is in range, which is always true for a dense array,
 * so a world 65536 tall writes <b>all 4096 sections into the save</b> (bloat, and on load they get re-instantiated,
 * which undoes the whole sparse optimisation).
 *
 * <p>Only the {@code List.add(SectionData)} call is intercepted here (ordinal = 0 is it; the same
 * method later has another List.add for block entity NBT):
 * an empty shared instance with no lighting data is not added.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataSparseMixin {

   @Redirect(
      method = "copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;",
      at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0)
   )
   private static boolean tellus$skipSparseSection(List<Object> list, Object value) {
      if (value instanceof SerializableChunkData.SectionData sectionData
            && SparseSections.skipOnSave(sectionData.chunkSection(), sectionData.blockLight(),
                  sectionData.skyLight())) {
         return false;
      }
      // The final step must not be cast back to SectionData: the ordinal of @Redirect only guarantees a hit on that one
      // List.add, and once Mojang reorders the adds inside copyOf the hit could land on the block entity NBT instead,
      // where that cast would throw ClassCastException and take the save thread down. Here the object is put back as is.
      return list.add(value);
   }
}
