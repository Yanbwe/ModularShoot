package org.yanbwe.modularshoot.registry.gun;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for {@link GunDefinition} map-key namespacing (任务 M6):
 * stats/traits/slots/variants bare keys (no colon) resolve to the
 * {@code modularshoot} namespace, mirroring the plugin modifier
 * {@code attribute} bare-key rule in
 * {@code AttributeModifierService.resolveAttributeHolder}; fully namespaced
 * keys are kept as-is and the encode side is untouched (round-trip stable).
 *
 * <p>Inline-JSON style mirroring {@code GunDefinitionVariantsCodecTest} — no
 * datapack file dependency.</p>
 */
class GunDefinitionKeyCodecTest {

    private static GunDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return GunDefinition.CODEC.parse(JsonOps.INSTANCE, element)
                .getOrThrow(m -> new AssertionError("Decode failed: " + m));
    }

    @Test
    void statsBareKeyResolvesToModularShootNamespace() {
        GunDefinition gun = decode("""
                {
                  "texture": "m:textures/gun/test.png",
                  "stats": { "hit_damage": 5.0 }
                }
                """);
        Map<ResourceLocation, Double> stats = gun.stats();
        assertEquals(1, stats.size(), "bare key should parse");
        ResourceLocation resolved = ResourceLocation.parse("modularshoot:hit_damage");
        assertTrue(stats.containsKey(resolved),
                "bare key should resolve to the modularshoot namespace");
        assertEquals(5.0, stats.get(resolved), 1.0E-9,
                "the stat value should be preserved");
    }

    @Test
    void fullyNamespacedKeyIsKeptAsIs() {
        GunDefinition gun = decode("""
                {
                  "texture": "m:textures/gun/test.png",
                  "stats": { "examplemod:foo": 2.0 }
                }
                """);
        assertTrue(gun.stats().containsKey(ResourceLocation.parse("examplemod:foo")),
                "fully namespaced key should keep its declared namespace");
        assertFalse(gun.stats().containsKey(ResourceLocation.parse("modularshoot:foo")),
                "a full key must not be remapped to the modularshoot namespace");
    }

    @Test
    void traitsSlotsVariantsBareKeysResolveToModularShootNamespace() {
        GunDefinition gun = decode("""
                {
                  "texture": "m:textures/gun/test.png",
                  "traits": { "explosive": true },
                  "slots": { "barrel": 1 },
                  "variants": { "fire": 3.0 }
                }
                """);
        assertTrue(gun.traits().containsKey(ResourceLocation.parse("modularshoot:explosive")),
                "traits bare key should resolve to the modularshoot namespace");
        assertTrue(gun.slots().containsKey(ResourceLocation.parse("modularshoot:barrel")),
                "slots bare key should resolve to the modularshoot namespace");
        assertTrue(gun.variants().containsKey(ResourceLocation.parse("modularshoot:fire")),
                "variants bare key should resolve to the modularshoot namespace");
    }

    @Test
    void encodeKeepsResolvedKeyUntouched() {
        // encode 侧为恒等：完整命名空间键 decode 不动，round-trip 稳定。
        JsonElement encoded = GunDefinition.CODEC.encodeStart(JsonOps.INSTANCE, decode("""
                {
                  "texture": "m:textures/gun/test.png",
                  "stats": { "examplemod:foo": 2.0 }
                }
                """))
                .getOrThrow(m -> new AssertionError("Encode failed: " + m));
        assertTrue(encoded.toString().contains("\"examplemod:foo\""),
                "encode should keep the full key untouched");
    }
}
