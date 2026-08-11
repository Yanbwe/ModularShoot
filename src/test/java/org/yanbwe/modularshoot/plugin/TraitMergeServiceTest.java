package org.yanbwe.modularshoot.plugin;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link TraitMergeService} merge semantics (设计文档
 * §布尔特性合并规则), focused on the plugin bare-key namespace contract:
 * a plugin JSON writing {@code "traits":{"auto_fire":true}} must decode to the
 * {@code modularshoot:auto_fire} key so the merge (and the gun's inherent
 * traits) collide under one namespace — no {@code minecraft:}-prefixed trait
 * key may ever reach the final map.
 *
 * <h2>JUnit environment bridge</h2>
 * <p>{@link TraitMergeService#computeTraits} reads the {@code gun_data}
 * component off an {@link ItemStack} and resolves gun/plugin definitions
 * through a {@link RegistryAccess}. The static initializer applies the
 * probe-verified bridge of {@link GunRegistryEnsureGunDataTest}:
 * {@code LoadingModList} shim, {@code SharedConstants.setVersion},
 * {@code Bootstrap.bootStrap()} give us a usable {@link ItemStack}; the
 * {@code GUN_DATA} DeferredHolder is bound to a direct {@link Holder} via
 * reflection ({@link #bindGunDataHolder()}) so {@code GUN_DATA.get()} resolves
 * in JUnit. If the NeoForge internal field layout changes the bind fails,
 * {@link #GUN_DATA_BOUND} becomes {@code false} and the component-dependent
 * cases degrade to skipped (same degradation rule as the reference test).</p>
 *
 * <p>Plugins live in a stub {@link MappedRegistry} for
 * {@link ModularShootRegistries#PLUGINS_KEY} wrapped in a
 * {@link RegistryAccess.ImmutableRegistryAccess} (same pattern as
 * {@code CrossReferenceValidatorTest}); the gun definition is registered via
 * {@link GunRegistry#registerGun} (Java-API store, no registry needed).
 * Every test uses unique gun/plugin ids so the shared static
 * {@code JAVA_API_GUNS} map cannot be polluted by — or pollute — sibling test
 * classes.</p>
 */
class TraitMergeServiceTest {

    private static final ResourceLocation AUTO_FIRE = ResourceLocation.parse("modularshoot:auto_fire");
    private static final ResourceLocation SILENT = ResourceLocation.parse("modularshoot:silent");
    private static final ResourceLocation RAPID = ResourceLocation.parse("modularshoot:rapid");

    // ---- Environment bootstrap (probe-verified bridge, see class javadoc) -

    static {
        // FML shim: FeatureFlags.<clinit> -> FeatureFlagLoader needs a non-null
        // LoadingModList; of() installs an empty instance (loader 4.0.42).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        // DataFixers.<clinit> requires a current game version.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        // Full vanilla registry bootstrap so ItemStack/Items are usable.
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /**
     * Binds the {@code GUN_DATA} DeferredHolder to a direct {@link Holder} so
     * its {@code get()} resolves in JUnit (bridge of
     * {@link GunRegistryEnsureGunDataTest}). Returns {@code false} (and the
     * component-dependent cases degrade to skipped) when the NeoForge
     * internals no longer expose the {@code holder} field.
     */
    private static boolean bindGunDataHolder() {
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
            return true;
        } catch (Exception e) {
            System.err.println("[TraitMergeServiceTest] DeferredHolder bind failed: " + e);
            return false;
        }
    }

    /** Probe verdict: {@code true} when {@code GUN_DATA.get()} is usable in JUnit. */
    private static final boolean GUN_DATA_BOUND = bindGunDataHolder();

    // ---- Helpers ----------------------------------------------------------

    /** Decodes a plugin definition from a JSON string (same style as the codec tests). */
    private static PluginDefinition parsePlugin(String json) {
        return PluginDefinition.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    /** Builds a {@link GunDefinition} carrying only the given inherent traits. */
    private static GunDefinition gunWithTraits(Map<ResourceLocation, Boolean> traits) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("m:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                traits,
                Map.of(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** Builds an installed plugin instance for the given plugin id. */
    private static PluginInstance inst(String pluginId) {
        return new PluginInstance(
                ResourceLocation.parse(pluginId), UUID.randomUUID(),
                ResourceLocation.parse("m:type"), false);
    }

    /**
     * Registers the given plugins into a stub {@code modularshoot:plugins}
     * registry and the gun into the Java-API store, then returns the merged
     * trait map via {@link TraitMergeService#computeTraits}.
     */
    private static Map<ResourceLocation, Boolean> compute(
            ResourceLocation gunId, Map<ResourceLocation, Boolean> gunTraits,
            Map<String, PluginDefinition> plugins) {
        GunRegistry.registerGun(gunId, gunWithTraits(gunTraits));
        MappedRegistry<PluginDefinition> pluginRegistry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        plugins.forEach((id, def) ->
                Registry.register(pluginRegistry, ResourceLocation.parse(id), def));
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(pluginRegistry));
        List<PluginInstance> installed = plugins.keySet().stream().map(TraitMergeServiceTest::inst).toList();
        ItemStack stack = new ItemStack(Items.STONE);
        stack.set(ModularShootDataComponents.GUN_DATA.get(),
                new GunData(gunId, UUID.randomUUID(), installed, 0, new CompoundTag()));
        return TraitMergeService.computeTraits(stack, access);
    }

    // ---- Merge tests ------------------------------------------------------

    @Test
    void bareKeyPluginTraitsMergeWithGunTraitsUnderModularshootNamespace() {
        assumeTrue(GUN_DATA_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        // 插件 JSON 用裸键（数据包常见写法）：auto_fire 与枪械 traits 的
        // modularshoot:auto_fire 必须是同一个键，否则合并契约静默失效
        // （裸键落 minecraft 是历史错误，见 SharedKeyCodecs javadoc）。
        Map<ResourceLocation, Boolean> result = compute(
                ResourceLocation.parse("mypack:test_gun_" + UUID.randomUUID()),
                Map.of(AUTO_FIRE, true),
                Map.of("mypack:test_plugin_" + UUID.randomUUID(),
                        parsePlugin("{\"item_icon\": \"m:icon\", \"priority\": 100, "
                                + "\"traits\": {\"auto_fire\": true, \"silent\": false}}")));

        boolean hasMinecraftKeys = result.keySet().stream()
                .anyMatch(k -> k.getNamespace().equals("minecraft"));
        assertFalse(hasMinecraftKeys,
                "合并结果不得含任何 minecraft: 命名空间键（裸键必须统一为 modularshoot），实际 " + result.keySet());
        assertEquals(2, result.size(),
                "auto_fire 与 silent 各自只占一个键（不得出现同名双键漂移），实际 " + result.keySet());
        assertEquals(true, result.get(AUTO_FIRE), "枪械声明的 auto_fire=true 必须生效");
        assertEquals(false, result.get(SILENT),
                "枪械未声明的 silent 保留插件合并值 false（键必须是 modularshoot:silent）");
    }

    @Test
    void higherPriorityPluginWinsAndGunOverridesAll() {
        assumeTrue(GUN_DATA_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        // Javadoc 合并规则 1-2：priority 升序合并，高优先级插件覆盖低优先级；
        // 规则 3：枪械固有 traits 覆盖一切插件。
        String pluginA = "mypack:test_plugin_a_" + UUID.randomUUID();
        String pluginB = "mypack:test_plugin_b_" + UUID.randomUUID();
        Map<String, PluginDefinition> plugins = Map.of(
                pluginA, parsePlugin("{\"item_icon\": \"m:icon\", \"priority\": 100, "
                        + "\"traits\": {\"rapid\": true}}"),
                pluginB, parsePlugin("{\"item_icon\": \"m:icon\", \"priority\": 200, "
                        + "\"traits\": {\"rapid\": false}}"));

        // 枪械未声明 rapid → 高优先级插件 B 的 false 覆盖 A 的 true。
        Map<ResourceLocation, Boolean> noGunTrait = compute(
                ResourceLocation.parse("mypack:test_gun_no_" + UUID.randomUUID()), Map.of(), plugins);
        assertEquals(false, noGunTrait.get(RAPID),
                "枪械未声明时高优先级插件（B=200, false）必须覆盖低优先级（A=100, true）");
        assertFalse(noGunTrait.keySet().stream().anyMatch(k -> k.getNamespace().equals("minecraft")),
                "合并结果不得含 minecraft: 键，实际 " + noGunTrait.keySet());

        // 枪械声明 rapid=true → 枪械覆盖所有插件。
        Map<ResourceLocation, Boolean> gunWins = compute(
                ResourceLocation.parse("mypack:test_gun_yes_" + UUID.randomUUID()),
                Map.of(RAPID, true), plugins);
        assertEquals(true, gunWins.get(RAPID), "枪械固有 traits 必须覆盖所有插件");
        assertEquals(1, gunWins.size(), "rapid 只占一个键（不得出现 modularshoot/minecraft 双键），实际 " + gunWins.keySet());
    }
}
