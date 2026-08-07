package org.yanbwe.modularshoot.registry.binding;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Server-side tick channel that lazily attaches {@code gun_data} to bound
 * guns held by players (设计规格 物品绑定系统 §5.3).
 *
 * <p>Bound guns &mdash; items whose id is mapped to a gun via the
 * {@code modularshoot:gun_items} binding table &mdash; enter the world
 * without a {@code gun_data} component. This handler is the <b>main
 * channel</b> that attaches one: after each server level finishes its tick
 * work it iterates every online player in that level and calls
 * {@link GunRegistry#ensureGunData} on the player's main-hand and off-hand
 * stacks, so a bound gun is attached within 1 tick of being picked up. The
 * component then reaches clients through the regular item sync (≤ 1 tick)
 * with no extra packet.</p>
 *
 * <p><b>Server-only.</b> A {@code level.isClientSide()} guard ensures the
 * handler runs exclusively on the authoritative server; the client has no
 * attachment path of its own (设计规格 物品绑定系统 §5.3) — it always receives
 * the component via item sync.</p>
 *
 * <h2>Relationship with {@link org.yanbwe.modularshoot.state.GunSyncTickHandler}</h2>
 * <p>Both handlers listen to {@link LevelTickEvent.Post} on the game bus but
 * have distinct, independent responsibilities: {@code GunSyncTickHandler}
 * flushes <em>throttled state syncs</em> for guns that already carry
 * {@code gun_data}, while this handler <em>attaches</em> the component to
 * bound guns that lack it. They never conflict: the sync handler skips
 * stacks without {@code gun_data} (via
 * {@link org.yanbwe.modularshoot.ModularShootAPI#isGun}), and the attach
 * handler is idempotent, leaving already-attached stacks untouched.</p>
 *
 * @see GunRegistry#ensureGunData
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BoundGunAttachHandler {

    private BoundGunAttachHandler() {
    }

    /**
     * Fired once per tick per level after the level has finished its work.
     *
     * <p>On the authoritative server, attaches {@code gun_data} to every
     * online player's main-hand and off-hand stacks that are recognized as
     * guns via the binding channel (设计规格 物品绑定系统 §5.3).</p>
     *
     * @param event the post-level-tick event carrying the ticking level
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        for (Player player : level.players()) {
            attachHandItems(player, level);
        }
    }

    /**
     * Ensures {@code gun_data} is attached to a player's main-hand and
     * off-hand stacks.
     *
     * @param player the player whose held items to inspect
     * @param level  the ticking server level (source of the registry view)
     */
    private static void attachHandItems(Player player, Level level) {
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offhand = player.getOffhandItem();
        GunRegistry.ensureGunData(mainHand, level.registryAccess());
        GunRegistry.ensureGunData(offhand, level.registryAccess());
    }
}
