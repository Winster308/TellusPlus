package com.yucareux.tellus.worldgen;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h2>2026-10-05: process-wide high-water mark of the sections that have really been materialized</h2>
 *
 * <p>{@link HighestSectionHintMixin} scans {@code getHighestFilledSectionIndex} down from a per-chunk hint
 * instead of down from the top of the array. That hint is only allowed to be an <b>upper bound</b> of the
 * highest non-air section, otherwise the scan starts too low and the heightmap / lighting / spawn decisions
 * get a value that is too low.
 *
 * <p>Two things can turn a slot from "the shared empty section" into "a real section holding content":
 * <ul>
 *   <li>{@code SparseSections.materialize(chunk, blockY)} &mdash; it has the {@code ChunkAccess} at hand, so it
 *       raises the per-chunk hint directly through {@code SectionHint};</li>
 *   <li>{@code SparseSections.materializeSection(sections, index)} &mdash; the "fast fill" path that every writer
 *       holding only the section array uses (5 call sites in {@code EarthChunkGenerator}, each of them a section
 *       written straight into the palette). It gets <b>no chunk</b>, so it cannot reach the per-chunk hint,
 *       and this mark is what covers it.</li>
 * </ul>
 *
 * <p>2026-10-06: this class used to live in {@code com.yucareux.tellus.mixin}, which made it
 * <b>unloadable</b>: Mixin refuses to load an ordinary class out of a package it reserves for
 * mixin classes ("is in a defined (\"c\") package but cannot be loaded"). javac and the jar
 * task both accept it, so it only surfaced when {@code runServer} actually triggered the mixin
 * transform. It now lives in {@code worldgen} next to {@link SectionHint} and
 * {@link SparseSections}, the two things it collaborates with.
 *
 * <p>It is a single {@link AtomicInteger} and the update is one accumulating {@code max}, so it is safe under
 * the concurrent section fill pool; the cheap {@code get()} + compare runs first, so the common case (a write
 * below the current mark) never touches the CAS.
 *
 * <p>It is only ever used to <b>raise</b> the point a scan starts from, so being too generous costs a few extra
 * {@code hasOnlyAir()} calls and can never produce a wrong answer.
 *
 * <p>Why a plain class and not a {@code @Unique} static field of the mixin: an injector handler body is copied
 * into the class it is injected into (here {@code SparseSections}), and Mixin renames {@code @Unique} members
 * while merging them into <i>their</i> target. A field of an ordinary class is referenced by name, so it stays
 * exactly one object no matter which class the calling code ended up in.
 */
public final class HighestSectionHintState {

   /** -2 = nothing has been materialized anywhere yet (the same "unknown" value the per-chunk hint uses). */
   private static final AtomicInteger HIGHEST_MATERIALIZED = new AtomicInteger(-2);

   private HighestSectionHintState() {
   }

   /** Monotonic raise; never lowers the mark. */
   public static void raise(int sectionIndex) {
      if (sectionIndex <= HIGHEST_MATERIALIZED.get()) {
         return;   // fast path, taken by almost every block write
      }
      HIGHEST_MATERIALIZED.accumulateAndGet(sectionIndex, Math::max);
   }

   /** The highest section index materialized anywhere so far (-2 when there was none). */
   public static int highest() {
      return HIGHEST_MATERIALIZED.get();
   }
}
