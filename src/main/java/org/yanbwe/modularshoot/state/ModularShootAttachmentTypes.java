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
 * {@link PlayerStateData#CODEC}, synced to clients via
 * {@link PlayerStateData#STREAM_CODEC}, and configured with
 * {@code copyOnDeath} so the payload survives respawn
 * (设计文档 §持久化与同步 — per-player).</p>
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
     * {@link PlayerStateData#CODEC} (NBT persistence), synced with
     * {@link PlayerStateData#STREAM_CODEC} (client sync), and configured
     * with {@code copyOnDeath} so the payload is retained across respawn
     * (设计文档 §持久化与同步 — per-player).</p>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerStateData>> PLAYER_STATE =
            ATTACHMENT_TYPES.register("player_state", () ->
                    AttachmentType.builder(() -> new PlayerStateData())
                            .serialize(PlayerStateData.CODEC)
                            .sync(PlayerStateData.STREAM_CODEC)
                            .copyOnDeath()
                            .build());
}
