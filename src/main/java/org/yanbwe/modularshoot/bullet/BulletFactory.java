package org.yanbwe.modularshoot.bullet;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.util.GunResolver;

import java.util.UUID;

/**
 * Owns the <em>creation</em> half of the bullet system (阶段 6 / 任务 6.3).
 *
 * <p>Before the responsibility split, {@link BulletManager#fireBullet} mixed
 * four concerns into one method: unique-id allocation, gun back-tracking
 * ({@link GunResolver} reverse inventory scan), full visual composition
 * ({@link VisualCompositionService#compose}) and network marking
 * ({@link org.yanbwe.modularshoot.network.BulletSyncService#markBulletCreated}).
 * This class takes the creation concerns so that {@link BulletManager} becomes
 * a pure storage/retrieval index.</p>
 *
 * <p>Responsibilities here:</p>
 * <ul>
 *   <li><b>Unique id allocation</b> &mdash; delegated to the owning
 *       {@link BulletManager#nextBulletId()} (the counter is manager-scoped
 *       storage identity, kept with the manager).</li>
 *   <li><b>Gun back-track</b> &mdash; when the caller does not already hold a
 *       {@link GunData} (independent firing from a turret/trap/custom source),
 *       the firing gun is reverse-looked-up from the snapshot's gun instance
 *       uuid via {@link GunResolver}.</li>
 *   <li><b>Visual composition</b> &mdash; {@link #composer} is invoked exactly
 *       once at creation (spec §2.1 "创建瞬间冻结") and the result is frozen on
 *       the produced {@link BulletRecord}.</li>
 *   <li><b>Registration</b> &mdash; the produced record is registered with the
 *       owning {@link BulletManager} (id + spatial index).</li>
 * </ul>
 *
 * <p><b>Deliberately <em>not</em> done here</b>: network marking. The
 * short-life guarantee (D-03) is the caller's / {@link CreationCoordinator}'s
 * concern, so this factory stays a pure creator and can be unit-tested headlessly
 * with a stubbed {@link Composer}.</p>
 */
public final class BulletFactory {

    /**
     * Injectable visual-composition seam. The default implementation delegates
     * to {@link VisualCompositionService#INSTANCE}; tests supply a stub so
     * creation never touches a live registry.
     */
    @FunctionalInterface
    public interface Composer {
        /**
         * Composes the bullet's visual style once at creation.
         *
         * @param ra       the runtime registry view (may be {@code null} in
         *                 tests that stub this seam)
         * @param snapshot the frozen bullet snapshot
         * @param gunData  the firing gun's data, or {@code null} for
         *                 independent firing
         * @return the composed style, never {@code null}
         */
        ComposedBulletStyle compose(
                @Nullable RegistryAccess ra,
                @Nullable BulletSnapshot snapshot,
                @Nullable GunData gunData);
    }

    /** Shared singleton used by the coordinator and call sites. */
    public static final BulletFactory INSTANCE = new BulletFactory(
            (ra, snapshot, gunData) -> VisualCompositionService.INSTANCE.compose(ra, snapshot, gunData));

    private final Composer composer;

    /**
     * Package-private: constructed via {@link #INSTANCE} in production and
     * directly with a stubbed {@link Composer} by the headless unit tests in
     * this package.
     *
     * @param composer the visual-composition seam (never {@code null})
     */
    BulletFactory(Composer composer) {
        this.composer = composer;
    }

    /**
     * Creates and registers a {@link BulletRecord} from a firing request.
     *
     * <p>Allocates a fresh per-dimension id from {@code manager}, resolves the
     * firing gun's {@link GunData} (supplied directly by the caller when
     * available, otherwise back-tracked from the snapshot via
     * {@link GunResolver} — which is safe with a {@code null} {@code level}),
     * composes the visual style once, constructs the record and registers it
     * with {@code manager}.</p>
     *
     * <p>This method performs <b>no network marking</b> (D-03 created-this-tick
     * bookkeeping). The caller or {@link CreationCoordinator#fireBullet} is
     * responsible for that, keeping the factory a pure creator.</p>
     *
     * @param manager        the dimension's bullet manager (id source + storage)
     * @param level          the level used only for gun back-tracking, or
     *                       {@code null} when no back-track is needed
     *                       (supplied {@code gunData})
     * @param registryAccess the runtime registry view used for visual
     *                       composition
     * @param position       the launch position
     * @param direction      the initial flight direction (will be normalised)
     * @param snapshot       the frozen bullet snapshot
     * @param shooter        the shooter uuid, or {@code null} for ownerless
     * @param gunData        the firing gun's data, or {@code null} to
     *                       back-track it from the snapshot
     * @return the newly created, already-registered {@link BulletRecord}
     */
    public BulletRecord createAndRegister(
            BulletManager manager,
            @Nullable Level level,
            RegistryAccess registryAccess,
            Vec3 position,
            Vec3 direction,
            BulletSnapshot snapshot,
            @Nullable UUID shooter,
            @Nullable GunData gunData) {
        int bulletId = manager.nextBulletId();
        GunData effectiveGunData = gunData;
        if (effectiveGunData == null) {
            effectiveGunData = backTrackGunData(snapshot, level);
        }
        ComposedBulletStyle composed = composer.compose(registryAccess, snapshot, effectiveGunData);
        BulletRecord bullet = new BulletRecord(snapshot, shooter, position, direction, bulletId, composed);
        manager.addBullet(bullet);
        return bullet;
    }

    /**
     * Reverse-looks-up the firing gun's {@link GunData} from the snapshot's
     * gun instance uuid, when the caller did not supply it (independent
     * firing path). Returns {@code null} when no gun is resolvable, mirroring
     * the pre-split {@code fireBullet} degradation.
     */
    @Nullable
    private static GunData backTrackGunData(BulletSnapshot snapshot, @Nullable Level level) {
        ItemStack gunStack = GunResolver.resolveGunFromSnapshot(snapshot, level);
        if (gunStack == null) {
            return null;
        }
        return gunStack.get(ModularShootDataComponents.GUN_DATA.get());
    }
}
