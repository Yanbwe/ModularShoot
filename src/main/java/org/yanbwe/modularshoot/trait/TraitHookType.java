package org.yanbwe.modularshoot.trait;

/**
 * Identifies a bullet lifecycle hook point that trait callbacks can attach to.
 *
 * <p>The framework does not bake any trait behaviour into the bullet engine.
 * Instead it exposes these hook points so that third-party mods can register
 * callbacks via {@link TraitHookRegistry} and implement arbitrary runtime
 * logic (tracking rounds, ramping damage, particle bursts, etc.) (设计文档
 * §特性运行时钩子).</p>
 *
 * <p>Each constant maps 1:1 to a functional interface declared in
 * {@link TraitCallbacks}. The expected callback type for a given hook type is
 * validated at registration time by {@link TraitHookRegistry#register}.</p>
 *
 * <table>
 *   <caption>Hook types and their callback signatures</caption>
 *   <tr><th>Constant</th><th>Callback interface</th><th>Triggered when</th></tr>
 *   <tr><td>{@link #ON_TICK}</td><td>{@link TraitCallbacks.TraitTickCallback}</td>
 *       <td>Every server tick while the bullet is alive</td></tr>
 *   <tr><td>{@link #ON_HIT}</td><td>{@link TraitCallbacks.TraitHitCallback}</td>
 *       <td>The bullet hits a living entity</td></tr>
 *   <tr><td>{@link #ON_BLOCK_HIT}</td>
 *       <td>{@link TraitCallbacks.TraitBlockHitCallback}</td>
 *       <td>The bullet hits a block face</td></tr>
 *   <tr><td>{@link #ON_EXPIRE}</td>
 *       <td>{@link TraitCallbacks.TraitExpireCallback}</td>
 *       <td>The bullet exceeds its {@code range} and its life ends</td></tr>
 *   <tr><td>{@link #ON_REMOVE}</td>
 *       <td>{@link TraitCallbacks.TraitRemoveCallback}</td>
 *       <td>The bullet is removed from the manager for any reason</td></tr>
 *   <tr><td>{@link #ON_VISUAL_TICK}</td>
 *       <td>{@link TraitCallbacks.TraitVisualTickCallback}</td>
 *       <td>Every client render frame before the bullet is drawn
 *           (client-side only)</td></tr>
 * </table>
 *
 * @see TraitCallbacks
 * @see TraitHookRegistry
 * @see RemoveReason
 */
public enum TraitHookType {
    /**
     * Fired every server tick for each active bullet.
     *
     * <p>Receives the live {@link org.yanbwe.modularshoot.bullet.BulletRecord}
     * (mutable position/direction) and the
     * {@link org.yanbwe.modularshoot.bullet.BulletSnapshot} (mutable stats).
     * Hooks may modify either to implement custom trajectories or ramping
     * attributes (设计文档 §onTick).</p>
     */
    ON_TICK,

    /**
     * Fired when a bullet collides with an entity.
     *
     * <p>Receives the bullet record, snapshot and the hit
     * {@link net.minecraft.world.entity.Entity}. Damage application happens
     * after all {@code ON_HIT} callbacks for this bullet have run, so hooks
     * may adjust {@code hit_damage} or the damage type in-flight (设计文档
     * §onHit).</p>
     */
    ON_HIT,

    /**
     * Fired when a bullet collides with a block face.
     *
     * <p>Receives the bullet record, snapshot, the
     * {@link net.minecraft.core.BlockPos} of the hit block and the
     * {@link net.minecraft.core.Direction} of the impacted face. Hooks may
     * use this to spawn impact effects or modify penetration behaviour
     * (设计文档 §onBlockHit).</p>
     */
    ON_BLOCK_HIT,

    /**
     * Fired when a bullet exceeds its {@code range} and its life ends.
     *
     * <p>This is the "lifetime expired" specialist hook. When the bullet is
     * subsequently removed from the manager, {@link #ON_REMOVE} fires with
     * {@link RemoveReason#EXPIRED} — the two fire in order:
     * {@code ON_EXPIRE} first, then {@code ON_REMOVE}. Other removal scenarios
     * (hit, unloaded chunk, etc.) fire only {@code ON_REMOVE} (设计文档
     * §onExpire 与 onRemove 的触发关系).</p>
     */
    ON_EXPIRE,

    /**
     * Fired before a bullet is removed from the manager for any reason.
     *
     * <p>Receives the bullet record, snapshot and a {@link RemoveReason}
     * explaining why the bullet is being removed. This is the universal
     * cleanup hook; listeners that need to release resources in all removal
     * scenarios should listen here rather than to each specific hook
     * (设计文档 §onRemove).</p>
     */
    ON_REMOVE,

    /**
     * Fired every client render frame before a bullet is drawn
     * (设计文档 §特性视觉钩子, line 1269).
     *
     * <p>This is the only hook that runs <strong>client-side only</strong>;
     * all other hook types fire on the server. It triggers once per render
     * frame (not per server tick), so it runs at a higher frequency than
     * {@link #ON_TICK} and is suited to smooth visual changes.</p>
     *
     * <p>The callback receives the client-side {@code BulletRenderObject},
     * passed as {@link Object} to keep this common module free of client-class
     * dependencies, and may mutate its texture, scale and render mode
     * in-flight. See {@link TraitCallbacks.TraitVisualTickCallback} for the
     * cast contract and {@code VisualTickHookDispatcher} for the client-side
     * dispatch entry point.</p>
     */
    ON_VISUAL_TICK
}
