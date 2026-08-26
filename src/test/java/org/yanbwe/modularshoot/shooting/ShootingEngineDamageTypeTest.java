package org.yanbwe.modularshoot.shooting;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.state.GunStateStorage;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateDisplay;
import org.yanbwe.modularshoot.state.StateDomain;
import org.yanbwe.modularshoot.state.StateValueType;

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

    // ---- 审查 R1: 真实读取路径（类型化状态访问器，而非原始 tag） ----

    private static final ResourceLocation AMMO_DAMAGE_TYPE =
            ResourceLocation.parse("modularshoot:ammo_damage_type");
    private static final ResourceLocation GUN_ID =
            ResourceLocation.parse("modularshoot:test_gun");

    /** Registers the conventional STRING state (upper-layer mods do this). */
    private static RegistryAccess accessWithAmmoState(StateValueType type) {
        MappedRegistry<StateDefinition> states = new MappedRegistry<>(
                ModularShootRegistries.STATES_KEY, Lifecycle.stable());
        Registry.register(states, AMMO_DAMAGE_TYPE, new StateDefinition(
                StateDomain.GUN, type, type.zeroValue(),
                StateDisplay.of("ammo", Optional.of("#FFFFFF")), List.of()));
        return new RegistryAccess.ImmutableRegistryAccess(List.of(states));
    }

    private static GunData gunWithState(CompoundTag state) {
        return new GunData(GUN_ID, UUID.randomUUID(), List.of(), 0, state);
    }

    @Test
    void presetWrittenViaGunStateIsReadable() {
        // 审查 R1 回归：经 GunState 写入的预设必须能读回——此前的原始
        // tag getString 路径因 {type, value} 嵌套结构永远读到空串。
        RegistryAccess access = accessWithAmmoState(StateValueType.STRING);
        CompoundTag state = GunStateStorage.setStateValue(
                new CompoundTag(), AMMO_DAMAGE_TYPE, "minecraft:in_fire", access);

        assertEquals("minecraft:in_fire",
                ShootingEngine.readDamageTypePreset(gunWithState(state), access));
    }

    @Test
    void presetMissingOrUnregisteredDegradesToEmpty() {
        RegistryAccess access = accessWithAmmoState(StateValueType.STRING);
        assertEquals("", ShootingEngine.readDamageTypePreset(
                gunWithState(new CompoundTag()), access),
                "absent preset entry degrades to empty");
        assertEquals("", ShootingEngine.readDamageTypePreset(
                gunWithState(new CompoundTag()), RegistryAccess.EMPTY),
                "unregistered state id degrades to empty");
    }

    @Test
    void presetRegisteredWithWrongTypeDegradesToEmpty() {
        // 上层模组误把约定状态注册为 INT：读取不得抛异常，降级为空串。
        RegistryAccess access = accessWithAmmoState(StateValueType.INT);
        CompoundTag state = GunStateStorage.setStateValue(
                new CompoundTag(), AMMO_DAMAGE_TYPE, 42, access);

        assertEquals("", ShootingEngine.readDamageTypePreset(gunWithState(state), access));
    }
}
