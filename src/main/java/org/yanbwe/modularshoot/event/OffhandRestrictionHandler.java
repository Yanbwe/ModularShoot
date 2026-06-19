package org.yanbwe.modularshoot.event;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.item.ModularShootItems;

/**
 * Enforces the design-mandated offhand restriction for guns.
 *
 * <p>Guns must never reside in the offhand slot. Their {@code ATTRIBUTE_MODIFIERS}
 * component is scoped to {@code MAINHAND}, so an offhand gun would silently lose its
 * attribute bonuses and mislead the player. To keep behaviour predictable regardless
 * of how the item ends up in the offhand (F-key swap, command, dispenser, etc.) the
 * framework polls every server tick: when a gun is detected in the offhand it is
 * immediately ejected as an item entity and the player is told why.</p>
 *
 * <p>Server-side only. The client tick is ignored via a {@code level().isClientSide()}
 * guard so inventory state is authored on the authoritative side only, matching the
 * NeoForge-recommended pattern for {@link PlayerTickEvent} handlers.</p>
 *
 * @see ModularShootItems#GUN_ITEM
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class OffhandRestrictionHandler {

    /** Translation key for the action-bar warning shown when a gun is ejected. */
    public static final String OFFHAND_RESTRICTED_KEY = "modularshoot.offhand_restricted";

    private OffhandRestrictionHandler() {
    }

    /**
     * Polled every tick on both logical sides; guarded to run only on the server.
     *
     * <p>Using {@link PlayerTickEvent.Pre} lets us strip the gun before the rest of
     * the tick (attribute re-evaluation, interaction checks) observes it in the
     * offhand, so the restricted state never propagates into downstream logic.</p>
     *
     * @param event the pre-tick event carrying the ticking player
     */
    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        // Only the server is authoritative for inventory changes.
        if (player.level().isClientSide()) {
            return;
        }

        ItemStack offhand = player.getOffhandItem();
        // Non-gun items (including empty stacks) are left untouched.
        if (!offhand.is(ModularShootItems.GUN_ITEM.get())) {
            return;
        }

        // Remove the gun from the offhand slot first so the dropped entity owns the
        // stack exclusively (no aliasing with the inventory list reference).
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        // Eject the saved stack in front of the player. The boolean controls owner
        // retention (false = no thrower, anyone may pick it up after the pickup
        // delay); the 2-arg overload always throws along the look direction.
        player.drop(offhand, false);
        // Notify the player via the action bar (transient, non-intrusive notice).
        player.displayClientMessage(Component.translatable(OFFHAND_RESTRICTED_KEY), true);
    }
}
