package com.yucareux.tellus.world.data.elevation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.tukaani.xz.SingleXZInputStream;

final class NorwayDtm1CoverageIndex {
   private static final String DEFAULT_RESOURCE_PATH = "/tellus/elevation/norway_dtm1_index.json.xz";
   private static final int LON_CELLS = 360;
   private static final int LAT_CELLS = 180;
   private final String resourcePath;
   private final ThreadLocal<NorwayDtm1CoverageIndex.LookupState> lookupState = ThreadLocal.withInitial(NorwayDtm1CoverageIndex.LookupState::new);
   private volatile NorwayDtm1CoverageIndex.Index index;

   static NorwayDtm1CoverageIndex create() {
      return new NorwayDtm1CoverageIndex(DEFAULT_RESOURCE_PATH);
   }

   private NorwayDtm1CoverageIndex(String resourcePath) {
      this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
   }

   boolean available() {
      return this.loadIndex().available;
   }

   NorwayDtm1CoverageIndex.TileReference findTile(double lat, double lon) {
      NorwayDtm1CoverageIndex.Index loaded = this.loadIndex();
      if (!loaded.available || Double.isNaN(lat) || Double.isNaN(lon)) {
         return null;
      } else {
         double clampedLat = Math.max(-90.0, Math.min(89.999999, lat));
         double clampedLon = Math.max(-180.0, Math.min(179.999999, lon));
         if (!this.containsCoverage(loaded, clampedLon, clampedLat)) {
            return null;
         } else {
            NorwayDtm1CoverageIndex.LookupState state = this.lookupState.get();
            if (state.lastPolygonIndex >= 0 && this.containsPolygon(loaded, state.lastPolygonIndex, clampedLon, clampedLat)) {
               return loaded.tileRefs[state.lastPolygonIndex];
            } else {
               int cellX = Math.max(0, Math.min(LON_CELLS - 1, (int)Math.floor(clampedLon) + 180));
               int cellY = Math.max(0, Math.min(LAT_CELLS - 1, (int)Math.floor(clampedLat) + 90));
               int cellIndex = cellY * LON_CELLS + cellX;
               int start = loaded.cellStarts[cellIndex];
               int count = loaded.cellCounts[cellIndex];

               for (int i = 0; i < count; i++) {
                  int polygonIndex = loaded.polygonRefs[start + i];
                  if (polygonIndex != state.lastPolygonIndex && this.containsPolygon(loaded, polygonIndex, clampedLon, clampedLat)) {
                     state.lastPolygonIndex = polygonIndex;
                     return loaded.tileRefs[polygonIndex];
                  }
               }

               state.lastPolygonIndex = -1;
               return null;
            }
         }
      }
   }

   private boolean containsCoverage(NorwayDtm1CoverageIndex.Index index, double lon, double lat) {
      if (lon < index.coverageMinLon || lon > index.coverageMaxLon || lat < index.coverageMinLat || lat > index.coverageMaxLat) {
         return false;
      } else {
         return this.pointInRing(index.coverageCoords, lon, lat);
      }
   }

   private boolean containsPolygon(NorwayDtm1CoverageIndex.Index index, int polygonIndex, double lon, double lat) {
      if (polygonIndex < 0 || polygonIndex >= index.tileRefs.length) {
         return false;
      } else if (lon < index.polygonMinLon[polygonIndex]
         || lon > index.polygonMaxLon[polygonIndex]
         || lat < index.polygonMinLat[polygonIndex]
         || lat > index.polygonMaxLat[polygonIndex]) {
         return false;
      } else {
         int coordStart = index.polygonCoordStart[polygonIndex];
         int coordCount = index.polygonCoordCount[polygonIndex];
         return this.pointInRing(index.coords, coordStart, coordCount, lon, lat);
      }
   }

   private boolean pointInRing(float[] coords, double lon, double lat) {
      return this.pointInRing(coords, 0, coords.length / 2, lon, lat);
   }

   private boolean pointInRing(float[] coords, int coordStart, int coordCount, double lon, double lat) {
      if (coordCount < 3) {
         return false;
      } else {
         int base = coordStart * 2;
         int prev = base + (coordCount - 1) * 2;
         double prevLon = coords[prev];
         double prevLat = coords[prev + 1];
         boolean inside = false;

         for (int i = 0; i < coordCount; i++) {
            int offset = base + i * 2;
            double currentLon = coords[offset];
            double currentLat = coords[offset + 1];
            boolean intersects = currentLat > lat != prevLat > lat;
            if (intersects) {
               double edgeLon = (prevLon - currentLon) * (lat - currentLat) / (prevLat - currentLat) + currentLon;
               if (lon < edgeLon) {
                  inside = !inside;
               }
            }

            prevLon = currentLon;
            prevLat = currentLat;
         }

         return inside;
      }
   }

   private NorwayDtm1CoverageIndex.Index loadIndex() {
      NorwayDtm1CoverageIndex.Index loaded = this.index;
      if (loaded != null) {
         return loaded;
      } else {
         synchronized(this) {
            loaded = this.index;
            if (loaded != null) {
               return loaded;
            } else {
               this.index = loaded = this.readIndex();
               return loaded;
            }
         }
      }
   }

   private NorwayDtm1CoverageIndex.Index readIndex() {
      try (InputStream raw = NorwayDtm1CoverageIndex.class.getResourceAsStream(this.resourcePath)) {
         if (raw == null) {
            Tellus.LOGGER.warn("Norway DTM1 coverage index resource is missing: {}.", this.resourcePath);
            return NorwayDtm1CoverageIndex.Index.unavailable();
         } else {
            try (SingleXZInputStream xz = new SingleXZInputStream(raw);
               InputStreamReader reader = new InputStreamReader(xz, StandardCharsets.UTF_8)) {
               JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
               JsonArray coverageBbox = root.getAsJsonArray("bbox");
               JsonArray coverageRing = root.getAsJsonArray("ring");
               JsonArray tiles = root.getAsJsonArray("tiles");
               if (coverageBbox == null || coverageBbox.size() != 4) {
                  throw new IOException("Invalid Norway DTM1 coverage bounding box");
               } else if (coverageRing == null || coverageRing.size() < 3) {
                  throw new IOException("Invalid Norway DTM1 coverage ring");
               } else if (tiles == null || tiles.isEmpty()) {
                  throw new IOException("Norway DTM1 index contains no tiles");
               } else {
                  List<NorwayDtm1CoverageIndex.TileReference> tileRefs = new ArrayList<>(tiles.size());
                  List<Float> minLon = new ArrayList<>(tiles.size());
                  List<Float> minLat = new ArrayList<>(tiles.size());
                  List<Float> maxLon = new ArrayList<>(tiles.size());
                  List<Float> maxLat = new ArrayList<>(tiles.size());
                  List<Integer> coordStart = new ArrayList<>(tiles.size());
                  List<Integer> coordCount = new ArrayList<>(tiles.size());
                  List<Float> coords = new ArrayList<>();
                  List<List<Integer>> cellRefs = new ArrayList<>(LON_CELLS * LAT_CELLS);

                  for (int i = 0; i < LON_CELLS * LAT_CELLS; i++) {
                     cellRefs.add(new ArrayList<>());
                  }

                  for (JsonElement tileElement : tiles) {
                     JsonObject tile = tileElement.getAsJsonObject();
                     NorwayDtm1CoverageIndex.TileReference tileRef = readTileReference(tile);
                     JsonArray bbox = tile.getAsJsonArray("bbox");
                     JsonArray ring = tile.getAsJsonArray("ring");
                     if (bbox == null || bbox.size() != 4) {
                        throw new IOException("Invalid Norway DTM1 bounding box: " + tileRef.id());
                     }

                     if (ring == null || ring.size() < 3) {
                        throw new IOException("Invalid Norway DTM1 ring: " + tileRef.id());
                     }

                     int polygonIndex = tileRefs.size();
                     float polygonMinLon = bbox.get(0).getAsFloat();
                     float polygonMinLat = bbox.get(1).getAsFloat();
                     float polygonMaxLon = bbox.get(2).getAsFloat();
                     float polygonMaxLat = bbox.get(3).getAsFloat();
                     int polygonCoordStart = coords.size() / 2;

                     for (JsonElement pointElement : ring) {
                        JsonArray point = pointElement.getAsJsonArray();
                        if (point == null || point.size() != 2) {
                           throw new IOException("Invalid Norway DTM1 ring vertex: " + tileRef.id());
                        }

                        coords.add(point.get(0).getAsFloat());
                        coords.add(point.get(1).getAsFloat());
                     }

                     tileRefs.add(tileRef);
                     minLon.add(polygonMinLon);
                     minLat.add(polygonMinLat);
                     maxLon.add(polygonMaxLon);
                     maxLat.add(polygonMaxLat);
                     coordStart.add(polygonCoordStart);
                     coordCount.add(ring.size());

                     int minCellLon = clampCellLon((int)Math.floor(polygonMinLon));
                     int maxCellLon = clampCellLon((int)Math.floor(polygonMaxLon));
                     int minCellLat = clampCellLat((int)Math.floor(polygonMinLat));
                     int maxCellLat = clampCellLat((int)Math.floor(polygonMaxLat));

                     for (int cellLat = minCellLat; cellLat <= maxCellLat; cellLat++) {
                        int row = cellLat * LON_CELLS;

                        for (int cellLon = minCellLon; cellLon <= maxCellLon; cellLon++) {
                           cellRefs.get(row + cellLon).add(polygonIndex);
                        }
                     }
                  }

                  int[] cellStarts = new int[LON_CELLS * LAT_CELLS];
                  int[] cellCounts = new int[LON_CELLS * LAT_CELLS];
                  int refCount = 0;

                  for (List<Integer> refsForCell : cellRefs) {
                     refCount += refsForCell.size();
                  }

                  int[] polygonRefs = new int[refCount];
                  int refCursor = 0;

                  for (int cellIndex = 0; cellIndex < cellRefs.size(); cellIndex++) {
                     List<Integer> refsForCell = cellRefs.get(cellIndex);
                     cellStarts[cellIndex] = refCursor;
                     cellCounts[cellIndex] = refsForCell.size();

                     for (int polygonIndex : refsForCell) {
                        polygonRefs[refCursor++] = polygonIndex;
                     }
                  }

                  return new NorwayDtm1CoverageIndex.Index(
                     true,
                     coverageBbox.get(0).getAsFloat(),
                     coverageBbox.get(1).getAsFloat(),
                     coverageBbox.get(2).getAsFloat(),
                     coverageBbox.get(3).getAsFloat(),
                     toFloatArray(coverageRing),
                     cellStarts,
                     cellCounts,
                     polygonRefs,
                     tileRefs.toArray(new NorwayDtm1CoverageIndex.TileReference[0]),
                     toFloatArray(minLon),
                     toFloatArray(minLat),
                     toFloatArray(maxLon),
                     toFloatArray(maxLat),
                     toIntArray(coordStart),
                     toIntArray(coordCount),
                     toFloatArray(coords)
                  );
               }
            }
         }
      } catch (Exception error) {
         Tellus.LOGGER.warn("Failed to load the Norway DTM1 coverage index.", error);
         return NorwayDtm1CoverageIndex.Index.unavailable();
      }
   }

   private static NorwayDtm1CoverageIndex.TileReference readTileReference(JsonObject object) throws IOException {
      return new NorwayDtm1CoverageIndex.TileReference(getRequiredString(object, "id"), getRequiredString(object, "url"));
   }

   private static int clampCellLon(int lonFloor) {
      return Math.max(0, Math.min(LON_CELLS - 1, lonFloor + 180));
   }

   private static int clampCellLat(int latFloor) {
      return Math.max(0, Math.min(LAT_CELLS - 1, latFloor + 90));
   }

   private static String getRequiredString(JsonObject object, String key) throws IOException {
      JsonElement element = object.get(key);
      if (element != null && !element.isJsonNull()) {
         String value = element.getAsString();
         if (!value.isBlank()) {
            return value;
         }
      }

      throw new IOException("Missing required field: " + key);
   }

   private static float[] toFloatArray(List<Float> values) {
      float[] result = new float[values.size()];

      for (int i = 0; i < values.size(); i++) {
         result[i] = values.get(i);
      }

      return result;
   }

   private static float[] toFloatArray(JsonArray ring) throws IOException {
      float[] result = new float[ring.size() * 2];

      for (int i = 0; i < ring.size(); i++) {
         JsonArray point = ring.get(i).getAsJsonArray();
         if (point == null || point.size() != 2) {
            throw new IOException("Invalid Norway DTM1 ring vertex");
         }

         result[i * 2] = point.get(0).getAsFloat();
         result[i * 2 + 1] = point.get(1).getAsFloat();
      }

      return result;
   }

   private static int[] toIntArray(List<Integer> values) {
      int[] result = new int[values.size()];

      for (int i = 0; i < values.size(); i++) {
         result[i] = values.get(i);
      }

      return result;
   }

   static record TileReference(String id, String url) {
      TileReference {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(url, "url");
      }

      String cacheDirectory() {
         return this.id.toLowerCase(Locale.ROOT).replace(".tif", "").replaceAll("[^a-z0-9._-]", "_");
      }
   }

   private static final class LookupState {
      private int lastPolygonIndex = -1;
   }

   private record Index(
      boolean available,
      float coverageMinLon,
      float coverageMinLat,
      float coverageMaxLon,
      float coverageMaxLat,
      float[] coverageCoords,
      int[] cellStarts,
      int[] cellCounts,
      int[] polygonRefs,
      NorwayDtm1CoverageIndex.TileReference[] tileRefs,
      float[] polygonMinLon,
      float[] polygonMinLat,
      float[] polygonMaxLon,
      float[] polygonMaxLat,
      int[] polygonCoordStart,
      int[] polygonCoordCount,
      float[] coords
   ) {
      private static NorwayDtm1CoverageIndex.Index unavailable() {
         return new NorwayDtm1CoverageIndex.Index(
            false,
            0.0F,
            0.0F,
            0.0F,
            0.0F,
            new float[0],
            new int[LON_CELLS * LAT_CELLS],
            new int[LON_CELLS * LAT_CELLS],
            new int[0],
            new NorwayDtm1CoverageIndex.TileReference[0],
            new float[0],
            new float[0],
            new float[0],
            new float[0],
            new int[0],
            new int[0],
            new float[0]
         );
      }
   }
}
