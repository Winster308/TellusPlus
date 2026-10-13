package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.preload.TellusPreloadClientState;
import com.yucareux.tellus.config.TellusCnOptions;
import com.yucareux.tellus.network.TellusPreloadDeletePayload;
import com.yucareux.tellus.preload.ArnisSources;
import com.yucareux.tellus.preload.PreloadStorage;
import com.yucareux.tellus.preload.TellusPreloadRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The TellusCN Manager screen inside Mod Menu.
 *
 * <p>What users expect from this entry point: the data source (two choices, tellus / arnis),
 * the celestial body (Earth / Moon / Mars), the map theme (always the arnis one),
 * <b>the disk usage of the downloaded data</b>, and an entry point for
 * <b>rubber-band selection deletion</b>.
 *
 * <p>The "clear cache" button sends a network packet and lets the server delete (on single player
 * the server runs on the same machine, but the ledger lives in server memory, so only the server
 * can keep the ledger in sync); the usage statistics read the local directory directly, so the
 * client can report exact numbers.
 */
@Environment(EnvType.CLIENT)
public class TellusManageScreen extends Screen {

   private static final int ROW_HEIGHT = 22;
   private static final int WIDGET_WIDTH = 220;
   private static final int WIDGET_HEIGHT = 20;

   private final Screen parent;
   private final List<Button> rows = new ArrayList<>();

   /**
    * Cache usage information.
    *
    * <p><b>Must be cached</b>: the statistics walk the whole cache directory (possibly tens of
    * thousands of files / several GB). They used to sit directly in {@code render()}, that is
    * <b>one full walk per frame</b>, which turned the Mod Menu screen into a slideshow.
    * Now they are computed once when the screen opens and refreshed at most every
    * {@value #USAGE_REFRESH_MS} ms.
    */
   private List<String> cachedInfo = List.of("（统计中……）");
   private long lastUsageAt;
   private static final long USAGE_REFRESH_MS = 3000L;

   /** Packets cannot be sent outside a world (otherwise Fabric throws IllegalStateException and crashes the client). */
   private boolean canTalkToServer() {
      return this.minecraft != null
            && this.minecraft.level != null
            && this.minecraft.player != null
            && ClientPlayNetworking.canSend(
                  com.yucareux.tellus.network.TellusPreloadRequestPayload.TYPE);
   }

   public TellusManageScreen(Screen parent) {
      super(Component.translatable("tellusplus.screen.manage.title"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      this.rows.clear();
      int y = 44;
      int x = this.width / 2 - WIDGET_WIDTH / 2;

      this.addRow(Button.builder(Component.empty(), button -> {
         TellusCnOptions.DataSource next = TellusCnOptions.dataSource() == TellusCnOptions.DataSource.TELLUS
               ? TellusCnOptions.DataSource.ARNIS
               : TellusCnOptions.DataSource.TELLUS;
         TellusCnOptions.setDataSource(next);
         this.refreshLabels();
      }).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
      y += ROW_HEIGHT;

      this.addRow(Button.builder(Component.empty(), button -> {
         TellusCnOptions.CelestialBody[] bodies = TellusCnOptions.CelestialBody.values();
         TellusCnOptions.CelestialBody current = TellusCnOptions.celestialBody();
         TellusCnOptions.setCelestialBody(bodies[(current.ordinal() + 1) % bodies.length]);
         this.refreshLabels();
      }).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
      y += ROW_HEIGHT;

      this.addRow(Button.builder(Component.empty(), button -> {
         String[] themes = new String[ArnisSources.MAP_THEMES.length];
         for (int i = 0; i < themes.length; i++) {
            themes[i] = ArnisSources.MAP_THEMES[i][0];
         }
         String current = TellusCnOptions.mapTheme();
         int index = 0;
         for (int i = 0; i < themes.length; i++) {
            if (themes[i].equals(current)) {
               index = i;
            }
         }
         TellusCnOptions.setMapTheme(themes[(index + 1) % themes.length]);
         this.refreshLabels();
      }).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
      y += ROW_HEIGHT;

      this.addRow(Button.builder(Component.empty(), button -> {
         TellusCnOptions.setPreloadToDisk(!TellusCnOptions.preloadToDisk());
         this.refreshLabels();
      }).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
      y += ROW_HEIGHT + 6;

      this.addRow(Button.builder(Component.translatable("tellusplus.cache.clear", "tellus"), button -> {
         if (!this.canTalkToServer()) {
            this.hintKey("tellusplus.screen.manage.need_world");
            return;
         }
         ClientPlayNetworking.send(new TellusPreloadDeletePayload(0.0, 0.0, 0.0, 0.0,
               TellusPreloadRegistry.SOURCE_TELLUS));
         this.invalidateUsage();
         this.hintKey("tellusplus.screen.manage.clear_requested", "tellus");
      }).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
      y += ROW_HEIGHT;

      this.addRow(Button.builder(Component.translatable("tellusplus.cache.clear", "arnis"), button -> {
         if (!this.canTalkToServer()) {
            this.hintKey("tellusplus.screen.manage.need_world");
            return;
         }
         ClientPlayNetworking.send(new TellusPreloadDeletePayload(0.0, 0.0, 0.0, 0.0,
               TellusPreloadRegistry.SOURCE_ARNIS));
         this.invalidateUsage();
         this.hintKey("tellusplus.screen.manage.clear_requested", "arnis");
      }).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
      y += ROW_HEIGHT + 6;

      this.addRow(Button.builder(Component.translatable("tellusplus.screen.manage.open_map"), button -> {
         if (!this.canTalkToServer()) {
            this.hintKey("tellusplus.screen.manage.need_world_map");
            return;
         }
         if (this.minecraft != null) {
            double latitude = this.minecraft.player != null ? this.minecraft.player.getZ() / 1000.0 : 0.0;
            double longitude = this.minecraft.player != null ? this.minecraft.player.getX() / 1000.0 : 0.0;
            this.minecraft.setScreenAndShow(new EarthTeleportScreen(this, latitude, longitude));
         }
      }).bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
      y += ROW_HEIGHT;

      this.addRow(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
            .bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());

      this.refreshLabels();

      // Outside a world (for example when Mod Menu is opened from the title screen): grey out the
      // server-dependent buttons and say why, instead of letting the user click and only then
      // finding out - clicking used to crash the client outright (Cannot send packets when not in game!).
      //
      // 2026-10-10: the marker is appended as a *translated* suffix rather than concatenated onto the
      // button text, because concatenation broke the layout for long English labels and could not be
      // localised independently of the label it was glued to.
      if (!this.canTalkToServer()) {
         for (int index : new int[]{4, 5, 6}) {
            if (index < this.rows.size()) {
               Button button = this.rows.get(index);
               button.active = false;
               button.setMessage(Component.translatable("tellusplus.screen.manage.needs_world_suffix",
                     button.getMessage()));
            }
         }
      }
   }

   private void addRow(Button button) {
      this.rows.add(button);
      this.addRenderableWidget(button);
   }

   private void refreshLabels() {
      TellusCnOptions.DataSource source = TellusCnOptions.dataSource();
      TellusCnOptions.CelestialBody body = TellusCnOptions.celestialBody();
      if (this.rows.size() >= 8) {
         // 2026-10-10: translatable rows. The old text concatenated an explanatory tail onto the value
         // ("DataSource: tellus (click to switch; the two sources download separately)"). English is much
         // longer than Chinese, so those tails are now separate keys and are shown as the button tooltip
         // instead of being glued into the label -- the label stays one line in both languages.
         this.rows.get(0).setMessage(Component.translatable("tellusplus.screen.manage.row.source",
               Component.translatable("tellusplus.source." + source.name().toLowerCase(Locale.ROOT))));
         this.rows.get(0).setTooltip(Tooltip.create(Component.translatable("tellusplus.screen.manage.tooltip.source")));

         this.rows.get(1).setMessage(Component.translatable("tellusplus.screen.manage.row.celestial",
               Component.translatable("tellusplus.body." + body.id())));
         this.rows.get(1).setTooltip(Tooltip.create(Component.translatable("tellusplus.screen.manage.tooltip.celestial")));

         this.rows.get(2).setMessage(Component.translatable("tellusplus.screen.manage.row.theme",
               Component.translatable("tellusplus.theme." + TellusCnOptions.mapTheme())));
         this.rows.get(2).setTooltip(Tooltip.create(Component.translatable("tellusplus.screen.manage.tooltip.theme")));

         this.rows.get(3).setMessage(Component.translatable("tellusplus.screen.manage.row.cache",
               Component.translatable(TellusCnOptions.preloadToDisk()
                     ? "tellusplus.toggle.on" : "tellusplus.toggle.off")));
         this.rows.get(3).setTooltip(Tooltip.create(Component.translatable("tellusplus.screen.manage.tooltip.cache")));
      }
   }

   /** Show a localised line to the player, prefixed with the mod name. */
   private void hintKey(String key, Object... args) {
      if (this.minecraft != null && this.minecraft.player != null) {
         this.minecraft.player.sendSystemMessage(Component.translatable("tellusplus.chat.prefix")
                     .append(Component.translatable(key, args)));
      }
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

      int infoY = this.height - 92;
      for (String line : this.infoLines()) {
         graphics.centeredText(this.font, Component.literal(line), this.width / 2, infoY, 0xA0A0A0);
         infoY += 11;
      }
   }

   /** Invalidates the cache so the next frame recomputes it (called after the cache was cleared). */
   private void invalidateUsage() {
      this.lastUsageAt = 0L;
   }

   /**
    * Info lines - <b>recomputed only when the cache expired</b>.
    *
    * <p>This used to walk the directory directly and was called from {@code render()} every frame:
    * tens of thousands of files times 60 FPS froze the screen completely. It is now throttled by
    * {@link #USAGE_REFRESH_MS}.
    */
   private List<String> infoLines() {
      long now = System.currentTimeMillis();
      if (now - this.lastUsageAt < USAGE_REFRESH_MS && !this.cachedInfo.isEmpty()) {
         return this.cachedInfo;
      }
      this.lastUsageAt = now;
      List<String> lines = new ArrayList<>();
      lines.add(PreloadStorage.describe());
      lines.add(String.format(Locale.ROOT, "账本：tellus %.1f km^2 / arnis %.1f km^2",
            TellusPreloadClientState.regions().stream()
                  .filter(r -> TellusPreloadRegistry.SOURCE_TELLUS.equals(r.effectiveSource()))
                  .mapToDouble(TellusPreloadRegistry.Region::areaKm2).sum(),
            TellusPreloadClientState.regions().stream()
                  .filter(r -> TellusPreloadRegistry.SOURCE_ARNIS.equals(r.effectiveSource()))
                  .mapToDouble(TellusPreloadRegistry.Region::areaKm2).sum()));
      lines.add("注意：切换数据源或天体后，两个数据源都需要重新预加载");
      this.cachedInfo = lines;
      return lines;
   }

   @Override
   public void onClose() {
      if (this.minecraft != null) {
         this.minecraft.setScreenAndShow(this.parent);
      }
   }
}
