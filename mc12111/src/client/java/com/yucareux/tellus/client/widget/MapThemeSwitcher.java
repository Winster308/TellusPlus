package com.yucareux.tellus.client.widget;

import com.yucareux.tellus.config.TellusCnOptions;
import com.yucareux.tellus.preload.ArnisSources;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * "Map type / Base map" switch button - <b>one implementation, usable everywhere</b>.
 *
 * <h2>Why it was extracted</h2>
 * This feature used to be hand written once inside {@code EarthTeleportScreen} only (the comment
 * even said "switch it right on the screen, no need to go to Mod Menu"), while the
 * <b>terrain preview</b> flow (the preview panel of {@code EarthCustomizeScreen}, the fullscreen
 * preview {@code TerrainPreviewScreen}, and the spawn point map {@code EarthSpawnpointScreen} used
 * during world creation) had <b>none</b> of it. So changing the base map meant "leave the preview,
 * enter a world, open Mod Menu first".
 *
 * <p>Everything now goes through here: any screen only needs
 * {@code addRenderableWidget(MapThemeSwitcher.button(x, y, w, h))} to switch the base map in place,
 * and one click advances to the next theme.
 *
 * <h2>Will old and new base maps get mixed after switching</h2>
 * No: the tile cache key <b>carries the theme</b> (see {@code SlippyMapTileCache.currentSource()}),
 * so as soon as the theme changes the next tile request already uses the new key.
 */
@Environment(EnvType.CLIENT)
public final class MapThemeSwitcher {

   private MapThemeSwitcher() {
   }

   /** Builds a "one click switches to the next map type" button. */
   public static Button button(int x, int y, int width, int height) {
      return Button.builder(label(), button -> button.setMessage(cycle()))
         .bounds(x, y, width, height)
         .build();
   }

   /** Button text: {@code Base map: <readable name of the current theme>}. */
   public static Component label() {
      return Component.literal("底图：" + readableName(TellusCnOptions.mapTheme()));
   }

   /** Switches to the next map type and returns the new text (for {@code button.setMessage(...)}). */
   public static Component cycle() {
      String[] themes = themeIds();
      if (themes.length == 0) {
         return label();
      }

      String current = TellusCnOptions.mapTheme();
      int index = 0;
      for (int i = 0; i < themes.length; i++) {
         if (themes[i].equalsIgnoreCase(current)) {
            index = i;
         }
      }

      TellusCnOptions.setMapTheme(themes[(index + 1) % themes.length]);
      return label();
   }

   private static String[] themeIds() {
      String[] themes = new String[ArnisSources.MAP_THEMES.length];
      for (int i = 0; i < themes.length; i++) {
         themes[i] = ArnisSources.MAP_THEMES[i][0];
      }

      return themes;
   }

   private static String readableName(String theme) {
      if (theme == null || theme.isBlank()) {
         return "-";
      }

      for (String[] entry : ArnisSources.MAP_THEMES) {
         if (entry[0].equalsIgnoreCase(theme)) {
            return entry.length > 1 ? entry[1] : entry[0];
         }
      }

      return theme;
   }
}
