package com.yucareux.tellus.world.data.elevation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.SingleXZInputStream;
import org.tukaani.xz.XZOutputStream;

final class TellusElevationProvenanceCodec {
   private static final byte[] SIGNATURE = "TELLUS/PROVENANCE".getBytes(StandardCharsets.US_ASCII);

   private TellusElevationProvenanceCodec() {
   }

   static void write(OutputStream output, TellusElevationProvenance provenance) throws IOException {
      try (DataOutputStream dataOut = new DataOutputStream(output)) {
         dataOut.write(SIGNATURE);
         dataOut.writeByte(0);
         dataOut.writeInt(provenance.width());
         dataOut.writeInt(provenance.height());
         dataOut.writeInt(provenance.providerMask());
         LZMA2Options options = new LZMA2Options();
         options.setPreset(3);
         try (XZOutputStream xzOut = new XZOutputStream(dataOut, options); DataOutputStream xzData = new DataOutputStream(xzOut)) {
            xzData.write(provenance.primaryProviders());
            xzData.write(provenance.blendedFlags());
         }
      }
   }

   static TellusElevationProvenance read(InputStream input) throws IOException {
      DataInputStream dataIn = new DataInputStream(input);
      byte[] signature = new byte[SIGNATURE.length];
      dataIn.readFully(signature);
      if (!Arrays.equals(signature, SIGNATURE)) {
         throw new IOException("Invalid elevation provenance signature");
      } else {
         int version = dataIn.readUnsignedByte();
         if (version != 0) {
            throw new IOException("Unsupported elevation provenance version: " + version);
         } else {
            int width = dataIn.readInt();
            int height = dataIn.readInt();
            int providerMask = dataIn.readInt();
            int sampleCount = width * height;
            byte[] primaryProviders = new byte[sampleCount];
            byte[] blendedFlags = new byte[TellusElevationProvenance.bitSetLength(sampleCount)];
            try (SingleXZInputStream xzIn = new SingleXZInputStream(input); DataInputStream xzData = new DataInputStream(xzIn)) {
               xzData.readFully(primaryProviders);
               xzData.readFully(blendedFlags);
            }

            return new TellusElevationProvenance(width, height, providerMask, primaryProviders, blendedFlags);
         }
      }
   }
}
