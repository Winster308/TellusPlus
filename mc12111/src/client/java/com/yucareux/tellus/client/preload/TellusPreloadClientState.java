package com.yucareux.tellus.client.preload;

import com.yucareux.tellus.preload.PreloadLayers;
import com.yucareux.tellus.preload.TellusPreloadRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side snapshot of the "already downloaded regions".
 *
 * <p>The data comes from the {@code TellusPreloadStatePayload} the server pushes and is stored
 * directly in the in memory copy of {@link TellusPreloadRegistry}, so the client and the map
 * components read from the same place instead of each keeping their own copy. A few convenience
 * methods for the screens are provided here as well.
 */
public final class TellusPreloadClientState {

   private TellusPreloadClientState() {
   }

   public static void acceptFromServer(String json) {
      TellusPreloadRegistry.replaceFromJson(json);
   }

   /** The downloaded region rectangles (latitude/longitude bounding boxes). */
   public static List<TellusPreloadRegistry.Region> regions() {
      return TellusPreloadRegistry.regions();
   }

   /** Total area (km2). */
   public static double downloadedAreaKm2() {
      return TellusPreloadRegistry.downloadedAreaKm2();
   }

   /** Whether the given rectangle is already downloaded (used by the screen to say "no need to download it again"). */
   public static boolean alreadyDownloaded(double minLat, double minLon, double maxLat, double maxLon,
                                           java.util.Set<String> layers) {
      return TellusPreloadRegistry.covers(minLat, minLon, maxLat, maxLon, layers);
   }

   /** Which layers the downloaded regions cover (used to build the hint text). */
   public static List<String> downloadedLayerLabels() {
      List<String> labels = new ArrayList<>();
      for (String layer : PreloadLayers.ALL) {
         boolean present = false;
         for (TellusPreloadRegistry.Region region : regions()) {
            if (region.layers().contains(layer)) {
               present = true;
               break;
            }
         }
         if (present) {
            // label() now returns a translation key, resolve it here in the client language (real i18n)
            labels.add(net.minecraft.client.resources.language.I18n.get(PreloadLayers.label(layer)));
         }
      }
      return labels;
   }
}
