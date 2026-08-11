package org.yanbwe.modularshoot.shooting;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ShootingEngine#tryParseDamageTypeId} — the pure
 * parse seam behind {@code resolveDamageType} (设计文档 §伤害类型预设机制一).
 *
 * <p>The parse seam must degrade on illegal state strings (third-party mods
 * writing junk into {@code modularshoot:ammo_damage_type} via GunState) the
 * same way the rest of the shooting pipeline degrades: no unchecked
 * {@code ResourceLocationSyntaxException}, empty {@link Optional} instead,
 * so the caller can WARN + fall back to the default damage type.</p>
 *
 * <p>Loading {@link ShootingEngine} initialises {@code ModularShootAttributes}
 * (DeferredHolder statics), so the same vanilla bootstrap recipe as
 * {@code BulletSnapshotBuilderTest} is required (FML shim +
 * {@code Bootstrap.bootStrap()} + {@code GameData.unfreezeData()}).</p>
 */
class ShootingEngineDamageTypeTest {

    // ---- Environment bootstrap (copied from BulletSnapshotBuilderTest) ----

    static {
        // FML shim: FeatureFlags.<clinit> -> FeatureFlagLoader needs a
        // non-null LoadingModList; of() installs an empty instance.
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        // DataFixers.<clinit> requires a current game version.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        // Full vanilla registry bootstrap (also freezes vanilla registries via
        // the NeoForge vanillaSnapshot patch).
        net.minecraft.server.Bootstrap.bootStrap();
        // Reopen the registries for test-side registration.
        net.neoforged.neoforge.registries.GameData.unfreezeData();
    }

    @Test
    void validIdParsesToOptional() {
        Optional<ResourceLocation> parsed =
                ShootingEngine.tryParseDamageTypeId("modularshoot:bullet_damage");
        assertTrue(parsed.isPresent(), "a valid resource location must parse");
        assertEquals(ResourceLocation.parse("modularshoot:bullet_damage"), parsed.get());
    }

    @Test
    void illegalIdYieldsEmptyOptional() {
        // ":::" and "a b" are not valid resource locations (third-party mod
        // junk written into per-gun state); the seam must return empty
        // instead of throwing ResourceLocationSyntaxException.
        // （空串由调用方 resolveDamageType 的 isEmpty() 分支先行拦截，不在此列。）
        assertEquals(Optional.empty(), ShootingEngine.tryParseDamageTypeId(":::"));
        assertEquals(Optional.empty(), ShootingEngine.tryParseDamageTypeId("a b"));
    }
}
