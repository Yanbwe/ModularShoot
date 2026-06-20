package org.yanbwe.modularshoot.client.render;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
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
 * typed {@link BulletRenderObject} to
 * {@link TraitCallbacks.TraitVisualTickCallback#onVisualTick(Object)}; the
 * callback implementation casts the {@code Object} parameter back to
 * {@link BulletRenderObject} (see
 * {@link TraitCallbacks.TraitVisualTickCallback} for the cast contract).</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see TraitHookRegistry
 * @see TraitCallbacks.TraitVisualTickCallback
 * @see BulletRenderObject
 */
public final class VisualTickHookDispatcher {

    private VisualTickHookDispatcher() {
    }

    /**
     * Fires all registered {@link TraitHookType#ON_VISUAL_TICK} callbacks for
     * the given render object. Intended to be called once per frame, before
     * the bullet is drawn.
     *
     * @param renderObject the client-side render object to expose to visual
     *                     hooks; must not be {@code null}
     */
    public static void dispatchVisualTick(BulletRenderObject renderObject) {
        Set<ResourceLocation> traitIds = TraitHookRegistry.getRegisteredTraitIds();
        for (ResourceLocation traitId : traitIds) {
            List<TraitCallbacks.TraitVisualTickCallback> hooks = TraitHookRegistry.getHooks(
                    traitId, TraitHookType.ON_VISUAL_TICK, TraitCallbacks.TraitVisualTickCallback.class);
            for (TraitCallbacks.TraitVisualTickCallback hook : hooks) {
                hook.onVisualTick(renderObject);
            }
        }
    }
}
