package org.yanbwe.modularshoot.registry.gun;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GunSounds}: sound-slot lookup and {@code sound_range}
 * (audible radius) reading on a gun definition. The range tests decode the
 * {@link GunDefinition} codec from inline JSON, mirroring
 * {@code GunDefinitionVariantsCodecTest}.
 */
class GunSoundsTest {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("modularshoot", "textures/gun/test.png");
    private static final ResourceLocation HIT_SOUND =
            ResourceLocation.fromNamespaceAndPath("modularshoot", "hit");

    private static GunDefinition definitionWithSounds(Map<String, ResourceLocation> sounds) {
        return new GunDefinition(
                Optional.empty(),
                TEXTURE,
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                Map.of(),
                sounds,
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    private static GunDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return GunDefinition.CODEC.parse(JsonOps.INSTANCE, element)
                .getOrThrow(m -> new AssertionError("Decode failed: " + m));
    }

    @Test
    void getReturnsSoundIdForConfiguredSlot() {
        GunDefinition definition = definitionWithSounds(
                Map.of("hit_entity", HIT_SOUND));
        assertEquals(Optional.of(HIT_SOUND), GunSounds.get(definition, "hit_entity"));
    }

    @Test
    void getReturnsEmptyForMissingSlot() {
        GunDefinition definition = definitionWithSounds(
                Map.of("hit_entity", HIT_SOUND));
        assertEquals(Optional.empty(), GunSounds.get(definition, "missing"));
    }

    @Test
    void getReturnsEmptyWhenSoundsMapIsEmpty() {
        GunDefinition definition = definitionWithSounds(Map.of());
        assertEquals(Optional.empty(), GunSounds.get(definition, "hit_block"));
    }

    @Test
    void soundRangeParsesWhenDeclared() {
        GunDefinition gun = decode("""
                {
                  "texture": "modularshoot:textures/gun/test.png",
                  "sound_range": 48
                }
                """);
        assertEquals(Optional.of(48.0F), gun.soundRange(),
                "sound_range should be parsed as the audible radius");
        assertEquals(Optional.of(48.0F), GunSounds.getRange(gun),
                "getRange should expose the declared sound_range");
    }

    @Test
    void soundRangeAbsentDefaultsToEmpty() {
        GunDefinition gun = decode("""
                { "texture": "modularshoot:textures/gun/test.png" }
                """);
        assertEquals(Optional.empty(), gun.soundRange(),
                "sound_range omitted should default to empty");
        assertEquals(Optional.empty(), GunSounds.getRange(gun),
                "getRange should be empty when sound_range is omitted");
    }

    @Test
    void getRangeReturnsEmptyForDirectlyBuiltDefinition() {
        GunDefinition definition = definitionWithSounds(Map.of());
        assertEquals(Optional.empty(), GunSounds.getRange(definition),
                "definitions without sound_range expose an empty range");
    }
}
