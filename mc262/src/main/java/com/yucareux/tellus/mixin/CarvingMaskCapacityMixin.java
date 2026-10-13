package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.TellusCarvingMaskCapacity;
import com.yucareux.tellus.worldgen.TellusLayout;
import net.minecraft.world.level.chunk.CarvingMask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Caps the <b>initial capacity</b> of the BitSet inside {@code CarvingMask}.
 *
 * <h2>The problem</h2>
 *
 * <p>The bytecode of vanilla {@code CarvingMask(int height, int minY)} is:
 * <pre>
 * new BitSet
 * sipush 256
 * iload_1              // height
 * imul                 // 256 * height   &lt;- preallocated for the <b>whole world height</b>
 * invokespecial BitSet."&lt;init&gt;"(I)V
 * </pre>
 * A vanilla world 384 tall asks for 98,304 bit, about 12 KB: harmless.<br>
 * Our world 65,600 tall asks for <b>16,793,600 bit, about 2.0 MB</b>, and <b>every chunk</b> gets one
 * (allocated by {@code ChunkAccess.getOrCreateCarvingMask()} and by the {@code ProtoChunk} constructor).
 * With a Voxy preload radius of 96 about 37,000 chunks are alive at once, so that one BitSet alone needs
 * <b>about 78 GB</b>.
 *
 * <h2>How the cap is derived (2026-10-05, rewritten)</h2>
 *
 * <p>The mask is indexed by section <i>relative to the chunk's own min Y</i>
 * ({@code (y - minY) >> 4}), and for a generated Earth chunk that origin is
 * {@link TellusLayout#WORLD_MIN_Y} (default <b>-2032</b>, not the vanilla -64).
 * So the number of sections that has to be covered is measured from there, up to the top of the window the
 * carver can possibly write in, which is the ceiling {@code TellusNoiseSettingsAdapter} clamps the carving
 * noise to: {@code noiseMinY + noiseHeight <= 2032} (the same 2032 the {@code NoiseSettings.guardY} check uses).
 * With the default layout that is {@code ceil((2032 - -2032) / 16) = 254} sections = 65,024 bit = 8 KB.
 *
 * <p><b>The previous version derived the 64 sections from the vanilla {@code minY = -64}</b>
 * ("the carver works in Y in [24, 300], which is sections 5..23"). That is wrong twice over for the real layout:
 * <ul>
 *   <li>64 sections counted from <b>-2032</b> only reach Y = -1008, and</li>
 *   <li>the carving band is nowhere near -1008: since 2026 the noise window is <b>centred on the sea level</b>
 *       (cave ceiling Y=220 lands in section {@code (220 + 2032) >> 4 = 140}).</li>
 * </ul>
 * Consequence avoided: {@code CarvingMask.findIndex} returns -1 for every index outside the BitSet, so the
 * carver silently carved <b>nothing</b> - caves were simply gone, with no error anywhere.
 *
 * <p>The old {@code 65,536 bit} figure mentioned in the class text was the same bug wearing a different hat;
 * {@code TellusVanillaCarverRunner} already describes the resulting mask as "only 8 KB", which is what this
 * derivation produces.
 *
 * <h2>Why this change is safe</h2>
 *
 * <p>Only the <b>initial capacity</b> changes. {@code BitSet} grows on demand,
 * and the meaning of the bits plus the results of {@code set/get}, {@code toArray()} and {@code stream()}
 * do not change by even one bit. The cap is {@code Math.min(nbits, BITS_PER_SECTION * MASK_SECTIONS)}.
 * A vanilla 384-tall world asks for 98,304 bit, so the min pulls it down; that is still harmless, because the
 * carving noise window can never reach above Y=2031 no matter how tall the world is.
 *
 * <p>Operators can still raise (or lower) it with {@code -Dtellus.caves.maskSections=N}, and
 * {@code -Dtellus.caves.noiseHeight} is used as a <b>floor</b>: a window that is taller than the mask would
 * silently disable carving above it, so the mask is never allowed to be smaller than the window it has to hold.
 * Even if something really is written further up there is no problem: BitSet grows automatically
 * (one array copy) and the semantics change by no bit.
 */
@Mixin(CarvingMask.class)
public abstract class CarvingMaskCapacityMixin {

   @ModifyArg(
      method = "<init>(II)V",
      at = @At(value = "INVOKE", target = "Ljava/util/BitSet;<init>(I)V")
   )
   private static int tellus$capInitialBits(int nbits) {
      // 2026-10-05: derived from TellusLayout.WORLD_MIN_Y and the carving noise window (see the class text).
      // Was `256 * 64`, which was derived from the vanilla minY = -64 and therefore left the whole carving band
      // outside the BitSet: caves were silently not carved at all.
      // 65,024 bit = 8 KB per chunk, against 16,793,600 bit = 2 MB uncapped (a 1/258 ratio).
      //
      // 2026-10-11: the capacity constants moved to TellusCarvingMaskCapacity. They used to live here, with a
      // `public static int expectedMaskSections()` accessor so Tellus.probeCarvingMaskCapacity() could compare
      // against the same expression. That accessor is illegal in a mixin -- the framework rejects any mixin
      // carrying a non-private static method -- so the whole mixin was refused at runtime and the cap never
      // applied. Verified in a live log:
      //   InvalidMixinException: Mixin tellus.mixins.json:CarvingMaskCapacityMixin
      //     contains non-private static method
      //     at MixinApplicatorStandard.checkMethodVisibility
      // Keeping the values in a plain class gives the self-check its single source of truth without asking the
      // mixin to expose anything.
      return Math.min(nbits, TellusCarvingMaskCapacity.expectedBits());
   }
}
