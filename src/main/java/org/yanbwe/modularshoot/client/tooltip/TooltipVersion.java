package org.yanbwe.modularshoot.client.tooltip;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.client.ClientGunDataStore;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.state.ModularShootAttachmentTypes;
import org.yanbwe.modularshoot.state.PlayerStateData;

/**
 * Cheap version derivation for the mutable inputs of a tooltip cache key
 * (阶段 4 / 任务 4.1 审查修复).
 *
 * <p>The original implementation folded the <em>entire</em> recursive
 * {@code CompoundTag.hashCode()} of the {@link ClientGunDataStore} state and of
 * the stack {@link GunData} state into the cache key on every frame, making
 * every key construction O(state). This class replaces that with a cheap
 * identity-based version:
 * <ul>
 *   <li>Mutable payloads in this codebase ({@code ClientGunDataStore} state,
 *       the {@code PLAYER_STATE} attachment) are <em>immutable snapshots</em>
 *       that are <em>replaced by a brand-new instance</em> on every change
 *       (see {@link org.yanbwe.modularshoot.network.GunStateDiff#merge} and
 *       {@link org.yanbwe.modularshoot.state.GunStateStorage#setStateValue}).
 *       A payload's <em>identity</em> therefore changes exactly when its
 *       content <em>may</em> have changed.</li>
 *   <li>Track each stream by a stable token (the store, or a per-player
 *       UUID) and compare payloads by reference. A reference match reuses the
 *       stored version (O(1), no hash); a reference change bumps the version
 *       monotonically and snapshots the new reference.</li>
 *   <li>Because a version bump on a content-preserving-but-new-instance
 *       payload merely causes an occasional cache <em>miss</em> (a rebuild,
 *       never a stale hit), it is the correct short-term-cache trade-off.</li>
 * </ul>
 * </p>
 *
 * <p><b>Stack-side gun data</b> is <em>not</em> versioned here: its state and
 * plugin changes are already captured by {@link ItemStack#hashItemAndComponents}
 * which is folded into {@link TooltipCacheKey#stackIdentity()} — a component
 * swap (new {@link GunData} with a new state tag) changes the stack hash.
 * Only the cheap {@link GunData#modifierVersion()} counter is folded, because
 * it is an explicit anti-cheat increment that some sync paths may change while
 * the component instance stays referentially stable. Note (审查 Low) that this
 * stack hash is still recomputed recursively on <em>every</em> key
 * construction; see {@link TooltipCacheKey} for the honest per-frame cost
 * statement. A separate incremental stack-state identity is deliberately not
 * introduced here — it would split the stack key into two sources that could
 * disagree on staleness, and the short-term cache accepts the per-frame hash
 * cost in exchange for that correctness guarantee.</p>
 *
 * <p><b>Viewing-context booleans (审查 High fix):</b>
 * {@code StateTooltipBuilder}/{@code TooltipBuilder} output also depends on two
 * booleans that are <em>not</em> part of the stack / registry / modifier /
 * payload-version inputs:
 * <ul>
 *   <li>whether the hovered gun stack is the viewing player's local main-hand
 *       item — decides whether {@code resolveGunState} reads the sync store or
 *       the stack's local {@code GunData} (see
 *       {@link StateTooltipBuilder#isLocalMainHand});</li>
 *   <li>whether the viewing player's main hand holds a gun — decides whether
 *       the per-player rows are shown (see
 *       {@link StateTooltipBuilder#createPlayerStateIfHoldingGun}).</li>
 * </ul>
 * Both are folded into the data version ({@link #holdsGunInMainHand},
 * {@link #isHoveredLocalMainHand}) so a main-hand switch (持枪↔不持枪) invalidates
 * the key even though the stack identity, registry, modifier keys, and
 * {@code PLAYER_STATE} content version are all unchanged.</p>
 *
 * <p><b>Player identity in per-player version (审查 Medium fix):</b> the
 * per-player stream is tracked by the player UUID, but folding only the
 * resulting <em>counter</em> would let two different players with the same
 * counter collide. The player's UUID is folded into the data version as well,
 * so the key genuinely distinguishes viewers.</p>
 */
final class TooltipVersion {
    private TooltipVersion() {
    }

    /** Token identifying the single {@link ClientGunDataStore} state stream. */
    private static final Object STORE_TOKEN = new Object();

    /** Hard bound on tracked streams so the map never grows unboundedly. */
    private static final int MAX_TRACKED = 64;

    /**
     * Access-order map: token → {@link StreamState}. Uses access order so
     * hot players promote and the coldest per-player stream is evicted.
     */
    private static final Map<Object, StreamState> TRACKED =
            new LinkedHashMap<>(16, 0.75f, true);

    /** Immutable snapshot of one tracked stream's last-seen payload + version. */
    private record StreamState(Object payload, int version) {
    }

    /**
     * Returns a monotonically increasing version for {@code currentPayload},
     * keyed by {@code token}. The version only changes when the payload
     * <em>reference</em> changes; equal references reuse the stored version
     * with O(1) cost (no recursive hash).
     *
     * @param token          stable per-stream key (store marker or player UUID)
     * @param currentPayload the current payload reference
     * @return the current version for this stream
     */
    static int versionFor(Object token, Object currentPayload) {
        StreamState prev = TRACKED.get(token);
        if (prev != null && prev.payload == currentPayload) {
            return prev.version();
        }
        int next = (prev == null ? 0 : prev.version()) + 1;
        TRACKED.put(token, new StreamState(currentPayload, next));
        while (TRACKED.size() > MAX_TRACKED) {
            Object eldest = TRACKED.keySet().iterator().next();
            TRACKED.remove(eldest);
        }
        return next;
    }

    /**
     * Computes the combined mutable-data version for a tooltip cache key.
     *
     * <p>Folds every mutable / viewer-dependent input (any of which can change
     * the rendered tooltip while the stack identity / registry / modifier keys
     * stay the same):
     * <ol>
     *   <li>the stack's {@link GunData#modifierVersion()} (cheap counter);</li>
     *   <li>the {@link ClientGunDataStore} modifier version + identity-versioned
     *       state payload;</li>
     *   <li>the viewing-context booleans — whether the hovered stack is the
     *       local main hand (store-vs-stack state source) and whether the
     *       viewer holds a gun (per-player rows shown) — the 审查 High fix so
     *       a main-hand switch invalidates the key;</li>
     *   <li>the viewing player's {@code PLAYER_STATE} attachment (identity-
     *       versioned) plus the player's UUID (审查 Medium fix so distinct
     *       viewers cannot collide even with equal counters).</li>
     * </ol>
     * Shared by {@link StateTooltipBuilder} and {@link TooltipBuilder} so both
     * the layered caches derive an identical version for the same inputs.</p>
     *
     * @param stack          the tooltip'd gun stack
     * @param viewingPlayer  the viewing player (may be {@code null} on the main
     *                       menu, where per-player state is absent)
     * @return an int version that changes when any mutable input changes
     */
    static int mutableDataVersion(ItemStack stack, @Nullable Player viewingPlayer) {
        int version = 0;
        GunData gunData = stack.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData != null) {
            version = version * 31 + gunData.modifierVersion();
        }
        ClientGunDataStore store = ClientGunDataStore.getInstance();
        version = version * 31 + store.getModifierVersion();
        version = version * 31 + versionFor(STORE_TOKEN, store.getState());

        // 审查 High fix: fold the viewing-context booleans that change the
        // built output (store-vs-stack state source; whether per-player rows
        // are shown) so a main-hand switch invalidates the key even when every
        // other input (stack / registry / modifiers / PLAYER_STATE) is stable.
        version = version * 31 + (isHoveredLocalMainHand(stack, viewingPlayer) ? 1 : 0);
        version = version * 31 + (holdsGunInMainHand(viewingPlayer) ? 1 : 0);

        if (viewingPlayer != null) {
            PlayerStateData playerState = viewingPlayer.getData(
                    ModularShootAttachmentTypes.PLAYER_STATE.get());
            // 审查 Medium fix: fold the player identity (UUID) so two different
            // viewers with the same per-player version counter produce distinct
            // keys instead of colliding.
            version = version * 31 + viewingPlayer.getUUID().hashCode();
            version = version * 31 + versionFor(viewingPlayer.getUUID(), playerState);
        }
        return version;
    }

    /**
     * Whether the tooltip'd gun stack is the viewing player's own local
     * main-hand item (mirrors
     * {@link StateTooltipBuilder#isLocalMainHand}).
     *
     * @param stack         the gun stack being tooltip'd
     * @param viewingPlayer the viewing player, or {@code null}
     * @return {@code true} when {@code stack} is the local player's main hand
     */
    static boolean isHoveredLocalMainHand(ItemStack stack, @Nullable Player viewingPlayer) {
        // Guard against Minecraft.getInstance() being null in headless tests.
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft == null ? null : minecraft.player;
        if (localPlayer == null || viewingPlayer != localPlayer) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(stack, localPlayer.getMainHandItem());
    }

    /**
     * Whether the viewing player's main hand currently holds a gun (mirrors
     * {@link StateTooltipBuilder#createPlayerStateIfHoldingGun}).
     *
     * @param viewingPlayer the viewing player, or {@code null}
     * @return {@code true} when the viewer is non-null and holds a gun
     */
    static boolean holdsGunInMainHand(@Nullable Player viewingPlayer) {
        if (viewingPlayer == null) {
            return false;
        }
        return ModularShootAPI.isGun(
                viewingPlayer.getMainHandItem(), viewingPlayer.registryAccess());
    }
}
