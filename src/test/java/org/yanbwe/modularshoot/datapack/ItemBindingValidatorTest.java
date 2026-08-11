package org.yanbwe.modularshoot.datapack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.binding.GunItemBinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ItemBindingValidator#findDuplicateItemKeys} and
 * {@link ItemBindingValidator#findBindingsWithUnknownGunId}
 * (设计规格 物品绑定系统 §3.2).
 *
 * <p>The {@code validateGunBindings}/{@code validatePluginBindings} methods
 * require a live {@code RegistryAccess} and the vanilla item registry, so
 * they are not unit-tested here; the duplicate-binding rule ("同一物品 ID
 * 出现多个绑定 → WARN + 字典序最小键胜出") and the unknown-gun-id rule
 * ("Bound gun not found", 已知枪械 id = datapack 键 ∪ Java API 键，审查修复
 * M5) are extracted as pure package-private functions so the core matching
 * semantics get full coverage.</p>
 *
 * <p>Input maps are built as {@link LinkedHashMap} because the returned
 * list must follow the entries map's iteration order and
 * {@code Map.of()} does not guarantee one.</p>
 */
class ItemBindingValidatorTest {

    private static final ResourceLocation ITEM_SWORD =
            ResourceLocation.parse("minecraft:diamond_sword");
    private static final ResourceLocation ITEM_STICK =
            ResourceLocation.parse("minecraft:stick");
    private static final ResourceLocation ITEM_APPLE =
            ResourceLocation.parse("minecraft:apple");

    @Test
    void findDuplicateItemKeysEmptyWhenUnique() {
        Map<ResourceLocation, GunItemBinding> entries = new LinkedHashMap<>();
        entries.put(ResourceLocation.parse("mypack:sword_binding"),
                new GunItemBinding(ITEM_SWORD, ResourceLocation.parse("mypack:sword_rifle")));
        entries.put(ResourceLocation.parse("mypack:bow_binding"),
                new GunItemBinding(ResourceLocation.parse("minecraft:bow"),
                        ResourceLocation.parse("mypack:bow_rifle")));
        assertTrue(ItemBindingValidator
                .findDuplicateItemKeys(entries, GunItemBinding::itemId).isEmpty());
    }

    @Test
    void findDuplicateItemKeysReturnsNonSmallestKeys() {
        Map<ResourceLocation, GunItemBinding> entries = new LinkedHashMap<>();
        entries.put(ResourceLocation.parse("mypack:a"),
                new GunItemBinding(ITEM_SWORD, ResourceLocation.parse("mypack:gun_a")));
        entries.put(ResourceLocation.parse("mypack:m"),
                new GunItemBinding(ITEM_SWORD, ResourceLocation.parse("mypack:gun_m")));
        entries.put(ResourceLocation.parse("mypack:z"),
                new GunItemBinding(ITEM_SWORD, ResourceLocation.parse("mypack:gun_z")));
        assertEquals(
                List.of(ResourceLocation.parse("mypack:m"), ResourceLocation.parse("mypack:z")),
                ItemBindingValidator.findDuplicateItemKeys(entries, GunItemBinding::itemId));
    }

    @Test
    void findDuplicateItemKeysMultipleGroups() {
        Map<ResourceLocation, GunItemBinding> entries = new LinkedHashMap<>();
        // 组 1：stick 被 mypack:a（最小）与 mypack:b 绑定
        entries.put(ResourceLocation.parse("mypack:a"),
                new GunItemBinding(ITEM_STICK, ResourceLocation.parse("mypack:gun_a")));
        entries.put(ResourceLocation.parse("mypack:b"),
                new GunItemBinding(ITEM_STICK, ResourceLocation.parse("mypack:gun_b")));
        // 组 2：apple 被 mypack:x（最小）、mypack:y、mypack:z 绑定
        entries.put(ResourceLocation.parse("mypack:x"),
                new GunItemBinding(ITEM_APPLE, ResourceLocation.parse("mypack:gun_x")));
        entries.put(ResourceLocation.parse("mypack:y"),
                new GunItemBinding(ITEM_APPLE, ResourceLocation.parse("mypack:gun_y")));
        entries.put(ResourceLocation.parse("mypack:z"),
                new GunItemBinding(ITEM_APPLE, ResourceLocation.parse("mypack:gun_z")));
        assertEquals(
                List.of(
                        ResourceLocation.parse("mypack:b"),
                        ResourceLocation.parse("mypack:y"),
                        ResourceLocation.parse("mypack:z")),
                ItemBindingValidator.findDuplicateItemKeys(entries, GunItemBinding::itemId));
    }

    @Test
    void findDuplicateItemKeysEmptyEntries() {
        assertTrue(ItemBindingValidator
                .findDuplicateItemKeys(Map.of(), GunItemBinding::itemId).isEmpty());
    }

    @Test
    void javaApiRegisteredGunIsNotReportedMissing() {
        // knownGunIds 含 mypack:java_gun（模拟 Java API 注册的枪械，
        // GunRegistry.getJavaApiRegisteredGuns 的键）与 mypack:dp_gun
        // （datapack guns 注册表键）；绑定指向 Java API 枪械时不得误报
        Map<ResourceLocation, GunItemBinding> entries = new LinkedHashMap<>();
        entries.put(ResourceLocation.parse("mypack:java_binding"),
                new GunItemBinding(ITEM_SWORD, ResourceLocation.parse("mypack:java_gun")));
        Set<ResourceLocation> knownGunIds = Set.of(
                ResourceLocation.parse("mypack:java_gun"),
                ResourceLocation.parse("mypack:dp_gun"));
        assertTrue(ItemBindingValidator
                .findBindingsWithUnknownGunId(entries, knownGunIds).isEmpty());
    }

    @Test
    void unknownGunIdIsReported() {
        Map<ResourceLocation, GunItemBinding> entries = new LinkedHashMap<>();
        entries.put(ResourceLocation.parse("mypack:ghost_binding"),
                new GunItemBinding(ITEM_SWORD, ResourceLocation.parse("mypack:ghost")));
        entries.put(ResourceLocation.parse("mypack:ok_binding"),
                new GunItemBinding(ITEM_STICK, ResourceLocation.parse("mypack:dp_gun")));
        Set<ResourceLocation> knownGunIds = Set.of(ResourceLocation.parse("mypack:dp_gun"));
        assertEquals(
                List.of(ResourceLocation.parse("mypack:ghost_binding")),
                ItemBindingValidator.findBindingsWithUnknownGunId(entries, knownGunIds));
    }

    @Test
    void emptyEntriesReturnsEmpty() {
        assertTrue(ItemBindingValidator
                .findBindingsWithUnknownGunId(Map.of(), Set.of()).isEmpty());
    }

    @Test
    void knownSetContainsAllReturnsEmpty() {
        Map<ResourceLocation, GunItemBinding> entries = new LinkedHashMap<>();
        entries.put(ResourceLocation.parse("mypack:a"),
                new GunItemBinding(ITEM_SWORD, ResourceLocation.parse("mypack:gun_a")));
        entries.put(ResourceLocation.parse("mypack:b"),
                new GunItemBinding(ITEM_STICK, ResourceLocation.parse("mypack:gun_b")));
        Set<ResourceLocation> knownGunIds = Set.of(
                ResourceLocation.parse("mypack:gun_a"),
                ResourceLocation.parse("mypack:gun_b"));
        assertTrue(ItemBindingValidator
                .findBindingsWithUnknownGunId(entries, knownGunIds).isEmpty());
    }
}
