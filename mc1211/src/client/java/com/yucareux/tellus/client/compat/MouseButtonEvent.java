package com.yucareux.tellus.client.compat;

/**
 * 1.21.1 compatibility shim for {@code com.yucareux.tellus.client.compat.MouseButtonEvent}.
 *
 * <p>1.21.9 replaced the primitive mouse-event parameters of the GUI event methods
 * ({@code mouseClicked(double mouseX, double mouseY, int button)} and friends) with record
 * parameters ({@code MouseButtonEvent}, {@code KeyEvent}). 1.21.1 still uses the primitives.
 *
 * <p>Rather than rewriting ~70 call sites that read {@code event.x()}, {@code event.y()} and
 * {@code event.button()}, the port keeps a record with the same accessor names and constructs it at
 * the boundary of each GUI override. Only the three accessors the Tellus code actually uses are
 * declared (verified by grep over the whole client source tree: no other member is referenced).
 *
 * <p>This is a Tellus-side class. It deliberately does not reuse the 1.21.9 type name in the
 * {@code net.minecraft} namespace, so it can never be confused with a real Minecraft class.
 */
public record MouseButtonEvent(double x, double y, int button) {
}
