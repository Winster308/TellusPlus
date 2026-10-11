package com.yucareux.tellus.world.data.source;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone reproduction of the {@code IN_FLIGHT} leak in {@link TellusDownloadExecutor}, written so the
 * bug can be demonstrated <b>without Minecraft on the classpath</b>.
 *
 * <h2>What is being tested</h2>
 *
 * <p>{@code Flight}'s constructor removes itself from {@code IN_FLIGHT} inside the {@code finally} of its
 * {@link FutureTask} callable. That callable <b>never runs</b> if the task is cancelled while still queued:
 * {@code FutureTask.cancel} on a not-yet-started task just flips its state to CANCELLED. So the flight stays
 * in the map forever, and every later request for the same key joins it through {@code submit()}'s dedup
 * branch and fails instantly with {@link CancellationException}.
 *
 * <h2>Why this file exists rather than a unit test in the mod</h2>
 *
 * <p>The executor's real code depends on Minecraft's {@code LOGGER} and on Guava, so it cannot be loaded in
 * a bare JVM. This file reproduces the <b>exact lifecycle</b> (single-flight map + waiter count + cancel
 * on last waiter leaving) with plain JDK types, so the defect and its fix can be observed directly.
 *
 * <p>Run it with {@code java TellusFlightLeakRepro.java} (single-file source launch, JDK 11+).
 */
public final class TellusFlightLeakRepro {

   /** Mirrors Flight: the removal lives inside the callable's finally. */
   static final class OldFlight {
      final String key;
      final AtomicInteger waiters = new AtomicInteger();
      final FutureTask<String> task;

      OldFlight(String key, ConcurrentHashMap<String, OldFlight> inFlight, boolean block) {
         this.key = key;
         this.task = new FutureTask<>(() -> {
            try {
               if (block) {
                  Thread.sleep(10_000);
               }
               return "value";
            } finally {
               // <-- exactly where the real code had it: only reachable if the callable runs
               inFlight.remove(this.key, this);
            }
         });
      }
   }

   /** Mirrors the fix: removal happens on the cancel path too, before cancel(). */
   static final class NewFlight {
      final String key;
      final AtomicInteger waiters = new AtomicInteger();
      final FutureTask<String> task;

      NewFlight(String key, ConcurrentHashMap<String, NewFlight> inFlight, boolean block) {
         this.key = key;
         this.task = new FutureTask<>(() -> {
            try {
               if (block) {
                  Thread.sleep(10_000);
               }
               return "value";
            } finally {
               inFlight.remove(this.key, this);
            }
         });
      }

      void cancelAndEvict(ConcurrentHashMap<String, NewFlight> inFlight) {
         inFlight.remove(this.key, this);   // <-- the fix
         this.task.cancel(true);
      }
   }

   public static void main(String[] args) throws Exception {
      System.out.println("=== IN_FLIGHT leak reproduction ===\n");

      // A pool with ONE thread, already busy, so the second task is guaranteed to sit in the queue.
      ThreadPoolExecutor pool = new ThreadPoolExecutor(
         1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));

      // ---------------------------------------------------------------- OLD behaviour
      ConcurrentHashMap<String, OldFlight> oldMap = new ConcurrentHashMap<>();
      pool.execute(() -> { try { Thread.sleep(2_000); } catch (InterruptedException ignored) { } }); // occupy the thread

      String key = "demo|tile";
      OldFlight queued = new OldFlight(key, oldMap, false);
      oldMap.put(key, queued);
      pool.execute(queued.task);                        // sits in the queue, callable never entered
      Thread.sleep(150);
      boolean wasCancelled = queued.task.cancel(true);  // what await()'s finally does
      Thread.sleep(150);

      System.out.println("[OLD] task cancelled while queued : " + wasCancelled);
      System.out.println("[OLD] still in IN_FLIGHT          : " + oldMap.containsKey(key));
      System.out.println("[OLD] -> every later request for this key now fails instantly");

      // Demonstrate the consequence: a second request joins the dead flight.
      OldFlight joined = oldMap.get(key);
      String outcome;
      try {
         joined.task.get(200, TimeUnit.MILLISECONDS);
         outcome = "returned a value (unexpected)";
      } catch (CancellationException e) {
         outcome = "CancellationException  <-- tile can never be downloaded again";
      } catch (Exception e) {
         outcome = e.getClass().getSimpleName();
      }
      System.out.println("[OLD] second request for same key   : " + outcome);

      // ---------------------------------------------------------------- NEW behaviour
      ConcurrentHashMap<String, NewFlight> newMap = new ConcurrentHashMap<>();
      String key2 = "demo|tile2";
      NewFlight queued2 = new NewFlight(key2, newMap, false);
      newMap.put(key2, queued2);
      pool.execute(queued2.task);
      Thread.sleep(150);
      queued2.cancelAndEvict(newMap);
      Thread.sleep(150);

      System.out.println();
      System.out.println("[NEW] task cancelled while queued : " + queued2.task.isCancelled());
      System.out.println("[NEW] still in IN_FLIGHT          : " + newMap.containsKey(key2));
      System.out.println("[NEW] -> a later request can submit a fresh download");

      // ---------------------------------------------------------------- verdict
      boolean leakInOld = oldMap.containsKey(key);
      boolean goneInNew = !newMap.containsKey(key2);

      System.out.println();
      System.out.println("=== VERDICT ===");
      System.out.println("  leak reproduced with the old ordering : " + leakInOld);
      System.out.println("  leak absent  with the new ordering    : " + goneInNew);
      System.out.println("  => fix " + (leakInOld && goneInNew ? "CONFIRMED" : "NOT confirmed"));

      pool.shutdownNow();
      if (!(leakInOld && goneInNew)) {
         throw new AssertionError("reproduction did not behave as expected");
      }
   }
}
