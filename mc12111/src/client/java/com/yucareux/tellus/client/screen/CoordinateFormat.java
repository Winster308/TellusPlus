package com.yucareux.tellus.client.screen;

import java.util.Locale;

/**
 * Latitude/longitude text parsing and formatting (6 decimal places, about 0.1 m).
 *
 * <p>Accepted forms (case insensitive, full width commas are accepted too):
 * <ul>
 *   <li>Decimal degrees: {@code 31.5} / {@code -31.5} / {@code +31.5}</li>
 *   <li>With a hemisphere letter: {@code 31.5N} / {@code 121.4E} / {@code N31.5} / {@code 31.5W}</li>
 *   <li>Degrees, minutes, seconds: {@code 31d30m00sN} / {@code 31 30 0 N} / {@code 31d30m0sN}</li>
 *   <li>Both components at once: {@code 31.5, 121.4} (split automatically when the longitude box is left empty)</li>
 * </ul>
 *
 * <p>Returning {@code null} means the text could not be parsed or is out of range, so the caller
 * can tell the user instead of silently substituting 0.
 */
public final class CoordinateFormat {

   private CoordinateFormat() {
   }

   /** Upper latitude bound. */
   public static final double MAX_LATITUDE = 90.0;
   /** Upper longitude bound. */
   public static final double MAX_LONGITUDE = 180.0;

   /** Precise coordinate string: 6 decimals (about 0.1 m). */
   public static String format(double value) {
      return String.format(Locale.ROOT, "%.6f", value);
   }

   /** A single "latitude, longitude" line, used for the status display. */
   public static String formatPair(double latitude, double longitude) {
      return format(latitude) + ", " + format(longitude);
   }

   /**
    * Parses a latitude/longitude pair.
    *
    * @param latitudeText  content of the latitude input box
    * @param longitudeText content of the longitude input box (may be left empty, in which case the
    *                      latitude box is split on the comma)
    * @return {@code {lat, lon}}; {@code null} when it cannot be parsed or is out of range
    */
   public static double[] parsePair(String latitudeText, String longitudeText) {
      String latText = latitudeText == null ? "" : latitudeText.trim();
      String lonText = longitudeText == null ? "" : longitudeText.trim();
      if (latText.isEmpty()) {
         return null;
      }

      if (lonText.isEmpty()) {
         // Accepts a whole "31.5, 121.4" string pasted into the latitude box
         String normalized = latText.replace('，', ',').replace('、', ',');
         int comma = normalized.indexOf(',');
         if (comma < 0) {
            // A space separator is accepted too, but only when both parts look numeric
            String[] parts = normalized.split("\\s+");
            if (parts.length == 2) {
               Double single = parseComponent(parts[0], true);
               Double other = parseComponent(parts[1], false);
               if (single != null && other != null) {
                  return validate(single, other);
               }
            }
            return null;
         }
         lonText = normalized.substring(comma + 1).trim();
         latText = normalized.substring(0, comma).trim();
      }

      Double latitude = parseComponent(latText, true);
      Double longitude = parseComponent(lonText, false);
      if (latitude == null || longitude == null) {
         return null;
      }
      return validate(latitude, longitude);
   }

   private static double[] validate(double latitude, double longitude) {
      if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
         return null;
      }
      if (Math.abs(latitude) > MAX_LATITUDE + 1.0e-9 || Math.abs(longitude) > MAX_LONGITUDE + 1.0e-9) {
         return null;
      }
      return new double[]{latitude, longitude};
   }

   /**
    * Parses a single component.
    *
    * @param latitude true for latitude (N/S, plus or minus 90), false for longitude (E/W, plus or minus 180)
    * @return the parsed value; {@code null} on failure
    */
   public static Double parseComponent(String text, boolean latitude) {
      if (text == null) {
         return null;
      }
      String value = text.trim().toUpperCase(Locale.ROOT);
      if (value.isEmpty()) {
         return null;
      }

      double sign = 1.0;

      // The hemisphere letter may come before or after the number
      if (latitude) {
         if (value.startsWith("N")) {
            value = value.substring(1).trim();
         } else if (value.startsWith("S")) {
            sign = -1.0;
            value = value.substring(1).trim();
         }
      } else if (value.startsWith("E")) {
         value = value.substring(1).trim();
      } else if (value.startsWith("W")) {
         sign = -1.0;
         value = value.substring(1).trim();
      }

      if (value.endsWith("N")) {
         value = value.substring(0, value.length() - 1).trim();
      } else if (value.endsWith("S")) {
         sign = -1.0;
         value = value.substring(0, value.length() - 1).trim();
      } else if (value.endsWith("E")) {
         value = value.substring(0, value.length() - 1).trim();
      } else if (value.endsWith("W")) {
         sign = -1.0;
         value = value.substring(0, value.length() - 1).trim();
      }

      value = value.replace('°', ' ').replace('\'', ' ').replace('"', ' ')
            .replace('D', ' ').replace('M', ' ').replace('S', ' ').replace('，', ' ');

      String[] parts = value.trim().split("[\\s]+");
      if (parts.length == 0 || parts[0].isEmpty()) {
         return null;
      }

      try {
         double degrees = Double.parseDouble(parts[0].replace("+", ""));
         double result = Math.abs(degrees);
         if (parts.length > 1 && !parts[1].isEmpty()) {
            result += Double.parseDouble(parts[1]) / 60.0;
         }
         if (parts.length > 2 && !parts[2].isEmpty()) {
            result += Double.parseDouble(parts[2]) / 3600.0;
         }
         // A leading minus sign in the raw text wins, otherwise the sign of the hemisphere letter is used
         double signed = degrees < 0.0 ? -result : sign * result;
         return signed;
      } catch (NumberFormatException e) {
         return null;
      }
   }
}
