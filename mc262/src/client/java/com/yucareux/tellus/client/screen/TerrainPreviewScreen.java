package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.preview.TerrainPreview;
import com.yucareux.tellus.client.preview.TerrainPreviewWidget;
import com.yucareux.tellus.client.widget.MapThemeSwitcher;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

@Environment(EnvType.CLIENT)
public class TerrainPreviewScreen extends Screen {
   
   private final EarthCustomizeScreen parent;
   
   private final TerrainPreview preview;
   
   private final TerrainPreviewWidget.ViewState initialView;
   private TerrainPreviewWidget previewWidget;

   public TerrainPreviewScreen( EarthCustomizeScreen parent,  TerrainPreview preview,  TerrainPreviewWidget.ViewState initialView) {
      super(Component.empty());
      this.parent = Objects.requireNonNull(parent, "parent");
      this.preview = Objects.requireNonNull(preview, "preview");
      this.initialView = Objects.requireNonNull(initialView, "initialView");
   }

   protected void init() {
      if (this.previewWidget != null) {
         this.previewWidget.close();
      }

      this.previewWidget = new TerrainPreviewWidget(0, 0, this.width, this.height, this.preview);
      this.previewWidget.setViewState(this.initialView);
      this.previewWidget.setFullscreenAction(this::onClose);
      this.previewWidget.setAutoAdjustAction(this.parent::applyPreviewAutoAdjust);
      this.addRenderableOnly(this.previewWidget);
      int buttonY = this.height - 28;
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.back"), button -> this.onClose()).bounds(this.width / 2 - 75, buttonY, 150, 20).build()
      );
      // The "Map type / Base map" switch is available right here in the terrain preview
      // - no need to go back to the world menu or open Mod Menu
      this.addRenderableWidget(MapThemeSwitcher.button(6, buttonY, 150, 20));
      this.addWidget(this.previewWidget);
   }

   public void tick() {
      super.tick();
      if (this.previewWidget != null) {
         this.previewWidget.tick();
      }
   }

   public void onClose() {
      if (this.minecraft != null) {
         if (this.previewWidget != null) {
            TerrainPreviewWidget.ViewState viewState = Objects.requireNonNull(this.previewWidget.getViewState(), "viewState");
            this.parent.applyPreviewViewState(viewState);
         }

         this.minecraft.setScreenAndShow(this.parent);
      }
   }

   public void removed() {
      if (this.previewWidget != null) {
         this.previewWidget.close();
      }
   }
}
