package org.yanbwe.modularshoot.shooting;

import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * Fired after the bullets of a shot have been successfully registered with
 * the {@code BulletManager}.
 *
 * <p>This event carries every bullet record of the shot, in registration
 * order. {@link #getBulletRecord()} returns the first bullet and is kept so
 * single-pellet callers behave exactly as before (规格 §V7); {@link #getBullets()}
 * exposes the full list so rule and barrage listeners can
 * {@code setDirection()} each bullet individually, overriding its trajectory
 * (规格 §4).</p>
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus) after
 * the {@link BulletRecord}s have been created and added to the per-dimension
 * {@code BulletManager}, so listeners observe the bullets in their initial,
 * in-flight state.</p>
 *
 * <p>The bullet records are the live, mutable records of the spawned bullets.
 * Trait hooks or listeners may read their position, direction, snapshot, and
 * bullet id. Note that each record's flight state (position, direction,
 * traveled distance, age) will continue to mutate as the bullet advances each
 * tick; listeners that need a frozen view should copy the relevant fields.</p>
 *
 * <p>This event is <strong>not</strong> cancelable. To prevent a shot from
 * happening, listen to {@link PreShootEvent} instead.</p>
 *
 * @see PreShootEvent
 * @see BulletRecord
 */
public class PostShootEvent extends Event {
    private final Player player;
    private final ItemStack gun;
    private final List<BulletRecord> bullets;

    /**
     * @param player  the player who performed the shot
     * @param gun     the gun {@link ItemStack} the shot was fired from
     * @param bullets every live bullet record registered for this shot, in
     *                registration order; never empty, never {@code null}
     */
    public PostShootEvent(Player player, ItemStack gun, List<BulletRecord> bullets) {
        this.player = player;
        this.gun = gun;
        this.bullets = List.copyOf(bullets);
    }

    /**
     * Single-bullet convenience constructor, kept for zero-break compatibility
     * (规格 §V7); delegates to the list constructor.
     *
     * @param player       the player who performed the shot
     * @param gun          the gun {@link ItemStack} the shot was fired from
     * @param bulletRecord the live bullet record that was registered with the
     *                     {@code BulletManager}; never {@code null}
     */
    public PostShootEvent(Player player, ItemStack gun, BulletRecord bulletRecord) {
        this(player, gun, List.of(bulletRecord));
    }

    /**
     * @return the player who performed the shot
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the gun item stack the shot was fired from
     */
    public ItemStack getGun() {
        return gun;
    }

    /**
     * @return the first bullet record of the shot; identical to the previous
     *         single-bullet behavior (equivalent to {@code getBullets().get(0)})
     */
    public BulletRecord getBulletRecord() {
        return bullets.get(0);
    }

    /**
     * @return all bullet records of this shot, in registration order; always
     *         contains at least one bullet
     */
    public List<BulletRecord> getBullets() {
        return bullets;
    }
}
