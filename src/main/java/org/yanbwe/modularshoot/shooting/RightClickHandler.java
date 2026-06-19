package org.yanbwe.modularshoot.shooting;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;

/**
 * Fires {@link GunRightClickEvent} when a player right-clicks while holding a
 * gun in the main hand and no container GUI is open (设计文档 §右键 API,
 * lines 827-849).
 *
 * <p>The framework provides no default right-click (use) behavior for guns.
 * This handler is purely an extension point: it translates NeoForge's
 * {@link PlayerInteractEvent.RightClickItem} and
 * {@link PlayerInteractEvent.RightClickEmpty} into the framework-specific
 * {@link GunRightClickEvent}, which other mods listen to via
 * {@code @SubscribeEvent} to implement custom behavior such as reloading,
 * firing-mode switching, aiming, etc. If no listener cancels the event, the
 * framework performs no action on its own.</p>
 *
 * <h2>Container-GUI routing</h2>
 * <p>{@link GunRightClickEvent} fires only when the player is <em>not</em>
 * inside any container GUI. When a non-default container (chest, crafting
 * table, etc.) is open, right-clicks inside inventories are owned by the
 * plugin-install flow ({@link org.yanbwe.modularshoot.plugin.PluginInstallEventHandler}),
 * so this handler stays silent. The check uses
 * {@link Player#hasContainerOpen()}, which returns {@code true} when
 * {@code containerMenu} differs from the default {@code inventoryMenu}.</p>
 *
 * <h2>Logical-side handling</h2>
 * <p>{@link PlayerInteractEvent.RightClickItem} fires on both logical sides,
 * while {@link PlayerInteractEvent.RightClickEmpty} fires on the client only.
 * This handler does not guard on logical side: {@link GunRightClickEvent} is
 * notification-only, so it is posted on whichever side the underlying
 * NeoForge event fires. Downstream listeners decide per-side how to respond,
 * matching the NeoForge-recommended pattern for purely informational events.
 * The {@code containerMenu} check is valid on both sides because the client
 * player also tracks its open menu.</p>
 *
 * <p>Both listeners are registered on the NeoForge game event bus
 * ({@code NeoForge.EVENT_BUS}) via {@link EventBusSubscriber} with no
 * {@code bus} parameter, matching the pattern used by
 * {@link LeftClickInterceptHandler}.</p>
 *
 * @see GunRightClickEvent
 * @see ModularShootAPI#isGun(ItemStack)
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class RightClickHandler {

    private RightClickHandler() {
    }

    /**
     * Handles right-click on a held item, firing {@link GunRightClickEvent}
     * when the main hand holds a gun and no container GUI is open.
     *
     * <p>{@link PlayerInteractEvent.RightClickItem} fires on both logical
     * sides before {@code Item#use}. Because guns are restricted to the main
     * hand by {@link org.yanbwe.modularshoot.event.OffhandRestrictionHandler},
     * the handler ignores off-hand right-clicks so the event fires at most
     * once per right-click when both hands hold items.</p>
     *
     * @param event the right-click-item event carrying the player and hand
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // Guns are main-hand only; skip off-hand right-clicks to avoid firing
        // twice when both hands hold items.
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Player player = event.getEntity();
        if (!isMainHandGun(player)) {
            return;
        }
        // A non-default container GUI is open: the install flow owns
        // right-clicks inside inventories, so GunRightClickEvent must not fire.
        if (isDefaultContainerOpen(player)) {
            return;
        }
        fireRightClickEvent(player, player.getMainHandItem());
    }

    /**
     * Handles right-click on empty space, firing {@link GunRightClickEvent}
     * when the main hand holds a gun and no container GUI is open.
     *
     * <p>{@link PlayerInteractEvent.RightClickEmpty} fires on the client side
     * when the player right-clicks empty space with an empty hand. When the
     * main hand holds a gun, the empty hand is the off hand; this handler
     * still fires {@link GunRightClickEvent} because the right-click intent
     * concerns the main-hand gun. The event itself is not cancelable, but the
     * {@link GunRightClickEvent} it posts is.</p>
     *
     * @param event the right-click-empty event carrying the player and hand
     */
    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        Player player = event.getEntity();
        if (!isMainHandGun(player)) {
            return;
        }
        // A non-default container GUI is open: the install flow owns
        // right-clicks inside inventories, so GunRightClickEvent must not fire.
        if (isDefaultContainerOpen(player)) {
            return;
        }
        fireRightClickEvent(player, player.getMainHandItem());
    }

    /**
     * Checks whether the player's main-hand item is a framework gun.
     *
     * <p>Delegates to {@link ModularShootAPI#isGun(ItemStack)} after reading
     * the main-hand stack. Extracted as a helper so both listeners share a
     * single source of truth for the gun-detection predicate, matching the
     * pattern in {@link LeftClickInterceptHandler#isMainHandGun}.</p>
     *
     * @param player the player whose main hand to inspect
     * @return {@code true} when the main-hand stack is a {@code modularshoot:gun}
     */
    private static boolean isMainHandGun(Player player) {
        return ModularShootAPI.isGun(player.getMainHandItem());
    }

    /**
     * Checks whether the player currently has a non-default container GUI
     * open.
     *
     * <p>A player's {@code containerMenu} defaults to the {@code inventoryMenu}
     * (the survival inventory screen). When any other container (chest,
     * crafting table, anvil, etc.) is open, {@code containerMenu} differs from
     * {@code inventoryMenu}. {@link Player#hasContainerOpen()} encapsulates
     * exactly this check and is valid on both logical sides.</p>
     *
     * @param player the player to inspect
     * @return {@code true} when a non-default container GUI is open
     */
    private static boolean isDefaultContainerOpen(Player player) {
        return player.hasContainerOpen();
    }

    /**
     * Constructs and posts a {@link GunRightClickEvent} on the NeoForge game
     * bus.
     *
     * <p>The framework performs no action after posting, regardless of whether
     * the event is canceled. Canceling only prevents subsequent handlers in
     * the same bus dispatch from receiving the event; the framework itself has
     * no default right-click behavior to suppress (设计文档 §右键 API).</p>
     *
     * @param player the player right-clicking the gun
     * @param gun    the gun {@link ItemStack} in the player's main hand
     */
    private static void fireRightClickEvent(Player player, ItemStack gun) {
        NeoForge.EVENT_BUS.post(new GunRightClickEvent(player, gun));
    }
}
