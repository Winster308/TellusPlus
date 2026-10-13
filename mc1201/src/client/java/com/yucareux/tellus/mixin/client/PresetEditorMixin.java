package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.screen.EarthCustomizeScreen;
import com.yucareux.tellus.worldgen.TellusWorldPresets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Environment(EnvType.CLIENT)
@Mixin({PresetEditor.class})
public interface PresetEditorMixin {
   // 2026-10-05: `ordinal = 0` was added to the @At.
   //   Trigger: an @At with no ordinal must match **exactly one** call site; the moment <clinit> contains a
   //   second `Map.of(Object,Object,Object,Object)` (Mojang adding a third editor / reordering the two
   //   `Optional.of(...)` pairs) Mixin aborts the whole mixin because the reference is ambiguous
   //   (`expect` defaults to 1), and it does so while the class is being loaded.
   //   Consequence avoided: the game dies at class load of PresetEditor (crash before the world list is
   //   even shown), and with the mixin rejected the Earth world type disappears from the preset list.
   //   Verified against the shipped bytecode: today <clinit> has exactly one Map.of call
   //   (javap minecraft-clientOnly: `invokestatic java/util/Map.of` -> `putstatic EDITORS`), so pinning the
   //   first call keeps the behaviour bit for bit and only removes the latent crash.
   //   (`ordinal` is an @At attribute, not an @Redirect one, same as DimensionTypeMinYMixin.)
   @Redirect(
      method = {"<clinit>"},
      at = @At(
         value = "INVOKE",
         target = "Ljava/util/Map;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;",
         ordinal = 0
      )
   )
   private static Map<Object, Object> tellus$addEarthEditor(Object key1, Object value1, Object key2, Object value2) {
      Map<Object, Object> editors = new HashMap<>();
      PresetEditor earthEditor = EarthCustomizeScreen::new;
      editors.put(key1, value1);
      editors.put(key2, value2);
      editors.put(Optional.of(TellusWorldPresets.EARTH), earthEditor);
      return Map.copyOf(editors);
   }
}
