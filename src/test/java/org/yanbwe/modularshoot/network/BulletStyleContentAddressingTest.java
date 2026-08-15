package org.yanbwe.modularshoot.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.BulletS2CPacket.FullBulletEntry.LayerEntryFull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for the bullet content-addressing layer (阶段 2 /
 * 任务 2.3): stable fingerprints over the "flight-invariant" style data, and a
 * server-side content addresser that assigns a stable wire id per fingerprint
 * and only carries the full style over the wire once per client.
 *
 * <p>Covered behaviours:</p>
 * <ul>
 *   <li>identical content produces a stable (equal) fingerprint;</li>
 *   <li>changed content produces a different fingerprint;</li>
 *   <li>the first encounter of a fingerprint assigns a wire id and requires a
 *       full style send;</li>
 *   <li>a client that already knows the wire id receives only the id (no full
 *       style resend);</li>
 *   <li>a changed fingerprint gets a fresh id and requires a full style send;</li>
 *   <li>a client that does not know an already-assigned id still receives the
 *       full style (the "once per client" guarantee).</li>
 * </ul>
 */
class BulletStyleContentAddressingTest {

    // ---- Fingerprint stability -----------------------------------------

    private static ResourceLocation tex(String path) {
        return ResourceLocation.parse(path);
    }

    private static ClientBulletSnapshot snapshot() {
        return new ClientBulletSnapshot(
                Map.of(tex("modularshoot:hit_damage"), 6.0, tex("modularshoot:fire_rate"), 4.0),
                Map.of(tex("modularshoot:example_flame"), true),
                tex("modularshoot:composite_cane"),
                UUID.fromString("6e8a2f4a-1b2c-3d4e-5f6a-7b8c9d0e1f2a"));
    }

    private static BulletStyleData defaultStyle() {
        return new BulletStyleData(
                tex("modularshoot:textures/bullet/example.png"),
                null,
                "billboard",
                0.75f,
                new Vector4f(0.1f, 0.2f, 0.3f, 0.9f),
                List.of(
                        new LayerEntryFull(
                                "3d", null, tex("modularshoot:item/ice_shard"),
                                false, true, -1.0f, 2.0f, -3.0f, 0.25f,
                                1.0f, 0.9f, 0.8f, 0.5f)),
                snapshot());
    }

    @Test
    void identicalContentProducesStableFingerprint() {
        assertEquals(
                BulletStyleFingerprint.of(defaultStyle()),
                BulletStyleFingerprint.of(defaultStyle()),
                "two identical style payloads must fingerprint equal");
    }

    @Test
    void changedContentProducesDifferentFingerprint() {
        BulletStyleData changed = new BulletStyleData(
                tex("modularshoot:textures/bullet/other.png"),
                defaultStyle().modelLocation(),
                defaultStyle().renderMode(),
                defaultStyle().renderScale(),
                defaultStyle().composedTint(),
                defaultStyle().layers(),
                defaultStyle().snapshot());

        assertNotEquals(
                BulletStyleFingerprint.of(defaultStyle()),
                BulletStyleFingerprint.of(changed),
                "a changed texture must change the fingerprint so a new wire id is issued");
    }

    @Test
    void changedSnapshotProducesDifferentFingerprint() {
        ClientBulletSnapshot otherSnapshot = new ClientBulletSnapshot(
                Map.of(tex("modularshoot:hit_damage"), 6.0, tex("modularshoot:fire_rate"), 5.0),
                Map.of(tex("modularshoot:example_flame"), true),
                tex("modularshoot:composite_cane"),
                UUID.fromString("6e8a2f4a-1b2c-3d4e-5f6a-7b8c9d0e1f2a"));
        BulletStyleData changed = new BulletStyleData(
                defaultStyle().texture(),
                defaultStyle().modelLocation(),
                defaultStyle().renderMode(),
                defaultStyle().renderScale(),
                defaultStyle().composedTint(),
                defaultStyle().layers(),
                otherSnapshot);

        assertNotEquals(
                BulletStyleFingerprint.of(defaultStyle()),
                BulletStyleFingerprint.of(changed),
                "a changed stats/traits snapshot must change the fingerprint");
    }

    // ---- Content addresser ---------------------------------------------

    @Test
    void firstEncounterAssignsWireIdAndRequiresFullStyle() {
        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser();
        String fp = BulletStyleFingerprint.of(defaultStyle());

        BulletStyleContentAddresser.BulletStyleRef ref =
                addresser.resolve(defaultStyle(), fp, false);

        assertTrue(ref.hasFullStyle(),
                "the first encounter of a fingerprint must carry the full style");
        assertEquals(defaultStyle(), ref.style(), "the full style payload is attached on first send");
        assertTrue(ref.styleId() > 0, "a positive wire id is assigned");
    }

    @Test
    void knownClientReceivesIdOnlyNoFullStyleResend() {
        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser();
        String fp = BulletStyleFingerprint.of(defaultStyle());

        BulletStyleContentAddresser.BulletStyleRef first =
                addresser.resolve(defaultStyle(), fp, false);
        BulletStyleContentAddresser.BulletStyleRef second =
                addresser.resolve(defaultStyle(), fp, true);

        assertEquals(first.styleId(), second.styleId(),
                "the same content must keep the same wire id");
        assertFalse(second.hasFullStyle(),
                "a client that already knows the id must receive only the id, not a full style");
        assertNull(second.style(), "no full style is attached when the client already knows it");
    }

    @Test
    void changedFingerprintGetsNewIdAndSendsFullStyle() {
        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser();
        BulletStyleData changed = new BulletStyleData(
                tex("modularshoot:textures/bullet/other.png"),
                null, "billboard", 0.75f,
                new Vector4f(0.1f, 0.2f, 0.3f, 0.9f),
                List.of(), snapshot());

        BulletStyleContentAddresser.BulletStyleRef a =
                addresser.resolve(defaultStyle(), BulletStyleFingerprint.of(defaultStyle()), false);
        BulletStyleContentAddresser.BulletStyleRef b =
                addresser.resolve(changed, BulletStyleFingerprint.of(changed), false);

        assertNotEquals(a.styleId(), b.styleId(),
                "a changed fingerprint must be assigned a different wire id");
        assertTrue(b.hasFullStyle(),
                "the changed style must be sent in full when first encountered");
    }

    @Test
    void clientNotKnowingKnownIdStillReceivesFullStyle() {
        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser();
        String fp = BulletStyleFingerprint.of(defaultStyle());

        addresser.resolve(defaultStyle(), fp, true); // a client that "knows" it first
        BulletStyleContentAddresser.BulletStyleRef late =
                addresser.resolve(defaultStyle(), fp, false); // now a client that doesn't know

        assertTrue(late.hasFullStyle(),
                "a client that does not yet know the id must still receive the full style exactly once");
        assertNotNull(late.style(), "the cached full style is available for the first-time client");
    }

    @Test
    void forceFullSyncResendsFullStyleToKnownClient() {
        // 审查修复（High）: 客户端首次未收到完整样式，后续 force-full-sync 能补发。
        // The force-full-sync path (BulletSyncService) calls resolve with
        // clientKnows=false regardless of the player's known-set, so even a
        // client that was previously marked "known" (but whose first full-style
        // packet was dropped) receives the full payload again on the next
        // force-full-sync, letting it heal its style cache.
        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser();
        String fp = BulletStyleFingerprint.of(defaultStyle());

        // First delta: full style is attached and the sync layer marks the
        // client as "known" — but this packet is dropped on the wire.
        BulletStyleContentAddresser.BulletStyleRef first =
                addresser.resolve(defaultStyle(), fp, false);
        assertTrue(first.hasFullStyle(), "the first transmission carries the full style");

        // Subsequent delta: the known-set makes the server send only the id —
        // the dropped full style would otherwise leave the client without it.
        BulletStyleContentAddresser.BulletStyleRef idOnly =
                addresser.resolve(defaultStyle(), fp, true);
        assertFalse(idOnly.hasFullStyle(), "a known client receives only the id on a delta");

        // Force-full-sync: the service forces clientKnows=false (unconditional
        // full attach), so the full style is re-sent and the client recovers.
        BulletStyleContentAddresser.BulletStyleRef recovery =
                addresser.resolve(defaultStyle(), fp, false);
        assertTrue(recovery.hasFullStyle(),
                "force-full-sync must re-attach the full style even for a client marked known");
        assertEquals(idOnly.styleId(), recovery.styleId(),
                "the recovered style keeps the original wire id so the cache heals in place");
        assertEquals(defaultStyle(), recovery.style(),
                "the recovered full payload matches the original style");
    }

    // ---- Shooter exclusion ----------------------------------------------

    @Test
    void shooterDoesNotAffectFingerprintOrWireId() {
        // 审查修复（Medium）: shooter 是每子弹动态身份（由 FullBulletEntry 内联携带），
        // 不应进入内容键。同一视觉样式 + 不同 shooter 必须共享同一 fingerprint / wire id。
        BulletStyleData withShooterA = new BulletStyleData(
                defaultStyle().texture(), defaultStyle().modelLocation(),
                defaultStyle().renderMode(), defaultStyle().renderScale(),
                defaultStyle().composedTint(), defaultStyle().layers(),
                new ClientBulletSnapshot(
                        snapshot().stats(), snapshot().traits(), snapshot().gunId(),
                        UUID.fromString("11111111-1111-1111-1111-111111111111")));
        BulletStyleData withShooterB = new BulletStyleData(
                defaultStyle().texture(), defaultStyle().modelLocation(),
                defaultStyle().renderMode(), defaultStyle().renderScale(),
                defaultStyle().composedTint(), defaultStyle().layers(),
                new ClientBulletSnapshot(
                        snapshot().stats(), snapshot().traits(), snapshot().gunId(),
                        UUID.fromString("22222222-2222-2222-2222-222222222222")));

        assertEquals(
                BulletStyleFingerprint.of(withShooterA),
                BulletStyleFingerprint.of(withShooterB),
                "a different shooter must NOT change the fingerprint (shooter is excluded from the content key)");

        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser();
        BulletStyleContentAddresser.BulletStyleRef a =
                addresser.resolve(withShooterA, BulletStyleFingerprint.of(withShooterA), false);
        BulletStyleContentAddresser.BulletStyleRef b =
                addresser.resolve(withShooterB, BulletStyleFingerprint.of(withShooterB), false);
        assertEquals(a.styleId(), b.styleId(),
                "the same visual style must share one wire id across different shooters");
    }

    // ---- Bounded LRU eviction -------------------------------------------

    @Test
    void addresserEvictsLeastRecentlyUsedBeyondCapacity() {
        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser(3);
        String fp1 = BulletStyleFingerprint.of(styleFor(1));
        String fp2 = BulletStyleFingerprint.of(styleFor(2));
        String fp3 = BulletStyleFingerprint.of(styleFor(3));

        // Insert three distinct styles.
        addresser.resolve(styleFor(1), fp1, false);
        addresser.resolve(styleFor(2), fp2, false);
        addresser.resolve(styleFor(3), fp3, false);
        assertEquals(Integer.valueOf(1), addresser.peekId(fp1), "fp1 cached");
        assertEquals(Integer.valueOf(3), addresser.peekId(fp3), "fp3 cached");

        // Touch fp1 (LRU recency) then insert a fourth style, forcing eviction
        // of the least-recently-used entry (fp2).
        addresser.resolve(styleFor(1), fp1, true); // re-access fp1
        String fp4 = BulletStyleFingerprint.of(styleFor(4));
        addresser.resolve(styleFor(4), fp4, false);

        assertNull(addresser.peekId(fp2),
                "the least-recently-used fingerprint must be evicted when capacity is exceeded");
        assertNotNull(addresser.peekId(fp1), "the recently-accessed entry survives");
        assertNotNull(addresser.peekId(fp3), "the untouched-but-not-least entry survives");
    }

    @Test
    void evictedStyleReassignedFreshIdAndResentInFull() {
        BulletStyleContentAddresser addresser = new BulletStyleContentAddresser(2);
        String fpA = BulletStyleFingerprint.of(styleFor(1));
        String fpB = BulletStyleFingerprint.of(styleFor(2));
        String fpC = BulletStyleFingerprint.of(styleFor(3));

        BulletStyleContentAddresser.BulletStyleRef first =
                addresser.resolve(styleFor(1), fpA, false);
        addresser.resolve(styleFor(2), fpB, false);
        // fpA is now least-recently-used: inserting fpC evicts it.
        addresser.resolve(styleFor(3), fpC, false);

        assertNull(addresser.peekId(fpA), "fpA was evicted along with its id");

        // Re-encounter fpA: a fresh (never-reused), larger id is assigned and
        // the full style must be re-sent to clients that no longer have it.
        BulletStyleContentAddresser.BulletStyleRef reencounter =
                addresser.resolve(styleFor(1), fpA, false);
        assertNotEquals(first.styleId(), reencounter.styleId(),
                "an evicted style gets a fresh id, not the original one");
        assertTrue(reencounter.styleId() > first.styleId(),
                "fresh ids are monotonically increasing and never reused");
        assertTrue(reencounter.hasFullStyle(),
                "a re-encountered (evicted) style must be re-sent in full");
    }

    /** Builds a distinct style whose texture path encodes {@code n}. */
    private static BulletStyleData styleFor(int n) {
        return new BulletStyleData(
                tex("modularshoot:textures/bullet/style_" + n + ".png"),
                null, "billboard", 0.5f,
                new Vector4f(0.1f, 0.1f, 0.1f, 1.0f),
                List.of(), snapshot());
    }
}
