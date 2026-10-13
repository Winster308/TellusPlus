package com.yucareux.tellus.client.compat;

/**
 * 1.21.1 compatibility shim for {@code com.yucareux.tellus.client.compat.KeyEvent}.
 *
 * <p>See {@link MouseButtonEvent} for why this exists. 1.21.1's GUI methods still take
 * {@code keyPressed(int keyCode, int scanCode, int modifiers)}; the only member the Tellus code
 * reads is {@code key()}, but {@code scancode()} and {@code modifiers()} are declared so that a
 * {@code super.keyPressed(event)} style call can be translated without special-casing.
 */
public record KeyEvent(int key, int scancode, int modifiers) {
}
