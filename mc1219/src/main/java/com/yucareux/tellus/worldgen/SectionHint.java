package com.yucareux.tellus.worldgen;

/**
 * Implemented on {@code ChunkAccess} by {@code HighestSectionHintMixin}.
 *
 * <p>Purpose: feed the "highest section index already materialised" hint into
 * {@code getHighestFilledSectionIndex} -- the vanilla method scans downward from the very top,
 * so in a 65536-tall world (4100 sections) whose content all sits at the bottom it needlessly
 * scans ~4000 empty sections every single time. At a 1:1 scale a huge number of chunks get loaded,
 * so that scan cost is paid over and over; it has to be removed.
 */
public interface SectionHint {
   /** Raise the hint (it only ever grows, so the hint stays at or above the true highest one and the scan result is still exact). */
   void tellus$raiseHighestHint(int sectionIndex);
}
