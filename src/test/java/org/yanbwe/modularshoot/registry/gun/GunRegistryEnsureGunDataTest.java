package org.yanbwe.modularshoot.registry.gun;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.registry.binding.GunItemBinding;
import org.yanbwe.modularshoot.registry.binding.GunItemBindingRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link GunRegistry#ensureGunData} — the lazy
 * {@code gun_data} attachment for bound guns (设计规格 物品绑定系统 §5.2).
 *
 * <h2>JUnit environment bridge</h2>
 * <p>Plain JUnit cannot construct {@link ItemStack} or resolve the framework
 * {@link DeferredHolder}s without extra setup. The static initializer copies
 * the probe-verified bridge of
 * {@link org.yanbwe.modularshoot.ModularShootAPIItemBindingTest} verbatim:
 * {@code LoadingModList} shim, {@code SharedConstants.setVersion},
 * {@code Bootstrap.bootStrap()} and {@code GameData.unfreezeData()} reopen the
 * vanilla registries for unique test-item registration. {@code GUN_DATA} /
 * {@code PLUGIN_DATA} DeferredHolders are bound to direct {@link Holder}s via
 * reflection ({@link #bindComponentHolders()}) so {@code GUN_DATA.get()}
 * resolves in JUnit; if the NeoForge internal field layout changes the bind
 * fails, {@link #COMPONENT_HOLDERS_BOUND} becomes {@code false}, and the
 * component-dependent cases degrade to skipped (same degradation rule as the
 * reference test).</p>
 *
 * <h2>Shared static binding-map pollution avoidance</h2>
 * <p>{@link GunItemBindingRegistry} keeps a static {@code JAVA_API_BINDINGS}
 * map that persists across test classes in the same JVM (and
 * {@code BuiltInRegistries.ITEM} is shared too). Every test therefore
 * registers its own unique item id ({@code minecraft:test_&lt;kind&gt;_&lt;uuid&gt;})
 * and a unique binding entry key, so no test depends on — and none can be
 * polluted by — bindings registered by other test classes
 * ({@code GunItemBindingRegistryTest}, {@code ModularShootAPIItemBindingTest},
 * or sibling cases in this class).</p>
 */
class GunRegistryEnsureGunDataTest {

    private static final RegistryAccess EMPTY = RegistryAccess.EMPTY;

    // ---- Environment bootstrap (probe-verified bridge, see class javadoc) -

    static {
        // FML shim: FeatureFlags.<clinit> -> FeatureFlagLoader needs a non-null
        // LoadingModList; of() installs an empty instance (loader 4.0.42).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        // DataFixers.<clinit> requires a current game version.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        // Full vanilla registry bootstrap (also freezes vanilla registries via
        // the NeoForge vanillaSnapshot patch).
        net.minecraft.server.Bootstrap.bootStrap();
        // Reopen the registries for test-item registration.
        net.neoforged.neoforge.registries.GameData.unfreezeData();
    }

    /**
     * Binds {@code GUN_DATA}/{@code PLUGIN_DATA} DeferredHolders to direct
     * {@link Holder}s so their {@code get()} resolves in JUnit (bridge of
     * {@link org.yanbwe.modularshoot.ModularShootAPIItemBindingTest}, probe
     * verdict 4). Returns {@code false} (and component-dependent tests
     * degrade to skipped) when the NeoForge internals no longer expose the
     * {@code holder} field.
     */
    private static boolean bindComponentHolders() {
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
            DataComponentType<org.yanbwe.modularshoot.component.PluginData> pluginType =
                    new DataComponentType.Builder<org.yanbwe.modularshoot.component.PluginData>()
                            .persistent(org.yanbwe.modularshoot.component.PluginData.CODEC).build();
            holderField.set(ModularShootDataComponents.PLUGIN_DATA, Holder.direct(pluginType));
            return true;
        } catch (Exception e) {
            System.err.println("[GunRegistryEnsureGunDataTest] DeferredHolder bind failed: "
                    + e);
            return false;
        }
    }

    /** Probe verdict: {@code true} when {@code GUN_DATA.get()} is usable in JUnit. */
    private static final boolean COMPONENT_HOLDERS_BOUND = bindComponentHolders();

    // ---- Helpers (unique per test, see class javadoc) ---------------------

    /** Registers a unique item id and returns the registered item. */
    private static Item newUniqueItem(String discriminator) {
        ResourceLocation id = ResourceLocation.parse(
                "minecraft:test_" + discriminator + "_" + UUID.randomUUID());
        Item item = new Item(new Item.Properties());
        Registry.register(BuiltInRegistries.ITEM, id, item);
        return item;
    }

    private static ResourceLocation itemId(ItemStack stack) {
        return stack.getItemHolder().unwrapKey().map(ResourceKey::location).orElseThrow();
    }

    /** Registers a unique gun binding and returns the bound item stack. */
    private static ItemStack newGunBoundStack(String discriminator, ResourceLocation gunId) {
        ItemStack stack = new ItemStack(newUniqueItem(discriminator));
        GunItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:test_gun_binding_" + UUID.randomUUID()),
                new GunItemBinding(itemId(stack), gunId));
        return stack;
    }

    // ---- ensureGunData ----------------------------------------------------

    @Test
    void ensuresGunDataOnBoundStack() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        ResourceLocation gunId = ResourceLocation.parse("mypack:bound_rifle_" + UUID.randomUUID());
        ItemStack stack = newGunBoundStack("ensure", gunId);

        GunRegistry.ensureGunData(stack, EMPTY);

        GunData data = stack.get(ModularShootDataComponents.GUN_DATA.get());
        assertNotNull(data, "绑定枪械附加后必须携带 gun_data 组件");
        assertEquals(gunId, data.gunId());
        assertTrue(data.installedPlugins().isEmpty(), "新附加的插件列表必须为空");
        assertEquals(0, data.modifierVersion(), "新附加的 modifierVersion 必须为 0");
        assertNotNull(data.gunInstanceUuid(), "新附加的实例 UUID 必须非空");
    }

    @Test
    void idempotent() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        ItemStack stack = newGunBoundStack(
                "idempotent", ResourceLocation.parse("mypack:bound_rifle_" + UUID.randomUUID()));

        GunRegistry.ensureGunData(stack, EMPTY);
        GunData first = stack.get(ModularShootDataComponents.GUN_DATA.get());
        assertNotNull(first);

        GunRegistry.ensureGunData(stack, EMPTY);
        GunData second = stack.get(ModularShootDataComponents.GUN_DATA.get());
        assertEquals(first.gunInstanceUuid(), second.gunInstanceUuid(),
                "重复调用不得重新生成实例 UUID");
        assertEquals(first, second, "重复调用后组件必须完全不变");
        assertTrue(second.installedPlugins().isEmpty(), "重复调用后插件列表仍为空");
    }

    @Test
    void noOpOnUnboundStack() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        ItemStack stack = new ItemStack(newUniqueItem("unbound"));

        assertDoesNotThrow(() -> GunRegistry.ensureGunData(stack, EMPTY));

        assertFalse(stack.has(ModularShootDataComponents.GUN_DATA.get()),
                "未绑定物品不得被附加 gun_data");
    }

    @Test
    void noOpOnComponentStack() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件用例跳过（见类 javadoc 桥接说明）");
        ItemStack stack = new ItemStack(newUniqueItem("component"));
        GunData original = GunData.create(
                ResourceLocation.parse("mypack:existing_gun_" + UUID.randomUUID()),
                UUID.randomUUID());
        stack.set(ModularShootDataComponents.GUN_DATA.get(), original);

        GunRegistry.ensureGunData(stack, EMPTY);

        GunData after = stack.get(ModularShootDataComponents.GUN_DATA.get());
        assertEquals(original, after,
                "已携带 gun_data 的栈必须保持组件不变（uuid 与 gunId 不变）");
    }
}
