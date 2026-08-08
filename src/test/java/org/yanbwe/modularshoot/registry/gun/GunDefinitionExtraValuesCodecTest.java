package org.yanbwe.modularshoot.registry.gun;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@link GunDefinition} {@code extra_values} field: an
 * optional namespaced numeric extension field (keys must be fully
 * namespaced, same contract as plugin {@code extra_values}), defaulting to
 * an empty map when omitted.
 *
 * <p>Inline-JSON style mirroring {@code GunDefinitionVariantsCodecTest} — no
 * datapack file dependency.</p>
 */
class GunDefinitionExtraValuesCodecTest {

    private static GunDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return GunDefinition.CODEC.parse(JsonOps.INSTANCE, element)
                .getOrThrow(m -> new AssertionError("Decode failed: " + m));
    }

    @Test
    void extraValuesParsesNamespacedKeys() {
        GunDefinition gun = decode("""
                {
                  "texture": "m:textures/gun/test.png",
                  "extra_values": { "raritycore:rarity": 5.0, "examplemod:value": -2.5 }
                }
                """);
        Map<ResourceLocation, Double> extraValues = gun.extraValues();
        assertEquals(2, extraValues.size(), "both keys should parse");
        assertEquals(5.0, extraValues.get(ResourceLocation.parse("raritycore:rarity")), 1.0E-9,
                "first key keeps its declared value");
        assertEquals(-2.5, extraValues.get(ResourceLocation.parse("examplemod:value")), 1.0E-9,
                "second key keeps its declared value");
    }

    @Test
    void extraValuesAbsentDefaultsToEmptyMap() {
        GunDefinition gun = decode("""
                { "texture": "m:textures/gun/test.png" }
                """);
        assertTrue(gun.extraValues().isEmpty(),
                "extra_values should default to an empty map when omitted");
    }
}
