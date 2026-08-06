package org.yanbwe.modularshoot.registry.variant;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.ScaleModifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codec tests for {@link VariantDefinition} (机制四 §6.1, 第 7 张 DataPackRegistry
 * {@code modularshoot:variants}). Guards the full-field JSON shape, the
 * defaults for absent fields (empty maps / empty optionals / {@code base_weight}
 * defaulting to {@code 0.0}) and tolerance of unknown keys (per-element
 * {@code optionalFieldOf} semantics of {@code RecordCodecBuilder}).
 *
 * <p>JSON keys follow the design spec §6.1:
 * {@code base_weight}, {@code traits}, {@code stats}, {@code damage_type},
 * {@code bullet_style_override}. Map keys ({@code traits}/{@code stats}) must
 * be fully namespaced {@link ResourceLocation}s.</p>
 */
class VariantDefinitionCodecTest {

    private static VariantDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return VariantDefinition.CODEC.decode(JsonOps.INSTANCE, element)
                .getOrThrow(m -> new AssertionError("Decode failed: " + m))
                .getFirst();
    }

    @Test
    void fullVariantRoundTrips() {
        VariantDefinition variant = decode("""
                {
                  "base_weight": 3.0,
                  "traits": { "modularshoot:visual_fire": true, "modularshoot:heavy": false },
                  "stats": { "modularshoot:hit_damage": 15.0, "examplemod:extra": 2.5 },
                  "damage_type": "minecraft:in_fire",
                  "bullet_style_override": {
                    "base": {
                      "render_mode": "billboard",
                      "texture": "modularshoot:textures/bullet/fireball.png"
                    },
                    "modifiers": [ { "type": "scale", "value": 1.2 } ]
                  }
                }
                """);

        assertEquals(3.0, variant.baseWeight(), 1e-6);

        assertEquals(2, variant.traits().size());
        assertEquals(Boolean.TRUE, variant.traits().get(ResourceLocation.parse("modularshoot:visual_fire")));
        assertEquals(Boolean.FALSE, variant.traits().get(ResourceLocation.parse("modularshoot:heavy")));

        assertEquals(2, variant.stats().size());
        assertEquals(15.0, variant.stats().get(ResourceLocation.parse("modularshoot:hit_damage")), 1e-6);
        assertEquals(2.5, variant.stats().get(ResourceLocation.parse("examplemod:extra")), 1e-6);

        assertEquals(Optional.of(ResourceLocation.parse("minecraft:in_fire")), variant.damageType());

        Optional<BulletStyle> override = variant.bulletStyleOverride();
        assertTrue(override.isPresent());
        BulletStyle style = override.get();
        assertTrue(style.base().isPresent());
        assertEquals(BulletStyle.RenderMode.BILLBOARD, style.base().get().renderMode());
        assertEquals(Optional.of(ResourceLocation.parse("modularshoot:textures/bullet/fireball.png")),
                style.base().get().texture());
        assertEquals(1, style.modifiers().size());
        assertInstanceOf(ScaleModifier.class, style.modifiers().get(0));
        assertEquals(1.2f, ((ScaleModifier) style.modifiers().get(0)).value(), 1e-6);
    }

    @Test
    void absentFieldsUseDefaults() {
        VariantDefinition withHint = decode("""
                { "base_weight": 2.0 }
                """);
        assertEquals(2.0, withHint.baseWeight(), 1e-6);
        assertTrue(withHint.traits().isEmpty());
        assertTrue(withHint.stats().isEmpty());
        assertEquals(Optional.empty(), withHint.damageType());
        assertEquals(Optional.empty(), withHint.bulletStyleOverride());

        // base_weight itself is optional and defaults to 0.0 (设计决策 1).
        VariantDefinition empty = decode("""
                { }
                """);
        assertEquals(0.0, empty.baseWeight(), 1e-6);
        assertTrue(empty.traits().isEmpty());
        assertTrue(empty.stats().isEmpty());
    }

    @Test
    void unknownFieldsAreIgnored() {
        VariantDefinition variant = decode("""
                {
                  "base_weight": 1.0,
                  "some_unknown_key": "whatever",
                  "another_unknown": { "nested": [1, 2, 3] }
                }
                """);
        assertEquals(1.0, variant.baseWeight(), 1e-6);
        assertTrue(variant.traits().isEmpty());
        assertTrue(variant.stats().isEmpty());
        assertEquals(Optional.empty(), variant.damageType());
        assertEquals(Optional.empty(), variant.bulletStyleOverride());
        assertEquals(Map.of(), variant.traits());
    }

    @Test
    void newKeyBaseWeightParsesAndLegacyKeyIsIgnored() {
        // L4a: base_weight 是权威权重（非提示）——0.5 正常解析。
        VariantDefinition withWeight = decode("""
                { "base_weight": 0.5 }
                """);
        assertEquals(0.5, withWeight.baseWeight(), 1e-6);

        // 旧键 weight_hint 已废弃：按 optionalFieldOf 缺省语义解析为 0.0。
        VariantDefinition legacy = decode("""
                { "weight_hint": 2.0 }
                """);
        assertEquals(0.0, legacy.baseWeight(), 1e-6, "旧键 weight_hint 不再被识别，回落到默认 0.0");
    }
}
