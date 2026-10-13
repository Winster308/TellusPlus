package com.yucareux.tellus.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin({GuiGraphicsExtractor.class})
public interface GuiGraphicsAccessor {
   @Accessor("guiRenderState")
   GuiRenderState tellus$getGuiRenderState();
}
