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

final class AhnCoverageIndex {
   private static final String DEFAULT_RESOURCE_PATH = "/tellus/elevation/ahn_dtm05m_index.json.xz";
   private static final double CELL_SIZE_METERS = 10000.0;
   private final String resourcePath;
   private final ThreadLocal<AhnCoverageIndex.LookupState> lookupState = ThreadLocal.withInitial(AhnCoverageIndex.LookupState::new);
   private volatile AhnCoverageIndex.Index index;

   static AhnCoverageIndex create() {
      return new AhnCoverageIndex(DEFAULT_RESOURCE_PATH);
   }

   private AhnCoverageIndex(String resourcePath) {
      this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
   }

   boolean available() {
      return this.loadIndex().available;
   }

   AhnCoverageIndex.TileReference find(double x, double y) {
      AhnCoverageIndex.Index loaded = this.loadIndex();
      if (!loaded.available || Double.isNaN(x) || Double.isNaN(y)) {
         return null;
      } else if (x < loaded.minX || x > loaded.maxX || y < loaded.minY || y > loaded.maxY) {
         return null;
      } else {
         AhnCoverageIndex.LookupState state = this.lookupState.get();
         if (state.lastPolygonIndex >= 0 && this.containsPolygon(loaded, state.lastPolygonIndex, x, y)) {
            return loaded.tileRefs[state.lastPolygonIndex];
         } else {
            int cellX = Math.max(0, Math.min(loaded.cellsX - 1, (int)Math.floor((x - loaded.minX) / CELL_SIZE_METERS)));
            int cellY = Math.max(0, Math.min(loaded.cellsY - 1, (int)Math.floor((y - loaded.minY) / CELL_SIZE_METERS)));
            int cellIndex = cellY * loaded.cellsX + cellX;
            int start = loaded.cellStarts[cellIndex];
            int count = loaded.cellCounts[cellIndex];

            for (int i = 0; i < count; i++) {
               int polygonIndex = loaded.polygonRefs[start + i];
               if (polygonIndex != state.lastPolygonIndex && this.containsPolygon(loaded, polygonIndex, x, y)) {
                  state.lastPolygonIndex = polygonIndex;
                  return loaded.tileRefs[polygonIndex];
               }
            }

            state.lastPolygonIndex = -1;
            return null;
         }
      }
   }

   private boolean containsPolygon(AhnCoverageIndex.Index index, int polygonIndex, double x, double y) {
      if (polygonIndex < 0 || polygonIndex >= index.tileRefs.length) {
         return false;
      } else if (x < index.polygonMinX[polygonIndex]
         || x > index.polygonMaxX[polygonIndex]
         || y < index.polygonMinY[polygonIndex]
         || y > index.polygonMaxY[polygonIndex]) {
         return false;
      } else {
         int coordStart = index.polygonCoordStart[polygonIndex];
         int coordCount = index.polygonCoordCount[polygonIndex];
         return this.pointInRing(index.coords, coordStart, coordCount, x, y);
      }
   }

   private boolean pointInRing(float[] coords, int coordStart, int coordCount, double x, double y) {
      if (coordCount < 3) {
         return false;
      } else {
         int base = coordStart * 2;
         int prev = base + (coordCount - 1) * 2;
         double prevX = coords[prev];
         double prevY = coords[prev + 1];
         boolean inside = false;

         for (int i = 0; i < coordCount; i++) {
            int offset = base + i * 2;
            double currentX = coords[offset];
            double currentY = coords[offset + 1];
            boolean intersects = currentY > y != prevY > y;
            if (intersects) {
               double edgeX = (prevX - currentX) * (y - currentY) / (prevY - currentY) + currentX;
               if (x < edgeX) {
                  inside = !inside;
               }
            }

            prevX = currentX;
            prevY = currentY;
         }

         return inside;
      }
   }

   private AhnCoverageIndex.Index loadIndex() {
      AhnCoverageIndex.Index loaded = this.index;
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

   private AhnCoverageIndex.Index readIndex() {
      try (InputStream raw = AhnCoverageIndex.class.getResourceAsStream(this.resourcePath)) {
         if (raw == null) {
            Tellus.LOGGER.warn("AHN coverage index resource is missing: {}.", this.resourcePath);
            return AhnCoverageIndex.Index.unavailable();
         } else {
            try (SingleXZInputStream xz = new SingleXZInputStream(raw);
               InputStreamReader reader = new InputStreamReader(xz, StandardCharsets.UTF_8)) {
               JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
               JsonArray tiles = root.getAsJsonArray("tiles");
               if (tiles == null || tiles.isEmpty()) {
                  throw new IOException("The AHN coverage index contains no tiles");
               } else {
                  List<AhnCoverageIndex.TileReference> tileRefs = new ArrayList<>(tiles.size());
                  List<Float> minX = new ArrayList<>(tiles.size());
                  List<Float> minY = new ArrayList<>(tiles.size());
                  List<Float> maxX = new ArrayList<>(tiles.size());
                  List<Float> maxY = new ArrayList<>(tiles.size());
                  List<Integer> coordStart = new ArrayList<>(tiles.size());
                  List<Integer> coordCount = new ArrayList<>(tiles.size());
                  List<Float> coords = new ArrayList<>();
                  float globalMinX = Float.POSITIVE_INFINITY;
                  float globalMinY = Float.POSITIVE_INFINITY;
                  float globalMaxX = Float.NEGATIVE_INFINITY;
                  float globalMaxY = Float.NEGATIVE_INFINITY;

                  for (JsonElement tileElement : tiles) {
                     JsonObject tile = tileElement.getAsJsonObject();
                     AhnCoverageIndex.TileReference tileRef = readTileReference(tile);
                     JsonArray bbox = tile.getAsJsonArray("bbox");
                     JsonArray ring = tile.getAsJsonArray("ring");
                     if (bbox == null || bbox.size() != 4) {
                        throw new IOException("Invalid AHN bounding box: " + tileRef.id());
                     }

                     if (ring == null || ring.size() < 3) {
                        throw new IOException("Invalid AHN ring: " + tileRef.id());
                     }

                     float polygonMinX = bbox.get(0).getAsFloat();
                     float polygonMinY = bbox.get(1).getAsFloat();
                     float polygonMaxX = bbox.get(2).getAsFloat();
                     float polygonMaxY = bbox.get(3).getAsFloat();
                     globalMinX = Math.min(globalMinX, polygonMinX);
                     globalMinY = Math.min(globalMinY, polygonMinY);
                     globalMaxX = Math.max(globalMaxX, polygonMaxX);
                     globalMaxY = Math.max(globalMaxY, polygonMaxY);
                     int polygonCoordStart = coords.size() / 2;

                     for (JsonElement pointElement : ring) {
                        JsonArray point = pointElement.getAsJsonArray();
                        if (point == null || point.size() != 2) {
                           throw new IOException("Invalid AHN ring vertex: " + tileRef.id());
                        }

                        coords.add(point.get(0).getAsFloat());
                        coords.add(point.get(1).getAsFloat());
                     }

                     tileRefs.add(tileRef);
                     minX.add(polygonMinX);
                     minY.add(polygonMinY);
                     maxX.add(polygonMaxX);
                     maxY.add(polygonMaxY);
                     coordStart.add(polygonCoordStart);
                     coordCount.add(ring.size());
                  }

                  int cellsX = Math.max(1, (int)Math.ceil((globalMaxX - globalMinX) / CELL_SIZE_METERS));
                  int cellsY = Math.max(1, (int)Math.ceil((globalMaxY - globalMinY) / CELL_SIZE_METERS));
                  List<List<Integer>> cellRefs = new ArrayList<>(cellsX * cellsY);

                  for (int i = 0; i < cellsX * cellsY; i++) {
                     cellRefs.add(new ArrayList<>());
                  }

                  for (int polygonIndex = 0; polygonIndex < tileRefs.size(); polygonIndex++) {
                     int minCellX = clampCell((int)Math.floor((minX.get(polygonIndex) - globalMinX) / CELL_SIZE_METERS), cellsX);
                     int maxCellX = clampCell((int)Math.floor((maxX.get(polygonIndex) - globalMinX) / CELL_SIZE_METERS), cellsX);
                     int minCellY = clampCell((int)Math.floor((minY.get(polygonIndex) - globalMinY) / CELL_SIZE_METERS), cellsY);
                     int maxCellY = clampCell((int)Math.floor((maxY.get(polygonIndex) - globalMinY) / CELL_SIZE_METERS), cellsY);

                     for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                        int row = cellY * cellsX;

                        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                           cellRefs.get(row + cellX).add(polygonIndex);
                        }
                     }
                  }

                  int[] cellStarts = new int[cellsX * cellsY];
                  int[] cellCounts = new int[cellsX * cellsY];
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

                  return new AhnCoverageIndex.Index(
                     true,
                     globalMinX,
                     globalMinY,
                     globalMaxX,
                     globalMaxY,
                     cellsX,
                     cellsY,
                     cellStarts,
                     cellCounts,
                     polygonRefs,
                     tileRefs.toArray(new AhnCoverageIndex.TileReference[0]),
                     toFloatArray(minX),
                     toFloatArray(minY),
                     toFloatArray(maxX),
                     toFloatArray(maxY),
                     toIntArray(coordStart),
                     toIntArray(coordCount),
                     toFloatArray(coords)
                  );
               }
            }
         }
      } catch (Exception error) {
         Tellus.LOGGER.warn("Failed to load the AHN coverage index.", error);
         return AhnCoverageIndex.Index.unavailable();
      }
   }

   private static int clampCell(int value, int size) {
      return Math.max(0, Math.min(size - 1, value));
   }

   private static AhnCoverageIndex.TileReference readTileReference(JsonObject object) throws IOException {
      return new AhnCoverageIndex.TileReference(getRequiredString(object, "id"), getRequiredString(object, "url"));
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
      float minX,
      float minY,
      float maxX,
      float maxY,
      int cellsX,
      int cellsY,
      int[] cellStarts,
      int[] cellCounts,
      int[] polygonRefs,
      AhnCoverageIndex.TileReference[] tileRefs,
      float[] polygonMinX,
      float[] polygonMinY,
      float[] polygonMaxX,
      float[] polygonMaxY,
      int[] polygonCoordStart,
      int[] polygonCoordCount,
      float[] coords
   ) {
      private static AhnCoverageIndex.Index unavailable() {
         return new AhnCoverageIndex.Index(
            false,
            0.0F,
            0.0F,
            0.0F,
            0.0F,
            1,
            1,
            new int[1],
            new int[1],
            new int[0],
            new AhnCoverageIndex.TileReference[0],
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
