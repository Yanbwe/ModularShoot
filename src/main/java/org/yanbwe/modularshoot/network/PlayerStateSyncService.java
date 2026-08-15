package org.yanbwe.modularshoot.network;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.state.ModularShootAttachmentTypes;
import org.yanbwe.modularshoot.state.PlayerStateData;
import org.yanbwe.modularshoot.state.PlayerStateThrottleManager;

/**
 * Server-side per-player state sync orchestrator (任务 3.3 — PlayerState 同步节流).
 *
 * <p>The {@code PLAYER_STATE} attachment has no automatic {@code .sync(...)}
 * handler (removed in task 3.3), so the server must manually push per-player
 * state to the owning client. Because the attachment is a per-<em>player</em>
 * payload, the only client that needs it is the player themselves (no other
 * entity tracks a player's own attachment), so the sync is always a direct
 * {@link PacketDistributor#sendToPlayer} to that player.</p>
 *
 * <h2>Trigger scenarios</h2>
 * <ol>
 *   <li><b>Player login</b> — a <em>critical moment</em> that bypasses the
 *       throttle: the player is delivered their full current state immediately
 *       so the client never starts from a stale/empty baseline
 *       ({@link #onPlayerLoggedIn}).</li>
 *   <li><b>Per-player state modified</b> — {@link
 *       org.yanbwe.modularshoot.state.PlayerState} write accessors flag the
 *       player through
 *       {@link PlayerStateThrottleManager#markDirty}; the throttled flush is
 *       handled by
 *       {@link org.yanbwe.modularshoot.state.PlayerStateSyncTickHandler}, which
 *       calls {@link #syncStateToPlayer} at the throttle cadence. This service
 *       is otherwise not involved in that path.</li>
 * </ol>
 *
 * <p><b>Server-only.</b> Every handler guards against
 * {@code level().isClientSide()} so the authoritative server is the sole
 * sender, matching the pattern in {@link GunSyncService}.</p>
 *
 * @see PlayerStateS2CPacket
 * @see org.yanbwe.modularshoot.state.PlayerStateSyncTickHandler
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class PlayerStateSyncService {

    private PlayerStateSyncService() {
    }

    /**
     * Performs a full per-player state sync when a player logs into the server.
     *
     * <p>This is a critical-moment sync that bypasses the throttle: because the
     * attachment is no longer auto-synced, the client must receive its complete
     * baseline on login (otherwise the local {@code PlayerState} reads would
     * start from an empty payload).</p>
     *
     * @param event the login event
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        syncStateToPlayer(player);
    }

    /**
     * Removes the player's throttle entry on logout to prevent unbounded growth
     * of the runtime-only throttle map.
     *
     * @param event the logout event
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerStateThrottleManager.getInstance().remove(event.getEntity().getUUID());
    }

    /**
     * Sends the player's full current {@link PlayerStateData} to that player's
     * own client via a {@link PlayerStateS2CPacket}.
     *
     * <p>Used both by the login critical-moment sync and by
     * {@link org.yanbwe.modularshoot.state.PlayerStateSyncTickHandler} for the
     * throttled flush. The payload is read fresh from the player's attachment
     * so the client always receives the latest authoritative state.</p>
     *
     * @param player the player to sync to; must not be {@code null}
     */
    public static void syncStateToPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PlayerStateData data = player.getData(ModularShootAttachmentTypes.PLAYER_STATE.get());
        PacketDistributor.sendToPlayer(player, new PlayerStateS2CPacket(data));
    }
}
