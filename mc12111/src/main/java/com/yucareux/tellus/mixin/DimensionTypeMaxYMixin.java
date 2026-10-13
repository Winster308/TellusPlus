package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.world.level.dimension.DimensionType;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * <b>The second gate of the dimension height breakthrough</b>: the
 * {@code min_y + height <= MAX_Y + 1} check inside the {@code DimensionType} constructor.
 *
 * <p>The first gate (the codec's {@code intRange(16, Y_SIZE)}) is handled by
 * {@link DimensionTypeHeightMixin}; once that range is widened the game reports a second failure:
 *
 * <pre>
 * Caused by: IllegalStateException: Caught exception decoding {...}: min_y + height cannot be higher than: 2032
 * </pre>
 *
 * <p>Decompiling that check shows it reads a <b>static field</b> (not an inlined constant):
 *
 * <pre>
 * iload minY; iload height; iadd
 * getstatic MAX_Y          &lt;- the ceiling is read here
 * iconst_1; iadd; if_icmple ok
 * new IllegalStateException; ... athrow
 * </pre>
 *
 * <p>So the read of {@code MAX_Y} in the constructor is redirected to our widened ceiling.
 * Everything else (chunk height, lighting, serialization) is derived from {@code min_y + height} and
 * does not depend on {@code MAX_Y}, so changing this one spot does not affect any other logic.
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeMaxYMixin {

   @Redirect(
      method = "<init>",
      at = @At(
         value = "FIELD",
         target = "Lnet/minecraft/world/level/dimension/DimensionType;MAX_Y:I",
         opcode = Opcodes.GETSTATIC
      )
   )
   private static int tellus$widenMaxY() {
      // the check is min_y + height <= MAX_Y + 1, so return the highest Y we allow
      return EarthGeneratorSettings.MAX_WORLD_Y;
   }
}
