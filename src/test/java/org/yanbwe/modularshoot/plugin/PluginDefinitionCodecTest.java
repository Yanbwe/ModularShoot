package org.yanbwe.modularshoot.plugin;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@link PluginDefinition} {@code gun_outline} field:
 * an optional whole-gun outline stroked around the final composited texture
 * silhouette, defaulting to empty when omitted. Also covers the
 * {@code extra_values} extension field: a namespaced numeric field registry
 * defaulting to an empty map when omitted.
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
}
