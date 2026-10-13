package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SparseSections;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.21.1 port of {@code SerializableChunkDataSparseMixin} (was mc1219 / 1.21.9).
 *
 * <p>Skips writing the shared empty section into the save file. {@code SerializableChunkData} does not
 * exist in 1.21.1 (verified with javap: the only {@code *Serializable*} type in the jar is
 * {@code net.minecraft.world.ticks.SerializableTickContainer}), so this binds the equivalent steps of
 * {@code ChunkSerializer.write(ServerLevel, ChunkAccess)} instead.
 *
 * <h2>Verified injection points (javap, 1.21.1 merged jar)</h2>
 * {@code write} contains <b>exactly one</b> {@code LevelChunkSection.getStates()} call and
 * <b>exactly two</b> {@code ListTag.add(Object)} calls:
 * <pre>
 * 408: invokevirtual // Method net/minecraft/world/level/chunk/LevelChunkSection.getStates:()...
 * 532: invokevirtual // Method net/minecraft/nbt/ListTag.add:(Ljava/lang/Object;)Z   &lt;- sections  (ordinal 0)
 * 626: invokevirtual // Method net/minecraft/nbt/ListTag.add:(Ljava/lang/Object;)Z   &lt;- block_entities (ordinal 1)
 * </pre>
 * The section list is added at the <b>lower</b> bytecode offset, so {@code ordinal = 0} is the section
 * entry and {@code ordinal = 1} is the block-entity entry. The old 1.21.9 mixin used the same
 * {@code ordinal = 0} idea but on {@code SerializableChunkData.copyOf}, and additionally guarded
 * against the ordinal landing on the block-entity add by never casting the value back.
 *
 * <h2>How the section identity reaches the {@code ListTag.add} handler</h2>
 * A {@code @Redirect} handler only receives the arguments of the call it replaces, so the
 * {@code LevelChunkSection} object is not directly visible at {@code list.add(tag)}. It is captured
 * where it <b>is</b> visible — the single {@code getStates()} call in the same loop iteration — and
 * consumed immediately afterwards:
 * <pre>
 * 384: iload 15                        &lt;- hasSection
 * 386: ifeq  460                       &lt;- sections not touched when false
 * 389: aload 7; iload 14; aaload       &lt;- sections[sectionIndex]
 * 394: astore 19                       &lt;- the LevelChunkSection
 * 408: invokevirtual LevelChunkSection.getStates()
 * 510: aload 18; invokevirtual CompoundTag.isEmpty()
 * 515: ifne  536                       &lt;- empty tag is already skipped by vanilla
 * 528: aload 8; aload 18; invokevirtual ListTag.add(Object)
 * </pre>
 * Because the pairing is one {@code getStates()} → one {@code add()} within a single iteration, and
 * the captured value is <b>removed</b> on every {@code add()} whether or not it is used, a stale
 * value can never be applied to the wrong section.
 *
 * <h2>Light check</h2>
 * {@code SparseSections.skipOnSave} also requires that no block/sky light was present. The section
 * compound tag is the ground truth for that: {@code ChunkSerializer.write} only adds the
 * {@code "BlockLight"} / {@code "SkyLight"} byte arrays when the corresponding {@code DataLayer} is
 * non-null and not empty (offsets 460-507), so the absence of both keys in the tag is exactly the
 * "no light data" condition.
 *
 * <p><b>Not runtime-verified.</b> The anchor offsets above are read from the real 1.21.1 bytecode, but
 * this behaviour was never exercised in a running game (see the port report).
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerSparseSaveMixin {

   /** The section whose tag is being appended right now; set at {@code getStates()}, cleared at {@code ListTag.add}. */
   @Unique
   private static final ThreadLocal<LevelChunkSection> TELLUS$PENDING_SECTION = new ThreadLocal<>();

   @Redirect(
      method = "write",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;getStates()Lnet/minecraft/world/level/chunk/PalettedContainer;"
      )
   )
   private static Object tellus$rememberSection(LevelChunkSection section) {
      TELLUS$PENDING_SECTION.set(section);
      return section.getStates();
   }

   @Redirect(
      method = "write",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/nbt/ListTag;add(Ljava/lang/Object;)Z",
         ordinal = 0
      )
   )
   private static boolean tellus$skipSparseSection(ListTag list, Tag tag) {
      LevelChunkSection section = TELLUS$PENDING_SECTION.get();
      TELLUS$PENDING_SECTION.remove();
      if (section != null
            && SparseSections.isShared(section)
            && tag instanceof CompoundTag compound
            && !compound.contains("BlockLight")
            && !compound.contains("SkyLight")) {
         return false;
      }
      // Mirrors the 1.21.9 version's care: the value is put back untouched, never cast to a more
      // specific type, so a mis-targeted ordinal degrades to a pass-through instead of a
      // ClassCastException on the save thread.
      //
      // The boolean-returning add is java.util.AbstractList#add(E): javap on the 1.21.1 jar shows
      //   public class net.minecraft.nbt.ListTag extends net.minecraft.nbt.CollectionTag<Tag>
      // and ListTag itself only declares add(int, Tag) / addTag(int, Tag) / add(int, Object), so the
      // one-argument boolean add is inherited (erased to (Ljava/lang/Object;)Z). The invocation in
      // ChunkSerializer.write names the owner as ListTag, which is why the @At target above uses
      // Lnet/minecraft/nbt/ListTag;add(Ljava/lang/Object;)Z -- that is exactly what the bytecode says:
      //   528: aload 8; 530: aload 18; 532: invokevirtual ListTag.add:(Ljava/lang/Object;)Z
      return list.add(tag);
   }
}
