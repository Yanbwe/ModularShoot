package org.yanbwe.modularshoot.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;

/**
 * Plays client-side hit effects (particles + sound) when a
 * {@link org.yanbwe.modularshoot.network.BulletHitS2CPacket} is received
 * (设计文档 §BulletHitS2CPacket 客户端处理, lines 2033-2035).
 *
 * <p>The server is the sole authority on hit resolution and damage; this
 * handler only renders visual feedback and never mutates game state. The
 * effect type is selected by {@link HitType}: entity hits spawn
 * damage-indicator particles plus a generic hurt sound, block hits spawn
 * block-break particles tinted by the struck block's state plus a
 * stone-hit sound, and pierce hits spawn crit particles plus a crit
 * sound.</p>
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
     * @param level       the client world to spawn particles/sound in
     * @param hitPos      the exact world-space hit point
     * @param hitType     kind of hit (ENTITY / BLOCK / PIERCE)
     * @param hitEntityId network id of the hit entity, or {@code -1} when
     *                    not an entity hit
     */
    public static void playHitEffect(
            ClientLevel level, Vec3 hitPos, HitType hitType, int hitEntityId) {
        switch (hitType) {
            case ENTITY -> playEntityHitEffect(level, hitPos);
            case BLOCK -> playBlockHitEffect(level, hitPos);
            case PIERCE -> playPierceEffect(level, hitPos);
        }
    }

    /**
     * Entity-hit effect: damage-indicator particles plus a generic hurt
     * sound (设计文档 §命中实体).
     *
     * @param level  the client world
     * @param hitPos the hit point
     */
    private static void playEntityHitEffect(ClientLevel level, Vec3 hitPos) {
        spawnParticles(level, ParticleTypes.DAMAGE_INDICATOR, hitPos);
        playSound(level, hitPos, SoundEvents.GENERIC_HURT, SoundSource.NEUTRAL);
    }

    /**
     * Block-hit effect: block-break particles tinted by the struck block's
     * state plus a stone-hit sound (设计文档 §命中方块).
     *
     * @param level  the client world
     * @param hitPos the hit point
     */
    private static void playBlockHitEffect(ClientLevel level, Vec3 hitPos) {
        BlockState blockState = level.getBlockState(BlockPos.containing(hitPos));
        spawnParticles(level, new BlockParticleOption(ParticleTypes.BLOCK, blockState), hitPos);
        playSound(level, hitPos, SoundEvents.STONE_HIT, SoundSource.BLOCKS);
    }

    /**
     * Pierce effect: crit particles plus a crit sound (设计文档 §穿透).
     *
     * @param level  the client world
     * @param hitPos the hit point
     */
    private static void playPierceEffect(ClientLevel level, Vec3 hitPos) {
        spawnParticles(level, ParticleTypes.CRIT, hitPos);
        playSound(level, hitPos, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS);
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
