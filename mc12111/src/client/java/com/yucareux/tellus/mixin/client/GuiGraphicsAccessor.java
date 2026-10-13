package com.yucareux.tellus.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin({GuiGraphics.class})
public interface GuiGraphicsAccessor {
   @Accessor("guiRenderState")
   GuiRenderState tellus$getGuiRenderState();
}
