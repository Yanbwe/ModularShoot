package org.yanbwe.modularshoot.registry.binding;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GunItemBindingRegistry} (设计规格 物品绑定系统 §3.2/§3.3).
 *
 * <p>The datapack-registry query path of {@code getBoundGunId} requires a live
 * {@code RegistryAccess} and is therefore not unit-tested; the Java-API
 * registration path and the pure package-private {@code findByItem} selection
 * function get full coverage here. {@link
 * org.yanbwe.modularshoot.datapack.RegistrationCoordinator#markJavaApiRegistered}
 * is a pure static map write and can be invoked directly from JUnit without a
 * registry.</p>
 *
 * <p>All tests use per-test unique entry keys so the shared static Java-API
 * store does not leak state between test methods.</p>
 */
class GunItemBindingRegistryTest {

    private static final ResourceLocation ITEM_DIAMOND_SWORD =
            ResourceLocation.parse("minecraft:diamond_sword");
    private static final ResourceLocation ITEM_STICK =
            ResourceLocation.parse("minecraft:stick");

    @Test
    void findByItemReturnsBindingForMatchingItem() {
        Map<ResourceLocation, GunItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:rifle_binding"),
                new GunItemBinding(ITEM_DIAMOND_SWORD,
                        ResourceLocation.parse("mypack:sword_rifle")));
        Optional<GunItemBinding> found = GunItemBindingRegistry.findByItem(
                entries, ITEM_DIAMOND_SWORD);
        assertTrue(found.isPresent());
        assertEquals(ResourceLocation.parse("mypack:sword_rifle"), found.get().gunId());
    }

    @Test
    void findByItemReturnsEmptyWhenNoMatch() {
        Map<ResourceLocation, GunItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:rifle_binding"),
                new GunItemBinding(ITEM_DIAMOND_SWORD,
                        ResourceLocation.parse("mypack:sword_rifle")));
        assertTrue(GunItemBindingRegistry.findByItem(
                entries, ResourceLocation.parse("minecraft:netherite_sword")).isEmpty());
    }

    @Test
    void findByItemDuplicateItemReturnsLexicographicallySmallestKey() {
        Map<ResourceLocation, GunItemBinding> entries = Map.of(
                ResourceLocation.parse("mypack:z_binding"),
                new GunItemBinding(ITEM_DIAMOND_SWORD,
                        ResourceLocation.parse("mypack:gun_z")),
                ResourceLocation.parse("mypack:a_binding"),
                new GunItemBinding(ITEM_DIAMOND_SWORD,
                        ResourceLocation.parse("mypack:gun_a")));
        Optional<GunItemBinding> found = GunItemBindingRegistry.findByItem(
                entries, ITEM_DIAMOND_SWORD);
        assertTrue(found.isPresent());
        assertEquals(ResourceLocation.parse("mypack:gun_a"), found.get().gunId());
    }

    @Test
    void registerBindingExposesJavaApiEntry() {
        ResourceLocation key = ResourceLocation.parse("mypack:test_binding");
        GunItemBinding binding = new GunItemBinding(
                ITEM_STICK, ResourceLocation.parse("mypack:stick_gun"));
        GunItemBindingRegistry.registerBinding(key, binding);
        assertEquals(binding, GunItemBindingRegistry.getJavaApiBindings().get(key));
        // Re-registering the same key replaces the previous binding.
        GunItemBinding replacement = new GunItemBinding(
                ITEM_STICK, ResourceLocation.parse("mypack:stick_gun_v2"));
        GunItemBindingRegistry.registerBinding(key, replacement);
        assertEquals(replacement, GunItemBindingRegistry.getJavaApiBindings().get(key));
    }

    @Test
    void getBoundGunIdMatchesJavaApiBinding() {
        ResourceLocation gunId = ResourceLocation.parse("mypack:sword_rifle");
        GunItemBindingRegistry.registerBinding(
                ResourceLocation.parse("mypack:query_test_binding"),
                new GunItemBinding(ITEM_DIAMOND_SWORD, gunId));
        Optional<ResourceLocation> bound = GunItemBindingRegistry.getBoundGunId(
                RegistryAccess.EMPTY, ITEM_DIAMOND_SWORD);
        assertTrue(bound.isPresent());
        assertEquals(gunId, bound.get());
    }

    @Test
    void getBoundGunIdEmptyWhenUnbound() {
        assertTrue(GunItemBindingRegistry.getBoundGunId(
                RegistryAccess.EMPTY,
                ResourceLocation.parse("minecraft:netherite_sword")).isEmpty());
    }
}
