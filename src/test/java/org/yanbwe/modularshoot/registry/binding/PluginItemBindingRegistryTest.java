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
}
