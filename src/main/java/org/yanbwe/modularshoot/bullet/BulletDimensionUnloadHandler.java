package org.yanbwe.modularshoot.bullet;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.trait.RemoveReason;

/**
 * Evicts every bullet from a dimension's {@link BulletManager} when that
 * dimension unloads (设计文档 §维度卸载移除, K5).
 *
 * <p>{@link BulletManager} stores per-dimension instances in a
 * {@link java.util.WeakHashMap} keyed by {@link Level}; when a dimension
 * unloads the {@code Level} object becomes unreachable and the manager is
 * silently garbage-collected. Without explicit eviction the
 * {@code ON_REMOVE} trait hook would never fire with
 * {@link RemoveReason#DIMENSION_UNLOAD}, leaking any resources held by
 * listeners (particle systems, external mod state records, etc.).</p>
 *
 * <p>This handler bridges that gap: on {@link LevelEvent.Unload} it looks up
 * the dimension's already-existing manager (without creating one) and evicts
 * every bullet with {@link RemoveReason#DIMENSION_UNLOAD} before the
 * {@code Level} is released. After eviction the manager is empty; the
 * {@code WeakHashMap} entry is reclaimed naturally once the {@code Level}
 * becomes unreachable.</p>
 *
 * <p><b>Both logical sides.</b> {@link LevelEvent.Unload} fires on both the
 * server and the client, and {@code BulletManager} instances exist on both
 * sides (the client mirrors server bullet state for visual-tick hooks), so
 * cleanup runs on the client too — matching the client-side render-object
 * cleanup already performed by
 * {@link org.yanbwe.modularshoot.client.render.BulletRenderManager#onLevelUnload(LevelEvent.Unload)}.</p>
 *
 * <p><b>Event bus.</b> Per the {@link LevelEvent} contract, this event is
 * fired on the {@linkplain net.neoforged.neoforge.common.NeoForge#EVENT_BUS
 * main NeoForge event bus}; the {@link EventBusSubscriber} annotation with
 * no explicit {@code bus} parameter defaults to the game bus, which is
 * correct here.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BulletDimensionUnloadHandler {

    private BulletDimensionUnloadHandler() {
    }

    /**
     * Evicts all bullets from the unloading dimension's manager so their
     * {@code ON_REMOVE} hooks fire with {@link RemoveReason#DIMENSION_UNLOAD}.
     *
     * <p>{@link LevelEvent.Unload#getLevel()} returns a
     * {@link LevelAccessor}; only actual {@link Level} instances own a
     * {@code BulletManager} (a {@code LevelAccessor} may also be a
     * chunk-scoped view that has no manager), so non-{@code Level}
     * accessors are skipped. If the dimension never had a manager created
     * (no bullets were ever spawned there) the lookup returns {@code null}
     * and nothing happens — no empty manager is created just to be
     * discarded.</p>
     *
     * @param event the level-unload event carrying the unloading dimension
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof Level level)) {
            return;
        }
        BulletManager manager = BulletManager.getExisting(level);
        if (manager == null) {
            return;
        }
        manager.evictAll(RemoveReason.DIMENSION_UNLOAD);
    }
}
