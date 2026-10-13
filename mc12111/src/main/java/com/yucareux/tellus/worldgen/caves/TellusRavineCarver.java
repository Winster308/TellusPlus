package com.yucareux.tellus.worldgen.caves;

import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;

public final class TellusRavineCarver {
   private final ConfiguredWorldCarver<CanyonCarverConfiguration> configured;

   public TellusRavineCarver(CanyonCarverConfiguration configuration) {
      this.configured = WorldCarver.CANYON.configured(configuration);
   }

   ConfiguredWorldCarver<CanyonCarverConfiguration> configured() {
      return this.configured;
   }
}
