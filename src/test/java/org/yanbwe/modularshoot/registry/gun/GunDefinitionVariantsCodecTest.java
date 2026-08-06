package org.yanbwe.modularshoot.registry.gun;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for the {@link GunDefinition} {@code variants} field: an
 * optional "variant id → base weight" map feeding the per-shot variant pool
 * (设计规格 §6.2 来源表：枪械声明变体 id + base_weight), defaulting to an
 * empty map when omitted.
 *
 * <p>Inline-JSON style mirroring {@code PluginDefinitionCodecTest} — no
 * datapack file dependency.</p>
 */
class GunDefinitionVariantsCodecTest {

    private static GunDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return GunDefinition.CODEC.parse(JsonOps.INSTANCE, element)
                .getOrThrow(m -> new AssertionError("Decode failed: " + m));
    }

    @Test
    void variantsParsesNamespacedKeys() {
        GunDefinition gun = decode("""
                {
                  "texture": "m:textures/gun/test.png",
                  "variants": { "examplemod:fire": 3.0, "modularshoot:big": 1.5 }
                }
                """);
        Map<ResourceLocation, Double> variants = gun.variants();
        assertEquals(2, variants.size(), "both keys should parse");
        assertEquals(3.0, variants.get(ResourceLocation.parse("examplemod:fire")), 1.0E-9,
                "first key keeps its declared weight");
        assertEquals(1.5, variants.get(ResourceLocation.parse("modularshoot:big")), 1.0E-9,
                "second key keeps its declared weight");
    }

    @Test
    void variantsAbsentDefaultsToEmptyMap() {
        GunDefinition gun = decode("""
                { "texture": "m:textures/gun/test.png" }
                """);
        assertTrue(gun.variants().isEmpty(),
                "variants should default to an empty map when omitted");
    }
}
