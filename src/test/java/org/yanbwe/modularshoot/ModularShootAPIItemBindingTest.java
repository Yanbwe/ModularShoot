package org.yanbwe.modularshoot;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.registry.binding.GunItemBinding;
import org.yanbwe.modularshoot.registry.binding.GunItemBindingRegistry;
import org.yanbwe.modularshoot.registry.binding.PluginItemBinding;
import org.yanbwe.modularshoot.registry.binding.PluginItemBindingRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for the {@link ModularShootAPI} dual-channel item recognition
 * (设计规格 物品绑定系统 §4.1): component channel first
 * ({@code gun_data}/{@code plugin_data}), binding channel as fallback
 * (Java API + datapack).
 *
 * <h2>JUnit environment (probe-verified, 2026-08-07)</h2>
 * <p>Plain JUnit cannot construct {@link ItemStack} or resolve the framework
 * {@link DeferredHolder}s without extra setup. A probe test verified each
 * step; the recorded verdicts:</p>
 * <ol>
 *   <li>{@code new ItemStack(...)} fails with {@code Not bootstrapped}
 *       ({@code BuiltInRegistries.<clinit>}) — vanilla {@code Bootstrap} must
 *       run first.</li>
 *   <li>{@code Bootstrap.bootStrap()} fails with an NPE from
 *       {@code FeatureFlagLoader} (FML {@code LoadingModList} absent) and
 *       {@code Game version not set} ({@code SharedConstants} / DataFixers).
 *       Fixed by {@code LoadingModList.of(...)} + {@code SharedConstants.setVersion}
 *       before bootstrapping.</li>
 *   <li>NeoForge's {@code Bootstrap} patch freezes the vanilla registries
 *       ({@code GameData.vanillaSnapshot}); {@code GameData.unfreezeData()}
 *       reopens them so tests can register unique items.</li>
 *   <li>{@code ModularShootDataComponents.GUN_DATA.get()} (DeferredHolder)
 *       throws {@code Trying to access unbound value} in JUnit — the holder
 *       is only bound by the NeoForge {@code RegisterEvent}, which never
 *       fires here. {@link #bindComponentHolders()} binds both holders to
 *       {@link Holder#direct} instances via reflection so the component
 *       channel is fully exercisable; if the NeoForge internal field layout
 *       changes, the bind fails, {@link #COMPONENT_HOLDERS_BOUND} becomes
 *       {@code false}, and the component-channel tests degrade to skipped
 *       (per the task's degradation rule) while the binding-channel tests
 *       keep running.</li>
 * </ol>
 *
 * <h2>Shared static state</h2>
 * <p>The binding registries keep static {@code JAVA_API_BINDINGS} maps that
 * persist across test classes in the same JVM (and {@code BuiltInRegistries.ITEM}
 * is shared too). Every test therefore registers its own unique item id
 * ({@code minecraft:test_&lt;kind&gt;_&lt;uuid&gt;}) and a unique binding entry
 * key, so no test depends on another test class's bindings and none of them
 * can be polluted by bindings registered by {@code GunItemBindingRegistryTest}
 * ({@code minecraft:diamond_sword}, {@code minecraft:stick}) or
 * {@code PluginItemBindingRegistryTest}.</p>
 */
class ModularShootAPIItemBindingTest {

    private static final RegistryAccess EMPTY = RegistryAccess.EMPTY;

    // ---- Environment bootstrap (probe-verified) -------------------------

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
     * {@link Holder}s so their {@code get()} resolves in JUnit (see the class
     * javadoc, probe verdict 4). Returns {@code false} (and component-channel
     * tests degrade to skipped) when the NeoForge internals no longer expose
     * the {@code holder} field.
     */
    private static boolean bindComponentHolders() {
        try {
            Field holderField = DeferredHolder.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            DataComponentType<GunData> gunType = new DataComponentType.Builder<GunData>()
                    .persistent(GunData.CODEC).build();
            holderField.set(ModularShootDataComponents.GUN_DATA, Holder.direct(gunType));
            DataComponentType<PluginData> pluginType = new DataComponentType.Builder<PluginData>()
                    .persistent(PluginData.CODEC).build();
            holderField.set(ModularShootDataComponents.PLUGIN_DATA, Holder.direct(pluginType));
            return true;
        } catch (Exception e) {
            System.err.println("[ModularShootAPIItemBindingTest] DeferredHolder bind failed: "
                    + e);
            return false;
        }
    }

    /** Probe verdict: {@code true} when the component channel is usable in JUnit. */
    private static final boolean COMPONENT_HOLDERS_BOUND = bindComponentHolders();

    // ---- Helpers ---------------------------------------------------------

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

    /** Registers a unique plugin binding and returns the bound item stack. */
    private static ItemStack newPluginBoundStack(String discriminator, ResourceLocation pluginId) {
        ItemStack stack = new ItemStack(newUniqueItem(discriminator));
        PluginItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:test_plugin_binding_" + UUID.randomUUID()),
                new PluginItemBinding(itemId(stack), pluginId));
        return stack;
    }

    private static ItemStack stackWithGunData(ResourceLocation gunId) {
        ItemStack stack = new ItemStack(newUniqueItem("gun"));
        stack.set(ModularShootDataComponents.GUN_DATA.get(),
                GunData.create(gunId, UUID.randomUUID()));
        return stack;
    }

    private static ItemStack stackWithPluginData(ResourceLocation pluginId) {
        ItemStack stack = new ItemStack(newUniqueItem("plugin"));
        stack.set(ModularShootDataComponents.PLUGIN_DATA.get(), new PluginData(pluginId));
        return stack;
    }

    // ---- Component channel (degraded to skip when the holder bind fails) --

    @Test
    void componentChannelIsGun() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件通道用例跳过（见类 javadoc 探针结论 4）");
        ItemStack stack = stackWithGunData(ResourceLocation.parse("mypack:rifle"));
        assertTrue(ModularShootAPI.isGun(stack, EMPTY));
    }

    @Test
    void componentChannelIsPlugin() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "PLUGIN_DATA DeferredHolder 未绑定，组件通道用例跳过（见类 javadoc 探针结论 4）");
        ItemStack stack = stackWithPluginData(ResourceLocation.parse("mypack:rifle_plugin"));
        assertTrue(ModularShootAPI.isPlugin(stack, EMPTY));
    }

    @Test
    void resolveGunIdComponentChannelTakesPriorityOverBinding() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件通道用例跳过（见类 javadoc 探针结论 4）");
        // Component id differs from the binding id: the component channel must win.
        ItemStack stack = stackWithGunData(ResourceLocation.parse("mypack:component_gun"));
        GunItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:test_priority_binding_" + UUID.randomUUID()),
                new GunItemBinding(itemId(stack), ResourceLocation.parse("mypack:bound_gun")));
        assertEquals(Optional.of(ResourceLocation.parse("mypack:component_gun")),
                ModularShootAPI.resolveGunId(stack, EMPTY));
    }

    @Test
    void resolvePluginIdComponentChannel() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "PLUGIN_DATA DeferredHolder 未绑定，组件通道用例跳过（见类 javadoc 探针结论 4）");
        ItemStack stack = stackWithPluginData(ResourceLocation.parse("mypack:component_plugin"));
        assertEquals(Optional.of(ResourceLocation.parse("mypack:component_plugin")),
                ModularShootAPI.resolvePluginId(stack, EMPTY));
    }

    @Test
    void getGunDataReadsComponentWithoutItemIdCheck() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件通道用例跳过（见类 javadoc 探针结论 4）");
        // A bound/vanilla item is not a modularshoot:gun item; after the
        // precheck removal the component is read directly (绑定枪械附加组件前
        // 返回 empty 属预期，见 §4.2).
        ItemStack stack = stackWithGunData(ResourceLocation.parse("mypack:sword_rifle"));
        assertTrue(ModularShootAPI.getGunData(stack).isPresent());
        assertEquals(ResourceLocation.parse("mypack:sword_rifle"),
                ModularShootAPI.getGunData(stack).orElseThrow().gunId());
    }

    @Test
    void getPluginDataReadsComponentWithoutItemIdCheck() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "PLUGIN_DATA DeferredHolder 未绑定，组件通道用例跳过（见类 javadoc 探针结论 4）");
        ItemStack stack = stackWithPluginData(ResourceLocation.parse("mypack:light_plugin"));
        assertTrue(ModularShootAPI.getPluginData(stack).isPresent());
        assertEquals(ResourceLocation.parse("mypack:light_plugin"),
                ModularShootAPI.getPluginData(stack).orElseThrow().pluginId());
    }

    // ---- Binding channel (Java API) --------------------------------------

    @Test
    void bindingChannelJavaApiIsGun() {
        ItemStack stack = newGunBoundStack(
                "gun_bind", ResourceLocation.parse("mypack:sword_rifle"));
        assertTrue(ModularShootAPI.isGun(stack, EMPTY));
    }

    @Test
    void bindingChannelJavaApiIsPlugin() {
        ItemStack stack = newPluginBoundStack(
                "plugin_bind", ResourceLocation.parse("mypack:light_plugin"));
        assertTrue(ModularShootAPI.isPlugin(stack, EMPTY));
    }

    @Test
    void noComponentNoBindingIsNotGun() {
        ItemStack stack = new ItemStack(newUniqueItem("plain"));
        assertFalse(ModularShootAPI.isGun(stack, EMPTY));
    }

    @Test
    void noComponentNoBindingIsNotPlugin() {
        ItemStack stack = new ItemStack(newUniqueItem("plain_plugin"));
        assertFalse(ModularShootAPI.isPlugin(stack, EMPTY));
    }

    @Test
    void resolveGunIdBindingChannel() {
        ItemStack stack = newGunBoundStack(
                "gun_resolve", ResourceLocation.parse("mypack:sword_rifle"));
        assertEquals(Optional.of(ResourceLocation.parse("mypack:sword_rifle")),
                ModularShootAPI.resolveGunId(stack, EMPTY));
    }

    @Test
    void resolveGunIdEmptyWhenUnbound() {
        ItemStack stack = new ItemStack(newUniqueItem("gun_unbound"));
        assertTrue(ModularShootAPI.resolveGunId(stack, EMPTY).isEmpty());
    }

    @Test
    void resolvePluginIdBindingChannel() {
        ItemStack stack = newPluginBoundStack(
                "plugin_resolve", ResourceLocation.parse("mypack:light_plugin"));
        assertEquals(Optional.of(ResourceLocation.parse("mypack:light_plugin")),
                ModularShootAPI.resolvePluginId(stack, EMPTY));
    }

    @Test
    void resolvePluginIdEmptyWhenUnbound() {
        ItemStack stack = new ItemStack(newUniqueItem("plugin_unbound"));
        assertTrue(ModularShootAPI.resolvePluginId(stack, EMPTY).isEmpty());
    }

    @Test
    void getGunDataEmptyWithoutComponent() {
        ItemStack stack = new ItemStack(newUniqueItem("gun_nodata"));
        assertTrue(ModularShootAPI.getGunData(stack).isEmpty());
    }

    @Test
    void getPluginDataEmptyWithoutComponent() {
        ItemStack stack = new ItemStack(newUniqueItem("plugin_nodata"));
        assertTrue(ModularShootAPI.getPluginData(stack).isEmpty());
    }

    // ---- Degraded overloads (no RegistryAccess) --------------------------

    /**
     * The degraded overloads ({@link ModularShootAPI#isGun(ItemStack)} /
     * {@link ModularShootAPI#isPlugin(ItemStack)}) see Java-API bindings
     * while staying blind to datapack bindings: they query with
     * {@link RegistryAccess#EMPTY}, which never contains the datapack
     * registries (设计规格 物品绑定系统 §4.1).
     */
    @Test
    void degradedOverloadSeesJavaApiBindings() {
        // Java-API binding is visible to the degraded overload...
        ItemStack bound = newGunBoundStack(
                "degraded", ResourceLocation.parse("mypack:sword_rifle"));
        assertTrue(ModularShootAPI.isGun(bound));
        // ...and an unbound item is not a gun (datapack entries cannot exist
        // with the EMPTY registry view used by the degraded overload).
        ItemStack unbound = new ItemStack(newUniqueItem("degraded_unbound"));
        assertFalse(ModularShootAPI.isGun(unbound));
        assertFalse(ModularShootAPI.isPlugin(unbound));
    }

    @Test
    void degradedOverloadIgnoresDatapackPluginSymmetric() {
        ItemStack bound = newPluginBoundStack(
                "degraded_plugin", ResourceLocation.parse("mypack:light_plugin"));
        assertTrue(ModularShootAPI.isPlugin(bound));
    }
}
