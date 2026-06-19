package org.yanbwe.modularshoot.item;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Deferred register for framework {@link Item}s.
 *
 * <p>Registration is bound to {@link ModularShoot#ITEMS}, which the main mod class already
 * attaches to the mod event bus. This class only declares the {@code DeferredItem} holders;
 * it does not register itself anywhere.</p>
 *
 * <p>Currently registers the single framework gun item {@code modularshoot:gun}. Plugin
 * items will be added in later milestones.</p>
 */
public final class ModularShootItems {

    /**
     * The framework gun item. All guns share this one item id; the concrete gun type is
     * selected by the {@code gun_data} component set on the stack at creation time
     * (creative tab, command, or API), so no default {@code gun_data} is configured here.
     *
     * <p>Base properties: max stack size 1, fire resistant, not enchantable (enforced by
     * {@link GunItem}).</p>
     */
    public static final DeferredItem<GunItem> GUN_ITEM =
            ModularShoot.ITEMS.register("gun", () -> new GunItem(new Item.Properties()
                    .stacksTo(1)
                    .fireResistant()));

    private ModularShootItems() {
    }

    /**
     * Forces class initialization so every static {@code DeferredItem} field is added to
     * {@link ModularShoot#ITEMS}. Must be called once during mod construction, before the
     * ITEMS deferred register is attached to the mod event bus.
     *
     * <p>The body is intentionally empty: merely referencing the class triggers Java to run
     * its static initializers, which is where the {@code register(...)} calls live.</p>
     */
    public static void init() {
    }
}
