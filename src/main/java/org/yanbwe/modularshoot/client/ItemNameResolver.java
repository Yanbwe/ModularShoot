package org.yanbwe.modularshoot.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.client.tooltip.TooltipUtils;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

import java.util.Optional;

/**
 * Client-side helper that resolves gun and plugin item display names by
 * reading the registered definition from the currently loaded world's
 * dynamic registry.
 *
 * <p>All resolution methods return {@code null} when no name can be
 * resolved — the caller must provide a fallback. This class references
 * {@link Minecraft#getInstance()} and is therefore only safe to call on
 * the physical client; every method wraps its logic in a try/catch so that
 * accidental server-side invocation (e.g. during command feedback) degrades
 * gracefully to {@code null}.</p>
 *
 * <p>Resolution logic mirrors the tooltip builders
 * ({@link org.yanbwe.modularshoot.client.tooltip.PluginBarTooltipBuilder},
 * {@link org.yanbwe.modularshoot.client.tooltip.PluginTooltipBuilder}):
 * <ul>
 *   <li>Guns: use {@link GunDefinition#nameOrNull()} resolved via
 *       {@link TooltipUtils#resolveText} (supports {@code §} colour codes
 *       and {@code lang:} translation keys).</li>
 *   <li>Plugins: use {@link PluginDefinition#name()} resolved via
 *       {@link TooltipUtils#resolveText}, then apply
 *       {@link PluginDefinition#color()} when present.</li>
 * </ul>
 * Fallback when no explicit name is set: the definition id's path portion
 * (e.g. {@code sniper_rifle} from {@code modularshoot:sniper_rifle})
 * (设计文档 §枪械定义: 不指定时使用枪械 ID 的路径部分作为回退名称).</p>
 *
 * @see org.yanbwe.modularshoot.item.GunItem
 * @see org.yanbwe.modularshoot.item.PluginItem
 */
public final class ItemNameResolver {

    private ItemNameResolver() {
    }

    /**
     * Resolves the display name for a gun {@link ItemStack} by reading
     * its {@code gun_data} component and looking up the gun definition in
     * the client world's dynamic registry.
     *
     * @param stack the gun item stack
     * @return the resolved display {@link Component}, or {@code null} when
     *         no world is loaded, the gun definition is absent, or the
     *         code is executing on the server
     */
    @Nullable
    public static Component resolveGunDisplayName(ItemStack stack) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return null;
            }
            RegistryAccess registryAccess = mc.level.registryAccess();
            GunData data = stack.get(ModularShootDataComponents.GUN_DATA.get());
            if (data == null) {
                return null;
            }
            ResourceLocation gunId = data.gunId();
            Optional<GunDefinition> defOpt = GunRegistry.getGun(registryAccess, gunId);
            if (defOpt.isEmpty()) {
                return null;
            }
            GunDefinition def = defOpt.get();
            String rawName = def.nameOrNull();
            if (rawName != null) {
                return TooltipUtils.resolveText(rawName);
            }
            // No explicit name: caller should use gunId path as fallback.
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves the display name for a plugin {@link ItemStack} by reading
     * its {@code plugin_data} component and looking up the plugin definition
     * in the client world's dynamic registry.
     *
     * <p>When the definition carries a {@code color} field, it is applied
     * via {@link MutableComponent#withColor}. When the definition carries
     * no explicit {@code name}, the plugin id's path portion is used as the
     * fallback raw name (matching the tooltip builder behaviour in
     * {@link org.yanbwe.modularshoot.client.tooltip.PluginBarTooltipBuilder}).
     * Colour codes ({@code §}) embedded in the raw name naturally override
     * the {@code color} field on a per-segment basis through Minecraft's
     * text rendering.</p>
     *
     * @param stack the plugin item stack
     * @return the resolved display {@link Component}, or {@code null} when
     *         no world is loaded, the plugin definition is absent, or the
     *         code is executing on the server
     */
    @Nullable
    public static Component resolvePluginDisplayName(ItemStack stack) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return null;
            }
            RegistryAccess registryAccess = mc.level.registryAccess();
            PluginData data = stack.get(ModularShootDataComponents.PLUGIN_DATA.get());
            if (data == null) {
                return null;
            }
            ResourceLocation pluginId = data.pluginId();
            Optional<PluginDefinition> defOpt = PluginRegistry.getPlugin(registryAccess, pluginId);
            if (defOpt.isEmpty()) {
                return null;
            }
            PluginDefinition def = defOpt.get();
            String rawName = def.name().filter(s -> !s.isEmpty()).orElse(pluginId.getPath());
            MutableComponent comp = TooltipUtils.resolveText(rawName);
            def.color().ifPresent(c -> comp.withColor(TooltipUtils.parseHexColor(c)));
            return comp;
        } catch (Exception e) {
            return null;
        }
    }
}
