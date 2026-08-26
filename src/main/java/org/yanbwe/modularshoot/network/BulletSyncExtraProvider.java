package org.yanbwe.modularshoot.network;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * Server-side contributor of third-party extension bytes carried by the
 * bullet sync packets (审查 E4 — {@code DeltaBulletEntry}/full entries gain
 * an opaque extension channel).
 *
 * <p>The framework sync wire format carries only position/direction/style;
 * mods that attach their own per-bullet runtime fields (rotation speed,
 * orientation angles, custom effect timers) register a provider here. The
 * collected bytes travel with both full and delta entries and are exposed to
 * client-side consumers (render object accessors, visual-tick hooks) via
 * {@link BulletSyncExtraRegistry#split(byte[])}.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Called on the server thread for every synced bullet (full entries
 *       each transmission, delta entries whenever an update is sent) — keep
 *       the implementation allocation-light and O(1).</li>
 *   <li>Return {@code null} or an empty array when this bullet carries no
 *       data for the provider; nothing is then written for it.</li>
 *   <li>Exceptions are caught, logged and skipped — a faulty provider must
 *       not break bullet sync.</li>
 *   <li>The provider's registration index is its wire identity; client-side
 *       consumers split the payload by index, so both sides must register
 *       providers in the same relative order (same mod list — the usual
 *       Neo-to-Neo guarantee).</li>
 * </ul>
 */
@FunctionalInterface
public interface BulletSyncExtraProvider {

    /**
     * Collects this provider's extension bytes for the given bullet.
     *
     * @param bullet the server-side bullet being synced; never {@code null}
     * @return the bytes to carry, or {@code null}/empty for none
     */
    @Nullable
    byte[] collect(BulletRecord bullet);
}
