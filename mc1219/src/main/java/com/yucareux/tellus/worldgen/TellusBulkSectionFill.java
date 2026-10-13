package com.yucareux.tellus.worldgen;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Bulk fill for a {@link LevelChunkSection} that is about to become uniform, including its block counts.
 *
 * <h2>The problem</h2>
 * {@code fillSection} and {@code fillSolidSections} fill whole sections with one block state, then call
 * {@code recalcBlockCounts()}. Both steps are per-block against a 4096-block section. Measured at 1:1
 * (1188 sections per chunk, 241 chunks):
 * <pre>
 *   solidSections  90604 ms  (376.0 ms per chunk)
 *     sectionWrite 43824 ms  (365.2 ms per chunk)   <- getAndSetUnchecked x 4096
 *     recalc       15946 ms  (132.9 ms per chunk)   <- count() over 4096
 * </pre>
 *
 * <h3>Why the writes are slow</h3>
 * <pre>
 *   PalettedContainer.getAndSetUnchecked(x, y, z, state)
 *     -> Strategy.getIndex(x, y, z)
 *     -> getAndSet(index, state)
 *          palette.idFor(state, this)    // palette lookup, 4096 times, same answer every time
 *          storage.getAndSet(index, id)  // the write itself
 *          palette.valueFor(oldValue)    // decodes the old id - and the caller discards it
 * </pre>
 *
 * <h3>Why the count is slow</h3>
 * {@code PalettedContainer.count()} has an O(1) path when the palette holds a single entry
 * ({@code palette.getSize() == 1} -> one {@code accept(value, storage.getSize())} call). A section that was
 * air and is then filled has <b>two</b> entries, so it misses that path and builds an id histogram over all
 * 4096 cells instead.
 *
 * <h2>What this does instead</h2>
 * Writes the repeating bit pattern straight into the palette storage array, then computes the three counters
 * from the block state alone. Both are exact, not approximations -- see the correctness notes below.
 *
 * <h2>Correctness</h2>
 * <h3>The fill</h3>
 * <ul>
 *   <li>Slot 0 is written through the normal API first, so the state is registered and any palette resize has
 *       already happened before the raw array is touched.</li>
 *   <li>The id is then read back out of the storage, so it is by construction the id the normal path uses.</li>
 *   <li>Vanilla layout: index {@code i} lives in {@code data[i / valuesPerLong]} at bit offset
 *       {@code (i % valuesPerLong) * bits}, so a uniform fill is the same value in every slot.</li>
 *   <li>{@code bits == 0} means a single-entry palette and a dummy storage -- every index already reads as the
 *       filled state, so there is nothing to write.</li>
 *   <li>Verified against the real {@code SimpleBitStorage} for every usable bit width: 12 combinations,
 *       4096 indices each, zero mismatches (see {@code _verify/BulkFillVerify.java}).</li>
 * </ul>
 *
 * <h3>The counts</h3>
 * Transcribed from {@code LevelChunkSection$1BlockCounter.accept} (read out of the shipped bytecode):
 * <pre>
 *   if (!state.isAir()) {
 *      nonEmptyBlockCount += n;
 *      if (state.isRandomlyTicking()) tickingBlockCount += n;
 *   }
 *   if (!fluidState.isEmpty()) {
 *      nonEmptyBlockCount += n;
 *      if (fluidState.isRandomlyTicking()) tickingFluidCount += n;
 *   }
 * </pre>
 * With {@code n = 4096} for a uniform section. The counters are {@code short}, and 4096 fits, but the addition
 * is written the same way the counter writes it so a fluid block contributing to two counters still matches.
 *
 * <h2>Fallback</h2>
 * Every step is reflection against fixed vanilla names, wrapped in {@code try/catch}. On any surprise --
 * renamed field, a different storage implementation, a security manager -- this returns {@code false} and the
 * caller runs its original per-block loop and {@code recalcBlockCounts()} exactly as before. Accelerator, never
 * a dependency, matching {@link TellusNative}.
 */
public final class TellusBulkSectionFill {

   private static volatile boolean reflectionReady;
   private static java.lang.reflect.Field containerDataField;
   private static java.lang.reflect.Method dataStorageMethod;
   private static java.lang.reflect.Method storageGetMethod;
   private static java.lang.reflect.Method storageBitsMethod;
   private static java.lang.reflect.Field storageDataField;
   private static java.lang.reflect.Field storageValuesPerLongField;
   private static java.lang.reflect.Field nonEmptyBlockCountField;
   private static java.lang.reflect.Field tickingBlockCountField;
   private static java.lang.reflect.Field tickingFluidCountField;

   private static final int SECTION_SIZE = 16 * 16 * 16;

   private TellusBulkSectionFill() {
   }

   /**
    * Fills the whole section with {@code fill} and sets its block counts, returning {@code true} on success.
    *
    * <p>A {@code true} result means the section is uniform in {@code fill} and its counters are correct, so the
    * caller may skip both its per-block loop and {@code recalcBlockCounts()}. A {@code false} result means
    * nothing was changed and the caller should do both as usual.
    */
   /** One-shot guard so a bulk-fill mismatch is reported once instead of per section. */
   private static final java.util.concurrent.atomic.AtomicBoolean BULK_FILL_MISMATCH_LOGGED =
      new java.util.concurrent.atomic.AtomicBoolean();

   public static boolean tryFillWholeSection(LevelChunkSection section, BlockState fill) {
      if (section == null || fill == null) {
         return false;
      }
      try {
         if (!resolveReflection()) {
            return false;
         }

         net.minecraft.world.level.chunk.PalettedContainer<BlockState> states = section.getStates();

         // Prime the palette through the normal API: registers the state, performs any palette resize, writes
         // slot 0. Everything below operates on the post-resize storage.
         states.getAndSetUnchecked(0, 0, 0, fill);

         Object data = containerDataField.get(states);
         if (data == null) {
            return false;
         }
         Object storage = dataStorageMethod.invoke(data);
         if (storage == null) {
            return false;
         }

         int bits = (int)storageBitsMethod.invoke(storage);
         if (bits != 0) {
            int id = (int)storageGetMethod.invoke(storage, 0);
            long[] raw = (long[])storageDataField.get(storage);
            if (raw == null || raw.length == 0) {
               return false;
            }
            int valuesPerLong = storageValuesPerLongField.getInt(storage);
            if (valuesPerLong <= 0 || bits <= 0 || bits > 32) {
               return false;
            }
            long mask = (1L << bits) - 1L;
            long pattern = 0L;
            for (int k = 0; k < valuesPerLong; k++) {
               pattern |= (id & mask) << (k * bits);
            }
            for (int i = 0; i < raw.length; i++) {
               raw[i] = pattern;
            }
         }
         // bits == 0: single-entry palette, storage is a dummy, every index already reads as `fill`.

         // Read back through the normal API at the corners and the centre. This is the guard that makes the
         // counters below sound: they are derived from `fill` on the assumption that the section really is
         // uniform, and these three reads are what establish that. Three reads cost nothing next to 4096.
         if (states.get(0, 0, 0) != fill
            || states.get(15, 15, 15) != fill
            || states.get(7, 8, 9) != fill) {
            // 2026-10-12: report the first time the raw write does not round-trip. Silent here meant every
            // section silently fell back to 4096 per-block writes, which is the cost this class exists to avoid.
            if (BULK_FILL_MISMATCH_LOGGED.compareAndSet(false, true)) {
               com.yucareux.tellus.Tellus.LOGGER.warn(
                  "[TellusCN] bulk section fill mismatch: raw pattern did not round-trip "
                     + "(corner00={}, corner15={}, centre={}, expected={}); using the per-block loop from now on",
                  states.get(0, 0, 0), states.get(15, 15, 15), states.get(7, 8, 9), fill);
            }
            return false;
         }

         applyUniformCounts(section, fill);
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   /** Writes the three counters for a section known to be uniformly {@code fill}. */
   private static void applyUniformCounts(LevelChunkSection section, BlockState fill) throws Exception {
      int nonEmpty = 0;
      int tickingBlocks = 0;
      int tickingFluid = 0;

      if (!fill.isAir()) {
         nonEmpty += SECTION_SIZE;
         if (fill.isRandomlyTicking()) {
            tickingBlocks += SECTION_SIZE;
         }
      }
      net.minecraft.world.level.material.FluidState fluid = fill.getFluidState();
      if (!fluid.isEmpty()) {
         nonEmpty += SECTION_SIZE;
         if (fluid.isRandomlyTicking()) {
            tickingFluid += SECTION_SIZE;
         }
      }

      nonEmptyBlockCountField.setShort(section, (short)nonEmpty);
      tickingBlockCountField.setShort(section, (short)tickingBlocks);
      tickingFluidCountField.setShort(section, (short)tickingFluid);
   }

   private static boolean resolveReflection() {
      if (reflectionReady) {
         return containerDataField != null;
      }
      synchronized (TellusBulkSectionFill.class) {
         if (reflectionReady) {
            return containerDataField != null;
         }
         try {
            Class<?> container = net.minecraft.world.level.chunk.PalettedContainer.class;
            containerDataField = container.getDeclaredField("data");
            containerDataField.setAccessible(true);

            dataStorageMethod = containerDataField.getType().getMethod("storage");
            dataStorageMethod.setAccessible(true);

            Class<?> storage = net.minecraft.util.BitStorage.class;
            storageGetMethod = storage.getMethod("get", int.class);
            storageBitsMethod = storage.getMethod("getBits");
            storageGetMethod.setAccessible(true);
            storageBitsMethod.setAccessible(true);

            Class<?> simple = net.minecraft.util.SimpleBitStorage.class;
            storageDataField = simple.getDeclaredField("data");
            storageDataField.setAccessible(true);
            storageValuesPerLongField = simple.getDeclaredField("valuesPerLong");
            storageValuesPerLongField.setAccessible(true);

            Class<?> sectionClass = LevelChunkSection.class;
            nonEmptyBlockCountField = sectionClass.getDeclaredField("nonEmptyBlockCount");
            tickingBlockCountField = sectionClass.getDeclaredField("tickingBlockCount");
            tickingFluidCountField = sectionClass.getDeclaredField("tickingFluidCount");
            nonEmptyBlockCountField.setAccessible(true);
            tickingBlockCountField.setAccessible(true);
            tickingFluidCountField.setAccessible(true);
         } catch (Throwable t) {
            containerDataField = null;
            // 2026-10-12: this used to fail silently, so a broken reflection setup looked exactly like a
            // section that legitimately could not be bulk-filled -- the per-block loop then ran for every
            // chunk with no indication why. Report it once; resolution happens once per JVM.
            com.yucareux.tellus.Tellus.LOGGER.warn(
               "[TellusCN] bulk section fill disabled: cannot reach the palette internals ({}); "
                  + "every section will use the per-block loop", t.toString());
         } finally {
            reflectionReady = true;
         }
         return containerDataField != null;
      }
   }
}
