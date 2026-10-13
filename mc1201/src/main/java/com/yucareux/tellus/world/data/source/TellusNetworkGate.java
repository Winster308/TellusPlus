package com.yucareux.tellus.world.data.source;

import com.yucareux.tellus.Tellus;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline-first gate: guarantees **no network request is ever issued** during world generation ✓.
 *
 * <h2>Why it exists</h2>
 *
 * <p>Field incident on 2026-09-20: a player entering the world got stuck for 10 minutes and could not get in ✗, the log stalled at "preparing region 16%" ✗.
 * The thread dump showed the <b>Server thread stopped at {@code FutureTask.get} → {@code TellusDownloadExecutor.await}</b> ✗,
 * while the download thread was stuck in a socket read for <b>694 seconds</b> ✗ -- a slow drip kept resetting {@code setReadTimeout(8000)} over and over ✗,
 * so "generation needs surface cover → waits synchronously for the download → network stuck" = the entire server thread hangs indefinitely ✗✓.
 *
 * <p>The two patches each cure half the problem:
 * <ol>
 *   <li>{@link TellusDownloadExecutor} adds a "task-level wall-clock deadline + consecutive-failure circuit breaker" ✓ (cures "stuck and never returns" ✗);</li>
 *   <li>this class adds "offline-first" ✓ (cures "should never have issued a request at this moment" ✗).</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <p>Configuration is written to {@code config/telluscn-layout.properties} (any key is converted to {@code -Dtellus.<key>} ✓):
 * <pre>
 * network.offlineFirst=true
 * </pre>
 *
 * <p>After switching it on: <b>local cache hits are used as usual ✓</b> (each data source's {@code loadTile} already
 * "checks the local file first, and only goes online when it is missing" ✓), only that **on a miss it goes straight to the fallback value** ✓ and
 * no longer downloads on the spot ✓. Use it together with {@code preload.auto} pre-downloading:
 * download the data into the cache first, then enter the world; the whole generation run has zero network access ✓ → no more stalling, no more rate limiting ✓.
 *
 * <p>Default <b>false</b> ✓ -- when it is not switched on, behavior is bit-for-bit identical to before the change ✓ (this project's hard rule 3 ✓).
 */
public final class TellusNetworkGate {

   private static final AtomicBoolean WARNED = new AtomicBoolean();

   /** The on/off value is read only once ✓: the offline gate is called before every HTTP request ✓, so do not check the system property every time ✗. */
   private static final boolean OFFLINE_FIRST =
         Boolean.parseBoolean(System.getProperty("tellus.network.offlineFirst", "false"));

   private TellusNetworkGate() {
   }

   /** Whether offline-first is switched on (reads {@code -Dtellus.network.offlineFirst}, default false). */
   public static boolean offlineFirst() {
      return OFFLINE_FIRST;
   }

   /**
    * Only intercepts http(s) requests that really would go over the network ✓; local URIs such as {@code file:} always pass ✓
    * (PMTiles may be a local file ✓, blocking it would break local reading too ✗).
    *
    * @param uri  the address about to be connected to
    * @param what the Chinese description used for the log (which data source / which tile)
    */
   public static void requireOnline(URI uri, String what) throws IOException {
      if (!offlineFirst()) {
         return;
      }
      if (uri == null) {
         return;
      }
      String scheme = uri.getScheme();
      if (scheme == null
            || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
         return;
      }
      if (WARNED.compareAndSet(false, true)) {
         Tellus.LOGGER.info("[TellusCN] Offline-first is enabled: no networking during generation; cache misses use fallback data"
            + " (please run /tellus preload or preload.auto first to fill the cache;"
            + " remove -Dtellus.network.offlineFirst to bring networking back)");
      }
      throw new IOException("offline-first mode: skipped network request (" + what + "), falling back to cached/default data");
   }

   /** Overload for call sites that have no ready-made URI variable: checks only the on/off flag, not the protocol. */
   public static void requireOnline(String what) throws IOException {
      if (offlineFirst()) {
         throw new IOException("offline-first mode: skipped network request (" + what + "), falling back to cached/default data");
      }
   }
}
