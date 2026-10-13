package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.LoadingTerrainScreenTiming;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({Minecraft.class})
public abstract class MinecraftScreenMixin {
   // 26.2 moved the current screen off Minecraft onto Gui and renamed the setter:
   // javap shows Minecraft has neither a `screen` field nor `setScreen(Screen)`,
   // only Gui#screen() and Minecraft#setScreenAndShow(Screen).
   @Shadow
   @Final
   public Gui gui;

   @Inject(
      method = {"setScreenAndShow"},
      at = {@At("HEAD")}
   )
   private void tellus$trackLoadingTerrainScreen(Screen newScreen, CallbackInfo ci) {
      LoadingTerrainScreenTiming.onScreenChange(this.gui.screen(), newScreen);
   }
}
