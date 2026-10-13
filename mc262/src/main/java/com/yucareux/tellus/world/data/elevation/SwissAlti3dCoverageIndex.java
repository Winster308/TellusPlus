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

final class SwissAlti3dCoverageIndex {
   private static final String DEFAULT_RESOURCE_PATH = "/tellus/elevation/swissalti3d_index.json.xz";
   private static final double CELL_SIZE_METERS = 10000.0;
   private final String resourcePath;
   private final ThreadLocal<SwissAlti3dCoverageIndex.LookupState> lookupState = ThreadLocal.withInitial(SwissAlti3dCoverageIndex.LookupState::new);
   private volatile SwissAlti3dCoverageIndex.Index index;

   static SwissAlti3dCoverageIndex create() {
      return new SwissAlti3dCoverageIndex(DEFAULT_RESOURCE_PATH);
   }

   private SwissAlti3dCoverageIndex(String resourcePath) {
      this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
   }

   boolean available() {
      return this.loadIndex().available;
   }

   SwissAlti3dCoverageIndex.TileReference find(double x, double y) {
      SwissAlti3dCoverageIndex.Index loaded = this.loadIndex();
      if (!loaded.available || Double.isNaN(x) || Double.isNaN(y)) {
         return null;
      } else if (x < loaded.minX || x > loaded.maxX || y < loaded.minY || y > loaded.maxY) {
         return null;
      } else {
         SwissAlti3dCoverageIndex.LookupState state = this.lookupState.get();
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

   private boolean containsPolygon(SwissAlti3dCoverageIndex.Index index, int polygonIndex, double x, double y) {
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

   private SwissAlti3dCoverageIndex.Index loadIndex() {
      SwissAlti3dCoverageIndex.Index loaded = this.index;
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

   private SwissAlti3dCoverageIndex.Index readIndex() {
      try (InputStream raw = SwissAlti3dCoverageIndex.class.getResourceAsStream(this.resourcePath)) {
         if (raw == null) {
            Tellus.LOGGER.warn("swissALTI3D coverage index resource is missing: {}.", this.resourcePath);
            return SwissAlti3dCoverageIndex.Index.unavailable();
         } else {
            try (SingleXZInputStream xz = new SingleXZInputStream(raw);
               InputStreamReader reader = new InputStreamReader(xz, StandardCharsets.UTF_8)) {
               JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
               JsonArray tiles = root.getAsJsonArray("tiles");
               if (tiles == null || tiles.isEmpty()) {
                  throw new IOException("The swissALTI3D coverage index contains no tiles");
               } else {
                  List<SwissAlti3dCoverageIndex.TileReference> tileRefs = new ArrayList<>(tiles.size());
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
                     SwissAlti3dCoverageIndex.TileReference tileRef = readTileReference(tile);
                     if (!tileRef.hasAnyAsset()) {
                        throw new IOException("swissALTI3D tile is missing all of its resources: " + tileRef.id());
                     }

                     JsonArray bbox = tile.getAsJsonArray("bbox");
                     JsonArray ring = tile.getAsJsonArray("ring");
                     if (bbox == null || bbox.size() != 4) {
                        throw new IOException("Invalid swissALTI3D bounding box: " + tileRef.id());
                     }

                     if (ring == null || ring.size() < 3) {
                        throw new IOException("Invalid swissALTI3D ring: " + tileRef.id());
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
                           throw new IOException("Invalid swissALTI3D ring vertex: " + tileRef.id());
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

                  return new SwissAlti3dCoverageIndex.Index(
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
                     tileRefs.toArray(new SwissAlti3dCoverageIndex.TileReference[0]),
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
         Tellus.LOGGER.warn("Failed to load the swissALTI3D coverage index.", error);
         return SwissAlti3dCoverageIndex.Index.unavailable();
      }
   }

   private static int clampCell(int value, int size) {
      return Math.max(0, Math.min(size - 1, value));
   }

   private static SwissAlti3dCoverageIndex.TileReference readTileReference(JsonObject object) throws IOException {
      String id = getRequiredString(object, "id");
      String url05m = getOptionalString(object, "url05m");
      String url2m = getOptionalString(object, "url2m");
      return new SwissAlti3dCoverageIndex.TileReference(id, url05m, url2m);
   }

   private static String getRequiredString(JsonObject object, String key) throws IOException {
      String value = getOptionalString(object, key);
      if (value != null) {
         return value;
      } else {
         throw new IOException("Missing required field: " + key);
      }
   }

   private static String getOptionalString(JsonObject object, String key) {
      JsonElement element = object.get(key);
      if (element != null && !element.isJsonNull()) {
         String value = element.getAsString();
         if (!value.isBlank()) {
            return value;
         }
      }

      return null;
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

   static record TileReference(String id, String url05m, String url2m) {
      TileReference {
         Objects.requireNonNull(id, "id");
      }

      boolean hasAnyAsset() {
         return this.url05m != null || this.url2m != null;
      }

      SwissAlti3dCoverageIndex.AssetReference preferredAsset(boolean prefer05m) {
         if (prefer05m) {
            if (this.url05m != null) {
               return new SwissAlti3dCoverageIndex.AssetReference(this.id + "_05m", this.url05m);
            } else if (this.url2m != null) {
               return new SwissAlti3dCoverageIndex.AssetReference(this.id + "_2m", this.url2m);
            }
         } else if (this.url2m != null) {
            return new SwissAlti3dCoverageIndex.AssetReference(this.id + "_2m", this.url2m);
         } else if (this.url05m != null) {
            return new SwissAlti3dCoverageIndex.AssetReference(this.id + "_05m", this.url05m);
         }

         return null;
      }

      SwissAlti3dCoverageIndex.AssetReference alternateAsset(boolean prefer05m) {
         if (prefer05m) {
            return this.url2m != null ? new SwissAlti3dCoverageIndex.AssetReference(this.id + "_2m", this.url2m) : null;
         } else {
            return this.url05m != null ? new SwissAlti3dCoverageIndex.AssetReference(this.id + "_05m", this.url05m) : null;
         }
      }
   }

   static record AssetReference(String id, String url) {
      AssetReference {
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
      SwissAlti3dCoverageIndex.TileReference[] tileRefs,
      float[] polygonMinX,
      float[] polygonMinY,
      float[] polygonMaxX,
      float[] polygonMaxY,
      int[] polygonCoordStart,
      int[] polygonCoordCount,
      float[] coords
   ) {
      private static SwissAlti3dCoverageIndex.Index unavailable() {
         return new SwissAlti3dCoverageIndex.Index(
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
            new SwissAlti3dCoverageIndex.TileReference[0],
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
