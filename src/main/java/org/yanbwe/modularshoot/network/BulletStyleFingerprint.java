package org.yanbwe.modularshoot.network;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.network.BulletS2CPacket.FullBulletEntry.LayerEntryFull;

/**
 * Stable fingerprint for the "flight-invariant" bullet style payload
 * ({@link BulletStyleData}), used by {@link BulletStyleContentAddresser} to
 * decide whether a changed style must be re-sent over the wire (阶段 2 /
 * 任务 2.3).
 *
 * <p>The fingerprint is a deterministic, content-derived string over every
 * field of the style payload — visual style fields plus the stats/traits
 * snapshot. The per-bullet <em>shooter</em> is deliberately excluded (审查修复:
 * 跨玩家共享): it is a per-bullet dynamic identity carried inline by
 * {@link BulletS2CPacket.FullBulletEntry#shooterEntityId()}, not a property of
 * the shared style. Two bullets with identical style content produce equal
 * fingerprints; any change (a different texture, a different composed tint,
 * a different stat value, a different activated trait, …) produces a
 * different fingerprint, which in turn makes the content addresser assign a
 * fresh wire id and re-send the full payload exactly once.</p>
 *
 * <p>Map entries (stats / traits) are rendered in sorted key order so the
 * fingerprint is independent of map iteration order. Floats are rendered via
 * their raw bit pattern so NaN / signed-zero distinctions survive.</p>
 */
public final class BulletStyleFingerprint {

    private BulletStyleFingerprint() {
    }

    /**
     * Computes a stable, content-derived fingerprint for the given style
     * payload.
     *
     * @param style the flight-invariant style payload to fingerprint
     * @return a deterministic string fingerprint; equal for identical content
     */
    public static String of(BulletStyleData style) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(style.renderMode()).append('|');
        appendNullable(sb, style.texture());
        sb.append('|');
        appendNullable(sb, style.modelLocation());
        sb.append('|').append(Float.floatToIntBits(style.renderScale())).append('|');
        appendTint(sb, style.composedTint());
        sb.append('|');
        for (LayerEntryFull l : style.layers()) {
            sb.append('[')
                    .append(l.renderMode()).append('|');
            appendNullable(sb, l.texture());
            sb.append('|');
            appendNullable(sb, l.model());
            sb.append('|')
                    .append(l.followRotation()).append('|')
                    .append(l.followScale()).append('|')
                    .append(Float.floatToIntBits(l.offsetX())).append('|')
                    .append(Float.floatToIntBits(l.offsetY())).append('|')
                    .append(Float.floatToIntBits(l.offsetZ())).append('|')
                    .append(Float.floatToIntBits(l.scale())).append('|')
                    .append(Float.floatToIntBits(l.tintR())).append('|')
                    .append(Float.floatToIntBits(l.tintG())).append('|')
                    .append(Float.floatToIntBits(l.tintB())).append('|')
                    .append(Float.floatToIntBits(l.tintA()))
                    .append(']');
        }
        sb.append('|');
        appendSnapshot(sb, style.snapshot());
        return sb.toString();
    }

    private static void appendSnapshot(StringBuilder sb, ClientBulletSnapshot snapshot) {
        appendNullable(sb, snapshot.gunId());
        sb.append('|');
        // Stats, sorted by key for iteration-order independence.
        TreeMap<ResourceLocation, Double> stats = new TreeMap<>(snapshot.stats());
        for (Map.Entry<ResourceLocation, Double> e : stats.entrySet()) {
            sb.append('(').append(e.getKey()).append('=').append(e.getValue()).append(')');
        }
        sb.append('|');
        TreeMap<ResourceLocation, Boolean> traits = new TreeMap<>(snapshot.traits());
        for (Map.Entry<ResourceLocation, Boolean> e : traits.entrySet()) {
            sb.append('(').append(e.getKey()).append('=').append(e.getValue()).append(')');
        }
    }

    private static void appendNullable(StringBuilder sb, ResourceLocation loc) {
        sb.append(loc == null ? "~" : loc.toString());
    }

    private static void appendTint(StringBuilder sb, org.joml.Vector4f tint) {
        if (tint == null) {
            sb.append("~");
            return;
        }
        sb.append(Float.floatToIntBits(tint.x))
                .append('.')
                .append(Float.floatToIntBits(tint.y))
                .append('.')
                .append(Float.floatToIntBits(tint.z))
                .append('.')
                .append(Float.floatToIntBits(tint.w));
    }
}
