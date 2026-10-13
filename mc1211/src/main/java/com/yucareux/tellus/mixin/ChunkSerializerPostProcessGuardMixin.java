package com.yucareux.tellus.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.21.1 port of {@code SerializableChunkDataPostProcessGuardMixin} (was mc1219 / 1.21.9).
 *
 * <h2>Why the class had to be re-targeted</h2>
 * 1.21.9 saves chunks through {@code SerializableChunkData}; <b>1.21.1 does not have that class at
 * all</b>. Verified with javap on the 1.21.1 merged jar: {@code net/minecraft/world/level/chunk/storage/}
 * contains {@code ChunkSerializer}, {@code ChunkStorage}, {@code RegionFileStorage}, … and no
 * {@code SerializableChunkData} (the only {@code *Serializable*} type in the whole jar is
 * {@code net.minecraft.world.ticks.SerializableTickContainer}). The 1.21.1 equivalent of
 * {@code SerializableChunkData.read} is {@code ChunkSerializer.read(ServerLevel, PoiManager,
 * RegionStorageInfo, ChunkPos, CompoundTag)}.
 *
 * <h2>The bug being guarded (unchanged)</h2>
 * A world saved by a <b>taller</b> dimension, then loaded by a <b>shorter</b> one, replays packed
 * post-processing positions with an index the live chunk cannot hold:
 * <pre>
 * ArrayIndexOutOfBoundsException
 *   at ChunkAccess.getOrCreateOffsetList(ChunkAccess.java)
 *   at ProtoChunk.addPackedPostProcess(ProtoChunk.java)
 *   at ChunkSerializer.read(ChunkSerializer.java)
 * </pre>
 * The loop bound comes from the <b>file</b> while the target array is sized by the <b>current</b>
 * dimension, so the two disagree exactly when the dimension shrank between sessions.
 *
 * <h2>Verified injection point (javap, 1.21.1 merged jar)</h2>
 * <pre>
 * 1144: aload 27
 * 1146: invokevirtual  // Method net/minecraft/nbt/ListTag.size:()I   &lt;- bound from the SAVED file
 * 1149: if_icmpge 1172
 * 1152: aload 21
 * 1154: aload 27
 * 1156: iload 28
 * 1158: invokevirtual  // Method net/minecraft/nbt/ListTag.getShort:(I)S
 * 1161: iload 26                                                  &lt;- section index
 * 1163: invokevirtual  // Method net/minecraft/world/level/chunk/ChunkAccess.addPackedPostProcess:(SI)V
 * 1166: iinc 28, 1
 * 1169: goto 1142
 * </pre>
 * This is the <b>only</b> {@code addPackedPostProcess} call in {@code ChunkSerializer} (grep over the
 * whole disassembly: one hit). Note the 1.21.1 descriptor is {@code (SI)V} — a packed <b>short</b> plus
 * a section index. 1.21.9 changed it to {@code (ShortList, I)V}, which is why the old mixin's
 * {@code target} string would never have matched here.
 *
 * <h2>Bounds check</h2>
 * {@code addPackedPostProcess} indexes the {@code postProcessing} array, which the {@code ChunkAccess}
 * constructor allocates with {@code LevelHeightAccessor.getSectionsCount()}:
 * <pre>
 * 81: invokeinterface // InterfaceMethod LevelHeightAccessor.getSectionsCount:()I
 * 86: anewarray       // class it/unimi/dsi/fastutil/shorts/ShortList
 * 89: putfield        // Field postProcessing:[Lit/unimi/dsi/fastutil/shorts/ShortList;
 * </pre>
 * and the {@code sections} array in the same constructor (offset 62-70) is allocated from the
 * <b>same</b> call. So {@code chunk.getSections().length} is an exact bound for the index, and it is
 * used here instead of {@code getSectionsCount()} only because {@code ChunkAccess} does not declare
 * the latter (it implements {@code BlockGetter, NoiseBiomeSource, LightChunk, StructureAccess} — not
 * {@code LevelHeightAccessor}).
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerPostProcessGuardMixin {

   @Unique
   private static boolean tellus$postProcessWarned;

   @Redirect(
      method = "read",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/chunk/ChunkAccess;addPackedPostProcess(SI)V"
      )
   )
   private static void tellus$guardReplay(ChunkAccess chunk, short packedPos, int sectionIndex) {
      int sectionCount = chunk.getSections().length;
      if (sectionIndex < 0 || sectionIndex >= sectionCount) {
         if (!tellus$postProcessWarned) {
            tellus$postProcessWarned = true;
            com.yucareux.tellus.Tellus.LOGGER.warn(
               "[TellusCN] chunk was saved by a TALLER dimension (post-processing section {} does not exist in the current {}-section world); "
                  + "out-of-range entries are dropped for this chunk and further occurrences are silent",
               sectionIndex, sectionCount);
         }
         return;
      }
      chunk.addPackedPostProcess(packedPos, sectionIndex);
   }
}
