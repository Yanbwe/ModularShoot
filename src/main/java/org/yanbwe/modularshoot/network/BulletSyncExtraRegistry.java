package org.yanbwe.modularshoot.network;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * Registry of {@link BulletSyncExtraProvider}s and the framed byte payload
 * codec for the bullet sync extension channel (审查 E4).
 *
 * <p>Wire framing of the {@code extra} payload carried by full/delta
 * entries:</p>
 * <pre>
 *   varint providerCount
 *   providerCount × (varint providerIndex, varint byteLength, bytes)
 * </pre>
 *
 * <p>The provider index is its position in registration order; both sides
 * register in the same order (same mod list), so the index identifies the
 * provider without transmitting its id. An empty payload ({@code count = 0})
 * costs a single varint byte.</p>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 */
public final class BulletSyncExtraRegistry {

    /** Empty payload shared instance (zero providers). */
    private static final byte[] EMPTY = new byte[0];

    /** Registered providers; index == wire identity. */
    private static final CopyOnWriteArrayList<BulletSyncExtraProvider> PROVIDERS =
            new CopyOnWriteArrayList<>();

    private BulletSyncExtraRegistry() {
    }

    /**
     * Registers an extension-bytes provider (idempotent — re-registering the
     * same instance is a no-op returning the existing index).
     *
     * @param provider the provider to register; must not be {@code null}
     * @return the provider's wire index (registration order)
     */
    public static int register(BulletSyncExtraProvider provider) {
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.addIfAbsent(provider);
        return PROVIDERS.indexOf(provider);
    }

    /**
     * Returns the registered providers (read-only snapshot for tests and
     * debugging).
     *
     * @return an unmodifiable list of providers in registration order
     */
    public static List<BulletSyncExtraProvider> getProviders() {
        return Collections.unmodifiableList(new ArrayList<>(PROVIDERS));
    }

    /**
     * Collects every provider's extension bytes for a bullet into one framed
     * payload (server side). Providers returning {@code null}/empty are
     * omitted; a provider that throws is logged and skipped (a faulty
     * provider must not break bullet sync).
     *
     * @param bullet the server-side bullet being synced; must not be {@code null}
     * @return the framed payload, or a shared empty array when no provider
     *         contributes data
     */
    public static byte[] collect(BulletRecord bullet) {
        Objects.requireNonNull(bullet, "bullet");
        List<byte[]> segments = null;
        List<Integer> indexes = null;
        List<BulletSyncExtraProvider> providers = PROVIDERS;
        for (int i = 0; i < providers.size(); i++) {
            byte[] bytes;
            try {
                bytes = providers.get(i).collect(bullet);
            } catch (Exception e) {
                ModularShoot.LOGGER.warn(
                        "BulletSyncExtraProvider {} threw for bullet {}; skipping",
                        providers.get(i).getClass().getName(), bullet.getBulletId(), e);
                continue;
            }
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            if (segments == null) {
                segments = new ArrayList<>(2);
                indexes = new ArrayList<>(2);
            }
            segments.add(bytes);
            indexes.add(i);
        }
        if (segments == null) {
            return EMPTY;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        writeVarInt(out, segments.size());
        for (int s = 0; s < segments.size(); s++) {
            writeVarInt(out, indexes.get(s));
            byte[] bytes = segments.get(s);
            writeVarInt(out, bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

    /**
     * Splits a framed payload back into provider index → bytes (client side).
     * A corrupted or truncated payload degrades to an empty map with a WARN
     * instead of throwing (defensive decode, 审查 R2 同款哲学).
     *
     * @param payload the framed extra payload; may be {@code null} or empty
     * @return a map of provider index to bytes; empty when the payload is
     *         empty or corrupted
     */
    public static Map<Integer, byte[]> split(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Map.of();
        }
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        int pos = 0;
        int count = readVarInt(payload, 0);
        if (count < 0) {
            warnCorrupted();
            return Map.of();
        }
        pos += varIntSize(count);
        for (int i = 0; i < count; i++) {
            if (pos >= payload.length) {
                warnCorrupted();
                return result;
            }
            int index = readVarInt(payload, pos);
            pos += varIntSize(index);
            int length = readVarInt(payload, pos);
            pos += varIntSize(length);
            if (index < 0 || length < 0 || pos + length > payload.length) {
                warnCorrupted();
                return result;
            }
            byte[] bytes = new byte[length];
            System.arraycopy(payload, pos, bytes, 0, length);
            result.put(index, bytes);
            pos += length;
        }
        return result;
    }

    private static void warnCorrupted() {
        ModularShoot.LOGGER.warn("Corrupted bullet sync extra payload; degrading to empty.");
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** Reads a varint from {@code payload} starting at {@code pos}; negative on overflow. */
    private static int readVarInt(byte[] payload, int pos) {
        int value = 0;
        int shift = 0;
        while (pos < payload.length) {
            int b = payload[pos++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 32) {
                return -1;
            }
        }
        return -1;
    }

    /** Returns the number of bytes {@link #writeVarInt} writes for the value. */
    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    /**
     * Clears all registered providers (test isolation only; not part of the
     * public extension contract).
     */
    static void clear() {
        PROVIDERS.clear();
    }
}
