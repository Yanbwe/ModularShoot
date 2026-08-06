package org.yanbwe.modularshoot.plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PluginExtraValueService} aggregation semantics.
 *
 * <p>Only the pure parts are exercised headless: {@link #aggregateDefinitions}
 * over hand-built definitions, and the degradation path of
 * {@link #aggregate(List, RegistryAccess)} against {@link RegistryAccess#EMPTY}
 * (every instance resolves to a missing definition and is filtered out, so the
 * result is empty — the same graceful-degradation contract as the attribute
 * pipeline). The {@link ItemStack}-backed overloads are thin delegations over
 * these paths and require a bootstrapped {@code modularshoot:plugins} registry,
 * which is only available inside a running game; they are covered by
 * integration/manual verification.</p>
 */
class PluginExtraValueServiceTest {

    /** Builds a {@link PluginDefinition} carrying only the given extra values. */
    private static PluginDefinition def(Map<ResourceLocation, Double> extraValues) {
        return new PluginDefinition(
                List.of(),
                0,
                ResourceLocation.parse("m:icon"),
                TextureScaleMode.AUTO,
                List.of(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                extraValues,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Optional.empty());
    }

    /** Builds an installed plugin instance for the given plugin id. */
    private static PluginInstance inst(String pluginId) {
        return new PluginInstance(
                ResourceLocation.parse(pluginId), UUID.randomUUID(), ResourceLocation.parse("m:type"), false);
    }

    private static final ResourceLocation RARITY = ResourceLocation.parse("raritycore:rarity");
    private static final ResourceLocation DEMO = ResourceLocation.parse("examplemod:value");

    @Test
    void sumsAcrossPluginsByKey() {
        Map<ResourceLocation, Double> sums = PluginExtraValueService.aggregateDefinitions(List.of(
                def(Map.of(RARITY, 5.0)),
                def(Map.of(RARITY, 3.0, DEMO, -2.5)),
                def(Map.of())));
        assertEquals(2, sums.size(), "both keys accumulate");
        assertEquals(8.0, sums.get(RARITY), 1.0E-9, "same key sums across plugins");
        assertEquals(-2.5, sums.get(DEMO), 1.0E-9, "independent keys accumulate separately");
    }

    @Test
    void emptyDefinitionsYieldEmptyMap() {
        assertTrue(PluginExtraValueService.aggregateDefinitions(List.of()).isEmpty(),
                "no definitions produce no sums");
    }

    @Test
    void missingKeyIsAbsentFromSumsMap() {
        // Keys never declared by any plugin must not appear in the sums map
        // (callers fall back to their own default, e.g. 0).
        Map<ResourceLocation, Double> sums = PluginExtraValueService.aggregateDefinitions(List.of(
                def(Map.of(DEMO, 1.0))));
        assertFalse(sums.containsKey(RARITY), "undeclared key stays absent");
    }

    @Test
    void degradedInstancesWithEmptyRegistryYieldEmptySums() {
        // With an empty registry view every instance is degraded; the
        // aggregation must filter them out (same contract as the attribute
        // pipeline) and never throw.
        Map<ResourceLocation, Double> sums = PluginExtraValueService.aggregate(
                List.of(inst("examplemod:prismatic"), inst("examplemod:boost")),
                RegistryAccess.EMPTY);
        assertTrue(sums.isEmpty(), "degraded instances contribute no sums");
    }
}
