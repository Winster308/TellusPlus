package com.yucareux.tellus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs **before all mod initialization** ✓ (Fabric's preLaunch entrypoint ✓) → used to resolve
 * the hard conflict with c2me ✗✓.
 *
 * <h2>What the conflict is</h2>
 *
 * <p>c2me's {@code vanillaWorldGenOptimizations.optimizeAquifer} (default on ✓) rewrites
 * {@code Aquifer$NoiseBasedAquifer}'s constructor ✓, and performs a self-check at the end:
 * <pre>
 *   // First precompute the entire aquifer grid into field_28817[] (long[]) and c2me$packedBlockPositions[] (short[])
 *   for (…) { field_28817[posIdx] = BlockPos.asLong(x,y,z); … }
 *   // Then assert "not a single slot may remain"
 *   for (int i = 0; i &lt; field_28817.length; i++)
 *       if (field_28817[i] == Long.MAX_VALUE)
 *           throw new AssertionError("Array initialization");     ✗
 * </pre>
 * Tested (1.21.9 / c2me 0.3.5+alpha.0.44 / 65600 high world):
 * As long as the carving path ✓ is reached → {@code NoiseChunk.forChunk} → {@code new NoiseBasedAquifer(…)}
 * → **guaranteed to crash** ✗, the entire chunk upgrade fails ✗ → the world cannot be entered ✗.
 * Shrinking the noise range **doesn't help** ✗ (already tested ✓ — this assertion has no off ✗).
 *
 * <h2>Why this approach is correct</h2>
 *
 * <p>This optimization only affects the **aquifer** generation speed ✓; Tellus's terrain comes
 * from DEM ✗ and doesn't use vanilla noise ✓. The aquifer is only used in the **carving context** ✓
 * (the range is already near sea level ✓) → **turning it off has almost no cost** ✓✓.
 * What you get in exchange is "the world can be entered" ✓.
 *
 * <p>Only **this one key** is changed ✓, a backup is made **before** changing ✓, and a log
 * message explains it ✓; if you don't want it, add
 * {@code -Dtellus.c2me.autofix=false} ✓.
 */
public final class TellusPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("TellusCN");
    /**
     * Reads **world layout** parameters from {@code config/telluscn-layout.properties} ✓, and sets
     * them as system properties **before** anyone reads {@code TellusLayout} ✓✓.
     *
     * <h2>Why this step is necessary</h2>
     *
     * <p>{@code TellusLayout} is a **static class** ✗: `WORLD_TOP_Y`, `BITS_Y`, `PACKING_REPACKED`
     * are all fixed **at class load time** ✓, and the `DimensionType` mixin reads them **very
     * early** ✗ → so "automatically set the top based on Earth's maximum height" **must** be
     * done **before class loading** ✓. The `preLaunch` entrypoint is exactly the **earliest**
     * opportunity ✓✓ (earlier than any mod initialization ✓).
     *
     * <p>This way you don't have to edit HMCL's JVM arguments ✓ — just edit
     * {@code config/telluscn-layout.properties} ✓:
     * <pre>
     *   # World top (determines section count = performance ✓; also determines whether Everest fits ✓)
     *   world.top=10239
     *   # Whether to repack position encoding (required for 1:1 meters ✓; changes chunk format → must create new world ✓)
     *   layout.repack=true
     * </pre>
     *
     * <p><b>Properties explicitly set with {@code -D} take priority and are **not overridden** ✓</b>
     * (the file is just defaults ✓).
     */
    private static void applyLayoutProperties() {
       Path file = Paths.get("config", LAYOUT_FILE);
       if (!Files.isRegularFile(file)) {
          return;   // No such file → everything stays as-is ✓
       }
       try {
          java.util.Properties props = new java.util.Properties();
          try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
             props.load(reader);
          }
          int applied = 0;
          StringBuilder summary = new StringBuilder();
          for (String name : props.stringPropertyNames()) {
             String value = props.getProperty(name);
             if (name == null || value == null) {
                continue;
             }
             String key = name.trim();
             if (key.isEmpty()) {
                continue;
             }
             String fullKey = key.startsWith("tellus.") ? key : "tellus." + key;
             if (System.getProperty(fullKey) != null) {
                continue;   // -D takes priority ✓ do not override ✓
             }
             System.setProperty(fullKey, value.trim());
             applied++;
             summary.append("\n    - ").append(fullKey).append(" = ").append(value.trim());
          }
          if (applied > 0) {
             LOGGER.info("[TellusCN] Applied {} world layout properties from config/{} (explicit -D values are not overridden): {}",
                LAYOUT_FILE, applied, summary);
          }
       } catch (IOException e) {
          LOGGER.warn("[TellusCN] Failed to read config/{} (harmless): {}", LAYOUT_FILE, e.toString());
       }
    }

    /**
     * Sets a key in {@code c2me.toml} to {@code false} ✓ (returns the modified text ✓).
     * If the key doesn't exist, or is already false → returns as-is ✓ (does not modify on its own ✓).
     */
    /**
     * Sets a key in {@code c2me.toml} to {@code true} ✓ (used for "restore vanilla behavior" type toggles ✓).
     */
    private static String setTrue(String text, String key, StringBuilder changed, String why) {
       Pattern pattern = Pattern.compile("(?m)^(\\s*)" + Pattern.quote(key) + "\\s*=\\s*.*$");
       if (!pattern.matcher(text).find()) {
          return text;
       }
       Pattern already = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*true\\s*$");
       if (already.matcher(text).find()) {
          return text;
       }
       changed.append("\n    - ").append(key).append(" = true (").append(why).append(")");
       return pattern.matcher(text).replaceAll("$1" + key + " = true");
    }

    private static String fixKey(String text, String key, StringBuilder changed, String why) {
       Pattern pattern = Pattern.compile("(?m)^(\\s*)" + Pattern.quote(key) + "\\s*=\\s*.*$");
       if (!pattern.matcher(text).find()) {
          return text;
       }
       Pattern already = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*false\\s*$");
       if (already.matcher(text).find()) {
          return text;
       }
       changed.append("\n    - ").append(key).append(" = false (").append(why).append(")");
       return pattern.matcher(text).replaceAll("$1" + key + " = false");
    }

    /** Layout config file: much more convenient than editing HMCL's JVM arguments ✓. */
    private static final String LAYOUT_FILE = "telluscn-layout.properties";

    @Override
    public void onPreLaunch() {
       applyLayoutProperties();
       if (!Boolean.parseBoolean(System.getProperty("tellus.c2me.autofix", "true"))) {
          return;
       }
       Path cfg = Paths.get("config", "c2me.toml");
       if (!Files.isRegularFile(cfg)) {
          return;   // c2me not installed / no config file → do nothing ✓
       }
       try {
          String text = new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8);
          String original = text;
          StringBuilder changed = new StringBuilder();

          // (1) optimizeAquifer ✗ → false ✓
          //   c2me's aquifer optimization throws AssertionError (Array initialization) ✗
          //   → chunk upgrade fails in 65600 high worlds, world cannot be entered ✗ (confirmed by testing ✓)
          text = fixKey(text, "optimizeAquifer", changed, "Aquifer optimization causes chunk upgrade assertion failure");

          // (2) fluidPostProcessingToScheduledTick ✗ → false ✓
          //   c2me **defers fluid post-processing to scheduled tick** ✗. Tellus's terrain writes
          //   massive water bodies (oceans ✓ + cave bands right at sea level ✓ + fill uses WATER_STATE ✓)
          //   → the post-processing queue can never catch up ✗ → chunks **can never reach FULL** ✗
          //   → `getChunkForLighting` returns null ✗ → lighting thread NPE ✗ → world cannot be entered ✗ (tested symptom ✓)
          text = fixKey(text, "fluidPostProcessingToScheduledTick", changed,
             "Fluid post-processing is deferred, chunks can never complete");

          // (3) filterFluidPostProcessing ✗ → false ✓
          //   It uses a "coarse filter" to screen out liquids that "definitely don't flow" ✗ — once it misjudges,
          //   that chunk's fluid post-processing will **never complete** ✗ → chunk stuck ✗ (same symptom as above ✓)
          text = fixKey(text, "filterFluidPostProcessing", changed,
             "Coarse filtering causes chunk fluid post-processing to never complete");

          // (4) useLegacyScheduling ✗ → true ✓ (note: this one is **set to true** ✗, opposite direction from the above three ✓)
          //   c2me's scheduling no longer guarantees "neighboring chunks are present when a chunk is loaded" ✗ →
          //   the lighting thread asks **not-yet-loaded** neighbors for data ✗ → `getChunkForLighting` returns null ✗ →
          //   lighting thread NPE ✗ → chunks can never reach FULL ✗ → world cannot be entered ✗ (tested symptom ✓)
          //   Turning it on = restoring the vanilla behavior of "loading neighbors together" ✓
          text = setTrue(text, "useLegacyScheduling", changed,
             "Neighboring chunks are no longer loaded with the main chunk, the lighting thread gets null");

          if (changed.length() == 0) {
             if (original.contains("optimizeAquifer")
                   && original.contains("fluidPostProcessingToScheduledTick")
                   && original.contains("filterFluidPostProcessing")
                   && original.contains("useLegacyScheduling")) {
                LOGGER.info("[TellusCN] All four conflicting c2me switches resolved (three false + useLegacyScheduling true)");
             }
             return;   // Key not found (different c2me version) → do not modify ✓
          }

          Path backup = cfg.resolveSibling("c2me.toml.tellus-backup");
          if (!Files.exists(backup)) {
             Files.copy(cfg, backup, StandardCopyOption.COPY_ATTRIBUTES);
          }
          Files.write(cfg, text.getBytes(StandardCharsets.UTF_8));
          LOGGER.warn("[TellusCN] c2me.toml rewritten automatically: {}"
             + "\n   original file backed up to c2me.toml.tellus-backup"
             + "\n   these switches break a Tellus world with height 65600 and huge water volumes"
             + " (chunks never finish -> the lighting thread gets null chunks -> the world cannot load)"
             + "\n   evidence: after disabling optimizeAquifer, `Missing Palette` dropped to zero;"
             + " the other two are targeted fixes."
             + "\n   set -Dtellus.c2me.autofix=false to disable this auto-fix.", changed);
       } catch (IOException e) {
          LOGGER.warn("[TellusCN] Auto-fix of c2me config failed (harmless; please manually set "
             + "optimizeAquifer / fluidPostProcessingToScheduledTick / filterFluidPostProcessing "
             + " to false): {}", e.toString());
       }
    }
}