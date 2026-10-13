package com.yucareux.tellus.world.data.source;

import com.yucareux.tellus.Tellus;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified multi-threaded download layer (shared by DEM tiles / land cover / mask / PMTiles).
 *
 * <h2>Why it is needed</h2>
 *
 * <p>Every upstream data source does "Guava LoadingCache + direct HTTP inside loadTile(key)":
 * <ul>
 *   <li><b>No parallel downloads</b>: the N threads of chunk generation simultaneously miss N tiles,
 *       each issuing a request on <b>its own thread</b>; and {@code prefetchTile} is a synchronous
 *       {@code cache.get}, so entering a world becomes "the main thread pulls dozens of tiles one by one";</li>
 *   <li><b>No cross-call-site deduplication</b>: Guava only merges concurrent loads of the same key.</li>
 * </ul>
 *
 * <h2>What it does</h2>
 *
 * <ol>
 *   <li><b>Parallelism</b>: all downloads are submitted to the same bounded daemon thread pool, defaulting to
 *       {@code max(4, min(12, cores脳3))} (overridable via {@code -Dtellus.download.threads=N}).
 *       Callers only wait on Futures; HTTP happens on pool threads 鈥?"how many chunk generation threads"
 *       is decoupled from "how many concurrent downloads", so even weak machines can saturate the network.</li>
 *   <li><b>Deduplication (single-flight)</b>: concurrent requests are merged by {@code domain|key};
 *       the same tile is only downloaded once.</li>
 *   <li><b>Nested-deadlock prevention</b>: when a pool thread calls {@link #load} again, it runs
 *       <b>inline</b> (ThreadLocal flag), otherwise "fetching land cover inside elevation loading"
 *       would fill the pool with threads waiting on each other.</li>
 * </ol>
 *
 * <p>For statistics see {@link #describe()}, which can be used in logs or diagnostic commands to verify
 * that multi-threaded downloading is working.
 *
 * <h2>鈽呪槄 2026-09-26 Fix for "chunk generation crashes the game" 鈽呪槄</h2>
 *
 * <p><b>Symptom</b> (user log 10:35:59, crashed on far-away chunk 176833,-59375):
 * <pre>
 * com.google.common.util.concurrent.UncheckedExecutionException: java.util.concurrent.CancellationException
 *   at com.google.common.cache.LocalCache$Segment.get
 *   at CopernicusDemElevationSource.getTile
 *   ... through TellusElevationSource / EarthChunkGenerator ...
 *   Caused by: java.util.concurrent.CancellationException
 *     at com.yucareux.tellus.world.data.source.TellusDownloadExecutor.await(TellusDownloadExecutor.java:186)
 * </pre>
 *
 * <p><b>Root cause</b>: the same tile gets merged by multiple chunk generation threads into the
 * <b>same flight</b> of downloads (single-flight 鉁?. Previously, any waiter timing out would
 * {@code future.cancel(true)} that flight 鉁?鈥?so <b>other</b> waiters' {@code Future.get} would
 * directly throw {@link CancellationException} 鉁? It is an <b>unchecked</b> exception:
 * <ol>
 *   <li>{@link #await} only caught {@code TimeoutException / InterruptedException / ExecutionException} 鉁?</li>
 *   <li>The unchecked exception escapes from Guava's CacheLoader 鈫?Guava wraps it in
 *       {@code UncheckedExecutionException} 鉁?</li>
 *   <li>Each data source's {@code getTile} only catches {@code ExecutionException} 鉁?</li>
 *   <li>The exception travels all the way to worldgen 鈫?Minecraft treats it as fatal 鈫?
 *       "Exception generating new chunk" 鈫?client crash 鉁?</li>
 * </ol>
 *
 * <p><b>Now</b>:
 * <ul>
 *   <li>{@code await} converts {@link CancellationException} into a checked {@code IOException} 鉁?
 *       鈥?cancellation just means "this sample got no value"; callers always fall back to the
 *       fallback value 鉁? and unchecked exceptions never leak out 鉁?</li>
 *   <li>Timeout only cancels the flight <b>"when no one is waiting for it anymore"</b> 鉁?
 *       ({@link Flight#waiters} counter) 鉁? as long as other threads are still waiting, it is
 *       allowed to run to completion 鉁?(the result still goes into the cache 鉁?.</li>
 * </ul>
 */
public final class TellusDownloadExecutor {

   private static final int CORES = Runtime.getRuntime().availableProcessors();

   /**
    * Download pool size.
    *
    * <p>2026-10-11: this used to be {@code max(4, min(12, CORES * 3))}. That formula assumes the pool is the
    * bottleneck, which it is not: these threads spend most of their time parked on HTTP reads, so a wide pool
    * costs little when there are cores to spare. On a small machine it costs a lot. Measured on a 4-core
    * i5-3470, the old formula asked for 12 download threads, and the game also runs c2me chunk workers, the
    * server thread, the render thread and Voxy's pool on top of that -- roughly 17 runnable threads for 4
    * cores, which is context-switch overhead rather than throughput, and the player sees the CPU "barely
    * loaded" because most threads are queued rather than running.
    *
    * <p>The pool is now sized to the machine: about one and a half threads per core, never more than 12 and
    * never fewer than 4. Four cores therefore get 6 instead of 12, while a 16-core machine still gets the cap.
    * {@code -Dtellus.download.threads=N} overrides it either way.
    */
   private static final int THREADS = intProperty("tellus.download.threads",
         Math.max(4, Math.min(12, (CORES * 3) / 2)));
   private static final int QUEUE_CAPACITY = intProperty("tellus.download.queue", 8192);

   /** In-pool flag: prevents nested dispatch from filling the pool with threads waiting on each other. */
   private static final ThreadLocal<Boolean> ON_POOL = ThreadLocal.withInitial(() -> Boolean.FALSE);

   /**
    * 2026-10-10: how many prefetch tasks may be outstanding before {@link #prefetch} starts dropping them.
    *
    * <h2>Why prefetch needs back-pressure (measured, not theoretical)</h2>
    * The world could not be entered on 2026-10-10 21:03 because a <b>blocking</b> caller lost a race against
    * this pool. The log line at the moment of failure reads:
    * <pre>
    * multi-threaded download: Download pool 12 threads:
    *     submitted 1526, deduped 14909, completed 423, failed 1, in-flight 1102, queued 1090
    * </pre>
    * 鈥?roughly <b>2200 outstanding tasks on 12 threads</b>. Two things feed that: {@code preload.auto} was
    * pouring a 78 km x 83 km "all layers" region into the cache, and c2me's parallel chunk generation made
    * every worker thread issue its own prefetch round.
    *
    * <p>Nothing here is wrong with prefetching <em>per se</em> 鈥?it is speculative work by definition, and
    * dropping a prefetch costs one later re-request. But without a ceiling it can starve work that is
    * <b>not</b> speculative: the spawn column, a chunk the player is standing in, a synchronous repair.
    * Those callers block with a 90 s deadline ({@link #TASK_TIMEOUT_SECONDS}), so a saturated pool turns
    * into a hard failure, not just a delay.
    *
    * <p>So: once this many prefetch tasks are still outstanding, drop new speculative ones. The real
    * {@link #load} path is deliberately <b>not</b> gated 鈥?it must never be refused, and it already
    * degrades correctly when the queue is full (it runs the download inline on the calling thread).
    *
    * <p>Tuning: {@code -Dtellus.download.prefetchHighWater=N} (0 disables the gate, restoring the old
    * unbounded behaviour).
    */
   private static final int PREFETCH_HIGH_WATER = intProperty("tellus.download.prefetchHighWater", 256);
   private static final AtomicLong PREFETCH_DROPPED = new AtomicLong();

   /** In-flight downloads: {@code domain|key -> Flight} (the same flight can be awaited by multiple callers 鉁?. */
   private static final ConcurrentHashMap<String, Flight<?, ?>> IN_FLIGHT = new ConcurrentHashMap<>();

   private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(THREADS, THREADS,
         60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
            Thread thread = new Thread(runnable, "tellus-download");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
         });

   private static final AtomicLong SUBMITTED = new AtomicLong();
   private static final AtomicLong DEDUPED = new AtomicLong();
   private static final AtomicLong COMPLETED = new AtomicLong();
   private static final AtomicLong FAILED = new AtomicLong();

   // 鈽呪槄 Wall-clock deadline + circuit breaker (2026-09-20 fix for "can't enter the world for 10 minutes" 鈽呪槄
   //   Symptom: Server thread stuck at FutureTask.get 鈫?TellusDownloadExecutor.await,
   //        while the download thread was stuck on a socket read for 694 seconds 鉁? log frozen at "preparing to generate area 16%" 鉁?
   //   Cause: upstream uses HttpURLConnection.setReadTimeout(8000) 鉁?
   //        but **slow-drip** 鈥?data trickles in occasionally, each small read resets the timer 鉁?鈫?the 8-second timeout is effectively useless 鉁?
   //   鈬?A wall-clock hard limit must be added at the "task" level 鉁? on timeout, give up and let the caller use the fallback (MISSING) 鉁?
   private static final int TASK_TIMEOUT_SECONDS = intProperty("tellus.download.timeoutSeconds", 90);
   /** Circuit breaker after this many consecutive failures 鉁? when the network is entirely down, stop waiting tile by tile 鉁?*/
   private static final int FAIL_THRESHOLD = intProperty("tellus.download.failThreshold", 8);
   private static final int COOLDOWN_SECONDS = intProperty("tellus.download.cooldownSeconds", 120);
   private static final AtomicLong TIMED_OUT = new AtomicLong();
   private static final AtomicLong CANCELLED = new AtomicLong();
   private static final AtomicLong CIRCUIT_OPENED = new AtomicLong();
   private static final AtomicLong TRANSIENT_RETRY_OK = new AtomicLong();
   private static final AtomicLong TRANSIENT_GAVE_UP = new AtomicLong();

   /**
    * 鈽呪槄 2026-10-10: retry the failures that are <b>not the server saying no</b>, before counting them.
    *
    * <h2>What the real log showed</h2>
    * A single session reported {@code submitted 1223, completed 881, failed 324} 鈥?a <b>26% failure rate</b>
    * 鈥?and three different circuits tripped within nine minutes. The actual exception was:
    * <pre>
    * javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
    *     at PmTilesRangeReader.readBytes(:219) <- TellusOsmSandSource.ensureInitialized(:299)
    * </pre>
    * plus bare {@code SocketTimeoutException}s and {@code download cancelled} entries. None of these are
    * "this tile does not exist" 鈥?they are TLS/socket level noise that a second attempt usually clears.
    *
    * <h2>Why retrying matters more here than in a normal HTTP client</h2>
    * A failed tile is not just a slow load: it feeds the circuit breaker. Eight of them in a row silence
    * the whole domain for 120 s, and anything generated during that window falls back to approximate
    * terrain 鈥?which is exactly how "squares" and "bumps" get baked into a world permanently, because
    * generated chunks are saved. Paying 3 attempts to avoid a false trip is far cheaper than that.
    *
    * <h2>What is NOT retried</h2>
    * {@code Download was cancelled} (we cancelled it ourselves 鈥?see the {@code CancellationException}
    * branch in {@link #await}) and the circuit-open marker (retrying a deliberately open circuit is a
    * busy loop). Both return immediately.
    *
    * <p>Tuning: {@code -Dtellus.download.retries=N} (0 disables), {@code -Dtellus.download.retryBackoffMs=N}.
    */
   private static final int TRANSIENT_RETRIES = intProperty("tellus.download.retries", 3);
   private static final long TRANSIENT_RETRY_BACKOFF_MS = intProperty("tellus.download.retryBackoffMs", 250);
   /**
    * 鈽呪槄 2026-10-04: circuit-breaker counter changed from "one global" to **per-domain bucketing** 鉁?
    *
    * <p>Previously {@code CONSECUTIVE_FAILURES} was a single counter across all domain/keys 鉁?鈥?
    * any single mirror failing 8 times in a row would trip the circuit for the **entire download pool**
    * for 120 seconds 鉁? taking down even completely healthy DEM/OSM sources 鉁?
    * degrading large areas of terrain to fallback heights.
    *
    * <p>Now each domain counts and trips independently 鉁? a bad mirror only silences itself,
     * without dragging others down with it 鉁?
     */
   private static final ConcurrentHashMap<String, AtomicLong> FAILURES_BY_DOMAIN = new ConcurrentHashMap<>();
   /** Per-domain circuit-breaker deadline (based on {@code System.nanoTime()}). */
   private static final ConcurrentHashMap<String, Long> CIRCUIT_UNTIL_NANOS = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<String, Boolean> CIRCUIT_WARNED = new ConcurrentHashMap<>();

   static {
      POOL.allowCoreThreadTimeOut(true);
   }

   private TellusDownloadExecutor() {
   }

   /** Shape of a download task: may throw checked exceptions, can be fed directly to Guava's CacheLoader. */
   public interface Loader<K, V> {
      V load(K key) throws Exception;
   }

   /**
    * One in-flight download 鉁? the task itself + the number of threads waiting for it 鉁?
    *
    * <p>Why count "who is waiting": concurrent loads of the same key are merged into the same
    * flight (single-flight 鉁?. When a timed-out party wants to cancel the task it must confirm
    * that **no one else is still waiting** 鉁?鈥?
    * otherwise it would turn others' {@code Future.get} into {@link CancellationException}
     * (unchecked exception 鈫?chunk generation crash 鉁?.
     */
   private static final class Flight<K, V> {
      private final String key;
      private final String domain;
      private final AtomicInteger waiters = new AtomicInteger();
      private final FutureTask<V> task;

      private Flight(String key, String domain, K loaderKey, Loader<K, V> loader) {
         this.key = key;
         this.domain = domain;
         this.task = new FutureTask<>(() -> {
            ON_POOL.set(Boolean.TRUE);
            try {
               V value = loader.load(loaderKey);
               COMPLETED.incrementAndGet();
               noteSuccess(domain);
               return value;
            } catch (Exception e) {
               FAILED.incrementAndGet();
               noteFailure(domain, domain + ':' + loaderKey);
               throw e;
            } finally {
               ON_POOL.remove();
               // 鈽?Only remove our own flight 鉁?(remove(key,value)):
               //   someone else may have already queued a new flight for the same key; don't remove the new one too 鉁?
               IN_FLIGHT.remove(this.key, this);
            }
         });
      }
   }

   /**
    * Execute {@code loader} on the download pool, merging concurrent calls for the same {@code domain|key}.
    *
    * <p>The calling thread blocks waiting for the result (required by Guava LoadingCache semantics),
    * but HTTP runs on pool threads; when N threads wait for N different tiles they download in
    * parallel. Pool threads calling this method run it inline.
     */
   public static <K, V> V load(String domain, K key, Loader<K, V> loader) throws Exception {
      if (Boolean.TRUE.equals(ON_POOL.get())) {
         return loader.load(key);
      }
      // 2026-10-10: retry transient (TLS/socket) failures -- see TRANSIENT_RETRIES.
      int attempts = Math.max(1, TRANSIENT_RETRIES + 1);
      Exception last = null;

      for (int attempt = 0; attempt < attempts; attempt++) {
         // Circuit open: fail immediately and let the caller use the fallback value 鉁?(when this domain is broken, this is the only way to avoid hanging generation)
         if (isCircuitOpen(domain)) {
            throw new java.io.IOException("Download circuit breaker is open (" + domain + " failed " + FAIL_THRESHOLD + " times consecutively), skipping " + domain + ':' + key);
         }
         Flight<?, ?> flight = submit(domain, key, loader);
         if (flight == null) {
            // Queue full: fall back to downloading directly on the calling thread (preserves original semantics, no data loss)
            ON_POOL.set(Boolean.TRUE);
            try {
               return loader.load(key);
            } finally {
               ON_POOL.remove();
            }
         }

         try {
            V value = await(domain, flight);
            if (attempt > 0) {
               TRANSIENT_RETRY_OK.incrementAndGet();
            }
            return value;
         } catch (Exception error) {
            if (attempt == attempts - 1 || !isRetryable(error)) {
               if (attempt > 0) {
                  TRANSIENT_GAVE_UP.incrementAndGet();
               }
               throw error;
            }

            last = error;
            // Wait a little: an immediate re-handshake against the same overloaded endpoint tends to fail the same way.
            try {
               Thread.sleep(TRANSIENT_RETRY_BACKOFF_MS * (attempt + 1));
            } catch (InterruptedException interrupted) {
               Thread.currentThread().interrupt();
               throw error;
            }
         }
      }

      throw last != null ? last : new java.io.IOException("download failed without an exception");
   }

   /**
    * Whether an error is worth another attempt.
    *
    * <p>Retryable = transport-level trouble: TLS handshakes cut short, socket timeouts, connection resets,
    * HTTP 5xx/429. Not retryable = the server gave a definite answer (HTTP 4xx other than 429), the caller
    * cancelled us, or the circuit is deliberately open.
    */
   private static boolean isRetryable(Throwable error) {
      for (Throwable t = error; t != null; t = t.getCause()) {
         if (t instanceof java.util.concurrent.CancellationException) {
            return false;
         }

         String message = t.getMessage();
         if (message != null) {
            // Our own markers: never worth retrying, and retrying the open one would be a busy loop.
            if (message.startsWith("Download was cancelled") || message.startsWith("Download circuit breaker is open")) {
               return false;
            }
         }

         if (t instanceof javax.net.ssl.SSLException
               || t instanceof java.net.SocketTimeoutException
               || t instanceof java.net.SocketException
               || t instanceof java.net.ConnectException
               || t instanceof java.net.UnknownHostException
               || t instanceof javax.net.ssl.SSLHandshakeException) {
            return true;
         }

         if (t instanceof java.io.IOException) {
            String m = t.getMessage() == null ? "" : t.getMessage();
            if (m.contains("handshake") || m.contains("Connection reset") || m.contains("timed out")
                  || m.contains("Broken pipe") || m.contains("GOAWAY")) {
               return true;
            }
            // "Download did not complete within Ns" -- the socket produced nothing in 90 s; one more try is cheap.
            if (m.startsWith("Download did not complete within")) {
               return true;
            }
            if (t.getClass() == java.io.IOException.class && (m.contains("500") || m.contains("502") || m.contains("503") || m.contains("429"))) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Submit without waiting, for "prefetch" use.
    *
    * <p>Upstream's {@code prefetchTile} is a synchronous {@code cache.get}; one prefetch round means
    * dozens of serial downloads. Here we queue it in the background and return immediately.
    */
   public static <K, V> void prefetch(String domain, K key, Loader<K, V> loader) {
      if (isCircuitOpen(domain)) {
         return;
      }
      // 2026-10-10: speculative work must not crowd out blocking work -- see PREFETCH_HIGH_WATER.
      if (PREFETCH_HIGH_WATER > 0 && IN_FLIGHT.size() >= PREFETCH_HIGH_WATER) {
         PREFETCH_DROPPED.incrementAndGet();
         return;
      }
      if (!IN_FLIGHT.containsKey(domain + '|' + key)) {
         submit(domain, key, loader);
      } else {
         DEDUPED.incrementAndGet();
      }
   }

   /** Submit a task; returns null if the queue is full (caller decides whether to fall back to sync or give up). */
   private static <K, V> Flight<?, ?> submit(String domain, K key, Loader<K, V> loader) {
      String flightKey = domain + '|' + key;
      Flight<?, ?> running = IN_FLIGHT.get(flightKey);
      if (running != null) {
         DEDUPED.incrementAndGet();
         return running;
      }

      Flight<K, V> flight = new Flight<>(flightKey, domain, key, loader);

      Flight<?, ?> raced = IN_FLIGHT.putIfAbsent(flightKey, flight);
      if (raced != null) {
         DEDUPED.incrementAndGet();
         return raced;
      }

      SUBMITTED.incrementAndGet();
      try {
         POOL.execute(flight.task);
      } catch (RejectedExecutionException rejected) {
         IN_FLIGHT.remove(flightKey, flight);
         FAILED.incrementAndGet();
         Tellus.LOGGER.debug("Download queue is full: {}:{} handling on calling thread instead", domain, key);
         return null;
      }
      return flight;
   }

   @SuppressWarnings("unchecked")
   private static <V> V await(String domain, Flight<?, ?> flight) throws Exception {
      flight.waiters.incrementAndGet();
      try {
         // 鈽?Bounded wait 鉁? never allow the calling thread (often the Server thread) to hang on a Future indefinitely
         return (V) flight.task.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
         TIMED_OUT.incrementAndGet();
         // 鈽?Attribute to **this domain** 鉁? a slow source should only trip its own circuit, not drag other sources down 鉁?
         noteFailure(domain, "timed-out task");
         throw new java.io.IOException("Download did not complete within " + TASK_TIMEOUT_SECONDS + " seconds, giving up and using fallback value");
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw e;
      } catch (CancellationException e) {
         // 鈽呪槄 2026-09-26: cancellation must never leave this layer as an "unchecked exception" 鉁?
         //   (it was the root cause of this "Exception generating new chunk" 鈫?client crash 鉁?
         //
         // 鈽呪槄 2026-10-10: it must not count toward the circuit breaker either 鉁?
         //   Cancellation is issued by *us* -- see the finally block below: when the last waiter leaves
         //   before the task finished, we cancel it ourselves. Counting that as a "server failure" means
         //   the breaker trips on our own bookkeeping, and a tripped breaker makes every later tile of the
         //   domain fall back to approximate terrain for 120 s (which bakes permanent artifacts into the
         //   saved world). Measured 2026-10-10: 19 cancellations, and the log shows circuits opening with
         //   `latest: download cancelled` -- i.e. tripped by exactly this.
         //
         //   The download is still reported as failed to the caller (unchanged behaviour), it just no
         //   longer poisons the health signal of a domain that may be perfectly healthy.
         CANCELLED.incrementAndGet();
         throw new java.io.IOException("Download was cancelled, using fallback value instead");
      } catch (java.util.concurrent.ExecutionException e) {
         Throwable cause = e.getCause();
         if (cause instanceof Exception ex) {
            throw ex;
         }
         throw new RuntimeException(cause);
      } finally {
         // 鈽?Only allow cancellation after the last waiter has left 鉁?
         //   cancelling while others are still waiting would turn them all into CancellationException 鉁?
         if (flight.waiters.decrementAndGet() <= 0 && !flight.task.isDone()) {
            // 2026-10-11: remove the flight from IN_FLIGHT **before** cancelling it.
            //
            //   The removal used to live only inside the FutureTask's callable (see the `finally` in
            //   Flight's constructor). That callable does not run when the task is cancelled *before a
            //   pool thread picks it up* -- FutureTask.cancel on a queued task just flips the state to
            //   CANCELLED and the body is never entered. The flight therefore stayed in IN_FLIGHT
            //   forever, and every later request for that same key hit it via submit()'s dedup branch
            //   and failed instantly with CancellationException. The tile could then never be
            //   downloaded again for the lifetime of the process.
            //
            //   Measured 2026-10-11 11:39:37 -> 11:45:22 (six minutes, first world load):
            //     submitted 1832, deduped 67007, completed 1639, failed 21, queued 158,
            //     timed out 0, cancelled 66675
            //   `deduped` and `cancelled` being almost equal is the signature: nearly every deduped
            //   request was joining a flight that was already dead. (Cancelled is counted per *waiter*,
            //   not per task, which is why it dwarfs `submitted`.)
            //
            //   Removing here is enough on its own: when the task IS already running, its own finally
            //   still removes it too, and that remove is identity-guarded (remove(key, this)) so it
            //   cannot evict a newer flight for the same key.
            IN_FLIGHT.remove(flight.key, flight);
            flight.task.cancel(true);
         }
      }
   }

   /** Whether the circuit breaker for a domain is on 鉁?(when on, no more network tasks are submitted for it) */
   public static boolean isCircuitOpen(String domain) {
      Long until = CIRCUIT_UNTIL_NANOS.get(domain);
      if (until == null) {
         return false;
      }
      if (System.nanoTime() < until) {
         return true;
      }
      // Cooldown over: clear this domain's state 鉁?(other domains are unaffected 鉁?
      CIRCUIT_UNTIL_NANOS.remove(domain, until);
      CIRCUIT_WARNED.remove(domain);
      FAILURES_BY_DOMAIN.remove(domain);
      return false;
   }

   /** Current consecutive failure count for this domain (used by {@link #describe()}). */
   private static long consecutiveFailures(String domain) {
      AtomicLong counter = FAILURES_BY_DOMAIN.get(domain);
      return counter == null ? 0L : counter.get();
   }

   /** A successful download for a domain 鈬?only clear its own failure count 鉁?(no longer clear other domains along with it 鉁? */
   private static void noteSuccess(String domain) {
      FAILURES_BY_DOMAIN.remove(domain);
      CIRCUIT_WARNED.remove(domain);
   }

   /**
    * Record a failure; when the <b>same domain</b> reaches the threshold of consecutive failures,
    * only its own circuit is tripped for a while 鉁?
    * (prevents one broken mirror from taking down healthy DEM/OSM sources along with it 鉁?
     */
   private static void noteFailure(String domain, String what) {
      long failures = FAILURES_BY_DOMAIN
            .computeIfAbsent(domain, ignored -> new AtomicLong())
            .incrementAndGet();
      if (failures >= FAIL_THRESHOLD && !isCircuitOpen(domain)) {
         CIRCUIT_UNTIL_NANOS.put(domain, System.nanoTime() + COOLDOWN_SECONDS * 1_000_000_000L);
         CIRCUIT_OPENED.incrementAndGet();
         if (CIRCUIT_WARNED.putIfAbsent(domain, Boolean.TRUE) == null) {
            Tellus.LOGGER.warn("[TellusCN] {} consecutive download failures in domain {} (latest: {}) -> that domain's circuit OPEN for {}s;"
               + " other domains keep working, generation falls back instead of blocking"
               + " (-Dtellus.download.failThreshold / cooldownSeconds to tune)", failures, domain, what, COOLDOWN_SECONDS);
         }
      }
   }

   /** One-line statistics, used to verify that multi-threaded downloading is working. */
   public static String describe() {
      StringBuilder breakers = new StringBuilder();
      for (String domain : FAILURES_BY_DOMAIN.keySet()) {
         if (breakers.length() > 0) {
            breakers.append(", ");
         }
         breakers.append(domain).append('=')
               .append(consecutiveFailures(domain))
               .append(isCircuitOpen(domain) ? "(OPEN)" : "");
      }
      return String.format(java.util.Locale.ROOT,
            "Download pool %d threads: submitted %d, deduped %d, completed %d, failed %d, in-flight %d, queued %d, timed out %d, cancelled %d, circuit trips %d, prefetch dropped %d, transient retried %d, gave up after retry %d, per-domain consecutive failures {%s}",
            THREADS, SUBMITTED.get(), DEDUPED.get(), COMPLETED.get(), FAILED.get(),
            IN_FLIGHT.size(), POOL.getQueue().size(), TIMED_OUT.get(), CANCELLED.get(),
            CIRCUIT_OPENED.get(), PREFETCH_DROPPED.get(), TRANSIENT_RETRY_OK.get(), TRANSIENT_GAVE_UP.get(), breakers);
   }

   public static int threads() {
      return THREADS;
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         return Math.max(1, Integer.parseInt(value));
      } catch (NumberFormatException ignored) {
         return defaultValue;
      }
   }
}
