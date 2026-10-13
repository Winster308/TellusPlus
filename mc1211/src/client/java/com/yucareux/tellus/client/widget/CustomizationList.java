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
      super(minecraft, width, height, y, itemHeight);
      this.centerListVertically = false;
   }

   public void clear() {
      this.clearEntries();
   }

   public void addWidget(AbstractWidget widget) {
      this.addEntry(new CustomizationList.Entry(widget));
   }

   public int getRowWidth() {
      return this.width - 20;
   }

   protected int scrollBarX() {
      return this.getX() + this.width - 6;
   }

   protected void renderListBackground(GuiGraphics graphics) {
   }

   protected void renderListSeparators(GuiGraphics graphics) {
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

      // 1.21.1 PORT: 1.21.9 gave entries a renderContent(GuiGraphics, mouseX, mouseY, hovered, delta)
      // plus getContentX/Y/Width/Height accessors on the base class. Neither exists in 1.21.1; the
      // abstract method there is the 10-argument render(...), which takes the row geometry directly.
      // The parameter order was read out of the real 1.21.1 bytecode rather than guessed:
      // AbstractSelectionList.renderListItems ->
      //   renderItem(guiGraphics, mouseX, mouseY, partialTick, index, rowLeft, rowTop, rowWidth, height)
      // and AbstractSelectionList.renderItem -> Entry.render(guiGraphics, index, rowTop, rowLeft, rowWidth, height, mouseX, mouseY, hovered, partialTick)
      // (javap of the 1.21.1 merged jar: the call pushes locals 5,7,6,8,9,2,3, where 5=index,
      //  6=rowLeft, 7=rowTop, 8=rowWidth, 9=height).
      @Override
      public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float delta) {
         this.widget.setX(left);
         this.widget.setY(top);
         this.widget.setWidth(width);
         this.widget.setHeight(height);
         this.widget.render(graphics, mouseX, mouseY, delta);
      }
   }
}
