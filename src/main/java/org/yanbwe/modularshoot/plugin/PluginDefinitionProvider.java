package org.yanbwe.modularshoot.plugin;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Dynamic source of plugin definitions queried by
 * {@link PluginRegistry#getPlugin(net.minecraft.core.RegistryAccess, ResourceLocation)}
 * between the Java API map and the datapack registry (审查 E1 — mirrors the
 * gun-side {@link org.yanbwe.modularshoot.registry.gun.GunDefinitionProvider}
 * channel).
 *
 * <p>The datapack registry and the Java API map both key definitions by a
 * <em>global</em> plugin id; a provider lifts that constraint by supplying
 * definitions that are computed at query time — per-instance loot plugins,
 * procedurally generated affixes, or other mods' runtime logic. The plugin
 * id may encode the source (e.g. {@code loot:affix_<n>}) so the provider can
 * recover the context it needs.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Return {@link Optional#empty()} for ids this provider does not
 *       handle; the framework then falls through to the next source
 *       (datapack registry), preserving the existing degradation path.</li>
 *   <li>Providers run in registration order; the first non-empty result
 *       wins. Java-API-registered definitions always take priority over
 *       providers (设计文档 §注册冲突与覆盖 语义延伸).</li>
 *   <li>Implementations must be side-safe (no client-only calls) because
 *       {@code getPlugin} runs on both sides.</li>
 *   <li>Provider-defined definitions must be <em>deterministic across
 *       ends</em>: the same provider runs on the server and on the client,
 *       but client query points (tooltip, item name resolution, rendering)
 *       have no access to server-side state. A definition that depends on
 *       server-only state either degrades on the client or shows different
 *       values on each end — the framework has no sync mechanism for
 *       provider results. Prefer definitions derived from data available on
 *       both sides (e.g. the plugin id itself).</li>
 * </ul>
 *
 * <p>Registration is process-wide and survives {@code /reload}; use
 * {@link PluginRegistry#registerPluginDefinitionProvider} (or the
 * {@code ModularShootAPI} facade).</p>
 */
@FunctionalInterface
public interface PluginDefinitionProvider {

    /**
     * Resolves a plugin definition for the given id, or empty when this
     * provider does not handle the id.
     *
     * @param pluginId the plugin definition id being looked up
     * @return the definition, or empty to fall through to the next source
     */
    @NotNull
    Optional<PluginDefinition> get(@NotNull ResourceLocation pluginId);
}
