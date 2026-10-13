package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.LoadingTerrainScreenTiming;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({LevelLoadingScreen.class})
public abstract class LevelLoadingScreenMixin {
   private static final int TEXT_COLOR = -1710619;
   private static final int TEXT_PADDING = 20;
   private static final int LINE_SPACING = 2;
   private static final int MAX_TEXT_WIDTH = 420;
   private static final int LOADING_WIDGET_HALF_HEIGHT = 42;
   private static final int LOADING_WIDGET_TEXT_GAP = 16;
   private static final List<Component> CONTRIBUTIONS = List.of(
      Component.literal("Land cover: © ESA WorldCover project / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium."),
      Component.literal("Climate zones: Köppen–Geiger climate classification (Beck et al., 2018) — CC BY 4.0."),
      Component.literal("Elevation: swissALTI3D / Terrain Tiles / AHN / CANElevation / USGS 3DEP / Copernicus DEM / ArcticDEM/REMA — see Data Sources for required DEM attributions."),
      Component.literal("Weather: Weather data by Open-Meteo.com (https://open-meteo.com/)")
   );
   // 1.21.1 PORT NOTE: 1.21.9's LevelLoadingScreen carries a `Reason` enum and Tellus used it to tell
   // "loading a world" apart from the screen's other uses. javap on the 1.21.1 jar shows the class has
   // no Reason field or enum at all (fields: progressListener, lastNarration, done, COLORS), so the
   // guard is dropped. In 1.21.1 the screen is only ever opened for world loading, and the TAIL hook
   // is additionally gated on tellus$isLoadingTellusWorld(), so the contributions text still only
   // appears on Tellus worlds.

   @Inject(
      method = {"render"},
      at = {@At("HEAD")}
   )
   private void tellus$trackLoadingTerrainScreen(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      LoadingTerrainScreenTiming.onScreenRender((LevelLoadingScreen)(Object)this, "OTHER");
   }

   @Inject(
      method = {"render"},
      at = {@At("TAIL")}
   )
   private void tellus$renderContributions(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (tellus$isLoadingTellusWorld()) {
         Font font = Minecraft.getInstance().font;
         int width = graphics.guiWidth();
         int height = graphics.guiHeight();
         int availableWidth = Math.max(TEXT_PADDING * 2, width - TEXT_PADDING * 2);
         int wrapWidth = Math.min(MAX_TEXT_WIDTH, availableWidth);
         List<FormattedCharSequence> lines = new ArrayList<>();

         for (Component line : CONTRIBUTIONS) {
            for (FormattedCharSequence wrapped : font.split(line, wrapWidth)) {
               lines.add(Objects.requireNonNull(wrapped, "wrappedLine"));
            }
         }

         if (!lines.isEmpty()) {
            int totalHeight = lines.size() * font.lineHeight + (lines.size() - 1) * LINE_SPACING;
            int centerX = width / 2;
            int y = tellus$getTextY(height, totalHeight);

            for (FormattedCharSequence line : lines) {
               int lineWidth = font.width(line);
               int x = centerX - lineWidth / 2;
               graphics.drawString(font, line, x, y, TEXT_COLOR, true);
               y += font.lineHeight + LINE_SPACING;
            }
         }
      }
   }

   private static int tellus$getTextY(int height, int totalHeight) {
      int centerY = height / 2;
      int bottomY = height - totalHeight - TEXT_PADDING;
      int belowLoadingWidgetY = centerY + LOADING_WIDGET_HALF_HEIGHT + LOADING_WIDGET_TEXT_GAP;
      if (belowLoadingWidgetY <= bottomY) {
         return belowLoadingWidgetY;
      }

      int aboveLoadingWidgetY = centerY - LOADING_WIDGET_HALF_HEIGHT - LOADING_WIDGET_TEXT_GAP - totalHeight;
      return Math.max(TEXT_PADDING, Math.min(aboveLoadingWidgetY, bottomY));
   }

   private static boolean tellus$isLoadingTellusWorld() {
      MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
      if (server == null) {
         return false;
      }

      ServerLevel overworld = server.getLevel(Level.OVERWORLD);
      return overworld != null && overworld.getChunkSource().getGenerator() instanceof EarthChunkGenerator;
   }
}
