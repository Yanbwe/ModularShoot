package org.yanbwe.modularshoot.bullet;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Pure-function service that resolves the final projectile visual style for a
 * gun by applying the plugin {@code bullet_style} override chain on top of the
 * gun's base {@code bullet_style} (设计文档 §子弹视觉样式, lines 1189-1203).
 *
 * <h2>Priority chain (low to high)</h2>
 * <ol>
 *   <li><b>Gun base</b> &mdash; the {@code bullet_style} declared on the
 *       {@link GunDefinition}. This is the lowest-priority contributor.</li>
 *   <li><b>Plugin override</b> &mdash; the {@code bullet_style} declared on an
 *       installed plugin. Plugin overrides are <b>whole-value replacements</b>,
 *       not field-level merges: the highest-priority plugin's entire
 *       {@link BulletStyle} replaces every lower-priority plugin's style
 *       (including {@code model} and {@code render_mode}). Plugins sharing the
 *       same priority are ordered by install sequence (the order of
 *       {@code installedPlugins} in {@link GunData}); a later-installed plugin
 *       overwrites an earlier one at equal priority. Plugins that do not
 *       declare a {@code bullet_style} do not participate in the override
 *       chain (设计文档 line 1203).</li>
 * </ol>
 *
 * <p>The runtime {@code onVisualTick} hook (M4) may further mutate the
 * resolved style on the client; this resolver produces only the static,
 * pre-hook baseline that is sent to the client via {@code BulletS2CPacket}
 * (设计文档 line 1198).</p>
 *
 * <p>This is a <b>pure function</b>: the same registry view plus the same
 * {@link GunData} always produce the same output, with no side effects. The
 * returned {@link Optional} is empty when neither the gun nor any installed
 * plugin declares a {@code bullet_style} &mdash; the caller then falls back
 * to the default pure-collision-body appearance.</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.</p>
 *
 * @see BulletStyle
 * @see GunDefinition#bulletStyle()
 * @see PluginDefinition#bulletStyle()
 */
public final class VisualStyleResolver {

    private VisualStyleResolver() {
    }

    /**
     * Resolves the final bullet visual style for the given gun data.
     *
     * <p>Looks up the gun definition for {@link GunData#gunId()} and every
     * installed plugin, then applies the plugin override chain. When at least
     * one installed plugin declares a {@code bullet_style}, the
     * highest-priority plugin's style (install-order-stable at equal priority)
     * wins over the gun's base style. When no plugin overrides, the gun's
     * base style is returned. When neither declares a style,
     * {@link Optional#empty()} is returned.</p>
     *
     * @param registryAccess the runtime registry view used to look up gun and
     *                       plugin definitions; must not be {@code null}
     * @param gunData        the per-gun data carrying the gun id and installed
     *                       plugin list; must not be {@code null}
     * @return the resolved bullet style, or {@link Optional#empty()} when
     *         neither the gun nor any installed plugin declares one
     */
    public static Optional<BulletStyle> resolve(RegistryAccess registryAccess, GunData gunData) {
        Objects.requireNonNull(registryAccess, "registryAccess");
        Objects.requireNonNull(gunData, "gunData");
        Optional<BulletStyle> override = resolvePluginOverride(registryAccess, gunData.installedPlugins());
        // Plugin override wins; fall back to the gun base style only when no
        // plugin declares one (lazy — avoids a gun-registry lookup when an
        // override is present).
        return override.or(() -> resolveBaseStyle(registryAccess, gunData.gunId()));
    }

    /**
     * Looks up the gun definition's base {@code bullet_style}.
     *
     * @param registryAccess the runtime registry view
     * @param gunId          the gun definition id
     * @return the gun's base bullet style, or {@link Optional#empty()} when
     *         the gun is missing from the registry or declares no style
     */
    private static Optional<BulletStyle> resolveBaseStyle(RegistryAccess registryAccess, ResourceLocation gunId) {
        return GunRegistry.getGun(registryAccess, gunId)
                .flatMap(GunDefinition::bulletStyle);
    }

    /**
     * Resolves the highest-priority plugin {@code bullet_style} override.
     *
     * <p>Installed plugins are resolved to their definitions, filtered to
     * those that declare a {@code bullet_style}, and sorted by priority
     * ascending with a stable install-order tiebreaker (the stream is
     * ordered, so {@link java.util.stream.Stream#sorted(Comparator)
     * Stream.sorted} preserves the relative order of equal-priority elements).
     * The last element of the sorted list is the winner &mdash; highest
     * priority, or later-installed at equal priority (设计文档 line 1196).</p>
     *
     * @param registryAccess the runtime registry view for plugin lookups
     * @param plugins        the installed plugin instances in install order
     * @return the winning plugin's bullet style, or {@link Optional#empty()}
     *         when no installed plugin declares one
     */
    private static Optional<BulletStyle> resolvePluginOverride(
            RegistryAccess registryAccess, List<PluginInstance> plugins) {
        List<PluginDefinition> sorted = plugins.stream()
                .map(instance -> PluginRegistry.getPlugin(registryAccess, instance.pluginId()).orElse(null))
                .filter(definition -> definition != null)
                .filter(definition -> definition.bulletStyle().isPresent())
                .sorted(Comparator.comparingInt(PluginDefinition::priority))
                .toList();
        if (sorted.isEmpty()) {
            return Optional.empty();
        }
        return sorted.get(sorted.size() - 1).bulletStyle();
    }
}
