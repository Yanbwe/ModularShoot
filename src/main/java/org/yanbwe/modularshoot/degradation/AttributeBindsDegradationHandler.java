package org.yanbwe.modularshoot.degradation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;

/**
 * Handles graceful degradation when an {@link AttributeMeta} entry's
 * {@code binds} field points to a vanilla {@link Attribute} that is not
 * registered in {@link BuiltInRegistries#ATTRIBUTE}
 * (设计文档 §属性元数据 binds 失效降级, lines 2345-2351).
 *
 * <p>When a third-party mod is uninstalled, its attribute body disappears
 * from the vanilla {@code ATTRIBUTE} registry, but the framework's
 * {@code attribute_meta} entry (which only carries hot-reloadable metadata)
 * remains registered. This handler centralises the runtime checks that
 * prevent the framework from crashing or mounting modifiers onto missing
 * attributes.</p>
 *
 * <h2>Degradation contract</h2>
 * <ul>
 *   <li><b>Metadata entry</b> &mdash; stays registered in
 *       {@code modularshoot:attribute_meta}; never cleared by this handler
 *       (the NeoForge registry guarantees entry retention).</li>
 *   <li><b>Modifier mounting</b> &mdash; attributes whose {@code binds}
 *       target is unregistered are silently skipped by the
 *       {@code AttributeModifierService}; no {@link AttributeModifier} is
 *       mounted on the player and no exception is thrown.</li>
 *   <li><b>Tooltip</b> &mdash; tooltip systems use
 *       {@link #isAttributeRegistered} to skip rows whose {@code binds}
 *       target is gone, so the attribute line is not displayed.</li>
 *   <li><b>Stats / plugin modifiers</b> &mdash; gun {@code stats} keys and
 *       plugin modifier {@code attribute} fields that reference an
 *       unregistered attribute resolve to nothing at runtime; modifiers do
 *       not take effect and values fall back to {@code 0}.</li>
 *   <li><b>Recovery</b> &mdash; when the mod is reinstalled the attribute
 *       body reappears in the registry; all checks are read-time, so
 *       mounting and calculation resume automatically without a reload.</li>
 * </ul>
 *
 * <p>The class is not instantiable; all methods are static and each is
 * under 50 lines (设计文档 §函数&lt;50行).</p>
 *
 * @see AttributeMeta#binds()
 * @see BuiltInRegistries#ATTRIBUTE
 * @see org.yanbwe.modularshoot.attribute.AttributeModifierService
 */
public final class AttributeBindsDegradationHandler {

    private AttributeBindsDegradationHandler() {
    }

    /**
     * Checks whether a vanilla {@link Attribute} is registered under the
     * given id in {@link BuiltInRegistries#ATTRIBUTE}.
     *
     * <p>This is the primary predicate for the binds-degradation contract.
     * Callers use it to decide whether to mount modifiers, display tooltip
     * rows, or resolve stat values for an attribute. The check is
     * read-time and null-safe: a {@code null} id returns {@code false}.</p>
     *
     * @param bindsId the attribute id to look up (typically
     *                {@link AttributeMeta#binds()}); {@code null} returns
     *                {@code false}
     * @return {@code true} if a vanilla {@link Attribute} is registered
     *         under the given id
     */
    public static boolean isAttributeRegistered(@Nullable ResourceLocation bindsId) {
        return BuiltInRegistries.ATTRIBUTE.getOptional(bindsId).isPresent();
    }
}
