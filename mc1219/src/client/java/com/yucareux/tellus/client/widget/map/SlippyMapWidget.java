package com.yucareux.tellus.client.widget.map;

import com.yucareux.tellus.client.widget.map.component.MapComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import com.yucareux.tellus.client.TellusGui;
import com.yucareux.tellus.config.TellusCnOptions;

@Environment(EnvType.CLIENT)
public class SlippyMapWidget extends AbstractWidget {
   /**
    * Base map attribution. <b>Must follow the source actually used</b> - every data source requires
    * attribution in its terms of use. It used to be hardcoded to a single OSM line, which became
    * non compliant once Carto/Esri/OpenTopoMap/OpenFreeMap were added.
    */
   private static String attributionFor(String theme) {
      if (theme == null) {
         return "(c) OpenStreetMap Contributors";
      }
      return switch (theme) {
         case "carto", "cartodark" -> "(c) OpenStreetMap Contributors, (c) CARTO";
         case "esri", "esri-imagery" ->
               "Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community";
         case "topo", "opentopomap" ->
               "(c) OpenStreetMap Contributors, SRTM | Map style: (c) OpenTopoMap (CC-BY-SA)";
         case "openfreemap-liberty", "openfreemap-dark" ->
               "(c) OpenFreeMap (c) OpenMapTiles (c) OpenStreetMap Contributors";
         case "amap", "amapimg" -> "(c) AutoNavi (GCJ-02)";
         case "moon" -> "Lunar imagery: OpenPlanetaryMap / NASA";
         case "mars" -> "Mars imagery: Viking MDIM21 / NASA";
         default -> "(c) OpenStreetMap Contributors";
      };
   }

   private static final String ATTRIBUTION = "(c) OpenStreetMap Contributors";

   /**
    * Draw order: lowest zoom level first, tiles of the same zoom in cascade (insertion) order.
    *
    * <p>2026-10-05 performance fix: {@code Comparator.comparingInt(...)} allocated a new comparator
    * on every frame; the sort itself is a stable {@code ArrayList#sort}, so building the comparator
    * once changes nothing about the resulting order.
    */
   private static final Comparator<SlippyMap.DrawTile> TILE_DRAW_ORDER = Comparator.comparingInt(draw -> draw.pos().getZoom());

   private final SlippyMap map;
   private final List<MapComponent> components = new ArrayList<>();
   private boolean mouseDown;
   private boolean mouseDragged;
   private int attributionBottomPadding;

   public SlippyMapWidget(int x, int y, int width, int height) {
      super(x, y, width, height, Component.empty());
      this.map = new SlippyMap(width, height);
   }

   public SlippyMap getMap() {
      return this.map;
   }

   public <T extends MapComponent> T addComponent(T component) {
      this.components.add(component);
      return component;
   }

   public void setAttributionBottomPadding(int padding) {
      this.attributionBottomPadding = Math.max(0, padding);
   }

   protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      this.drawBackground(graphics);
      graphics.enableScissor(this.getX() + 4, this.getY() + 4, this.getX() + this.width - 4, this.getY() + this.height - 4);
      int cameraX = this.map.getCameraX();
      int cameraY = this.map.getCameraY();
      int cameraZoom = this.map.getCameraZoom();
      List<SlippyMapTilePos> tiles = this.map.getVisibleTiles();
      // 2026-10-05 performance fix: `map.getTile(pos)` used to run again for every position here,
      // duplicating the Guava LoadingCache lookup that SlippyMap.cascadeTile() had already done for
      // the same position (a second TileKey allocation + cache walk per tile, every frame). The
      // draw entries now carry the tile that lookup produced.
      List<SlippyMap.DrawTile> cascadedTiles = this.map.cascadeTiles(tiles);
      cascadedTiles.sort(TILE_DRAW_ORDER);

      for (SlippyMap.DrawTile draw : cascadedTiles) {
         this.renderTile(graphics, cameraX, cameraY, cameraZoom, draw.pos(), draw.tile(), delta);
      }

      SlippyMapPoint mouse = this.getPointUnderMouse(mouseX, mouseY);
      graphics.pose().pushMatrix();
      graphics.pose().translate(this.getX(), this.getY());

      for (MapComponent component : this.components) {
         component.onDrawMap(this.map, graphics, mouseX, mouseY, mouse);
      }

      graphics.pose().popMatrix();
      graphics.disableScissor();
      int maxX = this.getX() + this.width - 4;
      int maxY = this.getY() + this.height - 4 - this.attributionBottomPadding;
      String attribution = attributionFor(TellusCnOptions.mapTheme());
      int attributionWidth = Minecraft.getInstance().font.width(attribution) + 20;
      int attributionOriginX = maxX - attributionWidth;
      int attributionOriginY = maxY - 9 - 4;
      graphics.fill(attributionOriginX, attributionOriginY, maxX, maxY, -1072689136);
      graphics.drawString(Minecraft.getInstance().font, attribution, attributionOriginX + 10, attributionOriginY + 2, -1);
   }

   private void renderTile(GuiGraphics graphics, int cameraX, int cameraY, int cameraZoom, SlippyMapTilePos pos, SlippyMapTile image, float delta) {
      image.update(delta);
      ResourceLocation location = image.getLocation();
      if (location != null) {
         int deltaZoom = cameraZoom - pos.getZoom();
         double zoomScale = Math.pow(2.0, deltaZoom);
         int size = Mth.floor(256.0 * zoomScale);
         int renderX = (pos.getX() << deltaZoom) * 256 - cameraX;
         int renderY = (pos.getY() << deltaZoom) * 256 - cameraY;
         int textureSize = Math.max(256, size);
         graphics.pose().pushMatrix();
         graphics.pose().translate(this.getX(), this.getY());
         int scaleFactor = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
         float scale = 1.0F / scaleFactor;
         graphics.pose().scale(scale, scale);
         graphics.blit(
            RenderPipelines.GUI_TEXTURED, Objects.requireNonNull(location, "tileLocation"), renderX, renderY, 0.0F, 0.0F, size, size, textureSize, textureSize
         );
         graphics.pose().popMatrix();
      }
   }

   private void drawBackground(GuiGraphics graphics) {
      graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, -14671840);
      TellusGui.outline(graphics, this.getX(), this.getY(), this.width, this.height, -16777216);
   }

   public boolean mouseClicked( MouseButtonEvent event, boolean isPrimary) {
      if (!this.isMouseOver(event.x(), event.y())) {
         return false;
      } else {
         this.mouseDown = true;
         if (event.button() == 0) {
            SlippyMapPoint mouse = this.getPointUnderMouse(event.x(), event.y());

            for (MapComponent component : this.components) {
               if (component.onMouseClicked(this.map, mouse, event.button())) {
                  return true;
               }
            }
         }

         return true;
      }
   }

   public boolean mouseDragged( MouseButtonEvent event, double dragX, double dragY) {
      if (this.mouseDown) {
         this.map.drag((int)(-dragX), (int)(-dragY));
         this.mouseDragged = true;
         return true;
      } else {
         return false;
      }
   }

   public boolean mouseReleased( MouseButtonEvent event) {
      if (event.button() == 0 && this.mouseDown && !this.mouseDragged && this.isMouseOver(event.x(), event.y())) {
         SlippyMapPoint mouse = this.getPointUnderMouse(event.x(), event.y());

         for (MapComponent component : this.components) {
            if (component.onMouseReleased(this.map, mouse, event.button())) {
               return true;
            }
         }
      }

      this.mouseDown = false;
      this.mouseDragged = false;
      return false;
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.isMouseOver(mouseX, mouseY)) {
         int zoom = (int)Math.signum(scrollY);
         if (zoom != 0) {
            this.map.zoom(zoom, (int)(mouseX - this.getX()), (int)(mouseY - this.getY()));
         }

         return true;
      } else {
         return false;
      }
   }

   public void close() {
      this.map.shutdown();
   }

   public void cancelInteraction() {
      this.mouseDown = false;
      this.mouseDragged = false;
   }

   private SlippyMapPoint getPointUnderMouse(double mouseX, double mouseY) {
      int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
      int mapX = (int)((mouseX - this.getX()) * scale) + this.map.getCameraX();
      int mapY = (int)((mouseY - this.getY()) * scale) + this.map.getCameraY();
      return new SlippyMapPoint(mapX, mapY, this.map.getCameraZoom());
   }

   protected void updateWidgetNarration( NarrationElementOutput narration) {
   }
}
