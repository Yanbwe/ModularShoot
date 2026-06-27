package org.yanbwe.modularshoot.client.render;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.client.ClientBulletSnapshot;
import org.yanbwe.modularshoot.trait.TraitCallbacks;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;
import org.yanbwe.modularshoot.trait.TraitHookType;

/**
 * Client-side dispatcher for the {@link TraitHookType#ON_VISUAL_TICK} trait
 * hook (设计文档 §特性视觉钩子, line 1269).
 *
 * <p>Mirrors the server-side {@code BulletHookInvoker} but runs on the client
 * render thread. It is intended to be called once per render frame, before
 * the {@code BulletRenderManager} draws each bullet, so that registered
 * visual hooks can mutate the projectile's appearance in-flight (texture,
 * scale, render mode).</p>
 *
 * <p>The dispatcher iterates every trait id that has registered at least one
 * hook (queried via {@link TraitHookRegistry#getRegisteredTraitIds()}),
 * looks up the {@link TraitHookType#ON_VISUAL_TICK} callbacks, and invokes
 * them in registration order. Callbacks are fired unconditionally; each
 * callback is responsible for deciding whether its trait applies to the
 * bullet (设计文档 §特性钩子注册 API).</p>
 *
 * <p><strong>Client-only class.</strong> This class lives in the client
 * package and must only be referenced from client-side code. It passes the
 * typed {@link ClientBulletSnapshot} (the bullet's frozen stats/traits
 * projection) and {@link BulletRenderObject} to
 * {@link TraitCallbacks.TraitVisualTickCallback#onVisualTick(Object, Object)};
 * the callback implementation casts the {@code Object} parameters back to
 * their concrete types (see
 * {@link TraitCallbacks.TraitVisualTickCallback} for the cast contract).</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see TraitHookRegistry
 * @see TraitCallbacks.TraitVisualTickCallback
 * @see BulletRenderObject
 * @see ClientBulletSnapshot
 */
public final class VisualTickHookDispatcher {

    private VisualTickHookDispatcher() {
    }

    /**
     * Fires all registered {@link TraitHookType#ON_VISUAL_TICK} callbacks for
     * the given render object. Intended to be called once per frame, before
     * the bullet is drawn.
     *
     * <p>The snapshot supplies the bullet's frozen stats/traits and identity
     * so hooks can make data-driven visual decisions (e.g. scale by
     * {@code bullet_size}, swap texture when a trait is active). The render
     * object is the mutable visual target that hooks adjust in-flight
     * (设计文档 §特性视觉钩子, line 1298).</p>
     *
     * @param snapshot     the client-side snapshot projection for this bullet;
     *                     may be {@code null} when the bullet was created
     *                     before snapshots were synced (hooks should guard
     *                     against this)
     * @param renderObject the client-side render object to expose to visual
     *                     hooks; must not be {@code null}
     */
    public static void dispatchVisualTick(
            @Nullable ClientBulletSnapshot snapshot,
            BulletRenderObject renderObject) {
        Set<ResourceLocation> traitIds = TraitHookRegistry.getRegisteredTraitIds();
        for (ResourceLocation traitId : traitIds) {
            List<TraitCallbacks.TraitVisualTickCallback> hooks = TraitHookRegistry.getHooks(
                    traitId, TraitHookType.ON_VISUAL_TICK, TraitCallbacks.TraitVisualTickCallback.class);
            for (TraitCallbacks.TraitVisualTickCallback hook : hooks) {
                hook.onVisualTick(snapshot, renderObject);
            }
        }
    }
}
