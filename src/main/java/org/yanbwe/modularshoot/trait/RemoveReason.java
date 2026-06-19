package org.yanbwe.modularshoot.trait;

/**
 * Explains why a bullet was removed from the {@code BulletManager}.
 *
 * <p>Passed as the third argument to
 * {@link TraitCallbacks.TraitRemoveCallback#onRemove} so that listeners can
 * distinguish removal scenarios without inspecting bullet state. The
 * framework guarantees that {@code ON_REMOVE} is the last hook fired for a
 * bullet; after all registered callbacks have run the bullet record is
 * discarded (设计文档 §RemoveReason 枚举值).</p>
 *
 * <p>The special relationship between {@link #EXPIRED} and
 * {@link TraitHookType#ON_EXPIRE}: when a bullet is removed because it
 * exceeded its {@code range}, {@code ON_EXPIRE} fires first (semantic: the
 * bullet's life has ended), then {@code ON_REMOVE} fires with
 * {@code EXPIRED} (semantic: the bullet is being evicted from the manager).
 * All other reasons fire only {@code ON_REMOVE}.</p>
 *
 * @see TraitCallbacks.TraitRemoveCallback
 * @see TraitHookType#ON_REMOVE
 */
public enum RemoveReason {
    /**
     * The bullet hit an entity and its {@code entity_penetration} count is
     * exhausted (no penetration attribute or all penetration charges used).
     *
     * <p>The bullet disappears immediately after the
     * {@link TraitHookType#ON_HIT} callbacks for this hit have completed.</p>
     */
    HIT_ENTITY,

    /**
     * The bullet hit a block and its {@code block_penetration} count is
     * exhausted (no penetration attribute or all penetration charges used).
     *
     * <p>The bullet disappears immediately after the
     * {@link TraitHookType#ON_BLOCK_HIT} callbacks for this impact have
     * completed.</p>
     */
    HIT_BLOCK,

    /**
     * The bullet exceeded its {@code range} attribute and its lifetime has
     * ended.
     *
     * <p>For this reason — and only this reason — both
     * {@link TraitHookType#ON_EXPIRE} and {@link TraitHookType#ON_REMOVE}
     * fire, in that order. Listeners that need to distinguish "lifetime
     * expired" from other removal causes should check for this value in
     * their {@code ON_REMOVE} callback.</p>
     */
    EXPIRED,

    /**
     * The bullet advanced into an unloaded chunk and was removed without
     * forcing the chunk to load.
     *
     * <p>The framework never force-loads chunks for bullet simulation; a
     * bullet that leaves all loaded chunks is silently dropped to avoid
     * simulating in unloaded space.</p>
     */
    UNLOADED_CHUNK,

    /**
     * The bullet was explicitly removed by the {@code BulletManager} via an
     * administrative or API call.
     *
     * <p>Examples: a clear-all command, a scripted cleanup API, or a mod
     * calling the manager's removal method directly. This reason is never
     * produced by the normal flight simulation path.</p>
     */
    MANUAL,

    /**
     * The dimension the bullet inhabits was unloaded, causing every bullet
     * in that dimension to be removed.
     *
     * <p>Bullets are dimension-scoped; when the dimension tears down its
     * associated {@code BulletManager} instance all remaining bullets are
     * evicted with this reason.</p>
     */
    DIMENSION_UNLOAD
}
