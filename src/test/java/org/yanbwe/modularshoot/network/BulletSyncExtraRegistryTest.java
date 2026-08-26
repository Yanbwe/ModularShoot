package org.yanbwe.modularshoot.network;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.bullet.ComposedBulletStyle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the bullet sync extension channel (审查 E4):
 * {@link BulletSyncExtraRegistry#collect} frames every provider's bytes by
 * registration index and {@link BulletSyncExtraRegistry#split} restores them
 * on the client side. Faulty providers must be skipped, never crash sync.
 */
class BulletSyncExtraRegistryTest {

    @BeforeEach
    void clearRegistry() {
        BulletSyncExtraRegistry.clear();
    }

    /** Builds a minimal bullet (same recipe as BulletHookInvokerTest). */
    private static BulletRecord bullet() {
        return new BulletRecord(
                new BulletSnapshot(
                        new HashMap<>(), new HashMap<>(), null, null, null, null, new HashMap<>()),
                null, Vec3.ZERO, new Vec3(1, 0, 0), 1, ComposedBulletStyle.DEFAULT);
    }

    @Test
    void noProvidersCollectsEmptyPayload() {
        byte[] payload = BulletSyncExtraRegistry.collect(bullet());
        assertEquals(0, payload.length, "no providers must produce an empty payload");
        assertTrue(BulletSyncExtraRegistry.split(payload).isEmpty());
        assertTrue(BulletSyncExtraRegistry.split(null).isEmpty());
    }

    @Test
    void collectSplitRoundTripsEveryProviderByIndex() {
        byte[] first = new byte[] {1, 2, 3};
        byte[] second = new byte[] {9, 8, 7, 6};
        int indexA = BulletSyncExtraRegistry.register(bullet -> first);
        int indexB = BulletSyncExtraRegistry.register(bullet -> second);

        Map<Integer, byte[]> split = BulletSyncExtraRegistry.split(BulletSyncExtraRegistry.collect(bullet()));

        assertEquals(2, split.size());
        assertArrayEquals(first, split.get(indexA));
        assertArrayEquals(second, split.get(indexB));
    }

    @Test
    void nullAndEmptyContributionsAreOmitted() {
        BulletSyncExtraRegistry.register(bullet -> null);
        BulletSyncExtraRegistry.register(bullet -> new byte[0]);

        byte[] payload = BulletSyncExtraRegistry.collect(bullet());
        assertEquals(0, payload.length, "empty contributions must not be framed");
    }

    @Test
    void throwingProviderIsSkippedWithoutCrashing() {
        byte[] healthy = new byte[] {5};
        BulletSyncExtraRegistry.register(bullet -> {
            throw new IllegalStateException("deliberate test failure");
        });
        int healthyIndex = BulletSyncExtraRegistry.register(bullet -> healthy);

        byte[][] collected = new byte[1][];
        assertDoesNotThrow(() -> collected[0] = BulletSyncExtraRegistry.collect(bullet()));

        Map<Integer, byte[]> split = BulletSyncExtraRegistry.split(collected[0]);
        assertEquals(1, split.size());
        assertArrayEquals(healthy, split.get(healthyIndex));
    }

    @Test
    void registerIsIdempotentAndCorruptedPayloadDegrades() {
        BulletSyncExtraProvider provider = bullet -> new byte[] {1};
        int first = BulletSyncExtraRegistry.register(provider);
        int second = BulletSyncExtraRegistry.register(provider);
        assertEquals(first, second, "re-registering the same provider keeps its wire index");

        // Corrupted payload (count claims 3 entries but the body is empty).
        byte[] corrupted = new byte[] {(byte) 0x03};
        Map<Integer, byte[]> split = BulletSyncExtraRegistry.split(corrupted);
        assertTrue(split.isEmpty(), "a truncated payload degrades to an empty map instead of over-reading");
    }
}
