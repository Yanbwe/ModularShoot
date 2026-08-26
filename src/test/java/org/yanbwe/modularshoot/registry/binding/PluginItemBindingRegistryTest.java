package org.yanbwe.modularshoot.registry.binding;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PluginItemBindingRegistry} (设计规格 物品绑定系统
 * §3.2/§3.3).
 *
 * <p>The datapack-registry query path of {@code getBoundPluginId} requires a
 * live {@code RegistryAccess} and is therefore not unit-tested; the Java-API
 * registration path and the pure package-private {@code findByItem} selection
 * function get full coverage here. {@link
 * org.yanbwe.modularshoot.datapack.RegistrationCoordinator#markJavaApiRegistered}
 * is a pure static map write and can be invoked directly from JUnit without a
 * registry.</p>
 *
 * <p>All tests use per-test unique entry keys so the shared static Java-API
 * store does not leak state between test methods.</p>
 */
class PluginItemBindingRegistryTest {

    private static final ResourceLocation ITEM_STICK =
            ResourceLocation.parse("minecraft:stick");
    private static final ResourceLocation ITEM_IRON_SWORD =
            ResourceLocation.parse("minecraft:iron_sword");

    @Test
    void findByItemReturnsBindingForMatchingItem() {
        Map<ResourceLocation, PluginItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:light_binding"),
                new PluginItemBinding(ITEM_STICK,
                        ResourceLocation.parse("mypack:light_plugin")));
        Optional<PluginItemBinding> found = PluginItemBindingRegistry.findByItem(
                entries, ITEM_STICK);
        assertTrue(found.isPresent());
        assertEquals(ResourceLocation.parse("mypack:light_plugin"), found.get().pluginId());
    }

    @Test
    void findByItemReturnsEmptyWhenNoMatch() {
        Map<ResourceLocation, PluginItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:light_binding"),
                new PluginItemBinding(ITEM_STICK,
                        ResourceLocation.parse("mypack:light_plugin")));
        assertTrue(PluginItemBindingRegistry.findByItem(
                entries, ResourceLocation.parse("minecraft:netherite_sword")).isEmpty());
    }

    @Test
    void findByItemDuplicateItemReturnsLexicographicallySmallestKey() {
        Map<ResourceLocation, PluginItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:z_binding"),
                new PluginItemBinding(ITEM_STICK,
                        ResourceLocation.parse("mypack:plugin_z")),
                ResourceLocation.parse("mypack:a_binding"),
                new PluginItemBinding(ITEM_STICK,
                        ResourceLocation.parse("mypack:plugin_a")));
        Optional<PluginItemBinding> found = PluginItemBindingRegistry.findByItem(
                entries, ITEM_STICK);
        assertTrue(found.isPresent());
        assertEquals(ResourceLocation.parse("mypack:plugin_a"), found.get().pluginId());
    }

    @Test
    void registerBindingExposesJavaApiEntry() {
        ResourceLocation key = ResourceLocation.parse("mypack:test_plugin_binding");
        PluginItemBinding binding = new PluginItemBinding(
                ITEM_IRON_SWORD, ResourceLocation.parse("mypack:melee_plugin"));
        PluginItemBindingRegistry.registerBinding(key, binding);
        assertEquals(binding, PluginItemBindingRegistry.getJavaApiBindings().get(key));
        // Re-registering the same key replaces the previous binding.
        PluginItemBinding replacement = new PluginItemBinding(
                ITEM_IRON_SWORD, ResourceLocation.parse("mypack:melee_plugin_v2"));
        PluginItemBindingRegistry.registerBinding(key, replacement);
        assertEquals(replacement, PluginItemBindingRegistry.getJavaApiBindings().get(key));
    }

    @Test
    void getBoundPluginIdMatchesJavaApiBinding() {
        ResourceLocation pluginId = ResourceLocation.parse("mypack:light_plugin");
        PluginItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:query_test_plugin_binding"),
                new PluginItemBinding(ITEM_STICK, pluginId));
        Optional<ResourceLocation> bound = PluginItemBindingRegistry.getBoundPluginId(
                RegistryAccess.EMPTY, ITEM_STICK);
        assertTrue(bound.isPresent());
        assertEquals(pluginId, bound.get());
    }

    @Test
    void getBoundPluginIdEmptyWhenUnbound() {
        assertTrue(PluginItemBindingRegistry.getBoundPluginId(
                RegistryAccess.EMPTY,
                ResourceLocation.parse("minecraft:netherite_sword")).isEmpty());
    }

    @Test
    void reRegisteringKeyWithDifferentItemRepairsStaleIndex() {
        // 审查 R5 回归：同一 key 换绑不同物品时，旧物品的反向索引不得残留。
        ResourceLocation key = ResourceLocation.parse("mypack:r5_moving_binding");
        ResourceLocation itemA = ResourceLocation.parse("minecraft:gold_nugget");
        ResourceLocation itemB = ResourceLocation.parse("minecraft:blaze_rod");
        ResourceLocation pluginA = ResourceLocation.parse("mypack:r5_plugin_a");
        ResourceLocation pluginB = ResourceLocation.parse("mypack:r5_plugin_b");

        PluginItemBindingRegistry.registerBinding(key, new PluginItemBinding(itemA, pluginA));
        PluginItemBindingRegistry.registerBinding(key, new PluginItemBinding(itemB, pluginB));

        assertEquals(Optional.of(pluginB),
                PluginItemBindingRegistry.getBoundPluginId(RegistryAccess.EMPTY, itemB),
                "新绑定的物品必须解析到新目标");
        assertTrue(PluginItemBindingRegistry.getBoundPluginId(RegistryAccess.EMPTY, itemA).isEmpty(),
                "旧物品的反向索引必须被清理（审查 R5）");
    }

    @Test
    void buildDatapackIndexPicksSmallestKeyPerItem() {
        Map<ResourceLocation, PluginItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:z_binding"),
                new PluginItemBinding(ResourceLocation.parse("minecraft:stick"),
                        ResourceLocation.parse("mypack:plugin_z")),
                ResourceLocation.parse("mypack:a_binding"),
                new PluginItemBinding(ResourceLocation.parse("minecraft:stick"),
                        ResourceLocation.parse("mypack:plugin_a")));
        Map<ResourceLocation, PluginItemBinding> index =
                PluginItemBindingRegistry.buildDatapackIndex(entries);
        assertEquals(ResourceLocation.parse("mypack:plugin_a"),
                index.get(ResourceLocation.parse("minecraft:stick")).pluginId());
    }

    @Test
    void buildDatapackIndexDistinctItems() {
        Map<ResourceLocation, PluginItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:stick_binding"),
                new PluginItemBinding(ResourceLocation.parse("minecraft:stick"),
                        ResourceLocation.parse("mypack:stick_plugin")),
                ResourceLocation.parse("mypack:blaze_binding"),
                new PluginItemBinding(ResourceLocation.parse("minecraft:blaze_rod"),
                        ResourceLocation.parse("mypack:blaze_plugin")));
        Map<ResourceLocation, PluginItemBinding> index =
                PluginItemBindingRegistry.buildDatapackIndex(entries);
        assertEquals(2, index.size());
        assertEquals(ResourceLocation.parse("mypack:stick_plugin"),
                index.get(ResourceLocation.parse("minecraft:stick")).pluginId());
        assertEquals(ResourceLocation.parse("mypack:blaze_plugin"),
                index.get(ResourceLocation.parse("minecraft:blaze_rod")).pluginId());
    }

    @Test
    void buildDatapackIndexEmpty() {
        assertTrue(PluginItemBindingRegistry.buildDatapackIndex(Map.of()).isEmpty());
    }
}
