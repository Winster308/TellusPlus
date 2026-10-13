package com.yucareux.tellus.worldgen;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import it.unimi.dsi.fastutil.shorts.ShortList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sparse sections: <b>a section is materialized only for the Y range that really uses it</b>; every empty
 * region of the world points at one shared instance.
 * <p>Why it is needed: a world 65536 blocks tall has 4096 sections per chunk, and the vanilla
 * {@code ChunkAccess} constructor materializes <b>every</b> empty slot as a {@code LevelChunkSection}
 * (about 250 B) -> about 1.06 MB per chunk -> about 6 GB at render distance 37. Here that is cut down to
 * "only the ones actually used".
 * <p>Switch: it ships together with {@link TellusLayout} (only enabled in the repackaged layout). While it
 * stays off by default every method of this class is a no-op and vanilla behaviour is bit-for-bit unchanged.
 */
public final class SparseSections {

   private static final Logger LOGGER = LoggerFactory.getLogger("TellusCN");
   private static final AtomicBoolean WARNED_BLOCK = new AtomicBoolean();
   private static final AtomicBoolean LOGGED_BIOME = new AtomicBoolean();

   /** The shared empty section (every empty region of the world points at it). Safe to read; a write must materialize a real one in the chunk layer first. */
   // Round 3 self-check: these two must be volatile.
   //   Reason (race): the old code was "read shared -> if null then new -> assign", with no synchronisation at all,
   //   while `fillWithShared` is called concurrently by several worldgen workers (c2me multithreaded generation)
   //   -> two threads may each build an instance -> `shared` keeps only the last one, while the instance built
   //   first is still referenced by the section slots of a batch of chunks -> `isShared()` compares against the
   //   current `shared` and answers false -> the guard silently fails on those chunks (a modified shared instance goes unnoticed).
   private static volatile LevelChunkSection shared;
   private static volatile PalettedContainerFactory factory;

   private SparseSections() {
   }

   /**
    * Whether sparse sections are enabled.
    *
    * <p><b>Optimisation (this round)</b>: it used to be
    * {@code return TellusLayout.TALL && !Boolean.getBoolean("tellus.sections.off");}
    * -- and {@code Boolean.getBoolean(key)} is equivalent to
    * {@code Boolean.parseBoolean(System.getProperty(key))},
    * that is, <b>every call did a system property table lookup plus a string parse</b>.
    *
    * <p>And this method is <b>the first check on the hot path</b>: it is called by 5 mixins, among them
    * {@code ChunkAccess.setBlockState} / {@code ProtoChunk.setBlockState} /
    * {@code LevelChunk.setBlockState} / {@code BulkSectionAccess.getSection},
    * -- filling the terrain of one chunk is already <b>tens of thousands of block writes</b>,
    * so "one system property lookup per block written" (the property table is a synchronised HashTable and is
    * itself affected by the lock taken while writing).
    *
    * <p>Changing it to {@code static final}: <b>evaluated once at class load</b>, after that it is a plain static field read.
    * Semantics are unchanged (`-Dtellus.sections.off` was always a start-up switch, no runtime change supported).
    */
   private static final boolean CLIENT_SIDE = net.fabricmc.loader.api.FabricLoader.getInstance()
      .getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;

   /**
    * Whether the client also shares that one <b>global</b> empty instance. <b>Off by default</b> (fixed in 2026-10).
    *
    * <p>Why it has to be off: that is a <b>server-wide unique</b> instance, and on the client
    * {@code LevelChunk.replaceWithPacketData} reads <b>in place</b> into the already existing sections, so
    * before the packet is received {@link #detachShared} has to replace every slot with real ones
     * (one per section, i.e. {@link TellusLayout#MAX_HEIGHT}/16 -- <b>4223</b> with the default settings,
     * <b>not</b> the 767 this comment used to claim, which understated the cost by 5.5x),
    * and once that is done {@link #replaceEmptiesWithShared} is blocked by {@link #clientReplaceEnabled()} (false by default),
    * which means <b>every client chunk permanently holds that many extra real sections</b>
    * while sparsification saves <b>not a single byte</b> (the real instances are there anyway) -- a pure loss.
    *
    * <p>The client does not even share: `fillWithShared` has no effect, so at construction time it is the vanilla
    * "one instance per slot", {@link #detachShared} returns immediately (saving 2 passes over all
     * {@link TellusLayout#MAX_HEIGHT}/16 slots per packet),
    * and the global aliasing hazard disappears completely on the client (the soil of the 2026-09-22 crash).
    * Cost: the client no longer saves the 6 KB per chunk of {@code postProcessing} -- in exchange for zero risk.
    *
    * <p>To restore it, <b>both switches must be enabled together</b> (otherwise it degrades back into that pure-loss state):
    * <pre>-Dtellus.sections.clientShare=true -Dtellus.sections.clientReplace=true</pre>
    */
   private static final boolean CLIENT_SHARE =
      Boolean.parseBoolean(System.getProperty("tellus.sections.clientShare", "false"));

   private static final boolean ACTIVE = !Boolean.getBoolean("tellus.sections.off")
      && (CLIENT_SIDE ? CLIENT_SHARE : TellusLayout.TALL);

   public static boolean active() {
      return ACTIVE;
   }

   /**
    * Whether the client also replaces "empty sections" with the shared instance. <b>false by default</b>.
    *
    * <h2>2026-09-22: off by default (crash fix)</h2>
    *
    * <p>Reproduced crash (`crash-2026-09-22_22.32.54-client.txt`):
    * <pre>
    * NullPointerException: NoiseChunk.getBlockState(...) because "$$2" is null
    *   at MaterialRule.apply          <- surface rule
    *   <- ClientLevel <- LevelRenderer  <- Render thread
    * </pre>
    * Root cause: {@code LevelChunkSparseClientMixin} calls
    * {@link #replaceEmptiesWithShared} at TAIL of packet reception, replacing the client empty sections with
    * <b>the one shared instance used by the whole world</b> -- and it <b>belongs to no chunk and has no chunk context</b>.
    * As soon as the render thread reads it -> it asks "what block is this cell" -> enters the surface rule ->
    * <b>NoiseChunk is null on that thread</b> -> NPE (on the render thread that crashes the game outright).
    *
    * <p>So <b>sparse sections on the server are good</b> (they save memory and were measured to be problem free),
    *   while <b>the client part is unsafe</b> -> so it is off by default.
    *   Cost: the client uses a bit more empty-section memory per chunk -- but <b>all the server / singleplayer worldgen gains are kept</b>.
    *
    * <p><b>This switch only matters when {@code -Dtellus.sections.clientShare=true} is set</b>:
    * the client does not even share by default (see {@code CLIENT_SHARE}), so all {@link TellusLayout#MAX_HEIGHT}/16 slots are vanilla real instances
    * from the very start and the switch makes no difference -- that is the real reason "client memory was never optimised away".
    *
    * <p>To turn it back on (risky): {@code -Dtellus.sections.clientReplace=true}
    */
   /**
    * Exposes {@link class_11897}, used by "fill a whole section at once".
    *
    * <p>Purpose (performance fix of 2026-09-23): `fillSection` used to call
    * {@code getAndSetUnchecked} cell by cell over the whole 4096 cells; when spawning on a plateau
    * (solidMaxIndex about 557) that is <b>2.28 million writes per chunk</b> -> measured <b>2 seconds per chunk</b>.
    * And "the whole range is one and the same block" could have been built in a single go with
    * {@code new PalettedContainer<>(state, strategy)} (4096 constructions -> 1 construction).
    * That {@code strategy} can only be obtained from the factory -> so this is the opening for it.
    *
    * <p>Returning null means sparse mode is not enabled (the caller then takes the original cell-by-cell path, behaviour unchanged).
    */
   public static PalettedContainerFactory factoryOrNull() {
      return factory;
   }

   public static boolean clientReplaceEnabled() {
      return CLIENT_REPLACE;
   }

   private static final boolean CLIENT_REPLACE =
      Boolean.parseBoolean(System.getProperty("tellus.sections.clientReplace", "false"));

   public static boolean isShared(LevelChunkSection section) {
      LevelChunkSection empty = shared;
      return section != null && section == empty;
   }

   /** Whether the shared instance is already installed (if it is not, do not rely on sparsification, or a null may be read). */
   public static boolean ready() {
      return shared != null && factory != null;
   }

   /** Fill all null slots with the shared empty section (replacing the vanilla "new one per slot"). */
   public static void fillWithShared(PalettedContainerFactory factoryIn, LevelChunkSection[] sections) {
      if (!active()) {
         return;
      }
      // Double-checked locking (round 3 self-check fix):
      //   it makes "check whether shared is null -> create -> publish" atomic as a whole:
      //   the second thread to arrive sees the same instance and will not build another one.
      LevelChunkSection empty = shared;
      if (empty == null) {
         synchronized (SparseSections.class) {
            empty = shared;
            if (empty == null) {
               empty = new LevelChunkSection(factoryIn);
               factory = factoryIn;
               shared = empty;
               LOGGER.info("[TellusCN] Sparse sections enabled: empty area shares one section instance, materialized only on write");
            }
         }
      }
      for (int i = 0; i < sections.length; i++) {
         if (sections[i] == null) {
            sections[i] = empty;
         }
      }
   }

   /**
    * "Materialize on demand" before writing a block: only the section holding that Y becomes real.
    * That is "load only the cells actually used", with a granularity of 16 cells (one section).
    */
   public static void materialize(ChunkAccess chunk, int blockY) {
      if (!active()) {
         return;
      }
      LevelChunkSection empty = shared;
      PalettedContainerFactory factoryLocal = factory;
      if (empty == null || factoryLocal == null) {
         return;
      }
      if (blockY < chunk.getMinY() || blockY > chunk.getMaxY()) {
         return;
      }
      LevelChunkSection[] sections = chunk.getSections();
      int index = chunk.getSectionIndex(blockY);
      if (index >= 0 && index < sections.length && sections[index] == empty) {
         sections[index] = new LevelChunkSection(factoryLocal);
      }
      // Raise the "highest materialized section" hint so getHighestFilledSectionIndex need not scan from the top
      if (index >= 0 && chunk instanceof SectionHint hint) {
         hint.tellus$raiseHighestHint(index);
      }
   }

   /**
    * Replace "the section at some index of the array is still the shared instance" with a <b>real one</b>.
    *
    * <h2>Why it must be done (a top-severity bug found by the round 1 self-check)</h2>
    *
    * <p>Vanilla has a fast fill path that <b>bypasses `SectionPos.setBlockState` and writes the palette directly</b>:
    * <pre>
    *   PalettedContainer&lt;BlockState&gt; states = section.getStates();
    *   states.getAndSetUnchecked(x, y, z, state);      <- writes the palette directly
    * </pre>
    * Tellus' `LevelChunkSectionSparseGuardMixin` only intercepts `setBlockState` and
    * <b>cannot intercept that path</b>. And such "fast fills" are exactly what Tellus itself uses heavily
    * (`ChunkSectionWriter.acquireStates`, `fillSolidSections`).
    *
    * <p>If the `section` is still the <b>global shared instance</b> at that moment, then
    * a single fast fill rewrites that "empty section of the whole world" straight into stone/water,
    * the palette becomes solid while the data of the other chunks is still empty,
    * `PalettedContainer` throws `Missing Palette entry for index N`,
    * and the server main loop logs `Encountered an unexpected exception` (measured and confirmed).
    *
    * <p>So every writer that <b>only got hold of the section array</b> calls this method once before writing.
    */
   public static void materializeSection(LevelChunkSection[] sections, int index) {
      if (!active()) {
         return;
      }
      if (sections == null || index < 0 || index >= sections.length) {
         return;
      }
      // 2026-10-10: raise the high-water mark **here**, and stop relying on the mixin that never worked.
      //
      //   HighestSectionHintMixin claimed to cover this path with
      //       @Inject(method = "materializeSection(...)", require = 0, remap = false)
      //   but that mixin's target is @Mixin(ChunkAccess.class) -- ChunkAccess has no such method, so the
      //   injector could never find its target. `require = 0` made it silent, and the build warned:
      //       Cannot remap materializeSection([Lnet/minecraft/world/level/chunk/LevelChunkSection;I)V
      //       because it does not exist in any of the targets [net/minecraft/world/level/chunk/ChunkAccess]
      //   Net effect: HighestSectionHintState.raise() had NO caller on this path at all (grep: the only
      //   other raise() is inside tellus$raiseHighestHint, which itself is reached only from
      //   SparseSections.materialize -> so this comment's claim that "every path raises" was false for
      //   exactly the 5 fast-fill call sites in EarthChunkGenerator that the class doc says it covers).
      //
      //   Consequence of the gap: the scan in getHighestFilledSectionIndex could start below the real
      //   highest non-air section, so it answered too low -> wrong heightmap / lighting / spawn decisions.
      //
      //   Calling it directly is strictly better than re-pointing the mixin: SparseSections is our own
      //   class, so no mapping, no injector, and no silent-optional failure mode. It is one accumulating
      //   max on an AtomicInteger with a cheap get() guard, and it sits inside the `sections[index] == empty`
      //   branch, so it runs only when a slot is really taken out of the shared-empty state.
      if (sections[index] == shared) {
         HighestSectionHintState.raise(index);
      }
      LevelChunkSection empty = shared;
      PalettedContainerFactory factoryLocal = factory;
      if (empty == null || factoryLocal == null) {
         return;
      }
      if (sections[index] == empty) {
         sections[index] = new LevelChunkSection(factoryLocal);
      }
   }

   /**
    * Before the packet is received, replace the shared empty instance with <b>each chunk's own real one</b> (removing cross-chunk aliasing).
    *
    * <h2>Why it must be done (a confirmed bug)</h2>
    *
    * <p>The bytecode of `LevelChunk.replaceWithPacketData` is:
    * <pre>
    * getfield sections:[Lnet/minecraft/world/level/chunk/LevelChunkSection;   <- takes the array directly
    * aaload; ...
    * invokevirtual LevelChunkSection.read:(Lnet/minecraft/network/FriendlyByteBuf;)V   <- reads in place
    * </pre>
    * -- it reads <b>in place</b> into the existing section object, it does not create a new one.
    * And at chunk construction time (`ChunkAccessSparseSectionsMixin`) we point <b>every empty slot at the same global instance</b>,
    * so receiving one chunk packet <b>rewrites that global instance with this chunk's data</b>:
    * the palette becomes "this chunk's" while the data belongs to another chunk,
    * `PalettedContainer` throws
    * `Missing Palette entry for index N`,
    * the server main loop logs `Encountered an unexpected exception`,
    * chunks repeatedly log `Downgrading ... from minecraft:empty`,
    * and a single server tick stalls for 77 seconds (measured).
    *
    * <p><b>So the shared instance is detached before the packet is received</b>: slots that are still the shared
    * instance get their own new section, so read writes into <b>each chunk's own object</b>; when reception is done,
    * {@link #replaceEmptiesWithShared} puts back into the shared instance the sections that "are still empty after reading" --
    * one in and one out, symmetric.
    * <p>Cost: during reception every empty slot holds a real section object (which is exactly the cost
    * <b>vanilla</b> was paying anyway), and the steady-state memory is <b>completely unchanged</b>.
    */
   public static void detachShared(LevelChunkSection[] sections) {
      if (!active()) {
         return;
      }
      LevelChunkSection empty = shared;
      PalettedContainerFactory factoryLocal = factory;
      if (empty == null || factoryLocal == null || sections == null) {
         return;
      }
      for (int i = 0; i < sections.length; i++) {
         if (sections[i] == empty) {
            sections[i] = new LevelChunkSection(factoryLocal);
         }
      }
   }

   /** A block write landing on the shared instance means some path was not intercepted (the block would be lost) -> log one ERROR with a stack trace and drop that write. */
   public static boolean blockWriteOnShared() {
      if (WARNED_BLOCK.compareAndSet(false, true)) {
         LOGGER.error("[TellusCN] Unintercepted block-write path wrote into the shared empty section; that write was dropped"
                      + " (to avoid corrupting every chunk). Please report this log with the stack trace.",
               new Throwable("tellus:block-write-on-shared-section"));
      }
      return true;
   }

   /** The shared "empty post-processing" array (zero length, shared by every empty chunk in the world). */
   private static final ShortList[] EMPTY_POST_PROCESSING = new ShortList[0];

   /**
    * Shrink the freshly allocated {@code postProcessing} array into the shared empty array.
    *
    * <p>The vanilla constructor does {@code new ShortList[getSectionsCount()]} -- at 4096 sections that wastes
    * 32 KB per chunk, and it only has content once post-processing is really recorded. Here the freshly allocated
    * copy is dropped at the <b>end</b> of the constructor and replaced by the zero-length shared array;
    * {@link #ensurePostProcessing} grows it again when post-processing really has to be written.
    * <p>The cost is one-off 32 KB of temporary garbage per chunk (collected immediately), in exchange for dropping the <b>resident</b> 32 KB per chunk.
    */
   public static ShortList[] shrinkPostProcessing(ShortList[] allocated) {
      if (!active() || allocated.length == 0) {
         return allocated;
      }
      return EMPTY_POST_PROCESSING;
   }

   /**
    * Replace "the real sections that are still empty after reading" with the shared instance.
    *
    * <p>When the client parses a chunk packet it <b>reads in place into the already existing section objects</b>
    * (4096 of them per chunk), so every client chunk wastes about 1 MB of empty objects. Here the empty ones are
    * replaced once parsing finished: the <b>packet format is completely untouched</b>, the server needs no change and vanilla clients keep working as usual.
    */
   public static void replaceEmptiesWithShared(ChunkAccess chunk) {
      if (!active() || !ready()) {
         return;
      }
      LevelChunkSection empty = shared;
      LevelChunkSection[] sections = chunk.getSections();
      for (int i = 0; i < sections.length; i++) {
         LevelChunkSection section = sections[i];
         if (section != null && section != empty && section.hasOnlyAir()) {
            sections[i] = empty;
         }
      }
   }

   /**
    * Whether this section is skipped while saving to disk.
    *
    * <p>The write condition of vanilla {@code SerializableChunkData.copyOf} is
    * {@code hasSection} or there is light data, and {@code hasSection} only checks "the index is within range" --
    * which is always true for a dense array, so a world 65536 tall writes <b>all 4096 sections</b>:
    * apart from bloating the save, <b>loading it back re-instantiates them all and undoes the sparsification entirely</b>.
    * So an empty shared instance with no light data at all is not written.
    */
   public static boolean skipOnSave(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {
      if (!active()) {
         return false;
      }
      return isShared(section) && blockLight == null && skyLight == null;
   }

   /** A biome write landing on the shared instance is <b>expected</b>: an empty section stores no biomes, dropping it is fine. */
   public static boolean biomeWriteOnShared() {
      if (LOGGED_BIOME.compareAndSet(false, true)) {
         LOGGER.debug("[TellusCN] Biome write into a high-altitude empty section dropped (empty areas store no data)");
      }
      return true;
   }
}
