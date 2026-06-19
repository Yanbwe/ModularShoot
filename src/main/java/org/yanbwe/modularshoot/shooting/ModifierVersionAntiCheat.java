package org.yanbwe.modularshoot.shooting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Server-side anti-cheat that validates the {@code modifierVersion} carried by
 * each {@link org.yanbwe.modularshoot.network.ShootC2SPacket ShootC2SPacket}
 * against the main-hand gun's current
 * {@link org.yanbwe.modularshoot.component.GunData#modifierVersion() server value}.
 *
 * <p>Implements the mismatch-counter algorithm from 设计文档 §网络同步方案
 * (修饰符版本号维护机制). The goal is to reject clients that deliberately
 * hold a stale modifier set to exploit attribute recalculation timing, while
 * tolerating the occasional mismatch caused by legitimate network jitter or
 * plugin install/remove timing.</p>
 *
 * <p><b>Algorithm:</b> for each player the server keeps a counter (initial 0)
 * and a baseline snapshot of the last-checked server version. On every shoot
 * request:</p>
 * <ul>
 *   <li><b>Match</b> (packet version == server version) &mdash; counter reset
 *       to 0, baseline updated, shot allowed.</li>
 *   <li><b>Mismatch, counter &lt; 3</b> &mdash; counter incremented, baseline
 *       updated, shot still allowed (tolerate up to 2 consecutive
 *       mismatches).</li>
 *   <li><b>Mismatch, counter &ge; 3</b> &mdash; cheat detected: shot
 *       rejected, {@code WARN} log emitted (player name, packet version,
 *       server version), counter and baseline reset. The player is
 *       <em>not</em> kicked, to avoid false positives from network jitter or
 *       legitimate plugin install/remove timing.</li>
 * </ul>
 *
 * <p><b>Semantics:</b> three consecutive mismatches since the last match
 * trigger the cheat verdict; any matching packet resets the counter. The
 * baseline is always updated to the current server version so the server
 * tracks what it last saw regardless of the outcome.</p>
 *
 * <p><b>State:</b> a {@code player uuid &rarr; AntiCheatState} map. The state
 * is mutated only on the main game thread (NeoForge runs payload handlers
 * there), so a plain {@link HashMap} is sufficient.</p>
 */
public final class ModifierVersionAntiCheat {

    /** Number of consecutive mismatches that triggers a cheat verdict. */
    private static final int CHEAT_THRESHOLD = 3;

    /** Per-player anti-cheat state: player uuid &rarr; counter + baseline. */
    private static final Map<UUID, AntiCheatState> antiCheatStates = new HashMap<>();

    private ModifierVersionAntiCheat() {
    }

    /**
     * Validates the packet's modifier version against the server's current
     * value, updating the per-player counter and baseline as described in the
     * class docs.
     *
     * @param player        the shooting player; must not be {@code null}
     * @param packetVersion the {@code modifierVersion} carried by the packet
     * @param serverVersion the {@code modifierVersion} of the player's
     *                      main-hand gun on the server
     * @return {@code true} if the shot may proceed; {@code false} if a cheat
     *         verdict was reached (counter &ge; 3) and the shot must be
     *         rejected
     */
    public static boolean validate(ServerPlayer player, int packetVersion, int serverVersion) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUUID();
        AntiCheatState state = antiCheatStates.getOrDefault(playerId, new AntiCheatState(0, serverVersion));

        if (packetVersion == serverVersion) {
            antiCheatStates.put(playerId, new AntiCheatState(0, serverVersion));
            return true;
        }
        return handleMismatch(player, playerId, packetVersion, serverVersion, state);
    }

    /**
     * Handles the mismatch branch: increments the counter, tolerates the first
     * two mismatches, and triggers the cheat verdict on the third.
     *
     * @param player        the shooting player
     * @param playerId      the player uuid
     * @param packetVersion the packet's modifier version
     * @param serverVersion the server's current modifier version
     * @param state         the current anti-cheat state
     * @return {@code true} when tolerated (counter &lt; 3); {@code false} when
     *         a cheat verdict was reached
     */
    private static boolean handleMismatch(
            ServerPlayer player, UUID playerId, int packetVersion, int serverVersion, AntiCheatState state) {
        int newCounter = state.counter() + 1;
        if (newCounter < CHEAT_THRESHOLD) {
            antiCheatStates.put(playerId, new AntiCheatState(newCounter, serverVersion));
            return true;
        }
        logCheatVerdict(player, packetVersion, serverVersion);
        antiCheatStates.put(playerId, new AntiCheatState(0, serverVersion));
        return false;
    }

    /**
     * Emits the {@code WARN} log for a cheat verdict, including the player
     * name, the packet version and the server version (设计文档 §网络同步方案).
     *
     * @param player        the player that triggered the verdict
     * @param packetVersion the packet's modifier version
     * @param serverVersion the server's current modifier version
     */
    private static void logCheatVerdict(ServerPlayer player, int packetVersion, int serverVersion) {
        ModularShoot.LOGGER.warn(
                "Modifier version anti-cheat triggered for player {}: packet version={}, server version={}",
                player.getName().getString(),
                packetVersion,
                serverVersion);
    }

    /**
     * Clears the anti-cheat state for the given player.
     *
     * <p>Should be called when the player disconnects to avoid retaining
     * per-player state indefinitely. Safe to call even when no state exists.</p>
     *
     * @param playerId the player uuid to clear; must not be {@code null}
     */
    public static void clearPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        antiCheatStates.remove(playerId);
    }

    /** Immutable per-player anti-cheat state: consecutive mismatch counter and baseline server version. */
    private record AntiCheatState(int counter, int baselineVersion) {
    }
}
