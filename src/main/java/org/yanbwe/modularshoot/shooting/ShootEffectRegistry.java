package org.yanbwe.modularshoot.shooting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;

/**
 * Registry and execution hub for {@link ShootEffect}s (机制三 效果贡献者,
 * 规格 §5).
 *
 * <p>Provides the extension point where third-party mods register stackable
 * per-pellet effects (规格 §5.1). The framework itself registers zero effects
 * by default; probabilistic gameplay effects are intentionally left to other
 * mods.</p>
 *
 * <p>The effect list is a {@link CopyOnWriteArrayList} so that registration
 * from mod init threads and iteration from the server shoot path do not
 * require external synchronisation. Registration happens rarely (during mod
 * init) while iteration happens on every pellet; the copy-on-write cost is
 * therefore paid on the rare path and iteration is lock-free.</p>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 */
public final class ShootEffectRegistry {

    /**
     * Thread-safe list of effects registered by third-party mods.
     */
    private static final List<ShootEffect> EFFECTS = new CopyOnWriteArrayList<>();

    private ShootEffectRegistry() {
    }

    /**
     * Registers a per-pellet shoot effect.
     *
     * <p>Registered effects are executed by {@link #applyEffects} on every
     * pellet, right after the pellet's snapshot copy and before spread
     * application (规格 §5.1). Effects run in registration order; later
     * effects see earlier snapshot mutations.</p>
     *
     * <p>Safe to call during mod common-setup; the underlying list is
     * thread-safe.</p>
     *
     * @param effect the effect to register; must not be {@code null}
     */
    public static void register(ShootEffect effect) {
        Objects.requireNonNull(effect, "effect");
        EFFECTS.add(effect);
    }

    /**
     * Returns the registered effects.
     *
     * @return an immutable copy of the current effect list; never
     *         {@code null}, empty when no effects have been registered
     */
    public static List<ShootEffect> getEffects() {
        return Collections.unmodifiableList(new ArrayList<>(EFFECTS));
    }

    /**
     * 按注册顺序对一颗弹丸快照执行全部效果（规格 §5.1：copy 之后、applySpread 之前）。
     *
     * <p>Effects execute in registration order, each receiving the same
     * {@code pelletIndex}/{@code totalPellets} context so later effects observe
     * the snapshot mutations of earlier ones. When no effects are registered
     * this is a no-op.</p>
     *
     * <p>Exception isolation: an effect that throws an exception is logged
     * and skipped; the remaining effects still run (抛异常的第三方 effect
     * 被记录并跳过，继续执行其余 effect).</p>
     *
     * @param player       the shooting player; must not be {@code null}
     * @param gun          the gun item stack being fired; must not be {@code null}
     * @param snapshot     this pellet's copied snapshot; must not be {@code null}
     * @param pelletIndex  zero-based index of this pellet within the shot
     * @param totalPellets total number of pellets in this shot
     */
    public static void applyEffects(Player player, ItemStack gun, BulletSnapshot snapshot,
                                    int pelletIndex, int totalPellets) {
        for (ShootEffect effect : EFFECTS) {
            try {
                effect.apply(player, gun, snapshot, pelletIndex, totalPellets);
            } catch (Exception e) {
                ModularShoot.LOGGER.error(
                        "ShootEffect threw an exception; skipping this effect", e);
            }
        }
    }

    /**
     * Clears all registered effects (test isolation only; not part of the
     * public extension contract).
     */
    static void clear() {
        EFFECTS.clear();
    }
}
