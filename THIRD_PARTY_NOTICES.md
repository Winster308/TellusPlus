# Third-Party Notices

TellusCN (`com.yucareux.tellus`, and the companion `dev.telluscn` sources) incorporates and
derives work from the projects below. This file is part of the distribution and must accompany any
redistribution of the mod jar.

---

## arnis — https://github.com/louis-e/arnis

**License:** Apache License, Version 2.0

**What was taken, and where**

Portions of arnis were transcribed into this project. Each port below names the arnis source file
and the line ranges it came from, and the local file it landed in. Everything listed is either
**data** (constant tables) or an **algorithm** whose behaviour and constants were copied verbatim;
the deviations are listed with each entry.

### 1. Elevation repair pipeline — ported 2026-10-06

| arnis source | Landed in | What |
|---|---|---|
| `elevation/postprocess.rs:2538` `filter_elevation_outliers` | `tellus-native/src/lib.rs` (`filter_elevation_outliers`), `mc1219/.../worldgen/TellusElevationRepair.java`, `TellusNative.filterElevationOutliers` | Two-sided physical bounds on elevation, non-finite excluded, out-of-range rewritten to NaN |
| `elevation/postprocess.rs:56` `repair_terrain_anomalies` | `tellus-native/src/lib.rs` (`repair_terrain_anomalies`), `TellusElevationRepair.repairTerrainAnomalies`, `TellusNative.repairTerrainAnomalies` | 5x5 median + MAD despike, `RADIUS=2`, `RELATIVE_FACTOR=3.0`, `abs_threshold = max(6.0, 0.25*m_per_cell)`, `passes = if m_per_cell > 4.0 {2} else {10}`, `MIN_NEIGHBORS = 8`, Jacobi snapshot per pass, interior cells only |
| `elevation/postprocess.rs:2530` `fill_nan_values` | `tellus-native/src/lib.rs` (`fill_nan_values`, `fill_nan_values_cells`), `TellusElevationRepair.fillNanValues`, `TellusNative.fillNanValues` | 3x3 neighbourhood-mean iterative hole fill, Jacobi snapshot, `count > 0` guard, stop on `!any_changed` |
| `elevation/mod.rs:368` `fetch_raw_with_fallback` | `TellusElevationSource.repairDecodedTile`, `TellusElevationRepair.isUnusablePayload`, `TellusNative.NAN_RATIO_REJECT_THRESHOLD` | The provider-validity gate `nan_ratio > 0.5 -> treat as a failed fetch and try the next source` |

**Deviations from arnis, stated explicitly**

- **The lower elevation bound is not arnis's.** arnis uses `MIN_REASONABLE_M = -500.0`, which is
  consistent there because arnis does not model bathymetry at all (`whole_earth()` uses
  `LOWEST_LAND_M = -430.0`, the Dead Sea shore). TellusCN has a real sea floor, so it uses
  `-11000.0` (deepest known point `-10994 m`). Copying `-500.0` would classify the entire ocean as
  missing and interpolate a fabricated abyssal plain over it. See
  `TellusNative.MIN_REASONABLE_METERS` and the Rust test
  `lower_bound_diverges_from_arnis_because_we_model_ocean_floor`.
- **Parallelism is not ported.** arnis uses `rayon`; the Rust kernel here is single-threaded and the
  Java mirror is single-threaded. The algorithms and their constants are unchanged; only the
  execution strategy differs. On a 2 GB VRAM machine with chunk generation already the bottleneck,
  spawning a rayon pool per grid was judged a worse trade than the wall-clock it saved.
- **`fill_nan_values` returns different values from the two ports.** arnis returns nothing (it logs);
  the Rust version returns the iteration count and `fill_nan_values_cells` returns the number of
  cells filled, because the Java caller needs the count for its report line.
- **The `nan_ratio` gate is applied at a different layer.** arnis checks it between providers during
  a single CLI fetch. TellusCN streams at runtime, so the check is applied where a payload is
  decoded (`TellusElevationSource.readPngRaster`), and the rejection is expressed as an
  `IOException` so that the three existing `readPngRaster` callers route it through
  `handleInvalidTile` and treat the tile as a retryable failure rather than a success.

### 2. Building material tables — ported 2026-10-06

Source: `element_processing/buildings.rs`. Landed in
`mc1219/src/main/java/com/yucareux/tellus/worldgen/building/TellusBuildingMaterialLibrary.java`
and consumed by `TellusBuildingMaterials`.

All 21 tables were transcribed entry for entry: `ACCENT_BLOCK_OPTIONS` (:125),
`RESIDENTIAL_WALL_OPTIONS` (:139), `COMMERCIAL_WALL_OPTIONS` (:172), `INDUSTRIAL_WALL_OPTIONS`
(:184), `INSTITUTIONAL_WALL_OPTIONS` (:208), `FARM_WALL_OPTIONS` (:220), `HISTORIC_WALL_OPTIONS`
(:232), `GARAGE_WALL_OPTIONS` (:252), `SHED_WALL_OPTIONS` (:262), `GREENHOUSE_WALL_OPTIONS` (:265),
`TOWER_WALL_OPTIONS` (:2431), `MODERN_SKYSCRAPER_WALL_OPTIONS` (:2452),
`CONTEMPORARY` (:2469), `MASONRY` warm/grey (:2483, :2486), `GLASSY_WALL_OPTIONS` (:2497),
`SKYSCRAPER_ROOF_CAP_OPTIONS` (:971), `GLASSY_ACCENT_OPTIONS` (:989),
`MODERN_ACCENT_OPTIONS` (:992), `SILL_SLAB_OPTIONS` (:4797), `SHUTTER_TRAPDOOR_OPTIONS` (:4789),
`AWNING_OPTIONS` (:6375) — 149 block entries in total.

Also transcribed: `is_rare_wall_block` (:2332), `pick_with_rare_filter` (:2337),
`pick_weighted_wall` (:2297), `climate_wall_weight` (:2213) for the HotDesert/HotSteppe,
TropicalSavanna, Boreal, Tundra/IceCap and ColdSteppe/ColdDesert/DryContinental rows, and the
`climate_applies` gate (:2198).

**Deviations from arnis, stated explicitly**

- **Randomness is replaced by a seeded mix.** arnis draws from a per-building RNG. TellusCN must
  regenerate the same building identically on the client, on the server and on a later chunk-cache
  reread, so every choice is a pure function of the blueprint seed via splitmix64.
- **Categories are collapsed.** arnis has 23 categories derived from OSM tags; TellusCN's
  `BuildingProfile.Archetype` has 6, and `TellusBuildingMaterialLibrary.Category` maps those onto
  the nearest arnis table. `OsmBuildingKind` carries the finer tag detail but is deliberately not
  consulted for materials, because every other geometric decision is keyed to the archetype.
- **The cold climate rows are merged.** arnis has three distinct cold rows; TellusCN's `COLD` family
  cannot distinguish them, so the merge takes the milder reading for weights and the harsher reading
  for exclusions. The reasoning is documented at `coldWeight`.
- **`NETHER_BRICK` is spelled `NETHER_BRICKS`.** arnis's bedrock-block map names it after the item;
  in Minecraft 1.21.9 the block field is `Blocks.NETHER_BRICKS`.
- **The awning does not project outwards.** arnis hangs it one cell beyond the facade line, which
  would place a block in the neighbouring column and can write across a chunk border.
  TellusCN places it flush on the wall. See `EarthChunkGenerator.facadeDetailState`.

### What was deliberately NOT ported

The following were examined line by line and rejected, with the reason recorded here rather than in
a comment so it is not lost:

- `element_processing/buildings.rs` — facade **photographs**, `building_facades/`, `mapillary/`
  (~390 KB of arnis). These depend on photo assets and on decal entities. Without the photographs
  the algorithms have nothing to act on; the asset pipeline was not reproduced.
- `elevation/postprocess.rs` `level_water_surfaces` and the rest of `apply_land_cover_repair`.
  These flood-fill connected water bodies, and a connected body cut off at a chunk boundary yields
  several separate "levels", which is worse than not levelling at all under streaming generation.
- `elevation/postprocess.rs` `despeckle`, micro-cave pruning, deep-lava-sea. All depend on
  whole-tile flood fills; arnis's own comments note that boundary components must be preserved.
- `elevation/postprocess.rs` `derive_affine`'s per-bbox fit. Streaming generation has no global
  min/max to fit.
- `caves/*`. Our cave path already runs the vanilla carvers under c2me and is deliberately
  conservative; arnis's density field bypasses `NoiseChunk`/`Aquifer` but costs 21 `NormalNoise`
  evaluations per sample, which was not judged affordable on this hardware.
- `clipping.rs` and the OSM-specific parts of `colors.rs`. Not applicable to a runtime generator.
- `celestial.rs` (lunar and Martian sun/moon). Out of scope for an Earth mod.

---

## Apache License, Version 2.0 (full text)

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS