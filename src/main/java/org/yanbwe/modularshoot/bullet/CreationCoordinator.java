package org.yanbwe.modularshoot.bullet;

import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.network.BulletSyncService;

/**
 * Owner of the bullet <em>creation coordination</em> side effects (阶段 6 /
 * 任务 6.3): takes a firing request, composes + registers the bullet via
 * {@link BulletFactory}, and performs the short-life <em>network marking</em>
 * (D-03, {@link BulletSyncService#markBulletCreated}) on the authoritative
 * server.
 *
 * <p>This keeps {@link BulletManager} purely about storage/retrieval (it does
 * no composition and no network marking) and {@link BulletFactory} purely about
 * record construction (it does no network marking). The only side-effecting
 * step — marking a bullet as created-this-tick so the tick-end sync includes it
 * even if it is removed by collision in the same Pre step (设计文档 §短寿命子弹保证)
 * — lives here, on the creation path.</p>
 *
 * <p>Server-only guard: {@link BulletSyncService#markBulletCreated} is
 * server-side, so client-side creation skips the marking to avoid leaking into
 * the server's drained list.</p>
 *
 * <p><b>Responsibility convergence (任务 6.3 复审说明).</b> The creation
 * coordination side effects — id allocation, gun back-track, visual
 * composition, registration <em>and</em> the short-life network marking — all
 * converge in this coordinator (creating via {@link BulletFactory}).
 * {@code BulletManager} is therefore pure storage/retrieval and
 * {@code BulletTickHandler}/{@link BulletSyncService} are left untouched by
 * the split: no marking or creation logic was moved into them, and none is
 * needed because every firing path (player engine, direct API, custom
 * sources) funnels through {@link #fireBullet} here, which marks exactly once
 * per created bullet.</p>
 */
public final class CreationCoordinator {

    /** Shared singleton used by the public firing call sites. */
    public static final CreationCoordinator INSTANCE =
            new CreationCoordinator(BulletFactory.INSTANCE, BulletSyncService::markBulletCreated);

    private final BulletFactory factory;
    private final Marker marker;

    /**
     * Package-private: constructed via {@link #INSTANCE} in production and
     * directly by the headless unit tests with a stubbed factory when needed.
     *
     * <p>Uses the production network-marking seam
     * ({@link BulletSyncService#markBulletCreated}).</p>
     *
     * @param factory the bullet creator (never {@code null})
     */
    CreationCoordinator(BulletFactory factory) {
        this(factory, BulletSyncService::markBulletCreated);
    }

    /**
     * Package-private test seam: the short-life network marking is swappable so
     * the headless tests can observe and count exactly-once marking without
     * touching the {@link BulletSyncService} static created-this-tick state.
     *
     * @param factory the bullet creator (never {@code null})
     * @param marker  the short-life marking seam (never {@code null})
     */
    CreationCoordinator(BulletFactory factory, Marker marker) {
        this.factory = factory;
        this.marker = marker;
    }

    /**
     * Injectable seam for the short-life network marking (D-03). The default
     * implementation delegates to
     * {@link BulletSyncService#markBulletCreated}; tests supply a stub so the
     * marking side effect can be verified headlessly.
     */
    @FunctionalInterface
    public interface Marker {
        /**
         * Marks a bullet as created-this-tick on the authoritative server.
         *
         * @param level  the level the bullet was created in
         * @param bullet the newly created bullet record
         */
        void mark(Level level, BulletRecord bullet);
    }

    /**
     * Fires a bullet from a custom, non-player (or player) source: creates the
     * {@link BulletRecord} via {@link BulletFactory} and marks it created-this-tick
     * for the short-life network guarantee (server only).
     *
     * @param level     the dimension to fire into (must match the manager's
     *                  dimension)
     * @param position  the launch position
     * @param direction the initial flight direction (normalised by the record)
     * @param snapshot  the frozen bullet snapshot
     * @param shooter   the shooter uuid, or {@code null} for ownerless sources
     * @param gunData   the firing gun's data, or {@code null} to back-track it
     * @return the newly created and registered {@link BulletRecord}
     */
    public BulletRecord fireBullet(
            Level level,
            Vec3 position,
            Vec3 direction,
            BulletSnapshot snapshot,
            @Nullable UUID shooter,
            @Nullable GunData gunData) {
        BulletManager manager = BulletManager.get(level);
        BulletRecord bullet = factory.createAndRegister(
                manager, level, level.registryAccess(), position, direction, snapshot, shooter, gunData);
        // Short-life bullet guarantee (D-03): ensure the tick-end sync includes
        // this bullet in the newBullets bucket even if it is removed by
        // collision before the Post tick event fires. Server-only guard: the
        // created-this-tick list is only drained from the server's
        // LevelTickEvent.Post handler, so marking on the client would leak.
        if (!level.isClientSide()) {
            marker.mark(level, bullet);
        }
        return bullet;
    }
}
