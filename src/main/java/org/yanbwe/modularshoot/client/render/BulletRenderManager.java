package org.yanbwe.modularshoot.client.render;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.ClientBulletSnapshot;
import org.yanbwe.modularshoot.network.BulletS2CPacket;
import org.yanbwe.modularshoot.network.BulletS2CPacket.DeltaBulletEntry;
import org.yanbwe.modularshoot.network.BulletS2CPacket.FullBulletEntry;

/**
 * Client-side singleton managing the set of in-flight {@link BulletRenderObject}
 * instances (设计文档 §渲染对象与渲染管理器).
 *
 * <p>This is the client-side counterpart to the server-side
 * {@code BulletManager}. Where the server manages logical bullets (collision,
 * damage, trajectory), this manager only handles pure visual data — it never
 * participates in game logic, collision detection, or vanilla entity
 * traversal. This upholds the core design principle that bullets are not
 * Minecraft entities (设计文档 §客户端视觉同步与渲染).</p>
 *
 * <p>Lifecycle is driven entirely by {@link BulletS2CPacket} messages from the
 * server. On each packet the manager reconciles its render-object map
 * according to the packet's three buckets (设计文档 §同步策略, lines 2045-2048):</p>
 * <ul>
 *   <li>{@link BulletS2CPacket#newBullets()} (full entries) → create a
 *       {@link BulletRenderObject} (or update an existing one with full
 *       visual data, e.g. when a short-life bullet is re-created after a
 *       force-full-sync).</li>
 *   <li>{@link BulletS2CPacket#updatedBullets()} (delta entries) → update
 *       position (via {@link BulletRenderObject#updatePosition(Vec3)}) and
 *       direction of the existing render object.</li>
 *   <li>{@link BulletS2CPacket#removedBulletIds()} → destroy the render
 *       object (the bullet has expired or hit something).</li>
 * </ul>
 *
 * <p>When {@link BulletS2CPacket#forceFullSync()} is {@code true}, the manager
 * first clears its entire render-object map, then rebuilds from
 * {@link BulletS2CPacket#newBullets()} alone — the other buckets are ignored.
 * This recovers from any dropped delta packets (periodic drift recovery).</p>
 *
 * <p>Position interpolation: each render object keeps a
 * {@link BulletRenderObject#getPrevPosition() prevPosition} that lags one tick
 * behind {@link BulletRenderObject#getPosition() position}. The renderer
 * blends the two with {@link RenderInterpolation#lerpPosition(Vec3, Vec3, float)}
 * using the frame's {@code partialTick} to smooth high-speed bullets across
 * frames (设计文档 §位置插值).</p>
 *
 * <p>Render objects are cleaned up automatically on level unload and player
 * disconnect via {@link ClientPlayerNetworkEvent.LoggingOut} and
 * {@link LevelEvent.Unload} event handlers.</p>
 *
 * <p><b>Threading:</b> all methods are invoked on the main client thread —
 * packet handlers via {@code enqueueWork}, event handlers via the event bus,
 * and the render dispatcher via {@code RenderLevelStageEvent}. No explicit
 * synchronization is needed for the render-object map, though
 * {@link #getInstance()} is synchronized for safe lazy initialization.</p>
 *
 * @see BulletRenderObject
 * @see BulletS2CPacket
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class BulletRenderManager {

    private static BulletRenderManager instance;

    private final Map<Integer, BulletRenderObject> renderObjects = new HashMap<>();

    /**
     * Per-bullet client-side snapshot projections keyed by bullet id
     * (设计文档 §特性视觉钩子, line 1298).
     *
     * <p>Populated from {@link FullBulletEntry#snapshot()} when a render
     * object is created or fully updated, and evicted when the bullet is
     * removed. Exposed to visual-tick hooks via {@link #getSnapshot(int)} so
     * trait callbacks can read the bullet's frozen stats/traits and adjust
     * appearance in-flight. Kept in lock-step with {@link #renderObjects}:
     * every put/remove on one map is mirrored on the other.</p>
     */
    private final Map<Integer, ClientBulletSnapshot> snapshots = new HashMap<>();

    private BulletRenderManager() {
    }

    /**
     * Returns the singleton manager instance, creating it on first call.
     *
     * @return the client-side bullet render manager
     */
    public static synchronized BulletRenderManager getInstance() {
        if (instance == null) {
            instance = new BulletRenderManager();
        }
        return instance;
    }

    /**
     * Processes a {@link BulletS2CPacket}, reconciling the render-object map
     * with the server's current bullet state (设计文档 §客户端创建).
     *
     * <p>When {@link BulletS2CPacket#forceFullSync()} is {@code true}, the
     * entire render-object map is cleared and rebuilt from
     * {@link BulletS2CPacket#newBullets()} alone — the other buckets are
     * ignored. This is the periodic drift-recovery / initial-sync path.</p>
     *
     * <p>Otherwise (incremental delta packet):</p>
     * <ul>
     *   <li>{@link BulletS2CPacket#newBullets()} →
     *       {@link #createRenderObject(FullBulletEntry)} (or
     *       {@link #updateRenderObjectFull(BulletRenderObject, FullBulletEntry)}
     *       if the id already exists, e.g. after a dropped remove packet).</li>
     *   <li>{@link BulletS2CPacket#updatedBullets()} →
     *       {@link #updateRenderObjectDelta(BulletRenderObject, DeltaBulletEntry)}
     *       for each existing render object.</li>
     *   <li>{@link BulletS2CPacket#removedBulletIds()} → destroy the render
     *       object.</li>
     * </ul>
     *
     * @param packet the sync packet from the server
     */
    public void handlePacket(BulletS2CPacket packet) {
        if (packet.forceFullSync()) {
            renderObjects.clear();
            snapshots.clear();
            for (FullBulletEntry entry : packet.newBullets()) {
                createRenderObject(entry);
            }
            return;
        }
        processNewBullets(packet.newBullets());
        processUpdatedBullets(packet.updatedBullets());
        processRemovedBullets(packet.removedBulletIds());
    }

    /**
     * Creates or updates render objects from the new-bullets (full-data)
     * bucket.
     *
     * <p>An id that already exists (e.g. after a dropped remove packet) is
     * updated with the full visual data rather than discarded, keeping the
     * client in sync with the server's authoritative style.</p>
     *
     * @param newBullets the full-data entries to create or update
     */
    private void processNewBullets(List<FullBulletEntry> newBullets) {
        for (FullBulletEntry entry : newBullets) {
            BulletRenderObject existing = renderObjects.get(entry.bulletId());
            if (existing != null) {
                updateRenderObjectFull(existing, entry);
            } else {
                createRenderObject(entry);
            }
        }
    }

    /**
     * Updates existing render objects from the updated-bullets (delta)
     * bucket. Entries whose id is not in the map are silently skipped — the
     * server's periodic force-full-sync will recover them.
     *
     * @param updatedBullets the delta entries to apply
     */
    private void processUpdatedBullets(List<DeltaBulletEntry> updatedBullets) {
        for (DeltaBulletEntry entry : updatedBullets) {
            BulletRenderObject existing = renderObjects.get(entry.bulletId());
            if (existing != null) {
                updateRenderObjectDelta(existing, entry);
            }
        }
    }

    /**
     * Destroys render objects listed in the removed-bullets bucket.
     *
     * @param removedBulletIds the ids of bullets that have expired
     */
    private void processRemovedBullets(List<Integer> removedBulletIds) {
        for (Integer id : removedBulletIds) {
            renderObjects.remove(id);
            snapshots.remove(id);
        }
    }

    /**
     * Creates a new {@link BulletRenderObject} from a full-data entry and
     * registers it in the map.
     *
     * <p>The {@code bulletSize} from the entry is used as the initial visual
     * scale.</p>
     *
     * @param entry the full-data entry describing the new bullet
     */
    private void createRenderObject(FullBulletEntry entry) {
        Vec3 position = new Vec3(entry.posX(), entry.posY(), entry.posZ());
        Vec3 direction = new Vec3(entry.dirX(), entry.dirY(), entry.dirZ());
        BulletRenderObject obj = new BulletRenderObject(
                entry.bulletId(),
                position,
                direction,
                entry.texture(),
                entry.modelLocation(),
                entry.renderMode(),
                entry.bulletSize());
        renderObjects.put(entry.bulletId(), obj);
        snapshots.put(entry.bulletId(), entry.snapshot());
    }

    /**
     * Updates an existing render object with full visual data (position,
     * direction, texture, model, render mode, size) from a full-data entry.
     *
     * <p>Used when a new-bullets entry references an id that already has a
     * render object (e.g. after a dropped remove packet or a force-full-sync
     * race). Position is advanced via
     * {@link BulletRenderObject#updatePosition(Vec3)} which atomically
     * archives the old position as {@code prevPosition} for frame
     * interpolation.</p>
     *
     * @param obj   the existing render object to update
     * @param entry the full-data entry with the new state
     */
    private void updateRenderObjectFull(BulletRenderObject obj, FullBulletEntry entry) {
        obj.updatePosition(new Vec3(entry.posX(), entry.posY(), entry.posZ()));
        obj.setDirection(new Vec3(entry.dirX(), entry.dirY(), entry.dirZ()));
        snapshots.put(entry.bulletId(), entry.snapshot());
    }

    /**
     * Updates an existing render object's position and direction from a
     * delta entry (position/direction only, no visual style).
     *
     * <p>Position is advanced via
     * {@link BulletRenderObject#updatePosition(Vec3)} which atomically
     * archives the old position as {@code prevPosition} for frame
     * interpolation. Direction is replaced via
     * {@link BulletRenderObject#setDirection(Vec3)}.</p>
     *
     * @param obj   the existing render object to update
     * @param entry the delta entry with the new position/direction
     */
    private void updateRenderObjectDelta(BulletRenderObject obj, DeltaBulletEntry entry) {
        obj.updatePosition(new Vec3(entry.posX(), entry.posY(), entry.posZ()));
        obj.setDirection(new Vec3(entry.dirX(), entry.dirY(), entry.dirZ()));
    }

    /**
     * Returns the render object for the given bullet id, or {@code null} if
     * no bullet with that id is currently in flight.
     *
     * <p>Used by visual-tick hooks ({@code onVisualTick}) to adjust a bullet's
     * appearance in-flight (设计文档 §客户端查询).</p>
     *
     * @param bulletId the server-assigned bullet id
     * @return the render object, or {@code null}
     */
    @Nullable
    public BulletRenderObject getRenderObject(int bulletId) {
        return renderObjects.get(bulletId);
    }

    /**
     * Returns the client-side snapshot projection for the given bullet id,
     * or {@code null} if no bullet with that id is currently in flight
     * (设计文档 §特性视觉钩子 — onVisualTick 快照参数).
     *
     * <p>The returned snapshot carries the bullet's frozen stats/traits and
     * identity at the last full-sync point. It is the data source for
     * {@code onVisualTick} hooks that need to read server-side attribute
     * values (e.g. scaling a bullet by {@code bullet_size}, or changing
     * texture when a trait is active). The snapshot is immutable; hooks read
     * it but never mutate it (in-flight mutation happens server-side and is
     * re-synced on the next full-sync).</p>
     *
     * @param bulletId the server-assigned bullet id
     * @return the client-side snapshot, or {@code null}
     */
    @Nullable
    public ClientBulletSnapshot getSnapshot(int bulletId) {
        return snapshots.get(bulletId);
    }

    /**
     * Returns all currently active render objects.
     *
     * <p>The returned collection is an unmodifiable view backed by the
     * internal map; it reflects live state. Used by the render dispatcher to
     * iterate all bullets during {@code RenderLevelStageEvent}.</p>
     *
     * @return an unmodifiable collection of all render objects
     */
    public Collection<BulletRenderObject> getAllRenderObjects() {
        return Collections.unmodifiableCollection(renderObjects.values());
    }

    /**
     * Removes all render objects.
     *
     * <p>Called automatically on level unload and player disconnect. Can also
     * be called manually if needed.</p>
     */
    public void clear() {
        renderObjects.clear();
        snapshots.clear();
    }

    /**
     * Clears all render objects when the client player logs out
     * (disconnect / world close).
     *
     * @param event the logging-out event
     */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        getInstance().clear();
    }

    /**
     * Clears all render objects when a level unloads (dimension switch, world
     * close, etc.).
     *
     * @param event the level-unload event
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        getInstance().clear();
    }
}
