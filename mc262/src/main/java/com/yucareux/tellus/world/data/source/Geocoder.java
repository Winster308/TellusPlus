package com.yucareux.tellus.world.data.source;

import java.io.IOException;

public interface Geocoder {
   double[] get(String var1) throws IOException;

   Geocoder.Suggestion[] suggest(String var1) throws IOException;

   public record Suggestion(String displayName, double latitude, double longitude) {
   }
}
