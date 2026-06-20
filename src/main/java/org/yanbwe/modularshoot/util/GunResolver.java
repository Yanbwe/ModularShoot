package org.yanbwe.modularshoot.util;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;

/**
 * Pure-function utility for backtracking the firing gun {@link ItemStack} from
 * a {@link BulletSnapshot}.
 *
 * <p>This is a read-only helper used by trait runtime hooks (e.g.
 * {@code onHit}) that need to write per-gun state back to the gun that fired
 * the bullet. The class is not instantiable; every method is static and
 * side-effect free, so it is safe to call from any thread that holds a valid
 * {@link Level} reference (设计文档 §resolveGunFromSnapshot).</p>
 *
 * <p>The backtrack walks three links: snapshot &rarr; shooter uuid &rarr;
 * player &rarr; inventory stack. Each step is null-checked so the method never
 * throws; when any link is missing it returns {@code null} and the caller is
 * expected to fall back to per-player state (设计文档 §已知局限).</p>
 */
public final class GunResolver {

    private GunResolver() {
    }

    /**
     * Resolves the gun ItemStack that fired the given bullet snapshot.
     *
     * <p>Three-step backtrack algorithm (设计文档 §resolveGunFromSnapshot):</p>
     * <ol>
     *   <li>Read {@link BulletSnapshot#getGunInstanceUuid()}; if {@code null}
     *       (independent firing such as turrets/traps) return {@code null}.</li>
     *   <li>Read {@link BulletSnapshot#getShooter()} (player uuid); if
     *       {@code null} return {@code null}.</li>
     *   <li>Look up the player in {@code level}; if {@code level} is null or
     *       the player is offline/not found return {@code null}.</li>
     *   <li>Scan the player's main inventory and offhand for a
     *       {@code modularshoot:gun} stack whose
     *       {@link GunData#gunInstanceUuid()} equals the snapshot's uuid;
     *       return the first match, or {@code null} when not found.</li>
     * </ol>
     *
     * <p>Known limitation: if the player has dropped, stored or cross-dimension
     * moved the gun before the bullet hits, this method returns {@code null}
     * and per-gun state cannot be written. Switching main hand while the gun
     * remains in the inventory is covered by the uuid lookup and will not
     * lose state.</p>
     *
     * @param snapshot the bullet snapshot to backtrack from; must not be null
     * @param level    the level to resolve the player in, or {@code null}
     * @return the firing gun ItemStack, or {@code null} when the gun cannot be
     *         located
     */
    @Nullable
    public static ItemStack resolveGunFromSnapshot(BulletSnapshot snapshot, @Nullable Level level) {
        // Step 1: independent firing (turrets/traps) carries no gun instance uuid
        UUID gunInstanceUuid = snapshot.getGunInstanceUuid();
        if (gunInstanceUuid == null) {
            return null;
        }

        // Step 2: no shooter means no player inventory to scan
        UUID shooterUuid = snapshot.getShooter();
        if (shooterUuid == null) {
            return null;
        }

        // Step 3: resolve the player from the level
        Player player = resolvePlayer(level, shooterUuid);
        if (player == null) {
            return null;
        }

        // Step 4: scan the player's inventory for the matching gun stack
        return findGunInInventory(player, gunInstanceUuid);
    }

    /**
     * Looks up a player by uuid in the given level.
     *
     * <p>On the client side {@link Level#getPlayerByUUID} iterates the local
     * player list (the method is a default on {@code EntityGetter} and is
     * available on both client and server {@link Level}). On the server side
     * {@link ServerLevel#getEntity(UUID)} performs an O(1) entity-manager
     * lookup and the result is narrowed to a {@link Player}.</p>
     *
     * @param level       the level to search; if {@code null} returns
     *                    {@code null}
     * @param shooterUuid the player uuid to find
     * @return the player, or {@code null} when the level is null or the player
     *         is not loaded in this level
     */
    @Nullable
    private static Player resolvePlayer(@Nullable Level level, UUID shooterUuid) {
        if (level == null) {
            return null;
        }
        if (level.isClientSide()) {
            return level.getPlayerByUUID(shooterUuid);
        }
        // Server side: prefer the O(1) entity-manager lookup, narrow to Player
        Entity entity = ((ServerLevel) level).getEntity(shooterUuid);
        return entity instanceof Player player ? player : null;
    }

    /**
     * Scans the player's main inventory and offhand for the gun stack whose
     * {@code gunInstanceUuid} matches the target.
     *
     * <p>{@code player.getInventory().items} is the 36-slot main inventory
     * (hotbar + main grid). The offhand is checked separately via
     * {@link Player#getOffhandItem()}. Armor slots are intentionally skipped
     * since a gun cannot be equipped there.</p>
     *
     * @param player          the player whose inventory to scan
     * @param gunInstanceUuid the target gun instance uuid
     * @return the matching gun stack, or {@code null} when not found
     */
    @Nullable
    private static ItemStack findGunInInventory(Player player, UUID gunInstanceUuid) {
        for (ItemStack stack : player.getInventory().items) {
            if (matchesGun(stack, gunInstanceUuid)) {
                return stack;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        return matchesGun(offhand, gunInstanceUuid) ? offhand : null;
    }

    /**
     * Tests whether a stack is a {@code modularshoot:gun} item carrying the
     * target gun instance uuid.
     *
     * @param stack           the stack to test
     * @param gunInstanceUuid the target gun instance uuid
     * @return {@code true} when the stack is a gun whose
     *         {@link GunData#gunInstanceUuid()} equals the target
     */
    private static boolean matchesGun(ItemStack stack, UUID gunInstanceUuid) {
        if (!ModularShootAPI.isGun(stack)) {
            return false;
        }
        GunData gunData = stack.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData != null && gunInstanceUuid.equals(gunData.gunInstanceUuid());
    }
}
