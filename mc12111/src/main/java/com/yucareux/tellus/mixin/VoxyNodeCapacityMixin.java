package com.yucareux.tellus.mixin;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.worldgen.TellusLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * <b>Voxy top-level-node capacity fix for tall (1:1) worlds.</b>
 *
 * <h2>The crash</h2>
 * Voxy's {@code HierarchicalOcclusionTraverser} sizes two things from a single hard-coded number:
 * <pre>
 *   javap -p -c me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser
 *   &lt;init&gt;
 *     50: ldc           #27     // int 200000      -&gt; idx2topNodeMapping = new int[200000]
 *     62: ldc2_w        #94     // long 800000l    -&gt; topNodeIds = new GlBuffer(800000)
 * </pre>
 * 800,000 bytes / 4 bytes per int = 200,000 node slots, matching the array. The count is then
 * enforced on every insertion:
 * <pre>
 *   private void addTLN(int):
 *     2: getfield  topNodeCount:I
 *     7: iadd
 *     8: putfield  topNodeCount:I
 *    16: i2l
 *    21: invokevirtual GlBuffer.size:()J
 *    27: ldiv                    // size / 4
 *    28: lcmp
 *    29: ifle 43
 *    32: new IllegalStateException
 *    36: ldc "Top level node count greater than capacity"
 *    42: athrow
 * </pre>
 * So the moment the world produces more than 200,000 top-level nodes, the traverser throws and the
 * client dies. Observed in a live log:
 * <pre>
 *   [21:05:22] Resizing Dynamic Transforms UBO, capacity limit of 2 reached ... New capacity will be 4
 *   [21:05:23] ... capacity limit of 4 reached ... New capacity will be 8
 *   [21:05:24] ... capacity limit of 8 reached ... New capacity will be 16
 *   [21:06:07] java.lang.IllegalStateException: Top level node count greater than capacity
 * </pre>
 *
 * <h2>Why a 1:1 world exceeds it</h2>
 * The top-level node count follows the LOD hierarchy, which is derived from world height. Vanilla is
 * 384 blocks tall; this mod's 1:1 layout is {@link TellusLayout#WORLD_TOP_Y} - {@link TellusLayout#WORLD_MIN_Y}
 * blocks tall, roughly eighty times more. The 200,000 budget is a vanilla-scale constant, so it is
 * simply too small.
 *
 * <h2>What this mixin does</h2>
 * Raises both constants together, by the ratio between this world's height and the vanilla height
 * the constant was chosen for. They <b>must</b> move together: {@code addTLN} compares the node count
 * against {@code topNodeIds.size() / 4}, and the same index is later used to write into
 * {@code idx2topNodeMapping}. Growing only one of them would move the failure from an explicit throw
 * to a silent array overrun.
 *
 * <p>Only the first {@code 800000l} in the constructor is touched. The constant appears three times
 * in that class -- the other two size {@code scratchQueueA} and {@code scratchQueueB}, which have
 * nothing to do with node capacity, so an ordinal-pinned {@code @ModifyConstant} is used rather than
 * a blanket replacement.
 *
 * <h2>Why {@code require = 0} here</h2>
 * Voxy is an optional soft dependency and is not present at compile time (there is no dependency
 * declared for it), so {@code @Pseudo} plus {@code require = 0} is the only workable combination:
 * without Voxy the whole mixin is skipped, and if a future Voxy build renames or restructures the
 * constructor the injection is skipped rather than crashing the game. The trade-off is that a
 * silently skipped injection leaves the original 200,000 limit in place -- which is the pre-existing
 * behaviour, not a new failure. The cap being raised is logged at startup so the state is visible
 * rather than assumed.
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Memory: the raised buffer is proportional. At the 1:1 layout the node budget becomes
 *       {@code 200000 * ratio}; the buffer grows from 800 KB to {@code 4 * budget} bytes, and the
 *       int array from 800 KB to {@code 4 * budget} bytes. This is small next to Voxy's 512 MB
 *       geometry buffer, but it is not free, which is why the value is derived from the actual
 *       layout rather than simply multiplied by a large factor.</li>
 *   <li>Nothing else in Voxy is changed: the traversal algorithm, the shader bindings and the
 *       geometry buffer are untouched.</li>
 *   <li>Kill switch: {@code -Dtellus.voxy.expandNodeCapacity=false}.</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser", remap = false)
public abstract class VoxyNodeCapacityMixin {

   /** Vanilla world height the 200,000 constant was sized for. */
   private static final int VANILLA_HEIGHT = 384;

   /** The node budget Voxy ships with, and the byte size that matches it (200000 * 4). */
   private static final int VANILLA_NODE_BUDGET = 200_000;
   private static final long VANILLA_NODE_BYTES = 800_000L;

   private static final boolean TELLUS_EXPAND_NODE_CAPACITY =
      Boolean.parseBoolean(System.getProperty("tellus.voxy.expandNodeCapacity", "true"));

   /** The world height this mod is actually running with. */
   private static final int TELLUS_WORLD_HEIGHT = TellusLayout.WORLD_TOP_Y - TellusLayout.WORLD_MIN_Y;

   /**
    * How much bigger the node budget has to be.
    *
    * <p>Capped so a misconfigured layout cannot ask for an absurd allocation: the ratio is clamped to
    * the range [1, 256]. Below 1 there is nothing to do; 256 already covers a world far taller than
    * any supported layout.
    */
   private static final int EXPANSION = resolveExpansion();

   private static int resolveExpansion() {
      if (!TELLUS_EXPAND_NODE_CAPACITY) {
         return 1;
      }
      int ratio = (int)Math.ceil((double)TELLUS_WORLD_HEIGHT / (double)VANILLA_HEIGHT);
      int clamped = Math.max(1, Math.min(256, ratio));
      if (clamped > 1) {
         Tellus.LOGGER.info(
            "[TellusCN] Voxy top-level node budget raised {}x ({} -> {}) for the {} block world height; "
               + "Voxy ships a budget sized for {} blocks",
            clamped, VANILLA_NODE_BUDGET, (long)VANILLA_NODE_BUDGET * clamped, TELLUS_WORLD_HEIGHT, VANILLA_HEIGHT);
      }
      return clamped;
   }

   /**
    * The int array that maps a node index back to its id.
    *
    * <p>This is the single {@code ldc 200000} in the constructor (offset 50), so a plain
    * {@code @ModifyConstant} is unambiguous.
    */
   @ModifyConstant(method = "<init>", constant = @Constant(intValue = VANILLA_NODE_BUDGET), remap = false, require = 0)
   private int tellus$expandNodeIndexArray(int original) {
      return original * EXPANSION;
   }

   /**
    * The GL buffer holding one int per top-level node.
    *
    * <p>{@code 800000l} appears three times in the constructor: this one (offset 62, {@code topNodeIds})
    * and two more for {@code scratchQueueA} / {@code scratchQueueB} (offsets 96 and 113). {@code ordinal = 0}
    * pins the injection to the first occurrence, which is {@code topNodeIds}.
    */
   @ModifyConstant(method = "<init>", constant = @Constant(longValue = VANILLA_NODE_BYTES, ordinal = 0), remap = false, require = 0)
   private long tellus$expandNodeIdBuffer(long original) {
      return original * EXPANSION;
   }
}
