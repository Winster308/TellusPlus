package com.yucareux.tellus.client.widget;

import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.narration.NarratableEntry;

@Environment(EnvType.CLIENT)
public class CustomizationList extends ContainerObjectSelectionList<CustomizationList.Entry> {
   public CustomizationList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
      // 1.20.1: ContainerObjectSelectionList takes a 6th int (the footer/header height slot);
      // the 1.21.9 5-arg form does not exist. Passing itemHeight keeps the list geometry identical.
      super(minecraft, width, height, y, itemHeight, itemHeight);
      this.centerListVertically = false;
   }

   public void clear() {
      this.clearEntries();
   }

   public void addWidget(AbstractWidget widget) {
      this.addEntry(new CustomizationList.Entry(widget));
   }

   /**
    * 1.20.1: {@code AbstractSelectionList.getRowWidth()} is {@code public}, so an override may not
    * narrow it to {@code protected} (that is a compile error). The 1.21.9 tree had it protected.
    */
   @Override
   public int getRowWidth() {
      return this.width - 20;
   }

   // 1.20.1 has no AbstractWidget#getX on the list and no scrollBarX(); the scrollbar
   // position hook is getScrollbarPosition(), and the list's left edge is getRowLeft().
   protected int getScrollbarPosition() {
      return this.getRowLeft() + this.width - 6;
   }

   // 1.20.1 hook names: renderBackground(GuiGraphics) / renderDecorations; both were stubbed out
   // so the list draws no extra background or separators (same intent as the 1.21.9 overrides).
   protected void renderBackground(GuiGraphics graphics) {
   }

   protected void renderDecorations(GuiGraphics graphics, int mouseX, int mouseY) {
   }

   @Environment(EnvType.CLIENT)
   public static class Entry extends net.minecraft.client.gui.components.ContainerObjectSelectionList.Entry<CustomizationList.Entry> {
      private final AbstractWidget widget;

      public Entry(AbstractWidget widget) {
         this.widget = Objects.requireNonNull(widget, "widget");
      }

      
      public List<? extends GuiEventListener> children() {
         return Objects.requireNonNull(List.of(this.widget), "children");
      }

      
      public List<? extends NarratableEntry> narratables() {
         return Objects.requireNonNull(List.of(this.widget), "narratables");
      }

      /**
       * 1.20.1: Entry#render is the 10-arg form, and the per-row geometry comes from the arguments
       * (left/top/width/height) - getContentX()/getContentY()/getContentWidth()/getContentHeight()
       * do not exist. Set the widget from those so the behaviour matches the 1.21.9 version.
       */
      public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float delta) {
         this.widget.setX(left);
         this.widget.setY(top);
         this.widget.setWidth(width);
         // 1.20.1: AbstractWidget exposes no setHeight(int), and its `height` field is protected --
         // reachable from a subclass of AbstractWidget, but not from here (this class extends
         // AbstractSelectionList.Entry, a different hierarchy), so the assignment cannot be written at
         // all. It is also redundant: this list is constructed with itemHeight 20, so the `height`
         // argument above is always 20, and every widget handed to addWidget() is built with
         // `.bounds(0, 0, <w>, 20)` -- the two already agree. Dropping it preserves the geometry exactly.
         this.widget.render(graphics, mouseX, mouseY, delta);
      }
   }
}
