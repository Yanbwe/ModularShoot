package org.yanbwe.modularshoot.shooting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;

/**
 * Intercepts vanilla left-click attacks when the main-hand item is a gun,
 * channelling the input into the framework's shooting engine instead.
 *
 * <p>The design doc (§交互拦截) mandates that holding a
 * {@code modularshoot:gun} item must never trigger the vanilla entity-attack
 * or block-break behaviour. This handler cancels the two NeoForge events that
 * gate those actions — {@link AttackEntityEvent} (attacking an entity) and
 * {@link PlayerInteractEvent.LeftClickBlock} (breaking a block) — whenever the
 * player's main hand holds a gun, as determined by
 * {@link ModularShootAPI#isGun(ItemStack)}.</p>
 *
 * <p>Both listeners are registered on the NeoForge game event bus
 * ({@code NeoForge.EVENT_BUS}) via {@link EventBusSubscriber} with no
 * {@code dist} restriction. Cancellation must happen on <em>both</em> logical
 * sides: on the client it suppresses the vanilla attack/block-break prediction
 * (so the player never sees the vanilla swing animation or crack texture), and
 * on the server it prevents the authoritative damage/block-removal. Cancelling
 * on one side only would cause visible desynchronisation.</p>
 *
 * <p>The handler is intentionally minimal — it only decides <em>whether</em> to
 * suppress vanilla behaviour. The actual shoot request is sent client-side by
 * {@code ClientShootSender} and adjudicated server-side by the shooting
 * engine, keeping this class free of rate-limiting or state.</p>
 *
 * @see ModularShootAPI#isGun(ItemStack)
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class LeftClickInterceptHandler {

    private LeftClickInterceptHandler() {
    }

    /**
     * Cancels vanilla entity-attack damage when the main-hand item is a gun.
     *
     * <p>{@link AttackEntityEvent} fires inside {@code Player#attack(Entity)}
     * on both logical sides. Cancelling it prevents the vanilla melee damage
     * calculation and knockback from running, leaving the shoot engine as the
     * sole source of damage for gun-wielding players.</p>
     *
     * @param event the attack-entity event carrying the attacking player and target
     */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (isMainHandGun(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Cancels vanilla block-break progress when the main-hand item is a gun.
     *
     * <p>{@link PlayerInteractEvent.LeftClickBlock} fires at several points
     * during the left-click-on-block lifecycle (start, stop, abort, and
     * {@code CLIENT_HOLD}) on both logical sides. Cancelling suppresses the
     * block {@code attack} call and the item harvesting path, so guns never
     * dig blocks. The event re-fires every tick while the button is held, so
     * a single cancellation per tick is sufficient to keep the block
     * intact.</p>
     *
     * @param event the left-click-block event carrying the player and hand
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        if (isMainHandGun(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Checks whether the player's main-hand item is a framework gun.
     *
     * <p>Delegates to {@link ModularShootAPI#isGun(ItemStack)} after reading
     * the main-hand stack. Extracted as a helper so both intercept handlers
     * share a single source of truth for the gun-detection predicate.</p>
     *
     * @param player the player whose main hand to inspect
     * @return {@code true} when the main-hand stack is a {@code modularshoot:gun}
     */
    private static boolean isMainHandGun(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        return ModularShootAPI.isGun(mainHand);
    }
}
