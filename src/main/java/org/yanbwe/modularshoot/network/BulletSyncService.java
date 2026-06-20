package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side per-tick bullet broadcast service (设计文档 §同步策略).
 *
 * <p>Listens to {@link LevelTickEvent.Post} — which fires <em>after</em>
 * {@code BulletTickHandler}'s {@code Pre} simulation — so bullet positions
 * are already advanced and collisions resolved before sync. For every server
 * tick the service collects all active bullets from the dimension's
 * {@link BulletManager}, culls them per-player against that player's render
 * distance, builds a per-player {@link BulletS2CPacket} and dispatches it via
 * {@link PacketDistributor#sendToPlayer}.</p>
 *
 * <p><b>Server-only.</b> A {@code level.isClientSide()} guard ensures the
 * service only runs on the authoritative server, matching the
 * NeoForge-recommended pattern for {@code LevelTickEvent} handlers.</p>
 *
 * <h2>Short-life bullet guarantee (设计文档 §短寿命子弹保证)</h2>
 * <p>High-speed / short-range bullets (e.g. shotgun pellets at close range)
 * may be created and removed within the same Pre simulation step, before the
 * Post tick event ever fires. To guarantee such bullets still appear on the
 * client for at least one render frame, the service exposes
 * {@link #broadcastBulletCreated(ServerLevel, BulletRecord)} which is called
 * by {@code ShootingEngine} immediately after a bullet is registered. This
 * sends an incremental {@link BulletS2CPacket} (carrying just the new bullet,
 * {@code fullSync = false}) to all nearby players via
 * {@link PacketDistributor#sendToPlayersNear}. The client creates the render
 * object without culling the rest of the in-flight set.</p>
 *
 * <p>The regular per-tick Post sync ({@link BulletS2CPacket#fullSync(List)})
 * continues to update positions and remove expired bullets as usual. Bullets
 * that survive to the Post event are included in the full-sync packet; those
 * already removed are simply absent, and the client culls their render
 * objects at that point (设计文档: "客户端会先收到一次创建包再收到销毁包").</p>
 *
 * <h2>Render-distance culling</h2>
 * <p>The sync radius is derived from the server's view distance
 * ({@code PlayerList.getViewDistance() * 16} blocks). Each player receives
 * only bullets whose current position falls within that radius of the
 * player's position, minimising per-player bandwidth.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BulletSyncService {

    /** Attribute id for {@code bullet_size}, read from each bullet's frozen snapshot. */
    private static final ResourceLocation BULLET_SIZE_ID = ModularShootAttributes.BULLET_SIZE.getId();

    /** Render-mode tag for the billboard pipeline, used as the default when no style is defined. */
    private static final String DEFAULT_RENDER_MODE = BulletStyle.RenderMode.BILLBOARD.getSerializedName();

    /** Sentinel entity id used when the bullet has no shooter (ownerless independent firing). */
    private static final int NO_SHOOTER = -1;

    /**
     * Per-dimension record of the bullet ids seen at the end of the previous
     * tick. Retained for future per-tick delta analysis; the short-life
     * guarantee is now handled by the immediate
     * {@link #broadcastBulletCreated(ServerLevel, BulletRecord)} broadcast
     * rather than by diffing this set. Weak keys allow unloaded dimensions
     * to be garbage-collected.
     */
    private static final Map<Level, Set<Integer>> PREVIOUS_TICK_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private BulletSyncService() {
    }

    /**
     * Fired once per tick per level after the level has finished its work.
     * Guarded to process only the authoritative server side.
     *
     * @param event the post-level-tick event carrying the ticking level
     */
    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        syncBulletsToPlayers(level);
    }

    /**
     * Immediately broadcasts a single newly created bullet to all nearby
     * clients (设计文档 §短寿命子弹保证).
     *
     * <p>Called by {@code ShootingEngine} right after a bullet is registered
     * — before the next Post tick event — so that short-life bullets (those
     * created and removed within the same Pre simulation step) still reach
     * clients for at least one render frame. The packet is an incremental
     * {@link BulletS2CPacket#incremental(List)} ({@code fullSync = false}) so
     * the client only creates the render object without culling other
     * in-flight bullets.</p>
     *
     * <p>The regular per-tick Post sync continues to update positions and
     * remove expired bullets as usual. If the bullet is already gone by the
     * next Post event, the full-sync packet simply omits it and the client
     * culls the render object at that point.</p>
     *
     * @param level  the server level the bullet was created in
     * @param bullet the newly created bullet record
     */
    public static void broadcastBulletCreated(ServerLevel level, BulletRecord bullet) {
        Vec3 pos = bullet.getPosition();
        double syncRadius = getSyncRadius(level);
        BulletS2CPacket.BulletEntry entry = toBulletEntry(bullet, level);
        BulletS2CPacket packet = BulletS2CPacket.incremental(List.of(entry));
        // null excluded player → the shooter also receives the packet so they
        // see their own shot (设计文档: "玩家能看到自己开的枪").
        PacketDistributor.sendToPlayersNear(level, null, pos.x, pos.y, pos.z, syncRadius, packet);
    }

    /**
     * Collects all active bullets, culls per-player by render distance, builds
     * a per-player {@link BulletS2CPacket} and dispatches it.
     *
     * <p>After broadcasting, the current tick's id set is stored as the next
     * tick's "previous" set for new-bullet detection.</p>
     *
     * @param level the server level whose bullets are being synced
     */
    private static void syncBulletsToPlayers(Level level) {
        BulletManager manager = BulletManager.get(level);
        Collection<BulletRecord> allBullets = manager.getAllBullets();

        ServerLevel serverLevel = (ServerLevel) level;
        List<ServerPlayer> players = serverLevel.players();
        if (players.isEmpty()) {
            updatePreviousTickIds(level, allBullets);
            return;
        }

        double syncRadius = getSyncRadius(serverLevel);
        broadcastToPlayers(allBullets, players, syncRadius, level);
        updatePreviousTickIds(level, allBullets);
    }

    /**
     * Builds and sends a per-player {@link BulletS2CPacket} containing only
     * the bullets within that player's render distance.
     *
     * @param allBullets  every active bullet in the dimension
     * @param players     every online player in the dimension
     * @param syncRadius  the render-distance-derived cull radius in blocks
     * @param level       the server level (for gun-registry lookups)
     */
    private static void broadcastToPlayers(
            Collection<BulletRecord> allBullets,
            List<ServerPlayer> players,
            double syncRadius,
            Level level) {
        for (ServerPlayer player : players) {
            List<BulletS2CPacket.BulletEntry> entries = collectVisibleBullets(allBullets, player, syncRadius, level);
            BulletS2CPacket packet = BulletS2CPacket.fullSync(entries);
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    /**
     * Collects the subset of bullets visible to a single player, applying
     * render-distance culling.
     *
     * @param bullets     every active bullet in the dimension
     * @param player      the player to sync to
     * @param syncRadius  the cull radius in blocks
     * @param level       the server level (for gun-registry lookups)
     * @return a list of bullet entries within the player's sync radius
     */
    private static List<BulletS2CPacket.BulletEntry> collectVisibleBullets(
            Collection<BulletRecord> bullets,
            ServerPlayer player,
            double syncRadius,
            Level level) {
        Vec3 playerPos = player.position();
        double syncRadiusSqr = syncRadius * syncRadius;
        List<BulletS2CPacket.BulletEntry> entries = new ArrayList<>();
        for (BulletRecord bullet : bullets) {
            Vec3 bulletPos = bullet.getPosition();
            if (playerPos.distanceToSqr(bulletPos) > syncRadiusSqr) {
                continue;
            }
            entries.add(toBulletEntry(bullet, level));
        }
        return entries;
    }

    /**
     * Converts a {@link BulletRecord} into a serialisable
     * {@link BulletS2CPacket.BulletEntry}, resolving visual style from the
     * gun registry and the shooter's network entity id.
     *
     * @param bullet the bullet record to convert
     * @param level  the server level (for gun-registry and entity lookups)
     * @return a bullet entry ready for serialisation
     */
    private static BulletS2CPacket.BulletEntry toBulletEntry(BulletRecord bullet, Level level) {
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        BulletSnapshot snapshot = bullet.getSnapshot();

        VisualStyle style = resolveVisualStyle(snapshot, level);
        float bulletSize = (float) snapshot.getStat(BULLET_SIZE_ID);
        int shooterEntityId = resolveShooterEntityId(bullet, level);

        return new BulletS2CPacket.BulletEntry(
                bullet.getBulletId(),
                pos.x, pos.y, pos.z,
                dir.x, dir.y, dir.z,
                style.texture(), style.modelLocation(), style.renderMode(),
                bulletSize, shooterEntityId);
    }

    /**
     * Resolves the bullet's visual style (texture, model path, render mode)
     * from the gun definition's {@link BulletStyle}, falling back to a
     * billboard default when the gun or style is absent
     * (设计文档 §子弹视觉样式).
     *
     * @param snapshot the bullet's frozen snapshot carrying the gun id
     * @param level    the server level providing the registry access
     * @return the resolved visual style
     */
    private static VisualStyle resolveVisualStyle(BulletSnapshot snapshot, Level level) {
        ResourceLocation gunId = snapshot.getGunId();
        if (gunId == null) {
            return VisualStyle.DEFAULT;
        }
        Optional<GunDefinition> gunDef = GunRegistry.getGun(level, gunId);
        if (gunDef.isEmpty()) {
            return VisualStyle.DEFAULT;
        }
        return gunDef.get().bulletStyle()
                .map(BulletSyncService::fromBulletStyle)
                .orElse(VisualStyle.DEFAULT);
    }

    /**
     * Extracts the texture, model path and render-mode tag from a
     * {@link BulletStyle}. The {@code model} map is keyed by render-mode tag
     * ("billboard" → texture, "3d" → model path).
     *
     * @param style the bullet style from the gun definition
     * @return the resolved visual style
     */
    private static VisualStyle fromBulletStyle(BulletStyle style) {
        String renderMode = style.renderMode().getSerializedName();
        ResourceLocation texture = style.model().get(BulletStyle.RenderMode.BILLBOARD.getSerializedName());
        ResourceLocation modelLocation = style.model().get(BulletStyle.RenderMode.THREE_D.getSerializedName());
        return new VisualStyle(texture, modelLocation, renderMode);
    }

    /**
     * Resolves the shooter's network entity id from the bullet record's
     * shooter uuid, looking the entity up in the server level.
     *
     * @param bullet the bullet record carrying the shooter uuid
     * @param level  the server level for entity lookup
     * @return the shooter's network entity id, or {@link #NO_SHOOTER} when
     *         ownerless or the entity is no longer present
     */
    private static int resolveShooterEntityId(BulletRecord bullet, Level level) {
        UUID shooterUuid = bullet.getShooter();
        if (shooterUuid == null) {
            return NO_SHOOTER;
        }
        if (level instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(shooterUuid);
            return entity != null ? entity.getId() : NO_SHOOTER;
        }
        return NO_SHOOTER;
    }

    /**
     * Returns the per-player sync radius in blocks, derived from the server's
     * view distance (设计文档 §同步范围). Each chunk is 16 blocks wide.
     *
     * @param serverLevel the server level providing the server instance
     * @return the sync radius in blocks
     */
    private static double getSyncRadius(ServerLevel serverLevel) {
        int viewDistance = serverLevel.getServer().getPlayerList().getViewDistance();
        return viewDistance * 16.0;
    }

    /**
     * Stores the current tick's bullet id set as the next tick's "previous"
     * set, enabling new-bullet detection for the short-life guarantee
     * (设计文档 §短寿命子弹保证).
     *
     * @param level      the dimension whose id set is being tracked
     * @param allBullets every active bullet in the dimension this tick
     */
    private static void updatePreviousTickIds(Level level, Collection<BulletRecord> allBullets) {
        Set<Integer> currentIds = new HashSet<>(allBullets.size());
        for (BulletRecord bullet : allBullets) {
            currentIds.add(bullet.getBulletId());
        }
        PREVIOUS_TICK_IDS.put(level, currentIds);
    }

    /**
     * Resolved visual style triple carried through the conversion pipeline.
     *
     * @param texture       billboard-mode texture path, or {@code null}
     * @param modelLocation 3d-mode model path, or {@code null}
     * @param renderMode    rendering pipeline tag
     */
    private record VisualStyle(
            ResourceLocation texture,
            ResourceLocation modelLocation,
            String renderMode) {

        /** Default style used when no gun or bullet style is defined. */
        static final VisualStyle DEFAULT = new VisualStyle(null, null, DEFAULT_RENDER_MODE);
    }
}
