package org.yanbwe.modularshoot.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
 * </ul>
 *
 * <p>M2 scope: this class only defines the item shell. Plugin installation, trait resolution
 * and other interactions are implemented in later milestones (M2/M3).</p>
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
