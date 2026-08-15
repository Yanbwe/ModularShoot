package org.yanbwe.modularshoot.registry.variant;

import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.RegistryLookupCache;

/**
 * Query API for the {@code modularshoot:variants} dynamic registry (机制四
 * §6.1, 第 7 张 DataPackRegistry).
 *
 * <p>Like the other framework datapack tables, the variants registry is
 * populated from datapack JSONs at
 * {@code data/<namespace>/modularshoot/variants/<id>.json} when a world is
 * loaded and synced to clients on connect (see
 * {@link ModularShootRegistries#VARIANTS_KEY}). Every query therefore takes a
 * {@link RegistryAccess} so the caller supplies the correct runtime view
 * (the registry is empty on the main menu).</p>
 *
 * <p>This class intentionally provides <strong>read access only</strong>:
 * no Java API registration path is introduced (规格 §2.5 YAGNI —— 不引入
 * "变体注册表（带网络同步）"；contributors enter the per-shot pool via
 * {@code registerVariantContributor} weight modifiers instead, not via
 * registry writes).</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.</p>
 */
public final class VariantRegistry {
    private VariantRegistry() {
    }

    /** Per-{@link net.minecraft.core.Registry} weak-reference lookup cache. */
    private static final RegistryLookupCache<VariantDefinition> LOOKUP_CACHE =
            new RegistryLookupCache<>();

    /**
     * Looks up a variant definition by id in the {@code modularshoot:variants}
     * registry.
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param variantId      the variant definition id, e.g.
     *                       {@code modularshoot:heavy}
     * @return the matching {@link VariantDefinition}, or
     *         {@code Optional.empty()} when the registry is absent or the id
     *         is not registered
     */
    public static Optional<VariantDefinition> getVariant(RegistryAccess registryAccess, ResourceLocation variantId) {
        return LOOKUP_CACHE.get(registryAccess, ModularShootRegistries.VARIANTS_KEY, variantId);
    }
}
