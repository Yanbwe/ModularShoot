package org.yanbwe.modularshoot.util;

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
 * Behavior test for {@link GunRecognition} — the extracted dual-channel
 * gun/plugin recognition core (设计规格 物品绑定系统 §4.1, Task 6.1). Guards that
 * the logic moved out of {@code ModularShootAPI} still behaves identically:
 * component channel first, binding channel as fallback, degraded overloads
 * staying blind to datapack bindings.
 */
class GunRecognitionTest {

    private static final RegistryAccess EMPTY = RegistryAccess.EMPTY;

    // ---- Environment bootstrap (bridge of ModularShootAPIItemBindingTest) -

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        net.neoforged.neoforge.registries.GameData.unfreezeData();
    }

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
            System.err.println("[GunRecognitionTest] DeferredHolder bind failed: " + e);
            return false;
        }
    }

    private static final boolean COMPONENT_HOLDERS_BOUND = bindComponentHolders();

    // ---- Helpers ---------------------------------------------------------

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

    private static ItemStack newGunBoundStack(String discriminator, ResourceLocation gunId) {
        ItemStack stack = new ItemStack(newUniqueItem(discriminator));
        GunItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:test_gun_binding_" + UUID.randomUUID()),
                new GunItemBinding(itemId(stack), gunId));
        return stack;
    }

    private static ItemStack newPluginBoundStack(String discriminator, ResourceLocation pluginId) {
        ItemStack stack = new ItemStack(newUniqueItem(discriminator));
        PluginItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:test_plugin_binding_" + UUID.randomUUID()),
                new PluginItemBinding(itemId(stack), pluginId));
        return stack;
    }

    // ---- Gun recognition -------------------------------------------------

    @Test
    void isGunComponentChannel() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件通道用例跳过");
        ItemStack stack = new ItemStack(newUniqueItem("gun"));
        stack.set(ModularShootDataComponents.GUN_DATA.get(),
                GunData.create(ResourceLocation.parse("mypack:rifle"), UUID.randomUUID()));
        assertTrue(GunRecognition.isGun(stack, EMPTY));
    }

    @Test
    void isGunBindingChannelJavaApi() {
        ItemStack stack = newGunBoundStack(
                "gun_bind", ResourceLocation.parse("mypack:sword_rifle"));
        assertTrue(GunRecognition.isGun(stack, EMPTY));
    }

    @Test
    void isGunNoComponentNoBindingIsFalse() {
        ItemStack stack = new ItemStack(newUniqueItem("plain"));
        assertFalse(GunRecognition.isGun(stack, EMPTY));
    }

    @Test
    void resolveGunIdComponentChannelTakesPriorityOverBinding() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "GUN_DATA DeferredHolder 未绑定，组件通道用例跳过");
        ItemStack stack = new ItemStack(newUniqueItem("gun"));
        stack.set(ModularShootDataComponents.GUN_DATA.get(),
                GunData.create(ResourceLocation.parse("mypack:component_gun"), UUID.randomUUID()));
        GunItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:test_priority_binding_" + UUID.randomUUID()),
                new GunItemBinding(itemId(stack), ResourceLocation.parse("mypack:bound_gun")));
        assertEquals(Optional.of(ResourceLocation.parse("mypack:component_gun")),
                GunRecognition.resolveGunId(stack, EMPTY));
    }

    @Test
    void resolveGunIdBindingChannel() {
        ItemStack stack = newGunBoundStack(
                "gun_resolve", ResourceLocation.parse("mypack:sword_rifle"));
        assertEquals(Optional.of(ResourceLocation.parse("mypack:sword_rifle")),
                GunRecognition.resolveGunId(stack, EMPTY));
    }

    @Test
    void resolveGunIdEmptyWhenUnbound() {
        ItemStack stack = new ItemStack(newUniqueItem("gun_unbound"));
        assertTrue(GunRecognition.resolveGunId(stack, EMPTY).isEmpty());
    }

    @Test
    void degradedOverloadSeesJavaApiBindings() {
        ItemStack bound = newGunBoundStack(
                "degraded", ResourceLocation.parse("mypack:sword_rifle"));
        assertTrue(GunRecognition.isGun(bound));
        ItemStack unbound = new ItemStack(newUniqueItem("degraded_unbound"));
        assertFalse(GunRecognition.isGun(unbound));
    }

    // ---- Plugin recognition ----------------------------------------------

    @Test
    void isPluginComponentChannel() {
        assumeTrue(COMPONENT_HOLDERS_BOUND,
                "PLUGIN_DATA DeferredHolder 未绑定，组件通道用例跳过");
        ItemStack stack = new ItemStack(newUniqueItem("plugin"));
        stack.set(ModularShootDataComponents.PLUGIN_DATA.get(),
                new PluginData(ResourceLocation.parse("mypack:light_plugin")));
        assertTrue(GunRecognition.isPlugin(stack, EMPTY));
    }

    @Test
    void isPluginBindingChannelJavaApi() {
        ItemStack stack = newPluginBoundStack(
                "plugin_bind", ResourceLocation.parse("mypack:light_plugin"));
        assertTrue(GunRecognition.isPlugin(stack, EMPTY));
    }

    @Test
    void isPluginNoComponentNoBindingIsFalse() {
        ItemStack stack = new ItemStack(newUniqueItem("plain_plugin"));
        assertFalse(GunRecognition.isPlugin(stack, EMPTY));
    }

    @Test
    void resolvePluginIdBindingChannel() {
        ItemStack stack = newPluginBoundStack(
                "plugin_resolve", ResourceLocation.parse("mypack:light_plugin"));
        assertEquals(Optional.of(ResourceLocation.parse("mypack:light_plugin")),
                GunRecognition.resolvePluginId(stack, EMPTY));
    }

    @Test
    void resolvePluginIdEmptyWhenUnbound() {
        ItemStack stack = new ItemStack(newUniqueItem("plugin_unbound"));
        assertTrue(GunRecognition.resolvePluginId(stack, EMPTY).isEmpty());
    }

    @Test
    void degradedPluginOverloadSeesJavaApiBindings() {
        ItemStack bound = newPluginBoundStack(
                "degraded_plugin", ResourceLocation.parse("mypack:light_plugin"));
        assertTrue(GunRecognition.isPlugin(bound));
    }
}
