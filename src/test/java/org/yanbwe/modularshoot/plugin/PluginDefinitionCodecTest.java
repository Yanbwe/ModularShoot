package org.yanbwe.modularshoot.plugin;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@link PluginDefinition} {@code gun_outline} field:
 * an optional whole-gun outline stroked around the final composited texture
 * silhouette, defaulting to empty when omitted. Also covers the
 * {@code extra_values} extension field: a namespaced numeric field registry
 * defaulting to an empty map when omitted. Also covers the
 * {@code adds_variants} field: a namespaced "variant id → base weight" map
 * appended to the gun's per-shot variant pool (设计规格 §6.2 来源表：插件向
 * 枪的池子追加变体 id + base_weight), defaulting to an empty map when omitted.
 * Also covers the {@code visual_priority} field: an optional visual
 * base-election priority that falls back to {@code priority} when absent.
 */
class PluginDefinitionCodecTest {

    private static PluginDefinition parse(String json) {
        return PluginDefinition.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow(msg -> new AssertionError("Decode failed: " + msg))
                .getFirst();
    }

    @Test
    void defaultsNoGunOutline() {
        PluginDefinition definition = parse("{\"item_icon\": \"m:icon\"}");
        assertTrue(definition.gunOutline().isEmpty(), "gun_outline should default to empty");
    }

    @Test
    void parsesGunOutline() {
        PluginDefinition definition = parse(
                "{\"item_icon\": \"m:icon\", \"gun_outline\": {\"color\": [0.0, 1.0, 0.0], \"alpha\": 0.5, \"width\": 3}}");
        assertTrue(definition.gunOutline().isPresent(), "gun_outline should be present");
        OutlineSpec outline = definition.gunOutline().orElseThrow();
        assertEquals(0.0F, outline.color().x(), 1.0E-5F);
        assertEquals(1.0F, outline.color().y(), 1.0E-5F);
        assertEquals(0.0F, outline.color().z(), 1.0E-5F);
        assertEquals(0.5F, outline.alpha(), 1.0E-5F);
        assertEquals(3, outline.width());
    }

    @Test
    void extraValuesAbsentDefaultsToEmptyMap() {
        PluginDefinition definition = parse("{\"item_icon\": \"m:icon\"}");
        assertTrue(definition.extraValues().isEmpty(),
                "extra_values should default to an empty map");
    }

    @Test
    void extraValuesParsesNamespacedKeys() {
        PluginDefinition definition = parse(
                "{\"item_icon\": \"m:icon\", \"extra_values\": {\"raritycore:rarity\": 5.0, \"examplemod:value\": -2.5}}");
        assertEquals(2, definition.extraValues().size(), "both keys should parse");
        assertEquals(5.0, definition.extraValues().get(ResourceLocation.parse("raritycore:rarity")), 1.0E-9);
        assertEquals(-2.5, definition.extraValues().get(ResourceLocation.parse("examplemod:value")), 1.0E-9);
    }

    @Test
    void addsVariantsAbsentDefaultsToEmptyMap() {
        PluginDefinition definition = parse("{\"item_icon\": \"m:icon\"}");
        assertTrue(definition.addsVariants().isEmpty(),
                "adds_variants should default to an empty map");
    }

    @Test
    void addsVariantsParsesNamespacedKeys() {
        PluginDefinition definition = parse(
                "{\"item_icon\": \"m:icon\", \"adds_variants\": {\"examplemod:fire\": 3.0, \"modularshoot:big\": 1.5}}");
        assertEquals(2, definition.addsVariants().size(), "both keys should parse");
        assertEquals(3.0, definition.addsVariants().get(ResourceLocation.parse("examplemod:fire")), 1.0E-9,
                "first key keeps its declared weight");
        assertEquals(1.5, definition.addsVariants().get(ResourceLocation.parse("modularshoot:big")), 1.0E-9,
                "second key keeps its declared weight");
    }

    @Test
    void addsSlotsAbsentDefaultsToEmptyMap() {
        PluginDefinition definition = parse("{\"item_icon\": \"m:icon\"}");
        assertTrue(definition.addsSlots().isEmpty(),
                "adds_slots should default to an empty map");
    }

    @Test
    void addsSlotsParsesBareKeyWithModularshootNamespace() {
        PluginDefinition definition = parse(
                "{\"item_icon\": \"m:icon\", \"adds_slots\": {\"combat\": 1, \"modularshoot:accessory\": -1}}");
        assertEquals(2, definition.addsSlots().size(), "both keys should parse");
        assertEquals(1, definition.addsSlots().get(ResourceLocation.parse("modularshoot:combat")),
                "bare key defaults to the modularshoot namespace");
        assertEquals(-1, definition.addsSlots().get(ResourceLocation.parse("modularshoot:accessory")),
                "negative values are allowed");
    }

    @Test
    void visualPriorityAbsentDefaultsToEmpty() {
        PluginDefinition definition = parse("{\"item_icon\": \"m:icon\"}");
        assertTrue(definition.visualPriority().isEmpty(),
                "visual_priority should default to empty when omitted");
    }

    @Test
    void visualPriorityParsesExplicitValue() {
        PluginDefinition definition = parse(
                "{\"item_icon\": \"m:icon\", \"visual_priority\": 120}");
        assertEquals(Optional.of(120), definition.visualPriority(),
                "explicit visual_priority should be kept as-is");
    }

    @Test
    void visualPriorityOrFallbackUsesExplicitValueWhenPresent() {
        PluginDefinition definition = parse(
                "{\"item_icon\": \"m:icon\", \"priority\": 5, \"visual_priority\": 120}");
        assertEquals(120, definition.visualPriorityOrFallback(),
                "explicit visual_priority wins over priority");
    }

    @Test
    void visualPriorityOrFallbackFallsBackToPriorityWhenAbsent() {
        PluginDefinition definition = parse(
                "{\"item_icon\": \"m:icon\", \"priority\": 5}");
        assertEquals(5, definition.visualPriorityOrFallback(),
                "absent visual_priority falls back to priority");
    }

    @Test
    void visualPriorityOrFallbackDefaultsToZeroWhenBothAbsent() {
        PluginDefinition definition = parse("{\"item_icon\": \"m:icon\"}");
        assertEquals(0, definition.visualPriorityOrFallback(),
                "both absent yields the priority default of 0");
    }
}
