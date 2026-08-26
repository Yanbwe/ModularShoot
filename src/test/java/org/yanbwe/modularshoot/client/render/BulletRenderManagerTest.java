package org.yanbwe.modularshoot.client.render;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.BulletS2CPacket;
import org.yanbwe.modularshoot.network.BulletS2CPacket.FullBulletEntry;
import org.yanbwe.modularshoot.network.BulletStyleData;
import org.yanbwe.modularshoot.network.ClientBulletSnapshot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Headless unit tests for the client-side bullet style-miss degradation
 * (审查修复, High): when a {@link FullBulletEntry} references a style id the
 * client has not cached and carries no full-style payload (its first full-style
 * transmission was dropped), the client must <em>not</em> throw — it skips the
 * bullet and lets the server's next force-full-sync (which always attaches full
 * styles) heal the cache.
 */
class BulletRenderManagerTest {

    private static final ResourceLocation TEX =
            ResourceLocation.parse("modularshoot:textures/bullet/example.png");

    @AfterEach
    void tearDown() {
        BulletRenderManager.getInstance().clear();
    }

    @Test
    void forceFullSyncWithStyleCreatesObjectAndCachesStyle() {
        BulletRenderManager manager = BulletRenderManager.getInstance();
        FullBulletEntry entry = new FullBulletEntry(
                7, 1.0, 2.0, 3.0, 0.0, 1.0, 0.0, -1, snapshot(), 42, style());

        manager.handlePacket(BulletS2CPacket.fullSync(List.of(entry)));

        BulletRenderObject obj = manager.getRenderObject(7);
        assertNotNull(obj, "a full-sync entry carrying the full style must create a render object");
        assertNotNull(manager.getSnapshot(7),
                "the per-bullet snapshot riding inline on the entry must be stored (审查 E5)");
    }

    @Test
    void styleLessUnknownEntryDegradesWithoutThrowing() {
        BulletRenderManager manager = BulletRenderManager.getInstance();
        // First full-style transmission for style id 99 was dropped: the entry
        // carries only the (uncached) id, no payload.
        FullBulletEntry entry = new FullBulletEntry(
                8, 1.0, 2.0, 3.0, 0.0, 1.0, 0.0, -1, snapshot(), 99, null);

        // Must not throw (previously threw IllegalStateException).
        manager.handlePacket(BulletS2CPacket.fullSync(List.of(entry)));

        assertNull(manager.getRenderObject(8),
                "a bullet whose style is missing must be skipped (degraded), not created");
        assertNull(manager.getSnapshot(8),
                "no snapshot is recorded for a skipped bullet");
    }

    @Test
    void laterFullSyncWithStyleHealsSkippedBullet() {
        BulletRenderManager manager = BulletRenderManager.getInstance();
        // Client received a style-less entry for id 99 first (dropped full style).
        manager.handlePacket(BulletS2CPacket.fullSync(List.of(
                new FullBulletEntry(8, 1.0, 2.0, 3.0, 0.0, 1.0, 0.0, -1, snapshot(), 99, null))));
        assertNull(manager.getRenderObject(8), "bullet starts skipped");

        // The next force-full-sync re-sends the full style for id 99 (~100 ticks
        // later), healing the cache and creating the render object.
        manager.handlePacket(BulletS2CPacket.fullSync(List.of(
                new FullBulletEntry(8, 1.0, 2.0, 3.0, 0.0, 1.0, 0.0, -1, snapshot(), 99, style()))));

        assertNotNull(manager.getRenderObject(8),
                "the force-full-sync resend must create the previously-skipped bullet");
    }

    private static BulletStyleData style() {
        return new BulletStyleData(
                TEX, null, "billboard", 0.75f,
                new Vector4f(0.1f, 0.2f, 0.3f, 0.9f),
                List.of());
    }

    private static ClientBulletSnapshot snapshot() {
        return new ClientBulletSnapshot(
                Map.of(TEX, 6.0),
                Map.of(),
                ResourceLocation.parse("modularshoot:composite_cane"),
                UUID.fromString("6e8a2f4a-1b2c-3d4e-5f6a-7b8c9d0e1f2a"));
    }
}
