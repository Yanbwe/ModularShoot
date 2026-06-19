package org.yanbwe.modularshoot.shooting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Registry and execution hub for {@link ShootPredicate}s.
 *
 * <p>Provides the extension point where third-party mods register bespoke
 * pre-shoot conditions (设计文档 §射击条件判断). The framework itself
 * registers zero predicates by default; the ammo system and similar gameplay
 * are intentionally left to other mods.</p>
 *
 * <p>The predicate list is a {@link CopyOnWriteArrayList} so that registration
 * from mod init threads and iteration from the server shoot path do not
 * require external synchronisation. Registration happens rarely (during mod
 * init) while iteration happens on every shot; the copy-on-write cost is
 * therefore paid on the rare path and iteration is lock-free.</p>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 */
public final class ShootPredicateRegistry {

    /**
     * Thread-safe list of predicates registered by third-party mods.
     */
    private static final List<ShootPredicate> PREDICATES = new CopyOnWriteArrayList<>();

    private ShootPredicateRegistry() {
    }

    /**
     * Registers a custom shoot predicate.
     *
     * <p>Registered predicates are executed by {@link #testAll} on every shot,
     * after the framework's fire-rate control passes. The first predicate
     * that returns a failing {@link ShootPredicateResult} aborts the shot.</p>
     *
     * <p>Safe to call during mod common-setup; the underlying list is
     * thread-safe.</p>
     *
     * @param predicate the predicate to register; must not be {@code null}
     */
    public static void register(ShootPredicate predicate) {
        Objects.requireNonNull(predicate, "predicate");
        PREDICATES.add(predicate);
    }

    /**
     * Returns the registered predicates.
     *
     * @return an immutable copy of the current predicate list; never
     *         {@code null}, empty when no predicates have been registered
     */
    public static List<ShootPredicate> getPredicates() {
        return Collections.unmodifiableList(new ArrayList<>(PREDICATES));
    }

    /**
     * Runs every registered predicate against the shooting player and gun.
     *
     * <p>Predicates are executed in registration order. Iteration
     * short-circuits on the first failing result: per the design contract
     * "any predicate returning failure aborts the shot" (设计文档
     * §射击时序步骤三), remaining predicates are not run once a failure is
     * observed. When no predicates are registered the shot trivially passes.</p>
     *
     * @param player the player attempting to shoot; must not be {@code null}
     * @param gun    the gun item stack being fired; must not be {@code null}
     * @return {@link ShootPredicateResult#success()} when all registered
     *         predicates pass (or when none are registered); otherwise the
     *         first failing {@link ShootPredicateResult}, whose reason should
     *         be shown to the player on the action bar
     */
    public static ShootPredicateResult testAll(Player player, ItemStack gun) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gun, "gun");
        for (ShootPredicate predicate : PREDICATES) {
            ShootPredicateResult result = predicate.test(player, gun);
            if (!result.isSuccess()) {
                return result;
            }
        }
        return ShootPredicateResult.success();
    }
}
