package org.yanbwe.modularshoot.degradation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
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
 *   <li><b>Value read</b> &mdash; {@link #getSafeAttributeValue} returns
 *       {@code 0.0} when the player has no
 *       {@link AttributeInstance} for the attribute (vanilla behaviour:
 *       missing holder &rarr; zero value).</li>
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

    /**
     * Safely reads the current value of an attribute on a player, returning
     * {@code 0.0} when the player has no {@link AttributeInstance} for that
     * attribute.
     *
     * <p>When {@code binds} points to an unregistered attribute, the player
     * has no corresponding attribute holder. Vanilla's
     * {@code LivingEntity.getAttribute} returns {@code null} in that case;
     * this method intercepts the {@code null} and returns {@code 0.0}
     * instead of throwing (设计文档 §读取兜底).</p>
     *
     * @param player the player whose attribute value is sought; must not
     *               be {@code null}
     * @param holder the attribute holder to read; must not be {@code null}
     * @return the attribute's current value, or {@code 0.0} when the player
     *         has no {@link AttributeInstance} for the attribute
     */
    public static double getSafeAttributeValue(Player player, Holder<Attribute> holder) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(holder, "holder");
        AttributeInstance instance = player.getAttribute(holder);
        return instance != null ? instance.getValue() : 0.0;
    }

    /**
     * Filters a stats map, keeping only entries whose target attribute is
     * registered in the vanilla {@code ATTRIBUTE} registry.
     *
     * <p>For each entry the resolution order is:</p>
     * <ol>
     *   <li>look up {@link AttributeMeta} for the key in the
     *       {@code attribute_meta} registry; if found, check whether its
     *       {@link AttributeMeta#binds()} target is registered;</li>
     *   <li>if no {@code attribute_meta} entry exists, fall back to
     *       checking whether the key itself is a registered attribute
     *       (framework attributes where logical id equals binds).</li>
     * </ol>
     * <p>Entries whose binds target is unregistered are dropped silently.
     * The returned map is unmodifiable and preserves the original entry
     * order. Used by the attribute-modifier service to skip stats that
     * reference unregistered attributes (设计文档 §依赖该属性的枪械).</p>
     *
     * @param stats          the attribute id to value mapping to filter;
     *                       must not be {@code null}
     * @param registryAccess the runtime registry view (for
     *                       {@code attribute_meta}); must not be {@code null}
     * @return an unmodifiable map containing only entries whose binds
     *         target is registered; empty when every entry is degraded
     */
    public static Map<ResourceLocation, Double> filterRegisteredAttributes(
            Map<ResourceLocation, Double> stats, RegistryAccess registryAccess) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return stats.entrySet().stream()
                .filter(entry -> isBindsRegistered(entry.getKey(), registryAccess))
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
    }

    /**
     * Resolves whether the attribute identified by {@code attributeId} has
     * a registered binds target.
     *
     * <p>First consults the {@code attribute_meta} registry for the entry's
     * {@link AttributeMeta#binds()} field; if no metadata entry exists,
     * falls back to checking whether {@code attributeId} itself is a
     * registered vanilla attribute (the framework-attribute case where
     * logical id equals binds).</p>
     *
     * @param attributeId    the attribute id to resolve
     * @param registryAccess the runtime registry view
     * @return {@code true} if the binds target is registered
     */
    private static boolean isBindsRegistered(ResourceLocation attributeId, RegistryAccess registryAccess) {
        Optional<AttributeMeta> meta = registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY)
                .flatMap(reg -> reg.getOptional(attributeId));
        if (meta.isPresent()) {
            return isAttributeRegistered(meta.get().binds());
        }
        return isAttributeRegistered(attributeId);
    }
}
