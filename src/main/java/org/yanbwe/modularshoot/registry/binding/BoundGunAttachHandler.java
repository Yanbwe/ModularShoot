package org.yanbwe.modularshoot.registry.binding;

import net.minecraft.core.RegistryAccess;
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
 * guns in players' inventories (设计规格 物品绑定系统 §5.3).
 *
 * <p>Bound guns &mdash; items whose id is mapped to a gun via the
 * {@code modularshoot:gun_items} binding table &mdash; enter the world
 * without a {@code gun_data} component. This handler is the <b>main
 * channel</b> that attaches one: after each server level finishes its tick
 * work it iterates every online player in that level and calls
 * {@link GunRegistry#ensureGunData} on every stack of the player's
 * inventory (36 main-grid slots plus off-hand), so a bound gun is attached
 * within 1 tick of <em>entering the player's inventory</em> — it does not
 * need to be held. The component then reaches clients through the regular
 * item sync (≤ 1 tick) with no extra packet.</p>
 *
 * <p><b>Server-only.</b> A {@code level.isClientSide()} guard ensures the
 * handler runs exclusively on the authoritative server; the client has no
 * attachment path of its own (设计规格 物品绑定系统 §5.3) — it always receives
 * the component via item sync.</p>
 *
 * <p><b>Cost.</b> Attachment is one-shot and idempotent: after the first
 * tick each stack carries {@code gun_data} and {@code ensureGunData} returns
 * immediately, so the per-tick inventory scan is a cheap {@code has}-check
 * per slot rather than a repeated binding-table lookup. The 36+1-slot scan
 * per player per tick is negligible at binding-table sizes (设计规格 §3.3).</p>
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
     * online player's inventory stack that is recognized as a gun via the
     * binding channel (设计规格 物品绑定系统 §5.3).</p>
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
            attachInventoryItems(player, level);
        }
    }

    /**
     * Ensures {@code gun_data} is attached to every stack of a player's
     * inventory (main grid + off-hand). Armour slots are skipped: a bound
     * item converted to a gun lives in the inventory, not in an equipment
     * slot (设计规格 §5.3 "进背包即生效").
     *
     * @param player the player whose inventory to inspect
     * @param level  the ticking server level (source of the registry view)
     */
    private static void attachInventoryItems(Player player, Level level) {
        RegistryAccess access = level.registryAccess();
        for (ItemStack stack : player.getInventory().items) {
            GunRegistry.ensureGunData(stack, access);
        }
        GunRegistry.ensureGunData(player.getOffhandItem(), access);
    }
}
