package org.yanbwe.modularshoot.state;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Deferred register for the framework's NeoForge
 * {@link AttachmentType} entries.
 *
 * <p>Currently registers a single attachment:
 * {@link #PLAYER_STATE} — the per-player state map payload
 * (设计文档 §三层归属 — per-player 域). It is persisted to player NBT via
 * {@link PlayerStateData#CODEC} and configured with
 * {@code copyOnDeath} so the payload survives respawn
 * (设计文档 §持久化与同步 — per-player).</p>
 *
 * <p><strong>同步节流（任务 3.3）：</strong> no automatic
 * {@code .sync(...)} handler is configured. Per-player state is instead
 * flushed to the owning client at most once every
 * {@link PlayerStateThrottleManager#THROTTLE_INTERVAL_TICKS} ticks through
 * {@link org.yanbwe.modularshoot.network.PlayerStateSyncService} (driven by
 * {@link PlayerStateSyncTickHandler}), so a high-frequency write path (e.g.
 * heat accumulation every tick) no longer triggers a full attachment sync on
 * every {@code setData}. The {@link PlayerStateData#STREAM_CODEC} is still used
 * by that manual {@code PlayerStateS2CPacket} channel.</p>
 *
 * <p>The deferred register must be hooked into the mod event bus in the
 * mod constructor (see {@link ModularShoot}).</p>
 *
 * @see PlayerStateData
 */
public final class ModularShootAttachmentTypes {
    private ModularShootAttachmentTypes() {
    }

    /** Deferred register for all framework attachment types. */
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ModularShoot.MODID);

    /**
     * Per-player state map attachment.
     *
     * <p>Holds a {@link PlayerStateData} on every
     * {@link net.minecraft.world.entity.player.Player}. Serialised with
     * {@link PlayerStateData#CODEC} (NBT persistence) and configured with
     * {@code copyOnDeath} so the payload is retained across respawn
     * (设计文档 §持久化与同步 — per-player).</p>
     *
     * <p>Deliberately <em>not</em> configured with NeoForge's automatic
     * {@code .sync(...)}: automatic syncing would push the whole attachment to
     * every tracking client on every {@code setData}, defeating the throttling
     * introduced in task 3.3. Client synchronisation happens instead through
     * the throttled {@link org.yanbwe.modularshoot.network.PlayerStateS2CPacket}
     * channel, which reuses {@link PlayerStateData#STREAM_CODEC}.</p>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerStateData>> PLAYER_STATE =
            ATTACHMENT_TYPES.register("player_state", () ->
                    AttachmentType.builder(() -> new PlayerStateData())
                            .serialize(PlayerStateData.CODEC)
                            .copyOnDeath()
                            .build());
}
