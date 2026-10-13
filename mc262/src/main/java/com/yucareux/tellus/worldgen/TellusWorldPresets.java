package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.core.registries.Registries;

public final class TellusWorldPresets {
   public static final ResourceKey<WorldPreset> EARTH = ResourceKey.create(Registries.WORLD_PRESET, Objects.requireNonNull(Tellus.id("earth"), "worldPresetId"));

   private TellusWorldPresets() {
   }
}
