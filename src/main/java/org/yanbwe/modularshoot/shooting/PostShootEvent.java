package org.yanbwe.modularshoot.shooting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * Fired after a bullet has been successfully registered with the
 * {@code BulletManager} for a shot.
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus) after
 * the {@link BulletRecord} has been created and added to the per-dimension
 * {@code BulletManager}, so listeners observe the bullet in its initial,
 * in-flight state.</p>
 *
 * <p>The {@code bulletRecord} is the live, mutable record of the spawned
 * bullet. Trait hooks or listeners may read its position, direction, snapshot,
 * and bullet id. Note that the record's flight state (position, direction,
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
    private final BulletRecord bulletRecord;

    /**
     * @param player       the player who performed the shot
     * @param gun          the gun {@link ItemStack} the shot was fired from
     * @param bulletRecord the live bullet record that was registered with the
     *                     {@code BulletManager}; never {@code null}
     */
    public PostShootEvent(Player player, ItemStack gun, BulletRecord bulletRecord) {
        this.player = player;
        this.gun = gun;
        this.bulletRecord = bulletRecord;
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
     * @return the live bullet record registered with the {@code BulletManager}
     */
    public BulletRecord getBulletRecord() {
        return bulletRecord;
    }
}
