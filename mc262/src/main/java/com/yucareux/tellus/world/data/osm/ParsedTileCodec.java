package com.yucareux.tellus.world.data.osm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class ParsedTileCodec {
   private static final int MAGIC_ROAD = 1413827666;
   private static final int MAGIC_WATER = 1465349234;
   private static final int MAGIC_BUILDING = 1112428356;
   private static final int MAGIC_SAND = 1396787524;
   private static final int ROAD_VERSION = 3;
   private static final int WATER_VERSION = 1;
   private static final int BUILDING_VERSION = 2;
   private static final int SAND_VERSION = 1;
   private static final int MAX_FEATURES = 100000;
   private static final int MAX_POINTS_PER_FEATURE = 100000;

   private ParsedTileCodec() {
   }

   static OverpassRoadTile readRoadTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_ROAD) {
            throw new IOException("Invalid road tile magic");
         }

         int version = input.readInt();
         if (version != ROAD_VERSION) {
            throw new IOException("Unsupported road tile version: " + version);
         }

         double tileSouth = input.readDouble();
         double tileWest = input.readDouble();
         double tileNorth = input.readDouble();
         double tileEast = input.readDouble();
         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "road feature");
         List<RoadFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            long wayId = input.readLong();
            int classOrdinal = Byte.toUnsignedInt(input.readByte());
            if (classOrdinal >= RoadClass.values().length) {
               throw new IOException("Invalid road class enum ordinal: " + classOrdinal);
            }

            int modeOrdinal = Byte.toUnsignedInt(input.readByte());
            if (modeOrdinal >= RoadMode.values().length) {
               throw new IOException("Invalid road mode enum ordinal: " + modeOrdinal);
            }

            int bridgeLevel = input.readInt();
            String highwayTag = input.readUTF();
            int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "road feature point");
            if (pointCount < 2) {
               throw new IOException("Too few points in the road feature");
            }

            double[] longitudes = new double[pointCount];
            double[] latitudes = new double[pointCount];

            for (int point = 0; point < pointCount; point++) {
               longitudes[point] = input.readDouble();
               latitudes[point] = input.readDouble();
            }

            features.add(new RoadFeature(wayId, RoadClass.values()[classOrdinal], RoadMode.values()[modeOrdinal], bridgeLevel, highwayTag, longitudes, latitudes));
         }

         return features.isEmpty() ? OverpassRoadTile.empty() : new OverpassRoadTile(features, tileSouth, tileWest, tileNorth, tileEast);
      }
   }

   static void writeRoadTile(Path path, OverpassRoadTile tile) throws IOException {
      writeTileAtomically(path, output -> {
         output.writeInt(MAGIC_ROAD);
         output.writeInt(ROAD_VERSION);
         output.writeDouble(tile.tileSouth());
         output.writeDouble(tile.tileWest());
         output.writeDouble(tile.tileNorth());
         output.writeDouble(tile.tileEast());
         List<RoadFeature> features = tile.features();
         output.writeInt(features.size());

         for (RoadFeature feature : features) {
            output.writeLong(feature.wayId());
            output.writeByte(feature.roadClass().ordinal());
            output.writeByte(feature.mode().ordinal());
            output.writeInt(feature.bridgeLevel());
            output.writeUTF(feature.highwayTag());
            int points = feature.pointCount();
            output.writeInt(points);

            for (int point = 0; point < points; point++) {
               output.writeDouble(feature.lonAt(point));
               output.writeDouble(feature.latAt(point));
            }
         }
      });
   }

   static OsmWaterTile readWaterTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_WATER) {
            throw new IOException("Invalid water tile magic");
         }

         int version = input.readInt();
         if (version != WATER_VERSION) {
            throw new IOException("Unsupported water tile version: " + version);
         }

         double tileSouth = input.readDouble();
         double tileWest = input.readDouble();
         double tileNorth = input.readDouble();
         double tileEast = input.readDouble();
         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "water feature");
         List<OsmWaterFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            long featureId = input.readLong();
            boolean lineGeometry = input.readBoolean();
            boolean oceanHint = input.readBoolean();
            int partCount = boundedCount(input.readInt(), MAX_FEATURES, "water feature part");
            if (partCount <= 0) {
               throw new IOException("Water feature has no geometry parts");
            }

            double[][] longitudes = new double[partCount][];
            double[][] latitudes = new double[partCount][];

            for (int part = 0; part < partCount; part++) {
               int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "water feature point");
               int minPoints = lineGeometry ? 2 : 4;
               if (pointCount < minPoints) {
                  throw new IOException("Too few points in the water feature part");
               }

               longitudes[part] = new double[pointCount];
               latitudes[part] = new double[pointCount];

               for (int point = 0; point < pointCount; point++) {
                  longitudes[part][point] = input.readDouble();
                  latitudes[part][point] = input.readDouble();
               }
            }

            features.add(new OsmWaterFeature(featureId, lineGeometry, oceanHint, longitudes, latitudes));
         }

         return features.isEmpty() ? OsmWaterTile.empty() : new OsmWaterTile(features, tileSouth, tileWest, tileNorth, tileEast);
      }
   }

   static void writeWaterTile(Path path, OsmWaterTile tile) throws IOException {
      writeTileAtomically(path, output -> {
         output.writeInt(MAGIC_WATER);
         output.writeInt(WATER_VERSION);
         output.writeDouble(tile.tileSouth());
         output.writeDouble(tile.tileWest());
         output.writeDouble(tile.tileNorth());
         output.writeDouble(tile.tileEast());
         List<OsmWaterFeature> features = tile.features();
         output.writeInt(features.size());

         for (OsmWaterFeature feature : features) {
            output.writeLong(feature.featureId());
            output.writeBoolean(feature.lineGeometry());
            output.writeBoolean(feature.oceanHint());
            output.writeInt(feature.partCount());

            for (int part = 0; part < feature.partCount(); part++) {
               int points = feature.pointCount(part);
               output.writeInt(points);

               for (int point = 0; point < points; point++) {
                  output.writeDouble(feature.lonAt(part, point));
                  output.writeDouble(feature.latAt(part, point));
               }
            }
         }
      });
   }

   static OsmSandTile readSandTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_SAND) {
            throw new IOException("Invalid sand tile magic");
         }

         int version = input.readInt();
         if (version != SAND_VERSION) {
            throw new IOException("Unsupported sand tile version: " + version);
         }

         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "sand feature");
         List<OsmSandFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            long featureId = input.readLong();
            int partCount = boundedCount(input.readInt(), MAX_FEATURES, "sand feature part");
            if (partCount <= 0) {
               throw new IOException("Sand feature has no geometry parts");
            }

            double[][] longitudes = new double[partCount][];
            double[][] latitudes = new double[partCount][];

            for (int part = 0; part < partCount; part++) {
               int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "sand feature point");
               if (pointCount < 4) {
                  throw new IOException("Too few points in the sand feature part");
               }

               longitudes[part] = new double[pointCount];
               latitudes[part] = new double[pointCount];

               for (int point = 0; point < pointCount; point++) {
                  longitudes[part][point] = input.readDouble();
                  latitudes[part][point] = input.readDouble();
               }
            }

            features.add(new OsmSandFeature(featureId, longitudes, latitudes));
         }

         return features.isEmpty() ? OsmSandTile.empty() : new OsmSandTile(features);
      }
   }

   static void writeSandTile(Path path, OsmSandTile tile) throws IOException {
      writeTileAtomically(path, output -> {
         output.writeInt(MAGIC_SAND);
         output.writeInt(SAND_VERSION);
         List<OsmSandFeature> features = tile.features();
         output.writeInt(features.size());

         for (OsmSandFeature feature : features) {
            output.writeLong(feature.featureId());
            output.writeInt(feature.partCount());

            for (int part = 0; part < feature.partCount(); part++) {
               int points = feature.pointCount(part);
               output.writeInt(points);

               for (int point = 0; point < points; point++) {
                  output.writeDouble(feature.lonAt(part, point));
                  output.writeDouble(feature.latAt(part, point));
               }
            }
         }
      });
   }

   static OsmBuildingTile readBuildingTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_BUILDING) {
            throw new IOException("Invalid building tile magic");
         }

         int version = input.readInt();
         if (version != BUILDING_VERSION) {
            throw new IOException("Unsupported building tile version: " + version);
         }

         double tileSouth = input.readDouble();
         double tileWest = input.readDouble();
         double tileNorth = input.readDouble();
         double tileEast = input.readDouble();
         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "building feature");
         List<OsmBuildingFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            int kindOrdinal = Byte.toUnsignedInt(input.readByte());
            if (kindOrdinal >= OsmBuildingKind.values().length) {
               throw new IOException("Invalid building kind enum ordinal: " + kindOrdinal);
            }

            long featureId = input.readLong();
            boolean hasParts = input.readBoolean();
            boolean hasBuildingId = input.readBoolean();
            String buildingId = hasBuildingId ? input.readUTF() : null;
            String buildingClass = readOptionalUtf(input);
            String subtype = readOptionalUtf(input);
            String use = readOptionalUtf(input);
            String name = readOptionalUtf(input);
            int floorCount = input.readInt();
            String roofShape = readOptionalUtf(input);
            String roofMaterial = readOptionalUtf(input);
            double heightMeters = input.readDouble();
            double minHeightMeters = input.readDouble();
            int partCount = boundedCount(input.readInt(), MAX_FEATURES, "building feature part");
            if (partCount <= 0) {
               throw new IOException("Building feature has no geometry parts");
            }

            double[][] longitudes = new double[partCount][];
            double[][] latitudes = new double[partCount][];

            for (int part = 0; part < partCount; part++) {
               int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "building feature point");
               if (pointCount < 4) {
                  throw new IOException("Too few points in the building feature part");
               }

               longitudes[part] = new double[pointCount];
               latitudes[part] = new double[pointCount];

               for (int point = 0; point < pointCount; point++) {
                  longitudes[part][point] = input.readDouble();
                  latitudes[part][point] = input.readDouble();
               }
            }

            features.add(
               new OsmBuildingFeature(
                  OsmBuildingKind.values()[kindOrdinal],
                  featureId,
                  buildingId,
                  hasParts,
                  new OsmBuildingMetadata(buildingClass, subtype, use, name, floorCount, roofShape, roofMaterial),
                  heightMeters,
                  minHeightMeters,
                  longitudes,
                  latitudes
               )
            );
         }

         return features.isEmpty() ? OsmBuildingTile.empty() : new OsmBuildingTile(features, tileSouth, tileWest, tileNorth, tileEast);
      }
   }

   static void writeBuildingTile(Path path, OsmBuildingTile tile) throws IOException {
      writeTileAtomically(path, output -> {
         output.writeInt(MAGIC_BUILDING);
         output.writeInt(BUILDING_VERSION);
         output.writeDouble(tile.tileSouth());
         output.writeDouble(tile.tileWest());
         output.writeDouble(tile.tileNorth());
         output.writeDouble(tile.tileEast());
         List<OsmBuildingFeature> features = tile.features();
         output.writeInt(features.size());

         for (OsmBuildingFeature feature : features) {
            output.writeByte(feature.kind().ordinal());
            output.writeLong(feature.featureId());
            output.writeBoolean(feature.hasParts());
            output.writeBoolean(feature.buildingId() != null);
            if (feature.buildingId() != null) {
               output.writeUTF(feature.buildingId());
            }

            writeOptionalUtf(output, feature.metadata().buildingClass());
            writeOptionalUtf(output, feature.metadata().subtype());
            writeOptionalUtf(output, feature.metadata().use());
            writeOptionalUtf(output, feature.metadata().name());
            output.writeInt(feature.metadata().floorCount());
            writeOptionalUtf(output, feature.metadata().roofShape());
            writeOptionalUtf(output, feature.metadata().roofMaterial());
            output.writeDouble(feature.heightMeters());
            output.writeDouble(feature.minHeightMeters());
            output.writeInt(feature.partCount());

            for (int part = 0; part < feature.partCount(); part++) {
               int points = feature.pointCount(part);
               output.writeInt(points);

               for (int point = 0; point < points; point++) {
                  output.writeDouble(feature.lonAt(part, point));
                  output.writeDouble(feature.latAt(part, point));
               }
            }
         }
      });
   }

   private static int boundedCount(int count, int max, String label) throws IOException {
      if (count >= 0 && count <= max) {
         return count;
      } else {
         throw new IOException("Invalid " + label + " count: " + count);
      }
   }


   /**
    * 2026-10-05: unique temp file for an atomic cache write.
    *
    * <p>The old code used a fixed {@code <name>.tmp} sibling. Two writers of the same tile then
    * shared one temp file: whichever opened second truncated it, they interleaved their writes, and
    * the final {@code Files.move} could promote a <b>half-written</b> file into the cache -- silent,
    * permanent cache corruption. {@link Files#createTempFile} guarantees a distinct name per writer,
    * and the move below is atomic so readers only ever see a complete tile.
    */
   private static Path newTempSibling(Path target) throws IOException {
      Path dir = target.toAbsolutePath().getParent();
      Files.createDirectories(dir);
      return Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
   }

   /** Writes the body of a cache tile, then publishes it with an atomic rename. */
   @FunctionalInterface
   private interface TileBody {
      void write(DataOutputStream output) throws IOException;
   }

   /**
    * 2026-10-05: safe publish of a parsed cache tile.
    *
    * <p>The old shape was {@code <name>.tmp} + write + move, repeated four times, with no cleanup:
    * <ul>
    *   <li>the temp name was <b>deterministic</b>, so two writers of the same tile shared one file
    *       -- the second open truncated it, they interleaved their bytes, and the final move could
    *       promote a <b>half-written</b> file into the cache. Reproduced: writers threw
    *       {@code NoSuchFileException} because one of them had already moved the shared temp away;</li>
    *   <li>the move was a plain REPLACE_EXISTING, so a reader could observe a partial file;</li>
    *   <li>a failed move left the .tmp behind forever, and on Windows a concurrent replace of the
    *       same target fails with {@code AccessDeniedException} (sharing violation), which needs a
    *       short retry rather than an outright failure.</li>
    * </ul>
    * Now: unique temp file per writer, atomic publish with retry, and the temp is always cleaned up.
    */
   private static void writeTileAtomically(Path target, TileBody body) throws IOException {
      Path temp = newTempSibling(target);

      try {
         try (DataOutputStream output = new DataOutputStream(
               new BufferedOutputStream(Files.newOutputStream(temp)))) {
            body.write(output);
         }

         moveIntoPlace(temp, target);
      } catch (IOException | RuntimeException error) {
         // never leave a stray .tmp occupying disk after a failed publish
         Files.deleteIfExists(temp);
         throw error;
      }
   }

   /**
    * Atomic replace, with a bounded retry for Windows sharing violations.
    *
    * <p>Windows cannot replace a file while another handle has it open, so two threads publishing
    * different tiles is fine but two publishing the <i>same</i> tile can collide with a transient
    * {@link AccessDeniedException}. That resolves in milliseconds, so a short backoff turns a hard
    * failure into a success without changing the outcome for genuinely broken paths.
    */
   private static void moveIntoPlace(Path temp, Path target) throws IOException {
      IOException lastFailure = null;

      for (int attempt = 0; attempt < 8; attempt++) {
         try {
            try {
               Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
               Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return;
         } catch (java.nio.file.AccessDeniedException targetBusy) {
            lastFailure = targetBusy;

            try {
               Thread.sleep(5L * (attempt + 1));
            } catch (InterruptedException interrupted) {
               Thread.currentThread().interrupt();
               throw targetBusy;
            }
         }
      }

      throw lastFailure;
   }

   private static void writeOptionalUtf(DataOutputStream output, String value) throws IOException {
      output.writeBoolean(value != null);
      if (value != null) {
         output.writeUTF(value);
      }
   }

   private static String readOptionalUtf(DataInputStream input) throws IOException {
      return input.readBoolean() ? input.readUTF() : null;
   }
}
