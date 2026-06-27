package org.yanbwe.modularshoot.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.client.ItemNameResolver;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;

/**
 * Framework plugin item, registered as {@code modularshoot:plugin}.
 *
 * <p>All plugins share this single item id; the concrete plugin is distinguished by the
 * {@code plugin_data} {@link org.yanbwe.modularshoot.component.PluginData Data Component} stored
 * on the stack, whose {@code pluginId} points into the {@code modularshoot:plugins} registry.
 * The item itself only carries the immutable, design-mandated base properties:</p>
 *
 * <ul>
 *   <li><b>Max stack size 1</b> &mdash; set via {@link Item.Properties#stacksTo(int)} at
 *       registration. A plugin is a unique, data-carrying instance, never a stackable commodity.</li>
 *   <li><b>Fire resistant</b> &mdash; set via {@link Item.Properties#fireResistant()} at
 *       registration. Plugins must survive lava and fire (design doc, core principles).</li>
 *   <li><b>Not enchantable</b> &mdash; vanilla enchantments have no effect on plugins. Both
 *       {@link #getEnchantmentValue()} and {@link #isEnchantable(ItemStack)} are overridden
 *       to guarantee this regardless of future component changes.</li>
 *   <li><b>Dynamic display name</b> &mdash; {@link #getName(ItemStack)} resolves the
 *       registered plugin definition's display name and colour at runtime; falls back to
 *       the plugin id path when the definition is absent.</li>
 * </ul>
 */
public class PluginItem extends Item {

    /**
     * @param properties item properties supplied by the registration; callers must already
     *                   have applied {@code stacksTo(1)} and {@code fireResistant()}
     */
    public PluginItem(Item.Properties properties) {
        super(properties);
    }

    /**
     * Resolves the display name from the plugin definition referenced by the
     * stack's {@code plugin_data} component.
     *
     * <p>Priority (first non-null wins):
     * <ol>
     *   <li>Client-side registry lookup: reads the definition's {@code name}
     *       and {@code color} fields via
     *       {@link ItemNameResolver#resolvePluginDisplayName}
     *       (supports {@code §} colour codes and {@code lang:} translation keys).
     *       Returns {@code null} on the server, on the main menu, or when the
     *       definition is absent.</li>
     *   <li>Plugin id path fallback: the path portion of the plugin id
     *       (e.g. {@code "rapid_barrel"} from
     *       {@code modularshoot:rapid_barrel}).</li>
     *   <li>Default item name: when the stack carries no {@code plugin_data}
     *       component at all, delegates to {@link Item#getName(ItemStack)}.</li>
     * </ol>
     * </p>
     *
     * @param stack the plugin item stack
     * @return the resolved display name {@link Component}
     */
    @Override
    public Component getName(ItemStack stack) {
        PluginData data = stack.get(ModularShootDataComponents.PLUGIN_DATA.get());
        if (data == null) {
            return super.getName(stack);
        }
        Component resolved = ItemNameResolver.resolvePluginDisplayName(stack);
        if (resolved != null) {
            return resolved;
        }
        return Component.literal(data.pluginId().getPath());
    }

    /**
     * Plugins never provide an enchantment value, so the vanilla enchantment table never
     * offers enchantments for them.
     *
     * @return always {@code 0}
     */
    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    /**
     * Plugins are never enchantable, even if a future change were to add a {@code MAX_DAMAGE}
     * component (which the default implementation would otherwise treat as enchantable).
     * This is the second layer of defence alongside {@link #getEnchantmentValue()}.
     *
     * @return always {@code false}
     */
    @Override
    public boolean isEnchantable(ItemStack itemStack) {
        return false;
    }
}
