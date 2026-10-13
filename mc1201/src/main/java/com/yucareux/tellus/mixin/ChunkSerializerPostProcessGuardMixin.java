package com.yucareux.tellus.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Guard the saved post-processing replay against a dimension that SHRANK between sessions.
 *
 * <h2>1.20.1 port note -- this is a re-implementation, not a copy</h2>
 * On 1.21.9 this guard lived on {@code SerializableChunkData} and redirected
 * {@code ChunkAccess.addPackedPostProcess(ShortList, int)}. <b>Neither the class nor that overload
 * exists on 1.20.1</b> ({@code SerializableChunkData} is 1.20.2+; it was introduced when chunk
 * serialisation was split out of {@code ChunkSerializer}). The owner of the replay loop on 1.20.1 is
 * {@code net.minecraft.world.level.chunk.storage.ChunkSerializer.read(...)}, and the method it calls
 * is the <b>other</b> overload, {@code ChunkAccess.addPackedPostProcess(short, int)}.
 *
 * <h2>The crash surface is genuinely present in 1.20.1 (verified with javap, not assumed)</h2>
 * <ol>
 *   <li>{@code ChunkSerializer.read} replays the <b>file's</b> list: the outer loop index is bounded by
 *       {@code ListTag.size()} of the saved {@code PostProcessing} list, and for each entry it calls
 *       {@code ChunkAccess.addPackedPostProcess(short, int)} with that <b>file-space</b> index.
 *       (javap -p -c ChunkSerializer: offsets 1090..1148, the call at 1136 carries the loop counter.)</li>
 *   <li>{@code ChunkAccess}'s constructor sizes {@code postProcessing} from
 *       {@code LevelHeightAccessor.getSectionsCount()} of the <b>current</b> dimension.
 *       (javap -p -c ChunkAccess: offset 77 {@code getSectionsCount}, 82 {@code anewarray ShortList},
 *       85 {@code putfield postProcessing}.)</li>
 *   <li>{@code ChunkAccess.addPackedPostProcess(short,int)} forwards to the private static
 *       {@code getOrCreateOffsetList(ShortList[], int)}, which does a bare {@code aaload} with
 *       <b>no bounds check</b>. (javap -p -c: offset 2 {@code aaload}, and on the null branch
 *       offset 15 {@code aastore} -- both index unguarded.)</li>
 * </ol>
 * So a chunk written by a taller dimension and read back by a shorter one produces exactly the
 * {@code ArrayIndexOutOfBoundsException} in {@code getOrCreateOffsetList} that the 1.21.9 guard was
 * written to stop. The failure mode -- many such chunks inside one spawn-chunk request, followed by
 * the server watchdog killing the process -- is unchanged.
 *
 * <p>Only the loop-bound mismatch is fixed here; nothing about the on-disk format changes.
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerPostProcessGuardMixin {

   @Unique
   private static boolean tellus$postProcessWarned;

   /**
    * Mirrors {@code ChunkAccess.addPackedPostProcess(short, int)} but refuses indexes the current
    * dimension cannot hold. Bound to the single replay call inside {@code read()}.
    *
    * <p>The handler must be {@code static} because {@code ChunkSerializer.read} is static; the
    * redirected call site is inside it.
    */
   @Redirect(
      method = "read",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/chunk/ChunkAccess;addPackedPostProcess(SI)V"
      )
   )
   private static void tellus$guardReplay(ChunkAccess chunk, short packedPos, int sectionIndex) {
      if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
         if (!tellus$postProcessWarned) {
            tellus$postProcessWarned = true;
            com.yucareux.tellus.Tellus.LOGGER.warn(
               "[TellusCN] chunk was saved by a TALLER dimension (post-processing section {} does not exist in the current {}-section world); "
                  + "out-of-range entries are dropped for this chunk and further occurrences are silent",
               sectionIndex, chunk.getSectionsCount());
         }
         return;
      }
      chunk.addPackedPostProcess(packedPos, sectionIndex);
   }
}
