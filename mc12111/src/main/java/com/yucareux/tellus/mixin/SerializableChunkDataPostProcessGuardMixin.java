package com.yucareux.tellus.mixin;

import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Guard the saved post-processing replay against a dimension that SHRANK between sessions.
 *
 * <h2>Measured crash (2026-10-07, {@code runServer} on the {@code followtest} world)</h2>
 * <pre>
 * "Server Watchdog" ... Considering it to be crashed, server will forcibly shutdown.
 *   Entry counts: chunk/load: ArrayIndexOutOfBoundsException x 501
 * at ChunkAccess.getOrCreateOffsetList(ChunkAccess.java:386)
 * at ProtoChunk.addPackedPostProcess(ProtoChunk.java:284)
 * at SerializableChunkData.read(SerializableChunkData.java:308)
 * </pre>
 *
 * <h2>Root cause (reproduced end to end, not inferred)</h2>
 * <ol>
 *   <li>Vanilla's {@code read()} allocates the replay loop bound from the <b>file</b>:
 *       {@code postProcessingSections = new ShortList[nbtList.size()]}, then calls
 *       {@code chunk.addPackedPostProcess(list, i)} for {@code i in 0..n-1} (javap: bytecode 640-667,
 *       the loop condition compares {@code i} against {@code postProcessingSections.arraylength}).</li>
 *   <li>{@code addPackedPostProcess} indexes {@code this.postProcessing}, which was sized by
 *       {@code getSectionsCount()} of the <b>current</b> dimension (javap of the constructor:
 *       {@code anewarray ShortList} right after {@code getSectionsCount()}).</li>
 *   <li>The previous session generated chunks while the dimension was still the vanilla
 *       {@code -368..431} (50 sections — {@code ensureDynamicDimensionPack} only takes effect on the
 *       <b>next</b> launch, exactly as its own comment says). The region file's
 *       {@code PostProcessing} list therefore has 50 entries (parsed out of {@code r.0.0.mca} directly).</li>
 *   <li>This session loaded the world with a 480-block dimension (30 sections): entry 30 indexes a
 *       30-slot array → AIOOBE on every such chunk. The failures pile up inside one spawn-chunk
 *       request and the watchdog kills the server 60 s later.</li>
 * </ol>
 *
 * <p>The tall-world comment inside {@code Tellus.ensureDynamicDimensionPack} already documented the
 * "takes effect next entry" half of this; this mixin covers the other half: a world saved by a
 * <b>taller</b> dimension being loaded by a <b>shorter</b> one. Dropping the out-of-range entries is
 * the honest degradation — those packed positions live above the current world top, so the current
 * dimension has nowhere to apply them anyway. Without the guard the choice is not "keep the data",
 * it is "crash the server".
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataPostProcessGuardMixin {

   @Unique
   private static boolean tellus$postProcessWarned;

   /**
    * Mirrors {@code ChunkAccess.addPackedPostProcess(ShortList, int)} but refuses indexes the
    * current dimension cannot hold. Bound to the single replay call inside {@code read()}
    * (bytecode offset 664 in 1.21.9) — the only place a saved list length can disagree with the
    * live section count.
    */
   @Redirect(
      method = "read",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/chunk/ChunkAccess;addPackedPostProcess(Lit/unimi/dsi/fastutil/shorts/ShortList;I)V"
      )
   )
   private void tellus$guardReplay(ChunkAccess chunk, ShortList offsets, int sectionIndex) {
      if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
         if (!tellus$postProcessWarned && !offsets.isEmpty()) {
            tellus$postProcessWarned = true;
            com.yucareux.tellus.Tellus.LOGGER.warn(
               "[TellusCN] chunk was saved by a TALLER dimension (post-processing section {} does not exist in the current {}-section world); "
                  + "out-of-range entries are dropped for this chunk and further occurrences are silent",
               sectionIndex, chunk.getSectionsCount());
         }
         return;
      }
      chunk.addPackedPostProcess(offsets, sectionIndex);
   }
}
