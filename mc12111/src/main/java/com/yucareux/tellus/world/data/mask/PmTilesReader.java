package com.yucareux.tellus.world.data.mask;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

final class PmTilesReader {
   private static final int HEADER_SIZE = 127;
   private static final int MAX_DIRECTORY_DEPTH = 4;
   /** PMTiles compression ids (spec v3): 1 = none, 2 = gzip. Anything else is refused explicitly. */
   private static final int COMPRESSION_NONE = 1;
   private static final int COMPRESSION_GZIP = 2;
   private static final int MAX_DIRECTORY_CACHE = intProperty("tellus.landmask.dirCache", 256);
   private static final int READ_TIMEOUT_MS = 15000;
   private static final int CONNECT_TIMEOUT_MS = 10000;
   private final String url;
   private final LoadingCache<PmTilesReader.DirectoryKey, PmTilesReader.Directory> directoryCache;
   
   /**
    * 2026-10-05: these lazy singletons are written by whichever block-generation thread gets here
    * first and read by all the others. They were plain (non-volatile) fields, so a second thread
    * could observe a non-null reference with none of the constructor/parsing writes visible.
    * That matters here because {@code Directory} holds a final {@code List}, but the {@code Entry}
    * elements inside it have <b>non-final mutable</b> offset/length/runLength fields filled in
    * during parsing -- a volatile write followed by a volatile read establishes the happens-before
    * edge that makes all of those visible.
    */
   private volatile PmTilesReader.PmTilesHeader header;

   private volatile PmTilesReader.Directory rootDirectory;
   private volatile URI uri;

   PmTilesReader(String url) {
      this.url = Objects.requireNonNull(url, "url");
      this.directoryCache = CacheBuilder.newBuilder()
         .maximumSize(MAX_DIRECTORY_CACHE)
         .build(new CacheLoader<PmTilesReader.DirectoryKey, PmTilesReader.Directory>() {
            public PmTilesReader.Directory load(PmTilesReader.DirectoryKey key) throws Exception {
               return PmTilesReader.this.readDirectory(key.offset, key.length);
            }
         });
   }

   PmTilesReader.PmTilesHeader header() throws IOException {
      if (this.header == null) {
         this.header = this.readHeader();
      }

      return this.header;
   }

   
   byte[] getTileBytes(int z, int x, int y) throws IOException {
      long tileId = zxyToTileId(z, x, y);
      PmTilesReader.PmTilesHeader header = this.header();
      PmTilesReader.Directory directory = this.getRootDirectory();

      for (int depth = 0; depth < MAX_DIRECTORY_DEPTH; depth++) {
         PmTilesReader.Entry entry = findTile(directory.entries, tileId);
         if (entry == null) {
            return null;
         }

         if (entry.runLength != 0L) {
            long dataOffset = header.tileDataOffset + entry.offset;
            if (entry.length > 2147483647L) {
               throw new IOException("Tile too large");
            }

            byte[] tileBytes = this.readBytes(dataOffset, (int)entry.length);
            return decompress(tileBytes, header.tileCompression);
         }

         long dirOffset = header.leafDirectoryOffset + entry.offset;
         long dirLength = entry.length;
         directory = this.getDirectory(dirOffset, dirLength);
      }

      // 2026-10-05: falling out of the depth loop used to `return null`, which is exactly what
      // "this tile does not exist" also returns. A too-deep directory tree was therefore
      // indistinguishable from an empty area and the data was dropped with no trace at all.
      throw new IOException("PMTiles directory for tile " + tileId + " is nested deeper than the "
         + MAX_DIRECTORY_DEPTH + " levels this reader supports; the tile exists but is unreachable");
   }

   private PmTilesReader.Directory getRootDirectory() throws IOException {
      if (this.rootDirectory == null) {
         PmTilesReader.PmTilesHeader header = this.header();
         this.rootDirectory = this.getDirectory(header.rootOffset, header.rootLength);
      }

      return this.rootDirectory;
   }

   private PmTilesReader.Directory getDirectory(long offset, long length) throws IOException {
      try {
         return (PmTilesReader.Directory)this.directoryCache.get(new PmTilesReader.DirectoryKey(offset, length));
      } catch (Exception var8) {
         if (var8.getCause() instanceof IOException io) {
            throw io;
         } else {
            throw new IOException("Failed to read the PMTiles directory", var8);
         }
      }
   }

   private PmTilesReader.PmTilesHeader readHeader() throws IOException {
      byte[] headerBytes = this.readBytes(0L, HEADER_SIZE);
      if (!"PMTiles".equals(new String(headerBytes, 0, 7, StandardCharsets.US_ASCII))) {
         throw new IOException("PMTiles header is missing");
      } else {
         int version = headerBytes[7] & 255;
         if (version != 3) {
            throw new IOException("Unsupported PMTiles version: " + version);
         } else {
            long rootOffset = readUint64(headerBytes, 8);
            long rootLength = readUint64(headerBytes, 16);
            long metadataOffset = readUint64(headerBytes, 24);
            long metadataLength = readUint64(headerBytes, 32);
            long leafOffset = readUint64(headerBytes, 40);
            long leafLength = readUint64(headerBytes, 48);
            long tileOffset = readUint64(headerBytes, 56);
            long tileLength = readUint64(headerBytes, 64);
            int internalCompression = headerBytes[97] & 255;
            int tileCompression = headerBytes[98] & 255;
            int tileType = headerBytes[99] & 255;
            int minZoom = headerBytes[100] & 255;
            int maxZoom = headerBytes[101] & 255;
            if (tileType != 2) {
               Tellus.LOGGER.warn("Unexpected PMTiles tile type {}", tileType);
            }

            // 2026-10-05: no longer "warn and ignore" -- these two are now carried in the header
            // and actually applied (see decompress()). An unexpected value raises a clear
            // "Unsupported PMTiles compression type" IOException at use time instead of silently
            // producing garbage bytes.
            return new PmTilesReader.PmTilesHeader(
               rootOffset, rootLength, metadataOffset, metadataLength, leafOffset, leafLength,
               tileOffset, tileLength, internalCompression, tileCompression, minZoom, maxZoom
            );
         }
      }
   }

   private PmTilesReader.Directory readDirectory(long offset, long length) throws IOException {
      if (length <= 0L) {
         return new PmTilesReader.Directory(List.of());
      } else if (length > Integer.MAX_VALUE) {
         throw new IOException("PMTiles directory too large");
      } else {
         byte[] compressed = this.readBytes(offset, (int)length);
         byte[] decompressed = decompress(compressed, this.header().internalCompression);
         ByteArrayInputStream input = new ByteArrayInputStream(decompressed);
         // 2026-10-05: validate before allocating. A hostile archive could declare more than
         // 2^31-1 entries, and the (int) cast would turn that into a NEGATIVE int, so
         // new ArrayList<>(negative) throws an UNCHECKED IllegalArgumentException that escapes
         // every catch (IOException) on the way out. A plausible-but-huge value would instead try
         // to pre-allocate that many entries and OOM. Each entry costs at least one byte, so the
         // remaining input length is a sound and cheap upper bound.
         long declaredEntries = readVarint(input);
         if (declaredEntries < 0L || declaredEntries > input.available()) {
            throw new IOException("PMTiles directory declares " + declaredEntries
               + " entries but only " + input.available() + " bytes are available");
         }

         int numEntries = (int)declaredEntries;
         List<PmTilesReader.Entry> entries = new ArrayList<>(numEntries);
         long lastId = 0L;

         for (int i = 0; i < numEntries; i++) {
            long delta = readVarint(input);
            long tileId = lastId + delta;
            entries.add(new PmTilesReader.Entry(tileId, 0L, 0L, 0L));
            lastId = tileId;
         }

         for (int i = 0; i < numEntries; i++) {
            entries.get(i).runLength = readVarint(input);
         }

         for (int i = 0; i < numEntries; i++) {
            entries.get(i).length = readVarint(input);
         }

         for (int i = 0; i < numEntries; i++) {
            long tmp = readVarint(input);
            if (i > 0 && tmp == 0L) {
               PmTilesReader.Entry prev = entries.get(i - 1);
               entries.get(i).offset = prev.offset + prev.length;
            } else {
               entries.get(i).offset = tmp - 1L;
            }
         }

         return new PmTilesReader.Directory(entries);
      }
   }

   private byte[] readBytes(long offset, int length) throws IOException {
      if (length <= 0) {
         return new byte[0];
      } else {
         // ★ Offline-first gate ✓ (when -Dtellus.network.offlineFirst=true, a local cache miss never goes online ✓)
         com.yucareux.tellus.world.data.source.TellusNetworkGate.requireOnline(this.uri(), "land mask PMTiles");
         HttpURLConnection connection = (HttpURLConnection)this.uri().toURL().openConnection();
         connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1L));
         connection.setInstanceFollowRedirects(true);
         connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
         connection.setReadTimeout(READ_TIMEOUT_MS);
         int code = connection.getResponseCode();
         if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IOException("PMTiles HTTP error code " + code);
         }

         byte[] var7;
         try (InputStream input = connection.getInputStream()) {
            if (code == HttpURLConnection.HTTP_OK) {
               skipFully(input, offset);
               return readFully(input, length);
            }

            var7 = readFully(input, length);
         } finally {
            connection.disconnect();
         }

         return var7;
      }
   }

   private URI uri() {
      if (this.uri == null) {
         this.uri = URI.create(this.url);
      }

      return this.uri;
   }

   /**
    * Hard ceiling on a decompressed PMTiles directory. 64 MiB is far above any real directory
    * (they hold a few thousand entries) but stops a zip bomb before it takes the heap down.
    */
   private static final int MAX_DECOMPRESSED_BYTES =
      Integer.getInteger("tellus.pmtiles.maxDecompressedBytes", 64 * 1024 * 1024);

   /**
    * 2026-10-05: bounded gunzip.
    *
    * <p>The old loop grew a {@link ByteArrayOutputStream} with no ceiling, so a tiny hostile (or
    * merely corrupt) archive could expand until the heap died -- the textbook zip bomb, reachable
    * straight from a remote PMTiles file. Directories are metadata and are small by nature, so
    * {@link #MAX_DECOMPRESSED_BYTES} is generous for them while still being a hard stop.
    */
   /**
    * 2026-10-05: cap on how much we are willing to download and throw away when a PMTiles host
    * ignores our Range header.
    */
   private static final long MAX_IGNORED_RANGE_SKIP_BYTES =
      Long.getLong("tellus.pmtiles.maxIgnoredRangeSkipBytes", 64L * 1024 * 1024);
   /** One-shot warning so a CDN misconfiguration is reported once, not once per tile. */
   private static final java.util.concurrent.atomic.AtomicBoolean WARNED_IGNORED_RANGE =
      new java.util.concurrent.atomic.AtomicBoolean(false);

   private static byte[] gunzip(byte[] input) throws IOException {
      byte[] var5;
      try (
         GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input));
         ByteArrayOutputStream output = new ByteArrayOutputStream();
      ) {
         byte[] buffer = new byte[8192];

         int read;
         int total = 0;
         while ((read = gzip.read(buffer)) != -1) {
            total += read;
            if (total > MAX_DECOMPRESSED_BYTES) {
               throw new IOException("PMTiles gzip stream expands beyond "
                  + MAX_DECOMPRESSED_BYTES + " bytes; refusing to continue");
            }

            output.write(buffer, 0, read);
         }

         var5 = output.toByteArray();
      }

      return var5;
   }

   /**
    * 2026-10-05: byte budget for the "server ignored our Range header" path.
    *
    * <p>When the origin/CDN answers 200 instead of 206 we have no choice but to throw away
    * everything before the byte we want. That is already expensive (every tile re-downloads the
    * whole prefix), but it was also <b>unbounded</b>: a corrupt directory could hand us an arbitrary
    * offset and we would stream that many bytes off the network before failing. This caps the waste
    * and says so plainly; the warn-once below points at the real problem.
    */
   private static void skipFully(InputStream input, long bytes) throws IOException {
      if (bytes > MAX_IGNORED_RANGE_SKIP_BYTES) {
         throw new IOException("PMTiles server ignored the Range header and the archive offset "
            + bytes + " exceeds the " + MAX_IGNORED_RANGE_SKIP_BYTES
            + " byte discard budget; refusing to stream that much just to reach one tile");
      }

      if (WARNED_IGNORED_RANGE.compareAndSet(false, true)) {
         com.yucareux.tellus.Tellus.LOGGER.warn(
            "[TellusCN] The PMTiles host answered 200 instead of 206 for a Range request, so tiles are"
               + " re-downloaded from the start of the archive each time. That is very expensive on a"
               + " large archive -- configure the CDN/origin to honour Range requests."
               + " (-Dtellus.pmtiles.maxIgnoredRangeSkipBytes to raise the discard budget)");
      }

      long remaining = bytes;

      while (remaining > 0L) {
         long skipped = input.skip(remaining);
         if (skipped <= 0L) {
            if (input.read() == -1) {
               throw new EOFException("Unexpected end of file while skipping excess data");
            }

            remaining--;
         } else {
            remaining -= skipped;
         }
      }
   }

   private static byte[] readFully(InputStream input, int length) throws IOException {
      byte[] buffer = new byte[length];
      int offset = 0;

      while (offset < length) {
         int read = input.read(buffer, offset, length - offset);
         if (read == -1) {
            throw new EOFException("Unexpected end of file while reading");
         }

         offset += read;
      }

      return buffer;
   }

   /**
    * 2026-10-05: bounded varint.
    *
    * <p>The old loop let {@code shift} grow without limit. The JVM masks a {@code long} shift
    * distance to 6 bits, so once {@code shift} passed 63 the writes silently wrapped around and the
    * returned value was garbage rather than an error -- and a stream of continuation bytes could
    * keep the loop reading for as long as it liked. PMTiles varints are 64-bit, so 10 bytes is the
    * legal maximum; anything longer is rejected.
    */
   private static long readVarint(InputStream input) throws IOException {
      long result = 0L;
      int shift = 0;

      while (true) {
         int raw = input.read();
         if (raw == -1) {
            throw new EOFException("Unexpected end of file while reading a varint");
         }

         if (shift >= 64) {
            throw new IOException("PMTiles varint is longer than 64 bits");
         }

         result |= (long)(raw & 127) << shift;
         if ((raw & 128) == 0) {
            return result;
         }

         shift += 7;
      }
   }

   private static long readUint64(byte[] buffer, int pos) {
      return buffer[pos] & 255L
         | (buffer[pos + 1] & 255L) << 8
         | (buffer[pos + 2] & 255L) << 16
         | (buffer[pos + 3] & 255L) << 24
         | (buffer[pos + 4] & 255L) << 32
         | (buffer[pos + 5] & 255L) << 40
         | (buffer[pos + 6] & 255L) << 48
         | (buffer[pos + 7] & 255L) << 56;
   }

   private static long zxyToTileId(int z, int x, int y) {
      if (z > 31) {
         throw new IllegalArgumentException("Tile zoom level exceeds the 64-bit integer range");
      } else {
         int max = (1 << z) - 1;
         if (x >= 0 && y >= 0 && x <= max && y <= max) {
            long acc = ((1L << z * 2) - 1L) / 3L;

            for (int level = z - 1; level >= 0; level--) {
               int scale = 1 << level;
               int rx = scale & x;
               int ry = scale & y;
               acc += (long)(3 * rx ^ ry) << level;
               if (ry == 0) {
                  if (rx != 0) {
                     x = scale - 1 - x;
                     y = scale - 1 - y;
                  }

                  int swapped = x;
                  x = y;
                  y = swapped;
               }
            }

            return acc;
         } else {
            throw new IllegalArgumentException("Tile x/y is out of range for that zoom level");
         }
      }
   }

   private static PmTilesReader.Entry findTile(List<PmTilesReader.Entry> entries, long tileId) {
      int low = 0;
      int high = entries.size() - 1;

      while (low <= high) {
         // The original expression low + high >>> 1, due to operator precedence, is actually low + (high >>> 1) ✗ (not (low+high)>>>1),
         // so the midpoint is always biased to the left. In practice it still converges, still misses nothing and is still O(log n), so it is **not** a correctness or performance bug ✓,
         // it just does not match the intent and can mislead later readers. Changed to the standard non-overflowing form.
         int mid = low + ((high - low) >>> 1);
         long diff = tileId - entries.get(mid).tileId;
         if (diff > 0L) {
            low = mid + 1;
         } else {
            if (diff >= 0L) {
               return entries.get(mid);
            }

            high = mid - 1;
         }
      }

      if (high >= 0) {
         PmTilesReader.Entry entry = entries.get(high);
         // On binary-search exit entries[high].tileId is always **less than** tileId (the equal case already returned above) ✓
         // PMTiles spec: run_length == 0 means this entry covers only **that single** tile_id block ✗
         // the old code returned entry directly here ⇒ when the requested tile does not exist, what comes back is the **previous block**'s offset/length
         //    ⇒ silently uses the neighbouring tile's vector data as this block's data, with no error and no crash ✓ (fixed)
         if (entry.runLength == 0L) {
            return null;
         }

         if (tileId - entry.tileId < entry.runLength) {
            return entry;
         }
      }

      return null;
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(1, Integer.parseInt(value));
         } catch (NumberFormatException var4) {
            return defaultValue;
         }
      }
   }

   private static final class Directory {
      private final List<PmTilesReader.Entry> entries;

      private Directory(List<PmTilesReader.Entry> entries) {
         this.entries = entries;
      }
   }

   /**
    * 2026-10-05: apply the archive's declared compression instead of assuming it.
    *
    * <p>This reader used to warn when {@code tileCompression}/{@code internalCompression} were not
    * the values it expected and then carry on regardless: tiles were handed back still compressed
    * and directories were always run through gunzip. For a gzip archive that means every single
    * {@code TellusLandMaskSource.loadTile} call feeds gzip bytes to {@code ImageIO.read}, gets null
    * back and dies with a misleading "invalid image" error. Mirrors what
    * {@code PmTilesRangeReader} already does.
    */
   private static byte[] decompress(byte[] payload, int compression) throws IOException {
      return switch (compression) {
         case COMPRESSION_NONE -> payload;
         case COMPRESSION_GZIP -> gunzip(payload);
         default -> throw new IOException("Unsupported PMTiles compression type: " + compression);
      };
   }

   private record DirectoryKey(long offset, long length) {
   }

   private static final class Entry {
      private final long tileId;
      private long offset;
      private long length;
      private long runLength;

      private Entry(long tileId, long offset, long length, long runLength) {
         this.tileId = tileId;
         this.offset = offset;
         this.length = length;
         this.runLength = runLength;
      }
   }

   static final class PmTilesHeader {
      private final long rootOffset;
      private final long rootLength;
      private final long metadataOffset;
      private final long metadataLength;
      private final long leafDirectoryOffset;
      private final long leafDirectoryLength;
      private final long tileDataOffset;
      private final long tileDataLength;
      private final int internalCompression;
      private final int tileCompression;
      private final int minZoom;
      private final int maxZoom;

      private PmTilesHeader(
         long rootOffset,
         long rootLength,
         long metadataOffset,
         long metadataLength,
         long leafDirectoryOffset,
         long leafDirectoryLength,
         long tileDataOffset,
         long tileDataLength,
         int internalCompression,
         int tileCompression,
         int minZoom,
         int maxZoom
      ) {
         this.rootOffset = rootOffset;
         this.rootLength = rootLength;
         this.metadataOffset = metadataOffset;
         this.metadataLength = metadataLength;
         this.leafDirectoryOffset = leafDirectoryOffset;
         this.leafDirectoryLength = leafDirectoryLength;
         this.tileDataOffset = tileDataOffset;
         this.tileDataLength = tileDataLength;
         this.internalCompression = internalCompression;
         this.tileCompression = tileCompression;
         this.minZoom = minZoom;
         this.maxZoom = maxZoom;
      }

      public int internalCompression() {
         return this.internalCompression;
      }

      public int tileCompression() {
         return this.tileCompression;
      }

      public long rootOffset() {
         return this.rootOffset;
      }

      public long rootLength() {
         return this.rootLength;
      }

      public long metadataOffset() {
         return this.metadataOffset;
      }

      public long metadataLength() {
         return this.metadataLength;
      }

      public long leafDirectoryOffset() {
         return this.leafDirectoryOffset;
      }

      public long leafDirectoryLength() {
         return this.leafDirectoryLength;
      }

      public long tileDataOffset() {
         return this.tileDataOffset;
      }

      public long tileDataLength() {
         return this.tileDataLength;
      }

      public int minZoom() {
         return this.minZoom;
      }

      public int maxZoom() {
         return this.maxZoom;
      }
   }
}
