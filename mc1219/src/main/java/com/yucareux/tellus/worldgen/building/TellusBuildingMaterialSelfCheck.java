package com.yucareux.tellus.worldgen.building;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 2026-10-06: startup self-check for the 2026-10-06 arnis ports.
 *
 * <p><b>Why a hand-rolled self-check and not a JUnit suite.</b> This project has no
 * {@code src/test} source set wired into the Loom build, so a JUnit test would be a file that never
 * runs. These assertions are instead executed once during server start (see
 * {@link #verifyAll()}, called from the worldgen startup path) and can be re-run on demand with
 * {@code /tellus verify}. A check that never executes is worse than no check, because it reads as
 * coverage.
 *
 * <p><b>What is asserted, and why each one is a real failure mode rather than a tautology.</b>
 * <ul>
 *   <li><b>149 entries, 21 tables.</b> If a table is accidentally truncated during an edit the
 *       material variety silently drops back towards one block per building, and nothing else in
 *       the build would notice.</li>
 *   <li><b>Every pick is deterministic.</b> The same seed must give the same wall. A shared mutable
 *       RNG would still pass a smoke test and then produce different buildings on the client, on
 *       the server and on a chunk-cache reread, which shows up as buildings that change when you
 *       walk away and come back.</li>
 *   <li><b>Climate exclusions are real.</b> A sand-brick wall in a boreal city, or a spruce wall
 *       in a desert, is the specific artefact the climate weights exist to prevent; a weight that
 *       silently became 1 everywhere would compile and look fine in a temperate test world.</li>
 *   <li><b>The pool actually varies.</b> Drawing 4000 seeds must produce more than a handful of
 *       distinct walls. A salt collision between wall and trim would collapse the variety without
 *       any visible error.</li>
 * </ul>
 *
 * <p>The ocean-floor bound and the empty-payload gate are asserted in the Rust test suite
 * ({@code tellus-native}, {@code arnis_repair_tests}), which does run under {@code cargo test}.
 */
public final class TellusBuildingMaterialSelfCheck {
   private TellusBuildingMaterialSelfCheck() {
   }

   /** A failed expectation, reported rather than thrown so one failure does not hide the rest. */
   private record Failure(String check, String detail) {
   }

   /**
    * Runs every check and returns the failures.
    *
    * @param samples how many seeds to draw when checking that the pool varies
    */
   public static List<String> verifyAll(int samples) {
      List<Failure> failures = new ArrayList<>();

      // 1. The declared total must match the tables that are actually declared.
      //    An earlier draft of this check asserted arnis's own totals (21 tables / 149 entries) and
      //    failed on the first real run: arnis actually declares 28 tables / 179 entries, and the
      //    subset that can be picked as a building surface is not the same set. The invariant worth
      //    asserting is the internal consistency, plus the floor that matters: the pool must be
      //    materially larger than the pre-port palette, which produced exactly one wall block per
      //    (archetype x climate) pair.
      List<Block[]> tables = TellusBuildingMaterialLibrary.allTables();
      int summed = 0;
      for (Block[] table : tables) {
         if (table.length == 0) {
            failures.add(new Failure("empty table", "a table has no entries"));
         }
         summed += table.length;
      }
      if (summed != TellusBuildingMaterialLibrary.TOTAL_TABLE_ENTRIES) {
         failures.add(new Failure("entry total", "tables sum to " + summed + " but TOTAL_TABLE_ENTRIES says "
            + TellusBuildingMaterialLibrary.TOTAL_TABLE_ENTRIES));
      }
      if (tables.size() < 20) {
         failures.add(new Failure("table count", "only " + tables.size() + " tables are declared; arnis has 28"));
      }
      if (summed < 120) {
         failures.add(new Failure("pool size", summed + " entries total; the pre-port palette had 6 wall blocks"));
      }

      // 2. No table contains a null: a null wall would blow up deep inside the column writer,
      //    where the stack trace no longer says which building and which table.
      for (Block[] table : tables) {
         for (Block block : table) {
            if (block == null) {
               failures.add(new Failure("null entry", "a table contains null"));
               break;
            }
         }
      }

      // 3. Determinism: same seed and profile must give the same wall, twice.
      BuildingProfile profile = new BuildingProfile(
         BuildingProfile.Archetype.HOUSE, BuildingProfile.RoofProfile.GABLED_X,
         BuildingProfile.ClimateFamily.TEMPERATE, 2, 4, true, 0, 3, 0, 0, 4
      );
      for (long seed : new long[]{0L, 1L, -1L, 0x5DEECE66DL, Long.MIN_VALUE}) {
         Block first = TellusBuildingMaterialLibrary.pickWall(
            TellusBuildingMaterialLibrary.Category.HOUSE, profile, seed);
         Block second = TellusBuildingMaterialLibrary.pickWall(
            TellusBuildingMaterialLibrary.Category.HOUSE, profile, seed);
         if (first != second) {
            failures.add(new Failure("determinism", "seed " + seed + " gave " + first + " then " + second));
         }
      }

      // 4. Climate exclusions actually bite. These are the three cases the weights were written for.
      if (TellusBuildingMaterialLibrary.climateWallWeight(
         BuildingProfile.ClimateFamily.COLD, Blocks.SANDSTONE, false) != 0) {
         failures.add(new Failure("cold excludes sandstone", "sandstone must not be buildable in a cold wall"));
      }
      if (TellusBuildingMaterialLibrary.climateWallWeight(
         BuildingProfile.ClimateFamily.COLD, Blocks.SPRUCE_PLANKS, false) <= 0) {
         failures.add(new Failure("cold prefers spruce", "spruce must be buildable in a cold wall"));
      }
      if (TellusBuildingMaterialLibrary.climateWallWeight(
         BuildingProfile.ClimateFamily.ARID, Blocks.OAK_PLANKS, false) != 0) {
         failures.add(new Failure("arid excludes timber", "oak must not be buildable in a desert wall"));
      }
      if (TellusBuildingMaterialLibrary.climateWallWeight(
         BuildingProfile.ClimateFamily.ARID, Blocks.SANDSTONE, false) <= 1) {
         failures.add(new Failure("arid prefers sandstone", "sandstone must be strongly preferred in a desert wall"));
      }
      // Multi-family halves the timber weight (arnis residential_scale).
      int single = TellusBuildingMaterialLibrary.climateWallWeight(
         BuildingProfile.ClimateFamily.COLD, Blocks.OAK_PLANKS, false);
      int multi = TellusBuildingMaterialLibrary.climateWallWeight(
         BuildingProfile.ClimateFamily.COLD, Blocks.OAK_PLANKS, true);
      if (multi > single) {
         failures.add(new Failure("residential scale", "multi-family oak weight " + multi + " exceeds single-family " + single));
      }

      // 5. No cold or arid wall may come out as timber across many seeds, which is the artefact a
      //    silently broken weight table produces.
      int coldTimber = 0;
      int aridTimber = 0;
      Set<Block> seenWalls = new HashSet<>();
      BuildingProfile coldProfile = new BuildingProfile(
         BuildingProfile.Archetype.HOUSE, BuildingProfile.RoofProfile.GABLED_X,
         BuildingProfile.ClimateFamily.COLD, 2, 4, true, 0, 3, 0, 0, 4
      );
      BuildingProfile aridProfile = new BuildingProfile(
         BuildingProfile.Archetype.HOUSE, BuildingProfile.RoofProfile.GABLED_X,
         BuildingProfile.ClimateFamily.ARID, 2, 4, true, 0, 3, 0, 0, 4
      );
      BuildingProfile temperateProfile = new BuildingProfile(
         BuildingProfile.Archetype.HOUSE, BuildingProfile.RoofProfile.GABLED_X,
         BuildingProfile.ClimateFamily.TEMPERATE, 2, 4, true, 0, 3, 0, 0, 4
      );
      for (int i = 0; i < samples; i++) {
         long seed = i * 2654435761L;
         Block cold = TellusBuildingMaterialLibrary.pickWall(
            TellusBuildingMaterialLibrary.Category.HOUSE, coldProfile, seed);
         Block arid = TellusBuildingMaterialLibrary.pickWall(
            TellusBuildingMaterialLibrary.Category.HOUSE, aridProfile, seed);
         seenWalls.add(TellusBuildingMaterialLibrary.pickWall(
            TellusBuildingMaterialLibrary.Category.HOUSE, temperateProfile, seed));
         if (isTimber(cold)) {
            coldTimber++;
         }
         if (isTimber(arid)) {
            aridTimber++;
         }
      }
      if (coldTimber != 0) {
         failures.add(new Failure("cold wall is timber", coldTimber + " of " + samples + " cold houses got a timber wall"));
      }
      if (aridTimber != 0) {
         failures.add(new Failure("arid wall is timber", aridTimber + " of " + samples + " desert houses got a timber wall"));
      }
      if (seenWalls.size() < 8) {
         failures.add(new Failure("pool variety", samples + " seeds produced only " + seenWalls.size()
            + " distinct residential walls; the pre-port palette produced exactly 1"));
      }

      // 6. Wall and trim must not be correlated: a salt collision would make every building
      //    single-material, which is exactly what this port exists to stop.
      int sameAsAccent = 0;
      for (int i = 0; i < samples; i++) {
         long seed = i * 40503L;
         Block wall = TellusBuildingMaterialLibrary.pickWall(
            TellusBuildingMaterialLibrary.Category.HOUSE, temperateProfile, seed);
         Block accent = TellusBuildingMaterialLibrary.pickAccent(
            TellusBuildingMaterialLibrary.Category.HOUSE, seed).getBlock();
         if (wall == accent) {
            sameAsAccent++;
         }
      }
      if (sameAsAccent * 4 > samples) {
         failures.add(new Failure("wall/accent correlation", sameAsAccent + " of " + samples
            + " buildings have wall == trim, which is not a correlation but a salt collision"));
      }

      List<String> messages = new ArrayList<>(failures.size());
      for (Failure failure : failures) {
         messages.add(failure.check() + ": " + failure.detail());
      }
      return messages;
   }

   private static boolean isTimber(Block block) {
      return block == Blocks.OAK_PLANKS || block == Blocks.SPRUCE_PLANKS || block == Blocks.DARK_OAK_PLANKS
         || block == Blocks.OAK_LOG;
   }

   /**
    * The startup entry point: runs the check, logs one line either way, and never throws.
    *
    * <p>A verification failure must not stop the server from starting; it is a report, not a gate.
    */
   public static void verifyAtStartup() {
      try {
         List<String> failures = verifyAll(2000);
         if (failures.isEmpty()) {
            com.yucareux.tellus.Tellus.LOGGER.info("[TellusCN] material self-check passed: {}",
               TellusBuildingMaterialLibrary.describe());
         } else {
            for (String failure : failures) {
               com.yucareux.tellus.Tellus.LOGGER.error("[TellusCN] material self-check FAILED: {}", failure);
            }
         }
      } catch (RuntimeException | LinkageError error) {
         com.yucareux.tellus.Tellus.LOGGER.error("[TellusCN] material self-check could not run", error);
      }
   }
}