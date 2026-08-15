package org.yanbwe.modularshoot.datapack;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.binding.GunItemBinding;
import org.yanbwe.modularshoot.registry.binding.PluginItemBinding;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TDD tests for the reload-time sharing of registry entries/keysets
 * (阶段 5 / 任务 5.1).
 *
 * <p>Before the optimisation, one {@code /reload} re-materialised the same
 * registry multiple times: once for the summary loader, again inside
 * {@link CrossReferenceValidator}, and a third time for the registration
 * conflict check. These tests pin two behaviours:</p>
 * <ol>
 *   <li><b>Collect once per registry per reload.</b> {@link ReloadSharedEntries}
 *       memoises {@code entrySet()} (and derives {@code keySet()} from it), so
 *       every consumer of one reload observes a single traversal of each
 *       registry. The end-to-end case asserts this across the whole reload
 *       flow.</li>
 *   <li><b>No per-plugin tag flattening.</b>
 *       {@link CrossReferenceValidator#validatePlugins} flattens the full
 *       plugin-type tag set once and reuses it for every plugin instead of
 *       rebuilding the union per plugin.</li>
 * </ol>
 *
 * <p>Registry collection is observed through {@link CountingRegistry}, a
 * thin {@link MappedRegistry} subclass that counts {@code entrySet()}/
 * {@code keySet()} invocations, with no mocking.</p>
 */
class ReloadSharedEntriesTest {

    static {
        // FML shim + game-version shim, then full vanilla registry bootstrap
        // (identical to the other datapack tests' probe-verified order).
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    // ──────────────── 1. Shared snapshot collects each registry once ────────────────

    /**
     * The shared snapshot must collect a registry's entries exactly once even
     * when both the {@code entries} and derived {@code keys} views are queried
     * repeatedly across one reload.
     */
    @Test
    void sharedSnapshotCollectsEachRegistryOnce() {
        ResourceLocation gunId = ResourceLocation.parse("mypack:rifle");
        CountingRegistry<GunDefinition> guns = new CountingRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, gunId, gunStub());

        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(guns));
        ReloadSharedEntries shared = new ReloadSharedEntries(access);

        // Simulate the summary, cross-reference and conflict-check phases all
        // asking for both the entries and the keys of the same registry.
        shared.entries(ModularShootRegistries.GUNS_KEY);
        shared.keys(ModularShootRegistries.GUNS_KEY);
        shared.entries(ModularShootRegistries.GUNS_KEY);
        shared.keys(ModularShootRegistries.GUNS_KEY);

        // Exactly one traversal; keySet is derived from the memoised entries.
        assertEquals(1, guns.entrySetCalls(), "registry must be collected exactly once per reload");
        assertEquals(0, guns.keySetCalls(), "keys must be derived from the memoised entries, not re-traversed");
    }

    // ──────────────── 2. Cross-reference tag matching flattens once ────────────────

    /**
     * {@code validatePlugins} must flatten the plugin-type tag union once and
     * reuse it across every plugin; the plugin_types registry is collected a
     * single time even when many plugins are validated.
     */
    @Test
    void pluginTagMatchingFlattensTypeTagsOnce() {
        CountingRegistry<PluginTypeDefinition> types = new CountingRegistry<>(
                ModularShootRegistries.PLUGIN_TYPES_KEY, Lifecycle.stable());
        Registry.register(types, ResourceLocation.parse("modularshoot:barrel"),
                new PluginTypeDefinition(
                        List.of(ResourceLocation.parse("modularshoot:barrel")), 0, null, null));
        Registry.register(types, ResourceLocation.parse("modularshoot:scope"),
                new PluginTypeDefinition(
                        List.of(ResourceLocation.parse("modularshoot:scope")), 0, null, null));

        // Three plugins that reference the plugin_types table through
        // CrossReferenceValidator (tags, adds_slots).
        Map<ResourceLocation, PluginDefinition> plugins = Map.of(
                ResourceLocation.parse("mypack:p1"), pluginWithSlotsAndTags("modularshoot:barrel"),
                ResourceLocation.parse("mypack:p2"), pluginWithSlotsAndTags("modularshoot:scope"),
                ResourceLocation.parse("mypack:p3"), pluginWithSlotsAndTags("mypack:typo_tag"));

        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(types));
        ReloadSharedEntries shared = new ReloadSharedEntries(access);

        // Drive the validator twice; the flattened tag union must be recomputed
        // exactly once across both runs, not once per run.
        CrossReferenceValidator.validatePlugins(shared, plugins);
        CrossReferenceValidator.validatePlugins(shared, plugins);

        assertEquals(1, types.entrySetCalls(),
                "plugin type tags must be flattened once across all plugins, not per plugin");
        assertEquals(1, shared.pluginTypeTagsFlattenCount(),
                "flattened plugin type tags must be computed exactly once, even across repeated validation runs");
    }

    // ──────────────── 3. End-to-end: one reload collects each registry once ────────────────

    /**
     * Driving the whole reload flow (summary + cross-reference + conflict
     * check) must collect every framework registry exactly once.
     */
    @Test
    void fullReloadCollectsEachRegistryOnce() {
        List<CountingRegistry<?>> registries = new ArrayList<>();
        CountingRegistry<GunDefinition> guns = new CountingRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, ResourceLocation.parse("mypack:rifle"), gunStub());
        CountingRegistry<PluginDefinition> plugins = new CountingRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(plugins, ResourceLocation.parse("mypack:p1"),
                pluginWithSlotsAndTags("modularshoot:barrel"));
        registries.add(guns);
        registries.add(plugins);
        registries.add(new CountingRegistry<>(ModularShootRegistries.PLUGIN_TYPES_KEY, Lifecycle.stable()));
        registries.add(new CountingRegistry<>(ModularShootRegistries.TRAITS_KEY, Lifecycle.stable()));
        registries.add(new CountingRegistry<>(ModularShootRegistries.STATES_KEY, Lifecycle.stable()));
        registries.add(new CountingRegistry<>(ModularShootRegistries.ATTRIBUTE_META_KEY, Lifecycle.stable()));
        registries.add(new CountingRegistry<>(ModularShootRegistries.VARIANTS_KEY, Lifecycle.stable()));
        registries.add(new CountingRegistry<>(ModularShootRegistries.SHOOTERS_KEY, Lifecycle.stable()));
        CountingRegistry<GunItemBinding> gunItems = new CountingRegistry<>(
                ModularShootRegistries.GUN_ITEMS_KEY, Lifecycle.stable());
        Registry.register(gunItems, ResourceLocation.parse("mypack:sword_binding"),
                new GunItemBinding(ResourceLocation.parse("minecraft:diamond_sword"),
                        ResourceLocation.parse("mypack:rifle")));
        CountingRegistry<PluginItemBinding> pluginItems = new CountingRegistry<>(
                ModularShootRegistries.PLUGIN_ITEMS_KEY, Lifecycle.stable());
        Registry.register(pluginItems, ResourceLocation.parse("mypack:stick_binding"),
                new PluginItemBinding(ResourceLocation.parse("minecraft:stick"),
                        ResourceLocation.parse("mypack:p1")));
        registries.add(gunItems);
        registries.add(pluginItems);

        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                registries.stream().map(r -> (Registry<?>) r).toList());

        DatapackReloadListener.handleReloadComplete(access);

        for (CountingRegistry<?> registry : registries) {
            assertEquals(1, registry.entrySetCalls(),
                    "registry " + registry.key().location() + " must be collected exactly once per reload");
            assertEquals(0, registry.keySetCalls(),
                    "registry " + registry.key().location() + " keys must be derived from the memoised entries, not re-traversed");
        }
    }

    // ──────────────── Test data helpers ────────────────

    private static PluginDefinition pluginWithSlotsAndTags(String tag) {
        return new PluginDefinition(
                List.of(ResourceLocation.parse(tag)),
                0,
                ResourceLocation.parse("modularshoot:textures/plugin/icon.png"),
                TextureScaleMode.AUTO,
                List.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                Map.of(ResourceLocation.parse("modularshoot:barrel"), 1),
                Map.of(),
                null);
    }

    private static GunDefinition gunStub() {
        // Minimal valid gun definition (no cross-table references).
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("modularshoot:textures/gun/base.png"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(), Map.of(), Map.of(), Map.of(),
                Optional.empty(),
                Map.of(), Map.of(),
                Optional.empty());
    }

    /**
     * A {@link MappedRegistry} that counts how many times {@code entrySet()}
     * and {@code keySet()} are invoked, so registry traversal is observable
     * without mocking.
     *
     * @param <T> the registry value type
     */
    private static final class CountingRegistry<T> extends MappedRegistry<T> {
        private int entrySetCalls;
        private int keySetCalls;

        CountingRegistry(ResourceKey<? extends Registry<T>> key, Lifecycle lifecycle) {
            super(key, lifecycle);
        }

        @Override
        public Set<ResourceLocation> keySet() {
            keySetCalls++;
            return super.keySet();
        }

        @Override
        public Set<Map.Entry<ResourceKey<T>, T>> entrySet() {
            entrySetCalls++;
            return super.entrySet();
        }

        int entrySetCalls() {
            return entrySetCalls;
        }

        int keySetCalls() {
            return keySetCalls;
        }
    }
}
