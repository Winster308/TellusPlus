package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.PlaceSearchWidget;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.MarkerMapComponent;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import com.yucareux.tellus.client.widget.CoordinateInput;
import com.yucareux.tellus.client.preload.TellusPreloadClientState;
import com.yucareux.tellus.client.widget.map.component.PreloadOverlayMapComponent;
import com.yucareux.tellus.client.widget.map.component.RegionSelectionMapComponent;
import com.yucareux.tellus.network.TellusPreloadDeletePayload;
import com.yucareux.tellus.network.TellusPreloadRequestPayload;
import com.yucareux.tellus.client.preload.TellusPreloadClientState;
import com.yucareux.tellus.preload.ArnisSources;
import com.yucareux.tellus.preload.PreloadLayers;

@Environment(EnvType.CLIENT)
public class EarthTeleportScreen extends Screen {
   private static final int DEFAULT_ZOOM = 6;
   
   private final Screen parent;
   private final double initialLatitude;
   private final double initialLongitude;
   private SlippyMapWidget mapWidget;
   private MarkerMapComponent markerComponent;
   private PlaceSearchWidget searchWidget;
   /** Exact coordinate input: paste coordinates to move the marker (the teleport target) there. */
   private CoordinateInput coordinateInput;
   /** The selected rectangle (used for preloading). */
   private RegionSelectionMapComponent selectionComponent;
   /** Overlay: downloaded = original colour, not downloaded = grey. */
   private PreloadOverlayMapComponent overlayComponent;
   private boolean selectionMode;
   /** Whether a selection means "download" or "delete". */
   private boolean deleteMode;

   public EarthTeleportScreen( Screen parent, double latitude, double longitude) {
      super(Component.translatable("gui.earth.teleport_map"));
      this.parent = parent;
      this.initialLatitude = latitude;
      this.initialLongitude = longitude;
   }

   protected void init() {
      if (this.mapWidget != null) {
         this.mapWidget.close();
      }

      int mapX = 20;
      int mapY = 20;
      int mapWidth = this.width - 40;
      int mapHeight = this.height - 60;
      this.mapWidget = new SlippyMapWidget(mapX, mapY, mapWidth, mapHeight);
      this.markerComponent = new MarkerMapComponent(new SlippyMapPoint(this.initialLatitude, this.initialLongitude)).allowMovement();
      this.mapWidget.addComponent(this.markerComponent);
      this.mapWidget.getMap().focus(this.initialLatitude, this.initialLongitude, DEFAULT_ZOOM);
      Geocoder geocoder = new NominatimGeocoder();
      this.searchWidget = new PlaceSearchWidget(mapX + 5, mapY + 5, 200, 20, geocoder, this::handleSearch);
      this.coordinateInput = new CoordinateInput(this.font, mapX + 5, mapY + 30, 200, this::handleCoordinates);
      this.coordinateInput.setMarker(this.initialLatitude, this.initialLongitude);
      this.selectionComponent = new RegionSelectionMapComponent();
      this.selectionComponent.setEnabled(false);
      this.mapWidget.addComponent(this.selectionComponent);
      this.overlayComponent = new PreloadOverlayMapComponent(this.mapWidget);
      this.mapWidget.addComponent(this.overlayComponent);
      this.addRenderableOnly(this.mapWidget);
      this.addRenderableWidget(this.searchWidget);
      for (AbstractWidget widget : this.coordinateInput.widgets()) {
         this.addRenderableWidget(widget);
      }
      int buttonY = this.height - 28;
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.earth.teleport"), button -> this.sendTeleport()).bounds(this.width / 2 - 154, buttonY, 150, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel"), button -> this.closeScreen()).bounds(this.width / 2 + 4, buttonY, 150, 20).build()
      );
      // Preload related: region selection toggle plus download the selected region plus shading toggle
      int preloadY = buttonY - 24;
      this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.toggle"), button -> {
            this.selectionMode = !this.selectionMode;
            this.selectionComponent.setEnabled(this.selectionMode);
            this.markerComponent.setMovable(!this.selectionMode);
            if (!this.selectionMode) {
               this.selectionComponent.clear();
            }
            button.setMessage(Component.translatable(
                  this.selectionMode ? "tellus.preload.toggle.on" : "tellus.preload.toggle"));
         }).bounds(this.width / 2 - 234, preloadY, 150, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.download"), button -> this.downloadSelection())
            .bounds(this.width / 2 - 78, preloadY, 150, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.overlay"), button -> {
            this.overlayComponent.setEnabled(!this.overlayComponent.isEnabled());
         }).bounds(this.width / 2 + 78, preloadY, 150, 20).build()
      );
      // Delete mode: after selecting a rectangle, delete the downloaded records and cache files inside it
      // (the arnis source deletes the exact files of the rectangle)
      this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.delete.toggle"), button -> {
            this.deleteMode = !this.deleteMode;
            if (this.deleteMode) {
               this.selectionMode = true;
               this.selectionComponent.setEnabled(true);
               this.markerComponent.setMovable(false);
            }
            button.setMessage(Component.translatable(this.deleteMode
                  ? "tellus.preload.delete.toggle.on" : "tellus.preload.delete.toggle"));
         }).bounds(this.width / 2 - 78, preloadY - 24, 150, 20).build()
      );
      // Base map switching: done <b>right on this screen</b>, no trip to Mod Menu needed.
      // The cache key now carries the theme (see SlippyMapTileCache), so after switching the tiles
      // are fetched again and old and new never get mixed.
      this.addRenderableWidget(
         Button.builder(themeLabel(), button -> {
            String[] themes = new String[ArnisSources.MAP_THEMES.length];
            for (int i = 0; i < themes.length; i++) {
               themes[i] = ArnisSources.MAP_THEMES[i][0];
            }
            String current = com.yucareux.tellus.config.TellusCnOptions.mapTheme();
            int index = 0;
            for (int i = 0; i < themes.length; i++) {
               if (themes[i].equalsIgnoreCase(current)) {
                  index = i;
               }
            }
            com.yucareux.tellus.config.TellusCnOptions.setMapTheme(themes[(index + 1) % themes.length]);
            button.setMessage(themeLabel());
         }).bounds(this.width / 2 + 78, buttonY - 48, 150, 20).build()
      );
      this.addWidget(this.mapWidget);
      // Ask the server for the freshest ledger once when this screen opens (the server answers a
      // degenerate rectangle with a snapshot only).
      // Note: packets cannot be sent outside a world (for example when this screen is opened from
      // the Mod Menu on the title screen), otherwise the client crashes right away.
      if (this.canTalkToServer()) {
         ClientPlayNetworking.send(new TellusPreloadRequestPayload(0.0, 0.0, 0.0, 0.0, ""));
      }
   }

   protected void setInitialFocus() {
      if (this.searchWidget != null) {
         this.setInitialFocus(this.searchWidget);
      }
   }

   /**
    * Whether the server can be talked to.
    *
    * <p>This screen can now also be opened from the <b>Mod Menu config screen</b>, and that one may
    * sit on the <b>title screen</b> (no world loaded). Sending a C2S packet there makes Fabric
    * throw {@code IllegalStateException: Cannot send packets when not in game!}, which crashes the
    * client. So every send goes through this check first.
    */
   private boolean canTalkToServer() {
      return this.minecraft != null
            && this.minecraft.level != null
            && this.minecraft.player != null
            && ClientPlayNetworking.canSend(TellusPreloadRequestPayload.TYPE);
   }

   /** Base map button text: shows the current theme plus the optional source name (the Chinese description is used when present). */
   private static Component themeLabel() {
      String current = com.yucareux.tellus.config.TellusCnOptions.mapTheme();
      String readable = current;
      for (String[] entry : ArnisSources.MAP_THEMES) {
         if (entry[0].equalsIgnoreCase(current)) {
            readable = entry[1];
         }
      }
      return Component.literal("底图：" + readable);
   }

   private void handleSearch(double latitude, double longitude) {
      this.markerComponent.moveMarker(latitude, longitude);
      this.coordinateInput.setMarker(latitude, longitude);
      this.mapWidget.getMap().focus(latitude, longitude, 12);
   }

   /** Exact coordinates: move the teleport marker to that point (the "Teleport" button uses the marker position). */
   private void handleCoordinates(double latitude, double longitude) {
      this.markerComponent.moveMarker(latitude, longitude);
      this.mapWidget.getMap().focus(latitude, longitude, 12);
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      // Cancel the map drag interaction when the coordinate bar is clicked, otherwise the screen
      // ends up "selecting a rectangle while dragging the map"
      if (this.coordinateInput != null && this.coordinateInput.isMouseOver(mouseX, mouseY)
            && this.mapWidget != null) {
         this.mapWidget.cancelInteraction();
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   /** Sends the selected rectangle to the server for preloading; the server skips what is already downloaded. */
   private void downloadSelection() {
      if (!this.canTalkToServer()) {
         if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                  Component.translatable("tellus.preload.need_world"), false);
         }
         return;
      }
      if (!this.selectionComponent.hasSelection()) {
         if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                  Component.translatable("tellus.preload.need_selection"), false);
         }
         return;
      }
      double minLat = this.selectionComponent.minLatitude();
      double maxLat = this.selectionComponent.maxLatitude();
      double minLon = this.selectionComponent.minLongitude();
      double maxLon = this.selectionComponent.maxLongitude();
      if (this.deleteMode) {
         if (!this.canTalkToServer()) {
            return;
         }
         ClientPlayNetworking.send(new TellusPreloadDeletePayload(minLat, minLon, maxLat, maxLon, ""));
         this.selectionComponent.clear();
         if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(Component.translatable("tellus.preload.delete.sent"), false);
         }
         return;
      }
      java.util.Set<String> layers = PreloadLayers.defaults();
      if (TellusPreloadClientState.alreadyDownloaded(minLat, minLon, maxLat, maxLon, layers)) {
         if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                  Component.translatable("tellus.preload.already"), false);
         }
         return;
      }
      ClientPlayNetworking.send(new TellusPreloadRequestPayload(minLat, minLon, maxLat, maxLon,
            PreloadLayers.format(layers)));
      if (this.minecraft != null && this.minecraft.player != null) {
         this.minecraft.player.displayClientMessage(Component.translatable("tellus.preload.sent"), false);
      }
   }

   private void sendTeleport() {
      if (this.markerComponent != null) {
         SlippyMapPoint marker = this.markerComponent.getMarker();
         if (marker != null && this.minecraft != null) {
            if (!ClientPlayNetworking.canSend(GeoTpTeleportPayload.TYPE)) {
               if (this.minecraft.player != null) {
                  this.minecraft.player.displayClientMessage(Component.literal("Tellus：服务端不接受 GeoTP 请求。"), true);
               }

               this.closeScreen();
            } else {
               ClientPlayNetworking.send(new GeoTpTeleportPayload(marker.getLatitude(), marker.getLongitude()));
               this.closeScreen();
            }
         }
      }
   }

   private void closeScreen() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   public void extractBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1072689136);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      super.render(graphics, mouseX, mouseY, delta);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 4, 16777215);
      if (this.coordinateInput != null) {
         this.coordinateInput.render(graphics, this.font);
      }
   }

   public void tick() {
      super.tick();
      if (this.searchWidget != null) {
         this.searchWidget.tick();
      }
   }

   public void onClose() {
      this.closeScreen();
   }

   public void removed() {
      if (this.mapWidget != null) {
         this.mapWidget.close();
      }

      if (this.searchWidget != null) {
         this.searchWidget.close();
      }
   }
}
