package org.yanbwe.modularshoot.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.client.event.ClientBulletHitEvent;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;

/**
 * Plays client-side hit effects (particles + sound) when a
 * {@link org.yanbwe.modularshoot.network.BulletHitS2CPacket} is received
 * (设计文档 §BulletHitS2CPacket 客户端处理, lines 2033-2035).
 *
 * <p>The server is the sole authority on hit resolution and damage; this
 * handler only renders visual feedback and never mutates game state. The
 * effect type is selected by {@link HitType}: particles are always spawned
 * per hit type (damage-indicator / block-break / crit), while the sound is
 * data-driven from the gun definition's {@code sounds} slots — silent when
 * not configured. Before playing, a
 * {@link ClientBulletHitEvent} is posted to the game bus; cancelling it
 * skips the default effects entirely.</p>
 *
 * <p><b>Client-only.</b> Referenced solely from the S→C payload handler in
 * {@link org.yanbwe.modularshoot.network.ModularShootPayloads}, invoked only
 * on the physical client via {@code enqueueWork}. Uses only vanilla
 * particle/sound resources as placeholders (设计文档 §客户端资源).</p>
 *
 * @see HitType
 */
public final class ClientHitEffectHandler {

    /** Number of particles spawned per hit for visual clarity. */
    private static final int PARTICLE_COUNT = 8;

    /** Half-range of the random offset applied to each spawned particle. */
    private static final double PARTICLE_SPREAD = 0.3;

    /** Sound volume for hit effects. */
    private static final float SOUND_VOLUME = 1.0F;

    /** Sound pitch for hit effects. */
    private static final float SOUND_PITCH = 1.0F;

    private ClientHitEffectHandler() {
    }

    /**
     * Plays the appropriate hit effect for a bullet impact.
     *
     * <p>Dispatches by {@link HitType} to a type-specific effect routine.
     * The {@code hitEntityId} is currently unused but reserved for future
     * entity-specific effects (e.g. directional blood spray oriented toward
     * the hit entity).</p>
     *
     * <p>As an entry hook, a {@link ClientBulletHitEvent} is posted to the
     * game bus before any effect is played. Subscribers may cancel the event
     * to skip the default particles and sound entirely (设计文档 §音效系统 —
     * 命中特效钩子).</p>
     *
     * @param level       the client world to spawn particles/sound in
     * @param hitPos      the exact world-space hit point
     * @param hitType     kind of hit (ENTITY / BLOCK / PIERCE)
     * @param hitEntityId network id of the hit entity, or {@code -1} when
     *                    not an entity hit
     * @param soundId     data-driven hit sound id resolved by the server from
     *                    the gun definition's {@code sounds} slots, or
     *                    {@code null} when not configured (silent)
     */
    public static void playHitEffect(
            ClientLevel level, Vec3 hitPos, HitType hitType, int hitEntityId,
            @Nullable ResourceLocation soundId) {
        if (NeoForge.EVENT_BUS.post(
                new ClientBulletHitEvent(level, hitPos, hitType, hitEntityId, soundId)).isCanceled()) {
            return;
        }
        switch (hitType) {
            case ENTITY -> playEntityHitEffect(level, hitPos, soundId);
            case BLOCK -> playBlockHitEffect(level, hitPos, soundId);
            case PIERCE -> playPierceEffect(level, hitPos, soundId);
        }
    }

    /**
     * Entity-hit effect: damage-indicator particles plus the configured
     * data-driven sound (设计文档 §命中实体).
     *
     * <p>The vanilla generic hurt sound previously used as a placeholder was
     * removed because it overlapped the hit entity's own hurt sound and
     * sounded like a player being hurt; audio is now driven solely by the
     * gun definition's {@code sounds} slot.</p>
     *
     * @param level   the client world
     * @param hitPos  the hit point
     * @param soundId data-driven hit sound id, or {@code null} for silence
     */
    private static void playEntityHitEffect(ClientLevel level, Vec3 hitPos,
                                            @Nullable ResourceLocation soundId) {
        spawnParticles(level, ParticleTypes.DAMAGE_INDICATOR, hitPos);
        playConfiguredSound(level, hitPos, soundId);
    }

    /**
     * Block-hit effect: block-break particles tinted by the struck block's
     * state plus the configured data-driven sound (设计文档 §命中方块).
     *
     * @param level   the client world
     * @param hitPos  the hit point
     * @param soundId data-driven hit sound id, or {@code null} for silence
     */
    private static void playBlockHitEffect(ClientLevel level, Vec3 hitPos,
                                           @Nullable ResourceLocation soundId) {
        BlockState blockState = level.getBlockState(BlockPos.containing(hitPos));
        spawnParticles(level, new BlockParticleOption(ParticleTypes.BLOCK, blockState), hitPos);
        playConfiguredSound(level, hitPos, soundId);
    }

    /**
     * Pierce effect: crit particles plus the configured data-driven sound
     * (设计文档 §穿透).
     *
     * @param level   the client world
     * @param hitPos  the hit point
     * @param soundId data-driven hit sound id, or {@code null} for silence
     */
    private static void playPierceEffect(ClientLevel level, Vec3 hitPos,
                                         @Nullable ResourceLocation soundId) {
        spawnParticles(level, ParticleTypes.CRIT, hitPos);
        playConfiguredSound(level, hitPos, soundId);
    }

    /**
     * Spawns {@value #PARTICLE_COUNT} particles around the hit point with a
     * small random offset so the burst looks natural rather than a single
     * point.
     *
     * @param level    the client world
     * @param particle the particle option to spawn
     * @param hitPos   the centre of the particle burst
     */
    private static void spawnParticles(ClientLevel level, ParticleOptions particle, Vec3 hitPos) {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            double dx = (level.random.nextDouble() - 0.5) * PARTICLE_SPREAD;
            double dy = (level.random.nextDouble() - 0.5) * PARTICLE_SPREAD;
            double dz = (level.random.nextDouble() - 0.5) * PARTICLE_SPREAD;
            level.addParticle(particle, hitPos.x, hitPos.y, hitPos.z, dx, dy, dz);
        }
    }

    /**
     * 播放数据驱动的命中音效（设计文档 §音效系统）。
     *
     * <p>音效 ID 由服务端从枪械定义 {@code sounds} 槽位解析并随
     * {@code BulletHitS2CPacket} 下发；ID 为 null（未配置槽位）或对应的
     * {@link SoundEvent} 未注册时静音，不播放任何声音。</p>
     *
     * @param level   客户端世界
     * @param hitPos  命中点（音效播放位置）
     * @param soundId 枪械 sounds 槽位解析的音效 ID；可为 null
     */
    private static void playConfiguredSound(ClientLevel level, Vec3 hitPos,
                                            @Nullable ResourceLocation soundId) {
        if (soundId == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(soundId);
        if (sound != null) {
            playSound(level, hitPos, sound, SoundSource.NEUTRAL);
        }
    }

    /**
     * Plays a local sound at the hit position.
     *
     * @param level  the client world
     * @param hitPos the sound origin
     * @param sound  the sound event
     * @param source the sound source category
     */
    private static void playSound(ClientLevel level, Vec3 hitPos, SoundEvent sound, SoundSource source) {
        level.playLocalSound(
                hitPos.x, hitPos.y, hitPos.z,
                sound, source,
                SOUND_VOLUME, SOUND_PITCH, false);
    }
}
