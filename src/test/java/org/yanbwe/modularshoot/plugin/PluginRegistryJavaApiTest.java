package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the plugin Java registration channels (审查 E1 — 与枪械侧
 * {@code GunRegistry.registerGun}/{@code registerGunDefinitionProvider} 对称):
 * static {@link PluginRegistry#registerPlugin} definitions win over datapack
 * entries with the same id, and dynamic providers resolve ids that no
 * registry knows.
 */
class PluginRegistryJavaApiTest {

    private static final ResourceLocation CLASH_ID =
            ResourceLocation.parse("e1test:clash_plugin");
    private static final ResourceLocation JAVA_ONLY_ID =
            ResourceLocation.parse("e1test:java_only_plugin");
    private static final ResourceLocation DYNAMIC_ID =
            ResourceLocation.parse("e1test:dynamic_plugin");

    /** A plugin definition carrying the given priority as a marker. */
    private static PluginDefinition pluginDef(int priorityMarker) {
        return new PluginDefinition(
                List.of(),
                priorityMarker,
                ResourceLocation.parse("m:icon"),
                TextureScaleMode.AUTO,
                List.of(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** A datapack registry view containing the given plugin entries. */
    private static RegistryAccess access(Map<ResourceLocation, PluginDefinition> plugins) {
        MappedRegistry<PluginDefinition> registry = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        plugins.forEach((id, def) -> Registry.register(registry, id, def));
        return new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
    }

    @Test
    void javaApiDefinitionWinsOverDatapackEntry() {
        PluginDefinition javaDef = pluginDef(111);
        PluginRegistry.registerPlugin(CLASH_ID, javaDef);

        RegistryAccess access = access(Map.of(CLASH_ID, pluginDef(222)));

        assertEquals(javaDef, PluginRegistry.getPlugin(access, CLASH_ID).orElse(null),
                "Java-API-registered plugins must take priority over datapack entries (审查 E1)");
    }

    @Test
    void javaApiDefinitionResolvesWithoutDatapackEntryAndIsEnumerated() {
        PluginDefinition javaDef = pluginDef(333);
        PluginRegistry.registerPlugin(JAVA_ONLY_ID, javaDef);

        RegistryAccess empty = access(Map.of());

        assertEquals(javaDef, PluginRegistry.getPlugin(empty, JAVA_ONLY_ID).orElse(null),
                "Java-API plugins resolve even when the datapack registry lacks the id");
        assertTrue(PluginRegistry.getAllPluginIds(empty).contains(JAVA_ONLY_ID),
                "getAllPluginIds must include Java-API ids");
    }

    @Test
    void providerResolvesIdsBetweenJavaApiAndDatapack() {
        PluginDefinition dynamicDef = pluginDef(444);
        PluginRegistry.registerPluginDefinitionProvider(pluginId ->
                DYNAMIC_ID.equals(pluginId) ? Optional.of(dynamicDef) : Optional.empty());

        RegistryAccess empty = access(Map.of());

        assertEquals(dynamicDef, PluginRegistry.getPlugin(empty, DYNAMIC_ID).orElse(null),
                "providers resolve query-time ids (审查 E1)");
        assertTrue(PluginRegistry.getAllPluginIds(empty).contains(DYNAMIC_ID) == false,
                "provider-defined ids are query-time only and not enumerated (同枪械侧语义)");
    }

    @Test
    void throwingProviderDegradesToEmpty() {
        ResourceLocation faulty = ResourceLocation.parse("e1test:faulty_plugin");
        PluginRegistry.registerPluginDefinitionProvider(pluginId -> {
            if (faulty.equals(pluginId)) {
                throw new IllegalStateException("deliberate test failure");
            }
            return Optional.empty();
        });

        assertTrue(PluginRegistry.getPlugin(access(Map.of()), faulty).isEmpty(),
                "a faulty provider is swallowed and degrades to empty (同枪械侧降级哲学)");
    }
}
