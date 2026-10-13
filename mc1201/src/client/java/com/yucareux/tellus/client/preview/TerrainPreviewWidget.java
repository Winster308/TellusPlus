package com.yucareux.tellus.client.preview;

import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.Tooltip;
import com.yucareux.tellus.client.TellusGui;

@Environment(EnvType.CLIENT)
public final class TerrainPreviewWidget extends AbstractWidget implements AutoCloseable {
   private static final float DEFAULT_ROTATION_X = (float)Math.toRadians(18.0);
   private static final float DEFAULT_ROTATION_Y = (float)Math.toRadians(18.0);
   private static final float DEFAULT_ZOOM = 1.4F;
   private static final float MIN_ROTATION_X = (float)Math.toRadians(0.0);
   private static final float MAX_ROTATION_X = (float)Math.toRadians(80.0);
   private static final float MIN_ZOOM = 0.5F;
   private static final float MAX_ZOOM = 4.0F;
   private static final float ROTATION_SPEED = 0.01F;
   private static final float ZOOM_SPEED = 0.1F;
   private static final float AUTO_ROTATION_SPEED = -0.0022F;
   private static final long AUTO_RESUME_DELAY_MS = 1200L;

   /**
    * How often the auto-rotation actually advances, and by how much.
    *
    * <p>Throttled because each advance invalidates the preview's cached painter's-algorithm ordering
    * (it is keyed on the model-view matrix), which costs a full re-projection and re-sort of 65536
    * quads. Stepping every {@link #AUTO_ROTATION_STEP_MS} instead of every tick keeps the same slow
    * visual rotation at a fraction of the cost. See the note in {@link #tick()}.
    */
   private static final long AUTO_ROTATION_STEP_MS = 250L;
   private static final float AUTO_ROTATION_STEP = AUTO_ROTATION_SPEED * (AUTO_ROTATION_STEP_MS / 50.0F);

   /** Timestamp of the last auto-rotation step (not of the last user interaction). */
   private long lastAutoRotationStep;
   private static final int MODE_BUTTON_WIDTH = 36;
   private static final int MODE_BUTTON_HEIGHT = 20;
   private static final int MODE_BUTTON_PADDING = 6;
   private static final int INFO_BUTTON_SIZE = 20;
   private static final int INFO_BUTTON_PADDING = 6;
   private static final int INFO_PANEL_WIDTH = 320;
   private static final int INFO_PANEL_PADDING = 8;
   private static final int INFO_TITLE_SPACING = 14;
   private static final int INFO_LINE_SPACING = 11;
   private static final int INFO_SECTION_SPACING = 8;
   private static final int INFO_PANEL_BG = -1609957376;
   private static final int INFO_PANEL_BORDER = -12961222;
   private static final int INFO_TEXT = -1710619;
   private static final int INFO_SUBTLE = -4605511;
   private static final int INFO_GOOD = 0xFF6ED470;
   private static final int INFO_BAD = 0xFFF06262;
   private static final int FULLSCREEN_BUTTON_SIZE = 20;
   private static final int FULLSCREEN_BUTTON_PADDING = 6;
   
   private static final Component FULLSCREEN_LABEL = Component.literal("[ ]");
   private static final Component INFO_LABEL = Component.literal("i");

   /**
    * Fixed labels of the info panel.
    *
    * <p>2026-10-05 performance fix: the info panel redraws every frame and a translatable is not
    * cached, so these five labels used to allocate a translatable plus a resolved {@code String}
    * ({@code getString()} walks the language map and builds a new {@code StringBuilder} every time)
    * <b>five times per frame</b>. Same fix as
    * {@code EarthCustomizeScreen.CacheEntryWidget#DELETE_LABEL}: build the component once, still at
    * draw time so a language switch is still picked up by {@code GuiGraphics#drawString}.
    */
   private static final Component INFO_TITLE_LABEL = Objects.requireNonNull(Component.translatable("tellus.preview.info.title"), "infoTitleLabel");
   private static final Component INFO_LOADING_LABEL = Objects.requireNonNull(Component.translatable("tellus.preview.info.loading"), "infoLoadingLabel");
   private static final Component INFO_BLENDED_LABEL = Objects.requireNonNull(Component.translatable("tellus.preview.info.blended"), "infoBlendedLabel");
   private static final Component INFO_HIGHEST_LABEL = Objects.requireNonNull(Component.translatable("tellus.preview.info.highest"), "infoHighestLabel");
   private static final Component INFO_MIN_Y_LABEL = Objects.requireNonNull(Component.translatable("tellus.preview.info.min_y"), "infoMinYLabel");
   private static final Component INFO_MAX_Y_LABEL = Objects.requireNonNull(Component.translatable("tellus.preview.info.max_y"), "infoMaxYLabel");
   private static final int LOADING_PANEL_BG = -871362544;
   private static final int LOADING_PANEL_BORDER = -12961222;
   private static final int LOADING_BAR_BG = -15066598;
   private static final int LOADING_BAR_BORDER = -13816531;
   private static final int LOADING_BAR_FILL = -12599473;
   private static final int LOADING_TEXT = -1;
   private final TerrainPreview preview;
   private final boolean ownsPreview;
   private final Button modeButton;
   private final Button infoButton;
   private final Button autoAdjustButton;
   private final Button fullscreenButton;
   
   private Function<TerrainPreview.PreviewInfo, EarthGeneratorSettings> autoAdjustAction;
   private Runnable fullscreenAction;
   private boolean dragging;
   private boolean infoPanelVisible;
   private float rotationX = DEFAULT_ROTATION_X;
   private float rotationY = DEFAULT_ROTATION_Y;
   private float zoom = DEFAULT_ZOOM;
   private TerrainPreviewWidget.RenderMode renderMode = TerrainPreviewWidget.RenderMode.FULL_DETAIL;
   private long lastInteractionTime;

   /**
    * Cached info panel geometry and text - <b>rebuilt only when an input actually changes</b>.
    *
    * <p>2026-10-05 performance fix. Both used to be recomputed from scratch on every single frame
    * while the panel is open (twice per frame for the geometry, once in {@code renderInfoPanel} and
    * once again in {@code isMouseOverInfoPanel} on every mouse event):
    * <ul>
    *    <li>the {@link TerrainPreviewWidget.InfoPanelLayout} record, from
    *    {@code (x, y, width, hasBlendedProviders)} - it only depends on those, and it did not even
    *    use the {@code Font} its signature asked for;</li>
    *    <li>the panel text, from about eight {@code Component.translatable(...).getString()}
    *    lookups, several {@code StringBuilder}s and two or three {@code String.format} calls,
    *    plus the {@code truncate} width probing - all of it thrown away the moment it was drawn.</li>
    * </ul>
    * The keys are exactly the inputs: {@link TerrainPreview.PreviewInfo} is an immutable record that
    * {@code TerrainPreview} replaces wholesale on every rebuild, so a slider drag invalidates the
    * text immediately; the font identity and content width feed {@code truncate}; the selected
    * language is included because a resource reload (F3+T) swaps it under a running screen.
    */
   private Font cachedInfoFont;
   private String cachedInfoLanguage;
   private int cachedInfoContentWidth;
   private TerrainPreview.PreviewInfo cachedInfoSource;
   private TerrainPreviewWidget.InfoPanelText cachedInfoText;
   private int cachedLayoutInfoButtonX = Integer.MIN_VALUE;
   private int cachedLayoutInfoButtonY;
   private int cachedLayoutWidth;
   private boolean cachedLayoutBlended;
   private TerrainPreviewWidget.InfoPanelLayout cachedLayout;

   public TerrainPreviewWidget(int x, int y, int width, int height) {
      this(x, y, width, height, new TerrainPreview(), true);
   }

   public TerrainPreviewWidget(int x, int y, int width, int height, TerrainPreview preview) {
      this(x, y, width, height, preview, false);
   }

   private TerrainPreviewWidget(int x, int y, int width, int height, TerrainPreview preview, boolean ownsPreview) {
      super(x, y, width, height, Objects.requireNonNull(Component.empty(), "widgetMessage"));
      this.preview = preview;
      this.ownsPreview = ownsPreview;
      this.modeButton = Button.builder(this.renderMode.buttonLabel(), button -> this.toggleRenderMode())
         .bounds(0, 0, MODE_BUTTON_WIDTH, MODE_BUTTON_HEIGHT)
         .build();
      this.modeButton.setTooltip(Tooltip.create(Component.literal("在「纯地形 3D」与「带预览树木/道路的 3D」之间切换")));
      this.infoButton = Button.builder(Objects.requireNonNull(INFO_LABEL, "infoLabel"), button -> this.toggleInfoPanel())
         .bounds(0, 0, INFO_BUTTON_SIZE, INFO_BUTTON_SIZE)
         .build();
      this.infoButton.setTooltip(Tooltip.create(Component.translatable("tellus.preview.info.button.tooltip")));
      this.autoAdjustButton = Button.builder(Component.translatable("tellus.preview.info.auto_adjust"), button -> this.runAutoAdjust())
         .bounds(0, 0, 110, 20)
         .build();
      this.fullscreenButton = Button.builder(Objects.requireNonNull(FULLSCREEN_LABEL, "fullscreenLabel"), button -> {
         if (this.fullscreenAction != null) {
            this.fullscreenAction.run();
         }
      }).bounds(0, 0, FULLSCREEN_BUTTON_SIZE, FULLSCREEN_BUTTON_SIZE).build();
      this.fullscreenButton.active = false;
   }

   public void requestRebuild(EarthGeneratorSettings settings) {
      this.preview.requestRebuild(settings);
   }

   public void setFullscreenAction(Runnable action) {
      this.fullscreenAction = action;
      this.fullscreenButton.active = action != null;
   }

   public void setAutoAdjustAction(Function<TerrainPreview.PreviewInfo, EarthGeneratorSettings> action) {
      this.autoAdjustAction = action;
   }

   public TerrainPreview getPreview() {
      return this.preview;
   }

   public TerrainPreviewWidget.ViewState getViewState() {
      return new TerrainPreviewWidget.ViewState(this.rotationX, this.rotationY, this.zoom, this.renderMode);
   }

   public void setViewState(TerrainPreviewWidget.ViewState state) {
      this.rotationX = Mth.clamp(state.rotationX(), MIN_ROTATION_X, MAX_ROTATION_X);
      this.rotationY = state.rotationY();
      this.zoom = Mth.clamp(state.zoom(), MIN_ZOOM, MAX_ZOOM);
      this.renderMode = state.renderMode();
      this.updateModeButtonLabel();
   }

   public void tick() {
      this.preview.tick();
      long now = System.currentTimeMillis();
      if (!this.dragging && now - this.lastInteractionTime > AUTO_RESUME_DELAY_MS) {
         // 2026-10-11: auto-rotation is throttled to a fixed step interval.
         //
         //   Why: the preview's painter's-algorithm ordering is cached against the model-view matrix
         //   (see TerrainPreview.PreviewMesh.sortedQuadTopLeft, which compares the matrix component by
         //   component). Advancing rotationY on EVERY tick changed that matrix on every frame, so the
         //   cache never hit and all 65536 quads were re-projected and re-sorted with an O(n log n)
         //   quicksort -- about 1.05 million comparisons per frame -- just to turn the view by
         //   0.126 degrees. That was the preview's "still laggy" complaint.
         //
         //   Rotating in discrete steps instead means the expensive re-sort happens once per
         //   AUTO_ROTATION_STEP_MS rather than once per frame, while the visual result is the same
         //   slow rotation the user already had (the step is larger, the interval longer).
         //
         //   Dragging is unaffected: while dragging, this branch is not taken at all, so a real drag
         //   still re-sorts every frame and keeps an exact ordering.
         if (now - this.lastAutoRotationStep >= AUTO_ROTATION_STEP_MS) {
            this.lastAutoRotationStep = now;
            this.rotationY += AUTO_ROTATION_STEP;
         }
      }
   }

   protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      this.renderBlurredBackground(graphics);
      int inset = 0;
      int contentX = this.getX() + inset;
      int contentY = this.getY() + inset;
      int contentWidth = Math.max(1, this.width - inset * 2);
      int contentHeight = Math.max(1, this.height - inset * 2);
      this.preview.render(graphics, contentX, contentY, contentWidth, contentHeight, this.rotationX, this.rotationY, this.zoom, this.renderMode);
      this.renderLoadingOverlay(graphics, contentX, contentY, contentWidth, contentHeight);
      this.renderModeButton(graphics, mouseX, mouseY, delta);
      this.renderInfoButton(graphics, mouseX, mouseY, delta);
      this.renderInfoPanel(graphics, mouseX, mouseY, delta);
      this.renderFullscreenButton(graphics, mouseX, mouseY, delta);
   }

   /**
    * 1.20.1 port note: {@code MouseButtonEvent} (1.21+) does not exist. The framework override is the
    * old-style {@code mouseClicked(double, double, int)}. The three {@code onClick}/{@code onDrag}/
    * {@code onRelease} helpers below are this class's own API (they are not framework overrides), so
    * they were converted to the same coordinate parameters and are called from here.
    */
   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (!this.active || !this.visible || !this.isMouseOver(mouseX, mouseY)) {
         return false;
      } else if (this.modeButton.isMouseOver(mouseX, mouseY)) {
         this.modeButton.mouseClicked(mouseX, mouseY, button);
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else if (this.infoButton.isMouseOver(mouseX, mouseY)) {
         this.infoButton.mouseClicked(mouseX, mouseY, button);
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else if (this.infoPanelVisible && this.autoAdjustButton.isMouseOver(mouseX, mouseY)) {
         this.autoAdjustButton.mouseClicked(mouseX, mouseY, button);
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else if (this.fullscreenAction != null && this.fullscreenButton.isMouseOver(mouseX, mouseY)) {
         this.fullscreenButton.mouseClicked(mouseX, mouseY, button);
         return true;
      } else if (this.infoPanelVisible && this.isMouseOverInfoPanel(mouseX, mouseY)) {
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else {
         if (button == 0) {
            this.dragging = true;
            this.lastInteractionTime = System.currentTimeMillis();
         }

         return true;
      }
   }

   public void onClick(double mouseX, double mouseY, boolean doubleClick) {
      if (this.modeButton.isMouseOver(mouseX, mouseY)) {
         this.modeButton.mouseClicked(mouseX, mouseY, 0);
         this.lastInteractionTime = System.currentTimeMillis();
      } else if (this.infoButton.isMouseOver(mouseX, mouseY)) {
         this.infoButton.mouseClicked(mouseX, mouseY, 0);
         this.lastInteractionTime = System.currentTimeMillis();
      } else if (this.infoPanelVisible && this.autoAdjustButton.isMouseOver(mouseX, mouseY)) {
         this.autoAdjustButton.mouseClicked(mouseX, mouseY, 0);
         this.lastInteractionTime = System.currentTimeMillis();
      } else if (this.fullscreenAction != null && this.fullscreenButton.isMouseOver(mouseX, mouseY)) {
         this.fullscreenButton.mouseClicked(mouseX, mouseY, 0);
      } else if (this.infoPanelVisible && this.isMouseOverInfoPanel(mouseX, mouseY)) {
         this.lastInteractionTime = System.currentTimeMillis();
      } else {
         // 1.20.1: the old body tested `button == 0` before starting a drag. This helper no
         // longer receives the button (MouseButtonEvent is gone, and the framework override that had
         // one now does the left-click check itself before reaching here), so the drag starts
         // unconditionally on the fall-through path.
         this.dragging = true;
         this.lastInteractionTime = System.currentTimeMillis();
      }
   }

   /**
    * 1.20.1: {@code AbstractWidget} has {@code setWidth(int)} but no {@code setHeight(int)} (that
    * asymmetric pair is 1.21 API). The protected {@code height} field is writable from inside a
    * subclass, which this is, so the missing setter is supplied here rather than at every call site.
    */
   public void setHeight(int height) {
      this.height = height;
   }

   protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
      if (this.dragging) {
         this.rotationY += (float)deltaX * ROTATION_SPEED;
         this.rotationX = Mth.clamp(this.rotationX + (float)deltaY * ROTATION_SPEED, MIN_ROTATION_X, MAX_ROTATION_X);
         this.lastInteractionTime = System.currentTimeMillis();
      }
   }

   public void onRelease(double mouseX, double mouseY) {
      this.modeButton.mouseReleased(mouseX, mouseY, 0);
      this.infoButton.mouseReleased(mouseX, mouseY, 0);
      this.autoAdjustButton.mouseReleased(mouseX, mouseY, 0);
      if (this.fullscreenAction != null) {
         this.fullscreenButton.mouseReleased(mouseX, mouseY, 0);
      }

      this.dragging = false;
      this.lastInteractionTime = System.currentTimeMillis();
   }

   /**
    * 1.20.1 port note: the signature is {@code mouseScrolled(double, double, double)} -- it lost the
    * horizontal amount (the 4-double form is 1.21+), which matches
    * {@code GuiEventListener.mouseScrolled} in this version. The body only ever used
    * {@code verticalAmount}, so behaviour is unchanged.
    */
   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
      if (this.modeButton.isMouseOver(mouseX, mouseY)
         || this.infoButton.isMouseOver(mouseX, mouseY)
         || this.infoPanelVisible && this.isMouseOverInfoPanel(mouseX, mouseY)
         || this.fullscreenAction != null && this.fullscreenButton.isMouseOver(mouseX, mouseY)) {
         return false;
      } else if (this.isMouseOver(mouseX, mouseY)) {
         this.zoom = Mth.clamp(this.zoom + (float)verticalAmount * ZOOM_SPEED, MIN_ZOOM, MAX_ZOOM);
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else {
         return super.mouseScrolled(mouseX, mouseY, verticalAmount);
      }
   }

   protected void updateWidgetNarration(NarrationElementOutput narration) {
   }

   private void renderBlurredBackground(GuiGraphics graphics) {
      int left = this.getX();
      int top = this.getY();
      int right = this.getX() + this.width;
      int bottom = this.getY() + this.height;
      graphics.fill(left, top, right, bottom, -2013265920);
      graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 855638016);
      graphics.fillGradient(left, top, right, top + 8, 1140850688, 335544320);
      graphics.fillGradient(left, bottom - 8, right, bottom, 335544320, 1140850688);
      TellusGui.outline(graphics, left, top, this.width, this.height, 402653184);
   }

   private void renderLoadingOverlay(GuiGraphics graphics, int x, int y, int width, int height) {
      if (this.preview.isLoading()) {
         TerrainPreview.PreviewStatus status = this.preview.getStatus();
         if (status.stage() != TerrainPreview.PreviewStage.COMPLETE) {
            String label = status.activity();
            if (label == null) {
               label = Objects.requireNonNull(switch (status.stage()) {
                  case DOWNLOADING -> "正在下载数据";
                  case PROCESSING_OSM -> "正在处理 OSM 数据";
                  case LOADING -> "正在处理数据";
                  case COMPLETE -> "正在处理数据";
               }, "loadingLabel");
            }
            float displayedProgress = displayedProgress(status);
            float percentValue = status.stage() == TerrainPreview.PreviewStage.COMPLETE ? 100.0F : Mth.clamp(displayedProgress * 100.0F, 0.0F, 99.9F);
            String percentText = Objects.requireNonNull(
               percentValue >= 10.0F ? String.format(Locale.ROOT, "%.0f%%", percentValue) : String.format(Locale.ROOT, "%.1f%%", percentValue),
               "loadingPercent"
            );
            Font font = Minecraft.getInstance().font;
            int padding = 6;
            int maxInnerWidth = Math.max(20, width - padding * 2 - 4);
            int barWidth = Math.min(180, maxInnerWidth);
            int textLineWidth = font.width(label) + font.width(percentText) + 12;
            int innerWidth = Math.min(maxInnerWidth, Math.max(barWidth, textLineWidth));
            int panelWidth = innerWidth + padding * 2;
            int barHeight = 8;
            int panelHeight = padding * 3 + 9 + barHeight;
            int panelX = x + (width - panelWidth) / 2;
            int panelY = y + height - panelHeight - 10;
            panelY = Mth.clamp(panelY, y + 6, y + height - panelHeight - 6);
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, LOADING_PANEL_BG);
            TellusGui.outline(graphics, panelX, panelY, panelWidth, panelHeight, LOADING_PANEL_BORDER);
            int textY = panelY + padding;
            graphics.drawString(font, label, panelX + padding, textY, LOADING_TEXT, true);
            graphics.drawString(font, percentText, panelX + panelWidth - padding - font.width(percentText), textY, LOADING_TEXT, true);
            int barX = panelX + padding;
            int barY = textY + 9 + padding;
            int barInnerWidth = innerWidth - 2;
            graphics.fill(barX, barY, barX + innerWidth, barY + barHeight, LOADING_BAR_BG);
            TellusGui.outline(graphics, barX, barY, innerWidth, barHeight, LOADING_BAR_BORDER);
            float clamped = Mth.clamp(displayedProgress, 0.0F, 1.0F);
            int fillWidth = Math.round(barInnerWidth * clamped);
            if (fillWidth == 0 && clamped > 0.0F) {
               fillWidth = 1;
            }

            if (fillWidth > 0) {
               graphics.fill(barX + 1, barY + 1, barX + 1 + fillWidth, barY + barHeight - 1, LOADING_BAR_FILL);
            }
         }
      }
   }

   private static float displayedProgress(TerrainPreview.PreviewStatus status) {
      float stageProgress = Mth.clamp(status.progress(), 0.0F, 1.0F);

      return switch (status.stage()) {
         case DOWNLOADING -> stageProgress * 0.4F;
         case PROCESSING_OSM -> 0.4F + stageProgress * 0.6F;
         case LOADING -> 0.4F + stageProgress * 0.6F;
         case COMPLETE -> 1.0F;
      };
   }

   private void toggleRenderMode() {
      this.renderMode = this.renderMode.toggle();
      this.updateModeButtonLabel();
      this.lastInteractionTime = System.currentTimeMillis();
      this.dragging = false;
   }

   private void toggleInfoPanel() {
      this.infoPanelVisible = !this.infoPanelVisible;
      this.lastInteractionTime = System.currentTimeMillis();
      this.dragging = false;
   }

   private void runAutoAdjust() {
      if (this.autoAdjustAction != null) {
         TerrainPreview.PreviewInfo info = this.preview.getInfo();
         if (info != null) {
            EarthGeneratorSettings settings = this.autoAdjustAction.apply(info);
            if (settings != null) {
               this.preview.requestRebuild(settings);
            }
         }
      }
   }

   private void updateModeButtonLabel() {
      this.modeButton.setMessage(this.renderMode.buttonLabel());
   }

   private void renderModeButton(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      int buttonX = this.getX() + MODE_BUTTON_PADDING;
      int buttonY = this.getY() + MODE_BUTTON_PADDING;
      this.modeButton.setX(buttonX);
      this.modeButton.setY(buttonY);
      this.modeButton.setWidth(MODE_BUTTON_WIDTH);
      // 1.20.1: AbstractWidget has no setHeight(int) (only setWidth). The height comes from
      // Button.builder(...).bounds(...) at construction time, which already used the same value.
      this.modeButton.render(graphics, mouseX, mouseY, delta);
   }

   private void renderInfoButton(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      int buttonX = this.getX() + MODE_BUTTON_PADDING;
      int buttonY = this.getY() + MODE_BUTTON_PADDING + MODE_BUTTON_HEIGHT + INFO_BUTTON_PADDING;
      this.infoButton.setX(buttonX);
      this.infoButton.setY(buttonY);
      this.infoButton.setWidth(INFO_BUTTON_SIZE);
      // 1.20.1: AbstractWidget has no setHeight(int) (only setWidth). The height comes from
      // Button.builder(...).bounds(...) at construction time, which already used the same value.
      this.infoButton.render(graphics, mouseX, mouseY, delta);
   }

   private void renderInfoPanel(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      if (!this.infoPanelVisible) {
         return;
      }

      // 2026-10-05: the panel can be asked to render before the client is fully initialised (no font
      // yet), so guard instead of letting the very first frame NPE. Returning early just skips one
      // frame of drawing.
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.font == null) {
         return;
      }

      Font font = minecraft.font;
      TerrainPreview.PreviewInfo info = this.preview.getInfo();
      // 2026-10-05: one layout per frame. isMouseOverInfoPanel() used to rebuild the very same
      // record a second time on every mouse event.
      TerrainPreviewWidget.InfoPanelLayout layout = this.infoPanelLayout(info);
      graphics.fill(layout.x(), layout.y(), layout.right(), layout.bottom(), INFO_PANEL_BG);
      TellusGui.outline(graphics, layout.x(), layout.y(), layout.width(), layout.height(), INFO_PANEL_BORDER);
      int textX = layout.x() + INFO_PANEL_PADDING;
      int textY = layout.y() + INFO_PANEL_PADDING;
      if (info == null) {
         graphics.drawString(font, INFO_TITLE_LABEL, textX, textY, INFO_TEXT, false);
         graphics.drawString(font, INFO_LOADING_LABEL, textX, textY + INFO_TITLE_SPACING, INFO_SUBTLE, false);
         this.autoAdjustButton.active = false;
      } else {
         // 2026-10-05: the whole text block is cached, so on a static frame it costs nothing but a
         // handful of pointer reads instead of ~8 translatable lookups + String.format + StringBuilders.
         TerrainPreviewWidget.InfoPanelText text = this.infoPanelText(info, font, layout.contentWidth());
         graphics.drawString(font, INFO_TITLE_LABEL, textX, textY, INFO_TEXT, false);
         textY += INFO_TITLE_SPACING;
         graphics.drawString(font, text.demSummary(), textX, textY, INFO_TEXT, false);
         textY += INFO_LINE_SPACING;
         graphics.drawString(font, text.resolutionSummary(), textX, textY, INFO_SUBTLE, false);
         textY += INFO_LINE_SPACING;
         if (info.hasBlendedProviders()) {
            graphics.drawString(font, text.blendedSummary(), textX, textY, INFO_SUBTLE, false);
            textY += INFO_LINE_SPACING;
         }

         graphics.drawString(font, text.altitudeSummary(), textX, textY, INFO_TEXT, false);
         textY += INFO_TITLE_SPACING;
         this.drawValueLine(
            graphics,
            font,
            textX,
            textY,
            text.minYLabel(),
            text.minYValue(),
            info.minWithinLimits() ? INFO_GOOD : INFO_BAD,
            layout.contentWidth()
         );
         textY += INFO_LINE_SPACING;
         this.drawValueLine(
            graphics,
            font,
            textX,
            textY,
            text.maxYLabel(),
            text.maxYValue(),
            info.maxWithinLimits() ? INFO_GOOD : INFO_BAD,
            layout.contentWidth()
         );
         textY += INFO_SECTION_SPACING;
         this.autoAdjustButton.active = this.autoAdjustAction != null;
      }

      this.autoAdjustButton.setX(layout.buttonX());
      this.autoAdjustButton.setY(layout.buttonY());
      this.autoAdjustButton.setWidth(layout.buttonWidth());
      // 1.20.1: AbstractWidget has no setHeight(int) (only setWidth). The height comes from
      // Button.builder(...).bounds(...) at construction time, which already used the same value.
      this.autoAdjustButton.render(graphics, mouseX, mouseY, delta);
   }

   private void renderFullscreenButton(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      if (this.fullscreenAction != null) {
         int buttonX = this.getX() + this.width - FULLSCREEN_BUTTON_SIZE - FULLSCREEN_BUTTON_PADDING;
         int buttonY = this.getY() + FULLSCREEN_BUTTON_PADDING;
         this.fullscreenButton.setX(buttonX);
         this.fullscreenButton.setY(buttonY);
         this.fullscreenButton.setWidth(FULLSCREEN_BUTTON_SIZE);
         // 1.20.1: AbstractWidget has no setHeight(int) (only setWidth). The height is set once at
         // construction via Button.builder(...).bounds(0, 0, FULLSCREEN_BUTTON_SIZE, FULLSCREEN_BUTTON_SIZE),
         // which already uses this exact value, so the call was redundant.
         this.fullscreenButton.render(graphics, mouseX, mouseY, delta);
      }
   }

   private TerrainPreviewWidget.InfoPanelLayout infoPanelLayout(TerrainPreview.PreviewInfo info) {
      int infoButtonX = this.getX() + MODE_BUTTON_PADDING;
      int infoButtonY = this.getY() + MODE_BUTTON_PADDING + MODE_BUTTON_HEIGHT + INFO_BUTTON_PADDING;
      int x = infoButtonX + INFO_BUTTON_SIZE + 8;
      int width = Math.min(INFO_PANEL_WIDTH, Math.max(190, this.getX() + this.width - x - 8));
      boolean blended = info != null && info.hasBlendedProviders();
      // 2026-10-05 performance fix: the panel box depends on nothing but the values above, yet a
      // fresh record was allocated twice per frame (renderInfoPanel + isMouseOverInfoPanel) plus
      // once per mouse event. The unused Font parameter is gone with it.
      if (this.cachedLayout != null
         && infoButtonX == this.cachedLayoutInfoButtonX
         && infoButtonY == this.cachedLayoutInfoButtonY
         && width == this.cachedLayoutWidth
         && blended == this.cachedLayoutBlended) {
         return this.cachedLayout;
      }

      int textLines = info == null ? 2 : 6 + (blended ? 1 : 0);
      int textHeight = INFO_TITLE_SPACING + (textLines - 1) * INFO_LINE_SPACING;
      int height = INFO_PANEL_PADDING + textHeight + INFO_SECTION_SPACING + 20 + INFO_PANEL_PADDING;
      int buttonWidth = 110;
      int y = infoButtonY;
      int buttonY = y + height - 20 - INFO_PANEL_PADDING;
      TerrainPreviewWidget.InfoPanelLayout layout = new TerrainPreviewWidget.InfoPanelLayout(x, y, width, height, x + width - INFO_PANEL_PADDING - buttonWidth, buttonY, buttonWidth);
      this.cachedLayoutInfoButtonX = infoButtonX;
      this.cachedLayoutInfoButtonY = infoButtonY;
      this.cachedLayoutWidth = width;
      this.cachedLayoutBlended = blended;
      this.cachedLayout = layout;
      return layout;
   }

   private boolean isMouseOverInfoPanel(double mouseX, double mouseY) {
      if (!this.infoPanelVisible) {
         return false;
      } else {
         // 2026-10-05 performance fix: reuses the cached layout instead of recomputing it.
         TerrainPreviewWidget.InfoPanelLayout layout = this.infoPanelLayout(this.preview.getInfo());
         return mouseX >= layout.x() && mouseX < layout.right() && mouseY >= layout.y() && mouseY < layout.bottom();
      }
   }

   /**
    * The rendered text of the info panel, already truncated to the panel width.
    *
    * <p>2026-10-05 performance fix - see {@link #cachedInfoText}. Recomputed only when
    * {@code (PreviewInfo, Font, contentWidth, language)} changes; since {@code PreviewInfo} is an
    * immutable record swapped on every preview rebuild, dragging a slider still updates the panel
    * on the very next frame.
    */
   private TerrainPreviewWidget.InfoPanelText infoPanelText(TerrainPreview.PreviewInfo info, Font font, int contentWidth) {
      String language = selectedLanguage();
      TerrainPreviewWidget.InfoPanelText cached = this.cachedInfoText;
      if (cached != null
         && info == this.cachedInfoSource
         && font == this.cachedInfoFont
         && contentWidth == this.cachedInfoContentWidth
         && language.equals(this.cachedInfoLanguage)) {
         return cached;
      }

      TerrainPreviewWidget.InfoPanelText text = new TerrainPreviewWidget.InfoPanelText(
         this.truncate(font, this.demSummary(info), contentWidth),
         this.truncate(font, this.resolutionSummary(info), contentWidth),
         info.hasBlendedProviders()
            ? this.truncate(font, INFO_BLENDED_LABEL.getString() + ": " + this.providerNames(info.blendedProviders()), contentWidth)
            : "",
         this.truncate(font, INFO_HIGHEST_LABEL.getString() + "：" + String.format(Locale.ROOT, "%.0f 米", info.maxElevationMeters()), contentWidth),
         INFO_MIN_Y_LABEL.getString(),
         Integer.toString(info.minSurfaceY()),
         INFO_MAX_Y_LABEL.getString(),
         Integer.toString(info.maxSurfaceY())
      );
      this.cachedInfoSource = info;
      this.cachedInfoFont = font;
      this.cachedInfoContentWidth = contentWidth;
      this.cachedInfoLanguage = language;
      this.cachedInfoText = text;
      return text;
   }

   /**
    * Part of the info panel cache key: a resource reload (F3+T) changes the selected language while
    * a screen stays open, and the cached strings would otherwise stay in the old language.
    */
   private static String selectedLanguage() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.getLanguageManager() == null) {
         return "";
      }

      String selected = minecraft.getLanguageManager().getSelected();
      return selected == null ? "" : selected;
   }

   private String demSummary(TerrainPreview.PreviewInfo info) {
      if (info.primaryProviders().isEmpty()) {
         return Component.translatable("tellus.preview.info.dem_unknown").getString();
      } else if (!info.hasMixedPrimaryProviders()) {
         return Component.translatable("tellus.preview.info.dem_main").getString() + ": " + info.mainProvider().provider().label().getString();
      } else {
         StringBuilder builder = new StringBuilder(Component.translatable("tellus.preview.info.dem_mix").getString()).append(": ");

         for (int i = 0; i < info.primaryProviders().size(); i++) {
            TerrainPreview.PreviewProviderShare share = info.primaryProviders().get(i);
            if (i > 0) {
               builder.append(", ");
            }

            builder.append(share.provider().label().getString())
               .append(" (")
               .append(String.format(Locale.ROOT, "%.0f%%", share.share() * 100.0))
               .append(")");
         }

         return builder.toString();
      }
   }

   private String providerNames(Iterable<TellusElevationSource.DemUsage> providers) {
      StringBuilder builder = new StringBuilder();

      for (TellusElevationSource.DemUsage provider : providers) {
         if (!builder.isEmpty()) {
            builder.append(", ");
         }

         builder.append(provider.label().getString());
      }

      return builder.toString();
   }

   private String resolutionSummary(TerrainPreview.PreviewInfo info) {
      String label = Component.translatable("tellus.preview.info.resolution").getString();
      if (info.primaryResolutions().isEmpty()) {
         return label + ": " + Component.translatable("tellus.preview.info.dem_unknown").getString();
      } else if (!info.hasMixedPrimaryResolutions()) {
         return label + ": " + this.formatResolution(info.mainResolution().resolutionMeters());
      } else {
         StringBuilder builder = new StringBuilder(label).append(": ");

         for (int i = 0; i < info.primaryResolutions().size(); i++) {
            TerrainPreview.PreviewResolutionShare share = info.primaryResolutions().get(i);
            if (i > 0) {
               builder.append(", ");
            }

            builder.append(this.formatResolution(share.resolutionMeters()))
               .append(" (")
               .append(String.format(Locale.ROOT, "%.0f%%", share.share() * 100.0))
               .append(")");
         }

         return builder.toString();
      }
   }

   private String formatResolution(double resolutionMeters) {
      if (!Double.isFinite(resolutionMeters) || resolutionMeters <= 0.0) {
         return Component.translatable("tellus.preview.info.dem_unknown").getString();
      } else if (Math.abs(resolutionMeters - Math.rint(resolutionMeters)) < 0.05) {
         return String.format(Locale.ROOT, "%.0f 米", resolutionMeters);
      } else {
         return String.format(Locale.ROOT, "%.1f 米", resolutionMeters);
      }
   }

   private String truncate(Font font, String text, int width) {
      if (font.width(text) <= width) {
         return text;
      } else {
         String truncated = font.plainSubstrByWidth(text, Math.max(0, width - font.width("...")));
         return truncated + "...";
      }
   }

   private void drawValueLine(GuiGraphics graphics, Font font, int x, int y, String label, String value, int valueColor, int width) {
      graphics.drawString(font, label, x, y, INFO_TEXT, false);
      int valueWidth = font.width(value);
      int valueX = Math.min(x + font.width(label) + 16, x + Math.max(0, width - valueWidth));
      graphics.drawString(font, value, valueX, y, valueColor, false);
   }

   @Override
   public void close() {
      if (this.ownsPreview) {
         this.preview.close();
      }
   }

   /**
    * The text of the info panel, fully resolved and already truncated to the panel width.
    *
    * <p>2026-10-05 performance fix: these lines used to be assembled from scratch on every frame -
    * eight {@code Component.translatable(...).getString()} lookups (each allocating a translatable
    * and re-resolving the language), several {@code StringBuilder}s, two {@code String.format}
    * calls and a {@code truncate} width probe per line. They now live here and are rebuilt only when
    * one of their inputs changes, see {@link #cachedInfoText}.
    *
    * @param blendedSummary only meaningful when the preview actually has blended providers
    */
   @Environment(EnvType.CLIENT)
   private record InfoPanelText(
      String demSummary,
      String resolutionSummary,
      String blendedSummary,
      String altitudeSummary,
      String minYLabel,
      String minYValue,
      String maxYLabel,
      String maxYValue
   ) {
   }

   @Environment(EnvType.CLIENT)
   private record InfoPanelLayout(int x, int y, int width, int height, int buttonX, int buttonY, int buttonWidth) {
      private int right() {
         return this.x + this.width;
      }

      private int bottom() {
         return this.y + this.height;
      }

      private int contentWidth() {
         return this.width - INFO_PANEL_PADDING * 2;
      }
   }

   @Environment(EnvType.CLIENT)
   public record ViewState(float rotationX, float rotationY, float zoom, TerrainPreviewWidget.RenderMode renderMode) {
      public ViewState(float rotationX, float rotationY, float zoom, TerrainPreviewWidget.RenderMode renderMode) {
         this.rotationX = rotationX;
         this.rotationY = rotationY;
         this.zoom = zoom;
         this.renderMode = Objects.requireNonNull(renderMode, "renderMode");
      }
   }

   @Environment(EnvType.CLIENT)
   public static enum RenderMode {
      FULL_DETAIL("全要素"),
      TERRAIN_ONLY("纯地形");

      private final Component buttonLabel;

      private RenderMode(String buttonLabel) {
         this.buttonLabel = Component.literal(buttonLabel);
      }

      private Component buttonLabel() {
         return this.buttonLabel;
      }

      private TerrainPreviewWidget.RenderMode toggle() {
         return this == FULL_DETAIL ? TERRAIN_ONLY : FULL_DETAIL;
      }
   }
}
