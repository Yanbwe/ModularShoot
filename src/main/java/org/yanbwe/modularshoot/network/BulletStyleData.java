package org.yanbwe.modularshoot.network;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

/**
 * Content-addressed, client-cacheable payload describing everything about a
 * bullet that is fixed for its whole flight (阶段 2 / 任务 2.3 §全量同步内容寻址).
 *
 * <p>This is the "flight-invariant" projection of a bullet that the server
 * assigns a stable wire id to via {@link BulletStyleContentAddresser}: the
 * composed visual style (texture / model / render mode / scale / tint / attach
 * layers) plus the client-visible {@link ClientBulletSnapshot} projection
 * (stats / traits / gun id). The per-bullet <em>shooter</em> is deliberately
 * excluded (审查修复: 跨玩家共享) — it is a per-bullet dynamic identity carried
 * inline by {@link BulletS2CPacket.FullBulletEntry#shooterEntityId()}, so the
 * same visual style can be shared by different shooters through one wire id.
 * The client caches this payload by wire id, so full-sync and delta packets
 * only need to reference the id and the full payload is transmitted exactly
 * once per client — when the fingerprint (see {@link BulletStyleFingerprint})
 * first changes.</p>
 *
 * <p>Per-bullet dynamic state (id, position, direction, shooter entity id)
 * is deliberately <em>not</em> part of this payload — it lives in
 * {@link BulletS2CPacket.FullBulletEntry} and changes every tick.</p>
 *
 * @param texture       billboard-mode texture path, or {@code null} when the
 *                      bullet uses 3d mode or has no visual
 * @param modelLocation 3d-mode vanilla JSON model path, or {@code null} when
 *                      the bullet uses billboard mode or has no visual
 * @param renderMode    rendering pipeline tag — {@code "billboard"} or
 *                      {@code "3d"}
 * @param renderScale   composed visual scale of the bullet
 * @param composedTint  channel-wise composed tint, or {@code null} as the
 *                      wire sentinel for the white identity tint
 * @param layers        composed attach-layer entries, in source order; empty
 *                      when the bullet has no additive layers
 * @param snapshot      client-side projection of the bullet's frozen
 *                      stats/traits and identity, consumed by visual-tick
 *                      hooks; never {@code null}
 */
public record BulletStyleData(
        @Nullable ResourceLocation texture,
        @Nullable ResourceLocation modelLocation,
        String renderMode,
        float renderScale,
        @Nullable Vector4f composedTint,
        List<BulletS2CPacket.FullBulletEntry.LayerEntryFull> layers,
        ClientBulletSnapshot snapshot) {
}
