package com.yucareux.tellus.integration.voxy;

/**
 * The <b>host</b> of the Voxy pregeneration manager (2026-09-23).
 *
 * <h2>Why this class exists (learned the hard way)</h2>
 * At first I put the singleton straight into the mixin class ({@code VoxyServerTickMixin}), which
 * compiled fine and <b>crashed immediately at runtime</b>:
 * <pre>
 * InvalidMixinException: Mixin tellus.mixins.json:voxy.VoxyServerTickMixin
 *     from mod telluscn contains **non-private static method** manager()
 * </pre>
 * The reason is Mixin's <b>hard constraint</b>: non-private members are <b>never allowed</b> in a mixin
 * class, and adding {@code @Unique} does not help ({@code @Unique} only applies to private / package-private).
 *
 * <h2>The correct split</h2>
 * <ul>
 *   <li><b>This class</b>, a plain class with no Mixin constraint, holds the <b>state</b> (the manager instance)</li>
 *   <li><b>{@code VoxyServerTickMixin}</b>, a mixin, only does the <b>hooking</b>: it calls {@link #get()}</li>
 * </ul>
 * That keeps the mixin "thin" (one injection point plus one call) while
 * <b>anyone can safely reach the state</b> (commands, the config panel and the mixin all go through {@link #get()}).
 */
public final class VoxyPregenHost {

   /**
    * The one and only pregeneration manager.
    *
    * <p>It used to be in {@code Tellus.java:120}, then moved into a mixin (which does not work), and is
    * now settled here, because this is where it belongs: a plain class with no Mixin constraint,
    * reachable by anyone.
    */
   private static final TellusVoxyPregenManager MANAGER = new TellusVoxyPregenManager();

   private VoxyPregenHost() {
   }

   /** Returns the manager. Commands, the config panel and the mixin all go through here. */
   public static TellusVoxyPregenManager get() {
      return MANAGER;
   }
}
