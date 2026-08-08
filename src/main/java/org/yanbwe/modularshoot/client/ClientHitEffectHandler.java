package org.yanbwe.modularshoot.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.client.event.ClientBulletHitEvent;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;

/**
 * Plays client-side hit effects when a
 * {@link org.yanbwe.modularshoot.network.BulletHitS2CPacket} is received
 * (设计文档 §BulletHitS2CPacket 客户端处理, lines 2033-2035).
 *
 * <p>The server is the sole authority on hit resolution and damage; this
 * handler only renders feedback and never mutates game state. The framework
 * deliberately spawns <b>no</b> default hit particles — visual effects are
 * left entirely to extensions via {@link ClientBulletHitEvent}. The only
 * default behaviour here is playing the data-driven hit sound resolved from
 * the gun definition's {@code sounds} slots — silent when not configured.
 * Before playing, a {@link ClientBulletHitEvent} is posted to the game bus;
 * cancelling it skips the default sound entirely.</p>
 *
 * <p><b>Client-only.</b> Referenced solely from the S→C payload handler in
 * {@link org.yanbwe.modularshoot.network.ModularShootPayloads}, invoked only
 * on the physical client via {@code enqueueWork}. Uses only vanilla sound
 * resources as placeholders (设计文档 §客户端资源).</p>
 *
 * @see HitType
 */
public final class ClientHitEffectHandler {

    /** Sound volume for hit effects. */
    private static final float SOUND_VOLUME = 1.0F;

    /** Sound pitch for hit effects. */
    private static final float SOUND_PITCH = 1.0F;

    private ClientHitEffectHandler() {
    }

    /**
     * Plays the appropriate hit effect for a bullet impact.
     *
     * <p>No particles are spawned by the framework — that's up to
     * extensions. The only default is the data-driven hit sound.</p>
     *
     * <p>As an entry hook, a {@link ClientBulletHitEvent} is posted to the
     * game bus before any effect is played. Subscribers may cancel the event
     * to skip the default sound entirely (设计文档 §音效系统 — 命中特效钩子).</p>
     *
     * @param level       the client world
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
        playConfiguredSound(level, hitPos, soundId);
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
