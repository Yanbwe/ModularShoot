package org.yanbwe.modularshoot.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.client.ItemNameResolver;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;

/**
 * Framework gun item, registered as {@code modularshoot:gun}.
 *
 * <p>All guns share this single item id; the concrete gun type is distinguished by the
 * {@code gun_data} {@link org.yanbwe.modularshoot.component.GunData Data Component} stored
 * on the stack. The item itself only carries the immutable, design-mandated base properties:</p>
 *
 * <ul>
 *   <li><b>Max stack size 1</b> &mdash; set via {@link Item.Properties#stacksTo(int)} at
 *       registration. A gun is a unique, data-carrying instance, never a stackable commodity.</li>
 *   <li><b>Fire resistant</b> &mdash; set via {@link Item.Properties#fireResistant()} at
 *       registration. Guns must survive lava and fire (design doc, core principles).</li>
 *   <li><b>Not enchantable</b> &mdash; vanilla enchantments have no effect on guns. Both
 *       {@link #getEnchantmentValue()} and {@link #isEnchantable(ItemStack)} are overridden
 *       to guarantee this regardless of future component changes.</li>
 *   <li><b>Dynamic display name</b> &mdash; {@link #getName(ItemStack)} resolves the
 *       registered gun definition's display name at runtime; falls back to the gun id
 *       path when the definition is absent (主菜单 / reload 过渡状态).</li>
 * </ul>
 */
public class GunItem extends Item {

    /**
     * @param properties item properties supplied by the registration; callers must already
     *                   have applied {@code stacksTo(1)} and {@code fireResistant()}
     */
    public GunItem(Item.Properties properties) {
        super(properties);
    }

    /**
     * Resolves the display name from the gun definition referenced by the
     * stack's {@code gun_data} component.
     *
     * <p>Priority (first non-null wins):
     * <ol>
     *   <li>Client-side registry lookup: reads the definition's {@code name}
     *       field, resolved via {@link ItemNameResolver#resolveGunDisplayName}
     *       (supports {@code §} colour codes and {@code lang:} translation keys).
     *       Returns {@code null} on the server, on the main menu, or when the
     *       definition is absent.</li>
     *   <li>Gun id path fallback: the path portion of the gun id
     *       (e.g. {@code "sniper_rifle"} from
     *       {@code modularshoot:sniper_rifle}), per the design doc fallback
     *       rule (设计文档: 不指定时使用枪械 ID 的路径部分作为回退名称).</li>
     *   <li>Default item name: when the stack carries no {@code gun_data}
     *       component at all, delegates to {@link Item#getName(ItemStack)}.</li>
     * </ol>
     * </p>
     *
     * @param stack the gun item stack
     * @return the resolved display name {@link Component}
     */
    @Override
    public Component getName(ItemStack stack) {
        GunData data = stack.get(ModularShootDataComponents.GUN_DATA.get());
        if (data == null) {
            return super.getName(stack);
        }
        Component resolved = ItemNameResolver.resolveGunDisplayName(stack);
        if (resolved != null) {
            return resolved;
        }
        return Component.literal(data.gunId().getPath());
    }

    /**
     * Guns never provide an enchantment value, so the vanilla enchantment table never
     * offers enchantments for them.
     *
     * @return always {@code 0}
     */
    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    /**
     * Guns are never enchantable, even if a future change were to add a {@code MAX_DAMAGE}
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
