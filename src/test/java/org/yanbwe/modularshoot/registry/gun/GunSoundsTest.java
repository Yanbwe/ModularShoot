package org.yanbwe.modularshoot.registry.gun;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GunSounds}: sound-slot lookup on a gun definition.
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
                Optional.empty());
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
}
