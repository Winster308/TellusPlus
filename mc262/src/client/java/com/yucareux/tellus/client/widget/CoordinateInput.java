package com.yucareux.tellus.client.widget;

import com.yucareux.tellus.client.screen.CoordinateFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import java.util.List;

/**
 * The "exact coordinates" input bar: two input boxes plus a "Locate" button.
 *
 * <p>The upstream map screens (spawn point setup / the teleport map opened via {@code /tellus map})
 * can only drag the marker or search by place name - there is no way to hit an exact coordinate.
 * This widget closes that gap: pasting {@code 31.5N / 121.4E}, {@code 31.5, 121.4} or the
 * degrees-minutes-seconds form {@code 31d30m00sN} all work, see {@link CoordinateFormat} for the
 * parsing rules.
 *
 * <p>It draws no background and never steals focus: {@link #widgets()} is handed to
 * {@code Screen#addRenderableWidget} so vanilla handles input focus and the keyboard; once the
 * coordinates are parsed they are passed back to the host screen through the {@link OnApply} callback.
 */
@Environment(EnvType.CLIENT)
public final class CoordinateInput {

   /** Callback fired on a successful parse (latitude, longitude). */
   public interface OnApply {
      void apply(double latitude, double longitude);
   }

   private static final int FIELD_HEIGHT = 18;
   private static final int LABEL_HEIGHT = 10;
   private static final int BUTTON_WIDTH = 52;
   private static final int GAP = 4;

   private final EditBox latitudeField;
   private final EditBox longitudeField;
   private final Button applyButton;
   private final OnApply onApply;
   private final int x;
   private final int y;
   private final int width;

   private String error;
   private double markerLatitude = Double.NaN;
   private double markerLongitude = Double.NaN;

   public CoordinateInput(Font font, int x, int y, int width, OnApply onApply) {
      this.onApply = onApply;
      this.x = x;
      this.y = y;
      this.width = width;

      int fieldWidth = Math.max(64, (width - BUTTON_WIDTH - GAP * 2) / 2);
      this.latitudeField = new EditBox(font, x, y + LABEL_HEIGHT, fieldWidth, FIELD_HEIGHT,
            Component.translatable("tellus.coord.latitude"));
      this.latitudeField.setHint(Component.translatable("tellus.coord.hint.lat"));
      this.latitudeField.setMaxLength(24);

      this.longitudeField = new EditBox(font, x + fieldWidth + GAP, y + LABEL_HEIGHT, fieldWidth, FIELD_HEIGHT,
            Component.translatable("tellus.coord.longitude"));
      this.longitudeField.setHint(Component.translatable("tellus.coord.hint.lon"));
      this.longitudeField.setMaxLength(24);

      this.applyButton = Button.builder(Component.translatable("tellus.coord.apply"), button -> this.apply())
            .bounds(x + (fieldWidth + GAP) * 2, y + LABEL_HEIGHT, BUTTON_WIDTH, FIELD_HEIGHT)
            .build();
   }

   /** The interactive widgets to register with the host Screen (order: latitude box, longitude box, locate button). */
   public List<AbstractWidget> widgets() {
      return List.of(this.latitudeField, this.longitudeField, this.applyButton);
   }

   /** Hit area of the whole control (label row and status row included): used to intercept map dragging/zooming. */
   public boolean isMouseOver(double mouseX, double mouseY) {
      return mouseX >= this.x && mouseX <= this.x + this.width
            && mouseY >= this.y && mouseY <= this.y + LABEL_HEIGHT + FIELD_HEIGHT + 12;
   }

   /** Writes the current marker position back into the input boxes (called when the screen opens, to allow fine tuning on top). */
   public void setMarker(double latitude, double longitude) {
      this.markerLatitude = latitude;
      this.markerLongitude = longitude;
      this.error = null;
      this.latitudeField.setValue(CoordinateFormat.format(latitude));
      this.longitudeField.setValue(CoordinateFormat.format(longitude));
   }

   /** Display update only (called when the user drags the marker; does not overwrite what is being typed). */
   public void markMoved(double latitude, double longitude) {
      this.markerLatitude = latitude;
      this.markerLongitude = longitude;
   }

   public void focus() {
      this.latitudeField.setFocused(true);
   }

   /** Parses and fires the callback; on failure the error is shown on the status row. */
   public void apply() {
      double[] parsed = CoordinateFormat.parsePair(this.latitudeField.getValue(), this.longitudeField.getValue());
      if (parsed == null) {
         this.error = Component.translatable("tellus.coord.invalid").getString();
         return;
      }
      this.error = null;
      this.setMarker(parsed[0], parsed[1]);
      this.onApply.apply(parsed[0], parsed[1]);
   }

   public void render(GuiGraphicsExtractor graphics, Font font) {
      graphics.text(font, Component.translatable("tellus.coord.latitude"), this.x, this.y, -1, false);
      graphics.text(font, Component.translatable("tellus.coord.longitude"),
            this.x + this.latitudeField.getWidth() + GAP, this.y, -1, false);

      int statusY = this.y + LABEL_HEIGHT + FIELD_HEIGHT + 2;
      if (this.error != null) {
         graphics.text(font, Component.literal(this.error).getString(), this.x, statusY, 0xFF5555, false);
      } else if (Double.isFinite(this.markerLatitude)) {
         String current = Component.translatable("tellus.coord.current").getString() + " "
               + CoordinateFormat.formatPair(this.markerLatitude, this.markerLongitude);
         graphics.text(font, current, this.x, statusY, 0xAAAAAA, false);
      }
   }
}
