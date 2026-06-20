package org.yanbwe.modularshoot.client.render;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.network.BulletS2CPacket;
import org.yanbwe.modularshoot.network.BulletS2CPacket.BulletEntry;

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
 * server. On each packet:</p>
 * <ul>
 *   <li>new bullet ids → create a {@link BulletRenderObject}</li>
 *   <li>existing bullet ids → update position (via
 *       {@link BulletRenderObject#updatePosition(Vec3)}) and direction</li>
 *   <li>ids present in the manager but absent from a <b>full-sync</b> packet →
 *       destroy the render object (the bullet has expired or hit something).
 *       Incremental packets (short-life creation broadcast) never cull.</li>
 * </ul>
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
     * with the server's current bullet set (设计文档 §客户端创建).
     *
     * <p>For each {@link BulletEntry} in the packet:</p>
     * <ul>
     *   <li>new bullet id → {@link #createRenderObject(BulletEntry)}</li>
     *   <li>existing bullet id →
     *       {@link #updateRenderObject(BulletRenderObject, BulletEntry)}</li>
     * </ul>
     * <p>After processing all entries, if the packet is a <b>full-sync</b>
     * ({@link BulletS2CPacket#fullSync()} is {@code true}), any render object
     * whose id is not in the packet is removed — the corresponding bullet has
     * expired or hit something on the server. Incremental packets
     * ({@code fullSync = false}, used for the short-life creation broadcast)
     * only create/update and never cull, so a single-bullet packet does not
     * wipe the rest of the in-flight set.</p>
     *
     * @param packet the sync packet from the server
     */
    public void handlePacket(BulletS2CPacket packet) {
        Set<Integer> currentIds = new HashSet<>();
        for (BulletEntry entry : packet.bullets()) {
            currentIds.add(entry.bulletId());
            BulletRenderObject existing = renderObjects.get(entry.bulletId());
            if (existing != null) {
                updateRenderObject(existing, entry);
            } else {
                createRenderObject(entry);
            }
        }
        // Only a full-sync packet represents the complete in-flight set;
        // incremental packets (short-life creation broadcast) must not cull
        // bullets that are simply absent from the partial list.
        if (packet.fullSync()) {
            renderObjects.keySet().removeIf(id -> !currentIds.contains(id));
        }
    }

    /**
     * Creates a new {@link BulletRenderObject} from a packet entry and
     * registers it in the map.
     *
     * <p>The {@code bulletSize} from the entry is used as the initial visual
     * scale.</p>
     *
     * @param entry the bullet entry describing the new bullet
     */
    private void createRenderObject(BulletEntry entry) {
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
    }

    /**
     * Updates an existing render object's position and direction from a
     * packet entry.
     *
     * <p>Position is advanced via
     * {@link BulletRenderObject#updatePosition(Vec3)} which atomically
     * archives the old position as {@code prevPosition} for frame
     * interpolation. Direction is replaced via
     * {@link BulletRenderObject#setDirection(Vec3)}.</p>
     *
     * @param obj   the existing render object to update
     * @param entry the packet entry with the new state
     */
    private void updateRenderObject(BulletRenderObject obj, BulletEntry entry) {
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
