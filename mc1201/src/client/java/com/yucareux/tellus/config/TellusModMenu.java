package com.yucareux.tellus.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.yucareux.tellus.client.screen.TellusManageScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod Menu entry point: hooks {@link TellusManageScreen} into the "Config" button of the mod list.
 *
 * <p>Note that Mod Menu instantiates the {@code modmenu} entry point declared in
 * {@code fabric.mod.json} <b>as a {@link ModMenuApi} instance</b> (it must have a no-arg
 * constructor), so the entry point has to be this class and must not point at the screen class directly.
 *
 * <p>When Mod Menu is not installed this class is never loaded (Fabric entry points are lazy), so it is an
 * optional dependency: without it there is simply no GUI, and commands plus map features keep working.
 */
@Environment(EnvType.CLIENT)
public final class TellusModMenu implements ModMenuApi {

   private static final Logger LOGGER = LoggerFactory.getLogger("tellusplus/modmenu");

   private static final java.util.concurrent.atomic.AtomicBoolean LOGGED =
         new java.util.concurrent.atomic.AtomicBoolean();

   @Override
   public ConfigScreenFactory<?> getModConfigScreenFactory() {
      // Called by Mod Menu and may fire repeatedly: log once only, never write to disk on a hot path
      if (LOGGED.compareAndSet(false, true)) {
         LOGGER.info("TellusCN: manage screen registered with Mod Menu (data sources / celestial bodies / map themes / cache usage / region delete)");
      }
      return TellusManageScreen::new;
   }
}
